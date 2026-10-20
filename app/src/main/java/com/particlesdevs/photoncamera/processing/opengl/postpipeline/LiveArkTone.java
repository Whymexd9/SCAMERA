package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.SystemClock;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.color.ColourLook;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.color.ToneCurve;
import com.particlesdevs.photoncamera.processing.opengl.GLInterface;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P301 (owner, 2026-10-20: «тонмапинг и тд у нас работает в живом видоискателе? Результат должен совпадать с итоговым фото»):
 * the photo's ARK tone on the developed-RAW viewfinder, with the photo's own shaders and settings - ark/low (arkLow at half the
 * viewport), ArkAe on a small arkLow read back every few frames (its Smart-HDR statistics, the auto exposure, the fusion's exposures
 * and the AgX parameters), ark/gf + box + ab (the guided-filter exposure fusion), ark/combine (the tone, the colour look, the film
 * simulation / LUT) - so the viewfinder shows the photo's tone, colour and LUT; only the noise and the detail of the merge differ.
 * The input is the RAW frame's linear values (rawdevelop_fs u_linear_out: white-balanced, lens-shaded, colour-transformed to linear
 * sRGB), so the colour matrices of ark/low and ark/combine are the identity here. What the single viewfinder frame cannot have: the
 * merged noise level (the AE's noise clamp takes the frame's noise divided by the merge's frames), the Bento headroom above the
 * clip (no HEADROOM_WIDE / landing), the full-size detail of the merge (detail off). Runs on the viewfinder's GL thread (GLES 3.1
 * shaders); any failure turns it off for the context (the RAW viewfinder's own look draws instead).
 */
public final class LiveArkTone {
    private static final String TAG = "LiveArkTone";
    private static final String VS = "#version 310 es\nin vec4 vPosition;\nvoid main() {\n    gl_Position = vPosition;\n}\n";
    /** The AE runs again every this many frames (ArkAe on a factor-8 arkLow read back). */
    static final int AE_EVERY = 4;
    /** The viewfinder's AE follows a new value this fast (per AE run), so the picture does not pump. */
    static final float AE_SMOOTH = 0.35f;
    /** Settings, colour look and LUT are read again at most this often. */
    static final long SETTINGS_MS = 500;

    private static final ExecutorService LUT_LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vf-ark-lut");
        t.setDaemon(true);
        return t;
    });

    /** The viewfinder shows the photo's ARK tone (scam_dev.txt "vf_ark 0": the RAW viewfinder's own look, as before P301). */
    public static boolean wanted() {
        return PreferenceKeys.scamDevSwitch("vf_ark", true);
    }

    private static final class Tex {
        int id, fbo, w, h, format;
    }

    private final HashMap<String, Integer> programs = new HashMap<>();
    private final HashMap<Integer, HashMap<String, Integer>> locations = new HashMap<>();
    private int vs, ones, dummy3d, lutTex;
    private boolean failed;
    private final Tex lin = new Tex(), low = new Tex(), aeLow = new Tex(), fused = new Tex();
    private final Tex[] means = {new Tex(), new Tex(), new Tex()}, ab = {new Tex(), new Tex()}, boxTmp = {new Tex(), new Tex()};
    private final FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
    private FloatBuffer aePixels;
    private ArkAe.Result ae;
    private float aeShown;
    private int frames;
    private long settingsAt = -SETTINGS_MS;
    // settings snapshot
    private ArkAe.Settings settings;
    private ColourLook look;
    private boolean colourOn;
    private int toneOp;
    private float[] lw = {0.8f, 1f, 0.7f, 0.5f};
    private float center, smooth, gammaInv, toe, eps, mono, inScale, macro, hlWhite, filmToe, deltaChroma, clarity, flatProtect;
    private float[] vib = new float[3];
    private int radius;
    private volatile String lutWanted = "";
    private volatile FilmLut lutReady;
    private FilmLut lutShown;
    private float lutStrength;

    /** A new GL context: everything made in the old one is gone with it. */
    public void onContextCreated() {
        programs.clear();
        locations.clear();
        vs = ones = dummy3d = lutTex = 0;
        lutShown = null;
        failed = false;
        for (Tex t : new Tex[]{lin, low, aeLow, fused, means[0], means[1], means[2], ab[0], ab[1], boxTmp[0], boxTmp[1]})
            t.id = t.fbo = t.w = t.h = 0;
        ae = null;
        frames = 0;
    }

    public boolean failed() {
        return failed;
    }

    /**
     * The frame: {@code linear} draws the RAW frame's linear values into the bound framebuffer at the current viewport (returns
     * false without a frame), {@code frame} then tells its ISO, analog ceiling and noise model; the toned frame goes to {@code outFbo} ({@code w} x {@code h}, the viewport). The default framebuffer
     * and the viewport are put back. False: nothing drawn (no frame, or the tone failed - then {@link #failed()}).
     */
    public boolean draw(java.util.function.BooleanSupplier linear, java.util.function.Supplier<double[]> frame, int outFbo, int w, int h) {
        if (failed) return false;
        int[] viewport = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0);
        try {
            if (vs == 0 && !init()) return fail("init");
            target(lin, w, h, GLES30.GL_RGBA16F, GLES20.GL_NEAREST);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lin.fbo);
            GLES20.glViewport(0, 0, w, h);
            final boolean drawn = linear.getAsBoolean();
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            if (!drawn) return false;
            refreshSettings();
            // arkLow (factor 2), as ArkStats
            final int lw2 = Math.max(1, (w + 1) / 2), lh2 = Math.max(1, (h + 1) / 2);
            target(low, lw2, lh2, GLES30.GL_RGBA16F, GLES20.GL_NEAREST);
            int p = program("ark/low", null);
            use(p);
            texture(p, "InputBuffer", 0, lin.id);
            colour(p);
            set(p, "factorU", 2);
            draw(low);
            // the AE every few frames, on a factor-8 arkLow
            if (ae == null || frames++ % AE_EVERY == 0) {
                // the drawn frame's ISO, the analog ceiling and its noise model ({iso, maxIso, noiseS, noiseO})
                final double[] info = frame.get();
                if (!runAe(w, h, (int) info[0], (int) info[1], info[2], info[3])) return fail("ae");
            }
            final ArkAe.Result r = ae;
            final float aeNow = aeShown;
            // the fusion, as ArkFusion (no bracket denoise: a single frame has no data above the clip)
            final float[] em = ArkAe.exposures(r);
            final int hw = (lw2 + 1) / 2, hh = (lh2 + 1) / 2;
            for (int mode = 0; mode < 3; mode++) {
                target(means[mode], hw, hh, GLES30.GL_RGBA32F, GLES20.GL_NEAREST);
                int g = program("ark/gf", new String[][]{{"MODE", String.valueOf(mode)}});
                weights(g, em, aeNow);
                texture(g, "InputBuffer", 0, low.id);
                draw(means[mode]);
                box(means[mode]);
            }
            for (int mode = 0; mode < 2; mode++) {
                target(ab[mode], hw, hh, GLES30.GL_RGBA32F, GLES20.GL_NEAREST);
                int a = program("ark/ab", new String[][]{{"MODE", String.valueOf(mode)}});
                use(a);
                texture(a, "MeanII", 0, means[0].id);
                texture(a, "MeanP", 1, means[1].id);
                texture(a, "MeanIP", 2, means[2].id);
                set(a, "epsU", eps);
                draw(ab[mode]);
                box(ab[mode]);
            }
            target(fused, lw2, lh2, GLES30.GL_RGBA32F, GLES20.GL_NEAREST);
            int g3 = program("ark/gf", new String[][]{{"MODE", "3"}});
            weights(g3, em, aeNow);
            texture(g3, "InputBuffer", 0, low.id);
            texture(g3, "MeanA", 1, ab[0].id);
            texture(g3, "MeanB", 2, ab[1].id);
            draw(fused);
            // the tone, as ArkCombine
            combine(r, aeNow, outFbo, w, h);
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) return fail("GL error 0x" + Integer.toHexString(error));
            return true;
        } catch (RuntimeException e) {
            return fail(e.toString());
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }
    }

    private boolean fail(String why) {
        failed = true;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        Log.e(TAG, "the photo's tone on the viewfinder is off for this context: " + why);
        return false;
    }

    private boolean init() {
        vs = compile(GLES20.GL_VERTEX_SHADER, VS);
        if (vs == 0) return false;
        int[] t = new int[2];
        GLES20.glGenTextures(2, t, 0);
        ones = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ones);
        // half floats: a linear-filtered RGBA32F texture needs OES_texture_float_linear (the lens-shading map is sampled bicubically)
        java.nio.ShortBuffer one = ByteBuffer.allocateDirect(2 * 2 * 4 * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        for (int i = 0; i < 16; i++) one.put((short) 0x3C00);
        one.flip();
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, 2, 2, 0, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, one);
        params(GLES20.GL_TEXTURE_2D, GLES20.GL_LINEAR);
        dummy3d = t[1];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, dummy3d);
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, 1, 1, 1, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4));
        params(GLES30.GL_TEXTURE_3D, GLES20.GL_LINEAR);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return GLES20.glGetError() == GLES20.GL_NO_ERROR;
    }

    private static void params(int target, int filter) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    /** The settings the photo would use now (ArkStats / ArkFusion / ArkCombine read the same). */
    private void refreshSettings() {
        final long now = SystemClock.uptimeMillis();
        if (now - settingsAt < SETTINGS_MS && settings != null) return;
        settingsAt = now;
        settings = ArkTone.settings();
        settings.bentoHeadroom = false;     // a single frame: no Bento data above the clip
        look = ArkTone.colourLook();
        colourOn = !look.shaderNeutral();
        toneOp = ArkTone.toneOperator();
        lw = new float[]{ArkTone.value("weight_hl", 0.8f), ArkTone.value("weight_mid", 1.0f),
                ArkTone.value("weight_ext_hl", 0.7f), ArkTone.value("weight_shadow", 0.5f)};
        center = ArkTone.value("weight_center", 0.6f);
        smooth = ArkTone.value("blend_smoothness", 0.25f);
        gammaInv = 1f / ArkTone.gamma();
        toe = ArkTone.value("aces_toe", 0.05f);
        int rad = Math.max(1, Math.round(ArkTone.value("gf_radius", 32f)));
        radius = rad > 1 ? rad >> 1 : 1;
        eps = Math.max(ArkTone.value("gf_eps_e3", 1f) * 0.001f, 1e-6f);
        mono = ArkTone.fusionMono();
        inScale = (float) Math.pow(2.0, ArkTone.value("input_ev", 0f));
        macro = ArkTone.value("macro_contrast", 1.1f);
        vib = new float[]{ArkTone.value("vibrance", 0f), ArkTone.value("vibrance_sky", 0.40f), ArkTone.value("vibrance_green", 0.20f)};
        hlWhite = Math.max(0f, Math.min(1f, ArkTone.value("hl_white", 0.15f)));
        filmToe = ArkTone.value("film_toe", 0.10f);
        deltaChroma = Math.max(0f, Math.min(1f, ArkTone.value("delta_chroma", 1f)));
        clarity = ArkTone.value("clarity", 0f);
        flatProtect = ArkTone.value("flat_protect", 0f);
        try {
            final com.particlesdevs.photoncamera.processing.color.FilmChain.State chain =
                    com.particlesdevs.photoncamera.processing.color.FilmChain.current();
            lutStrength = chain.strength;
            final String name = lutStrength > 0f ? chain.key : "";
            if (!name.equals(lutWanted)) {
                lutWanted = name;
                lutReady = null;
                if (!name.isEmpty()) LUT_LOADER.execute(() -> {
                    FilmLut lut = com.particlesdevs.photoncamera.processing.color.FilmChain.lut(ScameraApp.getAppContext(), name);
                    if (name.equals(lutWanted)) lutReady = lut;
                });
            }
        } catch (RuntimeException e) {
            lutStrength = 0f;
        }
    }

    /** ArkAe on arkLow at factor 8 (the photo: at factor 2 of the merge; the percentiles of a smooth image hardly move). */
    private boolean runAe(int w, int h, int iso, int maxIso, double noiseS, double noiseO) {
        final int aw = Math.max(1, (w + 7) / 8), ah = Math.max(1, (h + 7) / 8);
        target(aeLow, aw, ah, GLES30.GL_RGBA32F, GLES20.GL_NEAREST);
        int p = program("ark/low", null);
        use(p);
        texture(p, "InputBuffer", 0, lin.id);
        colour(p);
        set(p, "factorU", 8);
        draw(aeLow);
        if (aePixels == null || aePixels.capacity() < aw * ah * 4)
            aePixels = ByteBuffer.allocateDirect(aw * ah * 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
        aePixels.clear();
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, aeLow.fbo);
        GLES20.glReadPixels(0, 0, aw, ah, GLES20.GL_RGBA, GLES20.GL_FLOAT, aePixels);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return false;
        aePixels.position(0);
        // the photo's AE clamps the shadow gain by the MERGED image's noise: the frame's noise over the merge's frames
        // (the merge keeps ~0.55 of each normal frame on a handheld burst: HYBRID MERGE FACTORS of the owner's logs)
        final float merged = Math.max(1f, 0.55f * PreferenceKeys.hybridValue("zsl_frames", 20f));
        settings.noiseS = noiseS / merged;
        settings.noiseO = noiseO / merged;
        final ArkAe.Result r = ArkAe.smartHdr(aePixels, 4, aw, ah, settings, iso, maxIso);
        aeShown = ae == null ? r.ae : aeShown + (r.ae - aeShown) * AE_SMOOTH;
        ae = r;
        return true;
    }

    /** The colour chain of ark/low and ark/combine for the linear-sRGB input: identity matrices, neutral white. */
    private void colour(int p) {
        texture(p, "GainMap", 7, ones);
        final float[] id = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        GLES20.glUniformMatrix3fv(loc(p, "sensorToIntermediate"), 1, false, id, 0);
        GLES20.glUniformMatrix3fv(loc(p, "intermediateToSRGB"), 1, false, id, 0);
        GLES20.glUniform3f(loc(p, "neutralPointU"), 1f, 1f, 1f);
        set(p, "inScaleU", inScale);
    }

    private void weights(int p, float[] em, float aeNow) {
        use(p);
        GLES20.glUniform4f(loc(p, "expU"), em[0], em[1], em[2], em[3]);
        GLES20.glUniform4f(loc(p, "layerU"), lw[0], lw[1], lw[2], lw[3]);
        set(p, "aeU", aeNow);
        set(p, "centerU", center);
        set(p, "smoothU", smooth);
        set(p, "gammaInvU", gammaInv);
        set(p, "toeU", toe);
        set(p, "monoU", mono);
    }

    /** The separable box of ArkFusion (radius r) in place: t -> tmp -> t. */
    private void box(Tex t) {
        Tex tmp = boxTmp[0];
        target(tmp, t.w, t.h, GLES30.GL_RGBA32F, GLES20.GL_NEAREST);
        int p = program("ark/box", null);
        use(p);
        texture(p, "InputBuffer", 0, t.id);
        GLES20.glUniform2i(loc(p, "dirU"), 1, 0);
        set(p, "radiusU", radius);
        draw(tmp);
        texture(p, "InputBuffer", 0, tmp.id);
        GLES20.glUniform2i(loc(p, "dirU"), 0, 1);
        draw(t);
    }

    private void combine(ArkAe.Result r, float aeNow, int outFbo, int w, int h) {
        FilmLut lut = lutStrength > 0f ? lutReady : null;
        if (lut != lutShown) {
            if (lutTex != 0) GLES20.glDeleteTextures(1, new int[]{lutTex}, 0);
            lutTex = lut != null ? lut.upload() : 0;
            lutShown = lut;
        }
        final boolean lutOn = lutTex != 0 && lut != null;
        ArrayList<String[]> defs = new ArrayList<>();
        if (colourOn) defs.add(new String[]{"COLOUR_LOOK", "1"});
        if (toneOp != ToneCurve.AGX) defs.add(new String[]{"TONE_OP", "1"});
        if (lutOn) defs.add(new String[]{"FILM_LUT", "1"});
        int p = program("ark/combine", defs.toArray(new String[0][]));
        use(p);
        texture(p, "InputBuffer", 0, lin.id);
        texture(p, "ArkLow", 1, low.id);
        texture(p, "ArkColour", 2, low.id);
        texture(p, "ArkFused", 3, fused.id);
        texture(p, "ArkDetailRef", 4, low.id);
        texture(p, "ArkLumaS", 5, low.id);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE6);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutOn ? lutTex : dummy3d);
        set(p, "FilmLut", 6);
        colour(p);
        set(p, "fU", 2);
        set(p, "colourFU", 2);
        set(p, "signedColourU", 0);
        set(p, "aeU", aeNow);
        set(p, "clipU", r.clip);
        set(p, "toeU", toe);
        set(p, "gammaInvU", gammaInv);
        set(p, "macroU", macro);
        GLES20.glUniform3f(loc(p, "vibU"), vib[0], vib[1], vib[2]);
        set(p, "chromaDnU", 0f);
        set(p, "clarityU", clarity);
        set(p, "flatProtectU", flatProtect);
        set(p, "detailGainU", 0f);
        set(p, "detailRefU", 0);
        set(p, "sharpU", 0);
        set(p, "sharpToneU", 1f);
        set(p, "deltaChromaU", deltaChroma);
        set(p, "filmToeU", filmToe);
        GLES20.glUniform4f(loc(p, "agxAU"), r.slope, r.sp, r.tp, r.sat);
        GLES20.glUniform4f(loc(p, "agxBU"), r.minEv, r.maxEv, r.ev, toneOp == ToneCurve.AGX ? (float) r.look : 0f);
        if (toneOp != ToneCurve.AGX) {
            set(p, "toneOpU", toneOp);
            float[] aces = ArkTone.acesUniform();
            GLES20.glUniform4f(loc(p, "acesU"), aces[0], aces[1], aces[2], aces[3]);
            float[][] uchi = ArkTone.uchimuraUniforms();
            GLES20.glUniform4f(loc(p, "uchiAU"), uchi[0][0], uchi[0][1], uchi[0][2], uchi[0][3]);
            GLES20.glUniform4f(loc(p, "uchiBU"), uchi[1][0], uchi[1][1], uchi[1][2], uchi[1][3]);
            float[] curve = ArkTone.curveSamples();
            GLES20.glUniform4fv(loc(p, "curveU"), Math.min(64, curve.length / 4), curve, 0);
        }
        set(p, "ditherU", 1);
        set(p, "headroomU", 0);
        set(p, "hlWhiteU", hlWhite);
        if (colourOn) {
            float[] skin = look.skinUniform(), vibL = look.vibUniform(), monoF = look.monoUniform();
            GLES20.glUniform3f(loc(p, "skinU"), skin[0], skin[1], skin[2]);
            GLES20.glUniform2f(loc(p, "vibLU"), vibL[0], vibL[1]);
            GLES20.glUniform1fv(loc(p, "mixU"), 24, look.mixUniform(), 0);
            set(p, "mixOnU", look.mixerOn() ? 1 : 0);
            GLES20.glUniform3f(loc(p, "monoU"), monoF[0], monoF[1], monoF[2]);
            set(p, "gamutU", Math.max(0f, Math.min(1f, look.gamut / 100f)));
            set(p, "hueLockU", look.hueLock);
            float[][] grade = look.gradeUniforms();
            GLES20.glUniform4f(loc(p, "gradeAU"), grade[0][0], grade[0][1], grade[0][2], grade[0][3]);
            GLES20.glUniform4f(loc(p, "gradeBU"), grade[1][0], grade[1][1], grade[1][2], grade[1][3]);
        }
        if (lutOn) {
            GLES20.glUniform2f(loc(p, "lutU"), lutStrength, (float) lut.size);
            GLES20.glUniform3f(loc(p, "lutMinU"), lut.domainMin[0], lut.domainMin[1], lut.domainMin[2]);
            GLES20.glUniform3f(loc(p, "lutScaleU"), 1f / (lut.domainMax[0] - lut.domainMin[0]), 1f / (lut.domainMax[1] - lut.domainMin[1]),
                    1f / (lut.domainMax[2] - lut.domainMin[2]));
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo);
        GLES20.glViewport(0, 0, w, h);
        quad(p);
    }

    // ---- GL helpers

    private int program(String asset, String[][] defines) {
        StringBuilder key = new StringBuilder(asset);
        if (defines != null) for (String[] d : defines) key.append('|').append(d[0]).append('=').append(d[1]);
        Integer cached = programs.get(key.toString());
        if (cached != null) return cached;
        ArrayList<String[]> list = new ArrayList<>();
        if (defines != null) for (String[] d : defines) list.add(d);
        String source = GLInterface.loadShader(ScameraApp.getAssetLoader().getString("shaders/" + asset + ".glsl"), list);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, source);
        if (fs == 0) throw new IllegalStateException("shader " + key);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glBindAttribLocation(p, 0, "vPosition");
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(fs);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String info = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new IllegalStateException("link " + key + ": " + info);
        }
        programs.put(key.toString(), p);
        return p;
    }

    private static int compile(int type, String source) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, source);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] != 0) return s;
        Log.e(TAG, GLES20.glGetShaderInfoLog(s));
        GLES20.glDeleteShader(s);
        return 0;
    }

    private int loc(int p, String name) {
        HashMap<String, Integer> m = locations.get(p);
        if (m == null) locations.put(p, m = new HashMap<>());
        return m.computeIfAbsent(name, n -> GLES20.glGetUniformLocation(p, n));
    }

    private void use(int p) {
        GLES20.glUseProgram(p);
    }

    private void set(int p, String name, float v) {
        GLES20.glUniform1f(loc(p, name), v);
    }

    private void set(int p, String name, int v) {
        GLES20.glUniform1i(loc(p, name), v);
    }

    private void texture(int p, String name, int unit, int id) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
        set(p, name, unit);
    }

    /** (Re)makes t as a w x h texture of this format with its framebuffer. */
    private void target(Tex t, int w, int h, int format, int filter) {
        if (t.id != 0 && t.w == w && t.h == h && t.format == format) return;
        if (t.fbo != 0) GLES30.glDeleteFramebuffers(1, new int[]{t.fbo}, 0);
        if (t.id != 0) GLES20.glDeleteTextures(1, new int[]{t.id}, 0);
        int[] id = new int[1], fb = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, format, w, h);
        params(GLES20.GL_TEXTURE_2D, filter);
        GLES30.glGenFramebuffers(1, fb, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, id[0], 0);
        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        t.id = id[0];
        t.fbo = fb[0];
        t.w = w;
        t.h = h;
        t.format = format;
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("target " + w + "x" + h + " 0x" + Integer.toHexString(format) + " incomplete: 0x" + Integer.toHexString(status));
    }

    /** The program (in use) over the whole of t. */
    private void draw(Tex t) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fbo);
        GLES20.glViewport(0, 0, t.w, t.h);
        int[] p = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, p, 0);
        quad(p[0]);
    }

    private void quad(int p) {
        quad.position(0);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(0);
    }
}
