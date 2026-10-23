package com.particlesdevs.photoncamera.processing.render;

import com.particlesdevs.photoncamera.util.Log;
import android.util.Pair;

import com.particlesdevs.photoncamera.processing.parameters.FrameNumberSelector;

public class NoiseModeler {
    private static String TAG = "NoiseModeler";
    public Pair<Double,Double>[] baseModel;
    public Pair<Double,Double>[] computeModel;
    public int AnalogueISO;
    public int SensivityISO;
    double adaptiveMpy = 1.0;
    /** ISO fed to the model (the capture sensitivity; P320: over {@link #isoScale} for a DCG frame). */
    private int modelIso1;
    /** P320: the HAL profile as reported (null: none) and the CFA, kept for {@link #withIsoScale}. */
    private final Pair<Double,Double>[] reportedModel;
    private final int bayer;
    /** P320: what the reported ISO was divided by before a chosen profile / the fallback read it (1: the reported ISO). */
    public final float isoScale;
    public NoiseModeler(Pair<Double,Double>[] inModel, Integer analogISO, Integer ISO, int bayer) {
        this(inModel, analogISO, ISO, bayer, 1f);
    }

    /**
     * P320: {@code isoScale} > 1 (a DCG mode's Parameters.dcgIsoScale: the X300 Ultra reports ISO x8, the X200 Ultra x16 for the
     * gain its photons saw) evaluates a chosen profile and the no-profile fallback at ISO / isoScale. P322: the HAL's own profile
     * is keyed by the same inflated ISO (the X200 Ultra's DCG bursts: its slope 14 - 19x, its offset 55 - 100x the measured ones),
     * so it is taken as slope / isoScale, offset / isoScale^2 - a variance linear and quadratic in the gain. 1 (or less, or not a
     * number) = the reported ISO and profile, as before.
     */
    public NoiseModeler(Pair<Double,Double>[] inModel, Integer analogISO, Integer ISO, int bayer, float isoScale) {
        AnalogueISO = analogISO;
        SensivityISO = ISO;
        reportedModel = inModel;
        this.bayer = bayer;
        this.isoScale = isoScale > 1f && Float.isFinite(isoScale) ? isoScale : 1f;
        baseModel = new Pair[3];
        computeModel = new Pair[3];
        // A selected profile replaces whatever the sensor reported, so it reaches every
        // consumer of computeModel at once: the denoise nodes and the alignment
        // significance gate in align.glsl.
        modelIso1 = modelIso(ISO, this.isoScale);
        if (this.isoScale > 1f) Log.i(TAG, "DCG ISO x" + this.isoScale + ": a chosen profile / the fallback at ISO " + modelIso1
                + " (reported " + ISO + ")" + (inModel != null && inModel.length > 0 ? ", the HAL profile / x" + this.isoScale
                + " (slope), / x" + (this.isoScale * this.isoScale) + " (offset)" : ""));
        if (this.isoScale > 1f && inModel != null && inModel.length > 0) inModel = scaledProfile(inModel, this.isoScale);

        NoiseModelProfile profile = null;
        try {
            profile = NoiseModelProfile.byId(
                    com.particlesdevs.photoncamera.settings.PreferenceKeys.getNoiseModelProfileId());
        } catch (Throwable t) {
            // A broken profile must degrade to the sensor's own model, never abort a capture.
            Log.e(TAG, "Noise model profile unavailable, falling back to the sensor profile", t);
        }
        if (profile != null) {
            inModel = profile.evaluate(modelIso1, AnalogueISO);
            Log.d(TAG, "Noise model profile: " + profile.name + " at ISO " + modelIso1
                    + " -> R(S,O)=" + inModel[0] + " Gr=" + inModel[1]
                    + " Gb=" + inModel[2] + " B=" + inModel[3]);
        }
        //inModel = null;
        if (inModel == null || inModel.length == 0 || inModel[0].first == 0.0) {
            // The camera reports no noise profile: a generic model of a phone sensor (S and O linear / quadratic in ISO)
            Pair<Double, Double> CustomGeneratorS = new Pair<>(0.0000025720647, 0.000028855721);
            Pair<Double, Double> CustomGeneratorO = new Pair<>(0.000000000039798506, 0.000000046578279);
            Pair<Double,Double> computedModel = new Pair<>(computeNoiseModelS(modelIso1,CustomGeneratorS),computeNoiseModelO(modelIso1,CustomGeneratorO));
            //Test
            /*
            Pair<Double, Double> TestsGenerator = new Pair<>(1.0798706869238175e-06, -8.618818353621416e-06);
            Pair<Double, Double> TestoGenerator = new Pair<>(5.790989178667454e-12, 3.7009550769043865e-07);
            Pair<Double,Double> computedModel = new Pair<>(computeNoiseModelS(ISO,TestsGenerator),computeNoiseModelO(ISO,TestoGenerator));
             */
            baseModel[0] = new Pair<>(computedModel.first, computedModel.second);
            baseModel[1] = new Pair<>(computedModel.first, computedModel.second);
            baseModel[2] = new Pair<>(computedModel.first, computedModel.second);
        } else {
            if (inModel.length == 1) {
                baseModel[0] = new Pair<>(inModel[0].first, inModel[0].second);
                baseModel[1] = new Pair<>(inModel[0].first, inModel[0].second);
                baseModel[2] = new Pair<>(inModel[0].first, inModel[0].second);
            }
            if (inModel.length == 3) {
                baseModel[0] = new Pair<>(inModel[0].first, inModel[0].second);
                baseModel[1] = new Pair<>(inModel[1].first, inModel[1].second);
                baseModel[2] = new Pair<>(inModel[2].first, inModel[2].second);
            }
            if (inModel.length == 4) {
                baseModel[0] = new Pair<>(inModel[0].first, inModel[0].second);
                baseModel[1] = new Pair<>((inModel[1].first + inModel[2].first) / 2.0, (inModel[1].second + inModel[2].second) / 2.0);
                baseModel[2] = new Pair<>(inModel[3].first, inModel[3].second);
            }
        }

        Log.d(TAG, "NoiseModel0->" + baseModel[0]);
        Log.d(TAG, "NoiseModel1->" + baseModel[1]);
        Log.d(TAG, "NoiseModel2->" + baseModel[2]);
        computeStackingNoiseModel();
        Log.d(TAG, "ComputedNoiseModel0->" + computeModel[0]);
        Log.d(TAG, "ComputedNoiseModel1->" + computeModel[1]);
        Log.d(TAG, "ComputedNoiseModel2->" + computeModel[2]);
    }


    /** P322: a HAL profile keyed by an ISO {@code scale} times the gain: slope / scale, offset / scale^2. */
    static Pair<Double, Double>[] scaledProfile(Pair<Double, Double>[] in, float scale) {
        @SuppressWarnings("unchecked") Pair<Double, Double>[] out = new Pair[in.length];
        for (int i = 0; i < in.length; i++)
            out[i] = in[i] == null ? null : new Pair<>(in[i].first / scale, in[i].second / ((double) scale * scale));
        return out;
    }

    /** P320: the ISO a chosen profile / the fallback reads: the reported ISO over the scale (> 1), at least 1; else the ISO itself. */
    static int modelIso(int iso, float isoScale) {
        return isoScale > 1f && Float.isFinite(isoScale) ? Math.max(1, Math.round(iso / isoScale)) : iso;
    }

    /**
     * P320 (Parameters.applyDcgIsoScale): this model rebuilt from the same HAL profile, analogue and reported ISO with the ISO
     * scale {@code scale}; idempotent (it always starts from the reported ISO). The stacking multiplier carries over and
     * computeModel is the one-frame stack again (ScamProcessor rescales it for the merge before the post).
     */
    public NoiseModeler withIsoScale(float scale) {
        final NoiseModeler n = new NoiseModeler(reportedModel, AnalogueISO, SensivityISO, bayer, scale);
        if (adaptiveMpy != 1.0) {
            n.adaptiveMpy = adaptiveMpy;
            n.computeStackingNoiseModel();
        }
        return n;
    }

    /**
     * P320: the DCG shot's measured two zones (Parameters.dcgNoise = {slope S, high-gain offset, low-gain offset, knee t},
     * normalised units (v - black) / (white - black), the worker's «HYBRID NOISE CHECK DCG»; var = S x + offset of x's zone)
     * when usable: four finite numbers, S > 0, both offsets >= 0, t in [1e-6, 1] (below, the shaders' smoothstep edges could
     * meet as fp32 denormals flush), or t = 0 with equal offsets (the worker's one measured zone: «zones=1», lcg = hcg, knee 0;
     * its factor line then says x1, so the camera's curve would go uncorrected); else null, and every reader (ScamDenoise, the
     * WHY gain clamp, the DNG NoiseProfile) keeps the camera's single curve.
     */
    public static float[] dcgZones(float[] z) {
        if (z == null || z.length < 4) return null;
        for (int i = 0; i < 4; i++) if (!Float.isFinite(z[i])) return null;
        if (!(z[0] > 0f) || z[1] < 0f || z[2] < 0f || z[3] < 0f || z[3] > 1f || (z[3] > 0f && z[3] < 1e-6f)) return null;
        if (z[3] == 0f && z[1] != z[2]) return null; // knee 0 is one zone only: an offset step without a knee is no model
        return new float[]{z[0], z[1], z[2], z[3]};
    }

    /**
     * P320: the low-gain zone's share at level x: smoothstep over 0.9 .. 1.1 x the knee (scamdn lbf / cbf / stats blend with
     * the same weight under DCG_ZONES 1); 0 for a knee <= 0 or a level that is not a number.
     */
    public static float zoneWeight(float x, float knee) {
        if (!(knee > 0f) || Float.isNaN(x)) return 0f;
        final float t = Math.max(0f, Math.min(1f, (x - 0.9f * knee) / (0.2f * knee)));
        return t * t * (3f - 2f * t);
    }

    /** P320: the offset at level x of two zones: {@code hcg} below the knee, {@code lcg} above it, {@link #zoneWeight} between. */
    public static float zoneOffset(float x, float hcg, float lcg, float knee) {
        final float w = zoneWeight(x, knee);
        return w <= 0f ? hcg : w >= 1f ? lcg : hcg + (lcg - hcg) * w;
    }

    public void setAdaptiveMpy(double mpy){
        adaptiveMpy = mpy;
    }

    public void computeStackingNoiseModel(){
        computeStackingNoiseModel(FrameNumberSelector.frameCount);
    }
    public void computeStackingNoiseModel(int FrameCnt){
        computeStackingNoiseModel((double)Math.max(1,FrameCnt),1);
    }
    public void computeStackingNoiseModel(double effectiveSamples,double spatialSamples) {
        double scale=com.particlesdevs.photoncamera.processing.parameters.FinishMath.varianceScale(
                effectiveSamples,spatialSamples)*adaptiveMpy;
        for(int i=0;i<3;i++)computeModel[i]=new Pair<>(baseModel[i].first*scale,baseModel[i].second*scale);
        Log.d(TAG,"Noise rescale: effective="+effectiveSamples+" spatial="+spatialSamples+" scale="+scale);
    }
    private double computeNoiseModelS(double Sensitivity,Pair<Double,Double> sGenerator) {
        double returning = sGenerator.first * Sensitivity + sGenerator.second;
        if(returning < 0.0) {
            Log.d("NoiseModeler","Negative noise model sGenerator at Sensivity:"+ Sensitivity+
                    ",First:"+sGenerator.first+
                    ",Second:"+sGenerator.second);
            //returning=-returning;
        }
        return returning;
    }

    public double SPlace(double Sensitivity) {
        return 4.2917797046733595e-07 * Sensitivity + 6.293375122210356e-06;
    }

    public double OPlace(double Sensitivity) {
        double dGain = Math.max(Sensitivity/AnalogueISO,1.0);
        return (2.18746307756334e-13 * Sensitivity*Sensitivity) + (2.1731291715765075e-07*dGain*dGain);
    }

    private double computeNoiseModelO(double Sensitivity,Pair<Double,Double> oGenerator) {
        double dGain = Math.max(Sensitivity/AnalogueISO,1.0);
        double returning = (oGenerator.first * Sensitivity*Sensitivity) + (oGenerator.second*dGain*dGain);
        if(returning < 0.0) {
            Log.d("NoiseModeler","Negative noise model oGenerator at Sensivity:"+Sensitivity+
                    ",Dgain:"+dGain+
                    ",First:"+oGenerator.first+
                    ",Second:"+oGenerator.second);
            //returning=-returning;
        }
        return returning;
    }

}
