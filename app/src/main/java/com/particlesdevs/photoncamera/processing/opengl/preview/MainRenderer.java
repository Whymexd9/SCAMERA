package com.particlesdevs.photoncamera.processing.opengl.preview;

import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.processing.opengl.GLProg;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    private final String vss_default =
            "in vec2 vPosition;\n" +
                    "in vec2 vTexCoord;\n" +
                    "uniform mat4 uTexRotateMatrix;\n" +
                    "void main() {\n" +
                    "  gl_Position = uTexRotateMatrix * vec4 ( vPosition.x, vPosition.y, 0.0, 1.0 );\n" +
                    "}";

    private final String fss_default =
            "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                    "precision mediump float;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    //"uniform ivec2 outSize;" +
                    "uniform int yOffset;" +
                    "uniform sampler2D uToneCurve;" +
                    "uniform int uLookEnabled;" +
                    "out vec4 Output;" +
                    "void main() {\n" +
                    "  vec2 texSize = vec2(textureSize(sTexture, 0));" +
                    "  vec2 posScaled = (vec2(gl_FragCoord.xy)+vec2(0,yOffset));" +
                    "  vec2 pos = posScaled/texSize;" +
                    "  pos.y = 1.0-pos.y;" +
                    "  vec4 c = texture(sTexture,pos);\n" +
                    // Live look: replay the tone curve of the last processed shot
                    // so the viewfinder shows the tonemapping, shadow and
                    // highlight placement the saved photo will get. Per channel,
                    // so a curve that lifts shadows lifts them here too.
                    // Detail is another matter: merged denoise and MFSR need a
                    // burst and cannot appear in a live frame.
                    "  if (uLookEnabled == 1) {\n" +
                    "    c.r = texture(uToneCurve, vec2(clamp(c.r, 0.0, 1.0), 0.5)).r;\n" +
                    "    c.g = texture(uToneCurve, vec2(clamp(c.g, 0.0, 1.0), 0.5)).r;\n" +
                    "    c.b = texture(uToneCurve, vec2(clamp(c.b, 0.0, 1.0), 0.5)).r;\n" +
                    "  }\n" +
                    "  Output = c;\n" +
                    //"  if(pos.x > 1.0 || pos.x < 0.0) Output = vec4(0.0);" +
                    //"  if(pos.y > 1.0 || pos.y < 0.0) Output = vec4(0.0);" +
                    "}";

    private int[] hTex;
    private final FloatBuffer pVertex;
    private final FloatBuffer pTexCoord;
    private int hProgram;

    // Written on the GL thread (surface creation) and read from the frame
    // callback thread; volatile keeps onFrameAvailable() consistent with it.
    private volatile SurfaceTexture mSTexture;

    private GLProg glProg;
    private boolean mGLInit = false;
    private boolean mUpdateST = false;

    /** 1D tone curve of the last processed shot, uploaded lazily on the GL thread. */
    private final int[] mCurveTex = new int[1];
    private int mCurveVersion = -1;
    private boolean mCurveReady = false;

    private final GLPreview mView;

    MainRenderer(GLPreview view) {
        mView = view;
        float[] vtmp = {1.0f, -1.0f, -1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f};
        float[] ttmp = {1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f};
        pVertex = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        pVertex.put(vtmp);
        pVertex.position(0);
        pTexCoord = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        pTexCoord.put(ttmp);
        pTexCoord.position(0);
    }


    /**
     * Upload the published tone curve if it has changed. Runs on the GL thread;
     * the version check keeps this to a cheap integer compare on most frames.
     */
    private void updateToneCurve() {
        int v = com.particlesdevs.photoncamera.processing.PreviewLook.getVersion();
        if (v == mCurveVersion) return;
        float[] curve = com.particlesdevs.photoncamera.processing.PreviewLook.getToneCurve();
        mCurveVersion = v;
        if (curve == null || curve.length == 0) {
            mCurveReady = false;
            return;
        }
        if (mCurveTex[0] == 0) {
            GLES20.glGenTextures(1, mCurveTex, 0);
        }
        java.nio.FloatBuffer buf = ByteBuffer.allocateDirect(curve.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(curve);
        buf.position(0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mCurveTex[0]);
        // R32F: one channel is all a tone curve needs, and linear filtering
        // between the 1024 samples hides the quantisation.
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R32F,
                curve.length, 1, 0, GLES30.GL_RED, GLES20.GL_FLOAT, buf);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        mCurveReady = true;
    }

    public void onDrawFrame(GL10 unused) {
        if (!mGLInit) return;
        //GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        synchronized (this) {
            if (mUpdateST) {
                mSTexture.updateTexImage();
                mUpdateST = false;
            }
        }

        updateToneCurve();
        boolean lookOn = mCurveReady
                && com.particlesdevs.photoncamera.settings.PreferenceKeys.isLiveViewfinderLookEnabled();
        GLES20.glUniform1i(GLES20.glGetUniformLocation(hProgram, "uLookEnabled"), lookOn ? 1 : 0);
        if (lookOn) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mCurveTex[0]);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(hProgram, "uToneCurve"), 1);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        //GLES20.glFlush();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // A fresh GL surface invalidates the previous SurfaceTexture: its buffer
        // queue is bound to the destroyed EGL context, and the camera already
        // re-targets a new one. Release both to avoid leaking a buffer queue on
        // every background/foreground cycle.
        if (mSTexture != null) {
            mSTexture.release();
            mSTexture = null;
        }
        if (hTex != null && hTex[0] != 0) {
            GLES20.glDeleteTextures(1, hTex, 0);
        }
        mUpdateST = false;
        initTex();
        mSTexture = new SurfaceTexture(hTex[0]);
        mSTexture.setOnFrameAvailableListener(this);

        hProgram = loadShader(vss_default, fss_default);
        GLES20.glUseProgram(hProgram);
        int trmh = GLES20.glGetUniformLocation(hProgram, "uTexRotateMatrix");
        GLES20.glUniformMatrix4fv(trmh, 1, false, mTexRotateMatrix, 0);
        int ph = GLES20.glGetAttribLocation(hProgram, "vPosition");
        int tch = GLES20.glGetAttribLocation(hProgram, "vTexCoord");
        GLES20.glVertexAttribPointer(ph, 2, GLES20.GL_FLOAT, false, 4 * 2, pVertex);
        GLES20.glVertexAttribPointer(tch, 2, GLES20.GL_FLOAT, false, 4 * 2, pTexCoord);
        GLES20.glEnableVertexAttribArray(ph);
        GLES20.glEnableVertexAttribArray(tch);
        mGLInit = true;
        mView.fireOnSurfaceTextureAvailable(mSTexture, 0, 0);
    }

    public void onSurfaceChanged(GL10 unused, int width, int height) {
        GLES30.glViewport(0, 0, width, height);
    }

    private final float[] mTexRotateMatrix = new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    public SurfaceTexture getmSTexture() {
        return mSTexture;
    }

    private void initTex() {
        hTex = new int[1];
        GLES20.glGenTextures(1, hTex, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, hTex[0]);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    }

    public synchronized void onFrameAvailable(SurfaceTexture st) {
        if (st != mSTexture) {
            // Frame from a SurfaceTexture the renderer has already replaced.
            return;
        }
        mUpdateST = true;
        mView.requestRender();
    }

    private static String GetSupportedVersion() {
        return "#version 300 es";
    }

    private static int loadShader(String vss, String fss) {
        String SupportedVersion = GetSupportedVersion();
        vss = SupportedVersion + "\n #line 1\n" + vss;
        fss = SupportedVersion + "\n #line 1\n" + fss;
        int vshader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vshader, vss);
        GLES20.glCompileShader(vshader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(vshader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("Shader", "Could not compile vshader");
            Log.v("Shader", "Could not compile vshader:" + GLES20.glGetShaderInfoLog(vshader));
            GLES20.glDeleteShader(vshader);
            vshader = 0;
        }

        int fshader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fshader, fss);
        GLES20.glCompileShader(fshader);
        GLES20.glGetShaderiv(fshader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("Shader", "Could not compile fshader");
            Log.v("Shader", "Could not compile fshader:" + GLES20.glGetShaderInfoLog(fshader));
            GLES20.glDeleteShader(fshader);
            fshader = 0;
        }

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vshader);
        GLES20.glAttachShader(program, fshader);
        GLES20.glLinkProgram(program);

        return program;
    }

    public void setOrientation(int or) {
        android.opengl.Matrix.setRotateM(mTexRotateMatrix, 0, or, 0f, 0f, 1f);
    }

    RectF mLastImageRect = new RectF();
    RectF inputRect = new RectF();

    public void scale(int in_width, int in_height, int out_width, int out_height, int rotation) {
        int difw = out_width - in_width;
        int difh = out_height - in_height;

        inputRect.left = (int) (difw / 2);
        inputRect.top = (int) (difh / 2);
        inputRect.right = in_width;
        inputRect.bottom = in_height;
        if (mLastImageRect != inputRect) {
            GLES20.glViewport((int) inputRect.left, (int) inputRect.top, (int) inputRect.width(), (int) inputRect.height());

            mLastImageRect.set(inputRect);
        }

    }
}
