package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.color.ColourLook;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/**
 * ARK photo tone of the SCAM Hybrid and SCAM HDR (the one tonemap of both routes): the ArkCam 1.23 / SCAM 9.6 tone of libfc_suppressor.so (Smart-HDR AE, exposure
 * fusion on a guided filter, OKLab grading, AgX Custom; research/hybrid5/tone_port.md sections 2 and 7) with the
 * high-frequency detail of the Sabre merge. Route: ArkStats -> ArkFusion -> ArkLumaSharpen -> ArkCombine. This class holds the per-shot state handed between those nodes, their settings and the colour
 * chain of ark/low.glsl and ark/combine.glsl.
 */
public final class ArkTone {
    private ArkTone() {}

    /** Per-shot state; the textures are closed by ArkCombine (all but none are needed after it). */
    public static final class State {
        public ArkAe.Result ae;
        /** G_CLEAN equivalent (half the sensor, RGBA16F) before and after bracket_denoise. */
        public GLTexture low, lowDn;
        /** 2x grid only: 2x2 mean of the output grid (the 1x-equivalent colour source of ArkCombine). */
        public GLTexture mid;
        /** Fused display luma of the exposure fusion (.r), arkLow size. */
        public GLTexture fused;
        /** Bounded reference of the detail delta: box mean of min(ae * Y709, 1) (.r), arkLow size; null without detail. */
        public GLTexture detailRef;
        /** Box factor of arkLow against the output grid (2 at 1x, 4 on the Sabre 2x grid). */
        public int factor = 2;
        /** Multiplier of the input to the G_CLEAN scale (ark_input_ev). */
        public float inScale = 1f;
        /** ArkLumaSharpen: ArkCam's sharpened luminance S(Ya) (.r) on the output grid, null without ARK sharpening. */
        public GLTexture lumaS;
        /** Domain multiplier of lumaS: Ya = min(sharpMul * Y709, 1) (1 = G_CLEAN scale, ae = the delta's domain). */
        public float sharpMul = 1f;
        /** P134: the colour chain's linear -> sRGB matrix of this shot (the white-balance rows of «Цвет» folded in). */
        float[] colourMatrix;

        void closeTextures() {
            if (low != null) low.close();
            if (lowDn != null && lowDn != low) lowDn.close();
            if (mid != null) mid.close();
            if (fused != null) fused.close();
            if (detailRef != null) detailRef.close();
            if (lumaS != null) lumaS.close();
            low = lowDn = mid = fused = detailRef = lumaS = null;
        }
    }

    /** Hybrid ARK setting pref_scam_hybrid_ark_&lt;key&gt; (scam_dev.txt "hybrid_ark_&lt;key&gt;" overrides it). */
    static float value(String key, float fallback) {
        return PreferenceKeys.hybridValue("ark_" + key, fallback);
    }

    /** AE / Smart-HDR / AgX settings (defaults: ArkCam 2.85 X8U effective values, tone_port.md section 3). */
    static ArkAe.Settings settings() {
        ArkAe.Settings s = new ArkAe.Settings();
        s.aeTarget = value("ae_target", 0.15f);
        s.maxBoost = value("ae_max_boost", 5.0f);
        s.minLimit = Math.min(value("ae_min_limit", 0.5f), s.maxBoost);
        s.hlOverflow = value("hl_overflow", 2.0f);
        float nightVal = value("night_thresh", 0.05f);
        if (nightVal >= 1.0f) nightVal = 0.05f; // migration of legacy ISO values (e.g. 100)
        s.nightThresh = nightVal;
        s.noiseMax = nightVal;
        s.nightDim = value("night_dim", 1.0f);
        s.metering = Math.round(value("metering", 0f));
        s.brightThresh = value("bright_thresh", 0.8f);
        s.darkThresh = value("dark_thresh", 15f);
        s.darkPixelThresh = value("dark_pixel_thresh", 10f);
        s.hlBoost = value("hl_boost", 1.0f);
        s.contrastBoost = value("contrast_boost", 0.5f);
        s.shadowStr = Math.max(value("shadow_str", 2.0f), 0f);   // pushCurrentLut 0x80db8
        s.highlightStr = value("highlight_str", 2.0f);
        s.efEnabled = true;
        s.look = Math.round(value("agx_look", 4f));
        s.slope = value("agx_slope", 2.7f);
        s.sp = value("agx_sp", 1.35f);
        s.tp = value("agx_tp", 1.6f);
        s.minEv = value("agx_min_ev", -8.5f);
        s.maxEv = value("agx_max_ev", 3.5f);
        s.sat = value("agx_sat", 1.0f);
        s.ev = value("agx_ev", 0.30f);
        return s;
    }

    /**
     * AgX saturation factor for shots rendered with the OPPO tuned ISP matrix (Find X7 Ultra). 0.6 matched ArkCam 2.85 with
     * the X8U config at night (research/hybrid5); against ArkCam SCAM 9.6 with its X7U config (owner's pair 2026-10-08, main and
     * 3x tele, indoor ~3800 K) 0.6 left half of ArkCam's chroma (C*ab x0.48, lightness-normalised), and the numpy reference of
     * the tone put the match at 1.1. 1 = no compensation.
     */
    static float ccmSatComp(Parameters p) {
        return p != null && p.oppoTunedCcm ? Math.max(value("ccm_sat", 1.1f), 0f) : 1f;
    }

    /**
     * P144: the tone operator of «Кривая AgX и ACES» - ToneCurve.AGX unless the own curve, Uchimura or ACES is switched on (the
     * settings keep one on at a time; should several be stored, that order wins).
     */
    static int toneOperator() {
        if (PreferenceKeys.hybridSwitch("ark_curve_on", false)) return com.particlesdevs.photoncamera.processing.color.ToneCurve.CUSTOM;
        if (PreferenceKeys.hybridSwitch("ark_uchi_on", false)) return com.particlesdevs.photoncamera.processing.color.ToneCurve.UCHIMURA;
        if (PreferenceKeys.hybridSwitch("ark_aces_on", false)) return com.particlesdevs.photoncamera.processing.color.ToneCurve.ACES;
        return com.particlesdevs.photoncamera.processing.color.ToneCurve.AGX;
    }

    /** ACES (ArkCam defaults): pre-gain 1, a 2.61, d 0.59, toe (the same toe as the inversion of the fusion target). */
    static float[] acesUniform() {
        return new float[]{value("aces_pre_gain", 1.0f), value("aces_a", 2.61f), value("aces_d", 0.59f), value("aces_toe", 0.05f)};
    }

    /** Uchimura (ArkCam defaults): pre-gain 1, post-gain 0.9, P 1, a 1 | m 0.22, l 0.4, c 1.33, b 0. */
    static float[][] uchimuraUniforms() {
        float m = Math.max(value("uchi_m", 0.22f), 0.01f), a = Math.max(value("uchi_a", 1.0f), 0.05f);
        // the shoulder needs P above the linear start (P - S1 = (P - m)(1 - l) > 0): the ranges allow P 0.5 with m 0.8
        float p = Math.max(value("uchi_p", 1.0f), m + 0.05f), l = Math.min(Math.max(value("uchi_l", 0.4f), 0f), 0.95f);
        return new float[][]{{value("uchi_pre_gain", 1.0f), value("uchi_post_gain", 0.9f), p, a},
                {m, l, value("uchi_c", 1.33f), value("uchi_b", 0.0f)}};
    }

    /**
     * P177 (owner, 2026-10-16: «для кастомных кривых добавь возможность визуализации кривых AGX/ACES и тд для сравнения ...
     * дай возможность их подгружать чтобы потом редактировать»): AgX, ACES or Uchimura with today's settings as a function of
     * the own curve's x, a display value as its y (ToneCurve.agxAt / acesAt / uchimuraAt).
     */
    public static java.util.function.DoubleUnaryOperator reference(int op) {
        if (op == com.particlesdevs.photoncamera.processing.color.ToneCurve.ACES) {
            final float[] a = acesUniform();
            return x -> com.particlesdevs.photoncamera.processing.color.ToneCurve.acesAt((float) x, a);
        }
        if (op == com.particlesdevs.photoncamera.processing.color.ToneCurve.UCHIMURA) {
            final float[][] u = uchimuraUniforms();
            return x -> com.particlesdevs.photoncamera.processing.color.ToneCurve.uchimuraAt((float) x, u[0], u[1]);
        }
        final float slope = value("agx_slope", 2.7f), sp = value("agx_sp", 1.35f), tp = value("agx_tp", 1.6f),
                minEv = value("agx_min_ev", -8.5f), maxEv = value("agx_max_ev", 3.5f), ev = value("agx_ev", 0.30f);
        return x -> com.particlesdevs.photoncamera.processing.color.ToneCurve.agxAt((float) x, slope, sp, tp, minEv, maxEv, ev);
    }

    /** The own curve's 256 samples (pref_scam_hybrid_ark_curve_points, ArkCam's S when empty). */
    static float[] curveSamples() {
        return com.particlesdevs.photoncamera.processing.color.ToneCurve
                .parse(PreferenceKeys.hybridString("ark_curve_points", com.particlesdevs.photoncamera.processing.color.ToneCurve.DEFAULT_POINTS))
                .samples(com.particlesdevs.photoncamera.processing.color.ToneCurve.SAMPLES);
    }

    /** ef_gamma; the mod takes 2.2 for a value at or below 0.1 [pushCurrentLut 0x80da4]. */
    static float gamma() {
        float g = value("gamma", 2.2f);
        return g > 0.1f ? g : 2.2f;
    }

    /** Box factor of arkLow: half the SENSOR grid, i.e. 2 at 1x and 4 on the Sabre 2x grid. */
    static int lowFactor(Parameters p) {
        return 2 * Math.max(1, Math.round(p.outputScale));
    }

    /** ARK size of a grid reduced by a box of f (the last box is clamped at the edge). */
    static Point reduced(Point size, int f) {
        return new Point((size.x + f - 1) / f, (size.y + f - 1) / f);
    }

    /** ProPhoto -> sRGB matrix of the render. */
    static float[] intermediateToSRGB(Parameters p) {
        return p.CCT.matrix;
    }

    /**
     * P134 «Цвет»: the colour settings of the shot (pref_scam_hybrid_colour_*; scam_dev.txt "hybrid_colour_<key>" overrides).
     * Every default is «no change».
     */
    /** The «Цвет» rows of the current module (also the RAW viewfinder's look, P134). */
    public static ColourLook colourLook() {
        ColourLook c = new ColourLook();
        c.warmth = PreferenceKeys.hybridValue("colour_warmth", 0f);
        c.tint = PreferenceKeys.hybridValue("colour_tint", 0f);
        c.keepWarm = PreferenceKeys.hybridValue("colour_keep_warm", 0f);
        c.keepCool = PreferenceKeys.hybridValue("colour_keep_cool", 0f);
        c.skinProtect = PreferenceKeys.hybridValue("colour_skin_protect", 0f);
        c.skinHue = PreferenceKeys.hybridValue("colour_skin_hue", 0f);
        c.skinEven = PreferenceKeys.hybridValue("colour_skin_even", 0f);
        c.vibShadows = PreferenceKeys.hybridValue("colour_vib_shadows", 0f);
        c.vibHighlights = PreferenceKeys.hybridValue("colour_vib_highlights", 0f);
        final String[] parts = {"hue", "sat", "light"};
        for (int b = 0; b < ColourLook.BANDS.length; b++)
            for (int k = 0; k < 3; k++) c.mix[b * 3 + k] = PreferenceKeys.hybridValue("colour_mix_" + ColourLook.BANDS[b] + "_" + parts[k], 0f);
        for (int z = 0; z < 3; z++) {
            c.gradeHue[z] = PreferenceKeys.hybridValue("colour_grade_" + ColourLook.ZONES[z] + "_hue", 0f);
            c.gradeStr[z] = PreferenceKeys.hybridValue("colour_grade_" + ColourLook.ZONES[z] + "_str", 0f);
        }
        c.gradeBalance = PreferenceKeys.hybridValue("colour_grade_balance", 0f);
        c.gradeBlend = PreferenceKeys.hybridValue("colour_grade_blend", 50f);
        c.mono = Math.max(0, java.util.Arrays.asList(ColourLook.MONO).indexOf(PreferenceKeys.hybridString("colour_mono", "off")));
        c.hueLock = Math.max(0, java.util.Arrays.asList(ColourLook.HUE_LOCK).indexOf(PreferenceKeys.hybridString("colour_hue_lock", "full")));
        c.gamut = PreferenceKeys.hybridValue("colour_gamut", 0f);
        final String[] primaries = {"red", "green", "blue"};
        for (int p = 0; p < 3; p++) {
            c.calHue[p] = PreferenceKeys.hybridValue("colour_cal_" + primaries[p] + "_hue", 0f);
            c.calSat[p] = PreferenceKeys.hybridValue("colour_cal_" + primaries[p] + "_sat", 0f);
        }
        return c;
    }

    /**
     * Linear -> sRGB matrix of the colour chain: the camera's ProPhoto -> sRGB, with the white-balance rows of «Цвет»
     * (warmth, tint, the scene light kept) on top; once per shot, so the AE statistics and the photo share it.
     */
    static float[] colourMatrix(PostPipeline pipeline) {
        State st = pipeline.ark;
        if (st != null && st.colourMatrix != null) return st.colourMatrix;
        Parameters p = pipeline.mParameters;
        float[] m = intermediateToSRGB(p);
        float[] white = colourLook().whiteMatrix(p.sceneCct);
        if (white != null) {
            m = ColourLook.mul(white, m);
            com.particlesdevs.photoncamera.util.Log.i("SCAM_PIPELINE", "colour: white matrix " + java.util.Arrays.toString(white)
                    + " scene " + p.sceneCct + " K");
        }
        if (st != null) st.colourMatrix = m;
        return m;
    }

    /** Colour uniforms of ark/low.glsl and ark/combine.glsl (sceneLinear): the chain of headroom/render.glsl. */
    static void setColour(GLProg prog, PostPipeline pipeline, GLTexture gainMap, float inScale) {
        Parameters p = pipeline.mParameters;
        prog.setTexture("GainMap", gainMap);
        prog.setVar("sensorToIntermediate", p.sensorToProPhoto);
        prog.setVar("intermediateToSRGB", colourMatrix(pipeline));
        prog.setVar("neutralPointU", p.whitePoint[0], p.whitePoint[1], p.whitePoint[2]);
        prog.setVar("inScaleU", inScale);
    }
}
