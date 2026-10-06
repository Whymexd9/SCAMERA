package com.particlesdevs.photoncamera.processing.opengl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

import com.particlesdevs.photoncamera.util.Log;

/**
 * P27 any resolution: the GPU limits the post pipeline works within. Its textures, render targets and viewports are as wide as the
 * merged frame, so a frame side above {@link #maxSide()} cannot be developed. Queried once per process on a 1x1 pbuffer context of
 * the pipeline's own EGL config, then cached. The thread's current context is restored; eglInitialize / eglTerminate are paired
 * as in GLContext (Android counts them per display, the viewfinder's display stays). A failed query gives {@link #UNKNOWN}
 * (Integer.MAX_VALUE everywhere): no limit is applied, so nothing changes where the limits cannot be read.
 */
public final class GLLimits {
    private static final String TAG = "GLLimits";
    /** GLES 3.0 guarantees 2048 for every one of these; a smaller reading is broken and counts as unknown. */
    private static final int MIN_PLAUSIBLE = 2048;
    private static final int MAX_FAILED_QUERIES = 3;
    public static final GLLimits UNKNOWN = new GLLimits(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
            Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, false);

    /** GL_MAX_TEXTURE_SIZE, GL_MAX_RENDERBUFFER_SIZE, GL_MAX_VIEWPORT_DIMS, EGL_MAX_PBUFFER_WIDTH / HEIGHT (Integer.MAX_VALUE = unknown). */
    public final int maxTextureSize, maxRenderbufferSize, maxViewportWidth, maxViewportHeight, maxPbufferWidth, maxPbufferHeight;
    /** False for {@link #UNKNOWN}. */
    public final boolean measured;

    private static volatile GLLimits cached;
    private static volatile GLLimits testing;
    private static int failedQueries;

    private GLLimits(int texture, int renderbuffer, int viewportWidth, int viewportHeight, int pbufferWidth, int pbufferHeight,
                     boolean measured) {
        maxTextureSize = texture; maxRenderbufferSize = renderbuffer;
        maxViewportWidth = viewportWidth; maxViewportHeight = viewportHeight;
        maxPbufferWidth = pbufferWidth; maxPbufferHeight = pbufferHeight;
        this.measured = measured;
    }

    /** Known limits (tests, logs of a device). */
    public static GLLimits of(int texture, int renderbuffer, int viewportWidth, int viewportHeight) {
        return new GLLimits(texture, renderbuffer, viewportWidth, viewportHeight, Integer.MAX_VALUE, Integer.MAX_VALUE, true);
    }

    /** Unit tests: the limits {@link #get()} returns (null: query the GPU again). */
    public static void setForTesting(GLLimits limits) { testing = limits; }

    /**
     * Largest frame side the post pipeline can hold in one texture, render target and viewport. The pbuffer of a pipeline context
     * is not part of it: GLContext clamps it (it is never drawn to).
     */
    public int maxSide() {
        return Math.min(Math.min(maxTextureSize, maxRenderbufferSize), Math.min(maxViewportWidth, maxViewportHeight));
    }

    /** The limits of this device (queried at the first call). */
    public static GLLimits get() {
        final GLLimits forced = testing;
        if (forced != null) return forced;
        GLLimits limits = cached;
        if (limits != null) return limits;
        synchronized (GLLimits.class) {
            if (cached != null) return cached;
            limits = query();
            if (limits.measured || ++failedQueries >= MAX_FAILED_QUERIES) cached = limits;
            return limits;
        }
    }

    /** Bytes of a w x h buffer of {@code bytesPerPixel} as an int; IllegalStateException above one Java buffer (2 GB). */
    public static int bufferBytes(long w, long h, long bytesPerPixel, String what) {
        final long bytes = w * h * bytesPerPixel;
        if (w < 0 || h < 0 || bytesPerPixel < 0 || bytes > Integer.MAX_VALUE)
            throw new IllegalStateException(what + ": " + w + "x" + h + " x " + bytesPerPixel + " B = " + (bytes >> 20)
                    + " MB, above one 2 GB buffer");
        return (int) bytes;
    }

    private static int plausible(int value) {
        return value >= MIN_PLAUSIBLE ? value : Integer.MAX_VALUE;
    }

    private static boolean none(Object handle, Object noHandle) {
        return handle == null || handle.equals(noHandle);
    }

    private static GLLimits query() {
        final long started = System.nanoTime();
        EGLDisplay previousDisplay = null, display = null;
        EGLContext previousContext = null, context = null;
        EGLSurface previousDraw = null, previousRead = null, surface = null;
        boolean initialized = false;
        try {
            previousDisplay = EGL14.eglGetCurrentDisplay();
            previousContext = EGL14.eglGetCurrentContext();
            previousDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
            previousRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
            display = EGL14.eglGetDisplay(GLDrawParams.EGLDisplay);
            if (none(display, EGL14.EGL_NO_DISPLAY)) throw new IllegalStateException("no EGL display");
            final int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException("eglInitialize failed");
            initialized = true;
            final EGLConfig[] configs = new EGLConfig[1];
            final int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, GLDrawParams.attribList, 0, configs, 0, 1, count, 0) || count[0] < 1 || configs[0] == null)
                throw new IllegalStateException("no EGL config");
            final int[] value = new int[1];
            final int pbufferWidth = EGL14.eglGetConfigAttrib(display, configs[0], EGL14.EGL_MAX_PBUFFER_WIDTH, value, 0) ? value[0] : 0;
            final int pbufferHeight = EGL14.eglGetConfigAttrib(display, configs[0], EGL14.EGL_MAX_PBUFFER_HEIGHT, value, 0) ? value[0] : 0;
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, GLDrawParams.contextAttributeList, 0);
            if (none(context, EGL14.EGL_NO_CONTEXT)) throw new IllegalStateException("no GL context");
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (none(surface, EGL14.EGL_NO_SURFACE)) throw new IllegalStateException("no pbuffer");
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw new IllegalStateException("eglMakeCurrent failed");
            final int[] texture = new int[1], renderbuffer = new int[1], viewport = new int[2];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, texture, 0);
            GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE, renderbuffer, 0);
            GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, viewport, 0);
            final GLLimits limits = new GLLimits(plausible(texture[0]), plausible(renderbuffer[0]), plausible(viewport[0]),
                    plausible(viewport[1]), plausible(pbufferWidth), plausible(pbufferHeight), true);
            Log.i(TAG, "GPU limits: texture=" + texture[0] + " renderbuffer=" + renderbuffer[0] + " viewport=" + viewport[0] + "x"
                    + viewport[1] + " pbuffer=" + pbufferWidth + "x" + pbufferHeight + " -> max frame side " + limits.maxSide()
                    + " (" + (System.nanoTime() - started) / 1_000_000 + " ms)");
            return limits;
        } catch (Throwable t) {
            Log.w(TAG, "GPU limits unknown (" + t + "): no limit applied");
            return UNKNOWN;
        } finally {
            try {
                if (!none(display, EGL14.EGL_NO_DISPLAY)) {
                    if (!none(previousContext, EGL14.EGL_NO_CONTEXT) && !none(previousDisplay, EGL14.EGL_NO_DISPLAY))
                        EGL14.eglMakeCurrent(previousDisplay, previousDraw, previousRead, previousContext);
                    else
                        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (!none(surface, EGL14.EGL_NO_SURFACE)) EGL14.eglDestroySurface(display, surface);
                    if (!none(context, EGL14.EGL_NO_CONTEXT)) EGL14.eglDestroyContext(display, context);
                    if (initialized) EGL14.eglTerminate(display);
                }
            } catch (Throwable ignored) {
                // the limits are read; a failed clean-up costs one 1x1 pbuffer
            }
        }
    }

    @Override public String toString() {
        return measured ? "texture " + maxTextureSize + ", renderbuffer " + maxRenderbufferSize + ", viewport " + maxViewportWidth
                + "x" + maxViewportHeight + ", max side " + maxSide() : "unknown";
    }
}
