package com.particlesdevs.photoncamera.processing.opengl;

import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;

import static android.opengl.EGL14.EGL_HEIGHT;
import static android.opengl.EGL14.EGL_MAX_PBUFFER_HEIGHT;
import static android.opengl.EGL14.EGL_MAX_PBUFFER_WIDTH;
import static android.opengl.EGL14.EGL_NONE;
import static android.opengl.EGL14.EGL_NO_CONTEXT;
import static android.opengl.EGL14.EGL_NO_SURFACE;
import static android.opengl.EGL14.EGL_WIDTH;
import static android.opengl.EGL14.eglChooseConfig;
import static android.opengl.EGL14.eglCreateContext;
import static android.opengl.EGL14.eglCreatePbufferSurface;
import static android.opengl.EGL14.eglDestroyContext;
import static android.opengl.EGL14.eglDestroySurface;
import static android.opengl.EGL14.eglGetConfigAttrib;
import static android.opengl.EGL14.eglGetDisplay;
import static android.opengl.EGL14.eglInitialize;
import static android.opengl.EGL14.eglMakeCurrent;
import static android.opengl.EGL14.eglTerminate;
import static android.opengl.GLES20.GL_COLOR_ATTACHMENT0;
import static android.opengl.GLES20.GL_RENDERBUFFER;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glBindRenderbuffer;
import static android.opengl.GLES20.glFramebufferRenderbuffer;
import static android.opengl.GLES20.glGenFramebuffers;
import static android.opengl.GLES20.glGenRenderbuffers;
import static android.opengl.GLES20.glRenderbufferStorage;
import static android.opengl.GLES30.GL_DRAW_FRAMEBUFFER;
import static android.opengl.GLES30.GL_RGBA8;

public class GLContext implements AutoCloseable {
    private EGLDisplay mDisplay;
    private EGLContext mContext;
    private EGLSurface mSurface;
    public GLProg mProgram;
    public final int[] bindFB = new int[1];
    public final int[] bindRB = new int[1];

    public GLContext(int surfaceWidth, int surfaceHeight) {
        createContext(surfaceWidth,surfaceHeight);

    }
    public void createContext(int surfaceWidth, int surfaceHeight){
        int[] major = new int[2];
        int[] minor = new int[2];
        mDisplay = eglGetDisplay(GLDrawParams.EGLDisplay);
        eglInitialize(mDisplay, major, 0, minor, 0);
        int[] numConfig = new int[1];
        if (!eglChooseConfig(mDisplay, GLDrawParams.attribList, 0,
                null, 0, 0, numConfig, 0)
                || numConfig[0] == 0) {
            throw new RuntimeException("OpenGL config count zero");
        }
        int configSize = numConfig[0];
        EGLConfig[] configs = new EGLConfig[configSize];
        if (!eglChooseConfig(mDisplay, GLDrawParams.attribList, 0,
                configs, 0, configSize, numConfig, 0)) {
            throw new RuntimeException("OpenGL config loading failed");
        }
        if (configs[0] == null) {
            throw new RuntimeException("OpenGL config is null");
        }
        mContext = createProcessingContext(mDisplay, configs[0]);
        // P27 any resolution: every pass renders into textures / renderbuffers (FBOs), the pbuffer is never drawn to; it only has
        // to exist. A frame wider than EGL_MAX_PBUFFER_* gets a pbuffer at that limit instead of none (unchanged below it).
        final int[] limit = new int[1];
        if (eglGetConfigAttrib(mDisplay, configs[0], EGL_MAX_PBUFFER_WIDTH, limit, 0) && limit[0] > 0 && surfaceWidth > limit[0])
            surfaceWidth = limit[0];
        if (eglGetConfigAttrib(mDisplay, configs[0], EGL_MAX_PBUFFER_HEIGHT, limit, 0) && limit[0] > 0 && surfaceHeight > limit[0])
            surfaceHeight = limit[0];
        mSurface = eglCreatePbufferSurface(mDisplay, configs[0], new int[]{
                EGL_WIDTH, surfaceWidth,
                EGL_HEIGHT, surfaceHeight,
                EGL_NONE
        }, 0);
        eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);
        mProgram = new GLProg();
    }

    /**
     * Makes this context current on the calling thread again (W1.3: the deferred teardown after the JPEG encode must delete
     * the textures of this context even if another one was made current in between).
     */
    public void makeCurrent() {
        if (mDisplay != null && mContext != null) eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);
    }

    @Override
    public void close() {
        if (mDisplay == null) return;
        try {
            if (mProgram != null) mProgram.close();
        } catch (Exception ignored) {}
        try {
            eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        } catch (Exception ignored) {}
        try {
            if (mContext != null) eglDestroyContext(mDisplay, mContext);
        } catch (Exception ignored) {}
        try {
            if (mSurface != null) eglDestroySurface(mDisplay, mSurface);
        } catch (Exception ignored) {}
        try {
            eglTerminate(mDisplay);
        } catch (Exception ignored) {}
        mDisplay = null;
        mContext = null;
        mSurface = null;
    }

    /**
     * P57: the processing context at low GPU priority (EGL_IMG_context_priority), so the GPU preempts the post pipeline for the
     * viewfinder's draws; a driver without the extension, or one that refuses the hint, gets the context as before. nice_dev.txt
     * "gpu_low_priority 0" keeps the normal priority. Rendering results are unchanged.
     */
    private static android.opengl.EGLContext createProcessingContext(android.opengl.EGLDisplay display, EGLConfig config) {
        final String ext = android.opengl.EGL14.eglQueryString(display, android.opengl.EGL14.EGL_EXTENSIONS);
        final boolean low = ext != null && ext.contains("EGL_IMG_context_priority")
                && com.particlesdevs.photoncamera.settings.PreferenceKeys.niceDevNumber("gpu_low_priority", 1f) != 0f;
        if (low) {
            android.opengl.EGLContext c = eglCreateContext(display, config, EGL_NO_CONTEXT, new int[]{
                    android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_LOW_IMG,
                    android.opengl.EGL14.EGL_NONE}, 0);
            if (c != null && c != EGL_NO_CONTEXT) return c;
            android.opengl.EGL14.eglGetError();
        }
        return eglCreateContext(display, config, EGL_NO_CONTEXT, GLDrawParams.contextAttributeList, 0);
    }

    static final int EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100, EGL_CONTEXT_PRIORITY_LOW_IMG = 0x3103;
}
