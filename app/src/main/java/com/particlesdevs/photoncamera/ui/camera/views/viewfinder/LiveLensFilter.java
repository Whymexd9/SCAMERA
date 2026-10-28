package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.SystemClock;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.color.LensFilters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P228: the chosen lens filter on the ISP viewfinder (shaders/preview/lensfilter_live.glsl, spliced into main_fs.glsl): its
 * uniforms from the settings - the filter, its strength and tune - read again at most twice a second on the GL thread. No filter:
 * uLf 0, the viewfinder as before. (The developed-RAW viewfinder demosaics per pixel: its frames show no filter.)
 * Owner, 2026-10-18 («не видна работа лута в видоискателе»): the film LUT on the ISP viewfinder too (it was on the developed-RAW
 * one only) - read off the GL thread, uploaded as the RAW viewfinder does (FilmLut.upload), texture unit 1.
 * Owner, 2026-10-18 (the filter showed copies of the bright parts): a filter with a glow, a veil, streaks or a ghost first draws
 * the frame at half the viewport's size into two textures (lensfilter_prep.glsl: the light above the knee, the linear frame),
 * whose mip levels are the blur pyramid the main pass samples (units 2 and 3) - the photo's pyramid, not rings of single taps.
 * Owner, 2026-10-18: the diffusion filters are the photo's physical model (DiffusionModel) here too - the frame's weight and
 * its levels' per-channel weights fitted for this viewport's size and the mip pyramid's widths, refitted when the size, the
 * filter or its settings change.
 */
final class LiveLensFilter {
    static final String MARKER = "//#lensfilter_live";
    static final long REFRESH_MS = 500;
    /** The values' count ({@link #fill}). */
    static final int VALUES = 20;
    static final int UNIT_HI = 2, UNIT_LIN = 3;

    private static final ExecutorService LUT_LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vf-lut");
        t.setDaemon(true);
        return t;
    });
    private int uLf, uStrength, uParam, uTint, uW0, uW, uLevels, uStreak, uSegments;
    /** The diffusion's weights for the current filter and viewport ({@link #weights}), the key they were fitted for. */
    private float[][] diffusion;
    private String diffusionKey = "";
    private String filterId = "";
    private float filterStrength;
    private LensFilters.Tune filterTune;
    private int uHiTex, uLinTex, uSqrt, uView;
    private int uLut, uLutOn, uLutStrength, uLutSize, uLutMin, uLutScale;
    private int lutTex, dummyTex;
    private volatile String lutWanted = "";
    private volatile FilmLut lutReady;
    private FilmLut lutShown;
    private float lutStrength;
    private long readAt = -REFRESH_MS;
    private final float[] values = new float[VALUES];
    private int filter;
    /** False: the developed-RAW viewfinder's filter (P300) - its frame carries the LUT already (rawdevelop_fs), none here. */
    private final boolean ownLut;

    LiveLensFilter() {
        this(true);
    }

    LiveLensFilter(boolean ownLut) {
        this.ownLut = ownLut;
    }

    /** P300: the settings ask for a filter (read at most twice a second). */
    boolean active() {
        refresh();
        return filter != 0;
    }

    /**
     * P300 (owner, 2026-10-20: «исправь лайв превью для ISZ»): a viewfinder shader of the external camera texture as one of a
     * plain 2D texture (the developed-RAW frame drawn off screen first).
     */
    static String to2d(String shader) {
        return shader.replace("#extension GL_OES_EGL_image_external_essl3 : require", "").replace("samplerExternalOES", "sampler2D");
    }
    // the pyramid pass
    private int prep, pPosition, pTexCoord, pMatrix, pMirror, pGain, pKnee, pSqrt;
    private int fbo, hiTex, linTex, fboW, fboH;
    private boolean sqrt, floatFailed, pyramid;
    private final int[] viewport = new int[4];

    /** main_fs.glsl with the live filter's code in place of its marker. */
    static String splice(String main, String filter) {
        return main.replace(MARKER, filter);
    }

    /** Filters whose look needs the blur pyramid (all but GND, Light Pollution, Kaleidoscope and Infrared). */
    static boolean needsPyramid(int index) {
        return index > 0 && index != 7 && index != 8 && index != 11 && index != 12;
    }

    /** The pyramid's size for a viewport: half of it, at least 1. */
    static int half(int size) {
        return Math.max(1, (size + 1) / 2);
    }

    /** Mip levels of a w x h texture down to 1 x 1. */
    static int levels(int w, int h) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(w, h)));
    }

    void locate(int program) {
        uLf = GLES20.glGetUniformLocation(program, "uLf");
        uStrength = GLES20.glGetUniformLocation(program, "uLfStrength");
        uParam = GLES20.glGetUniformLocation(program, "uLfParam");
        uW0 = GLES20.glGetUniformLocation(program, "uLfW0");
        uW = GLES20.glGetUniformLocation(program, "uLfW");
        uLevels = GLES20.glGetUniformLocation(program, "uLfLevels");
        uTint = GLES20.glGetUniformLocation(program, "uLfTint");
        uStreak = GLES20.glGetUniformLocation(program, "uLfStreak");
        uSegments = GLES20.glGetUniformLocation(program, "uLfSegments");
        uHiTex = GLES20.glGetUniformLocation(program, "uLfHiTex");
        uLinTex = GLES20.glGetUniformLocation(program, "uLfLinTex");
        uSqrt = GLES20.glGetUniformLocation(program, "uLfSqrt");
        uView = GLES20.glGetUniformLocation(program, "uLfView");
        uLut = GLES20.glGetUniformLocation(program, "uLut");
        uLutOn = GLES20.glGetUniformLocation(program, "uLutOn");
        uLutStrength = GLES20.glGetUniformLocation(program, "uLutStrength");
        uLutSize = GLES20.glGetUniformLocation(program, "uLutSize");
        uLutMin = GLES20.glGetUniformLocation(program, "uLutMin");
        uLutScale = GLES20.glGetUniformLocation(program, "uLutScale");
        // a new GL context: the old textures, framebuffer and program are gone with it
        lutTex = 0;
        lutShown = null;
        fbo = hiTex = linTex = fboW = fboH = 0;
        prep = 0;
        floatFailed = false;
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        dummyTex = id[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, dummyTex);
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, 1, 1, 1, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4));
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0);
        readAt = -REFRESH_MS;
    }

    /** The pyramid pass's program (main_vs + lensfilter_prep), built with the main program; 0 when it failed. */
    void locatePrep(int program) {
        prep = program;
        if (program == 0) return;
        pPosition = GLES20.glGetAttribLocation(program, "vPosition");
        pTexCoord = GLES20.glGetAttribLocation(program, "vTexCoord");
        pMatrix = GLES20.glGetUniformLocation(program, "uTexRotateMatrix");
        pMirror = GLES20.glGetUniformLocation(program, "mirror");
        pGain = GLES20.glGetUniformLocation(program, "uGain");
        pKnee = GLES20.glGetUniformLocation(program, "uLfKnee");
        pSqrt = GLES20.glGetUniformLocation(program, "uLfSqrt");
    }

    private void refresh() {
        long now = SystemClock.uptimeMillis();
        if (now - readAt >= REFRESH_MS) {
            readAt = now;
            read();
        }
    }

    /**
     * Before the main pass (GL thread, ISP path): the blur pyramid of this frame when the filter needs one - the frame drawn at
     * half the viewport's size into the two textures, their mip levels made. The viewport and the default framebuffer are put
     * back; the caller binds its own program and textures afterwards.
     */
    void prepass(FloatBuffer vertex, FloatBuffer texCoord, float[] matrix, int oesTexture, boolean mirror, float gain) {
        prepass(vertex, texCoord, matrix, oesTexture, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mirror, gain);
    }

    /** The same of a frame in {@code target} (P300: the developed-RAW frame, GL_TEXTURE_2D). */
    void prepass(FloatBuffer vertex, FloatBuffer texCoord, float[] matrix, int texture, int target, boolean mirror, float gain) {
        refresh();
        pyramid = false;
        if (prep == 0 || !needsPyramid(filter)) return;
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0);
        int w = half(viewport[2]), h = half(viewport[3]);
        if (!targets(w, h)) return;
        if (filter >= 1 && filter <= 6) {
            String key = filterId + "|" + filterStrength + "|" + (filterTune == null ? "" : filterTune.serialize()) + "|" + viewport[2] + "x" + viewport[3];
            if (!key.equals(diffusionKey)) {
                diffusionKey = key;
                diffusion = weights(filterId, filterStrength, filterTune, Math.max(viewport[2], viewport[3]), levels(w, h));
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, w, h);
        GLES20.glUseProgram(prep);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(target, texture);
        GLES20.glUniformMatrix4fv(pMatrix, 1, false, matrix, 0);
        GLES20.glUniform1i(pMirror, mirror ? 1 : 0);
        GLES20.glUniform1f(pGain, gain);
        GLES20.glUniform1f(pKnee, values[2]);
        GLES20.glUniform1i(pSqrt, sqrt ? 1 : 0);
        vertex.position(0);
        texCoord.position(0);
        GLES20.glEnableVertexAttribArray(pPosition);
        GLES20.glVertexAttribPointer(pPosition, 2, GLES20.GL_FLOAT, false, 4 * 2, vertex);
        if (pTexCoord >= 0) {   // P300: the 2D pass's vertex shader takes the position only
            GLES20.glEnableVertexAttribArray(pTexCoord);
            GLES20.glVertexAttribPointer(pTexCoord, 2, GLES20.GL_FLOAT, false, 4 * 2, texCoord);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pPosition);
        if (pTexCoord >= 0) GLES20.glDisableVertexAttribArray(pTexCoord);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hiTex);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, linTex);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        pyramid = true;
    }

    /** The framebuffer with the two w x h mipmapped textures: half floats, or 8 bits (square roots) where those cannot be drawn to. */
    private boolean targets(int w, int h) {
        if (fbo != 0 && w == fboW && h == fboH) return true;
        release();
        for (int attempt = floatFailed ? 1 : 0; attempt < 2; attempt++) {
            boolean half = attempt == 0;
            int[] t = new int[2];
            GLES20.glGenTextures(2, t, 0);
            for (int tex : t) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
                GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, levels(w, h), half ? GLES30.GL_RGBA16F : GLES30.GL_RGBA8, w, h);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            int[] f = new int[1];
            GLES30.glGenFramebuffers(1, f, 0);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0]);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT1, GLES20.GL_TEXTURE_2D, t[1], 0);
            GLES30.glDrawBuffers(2, new int[]{GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1}, 0);
            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            if (status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                fbo = f[0];
                hiTex = t[0];
                linTex = t[1];
                fboW = w;
                fboH = h;
                sqrt = !half;
                Log.d("LiveLensFilter", "pyramid " + w + "x" + h + (half ? " half float" : " 8 bit"));
                return true;
            }
            GLES30.glDeleteFramebuffers(1, f, 0);
            GLES20.glDeleteTextures(2, t, 0);
            if (half) floatFailed = true;
            Log.w("LiveLensFilter", "pyramid framebuffer " + (half ? "RGBA16F" : "RGBA8") + " incomplete: 0x" + Integer.toHexString(status));
        }
        return false;
    }

    private void release() {
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, new int[]{fbo}, 0);
        if (hiTex != 0) GLES20.glDeleteTextures(2, new int[]{hiTex, linTex}, 0);
        fbo = hiTex = linTex = fboW = fboH = 0;
    }

    /** The uniforms of the current settings (the program in use). */
    void apply() {
        refresh();
        applyLut();
        // the pyramid's samplers on their own units always (unit 0 is the external frame: two sampler types on one unit fail)
        GLES20.glUniform1i(uHiTex, UNIT_HI);
        GLES20.glUniform1i(uLinTex, UNIT_LIN);
        // a filter that needs the pyramid shows nothing without it (no framebuffer on this GPU): the plain frame then
        int shown = needsPyramid(filter) && !pyramid ? 0 : filter;
        GLES20.glUniform1i(uLf, shown);
        if (shown == 0) return;
        GLES20.glUniform1f(uStrength, values[0]);
        GLES20.glUniform1f(uParam, values[1]);
        GLES20.glUniform3f(uTint, values[3], values[4], values[5]);
        GLES20.glUniform1f(uStreak, values[6]);
        GLES20.glUniform1f(uSegments, values[7]);
        float[][] d = diffusion;
        if (d != null && filter <= 6) {
            GLES20.glUniform3f(uW0, d[0][0], d[0][1], d[0][2]);
            int n = Math.min(12, d.length - 1);
            float[] flat = new float[36];
            for (int k = 1; k <= n; k++) System.arraycopy(d[k], 0, flat, (k - 1) * 3, 3);
            GLES20.glUniform3fv(uW, 12, flat, 0);
            GLES20.glUniform1i(uLevels, n);
        } else {
            GLES20.glUniform3f(uW0, 1f, 1f, 1f);
            GLES20.glUniform1i(uLevels, 0);
        }
        if (pyramid) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT_HI);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hiTex);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT_LIN);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, linTex);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glUniform1i(uSqrt, sqrt ? 1 : 0);
            GLES20.glUniform4f(uView, viewport[0], viewport[1], viewport[2], viewport[3]);
        } else {
            int[] v = new int[4];
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, v, 0);
            GLES20.glUniform4f(uView, v[0], v[1], v[2], v[3]);
        }
    }

    /** The film LUT's texture on unit 1 and its uniforms (the program in use); unit 0 is the camera's frame. */
    private void applyLut() {
        FilmLut lut = ownLut && lutStrength > 0f ? lutReady : null;
        if (lut != lutShown) {
            if (lutTex != 0) GLES20.glDeleteTextures(1, new int[]{lutTex}, 0);
            lutTex = lut != null ? lut.upload() : 0;
            lutShown = lut;
        }
        boolean on = lutTex != 0 && lut != null;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, on ? lutTex : dummyTex);
        GLES20.glUniform1i(uLut, 1);
        GLES20.glUniform1i(uLutOn, on ? 1 : 0);
        if (on) {
            GLES20.glUniform1f(uLutStrength, lutStrength);
            GLES20.glUniform1f(uLutSize, lut.size);
            GLES20.glUniform3f(uLutMin, lut.domainMin[0], lut.domainMin[1], lut.domainMin[2]);
            GLES20.glUniform3f(uLutScale, 1f / (lut.domainMax[0] - lut.domainMin[0]), 1f / (lut.domainMax[1] - lut.domainMin[1]),
                    1f / (lut.domainMax[2] - lut.domainMin[2]));
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    /** The chosen LUT read off the GL thread (a .cube file can take a while). */
    private void readLut() {
        if (!ownLut) {
            lutStrength = 0f;
            return;
        }
        try {
            // P281: the film simulation and the LUT as one table (FilmChain)
            final float userStrength = com.particlesdevs.photoncamera.settings.PreferenceKeys.filmLutStrength();
            final String user = userStrength > 0f ? com.particlesdevs.photoncamera.settings.PreferenceKeys.filmLut() : "";
            final com.particlesdevs.photoncamera.processing.color.FilmSim.Settings sim = com.particlesdevs.photoncamera.processing.color.FilmSim.current();
            final com.particlesdevs.photoncamera.processing.color.FilmChain.State chain =
                    com.particlesdevs.photoncamera.processing.color.FilmChain.current(sim, user, userStrength);
            lutStrength = chain.strength;
            final String name = lutStrength > 0f ? chain.key : "";
            if (name.equals(lutWanted)) return;
            lutWanted = name;
            lutReady = null;
            if (!name.isEmpty()) LUT_LOADER.execute(() -> {
                if (!name.equals(lutWanted)) return; // settings moved on while queued
                FilmLut lut = com.particlesdevs.photoncamera.processing.color.FilmChain.lut(ScameraApp.getAppContext(), name);
                if (name.equals(lutWanted)) lutReady = lut;
                // P369: the photo's scene table of the same settings built ahead (WhyCombine takes it from FilmChain's cache: the
                // first film shot of an ISP-viewfinder session does not build it); not for settings already left
                if (sim != null && name.equals(lutWanted))
                    com.particlesdevs.photoncamera.processing.color.FilmChain.scene(ScameraApp.getAppContext(), sim, user, userStrength);
            });
        } catch (RuntimeException e) {
            lutStrength = 0f;   // no settings yet
        }
    }

    private void read() {
        readLut();
        try {
            String id = PreferenceKeys.lensFilter();
            float s = PreferenceKeys.lensFilterStrength();
            int index = LensFilters.index(id);
            if (index <= 0 || s <= 0f) {
                filter = 0;
                return;
            }
            LensFilters.Tune tune = PreferenceKeys.lensFilterTune(id);
            fill(values, id, s, tune);
            filterId = id;
            filterStrength = s;
            filterTune = tune;
            filter = index;
        } catch (RuntimeException e) {
            filter = 0;   // no settings yet
        }
    }

    /**
     * The uniform values of filter {@code id} at strength {@code s} with its tune: strength, param, the star / streak's knee
     * (the filter's own: the diffusion has none), tint (3), the streak's length, the segments.
     */
    static void fill(float[] v, String id, float s, LensFilters.Tune tune) {
        float[] tint = LensFilters.tint(id, tune);
        v[0] = s;
        v[1] = tune.param;
        v[2] = com.particlesdevs.photoncamera.processing.color.DiffusionModel.diffuses(id) ? LensFilters.shape(id)[0]
                : LensFilters.shape(id, tune)[0];
        v[3] = tint[0];
        v[4] = tint[1];
        v[5] = tint[2];
        v[6] = LensFilters.streakLength(id, tune);
        v[7] = LensFilters.segments(tune.param);
    }

    /**
     * The diffusion's weights for a viewport {@code longPx} long whose half-size pyramid has {@code mipLevels} levels: [0] the
     * frame's own, [k] mip level k - 1 (DiffusionModel.mipSigmas), at most 12; null without a diffusion filter.
     */
    @androidx.annotation.Nullable
    static float[][] weights(String id, float s, LensFilters.Tune tune, int longPx, int mipLevels) {
        if (tune == null) return null;
        int n = Math.min(12, mipLevels);
        return com.particlesdevs.photoncamera.processing.color.DiffusionModel.levelWeights(id, s, tune, longPx,
                com.particlesdevs.photoncamera.processing.color.DiffusionModel.mipSigmas(n));
    }

}
