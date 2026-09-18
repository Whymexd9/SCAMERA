package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.opengl.GLES20;
import android.opengl.GLES30;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.LiveRawFrame;
import com.particlesdevs.photoncamera.processing.ToneCurveBuilder;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.FloatBuffer;

/**
 * Develops the preview RAW stream and draws it as the viewfinder.
 *
 * <p>The viewfinder used to show the ISP's own rendering, so what the user
 * framed and what the pipeline produced only agreed after processing. This
 * develops the same data the pipeline will: one fragment pass, no intermediate
 * targets, no readback - demosaic, levels, white balance, colour matrix, tone
 * mapping.
 *
 * <p>Exposure is measured from the frame just uploaded, on the CPU over a
 * strided sample of the RAW plane. The sample is a few thousand pixels, which
 * is enough for a global statistic and cheap enough to do per frame, and it
 * avoids a readback - the thing that limited the previous approach to four
 * updates a second.
 */
final class LiveRawRenderer {

    private int program = 0;
    private int rawTex = 0, shadingTex = 0, uShading;
    private boolean failed = false;
    private int uploadedVersion = -1;

    private int uRawTexture, uRawWidth, uRawHeight, uCfa, uBlack, uWhite,
            uGains, uMatrix, uGain, uWhitePoint, uKnee, uMirror, uTexRotate;

    /** Target mean of the response, matching AutoExposureCurve's default. */
    private static final float TARGET = 128.0f / 255.0f;
    private static final float GAIN_MAX = 9.0f;
    private static final int BINS = 128;
    private final int[] hist = new int[BINS];

    private float smoothedGain = 1.0f;
    private int drawCount = 0;

    boolean isReady() {
        return !failed;
    }

    /**
     * @return true when a frame was drawn; false means the caller should fall
     *         back to the ISP preview for this frame
     */
    boolean draw(FloatBuffer pVertex, FloatBuffer pTexCoord, float[] texRotate, boolean mirror) {
        if (failed) return false;
        LiveRawFrame.Frame frame = LiveRawFrame.acquire();
        if (frame == null || frame.width <= 0 || frame.height <= 0) return false;
        if (program == 0 && !init()) return false;

        GLES20.glUseProgram(program);
        // Unit 2, not 0. The external OES preview texture is bound to unit 0 for
        // the main program, and two samplers of different types on one unit is
        // undefined - the same trap that already rendered this viewfinder black
        // once, noted at the tone curve binding in MainRenderer.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rawTex);

        if (frame.version != uploadedVersion) {
            Log.d("LiveRawRenderer", "frame " + frame.version + " " + frame.width + "x"
                    + frame.height + " stride=" + frame.rowStride + " cfa=" + frame.cfaPattern
                    + " white=" + frame.whiteLevel + " gain=" + smoothedGain);
            // RAW16 as a single-channel unsigned integer texture: no filtering,
            // no normalisation, the shader sees the sensor's counts.
            frame.buffer.position(0);
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2);
            int rowPixels = frame.rowStride > 0 ? frame.rowStride / 2 : frame.width;
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, rowPixels);
            GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R16UI,
                    frame.width, frame.height, 0, GLES30.GL_RED_INTEGER,
                    GLES30.GL_UNSIGNED_SHORT, frame.buffer);
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0);
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4);
            int err = GLES20.glGetError();
            if (err != GLES20.GL_NO_ERROR) {
                // An upload that fails leaves the texture undefined, and
                // texelFetch on undefined contents is what the black and
                // rainbow frames were. Say so rather than drawing it.
                Log.e("LiveRawRenderer", "raw upload failed, glGetError=" + err
                        + " (needs an ES 3.0 context for R16UI)");
                failed = true;
                return false;
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,shadingTex);
            java.nio.FloatBuffer shadingBuffer=java.nio.ByteBuffer.allocateDirect(frame.shading.length*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            shadingBuffer.put(frame.shading).position(0);
            GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES30.GL_RGB16F,frame.shadingWidth,frame.shadingHeight,0,GLES20.GL_RGB,GLES20.GL_FLOAT,shadingBuffer);
            uploadedVersion = frame.version;
            updateExposure(frame);
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,shadingTex);GLES20.glUniform1i(uShading,3);
        GLES20.glUniform1i(uRawTexture, 2);
        GLES20.glUniform1i(uRawWidth, frame.width);
        GLES20.glUniform1i(uRawHeight, frame.height);
        GLES20.glUniform1i(uCfa, frame.cfaPattern);
        GLES20.glUniform4f(uBlack, frame.blackLevel[0], frame.blackLevel[1],
                frame.blackLevel[2], frame.blackLevel[3]);
        GLES20.glUniform1f(uWhite, frame.whiteLevel);
        GLES20.glUniform3f(uGains, frame.wbGains[0], frame.wbGains[1], frame.wbGains[2]);
        GLES20.glUniformMatrix3fv(uMatrix, 1, false, frame.colorTransform, 0);
        GLES20.glUniform1f(uGain, smoothedGain);
        GLES20.glUniform1f(uWhitePoint, Math.max(smoothedGain, 1.0f));
        GLES20.glUniform1f(uKnee, 0.85f);
        GLES20.glUniform1i(uMirror, mirror ? 1 : 0);
        GLES20.glUniformMatrix4fv(uTexRotate, 1, false, texRotate, 0);

        GLES20.glEnableVertexAttribArray(0);
        GLES20.glEnableVertexAttribArray(1);
        pVertex.position(0);
        pTexCoord.position(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, pVertex);
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 8, pTexCoord);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        // Do NOT disable the arrays: the main program binds its own attributes
        // once at init and leaves them enabled for the life of the renderer, and
        // on this device they resolve to locations 0 and 1 - the same ones used
        // here. Disabling them left the ISP path with no vertex data whenever
        // this path had drawn once.
        // Leave unit 0 selected: the caller's program expects it.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        if ((drawCount++ % 120) == 0) {
            Log.d("LiveRawRenderer", "drawing developed raw, frame " + frame.version
                    + " gain=" + smoothedGain);
        }
        return true;
    }

    /**
     * Measure the frame on the CPU over a strided sample.
     *
     * <p>Striding by 16 in each direction leaves a few thousand samples of a
     * 12 MP frame, which is ample for a mean - exposure is a global statistic.
     * The result is smoothed across frames because an unfiltered per-frame gain
     * makes the viewfinder pump on every small scene change.
     */
    private void updateExposure(LiveRawFrame.Frame frame) {
        java.util.Arrays.fill(hist, 0);
        int stridePixels = frame.rowStride > 0 ? frame.rowStride / 2 : frame.width;
        float bl = (frame.blackLevel[0] + frame.blackLevel[1]
                + frame.blackLevel[2] + frame.blackLevel[3]) * 0.25f;
        float range = Math.max(1.0f, frame.whiteLevel - bl);
        java.nio.ShortBuffer raw = frame.buffer.duplicate()
                .order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
        int count = 0;
        for (int y = 0; y < frame.height; y += 16) {
            int row = y * stridePixels;
            for (int x = 0; x < frame.width; x += 16) {
                int idx = row + x;
                if (idx >= raw.limit()) break;
                float v = ((raw.get(idx) & 0xFFFF) - bl) / range;
                // The histogram is built in the display domain because that is
                // where the curve's target sits.
                float enc = v <= 0.0031308f ? v * 12.92f
                        : 1.055f * (float) Math.pow(Math.max(v, 0.0f), 1.0f / 2.4f) - 0.055f;
                int bin = (int) (Math.min(Math.max(enc, 0.0f), 1.0f) * (BINS - 1));
                hist[bin]++;
                count++;
            }
        }
        if (count == 0) return;
        float gain = ToneCurveBuilder.gainFromHistogram(hist, TARGET, GAIN_MAX);
        // The Reinhard correction multiplies, so the clamp has to come after it
        // as well - it was producing gains above GAIN_MAX (10.4 in the last log)
        // and blowing the preview out.
        gain = Math.min(ToneCurveBuilder.normalizeGain(gain, BINS), GAIN_MAX);
        if (!Float.isFinite(gain) || gain <= 0.0f) return;
        // Exponential smoothing: fast enough to follow a pan, slow enough not to
        // flicker on noise.
        smoothedGain = smoothedGain * 0.8f + gain * 0.2f;
    }

    private boolean init() {
        String vs = PhotonCamera.getAssetLoader().getString("shaders/preview/rawdevelop_vs.glsl");
        String fs = PhotonCamera.getAssetLoader().getString("shaders/preview/rawdevelop_fs.glsl");
        if (vs == null || fs == null) {
            Log.e("LiveRawRenderer", "shader assets missing");
            failed = true;
            return false;
        }
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) {
            failed = true;
            return false;
        }
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, v);
        GLES20.glAttachShader(program, f);
        GLES20.glLinkProgram(program);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e("LiveRawRenderer", "link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
            failed = true;
            return false;
        }
        uRawTexture = GLES20.glGetUniformLocation(program, "rawTexture");
        uRawWidth = GLES20.glGetUniformLocation(program, "rawWidth");
        uRawHeight = GLES20.glGetUniformLocation(program, "rawHeight");
        uCfa = GLES20.glGetUniformLocation(program, "cfaPattern");
        uBlack = GLES20.glGetUniformLocation(program, "blackLevel");
        uWhite = GLES20.glGetUniformLocation(program, "whiteLevel");
        uGains = GLES20.glGetUniformLocation(program, "wbGains");
        uMatrix = GLES20.glGetUniformLocation(program, "colorTransform");
        uGain = GLES20.glGetUniformLocation(program, "exposureGain");
        uWhitePoint = GLES20.glGetUniformLocation(program, "whitePoint");
        uKnee = GLES20.glGetUniformLocation(program, "knee");
        uMirror = GLES20.glGetUniformLocation(program, "mirror");
        uTexRotate = GLES20.glGetUniformLocation(program, "texRotate");

        uShading=GLES20.glGetUniformLocation(program,"lensShading");
        int[] shade=new int[1];GLES20.glGenTextures(1,shade,0);shadingTex=shade[0];
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,shadingTex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        rawTex = t[0];
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rawTex);
        // Integer textures cannot be filtered; texelFetch is used throughout.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        Log.d("LiveRawRenderer", "raw viewfinder ready");
        return true;
    }

    private static int compile(int type, String src) {
        int sh = GLES20.glCreateShader(type);
        GLES20.glShaderSource(sh, src);
        GLES20.glCompileShader(sh);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e("LiveRawRenderer", "compile failed: " + GLES20.glGetShaderInfoLog(sh));
            GLES20.glDeleteShader(sh);
            return 0;
        }
        return sh;
    }
}
