package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.render.ColorCorrectionTransform;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/**
 * ARK photo tone of the LMC hybrid and SCAM HDR (the one tonemap of both routes): the ArkCam 1.23 / LMC 9.6 tone of libfc_suppressor.so (Smart-HDR AE, exposure
 * fusion on a guided filter, OKLab grading, AgX Custom; research/hybrid5/tone_port.md sections 2 and 7) with the
 * high-frequency detail of the Sabre merge. Route: ArkStats -> ArkFusion -> [ArkLumaSharpen] -> ArkCombine ->
 * [sharpening -> ArkSharpenGuard]. This class holds the per-shot state handed between those nodes, their settings and the colour
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
        /** Exponent of the sharpening weight gain^-guard written by ArkCombine (0: no guard). */
        public float guard;
        /** ArkCombine's output kept for ArkSharpenGuard (main3; not owned here), null without a guard. */
        public GLTexture preSharpen;
        /** ArkLumaSharpen: ArkCam's sharpened luminance S(Ya) (.r) on the output grid, null without ARK sharpening. */
        public GLTexture lumaS;
        /** Domain multiplier of lumaS: Ya = min(sharpMul * Y709, 1) (1 = G_CLEAN scale, ae = the delta's domain). */
        public float sharpMul = 1f;

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

    /** Hybrid ARK setting pref_lmc_hybrid_ark_&lt;key&gt; (nice_dev.txt "hybrid_ark_&lt;key&gt;" overrides it). */
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
        s.hlBlend = value("hl_blend", 0.33f);
        s.nightThresh = value("night_thresh", 100f);
        s.nightDim = value("night_dim", 1.0f);
        s.facePriority = value("face_priority", 0.7f);
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
     * the X8U config at night (research/hybrid5); against ArkCam LMC 9.6 with its X7U config (owner's pair 2026-10-08, main and
     * 3x tele, indoor ~3800 K) 0.6 left half of ArkCam's chroma (C*ab x0.48, lightness-normalised), and the numpy reference of
     * the tone put the match at 1.1. 1 = no compensation.
     */
    static float ccmSatComp(Parameters p) {
        return p != null && p.oppoTunedCcm ? Math.max(value("ccm_sat", 1.1f), 0f) : 1f;
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
        float[] m = p.CCT.matrix;
        if (p.CCT.correctionMode == ColorCorrectionTransform.CorrectionMode.MATRIXES) m = p.CCT.combineMatrix(p.whitePoint);
        return m;
    }

    /** Colour uniforms of ark/low.glsl and ark/combine.glsl (sceneLinear): the chain of headroom/render.glsl. */
    static void setColour(GLProg prog, PostPipeline pipeline, GLTexture gainMap, float inScale) {
        Parameters p = pipeline.mParameters;
        prog.setTexture("GainMap", gainMap);
        prog.setVar("sensorToIntermediate", p.sensorToProPhoto);
        prog.setVar("intermediateToSRGB", intermediateToSRGB(p));
        prog.setVar("neutralPointU", p.whitePoint[0], p.whitePoint[1], p.whitePoint[2]);
        prog.setVar("inScaleU", inScale);
    }
}
