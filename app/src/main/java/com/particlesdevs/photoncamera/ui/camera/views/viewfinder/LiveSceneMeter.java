package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;

import com.particlesdevs.photoncamera.processing.PreviewLook;
import com.particlesdevs.photoncamera.processing.ToneCurveBuilder;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Measures the live stream on the GPU and publishes the exposure curve derived
 * from it, so the viewfinder shows the shot's tone placement before the shutter
 * rather than after.
 *
 * <p>The curve used to come only from AutoExposureCurve, which runs once per
 * shot - which is why the viewfinder changed to match only after processing had
 * finished. This runs the measuring half continuously: the external preview
 * texture is rendered into a small offscreen target, read back once, turned
 * into a histogram, and the same response {@link ToneCurveBuilder} bakes for the
 * shot is built from it.
 *
 * <p>What it cannot show is anything that needs the burst - merged noise
 * reduction, MFSR detail, highlight reconstruction from the bracket. Those are
 * absent from the viewfinder by construction, so the photo stays cleaner and
 * more detailed than the preview. The tone placement matches; the detail does
 * not.
 *
 * <p>Cost is bounded by measuring at {@link #TILE} squared and only every
 * {@link #EVERY_N_FRAMES} frames: the readback is the expensive part because it
 * stalls the GL thread until the render completes, and a 64x64 RGBA readback is
 * 16 KB.
 */
final class LiveSceneMeter {

    /** Measurement resolution. Tone placement is a global statistic; it does not need pixels. */
    private static final int TILE = 64;
    /** Frames between measurements. At 30 fps this is about four updates a second. */
    private static final int EVERY_N_FRAMES = 8;
    private static final int BINS = 128;

    private static final String VS =
            "attribute vec2 vPosition;\n" +
            "attribute vec2 vTexCoord;\n" +
            "varying vec2 texCoord;\n" +
            "void main() {\n" +
            "  texCoord = vTexCoord;\n" +
            "  gl_Position = vec4(vPosition, 0.0, 1.0);\n" +
            "}\n";

    /**
     * Averages a block of the preview per output texel. The preview stream is
     * already display-encoded, which is the domain the curve is built in, so no
     * conversion is needed here - only the luminance.
     */
    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "varying vec2 texCoord;\n" +
            "void main() {\n" +
            "  vec3 c = texture2D(sTexture, texCoord).rgb;\n" +
            "  float y = dot(c, vec3(0.2126, 0.7152, 0.0722));\n" +
            "  gl_FragColor = vec4(y, y, y, 1.0);\n" +
            "}\n";

    private int program = 0;
    private int fbo = 0;
    private int tex = 0;
    private int aPosition, aTexCoord, uTexture;
    private boolean failed = false;
    private int frameCounter = 0;

    private final int[] hist = new int[BINS];
    private final ByteBuffer readback =
            ByteBuffer.allocateDirect(TILE * TILE * 4).order(ByteOrder.nativeOrder());

    /** Target mean of the response; AutoExposureCurve's default target is 128/255. */
    private static final float TARGET = 128.0f / 255.0f;
    private static final float GAIN_MAX = 9.0f;
    private static final float GAMMA_MIX = 0.1f;

    /**
     * Called once per drawn frame with the vertex buffers already prepared by
     * the renderer. Does nothing on most frames; the caller's GL state is
     * restored before returning.
     */
    void measure(java.nio.FloatBuffer pVertex, java.nio.FloatBuffer pTexCoord,
                 int previewTexture) {
        if (failed) return;
        if (++frameCounter < EVERY_N_FRAMES) return;
        frameCounter = 0;

        if (program == 0 && !init()) return;

        int[] prevFbo = new int[1];
        int[] prevViewport = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, TILE, TILE);
        GLES20.glUseProgram(program);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, previewTexture);
        GLES20.glUniform1i(uTexture, 0);

        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        pVertex.position(0);
        pTexCoord.position(0);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 8, pVertex);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 8, pTexCoord);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);

        readback.position(0);
        GLES20.glReadPixels(0, 0, TILE, TILE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0]);
        GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);

        publish();
    }

    private void publish() {
        java.util.Arrays.fill(hist, 0);
        readback.position(0);
        for (int i = 0; i < TILE * TILE; i++) {
            int y = readback.get(i * 4) & 0xFF;
            hist[y * (BINS - 1) / 255]++;
        }
        float mpy = ToneCurveBuilder.gainFromHistogram(hist, TARGET, GAIN_MAX);
        mpy = ToneCurveBuilder.normalizeGain(mpy, BINS);
        if (!Float.isFinite(mpy) || mpy <= 0.0f) return;
        // No white point search here: it needs the over-range detail only the
        // raw path has. The response uses the gain as its own white point, which
        // is what AutoExposureCurve falls back to when the search is disabled.
        float[] curve = ToneCurveBuilder.build(PreviewLook.CURVE_SIZE, mpy, mpy, GAMMA_MIX, 1.0f);
        PreviewLook.setToneCurve(curve);
    }

    private boolean init() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VS);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
        if (vs == 0 || fs == 0) {
            failed = true;
            return false;
        }
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glBindAttribLocation(program, 0, "vPosition");
        GLES20.glBindAttribLocation(program, 1, "vTexCoord");
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Log.e("LiveSceneMeter", "link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
            failed = true;
            return false;
        }
        aPosition = GLES20.glGetAttribLocation(program, "vPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "vTexCoord");
        uTexture = GLES20.glGetUniformLocation(program, "sTexture");

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        tex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, TILE, TILE, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        fbo = f[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tex, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e("LiveSceneMeter", "incomplete framebuffer: " + status);
            failed = true;
            return false;
        }
        Log.d("LiveSceneMeter", "measuring at " + TILE + "x" + TILE
                + " every " + EVERY_N_FRAMES + " frames");
        return true;
    }

    private static int compile(int type, String src) {
        int sh = GLES20.glCreateShader(type);
        GLES20.glShaderSource(sh, src);
        GLES20.glCompileShader(sh);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e("LiveSceneMeter", "compile failed: " + GLES20.glGetShaderInfoLog(sh));
            GLES20.glDeleteShader(sh);
            return 0;
        }
        return sh;
    }
}
