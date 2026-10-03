package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.render.ColorCorrectionTransform;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;
import com.particlesdevs.photoncamera.util.BufferUtils;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.util.Arrays;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * Sky headroom renderer, replacing the Initial role.
 *
 * Renders the SDR base with matrix-only color (sensor -> ProPhoto -> sRGB,
 * white-point WB; no CCT cubes/CLUTs), the lens shading GainMap and the
 * ExposureFusionBayer2 FusionMap as linear gains, and the sky
 * log-headroom tone curve driven by the {@link LinearExposure} display gain:
 * sceneWhite = clamp(headroomScale*displayGain, 1, sceneWhiteMax).
 */
public class HeadroomRender extends Node {
    @Tunable(title = "Output Exposure", category = "Sky (Headroom)", min = 0.50f, max = 1.20f, defaultValue = 0.80f, step = 0.01f, description = "Только Sky: линейный множитель яркости на выходе (0.80 ≈ −0.32 EV). На Exposure Fusion не влияет.")
    float outputExposureScale = 0.80f;

    @Tunable(title = "Headroom Scale", category = "Sky (Headroom)", min = 0.50f, max = 1.20f, defaultValue = 0.90f, step = 0.01f, description = "Только Sky: доля усиления, определяющая запас в светах. На Exposure Fusion не влияет.")
    float headroomScale = 0.90f;

    @Tunable(title = "Headroom Max", category = "Sky (Headroom)", min = 1.0f, max = 20.0f, defaultValue = 14.5f, step = 0.5f, description = "Только Sky: предел запаса в светах. Держите выше Headroom Scale × Gain Max, чтобы сохранить переходы в светах.")
    float sceneWhiteMax = 14.5f;

    protected float toneAmount = 1f;
    protected float localContrast = 0.42f;
    protected float shadowLift = 0f;
    /** Shoulder position (0..1) where highlights start fading to neutral white. */
    protected float highlightNeutralStart = 0.82f;
    /** Display-linear level where near-white starts losing tint; 1 disables it. */
    protected float displayNeutralStart = 1f;
    protected boolean manualTone = false;
    protected float manualExposure = 0f, manualContrast = 1f, manualGamma = 1f;
    protected float manualSaturation = 1f, manualBlack = 0f, manualWhite = 1f;
    /** AgX picture formation instead of the headroom shoulder (see AgxTone). */
    protected boolean agx = false;
    /** SCAM HDR soft tone (render.glsl GCAM): local knee, toe, soft shoulder; replaces AgX and the headroom shoulder. */
    protected boolean gcam = false;

    public HeadroomRender agx(boolean enabled) { agx = enabled; return this; }
    /** SCAM HDR tone stage: AgX with its HDR-specific highlight handling (see AgxTone.load). */
    protected boolean niceTone = false;
    private GLTexture fallbackGainMap;

    public HeadroomRender() {
        super("", "HeadroomRender");
    }

    @Override
    public void Compile() {}

    @Override
    public void AfterRun() {
        // Last consumer of the fusion map (was Initial's duty).
        if (((PostPipeline) basePipeline).FusionMap != null) {
            ((PostPipeline) basePipeline).FusionMap.close();
        }
        if (fallbackGainMap != null) {
            fallbackGainMap.close();
            fallbackGainMap = null;
        }
        if (agxBase != null) {
            agxBase.close();
            agxBase = null;
        }
    }

    private GLTexture agxBase;

    /**
     * Integer output grid step: 2 on the Sabre 2x grid (Parameters.outputScale), else 1. Every pixel-defined window
     * of this node is dilated by it so the processing covers the same SENSOR area as at 1x (and is unchanged at 1x).
     */
    private int outputStep() {
        return Math.max(1, Math.round(basePipeline.mParameters.outputScale));
    }

    /** Large-scale log2 luminance at 1/16 of the SENSOR grid (1/(16*step) of the working grid) for the AgX local highlight range. */
    private GLTexture buildAgxBase(GLTexture input) {
        // gaussdown(k) is 5x5 taps at stride k/2 with sigma k/2, so 4*step is the same relative low-pass as 4 at 1x:
        // the base keeps its 40 sensor px sigma (agxbase.glsl) instead of becoming twice as local on the 2x grid.
        GLTexture quarter = glUtils.gaussdown(input, 4 * outputStep());
        GLTexture sixteenth = glUtils.gaussdown(quarter, 4);
        quarter.close();
        GLTexture base = new GLTexture(sixteenth.mSize, new GLFormat(GLFormat.DataType.FLOAT_16, 1),
                null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        glProg.useAssetProgram("headroom/agxbase", false);
        glProg.setTexture("InputBuffer", sixteenth);
        glProg.setVar("neutral", basePipeline.mParameters.whitePoint);
        glProg.drawBlocks(base);
        glProg.closed = true;
        sixteenth.close();
        return base;
    }

    // Colour look (render.glsl colourLook): chroma gain and hue shift (degrees) per OKLab hue node (every 30 degrees from +a),
    // neutral tint in OKLab a, b. Fitted to the mean of the reference cameras' renders of the same scenes.
    private static final float[] LOOK_GAIN = {1.12f, 1.10f, 1.08f, 1.0f, 1.02f, 1.06f, 1.10f, 1.135f, 1.17f, 1.16f, 1.155f, 1.135f};
    private static final float[] LOOK_SHIFT = {2f, 3f, 4f, 3.5f, 2f, 2f, 2f, 2f, 1.5f, 0f, 0f, 1f};
    private static final float[] LOOK_TINT = {-0.0013f, 0f};
    // Warm light (lamps at night, below ~4500 K): the warm hues turn towards orange by this share of look_warm_shift
    // degrees per node (a stock render keeps a lamp-lit deck orange where the daylight look above makes it yellow).
    private static final float[] WARM_SHIFT = {0.3f, 0.7f, 1f, 0.8f, 0.3f, 0f, 0f, 0f, 0f, 0f, 0f, 0.1f};
    /** 1 for a warm scene light (at or below warmLo K), 0 for daylight (warmHi K and above). */
    static float warmLight(float cct, float warmLo, float warmHi) {
        if (!(warmHi > warmLo)) return cct <= warmLo ? 1f : 0f;
        return Math.max(0f, Math.min(1f, (warmHi - cct) / (warmHi - warmLo)));
    }

    /**
     * Linear sRGB colour of a Planckian light at the given temperature (green = 1), to the power of
     * the retained share, with the luminance kept: a third of a tungsten room's warmth stays, as in
     * a GCam/stock render, instead of every white being pulled fully neutral.
     */
    static float[] warmTint(float cct, float share) {
        if (share == 0f) return new float[]{1f, 1f, 1f};   // negative: over-corrects, cooler than neutral
        double t = Math.max(2000.0, Math.min(8000.0, cct));
        double x = t <= 7000 ? -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063
                : -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.23704;
        double y = -3 * x * x + 2.87 * x - 0.275;
        double X = x / y, Z = (1 - x - y) / y;
        double r = 3.2406 * X - 1.5372 - 0.4986 * Z, g = -0.9689 * X + 1.8758 + 0.0415 * Z, b = 0.0557 * X - 0.2040 + 1.0570 * Z;
        double[] d65 = {1.0, 1.0, 1.0};
        double tr = Math.pow(Math.max(r / g, 1e-3), share), tb = Math.pow(Math.max(b / g, 1e-3), share);
        double luma = 0.2126 * tr + 0.7152 + 0.0722 * tb;
        return new float[]{(float) (tr / luma), (float) (1.0 / luma), (float) (tb / luma)};
    }

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;

        float displayGain = Math.max(1.0f, pipeline.linearDisplayGain);
        float sceneWhite = Math.max(1.0f,
                Math.min(sceneWhiteMax, headroomScale * displayGain));

        // Matrix-only color: plain matrix for CUBE/CUBES modes (cubes skipped).
        float[] intermediateToSRGB = basePipeline.mParameters.CCT.matrix;
        if (basePipeline.mParameters.CCT.correctionMode
                == ColorCorrectionTransform.CorrectionMode.MATRIXES) {
            intermediateToSRGB = basePipeline.mParameters.CCT.combineMatrix(
                    basePipeline.mParameters.whitePoint);
        }

        GLTexture gainMapTex = pipeline.GainMap;
        if (gainMapTex == null) {
            if (fallbackGainMap == null) {
                fallbackGainMap = new GLTexture(new Point(1, 1),
                        new GLFormat(GLFormat.DataType.FLOAT_16, 4),
                        BufferUtils.getFrom(new float[]{1.f, 1.f, 1.f, 1.f}),
                        GL_LINEAR, GL_CLAMP_TO_EDGE);
            }
            gainMapTex = fallbackGainMap;
        }

        boolean fusion = pipeline.FusionMap != null;
        AgxTone.Params agxParams = agx && !gcam ? AgxTone.load(niceTone) : null;
        if (gcam || (agxParams != null && agxParams.localStrength > 0f))
            agxBase = buildAgxBase(super.previousNode.WorkingTexture);
        glProg.setDefine("MANUAL_TONE", manualTone);
        glProg.setDefine("AGX", agx && !gcam);
        glProg.setDefine("GCAM", gcam);
        glProg.setDefine("FUSION", fusion);
        glProg.setDefine("NEUTRALPOINT", basePipeline.mParameters.whitePoint);
        glProg.useAssetProgram("headroom/render");
        glProg.setTexture("InputBuffer", super.previousNode.WorkingTexture);
        if (fusion) glProg.setTexture("FusionMap", pipeline.FusionMap);
        glProg.setTexture("GainMap", gainMapTex);
        glProg.setVar("sensorToIntermediate", basePipeline.mParameters.sensorToProPhoto);
        glProg.setVar("intermediateToSRGB", intermediateToSRGB);
        glProg.setVar("displayGain", displayGain);
        glProg.setVar("sceneWhite", sceneWhite);
        if (manualTone) {
            glProg.setVar("manualExposure", (float) Math.pow(2.0, manualExposure));
            glProg.setVar("manualContrast", manualContrast);
            glProg.setVar("manualGamma", manualGamma);
            glProg.setVar("manualSaturation", manualSaturation);
            glProg.setVar("manualBlack", manualBlack);
            glProg.setVar("manualWhite", manualWhite);
            Log.d(Name, "manual tone: EV=" + manualExposure + " contrast=" + manualContrast
                    + " gamma=" + manualGamma + " saturation=" + manualSaturation
                    + " black=" + manualBlack + " white=" + manualWhite
                    + " shoulder=" + toneAmount + " local=" + localContrast + " shadows=" + shadowLift);
        }
        if (gcam) {
            glProg.setTexture("GcamBase", agxBase);
            glProg.setVar("gcamKnee", PreferenceKeys.niceInternalValue("tone_knee", 3.0f), PreferenceKeys.niceInternalValue("tone_knee_start", 0.8f), 0f, 0f);
            glProg.setVar("gcamCurve", PreferenceKeys.niceInternalValue("tone_toe", 0.035f), PreferenceKeys.niceInternalValue("tone_shoulder", 0.22f),
                    PreferenceKeys.niceInternalValue("tone_white", 1.4f), 0f);
            float[] hueGain = new float[12], hueShift = new float[12];
            float warm = niceTone ? warmLight(basePipeline.mParameters.sceneCct, PreferenceKeys.niceInternalValue("look_warm_lo", 3200f),
                    PreferenceKeys.niceInternalValue("look_warm_hi", 4800f)) : 0f;
            float warmShift = warm * PreferenceKeys.niceInternalValue("look_warm_shift", -10f);
            for (int i = 0; i < 12; i++) {
                hueGain[i] = PreferenceKeys.niceInternalValue("look_g" + i, LOOK_GAIN[i]);
                hueShift[i] = (float) Math.toRadians(PreferenceKeys.niceInternalValue("look_h" + i, LOOK_SHIFT[i])
                        + warmShift * PreferenceKeys.niceInternalValue("look_wh" + i, WARM_SHIFT[i]));
            }
            if (warm > 0f) Log.d(Name, "warm light: " + basePipeline.mParameters.sceneCct + " K weight " + warm + " hue shift " + warmShift + " deg");
            glProg.setVarFloats("hueGain", hueGain);
            glProg.setVarFloats("hueShift", hueShift);
            glProg.setVar("hueTint", PreferenceKeys.niceInternalValue("look_da", LOOK_TINT[0]), PreferenceKeys.niceInternalValue("look_db", LOOK_TINT[1]),
                    PreferenceKeys.niceInternalValue("look", 1f) > 0f ? 1f : 0f);
            glProg.setVar("gcamColor", PreferenceKeys.niceInternalValue("tone_sat", 1.05f), PreferenceKeys.niceInternalValue("tone_desat", 0.5f),
                    PreferenceKeys.niceInternalValue("tone_desat_start", 0.8f), PreferenceKeys.niceInternalValue("tone_shading", 0.6f));
        } else if (agx) {
            AgxTone.Params a = agxParams;
            glProg.setTexture("AgxBase", agxBase != null ? agxBase : gainMapTex);
            glProg.setVar("agxLocal", agxBase != null ? a.localStrength : 0f, a.localStart);
            // Bento highlights are real scene content (ultrashort frame): keep their colour longer and do not treat
            // bright tinted pixels as clipped magenta.
            glProg.setVar("agxHiDesat", a.highlightDesat, LmcHybridBurst.lastBentoApplied ? 0.95f : Math.min(a.desatStart, 0.95f));
            glProg.setVar("agxInset", a.inset);
            glProg.setVar("agxOutset", a.outset);
            glProg.setVar("agxExposure", a.exposure);
            glProg.setVar("agxRange", a.minEv, a.maxEv);
            glProg.setVar("agxCurve", a.px, a.py, a.slope, 0f);
            glProg.setVar("agxPowers", a.toe, a.shoulder, a.ts, a.ss);
            glProg.setVar("agxLook", a.lookSlope, a.lookOffset, a.lookPower, a.saturation);
        }
        float[] castTint = niceTone ? warmTint(basePipeline.mParameters.sceneCct, PreferenceKeys.getNiceWarmRetention()) : new float[]{1f, 1f, 1f};
        if (niceTone) Log.d(Name, "scene light " + basePipeline.mParameters.sceneCct + " K, kept " + PreferenceKeys.getNiceWarmRetention()
                + " -> cast tint " + java.util.Arrays.toString(castTint));
        glProg.setVar("castTint", castTint);
        glProg.setVar("castRange", PreferenceKeys.niceInternalValue("warm_shadow_lo", 0.015f), PreferenceKeys.niceInternalValue("warm_shadow_hi", 0.10f),
                PreferenceKeys.niceInternalValue("warm_peak_lo", 0.35f), PreferenceKeys.niceInternalValue("warm_peak_hi", 0.75f));
        glProg.setVar("toneAmount", toneAmount);
        glProg.setVar("localContrast", localContrast);
        // Set for every tone mode (GCAM, AgX, headroom): render.glsl dilates its 5x5 fusion-map fit and the 5x5
        // pre-tone local-contrast window by it; an unset int uniform reads 0 and would collapse both to one pixel.
        // Full lattice (1) also on the 2x grid: the dilated 5x5 windows were part of the a-trous set that produced 2x2
        // blocks / saw-tooth edges in the vivo 27020 test; the 1/16-of-sensor AgX base keeps its 4*step reduction.
        glProg.setVar("pxStepU", 1);
        // Declared outside the tone-mode blocks of render.glsl: set for every mode (0 = clipped highlights are neutralised as before).
        glProg.setVar("bentoReal", LmcHybridBurst.lastBentoApplied ? 1f : 0f);
        glProg.setVar("shadowLift", shadowLift);
        glProg.setVar("highlightNeutralStart", Math.max(0f, Math.min(0.99f, highlightNeutralStart)));
        glProg.setVar("displayNeutralStart", Math.max(0f, Math.min(1f, displayNeutralStart)));
        glProg.setVar("outputExposureScale", Math.max(outputExposureScale, 1.0e-2f));
        glProg.setVar("activeSize", 2, 2,
                basePipeline.mParameters.sensorPix.right - basePipeline.mParameters.sensorPix.left - 2,
                basePipeline.mParameters.sensorPix.bottom - basePipeline.mParameters.sensorPix.top - 2);
        Log.d(Name, "displayGain:" + displayGain + " sceneWhite:" + sceneWhite
                + " outputExposureScale:" + outputExposureScale
                + " intermediateToSRGB:" + Arrays.toString(intermediateToSRGB));

        WorkingTexture = basePipeline.getMain();
    }
}
