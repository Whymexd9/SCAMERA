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
 * display luma at effS 1.5-2, host model of a 2x ISZ merge). P333: the photo's fusion is a Laplacian pyramid now (WhyFusion); vf_why 2
 * keeps the guided filter (the photo's fusion before it): equal to the photo on flat areas, not at edges.
 *
 * <p>P365 (owner, 2026-10-27: «В арк камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»; the reference
 * viewfinder draws its look in one pass from the RAW texture): {@link #drawFused} - vf_why 1 in one pass, why/combine with
 * POINT_FUSED 1 and RAW_SOURCE 1 reading the RAW frame itself (utils/import_vfraw.glsl: rawdevelop_fs's linear values, the 3 x 3
 * colour neighbourhood from the same filtered quads), the same AE, curve and uniforms as {@link #draw}. No RGBA16F linear frame,
 * one GPU submission instead of two (three with the copy to the screen: MainRenderer draws it straight to the screen when no
 * filter, peaking or smaller tone size needs its target). scam_dev vf_fused 0: the two passes of P314. Any failure turns the fused
 * pass off for the context ({@link #fusedFailed()}): the passes of P314 draw instead (then the viewfinder's own look, then the
 * ISP picture, as before).
 *
 * <p>P378 review (P301: «Результат должен совпадать с итоговым фото»; the photo's sky term had no counterpart here): vf_why 1 takes
 * the photo's sky term too - every meter run decides the sky on the meter's own quads (WhySkyMask.Cells.ofQuads, the photo's
 * decision), every drawn frame eases the shown cells, levels and backlit share towards it in the scene's terms (LiveSky, as the
 * shown tone of P383), and why/combine SKY 1 takes them as its WhySky cells with the pixel's own gate in the shader. Not with a
 * film (the photo has none then), nor with vf_why 2.
 */
public final class LiveWhyTone {
    private static final String TAG = "LiveWhyTone";
    private static final String VS = "#version 310 es\nin vec4 vPosition;\nvoid main() {\n    gl_Position = vPosition;\n}\n";
    /** The AE runs again every this many frames (WhyAe on the CPU cells, or on a factor-8 whyLow read back with vf_why 2). */
    static final int AE_EVERY = 4;
    /**
     * P383: vf_why 1 meters every METER_FRAMES-th new frame once METER_NS passed since the last run - every 2nd frame at 30 and at 10
     * frames a second (the shown tone follows the scene with half the old delay: 0.2 s instead of 0.4 s at 10 frames a second; at
     * most 20 runs a second, ~2x the old cost, not every frame while a merge holds the GPU at 8 - 14 frames a second).
     */
    static final long METER_NS = 50_000_000L;
    static final int METER_FRAMES = 2;
    /** P383: scam_dev vf_ae_trace - every new frame's exposure and gain logged for this long after it is switched on. */
    static final long TRACE_MS = 5000;
    /** P314: scam_dev.txt vf_why - the viewfinder's own look, the photo's tone per pixel (the default), the guided-filter chain. */
    public static final int OWN = 0, POINT = 1, LOCAL = 2;
    /** P314: the viewfinder's AE line (WhyAe.describe, as the photo's SMART_HDR line) is logged at most this often. */
    static final long AE_LOG_MS = 5000;
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
     * P365: scam_dev.txt "vf_fused": vf_why 1 in one fused pass; 0 (default) = the two passes of P314. Off by default: on the OPPO
     * Find X7 Ultra (Adreno 750) the fused pass toned 4 frames a second against 27 for P314's passes (its 3 x 3 neighbourhood of
     * range-filtered quads is 45 gathers and 144 exp a pixel - nine times the first pass's work - where P314 computes each linear
     * pixel once into the tone target).
     */
    public static boolean fusedSwitch() {
        return PreferenceKeys.scamDevSwitch("vf_fused", false);
    }

    /** P365: whether the next draw is the fused pass ({@link #drawFused}): vf_fused, vf_why 1, neither failed in this context. */
    public boolean fused() {
        return !failed && !fusedFailed && mode() == POINT && fusedSwitch();
    }

    /** P365: the fused pass failed in this context (the passes of P314 draw instead). */
    public boolean fusedFailed() {
        return fusedFailed;
    }

    /**
     * P365: the set of programs the draws need now (0: none, vf_why 0; else per vf_why and whether it is fused) - MainRenderer asks
     * for a set's background compile when this changes.
     */
    public int programSet() {
        final int mode = failed ? OWN : mode();
        return mode == OWN ? 0 : 2 * mode + (fused() ? 1 : 0);
    }

    /**
     * P365: the RAW frame of the fused pass (MainRenderer: LiveRawRenderer.bindFrame / drawBound with the viewfinder's vertices,
     * rotation and mirror).
     */
    public interface RawSource {
        /** Binds the frame to the program {@code program} (its RAW uniforms, units 9 and 10); false without a frame to draw. */
        boolean bind(int program);

        /** Whether the bound frame is new (not drawn before): the AE counts and runs on new frames. */
        boolean fresh();

        /** The bound frame with its visible crop and colour transform (the CPU AE meters it). */
        com.particlesdevs.photoncamera.processing.LiveWhyMeter.View view();

        /** Draws the bound program over the viewport with the frame's vertices. */
        void draw();
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
    /** P365: the fused pass failed in this context. */
    private boolean fusedFailed;
    private final Tex lin = new Tex(), low = new Tex(), aeLow = new Tex(), fused = new Tex();
    private final Tex[] means = {new Tex(), new Tex(), new Tex()}, ab = {new Tex(), new Tex()}, boxTmp = {new Tex(), new Tex()};
    private final FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
    private FloatBuffer aePixels;
    private WhyAe.Result ae;
    /**
     * P383: the tone shown, one drawn frame closer to {@link #ae} each time (ToneEase: before, it moved once per AE run - steps every
     * fourth frame). P369: the film's exposure (WhyAe.filmAe: the meter before the highlight overflow's cut, {@link #filmAeTarget})
     * eases as ours does, so a cut that comes and goes with a bright window does not pump the film's grey while ours eases in.
     */
    private final ToneEase shown = new ToneEase();
    private float filmAeTarget;
    private float[] emTarget;
    private long easedAtNs;
    /**
     * P383: the drawn frames' exposure (FrameExposure.Track), the session and frame it saw last, the exposure of the frame the last
     * AE result metered, when that ran, and whether a new session's first frame meters at once.
     */
    private final FrameExposure.Track track = new FrameExposure.Track();
    private int trackedSession = -1, trackedVersion = -1, sinceMeter;
    private double meteredLogP;
    private long meterAtNs;
    private boolean meterNow;
    /** P383: scam_dev vf_ae_scene (the frames drawn against their own exposure; 0: as before, for an A/B) and vf_ae_trace. */
    private boolean aeScene = true, aeTrace;
    private long traceUntilMs;
    private int frames;
    /**
     * P314: the fusion's curve (vf_why 1): its texture (R32F FusedCurve.SIZE x 1), the AE result's values (P383: the shown ones ease
     * towards them, {@link #shown}), and the AE result it was filled for.
     */
    private int curveTex;
    private final float[] curve = new float[FusedCurve.SIZE];
    private FloatBuffer curveBuffer;
    private WhyAe.Result curveOf;
    /** P314: the CPU AE of vf_why 1. */
    private final com.particlesdevs.photoncamera.processing.LiveWhyMeter meter = new com.particlesdevs.photoncamera.processing.LiveWhyMeter();
    /**
     * P378 review: the sky term (vf_why 1): its shown state (LiveSky), its cells' texture (RGBA16F in the tone's orientation, unit
     * SKY_UNIT), whether the settings want it, the lens' f-number for the absolute EV, the logP its texture was made for and the
     * last decision's line for the AE log.
     */
    private final LiveSky sky = new LiveSky();
    static final int SKY_UNIT = 11;
    private int skyTex, skyTexW, skyTexH;
    private boolean skyWanted;
    private float aperture = 1.8f;
    private double skyLogP = Double.NaN;
    private String skyLog = "";
    /** P378 review: the cells' upload, reused (~48 KB a frame while the sky eases). */
    private float[] skyData;
    private FloatBuffer skyBuffer;
    /** P378 review: this draw takes the sky term (SKY 1; chosen before the program, from the sky shown at the last frame). */
    private boolean skyOnDraw;
    private long aeLogAt = -AE_LOG_MS;
    private long settingsAt = -SETTINGS_MS;
    // settings snapshot
    private WhyAe.Settings settings;
    private ColourLook look;
    private boolean colourOn;
    private int toneOp;
    private float[] lw = WhyTone.LAYER_WEIGHTS.clone();
    private float center, smooth, gammaInv, toe, eps, mono, inScale, macro, hlWhite, filmToe, deltaChroma, clarity, flatProtect;
    /** P369 (viewfinder-vibhl): the light-tone vibrance (P341), as WhyCombine sets it. */
    private float vibHl;
    /** P393: the light tones' share of the mid-tone chroma (LIGHT_CHROMA), as WhyCombine sets it. */
    private float lightChroma;
    /** P395: the orange-yellows turned towards yellow (yellowHueU), as WhyCombine sets it. */
    private float yellowHue;
    /** P398: a pale light tone towards white (hlNeutralU), as WhyCombine sets it. */
    private float hlNeutral;
    /** P402: the v2.07 fusion layout's base of the tone (ARK_BASE), as WhyCombine sets it. */
    private boolean arkBase;
    /** P402: the AgX inset's strength (AGX_INSET below 1), as WhyCombine sets it. */
    private float inset = 1f;
    /** P404: the display standard (GAMMA_TYPE), as WhyCombine sets it. */
    private int gammaType;
    /** P404: the reference app's vibrance terms (ARK_VIB), as WhyCombine sets them; null: none. */
    private float[][] arkVib;
    private float[] vib = new float[3];
    /** P393: the light-tone vibrance's shape for pale colour, as WhyCombine sets it. */
    private float[] vibHlShape = WhyTone.VIBRANCE_HL_SHAPE.clone();
    private int radius;
    /** P314: the box radius of this draw (vf_why 2): radius scaled from the viewport to the tone size, the same footprint. */
    private int boxRadius = 1;
    /** A table the loader built: its key (lutWanted when it was asked for) and whether it is the film's scene table (P369). */
    private static final class Loaded {
        final String key;
        final FilmLut lut;
        final boolean scene;

        Loaded(String key, FilmLut lut, boolean scene) {
            this.key = key;
            this.lut = lut;
            this.scene = scene;
        }
    }

    private volatile String lutWanted = "";
    private volatile Loaded lutLoaded;
    private FilmLut lutShown;
    /** P369: the table shown is the film's scene table (FILM_SIM 1), not the chosen LUT's (FILM_LUT 1). */
    private boolean lutShownScene;
    private float lutStrength;
    /** P369: the film simulation of the settings now (null: none) and its strength (the shader's mix). */
    private com.particlesdevs.photoncamera.processing.color.FilmSim.Settings filmNow;
    private float filmStrength;

    /** A new GL context: everything made in the old one is gone with it. */
    public void onContextCreated() {
        programs.clear();
        locations.clear();
        ones = dummy3d = lutTex = 0;
        inited = false;
        lutShown = null;
        failed = false;
        fusedFailed = false;
        for (Tex t : new Tex[]{lin, low, aeLow, fused, means[0], means[1], means[2], ab[0], ab[1], boxTmp[0], boxTmp[1]})
            t.id = t.fbo = t.w = t.h = 0;
        ae = null;
        frames = 0;
        curveTex = 0;
        curveOf = null;
        shown.reset();
        easedAtNs = 0;
        track.reset();
        trackedSession = trackedVersion = -1;
        meterAtNs = 0;
        sky.reset();
        skyTex = skyTexW = skyTexH = 0;
        skyLogP = Double.NaN;
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
                final com.particlesdevs.photoncamera.processing.LiveWhyMeter.View view = frame.get();
                if (meterDue(observe(view))) {
                    if (!runAePoint(view)) return fail("ae");
                }
                if (curveOf != ae) fillCurve(ae);
                final float aeNow = easeShown(true);
                // P378 review: the sky shown now, its cells in the tone's orientation
                skyOnDraw = uploadSky(view);
                combine(ae, aeNow, outFbo, w, h, true);
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
            final com.particlesdevs.photoncamera.processing.LiveWhyMeter.View drawnView = frame.get();
            observe(drawnView);
            if (ae == null || meterNow || frames++ % AE_EVERY == 0) {
                // the drawn frame's ISO, the analog ceiling and its noise model
                final com.particlesdevs.photoncamera.processing.LiveWhyMeter.View v = drawnView;
                final com.particlesdevs.photoncamera.processing.LiveRawFrame.Frame f = v == null ? null : v.frame;
                if (!runAe(w, h, f == null ? 100 : f.iso, f == null ? 6400 : f.maxAnalogIso, f == null ? 0 : f.noiseS, f == null ? 0 : f.noiseO))
                    return fail("ae");
            }
            final WhyAe.Result r = ae;
            final float aeNow = easeShown(false);
            skyOnDraw = false;
            // the fusion, as WhyFusion (no bracket denoise: a single frame has no data above the clip)
            final float[] em = shown.em;
            final int hw = (lw2 + 1) / 2, hh = (lh2 + 1) / 2;
            for (int mode = 0; mode < 3; mode++) {
                target(means[mode], hw, hh, GLES30.GL_RGBA32F, GLES20.GL_NEAREST);
                int g = program("why/gf", gammaType != 0 ? new String[][]{{"MODE", String.valueOf(mode)}, {"GAMMA_TYPE", String.valueOf(gammaType)}} : new String[][]{{"MODE", String.valueOf(mode)}});
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
            int g3 = program("why/gf", gammaType != 0 ? new String[][]{{"MODE", "3"}, {"GAMMA_TYPE", String.valueOf(gammaType)}} : new String[][]{{"MODE", "3"}});
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

    /**
     * P365: vf_why 1 in one pass - why/combine with POINT_FUSED 1 and RAW_SOURCE 1 into {@code outFbo} at the viewport {@code x},
     * {@code y}, {@code w} x {@code h} (MainRenderer: the screen's own viewport when the tone size is the viewport and no filter or
     * peaking pass follows, else the tone target at 0, 0), the RAW frame bound by {@code raw}. The AE, the fusion's curve, the
     * settings, the colour look and the film LUT as {@link #draw}; the AE counts new frames only (P383: a redraw of the same frame
     * still eases the shown tone on). The default framebuffer and the viewport are put back. False: nothing drawn - no frame, or
     * the fused pass failed ({@link #fusedFailed()}: off for the context).
     */
    public boolean drawFused(RawSource raw, int outFbo, int x, int y, int w, int h) {
        if (failed || fusedFailed) return false;
        int[] viewport = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0);
        try {
            if (!inited && !init()) return fusedFail("init");
            refreshSettings();
            final FilmLut lut = lutNow();
            final boolean lutOn = lutTex != 0 && lut != null;
            // P378 review: the sky term with the sky shown at the last frame (a sky coming in or going out is at a weight of ~0 then)
            skyOnDraw = skyWanted && sky.shown() && skyTex != 0;
            final int p = program("why/combine", combineDefines(lutOn, lutShownScene, true, true));
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo);
            GLES20.glViewport(x, y, w, h);
            if (!raw.bind(p)) return false;
            final com.particlesdevs.photoncamera.processing.LiveWhyMeter.View view = raw.view();
            if (meterDue(observe(view))) {
                if (!runAePoint(view)) return fusedFail("ae");
            }
            if (curveOf != ae) fillCurve(ae);
            final float aeNow = easeShown(true);
            // P378 review: the shown sky's cells (a sky shown first here takes the SKY program from the next frame)
            uploadSky(view);
            use(p);
            // the textures of P314's linear frame are not read with RAW_SOURCE 1: the 2 x 2 placeholder on their units
            final String[] inputs = {"InputBuffer", "WhyLow", "WhyColour", "WhyFused", "WhyDetailRef", "WhyLumaS"};
            for (int unit = 0; unit < inputs.length; unit++) texture(p, inputs[unit], unit, ones);
            combineUniforms(p, ae, aeNow, true, lutOn, lut);
            GLES20.glUniform2i(loc(p, "vpOriginU"), x, y);
            GLES20.glUniform2i(loc(p, "vpSizeU"), w, h);
            raw.draw();
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) return fusedFail("GL error 0x" + Integer.toHexString(error));
            return true;
        } catch (RuntimeException e) {
            return fusedFail(e.toString());
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

    /** P365: the fused pass off for this context (the passes of P314 draw instead). */
    private boolean fusedFail(String why) {
        fusedFailed = true;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        Log.e(TAG, "the fused WHY pass on the viewfinder is off for this context (the two passes instead): " + why);
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
        aeScene = PreferenceKeys.scamDevSwitch("vf_ae_scene", true);
        final boolean trace = PreferenceKeys.scamDevSwitch("vf_ae_trace", false);
        if (trace && !aeTrace) traceUntilMs = now + TRACE_MS;
        aeTrace = trace;
        // P320: a DCG session's frames take the photo's DCG rules: the night factor reads the gain (the reported ISO over the
        // mode's range scale), a bright scene keeps its exposure
        final android.hardware.camera2.CameraCharacteristics camera = com.particlesdevs.photoncamera.capture.CaptureController.mCameraCharacteristics;
        settings.dcg = com.particlesdevs.photoncamera.capture.Dcg14Mode.frames(camera);
        settings.dcgIsoScale = settings.dcg ? liveDcgIsoScale(camera) : 1f;
        // P370 (B4): the photo's AgX saturation of the OPPO tuned matrix when the tuned matrix renders this viewfinder (PhotoMatrix)
        settings.ccmSat = WhyTone.ccmSatComp(com.particlesdevs.photoncamera.processing.color.PhotoMatrix.viewfinderTuned);
        look = WhyTone.colourLook();
        colourOn = !look.shaderNeutral();
        toneOp = WhyTone.toneOperator();
        lw = WhyTone.layerWeights();
        center = WhyTone.fusionCenter();
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
        vibHlShape = WhyTone.vibranceHlShape();
        vibHl = WhyTone.vibranceHl();
        lightChroma = WhyTone.lightChroma();
        yellowHue = WhyTone.yellowHue();
        hlNeutral = WhyTone.hlNeutral();
        arkBase = WhyTone.arkLayout();
        inset = WhyTone.insetStrength();
        gammaType = WhyTone.gammaType();
        arkVib = WhyTone.arkVibrance();
        FusedCurve.GAMMA_TYPE[0] = gammaType;
        // P378 review: the lens' f-number (the sky's absolute EV), as Parameters reads it
        float[] apertures = camera != null ? camera.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) : null;
        aperture = apertures != null && apertures.length > 0 && apertures[0] > 0f ? apertures[0] : 1.8f;
        try {
            final float userStrength = PreferenceKeys.filmLutStrength();
            final String user = userStrength > 0f ? PreferenceKeys.filmLut() : "";
            final com.particlesdevs.photoncamera.processing.color.FilmSim.Settings sim =
                    com.particlesdevs.photoncamera.processing.color.FilmSim.current();
            filmNow = sim;
            filmStrength = sim != null ? sim.strength : 0f;
            if (sim != null) {
                // P369: the photo's scene feed (FILM_SIM): the film's scene table with the chosen LUT on its print
                lutStrength = 0f;
                final String key = com.particlesdevs.photoncamera.processing.color.FilmChain.sceneKey(sim, user, userStrength);
                if (!key.equals(lutWanted)) {
                    lutWanted = key;
                    lutLoaded = null;
                    LUT_LOADER.execute(() -> {
                        if (!key.equals(lutWanted)) return; // settings moved on while queued: no table nobody waits for
                        final com.particlesdevs.photoncamera.processing.color.FilmChain.Scene scene =
                                com.particlesdevs.photoncamera.processing.color.FilmChain.scene(ScameraApp.getAppContext(), sim, user, userStrength);
                        if (key.equals(lutWanted)) lutLoaded = new Loaded(key, scene != null ? scene.lut : null, true);
                    });
                }
                return;
            }
            final com.particlesdevs.photoncamera.processing.color.FilmChain.State chain =
                    com.particlesdevs.photoncamera.processing.color.FilmChain.current(null, user, userStrength);
            lutStrength = chain.strength;
            final String name = lutStrength > 0f ? chain.key : "";
            if (!name.equals(lutWanted)) {
                lutWanted = name;
                lutLoaded = null;
                if (!name.isEmpty()) LUT_LOADER.execute(() -> {
                    FilmLut lut = com.particlesdevs.photoncamera.processing.color.FilmChain.lut(ScameraApp.getAppContext(), name);
                    if (name.equals(lutWanted)) lutLoaded = new Loaded(name, lut, false);
                });
            }
        } catch (RuntimeException e) {
            lutStrength = 0f;
            filmNow = null;
            filmStrength = 0f;
        } finally {
            // P378 review: the sky term as the photo has it - on (hybrid_why_sky), never with a film; vf_why 1 only
            skyWanted = WhyTone.sky() > 0f && filmNow == null && mode() == POINT;
            meter.keepQuads = skyWanted;
            if (!skyWanted) sky.decided(null, 0f, 0.0);
        }
    }

    /**
     * P369: what the viewfinder's look depends on beyond the frame (MainRenderer's P319 tone reuse): the table loaded for the
     * settings (a film's scene table or the chosen LUT, once it is ready) and the strengths.
     */
    public int lookKey() {
        final Loaded l = lutLoaded;
        return 31 * (31 * (l == null ? 0 : System.identityHashCode(l)) + Float.floatToIntBits(lutStrength)) + Float.floatToIntBits(filmStrength);
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
        filmAeTarget = WhyAe.filmAe(r);
        emTarget = WhyFusion.liveExposures(r);
        metered();
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
        frameNoiseS = noiseS;
        frameNoiseO = noiseO;
    }

    /** P391: the drawn frame's one-frame noise model (variance S x + O of the normalised raw), for the fusion's noise-bound floor. */
    private double frameNoiseS, frameNoiseO;

    /**
     * P391: import why_fusion deepNoiseU of the viewfinder at the AE {@code ae} - the photo's (WhyFusion.guideNoise) with the merge's
     * expected share of the frame's noise (its frame map is the photo's alone): {tau, S_I, O_I}, zeros without a model.
     */
    private float[] deepNoise(float ae) {
        final float[] n = WhyFusion.guideNoise((float) frameNoiseS, (float) frameNoiseO, WhyTone.expectedMerged(), ae, inScale, WhyTone.noiseScale());
        final float tau = n == null ? 0f : WhyTone.deepNoise();
        return tau > 0f ? new float[]{tau, n[0], n[1]} : new float[]{0f, 0f, 0f};
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
        filmAeTarget = WhyAe.filmAe(r);
        emTarget = WhyFusion.liveExposures(r);
        metered();
        ae = r;
        if (skyWanted) decideSky(v, r);
        final long now = SystemClock.uptimeMillis();
        if (now - aeLogAt >= AE_LOG_MS) {
            aeLogAt = now;
            Log.d(TAG, "viewfinder SMART_HDR (per pixel, " + v.width + "x" + v.height + " of " + f.width + "x" + f.height + (f.cellMean ? " cell means" : "")
                    + ", shown ae=" + (shown.on() ? shown.aeFor(track.logP()) : r.ae) + ", exposure 2^" + String.format(java.util.Locale.ROOT,
                    "%.2f", track.logP()) + ", moves refused " + track.takeRefused() + " suspect " + track.takeSuspect() + "): " + r.describe()
                    + (skyWanted ? "; sky: " + skyLog : ""));
        }
        return true;
    }

    /**
     * P378 review: the sky of the metered frame - the meter's quads (LiveWhyMeter.keepQuads) as the photo's cells, the frame's
     * absolute EV (its ISO, exposure time, the lens' f-number, inScale), the photo's top edge now (the gravity's rotation of the
     * sensor, the front mirror) and the AE's ceiling; into LiveSky in the scene's terms of the metered frame.
     */
    private void decideSky(com.particlesdevs.photoncamera.processing.LiveWhyMeter.View v, WhyAe.Result result) {
        final float[] quads = meter.lastQuads();
        final int[] grid = meter.lastGrid();
        final com.particlesdevs.photoncamera.processing.LiveRawFrame.Frame f = v.frame;
        if (quads == null || grid[0] < 2 || grid[1] < 2) {
            sky.decided(null, 0f, 0.0);
            skyLog = "none (no quads)";
            return;
        }
        // the metered result's own ceiling (the decision's, as the photo's: not a shown value)
        final float ceiling = Math.max(result.clip, result.hr);
        final WhySkyMask.Cells cells = WhySkyMask.Cells.ofQuads(quads, grid[0], grid[1], WhySkyMask.CLIP_LEVEL * ceiling);
        final float ev = WhySkyMask.evOffset(f.iso, f.exposureNs / 1e9, aperture, inScale);
        final WhySkyMask.Decision d = WhySkyMask.decide(cells, new WhySkyMask.Scene(ev, WhySkyMask.topEdge(cameraRotation(), frontMirror()), ceiling));
        sky.decided(d.mask, WhyTone.sky(), meteredLogP);
        skyLog = d.log;
    }

    /** The rotation a photo taken now gets (CaptureController: the gravity's turn of the sensor), 0 without a camera. */
    private static int cameraRotation() {
        try {
            final android.hardware.camera2.CameraCharacteristics c = com.particlesdevs.photoncamera.capture.CaptureController.mCameraCharacteristics;
            final Integer sensor = c != null ? c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) : null;
            return ScameraApp.getGravity().getCameraRotation(sensor != null ? sensor : 0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Whether a photo taken now is mirrored (Parameters: the front camera). */
    private static boolean frontMirror() {
        final android.hardware.camera2.CameraCharacteristics c = com.particlesdevs.photoncamera.capture.CaptureController.mCameraCharacteristics;
        final Integer facing = c != null ? c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) : null;
        return facing != null && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT;
    }

    /**
     * P378 review: the shown sky's cells into their texture (unit SKY_UNIT) when they moved or the drawn frame's exposure did:
     * LiveSky.texture in the tone's orientation of the view; false when nothing is shown.
     */
    private boolean uploadSky(com.particlesdevs.photoncamera.processing.LiveWhyMeter.View v) {
        if (!skyWanted || !sky.shown() || v == null) return false;
        final double logP = track.logP();
        if (skyTex != 0 && !sky.dirty() && logP == skyLogP) return true;
        final int[] size = new int[2];
        final float[] data = sky.texture(v.rotation, v.mirror, com.particlesdevs.photoncamera.processing.LiveWhyMeter.cover(v), logP, size, skyData);
        if (data == null) return false;
        skyData = data;
        final int n = 4 * size[0] * size[1];
        if (skyBuffer == null || skyBuffer.capacity() < n) skyBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        skyBuffer.clear();
        skyBuffer.put(data, 0, n).flip();
        final FloatBuffer b = skyBuffer;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + SKY_UNIT);
        if (skyTex == 0 || skyTexW != size[0] || skyTexH != size[1]) {
            if (skyTex != 0) GLES20.glDeleteTextures(1, new int[]{skyTex}, 0);
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            skyTex = t[0];
            skyTexW = size[0];
            skyTexH = size[1];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, skyTex);
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, skyTexW, skyTexH);
            params(GLES20.GL_TEXTURE_2D, GLES20.GL_LINEAR);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, skyTex);
        }
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, skyTexW, skyTexH, GLES20.GL_RGBA, GLES20.GL_FLOAT, b);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        skyLogP = logP;
        return true;
    }

    /** P314: the fusion's curve of an AE result (FusedCurve, the current fusion settings); P383: the shown one eases towards it. */
    private void fillCurve(WhyAe.Result r) {
        FusedCurve.DEEP_FLOOR[0] = WhyTone.deepFloor();
        System.arraycopy(deepNoise(r.ae), 0, FusedCurve.DEEP_NOISE, 0, 3);
        FusedCurve.fill(curve, WhyFusion.liveExposures(r), lw, center, smooth, gammaInv, toe, mono);
        curveOf = r;
    }

    /**
     * P383: the shown tone one drawn frame closer to the last AE result, by the time since the last drawn frame (ToneEase: the same
     * speed at 10 and 30 frames a second; a redraw of the same RAW frame moves it too, so the picture keeps easing on the screen);
     * {@code point}: the fused curve too, into its texture when it moved.
     */
    private float easeShown(boolean point) {
        final long now = System.nanoTime();
        final long dt = easedAtNs == 0 ? 0 : now - easedAtNs;
        easedAtNs = now;
        shown.toward(ae, filmAeTarget, emTarget, meteredLogP, dt);
        // P378 review: the sky's cells and levels as the tone, per drawn frame
        sky.ease(dt);
        if (point) {
            shown.curveToward(curve, dt);
            if (shown.curveMoved() || curveTex == 0) uploadCurve(shown.curve);
        }
        // P383: the drawn frame at its own exposure (FrameExposure): a move of the camera's exposure is cancelled in its frame
        return shown.aeFor(track.logP());
    }

    /**
     * P383: the drawn frame's exposure into the track (a new frame once); a new session starts the track and the shown tone over
     * (scene terms do not carry from one camera to another) and meters its first frame. True for a frame not seen before.
     */
    private boolean observe(com.particlesdevs.photoncamera.processing.LiveWhyMeter.View v) {
        final com.particlesdevs.photoncamera.processing.LiveRawFrame.Frame f = v == null ? null : v.frame;
        if (f == null) return false;
        if (f.session != trackedSession) {
            trackedSession = f.session;
            trackedVersion = -1;
            track.reset();
            shown.reset();
            sky.reset();
            skyLogP = Double.NaN;
            meterNow = true;
        }
        if (f.version == trackedVersion) return false;
        trackedVersion = f.version;
        sinceMeter++;
        final double reported = FrameExposure.log2Product(f), level = FrameExposure.log2Level(f);
        track.next(reported, level, aeScene);
        if (aeTrace && SystemClock.uptimeMillis() < traceUntilMs)
            Log.d(TAG, String.format(java.util.Locale.ROOT, "vf ae trace: t=%d exp=%d iso=%d auto=%b logP=%.3f level=%.3f drawnP=%.3f"
                            + " target=%.3f shown=%.3f ae=%.3f", f.publishedNanos / 1_000_000, f.exposureNs, f.iso, f.autoExposure, reported,
                    level, track.logP(), shown.on() ? shown.targetLogAe() : Double.NaN, shown.on() ? shown.logAe() : Double.NaN,
                    shown.on() ? shown.aeFor(track.logP()) : Double.NaN));
        return true;
    }

    /** P383: vf_why 1's meter runs on every METER_FRAMES-th new frame, METER_NS after the last run (at once: the first, a new session). */
    private boolean meterDue(boolean fresh) {
        return ae == null || meterNow || fresh && sinceMeter >= METER_FRAMES && System.nanoTime() - meterAtNs >= METER_NS;
    }

    /** P383: a new AE result: the exposure of the frame it metered, and when. */
    private void metered() {
        meteredLogP = track.logP();
        meterAtNs = System.nanoTime();
        meterNow = false;
        sinceMeter = 0;
    }

    /** P314: a fusion curve into its R32F texture (unit 8). */
    private void uploadCurve(float[] values) {
        if (curveBuffer == null)
            curveBuffer = ByteBuffer.allocateDirect(FusedCurve.SIZE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        curveBuffer.clear();
        curveBuffer.put(values).flip();
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
        set(p, "deepFloorU", WhyTone.deepFloor());
        final float[] dn = deepNoise(aeNow);
        GLES20.glUniform4f(loc(p, "deepNoiseU"), dn[0], dn[1], dn[2], 0f);
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
        final FilmLut lut = lutNow();
        final boolean lutOn = lutTex != 0 && lut != null;
        int p = program("why/combine", combineDefines(lutOn, lutShownScene, point, false));
        use(p);
        final int colourSource = point ? lin.id : low.id;
        texture(p, "InputBuffer", 0, lin.id);
        texture(p, "WhyLow", 1, colourSource);
        texture(p, "WhyColour", 2, colourSource);
        texture(p, "WhyFused", 3, point ? lin.id : fused.id);
        texture(p, "WhyDetailRef", 4, colourSource);
        texture(p, "WhyLumaS", 5, colourSource);
        combineUniforms(p, r, aeNow, point, lutOn, lut);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo);
        GLES20.glViewport(0, 0, w, h);
        quad(p);
    }

    /**
     * The film LUT of the settings, uploaded when it changed (lutTex; null: none). P369: with a film its scene table
     * ({@link #lutShownScene}); a table is taken only for the key it was asked for (a late load of an older key is not shown).
     */
    private FilmLut lutNow() {
        final Loaded l = lutLoaded;
        final boolean match = l != null && l.key.equals(lutWanted);
        FilmLut lut = (filmNow != null || lutStrength > 0f) && match ? l.lut : null;
        if (lut != lutShown) {
            if (lutTex != 0) GLES20.glDeleteTextures(1, new int[]{lutTex}, 0);
            lutTex = lut != null ? lut.upload() : 0;
            lutShown = lut;
        }
        lutShownScene = lut != null && l.scene;
        return lut;
    }

    /** why/combine's uniforms but its image inputs (units 0 - 5): the LUT (6), the gain map (7), the fusion's curve (8), the tone. */
    private void combineUniforms(int p, WhyAe.Result r, float aeNow, boolean point, boolean lutOn, FilmLut lut) {
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
        set(p, "signedHueU", WhyTone.signedHue() ? 1 : 0); // P370 (B2): the photo's hue of a colour outside sRGB
        set(p, "aeU", aeNow);
        set(p, "clipU", shown.clip); // P383: the shown (eased) values, as aeNow
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
        GLES20.glUniform4f(loc(p, "agxAU"), shown.slope, shown.sp, shown.tp, shown.sat);
        GLES20.glUniform4f(loc(p, "agxBU"), shown.minEv, shown.maxEv, shown.ev, toneOp == ToneCurve.AGX ? (float) r.look : 0f);
        if (toneOp != ToneCurve.AGX) {
            set(p, "toneOpU", toneOp);
            float[] aces = WhyTone.acesUniform();
            GLES20.glUniform4f(loc(p, "acesU"), aces[0], aces[1], aces[2], aces[3]);
            float[][] uchi = WhyTone.uchimuraUniforms();
            GLES20.glUniform4f(loc(p, "uchiAU"), uchi[0][0], uchi[0][1], uchi[0][2], uchi[0][3]);
            GLES20.glUniform4f(loc(p, "uchiBU"), uchi[1][0], uchi[1][1], uchi[1][2], uchi[1][3]);
            float[] curve = WhyTone.curveSamples();
            GLES20.glUniform4fv(loc(p, "curveU"), Math.min(64, curve.length / 4), curve, 0);
            set(p, "curveBoostU", WhyTone.curveBoost(r.contrastC));   // P404, as the photo
        }
        set(p, "ditherU", 1);
        set(p, "headroomU", 0);
        set(p, "hlWhiteU", hlWhite);
        GLES20.glUniform4f(loc(p, "vibHlShapeU"), vibHlShape[0], vibHlShape[1], vibHlShape[2], vibHlShape[3]);   // P393, as the photo
        // P369 (viewfinder-vibhl): the light-tone vibrance, as the photo (P301 parity)
        set(p, "vibHlU", vibHl);
        // P393: the photo's light-tone chroma (LIGHT_CHROMA, AgX only; no location without the define)
        set(p, "lightChromaU", lightChroma);
        set(p, "yellowHueU", yellowHue);   // P395, as the photo (no location without the define)
        set(p, "hlNeutralU", hlNeutral);   // P398, as the photo
        if (inset < 0.999f) set(p, "insetU", inset);   // P402, as the photo
        if (arkVib != null) {
            GLES20.glUniform4f(loc(p, "arkVibU"), arkVib[0][0], arkVib[0][1], arkVib[0][2], arkVib[0][3]);
            GLES20.glUniform3f(loc(p, "arkSkinU"), arkVib[1][0], arkVib[1][1], arkVib[1][2]);
        }
        if (skyOnDraw) {
            // P378 review: the photo's sky term (WhyCombine) on the shown cells - the level share eased (0 backlit), the tops in this
            // frame's log2 Y
            final float strength = WhyTone.sky();
            final float[] tone = WhyTone.skyTone(), tops = sky.tops(track.logP());
            texture(p, "WhySky", SKY_UNIT, skyTex);
            GLES20.glUniform4f(loc(p, "skyU"), sky.level(), tone[0], tone[1], tone[2]);
            GLES20.glUniform4f(loc(p, "skyMU"), strength, strength, strength * WhyTone.SKY_GLOW, WhyTone.SKY_VIBRANCE);
            GLES20.glUniform2f(loc(p, "skyTopU"), tops[0], tops[1]);
        }
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
        if (lutOn && lutShownScene) {
            // P369: the photo's film feed (WhyCombine): the film's strength, its exposure over the shown ae (the meter before the
            // highlight overflow's cut, eased as the shown ae, at the film's mid grey) and the log grid
            GLES20.glUniform2f(loc(p, "lutU"), filmStrength, (float) lut.size);
            set(p, "filmExpU", shown.filmOverAe() * com.particlesdevs.photoncamera.processing.color.FilmSim.METER_TO_FILM);
            GLES20.glUniform2f(loc(p, "filmLogU"), com.particlesdevs.photoncamera.processing.color.FilmSim.LOG_LO,
                    com.particlesdevs.photoncamera.processing.color.FilmSim.LOG_SPAN);
        } else if (lutOn) {
            GLES20.glUniform2f(loc(p, "lutU"), lutStrength, (float) lut.size);
            GLES20.glUniform3f(loc(p, "lutMinU"), lut.domainMin[0], lut.domainMin[1], lut.domainMin[2]);
            GLES20.glUniform3f(loc(p, "lutScaleU"), 1f / (lut.domainMax[0] - lut.domainMin[0]), 1f / (lut.domainMax[1] - lut.domainMin[1]),
                    1f / (lut.domainMax[2] - lut.domainMin[2]));
        }
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
            // P365: the fused pass's vertex shader is the RAW viewfinder's (its texCoord; attributes at locations 0 and 1)
            if (rawSource(defines))
                return GLProg.viewfinderKey(fusedVertexShader(ScameraApp.getAssetLoader().getString("shaders/preview/rawdevelop_vs.glsl")), source);
            return GLProg.viewfinderKey(VS, source, "vPosition");
        });
    }

    /** P365: whether the defines draw from the RAW frame (RAW_SOURCE 1: the fused pass). */
    static boolean rawSource(String[][] defines) {
        if (defines != null) for (String[] d : defines) if ("RAW_SOURCE".equals(d[0]) && "1".equals(d[1])) return true;
        return false;
    }

    /**
     * P365: the fused pass's vertex shader - preview/rawdevelop_vs.glsl as GLSL ES 3.10 (why/combine is compiled as 3.10, and a
     * program's stages must share the version); the source is otherwise the RAW viewfinder's own.
     */
    static String fusedVertexShader(String rawdevelopVs) {
        return rawdevelopVs.replaceFirst("#version 300 es", "#version 310 es");
    }

    /**
     * The defines of why/combine for the current settings and film LUT (P314: {@code point}, the per-pixel fusion; P365
     * {@code fused}: from the RAW frame in one pass; P369 {@code scene}: the table is a film's scene table).
     */
    private String[][] combineDefines(boolean lutOn, boolean scene, boolean point, boolean fused) {
        return combineDefines(lutOn, scene, point, fused, skyOnDraw);
    }

    /** {@link #combineDefines(boolean, boolean, boolean, boolean)} with the sky term ({@code withSky}: SKY 1, vf_why 1 only). */
    private String[][] combineDefines(boolean lutOn, boolean scene, boolean point, boolean fused, boolean withSky) {
        ArrayList<String[]> defs = new ArrayList<>();
        if (point) defs.add(new String[]{"POINT_FUSED", "1"});
        if (WhyTone.macroToe()) defs.add(new String[]{"MACRO_TOE", "1"});
        if (point && fused) defs.add(new String[]{"RAW_SOURCE", "1"});
        if (colourOn) defs.add(new String[]{"COLOUR_LOOK", "1"});
        if (toneOp != ToneCurve.AGX) defs.add(new String[]{"TONE_OP", "1"});
        // P393: the light tones of a saturated colour keep its mid-tone chroma, as the photo (AgX only)
        if (toneOp == ToneCurve.AGX && lightChroma > 0f) defs.add(new String[]{"LIGHT_CHROMA", "1"});
        if (yellowHue > 0f) defs.add(new String[]{"YELLOW_HUE", "1"});   // P395, as the photo
        if (hlNeutral > 0f) defs.add(new String[]{"HL_NEUTRAL", "1"});   // P398, as the photo
        if (arkBase) defs.add(new String[]{"ARK_BASE", "1"});   // P402, as the photo
        if (inset < 0.999f) defs.add(new String[]{"AGX_INSET", "1"});   // P402, as the photo
        if (gammaType != 0) defs.add(new String[]{"GAMMA_TYPE", String.valueOf(gammaType)});   // P404, as the photo
        if (arkVib != null) defs.add(new String[]{"ARK_VIB", "1"});   // P404, as the photo
        // P369: a film's scene table (FILM_SIM) or the chosen LUT (FILM_LUT), never both
        if (lutOn) defs.add(new String[]{scene ? "FILM_SIM" : "FILM_LUT", "1"});
        // P378 review: the sky term (the photo's SKY, its cells from LiveSky)
        if (withSky && point) defs.add(new String[]{"SKY", "1"});
        return defs.toArray(new String[0][]);
    }

    /**
     * P312: the programs draw() uses with the current settings: {asset, defines} (both why/combine variants with a film LUT on);
     * P314: with vf_why 1 only why/combine's POINT_FUSED variants, with 2 the guided-filter chain's programs; P365: with the fused
     * pass ({@link #fused()}) its RAW_SOURCE variants instead (the passes of P314 are built when it fails).
     */
    private ArrayList<Object[]> programsWanted() {
        refreshSettings();
        ArrayList<Object[]> out = new ArrayList<>();
        final boolean point = mode() != LOCAL;
        final boolean fused = point && fused();
        if (!point) {
            out.add(new Object[]{"why/low", null});
            for (int mode = 0; mode <= 3; mode++) out.add(new Object[]{"why/gf", new String[][]{{"MODE", String.valueOf(mode)}}});
            for (int mode = 0; mode <= 1; mode++) out.add(new Object[]{"why/ab", new String[][]{{"MODE", String.valueOf(mode)}}});
            out.add(new Object[]{"why/box", null});
        }
        out.add(new Object[]{"why/combine", combineDefines(false, false, point, fused, false)});
        // P369: with a film its FILM_SIM variant
        if (lutStrength > 0f || filmNow != null) out.add(new Object[]{"why/combine", combineDefines(true, filmNow != null, point, fused, false)});
        // P378 review: the sky term's variants (never with a film: the photo has none then)
        if (skyWanted && point) {
            out.add(new Object[]{"why/combine", combineDefines(false, false, point, fused, true)});
            if (lutStrength > 0f) out.add(new Object[]{"why/combine", combineDefines(true, false, point, fused, true)});
        }
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
                    // P365: a fused program that does not link turns the fused pass off; the caller asks for P314's set next
                    if (p == 0 && rawSource(defines)) return fusedFail("program " + name);
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
