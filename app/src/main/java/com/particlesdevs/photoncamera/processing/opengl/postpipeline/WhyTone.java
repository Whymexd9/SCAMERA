package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.color.ColourLook;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/**
 * WHY photo tone of the SCAM Hybrid and SCAM HDR (the one tonemap of both routes): the WhyCam 1.23 / SCAM 9.6 tone of libfc_suppressor.so (Smart-HDR AE, exposure
 * fusion on a guided filter, OKLab grading, AgX Custom; research/hybrid5/tone_port.md sections 2 and 7) with the
 * high-frequency detail of the Sabre merge. Route: WhyStats -> WhyFusion -> WhyLumaSharpen -> WhyCombine. This class holds the per-shot state handed between those nodes, their settings and the colour
 * chain of why/low.glsl and why/combine.glsl.
 */
public final class WhyTone {
    private WhyTone() {}

    /** Per-shot state; the textures are closed by WhyCombine (all but none are needed after it). */
    public static final class State {
        public WhyAe.Result ae;
        /** G_CLEAN equivalent (half the sensor, RGBA16F) before and after bracket_denoise. */
        public GLTexture low, lowDn;
        /** 2x grid only: 2x2 mean of the output grid (the 1x-equivalent colour source of WhyCombine). */
        public GLTexture mid;
        /** Fused display luma of the exposure fusion (.r), whyLow size. */
        public GLTexture fused;
        /** Bounded reference of the detail delta: box mean of min(ae * Y709, 1) (.r), whyLow size; null without detail. */
        public GLTexture detailRef;
        /** Box factor of whyLow against the output grid (2 at 1x, 4 on the Sabre 2x grid). */
        public int factor = 2;
        /** Multiplier of the input to the G_CLEAN scale (why_input_ev). */
        public float inScale = 1f;
        /**
         * WhyLumaSharpen: WhyCam's sharpened luminance S(Ya) (.r) on the output grid, null without WHY sharpening. P214e: on a
         * mosaic shot (R1) Ya + S(B(Ya)) - B(Ya), B the 3x3 binomial.
         */
        public GLTexture lumaS;
        /** Domain multiplier of lumaS: Ya = min(sharpMul * Y709, 1) (1 = G_CLEAN scale, ae = the delta's domain). */
        public float sharpMul = 1f;
        /** P134: the colour chain's linear -> sRGB matrix of this shot (the white-balance rows of «Цвет» folded in). */
        float[] colourMatrix;
        /**
         * P187: above 100 MP WhyLumaSharpen gave the full-size image's storage back after its last reader (the image texture
         * passed on from node to node is deleted; WhyCombine must not bind it).
         */
        boolean inputReleased;

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

    /** Hybrid WHY setting pref_scam_hybrid_why_&lt;key&gt; (scam_dev.txt "hybrid_why_&lt;key&gt;" overrides it). */
    static float value(String key, float fallback) {
        return PreferenceKeys.hybridValue("why_" + key, fallback);
    }

    /**
     * P348: the default layer weights in the exposures' order (darkest, base, second dark, bright; WhyAe.exposures). P354 (owner,
     * 2026-10-27, the chandelier: «затенение вокруг люстры из-за неправильного локального тонмапинга»): the second dark layer
     * (sqrt(em0)) 0.4 -> 0.1 - at 0.4 it took a share wherever the lit ceiling around a lamp was bright (its luma there sits at the
     * weights' centre), and the glow of every lamp went flat with a darker ring round the shade; the darkest layer still draws the
     * shade itself. Host replay of the owner's burst: the glow back as in the reference shot; the sunset of P348 / P349: a softer,
     * brighter glow round the disc and no darker orange ring, the rungs unchanged.
     */
    static final float[] LAYER_WEIGHTS = {0.8f, 1f, 0.1f, 1f};

    /**
     * The fusion's layer weights: the kernel's highlight and base weights [code 0x78b18-0x78b3c]; P348's second dark layer 0.4 and
     * the one bright layer 1.0 (the closest to the old set's shadows and mid-tones of the curves on a grid of strengths).
     */
    static float[] layerWeights() {
        return new float[]{value("weight_hl", LAYER_WEIGHTS[0]), value("weight_mid", LAYER_WEIGHTS[1]),
                value("weight_dark2", LAYER_WEIGHTS[2]), value("weight_bright", LAYER_WEIGHTS[3])};
    }

    /** AE / Smart-HDR / AgX settings (defaults: WhyCam 2.85 X8U effective values, tone_port.md section 3). */
    static WhyAe.Settings settings() {
        WhyAe.Settings s = new WhyAe.Settings();
        s.aeTarget = value("ae_target", 0.15f);
        s.maxBoost = value("ae_max_boost", 5.0f);
        s.minLimit = Math.min(value("ae_min_limit", 0.5f), s.maxBoost);
        s.hlOverflow = value("hl_overflow", 2.0f);
        float nightVal = value("night_thresh", 0.05f);
        if (nightVal >= 1.0f) nightVal = 0.05f; // migration of legacy ISO values (e.g. 100)
        // The row is the noise-based gain clamp only. Since P63 the ISO night threshold took the same value, always below ISO 1,
        // so WhyAe used ISO 1 (max(nightThresh, 1)) whatever the row said; that threshold is now set here on purpose, the pixels
        // as before (settings audit 2026-10-21). The reference app's ISO 100 would strengthen the shadow lift (nf) at every ISO: a tone
        // change for the owner to decide.
        s.nightThresh = 1f;
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
        // P216: the Bento ultrashort frames merged - data above the native ceiling of 4 is the shot's real highlight range
        s.bentoHeadroom = ScamHybridBurst.lastBentoApplied;
        s.hrMix = Math.max(0f, Math.min(1f, value("hr_mix", 0.5f)));
        return s;
    }

    /**
     * P216: WhyCombine's handling of real Bento headroom - the shoulder knee and room (x the tonemapper top) and the share of the
     * full-size highlight contrast kept (scam_dev hybrid_why_hr_knee / _room / _detail).
     */
    /**
     * P291: the fusion's well-exposed centre and highlight-layer weight with Bento headroom (WhyFusion; kernel: 0.6, 0.8).
     * Lab round (owner, 2026-10-21: «проверь что все настройки рабочие»): they follow the menu's «Смещение центра экспозиции» and «Вес
     * слоя светов» (the headroom centre 0.15 above the menu's, the highlight weight half of it: 0.75 / 0.4 at the defaults, the
     * pixels as before), so the two rows act on a headroom shot's neutral highlights too, not only on its coloured light (P310
     * keeps the menu values there); a scam_dev line (hybrid_why_weight_center_hr / _hl_hr) still sets them outright.
     */
    static float headroomCenter() {
        if (PreferenceKeys.scamDevOverrides("hybrid_why_weight_center_hr")) return value("weight_center_hr", 0.75f);
        return Math.max(0.2f, Math.min(0.9f, 0.75f + (value("weight_center", 0.6f) - 0.6f)));
    }

    static float headroomHlWeight() {
        if (PreferenceKeys.scamDevOverrides("hybrid_why_weight_hl_hr")) return Math.max(0f, value("weight_hl_hr", 0.4f));
        return Math.max(0f, 0.4f * (value("weight_hl", 0.8f) / 0.8f));
    }

    /**
     * P310: the saturation ((max - min) / max of the light) over which a pixel moves from the headroom weights to the kernel's
     * (why/gf.glsl satLightU; hi <= lo: off, every pixel the headroom weights as P291), then the light's level (its brightest
     * channel x the AE) over which that fades in (satLevelU; only light near the top of the curve).
     * P341 (owner, 2026-10-26: «в новой версии у нас теперь артефакт оранжевый, переход должен быть плавный»): the saturation ramp
     * 0.15 .. 0.95 (was 0.35 .. 0.7). A sunset sky warms steadily towards the sun; the narrow ramp switched its weights within
     * ~100 px of that slope and the two tones (kernel: darker, orange; headroom: paler) met along an iso-saturation line, an
     * orange wedge with an edge. The wide ramp spreads the same switch over the whole slope (host replay of the owner's
     * 17:57:57 sunset: no edge, the glow still orange); a fully saturated LED (0.9+) keeps ~all of the kernel's weights.
     */
    static float[] saturatedLightRamp() {
        return new float[]{value("sat_light_lo", 0.15f), value("sat_light_hi", 0.95f),
                value("sat_light_level_lo", 0.25f), Math.max(value("sat_light_level_hi", 0.6f), value("sat_light_level_lo", 0.25f) + 0.01f)};
    }

    /**
     * P348: the warm hue band ((g - b) / (r - b) of r >= g >= b: orange to yellow, a setting sun's glow, fire) that keeps the
     * pyramid instead of P310's kernel weights (import why_fusion warmU; hi <= lo: off).
     */
    static float[] warmLight() {
        return new float[]{value("sat_light_warm_lo", 0.1f), value("sat_light_warm_hi", 0.25f)};
    }

    /**
     * P349: the sun bloom round fully clipped cores (why/combine.glsl SUN_BLOOM): strength (0 = off) and the glow's radius as a
     * share of the frame's shorter side.
     */
    static float[] sunBloom() {
        return new float[]{Math.max(0f, Math.min(1f, value("sun_bloom", 1f))), Math.max(0.005f, value("sun_bloom_radius", 0.033f))};
    }

    /**
     * P349: the guide's range (luminance x AE) over which the WHY fusion goes from the Laplacian pyramid to the guided filter
     * (why/lpyr guidedMixU): light above white, where the pyramid draws halos round dark objects; hi <= lo: the pyramid alone.
     */
    static float[] guidedLights() {
        return new float[]{value("fusion_guided_lo", 0.8f), value("fusion_guided_hi", 1.6f)};
    }

    /** P341: the vibrance of the light tones (why/combine.glsl vibHlU; 0 = off). */
    static float vibranceHl() {
        return Math.max(0f, value("vibrance_hl", 1.0f));
    }

    /** P291: the rise of the fusion's monotonic envelope per ln of the light (why/gf.glsl monoU; 0 = the kernel's fusion). */
    static float fusionMono() {
        return Math.max(0f, value("fusion_mono", 0.04f));
    }

    static float[] headroomTone() {
        return new float[]{value("hr_knee", 0.5f), value("hr_room", 0.4f), Math.max(0f, value("hr_detail", 0.35f))};
    }

    /**
     * AgX saturation factor for shots rendered with the OPPO tuned ISP matrix (Find X7 Ultra). 0.6 matched WhyCam 2.85 with
     * the X8U config at night (research/hybrid5); against WhyCam SCAM 9.6 with its X7U config (owner's pair 2026-10-08, main and
     * 3x tele, indoor ~3800 K) 0.6 left half of WhyCam's chroma (C*ab x0.48, lightness-normalised), and the numpy reference of
     * the tone put the match at 1.1. 1 = no compensation.
     */
    static float ccmSatComp(Parameters p) {
        return ccmSatComp(p != null && p.oppoTunedCcm);
    }

    /**
     * P370 (B2): why/combine signedHueU (and the RAW viewfinder's linear frame, rawdevelop_fs u_signed_hue): a colour the camera
     * matrix put outside sRGB keeps the hue of its signed value; scam_dev.txt "ccm_signed_hue 0": the per-channel clip as before.
     */
    public static boolean signedHue() {
        return PreferenceKeys.scamDevSwitch("ccm_signed_hue", true);
    }

    /** P370 (B4): {@link #ccmSatComp(Parameters)} of a frame the tuned matrix renders or not (the RAW viewfinder's). */
    static float ccmSatComp(boolean tuned) {
        return tuned ? Math.max(value("ccm_sat", 1.1f), 0f) : 1f;
    }

    /**
     * P144: the tone operator of «Кривая AgX и ACES» - ToneCurve.AGX unless the own curve, Uchimura or ACES is switched on (the
     * settings keep one on at a time; should several be stored, that order wins).
     */
    static int toneOperator() {
        if (PreferenceKeys.hybridSwitch("why_curve_on", false)) return com.particlesdevs.photoncamera.processing.color.ToneCurve.CUSTOM;
        if (PreferenceKeys.hybridSwitch("why_uchi_on", false)) return com.particlesdevs.photoncamera.processing.color.ToneCurve.UCHIMURA;
        if (PreferenceKeys.hybridSwitch("why_aces_on", false)) return com.particlesdevs.photoncamera.processing.color.ToneCurve.ACES;
        return com.particlesdevs.photoncamera.processing.color.ToneCurve.AGX;
    }

    /** ACES (WhyCam defaults): pre-gain 1, a 2.61, d 0.59, toe (the same toe as the inversion of the fusion target). */
    static float[] acesUniform() {
        return new float[]{value("aces_pre_gain", 1.0f), value("aces_a", 2.61f), value("aces_d", 0.59f), value("aces_toe", 0.05f)};
    }

    /** Uchimura (WhyCam defaults): pre-gain 1, post-gain 0.9, P 1, a 1 | m 0.22, l 0.4, c 1.33, b 0. */
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

    /** The own curve's 256 samples (pref_scam_hybrid_why_curve_points, WhyCam's S when empty). */
    static float[] curveSamples() {
        return com.particlesdevs.photoncamera.processing.color.ToneCurve
                .parse(PreferenceKeys.hybridString("why_curve_points", com.particlesdevs.photoncamera.processing.color.ToneCurve.DEFAULT_POINTS))
                .samples(com.particlesdevs.photoncamera.processing.color.ToneCurve.SAMPLES);
    }

    /** ef_gamma; the mod takes 2.2 for a value at or below 0.1 [pushCurrentLut 0x80da4]. */
    static float gamma() {
        float g = value("gamma", 2.2f);
        return g > 0.1f ? g : 2.2f;
    }

    /** Box factor of whyLow: half the SENSOR grid, i.e. 2 at 1x and 4 on the Sabre 2x grid. */
    static int lowFactor(Parameters p) {
        return 2 * Math.max(1, Math.round(p.outputScale));
    }

    /** WHY size of a grid reduced by a box of f (the last box is clamped at the edge). */
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
        State st = pipeline.why;
        if (st != null && st.colourMatrix != null) return st.colourMatrix;
        Parameters p = pipeline.mParameters;
        float[] white = colourLook().whiteMatrix(p.sceneCct);
        float[] m = colourMatrixOf(p, white);
        if (white != null) {
            com.particlesdevs.photoncamera.util.Log.i("SCAM_PIPELINE", "colour: white matrix " + java.util.Arrays.toString(white)
                    + " scene " + p.sceneCct + " K");
        }
        if (st != null) st.colourMatrix = m;
        return m;
    }

    /**
     * P370 (B1): the intermediateToSRGB uniform of a shot: the «Цвет» white matrix ({@code white}, null: none) after CCT.matrix -
     * what {@link #colourMatrix(PostPipeline)} uploads and the diagnostic README prints.
     */
    static float[] colourMatrixOf(Parameters p, float[] white) {
        float[] m = intermediateToSRGB(p);
        return white != null ? ColourLook.mul(white, m) : m;
    }

    /** P370 (B1): {@link #colourMatrixOf} with the «Цвет» rows of now (the diagnostic README before the pipeline runs). */
    static float[] colourMatrixNow(Parameters p) {
        return colourMatrixOf(p, colourLook().whiteMatrix(p.sceneCct));
    }

    /** Colour uniforms of why/low.glsl and why/combine.glsl (sceneLinear): the chain of headroom/render.glsl. */
    static void setColour(GLProg prog, PostPipeline pipeline, GLTexture gainMap, float inScale) {
        Parameters p = pipeline.mParameters;
        prog.setTexture("GainMap", gainMap);
        prog.setVar("sensorToIntermediate", p.sensorToProPhoto);
        prog.setVar("intermediateToSRGB", colourMatrix(pipeline));
        prog.setVar("neutralPointU", p.whitePoint[0], p.whitePoint[1], p.whitePoint[2]);
        prog.setVar("inScaleU", inScale);
    }
}
