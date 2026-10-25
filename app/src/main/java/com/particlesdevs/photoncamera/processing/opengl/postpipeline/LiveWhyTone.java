package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.SystemClock;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.color.ColourLook;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.color.ToneCurve;
import com.particlesdevs.photoncamera.processing.opengl.GLInterface;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
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
 * the photo's WHY tone on the developed-RAW viewfinder, with the photo's own shaders and settings - why/low (whyLow at half the
 * viewport), WhyAe on a small whyLow read back every few frames (its Smart-HDR statistics, the auto exposure, the fusion's exposures
 * and the AgX parameters), why/gf + box + ab (the guided-filter exposure fusion), why/combine (the tone, the colour look, the film
 * simulation / LUT) - so the viewfinder shows the photo's tone, colour and LUT; only the noise and the detail of the merge differ.
 * The input is the RAW frame's linear values (rawdevelop_fs u_linear_out: white-balanced, lens-shaded, colour-transformed to linear
 * sRGB), so the colour matrices of why/low and why/combine are the identity here. What the single viewfinder frame cannot have: the
 * merged noise level (the AE's noise clamp takes the frame's noise divided by the merge's frames), the Bento headroom above the
 * clip (no HEADROOM_WIDE / landing), the full-size detail of the merge (detail off). Runs on the viewfinder's GL thread (GLES 3.1
 * shaders); any failure turns it off for the context (the RAW viewfinder's own look draws instead).
 *
 * <p>P314 (owner, 2026-10-21: «Исправь живой видоискатель», the reference viewfinder «обработку он никак не замедляет»; a vivo
 * X200 Ultra 2x ISZ merge took 21-56 s under this tone against 5.5 s under the one-pass RAW viewfinder: ~20 passes a frame with
 * the guided filter, a synchronous readback every 4th frame): the reference viewfinder's shape - vf_why 1 (the default) is the
 * photo's tone per pixel: the linear frame at the colour resolution of the frame (MainRenderer: LiveRawRenderer.toneSize), the AE
 * on the CPU from the RAW frame (LiveWhyMeter: the same WhyAe, no readback), and why/combine with POINT_FUSED 1 straight on the
 * linear frame - the fusion's curve per pixel (FusedCurve: the guided filter's value on a flat area), the photo's AE, AgX, colour
 * look, film LUT and dither. Two passes a frame. The guided-filter chain above stays as vf_why 2 (now at the tone size too) for
 * an A/B against the photo; it differs in strongly fused scenes (the point fusion lifts the shadows evenly: up to 0.02-0.06 of
 * display luma at effS 1.5-2, host model of a 2x ISZ merge).
 */
public final class LiveWhyTone {
    private static final String TAG = "LiveWhyTone";
    private static final String VS = "#version 310 es\nin vec4 vPosition;\nvoid main() {\n    gl_Position = vPosition;\n}\n";
    /** The AE runs again every this many frames (WhyAe on the CPU cells, or on a factor-8 whyLow read back with vf_why 2). */
    static final int AE_EVERY = 4;
    /** P314: scam_dev.txt vf_why - the viewfinder's own look, the photo's tone per pixel (the default), the guided-filter chain. */
    public static final int OWN = 0, POINT = 1, LOCAL = 2;
    /** P314: the viewfinder's AE line (WhyAe.describe, as the photo's SMART_HDR line) is logged at most this often. */
    static final long AE_LOG_MS = 5000;
    /** The viewfinder's AE follows a new value this fast (per AE run), so the picture does not pump. */
    static final float AE_SMOOTH = 0.35f;
    /** Settings, colour look and LUT are read again at most this often. */
    static final long SETTINGS_MS = 500;

    private static final ExecutorService LUT_LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vf-why-lut");
        t.setDaemon(true);
        return t;
    });

    /** The viewfinder shows the photo's WHY tone (scam_dev.txt "vf_why 0": the RAW viewfinder's own look, as before P301). */
    public static boolean wanted() {
        return mode() != OWN;
    }

    /**
     * P314: how the RAW viewfinder tones its frame - scam_dev.txt "vf_why": 0 {@link #OWN}, 1 {@link #POINT} (the default: the
     * photo's tone per pixel), 2 {@link #LOCAL} (the photo's guided-filter fusion, P301); any other value: 1.
     */
    public static int mode() {
        final float v = PreferenceKeys.scamDevNumber("vf_why", POINT);
        return v == 0f ? OWN : v == 2f ? LOCAL : POINT;
    }

    private static final class Tex {
        int id, fbo, w, h, format;
    }

    private final HashMap<String, Integer> programs = new HashMap<>();
    private final HashMap<Integer, HashMap<String, Integer>> locations = new HashMap<>();
    private int ones, dummy3d, lutTex;
    private boolean inited, failed;
    private final Tex lin = new Tex(), low = new Tex(), aeLow = new Tex(), fused = new Tex();
    private final Tex[] means = {new Tex(), new Tex(), new Tex()}, ab = {new Tex(), new Tex()}, boxTmp = {new Tex(), new Tex()};
    private final FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
    private FloatBuffer aePixels;
    private WhyAe.Result ae;
    private float aeShown;
    private int frames;
    /** P314: the fusion's curve (vf_why 1): its texture (R32F FusedCurve.SIZE x 1), values, and the AE result it was filled for. */
    private int curveTex;
    private final float[] curve = new float[FusedCurve.SIZE];
    private FloatBuffer curveBuffer;
    private WhyAe.Result curveOf;
    /** P314: the CPU AE of vf_why 1. */
    private final com.particlesdevs.photoncamera.processing.LiveWhyMeter meter = new com.particlesdevs.photoncamera.processing.LiveWhyMeter();
    private long aeLogAt = -AE_LOG_MS;
    private long settingsAt = -SETTINGS_MS;
    // settings snapshot
    private WhyAe.Settings settings;
    private ColourLook look;
    private boolean colourOn;
    private int toneOp;
    private float[] lw = {0.8f, 1f, 0.7f, 0.5f};
    private float center, smooth, gammaInv, toe, eps, mono, inScale, macro, hlWhite, filmToe, deltaChroma, clarity, flatProtect;
    private float[] vib = new float[3];
    private int radius;
    /** P314: the box radius of this draw (vf_why 2): radius scaled from the viewport to the tone size, the same footprint. */
    private int boxRadius = 1;
    private volatile String lutWanted = "";
    private volatile FilmLut lutReady;
    private FilmLut lutShown;
    private float lutStrength;

    /** A new GL context: everything made in the old one is gone with it. */
    public void onContextCreated() {
        programs.clear();
        locations.clear();
        ones = dummy3d = lutTex = 0;
        inited = false;
        lutShown = null;
        failed = false;
        for (Tex t : new Tex[]{lin, low, aeLow, fused, means[0], means[1], means[2], ab[0], ab[1], boxTmp[0], boxTmp[1]})
            t.id = t.fbo = t.w = t.h = 0;
        ae = null;
        frames = 0;
        curveTex = 0;
        curveOf = null;
    }

    public boolean failed() {
        return failed;
    }

    /**
     * The frame: {@code linear} draws the RAW frame's linear values into the bound framebuffer at the current viewport (returns
     * false without a frame), {@code frame} then gives the frame it drew (P314: with its visible crop and colour transform, for
     * the CPU AE; its ISO, analog ceiling and noise model); the toned frame goes to {@code outFbo} ({@code w} x {@code h}: P314 the
     * tone size, MainRenderer). The default framebuffer and the viewport are put back. False: nothing drawn (no frame, or the tone
     * failed - then {@link #failed()}).
     */
    public boolean draw(java.util.function.BooleanSupplier linear,
                        java.util.function.Supplier<com.particlesdevs.photoncamera.processing.LiveWhyMeter.View> frame, int outFbo, int w, int h) {
        if (failed) return false;
        int[] viewport = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0);
        try {
            if (!inited && !init()) return fail("init");
            target(lin, w, h, GLES30.GL_RGBA16F, GLES20.GL_NEAREST);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lin.fbo);
            GLES20.glViewport(0, 0, w, h);
            final boolean drawn = linear.getAsBoolean();
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            if (!drawn) return false;
            refreshSettings();
            if (mode() != LOCAL) {
                // P314: the photo's tone per pixel - the AE on the CPU from the RAW frame (no readback), then one pass of why/combine
                if (ae == null || frames++ % AE_EVERY == 0) {
                    if (!runAePoint(frame.get())) return fail("ae");
                }
                if (curveOf != ae) fillCurve(ae);
                combine(ae, aeShown, outFbo, w, h, true);
                int error = GLES20.glGetError();
                if (error != GLES20.GL_NO_ERROR) return fail("GL error 0x" + Integer.toHexString(error));
                return true;
            }
            // P314: drawn at the tone size (below the viewport for a mosaic frame): the guided filter's box keeps its footprint on
            // the screen (its radius was set for grids of the viewport)
            boxRadius = Math.max(1, Math.round(radius * (viewport[2] > 0 ? Math.min(1f, w / (float) viewport[2]) : 1f)));
            // whyLow (factor 2), as WhyStats
            final int lw2 = Math.max(1, (w + 1) / 2), lh2 = Math.max(1, (h + 1) / 2);
            target(low, lw2, lh2, GLES30.GL_RGBA16F, GLES20.GL_NEAREST);
            int p = program("why/low", null);
            use(p);
            texture(p, "InputBuffer", 0, lin.id);
            colour(p);
            set(p, "factorU", 2);
            draw(low);
            // the AE every few frames, on a factor-8 whyLow
            if (ae == null || frames++ % AE_EVERY == 0) {
                // the drawn frame's ISO, the analog ceiling and its noise model
                final com.particlesdevs.photoncamera.processing.LiveWhyMeter.View v = frame.get();
                final com.particlesdevs.photoncamera.processing.LiveRawFrame.Frame f = v == null ? null : v.frame;
                if (!runAe(w, h, f == null ? 100 : f.iso, f == null ? 6400 : f.maxAnalogIso, f == null ? 0 : f.noiseS, f == null ? 0 : f.noiseO))
                    return fail("ae");
            }
            final WhyAe.Result r = ae;
            final float aeNow = aeShown;
            // the fusion, as WhyFusion (no bracket denoise: a single frame has no data above the clip)
            final float[] em = WhyAe.exposures(r);
            final int hw = (lw2 + 1) / 2, hh = (lh2 + 1) / 2;
            for (int mode = 0; mode < 3; mode++) {
                target(means[mode], hw, hh, GLES30.GL_RGBA32F, GLES20.GL_NEAREST);
                int g = program("why/gf", new String[][]{{"MODE", String.valueOf(mode)}});
                weights(g, em, aeNow);
                texture(g, "InputBuffer", 0, low.id);
                draw(means[mode]);
                box(means[mode]);
            }
            for (int mode = 0; mode < 2; mode++) {
                target(ab[mode], hw, hh, GLES30.GL_RGBA32F, GLES20.GL_NEAREST);
                int a = program("why/ab", new String[][]{{"MODE", String.valueOf(mode)}});
                use(a);
                texture(a, "MeanII", 0, means[0].id);
                texture(a, "MeanP", 1, means[1].id);
                texture(a, "MeanIP", 2, means[2].id);
                set(a, "epsU", eps);
                draw(ab[mode]);
                box(ab[mode]);
            }
            target(fused, lw2, lh2, GLES30.GL_RGBA32F, GLES20.GL_NEAREST);
            int g3 = program("why/gf", new String[][]{{"MODE", "3"}});
            weights(g3, em, aeNow);
            texture(g3, "InputBuffer", 0, low.id);
            texture(g3, "MeanA", 1, ab[0].id);
            texture(g3, "MeanB", 2, ab[1].id);
            draw(fused);
            // the tone, as WhyCombine
            combine(r, aeNow, outFbo, w, h, false);
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
        inited = GLES20.glGetError() == GLES20.GL_NO_ERROR;
        return inited;
    }

    private static void params(int target, int filter) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    /** The settings the photo would use now (WhyStats / WhyFusion / WhyCombine read the same). */
    private void refreshSettings() {
        final long now = SystemClock.uptimeMillis();
        if (now - settingsAt < SETTINGS_MS && settings != null) return;
        settingsAt = now;
        settings = WhyTone.settings();
        settings.bentoHeadroom = false;     // a single frame: no Bento data above the clip
        // P320: a DCG session's frames take the photo's DCG rules: the night factor reads the gain (the reported ISO over the
        // mode's range scale), a bright scene keeps its exposure
        final android.hardware.camera2.CameraCharacteristics camera = com.particlesdevs.photoncamera.capture.CaptureController.mCameraCharacteristics;
        settings.dcg = com.particlesdevs.photoncamera.capture.Dcg14Mode.frames(camera);
        settings.dcgIsoScale = settings.dcg ? liveDcgIsoScale(camera) : 1f;
        look = WhyTone.colourLook();
        colourOn = !look.shaderNeutral();
        toneOp = WhyTone.toneOperator();
        lw = new float[]{WhyTone.value("weight_hl", 0.8f), WhyTone.value("weight_mid", 1.0f),
                WhyTone.value("weight_ext_hl", 0.7f), WhyTone.value("weight_shadow", 0.5f)};
        center = WhyTone.value("weight_center", 0.6f);
        smooth = WhyTone.value("blend_smoothness", 0.25f);
        gammaInv = 1f / WhyTone.gamma();
        toe = WhyTone.value("aces_toe", 0.05f);
        radius = WhyFusion.GF_RADIUS > 1 ? WhyFusion.GF_RADIUS >> 1 : 1;
        eps = WhyFusion.GF_EPS;
        mono = WhyTone.fusionMono();
        inScale = (float) Math.pow(2.0, WhyTone.value("input_ev", 0f));
        macro = WhyTone.value("macro_contrast", 1.1f);
        vib = new float[]{WhyTone.value("vibrance", 0f), WhyTone.value("vibrance_sky", 0.40f), WhyTone.value("vibrance_green", 0.20f)};
        hlWhite = Math.max(0f, Math.min(1f, WhyTone.value("hl_white", 0.15f)));
        filmToe = WhyTone.value("film_toe", 0.10f);
        deltaChroma = Math.max(0f, Math.min(1f, WhyTone.value("delta_chroma", 1f)));
        clarity = WhyTone.value("clarity", 0f);
        flatProtect = WhyTone.value("flat_protect", 0f);
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

    /**
     * vf_why 2: WhyAe on whyLow at factor 8 (the photo: at factor 2 of the merge; the percentiles of a smooth image hardly move),
     * read back synchronously.
     */
    private boolean runAe(int w, int h, int iso, int maxIso, double noiseS, double noiseO) {
        final int aw = Math.max(1, (w + 7) / 8), ah = Math.max(1, (h + 7) / 8);
        target(aeLow, aw, ah, GLES30.GL_RGBA32F, GLES20.GL_NEAREST);
        int p = program("why/low", null);
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
        mergedNoise(noiseS, noiseO);
        final WhyAe.Result r = WhyAe.smartHdr(aePixels, 4, aw, ah, settings, iso, maxIso);
        aeShown = ae == null ? r.ae : aeShown + (r.ae - aeShown) * AE_SMOOTH;
        ae = r;
        return true;
    }

    /**
     * P320: the viewfinder's DCG ISO scale, by the photo's rule (ScamProcessor.dcgIsoScale): the session's resolved range over the
     * declared one (1 for a stream no wider than declared).
     */
    static float liveDcgIsoScale(android.hardware.camera2.CameraCharacteristics c) {
        if (c == null || !com.particlesdevs.photoncamera.processing.processor.ScamProcessor.isoInflationSeen()) return 1f;
        try {
            final com.particlesdevs.photoncamera.processing.RawLevels.Levels levels = com.particlesdevs.photoncamera.processing.RawLevels.current(c, null);
            if (levels == null) return 1f;
            float declaredBlack = 0f;
            final float[] black = com.particlesdevs.photoncamera.processing.RawLevels.staticBlack(c);
            if (black != null && black.length > 0) { for (float b : black) declaredBlack += b; declaredBlack /= black.length; }
            return com.particlesdevs.photoncamera.processing.processor.ScamProcessor.isoScale(levels.white - levels.meanBlack(),
                    com.particlesdevs.photoncamera.processing.RawLevels.staticWhite(c) - declaredBlack);
        } catch (RuntimeException e) {
            return 1f;
        }
    }

    /** The photo's AE clamps the shadow gain by the MERGED image's noise: the frame's noise over the merge's frames. */
    private void mergedNoise(double noiseS, double noiseO) {
        // (the merge keeps ~0.55 of each normal frame on a handheld burst: HYBRID MERGE FACTORS of the owner's logs)
        final float merged = Math.max(1f, 0.55f * PreferenceKeys.hybridValue("zsl_frames", 20f));
        settings.noiseS = noiseS / merged;
        settings.noiseO = noiseO / merged;
    }

    /**
     * P314 (vf_why 1): WhyAe on the CPU cells of the drawn RAW frame (LiveWhyMeter: its visible crop, in the linear values the
     * frame was drawn with) - no GPU pass, no readback; false when there is nothing to meter and no earlier result.
     */
    private boolean runAePoint(com.particlesdevs.photoncamera.processing.LiveWhyMeter.View v) {
        if (v == null || v.frame == null || v.frame.buffer == null) return ae != null;
        final com.particlesdevs.photoncamera.processing.LiveRawFrame.Frame f = v.frame;
        mergedNoise(f.noiseS, f.noiseO);
        final WhyAe.Result r = meter.measure(v, inScale, settings, f.iso, f.maxAnalogIso);
        if (r == null) return ae != null;
        aeShown = ae == null ? r.ae : aeShown + (r.ae - aeShown) * AE_SMOOTH;
        ae = r;
        final long now = SystemClock.uptimeMillis();
        if (now - aeLogAt >= AE_LOG_MS) {
            aeLogAt = now;
            Log.d(TAG, "viewfinder SMART_HDR (per pixel, " + v.width + "x" + v.height + " of " + f.width + "x" + f.height + (f.cellMean ? " cell means" : "")
                    + ", shown ae=" + aeShown + "): " + r.describe());
        }
        return true;
    }

    /** P314: the fusion's curve of an AE result (FusedCurve, the current fusion settings) into its R32F texture (unit 8). */
    private void fillCurve(WhyAe.Result r) {
        FusedCurve.fill(curve, WhyAe.exposures(r), lw, center, smooth, gammaInv, toe, mono);
        if (curveBuffer == null)
            curveBuffer = ByteBuffer.allocateDirect(FusedCurve.SIZE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        curveBuffer.clear();
        curveBuffer.put(curve).flip();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE8);
        if (curveTex == 0) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            curveTex = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTex);
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_R32F, FusedCurve.SIZE, 1);
            params(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTex);
        }
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, FusedCurve.SIZE, 1, GLES30.GL_RED, GLES20.GL_FLOAT, curveBuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        curveOf = r;
    }

    /** The colour chain of why/low and why/combine for the linear-sRGB input: identity matrices, neutral white. */
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

    /** The separable box of WhyFusion (radius r) in place: t -> tmp -> t. */
    private void box(Tex t) {
        Tex tmp = boxTmp[0];
        target(tmp, t.w, t.h, GLES30.GL_RGBA32F, GLES20.GL_NEAREST);
        int p = program("why/box", null);
        use(p);
        texture(p, "InputBuffer", 0, t.id);
        GLES20.glUniform2i(loc(p, "dirU"), 1, 0);
        set(p, "radiusU", boxRadius);
        draw(tmp);
        texture(p, "InputBuffer", 0, tmp.id);
        GLES20.glUniform2i(loc(p, "dirU"), 0, 1);
        draw(t);
    }

    /**
     * The tone, as WhyCombine. {@code point} (P314, vf_why 1): POINT_FUSED 1 on the linear frame itself (fU 1: the kernel's
     * same-size bypass, every input the linear frame, the fused luma from the FusedCurve texture); else the guided-filter chain's
     * whyLow / fused at factor 2.
     */
    private void combine(WhyAe.Result r, float aeNow, int outFbo, int w, int h, boolean point) {
        FilmLut lut = lutStrength > 0f ? lutReady : null;
        if (lut != lutShown) {
            if (lutTex != 0) GLES20.glDeleteTextures(1, new int[]{lutTex}, 0);
            lutTex = lut != null ? lut.upload() : 0;
            lutShown = lut;
        }
        final boolean lutOn = lutTex != 0 && lut != null;
        int p = program("why/combine", combineDefines(lutOn, point));
        use(p);
        final int colourSource = point ? lin.id : low.id;
        texture(p, "InputBuffer", 0, lin.id);
        texture(p, "WhyLow", 1, colourSource);
        texture(p, "WhyColour", 2, colourSource);
        texture(p, "WhyFused", 3, point ? lin.id : fused.id);
        texture(p, "WhyDetailRef", 4, colourSource);
        texture(p, "WhyLumaS", 5, colourSource);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE6);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutOn ? lutTex : dummy3d);
        set(p, "FilmLut", 6);
        colour(p);
        set(p, "fU", point ? 1 : 2);
        set(p, "colourFU", point ? 1 : 2);
        if (point) {
            texture(p, "FusedCurve", 8, curveTex);
            GLES20.glUniform2f(loc(p, "fusedCurveU"), FusedCurve.LOG2_MIN, FusedCurve.PER_EV);
        }
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
            float[] aces = WhyTone.acesUniform();
            GLES20.glUniform4f(loc(p, "acesU"), aces[0], aces[1], aces[2], aces[3]);
            float[][] uchi = WhyTone.uchimuraUniforms();
            GLES20.glUniform4f(loc(p, "uchiAU"), uchi[0][0], uchi[0][1], uchi[0][2], uchi[0][3]);
            GLES20.glUniform4f(loc(p, "uchiBU"), uchi[1][0], uchi[1][1], uchi[1][2], uchi[1][3]);
            float[] curve = WhyTone.curveSamples();
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
        final String name = name(asset, defines);
        Integer cached = programs.get(name);
        if (cached != null) return cached;
        // P312: from the kept driver binary (prepare() links them before the first draw); compiled here only for a variant it did
        // not foresee (a setting changed while the viewfinder runs)
        final int p = GLProg.viewfinderProgram(cacheKey(asset, defines));
        if (p == 0) throw new IllegalStateException("program " + name);
        programs.put(name, p);
        return p;
    }

    private static String name(String asset, String[][] defines) {
        StringBuilder key = new StringBuilder(asset);
        if (defines != null) for (String[] d : defines) key.append('|').append(d[0]).append('=').append(d[1]);
        return key.toString();
    }

    /** P312: the program's key in the driver-binary cache (GLProg.viewfinderKey: its full sources), made once per name. */
    private final HashMap<String, String> cacheKeys = new HashMap<>();

    private String cacheKey(String asset, String[][] defines) {
        return cacheKeys.computeIfAbsent(name(asset, defines), n -> {
            ArrayList<String[]> list = new ArrayList<>();
            if (defines != null) for (String[] d : defines) list.add(d);
            String source = GLInterface.loadShader(ScameraApp.getAssetLoader().getString("shaders/" + asset + ".glsl"), list);
            return GLProg.viewfinderKey(VS, source, "vPosition");
        });
    }

    /** The defines of why/combine for the current settings and film LUT (P314: {@code point}, the per-pixel fusion). */
    private String[][] combineDefines(boolean lutOn, boolean point) {
        ArrayList<String[]> defs = new ArrayList<>();
        if (point) defs.add(new String[]{"POINT_FUSED", "1"});
        if (colourOn) defs.add(new String[]{"COLOUR_LOOK", "1"});
        if (toneOp != ToneCurve.AGX) defs.add(new String[]{"TONE_OP", "1"});
        if (lutOn) defs.add(new String[]{"FILM_LUT", "1"});
        return defs.toArray(new String[0][]);
    }

    /**
     * P312: the programs draw() uses with the current settings: {asset, defines} (both why/combine variants with a film LUT on);
     * P314: with vf_why 1 only why/combine's POINT_FUSED variants, with 2 the guided-filter chain's programs.
     */
    private ArrayList<Object[]> programsWanted() {
        refreshSettings();
        ArrayList<Object[]> out = new ArrayList<>();
        final boolean point = mode() != LOCAL;
        if (!point) {
            out.add(new Object[]{"why/low", null});
            for (int mode = 0; mode <= 3; mode++) out.add(new Object[]{"why/gf", new String[][]{{"MODE", String.valueOf(mode)}}});
            for (int mode = 0; mode <= 1; mode++) out.add(new Object[]{"why/ab", new String[][]{{"MODE", String.valueOf(mode)}}});
            out.add(new Object[]{"why/box", null});
        }
        out.add(new Object[]{"why/combine", combineDefines(false, point)});
        if (lutStrength > 0f) out.add(new Object[]{"why/combine", combineDefines(true, point)});
        return out;
    }

    /** P312: the cache keys of the programs the next draw needs (for ViewfinderPrograms' background compile). GL thread. */
    public ArrayList<String> programKeys() {
        ArrayList<String> keys = new ArrayList<>();
        for (Object[] w : programsWanted()) keys.add(cacheKey((String) w[0], (String[][]) w[1]));
        return keys;
    }

    /**
     * P312: links the programs of the next draw before it - from their kept driver binaries at once; a missing one is compiled
     * here, at most one per call, unless {@code compilingElsewhere} has it or {@code sendOnce} sends it there (then false: the
     * caller keeps what it shows and asks again with the next frame). True when they are all linked, or when the tone failed
     * (draw() then says so).
     */
    public boolean prepare(java.util.function.Predicate<String> compilingElsewhere, java.util.function.Predicate<String> sendOnce) {
        if (failed) return true;
        try {
            if (!inited && !init()) return !fail("init");
            boolean compiled = false;
            for (Object[] w : programsWanted()) {
                final String asset = (String) w[0];
                final String[][] defines = (String[][]) w[1];
                final String name = name(asset, defines);
                if (programs.containsKey(name)) continue;
                final String key = cacheKey(asset, defines);
                // pending first: the background thread keeps the binary before it lets the key go
                if (compilingElsewhere.test(key)) return false;
                int p = GLProg.cachedViewfinderProgram(key);
                if (p == 0) {
                    // a binary refused or never kept: once to the background (sendOnce true), then compiled here
                    if (compiled || sendOnce.test(key)) return false;
                    p = GLProg.linkViewfinderProgram(key);
                    compiled = true;
                    if (p == 0) return !fail("program " + name);
                }
                programs.put(name, p);
            }
            return true;
        } catch (RuntimeException e) {
            return !fail(e.toString());
        }
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
