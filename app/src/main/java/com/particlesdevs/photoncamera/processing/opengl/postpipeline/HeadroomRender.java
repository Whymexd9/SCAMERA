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

    /** Large-scale log2 luminance at 1/16 scale for the AgX local highlight range. */
    private GLTexture buildAgxBase(GLTexture input) {
        GLTexture quarter = glUtils.gaussdown(input, 4);
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

    /**
     * Linear sRGB colour of a Planckian light at the given temperature (green = 1), to the power of
     * the retained share, with the luminance kept: a third of a tungsten room's warmth stays, as in
     * a GCam/stock render, instead of every white being pulled fully neutral.
     */
    static float[] warmTint(float cct, float share) {
        if (share <= 0f) return new float[]{1f, 1f, 1f};
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
        AgxTone.Params agxParams = agx ? AgxTone.load(niceTone) : null;
        if (agxParams != null && agxParams.localStrength > 0f)
            agxBase = buildAgxBase(super.previousNode.WorkingTexture);
        glProg.setDefine("MANUAL_TONE", manualTone);
        glProg.setDefine("AGX", agx);
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
        if (agx) {
            AgxTone.Params a = agxParams;
            glProg.setTexture("AgxBase", agxBase != null ? agxBase : gainMapTex);
            glProg.setVar("agxLocal", agxBase != null ? a.localStrength : 0f, a.localStart);
            glProg.setVar("agxHiDesat", a.highlightDesat, Math.min(a.desatStart, 0.95f));
            glProg.setVar("agxInset", a.inset);
            glProg.setVar("agxOutset", a.outset);
            glProg.setVar("agxExposure", a.exposure);
            glProg.setVar("agxRange", a.minEv, a.maxEv);
            glProg.setVar("agxCurve", a.px, a.py, a.slope, 0f);
            glProg.setVar("agxPowers", a.toe, a.shoulder, a.ts, a.ss);
            glProg.setVar("agxLook", a.lookSlope, a.lookOffset, a.lookPower, a.saturation);
        }
        glProg.setVar("castTint", niceTone ? warmTint(basePipeline.mParameters.sceneCct,
                Math.max(0f, Math.min(1f, PreferenceKeys.niceInternalValue("warm_retention", 30f) / 100f))) : new float[]{1f, 1f, 1f});
        glProg.setVar("toneAmount", toneAmount);
        glProg.setVar("localContrast", localContrast);
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
