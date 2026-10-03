package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.opengl.scripts.GLHistogram;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;
import com.particlesdevs.photoncamera.util.Log;

/**
 * Motion V2 exposure estimator, replacing the AutoExposure role.
 *
 * Computes one linear display gain from percentiles of the linear
 * (camera-space) histogram, mirroring motionv2's percentile scheme:
 * gain50 = midtone/p50, gain90 = highlight/p90,
 * gain = sqrt(max(1, gain50)*max(1, gain90)) clamped to [gainMin, gainMax].
 * The gain is consumed by {@link HeadroomRender} as the linear exposure
 * multiplier and as sceneWhite (clamped 0.90*gain).
 *
 * Renders nothing: passes the input texture through and marks the program
 * closed so the pipeline skips the draw for this node.
 */
public class LinearExposure extends Node {
    @Tunable(title = "Histogram size", category = "Sky Exposure", defaultValue = 1024, min = 256, max = 16384, step = 16, description = "Histogram bin count")
    int histSize;

    @Tunable(title = "Midtone Anchor", category = "Sky Exposure", min = 0.005f, max = 0.200f, defaultValue = 0.050f, step = 0.005f, description = "Linear luminance target for the 50th percentile")
    float midAnchor = 0.050f;

    @Tunable(title = "Highlight Anchor", category = "Sky Exposure", min = 0.020f, max = 0.500f, defaultValue = 0.180f, step = 0.005f, description = "Linear luminance target for the 90th percentile")
    float highAnchor = 0.180f;

    @Tunable(title = "Gain Min", category = "Sky Exposure", min = 0.25f, max = 4.0f, defaultValue = 1.0f, step = 0.25f, description = "Lower clamp of the estimated display gain")
    float gainMin = 1.0f;

    @Tunable(title = "Gain Max", category = "Sky Exposure", min = 1.0f, max = 16.0f, defaultValue = 16.0f, step = 1.0f, description = "Upper clamp of the estimated display gain")
    float gainMax = 16.0f;

    public LinearExposure() {
        super("", "LinearExposure");
    }

    @Override
    public void Compile() {}

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        if (pipeline.mParameters.vivoNiceRgb != null) {
            // NICE brightness targets are user settings (group "Светотень и тон"); on a hybrid shot its own copies
            // (pref_lmc_hybrid_ae_*, PreferenceKeys.profileNumber), never the SCAM HDR keys.
            // Gain up to x128 by default: 16 capped every dim indoor/night NICE shot
            // (p50 ~0.001-0.002 needs x25..x50) and pictures came out darker than the scene.
            histSize = 1024;
            midAnchor = com.particlesdevs.photoncamera.settings.PreferenceKeys.profileNumber("pref_nice_ae_mid", 0.050f, 0.005f, 0.200f);
            highAnchor = com.particlesdevs.photoncamera.settings.PreferenceKeys.profileNumber("pref_nice_ae_high", 0.180f, 0.020f, 0.500f);
            gainMin = 1.0f;
            gainMax = com.particlesdevs.photoncamera.settings.PreferenceKeys.profileNumber("pref_nice_ae_gain_max", 128f, 1f, 256f);
        }
        // Keep the linear scene snapshot for the Ultra HDR gain-map pass
        // (this buffer is the post-demosaic/ABLC input Initial used to see).
        if (pipeline.captureDemosaic) {
            pipeline.captureDemosaicLinear(previousNode.WorkingTexture);
        }
        int bins = histSize;
        if (bins < 16) bins = 1024; // guard against a failed tunable injection

        GLHistogram histogram = new GLHistogram(glProg, bins);
        histogram.Rc = true;
        histogram.Gc = true;
        histogram.Bc = true;
        histogram.Ac = false;
        // HDR RAW is stored at the shortest exposure. Meter at the reference
        // exposure so changing bracket EV neither darkens the picture nor
        // collapses the histogram into its first few bins.
        float rawScale=basePipeline.mParameters.vivoHdrMode
                ? Math.max(1e-6f,basePipeline.mParameters.vivoHdrRawScale) : 1f;
        for(int c=0;c<3;c++) histogram.exposure[c]=1f/rawScale;
        int[][] result;
        try {
            result = histogram.Compute(previousNode.WorkingTexture);
        } finally {
            histogram.close();
        }

        // Combined RGB histogram over the linear [0,1] range.
        long total = 0L;
        long[] cumulative = new long[bins];
        for (int i = 0; i < bins; i++) {
            total += (long) result[0][i] + (long) result[1][i] + (long) result[2][i];
            cumulative[i] = total;
        }

        float gain = gainMax;
        final boolean softTone = pipeline.mParameters.vivoNiceRgb != null && com.particlesdevs.photoncamera.settings.PreferenceKeys.isNiceSoftTone();
        if (softTone) {
            // GCam-like exposure: the median of the (white balanced) green channel is lifted towards a key that
            // grows slowly with the scene's own brightness (dim scenes stay dimmer than daylight, bright ones are
            // not pushed past their exposure); the dynamic range goes to the exposure fusion and the tone.
            float[] st = greenPercentiles(result, bins, 1f);
            if (st[2] < 32f / bins) {
                final float zoom = 64f;
                GLHistogram fine = new GLHistogram(glProg, bins);
                fine.Rc = true; fine.Gc = true; fine.Bc = true; fine.Ac = false;
                for (int c = 0; c < 3; c++) fine.exposure[c] = zoom / rawScale;
                try {
                    int[][] h = fine.Compute(previousNode.WorkingTexture);
                    st = greenPercentiles(h, bins, zoom);
                } finally {
                    fine.close();
                }
            }
            float key = com.particlesdevs.photoncamera.settings.PreferenceKeys.niceInternalValue("tone_key", 0.155f);
            float keyExp = com.particlesdevs.photoncamera.settings.PreferenceKeys.niceInternalValue("tone_key_exp", 0.30f);
            float keyMin = com.particlesdevs.photoncamera.settings.PreferenceKeys.niceInternalValue("tone_key_min", 0.045f);
            float keyMax = com.particlesdevs.photoncamera.settings.PreferenceKeys.niceInternalValue("tone_key_max", 0.30f);
            float p50g = Math.max(st[1], 1.0e-5f);
            float target = Math.max(keyMin, Math.min(keyMax, key * (float) Math.pow(p50g / 0.1f, keyExp)));
            // Night: a scene whose median sits far below daylight stays a night scene (the vivo stock keeps it nearly black, LMC dim).
            // The key floor above would lift a ship deck at 1/60 s ISO 2560 by x11 (median 70/255, sea and sky amplified to a noisy grey,
            // 8 % of the picture clipped); below nightP the key falls on with the scene's own median.
            float nightP = com.particlesdevs.photoncamera.settings.PreferenceKeys.niceInternalValue("tone_night_p", 0.015f);
            float nightExp = com.particlesdevs.photoncamera.settings.PreferenceKeys.niceInternalValue("tone_night_exp", 0.6f);
            if (nightP > 0f && p50g < nightP) target *= (float) Math.pow(p50g / nightP, nightExp);
            gain = Math.max(gainMin, Math.min(gainMax, Math.max(1f, target / p50g)));
            pipeline.sceneDynamicRange = (float) (Math.log(Math.max(st[3], 1.0e-5f) / Math.max(st[0], 1.0e-5f)) / Math.log(2.0));
            Log.d(Name, "soft tone: green p10:" + st[0] + " p50:" + st[1] + " p90:" + st[2] + " p99:" + st[3]
                    + " key:" + target + " dynamicRange:" + pipeline.sceneDynamicRange + " displayGain:" + gain);
        } else if (total > 0L) {
            float p50 = percentile(cumulative, total, 0.50f);
            float p90 = percentile(cumulative, total, 0.90f);
            // Dark scene: the whole histogram sits in the first few of 1024 linear
            // bins (p50 = p90 = 0 on a night wide-angle shot), so the gain was blind
            // and just hit its cap. Meter again with the values magnified.
            if (p90 < 32f / bins) {
                final float zoom = 64f;
                GLHistogram fine = new GLHistogram(glProg, bins);
                fine.Rc = true; fine.Gc = true; fine.Bc = true; fine.Ac = false;
                for (int c = 0; c < 3; c++) fine.exposure[c] = zoom / rawScale;
                try {
                    int[][] h = fine.Compute(previousNode.WorkingTexture);
                    long all = 0L; long[] cum = new long[bins];
                    for (int i = 0; i < bins; i++) { all += (long) h[0][i] + h[1][i] + h[2][i]; cum[i] = all; }
                    if (all > 0L) {
                        p50 = percentile(cum, all, 0.50f) / zoom;
                        p90 = percentile(cum, all, 0.90f) / zoom;
                    }
                } finally {
                    fine.close();
                }
            }
            float gain50 = midAnchor / Math.max(p50, 1.0e-5f);
            float gain90 = highAnchor / Math.max(p90, 1.0e-5f);
            float sceneGain = (float) Math.sqrt(
                    Math.max(1.f, gain50) * Math.max(1.f, gain90));
            boolean highlightLimited = pipeline.mParameters.vivoNiceRgb != null
                    && com.particlesdevs.photoncamera.settings.PreferenceKeys.isVivoNetSoc()
                    && com.particlesdevs.photoncamera.settings.PreferenceKeys.profileNumber("pref_nice_ae_limit_mode", 1f, 0f, 1f) > 0f;
            if (highlightLimited) {
                // A dim scene is lifted until its median reaches the mid anchor or its highlights
                // reach the limit, whichever comes first: lighting a room with a few bright
                // windows or lamps no longer pushes them far above what a GCam render shows
                // (the geometric mean with a clamped highlight term lifted such rooms ~0.8 EV
                // more than GCam and milked the blacks).
                float limit = com.particlesdevs.photoncamera.settings.PreferenceKeys.profileNumber("pref_nice_ae_high_limit", 0.40f, 0.05f, 1f);
                float highGain = Math.max(1.f, limit / Math.max(p90, 1.0e-5f));
                sceneGain = Math.min(Math.max(1.f, gain50), highGain);
                // Bright scenes (median above the mid anchor) are lifted towards a mid target, but
                // never beyond the highlight limit.
                float brightMid = com.particlesdevs.photoncamera.settings.PreferenceKeys.profileNumber("pref_nice_ae_bright_mid", 0.25f, 0f, 1f);
                if (brightMid > 0f && p50 >= midAnchor) {
                    sceneGain = Math.max(sceneGain, Math.min(highGain, Math.max(1.f, Math.min(2.2f, brightMid / p50))));
                }
            }
            if (pipeline.mParameters.vivoNiceRgb != null && !highlightLimited) {
                // Bright scenes (daylight, p50 ~0.15 of the reference white) already sit above both
                // anchors, so their gain stayed at 1 and the picture came out ~1 EV darker than
                // stock, sky and windows dull. Lift them towards a mid target; dim scenes keep
                // their (larger) gain, and nothing is ever darkened.
                float brightMid = com.particlesdevs.photoncamera.settings.PreferenceKeys.profileNumber("pref_nice_ae_bright_mid", 0.25f, 0f, 1f);
                if (brightMid > 0f) {
                    float brightLift = Math.max(1.f, Math.min(2.2f, brightMid / Math.max(p50, 1.0e-5f)));
                    sceneGain = Math.max(sceneGain, brightLift);
                }
            }
            gain = Math.max(gainMin, Math.min(gainMax, sceneGain));
            Log.d(Name, "p50:" + p50 + " p90:" + p90
                    + " displayGain:" + gain);
        } else {
            Log.d(Name, "Empty histogram, displayGain:" + gain);
        }
        pipeline.linearDisplayGain = gain/rawScale;
        pipeline.linearHighlight = 0f;
        if (basePipeline.mParameters.vivoHdrMode && !AgxTone.enabled()) {
            // Legacy shoulder only (AgX has its own range): highlight level of the HDR data itself (unscaled, up to its own max),
            // so the renderer's white point follows the scene instead of clipping
            // everything brighter than the normal exposure's white.
            GLHistogram high = new GLHistogram(glProg, bins);
            high.Rc = true; high.Gc = true; high.Bc = true; high.Ac = false;
            for (int c = 0; c < 3; c++) high.exposure[c] = 1f;
            try {
                int[][] h = high.Compute(previousNode.WorkingTexture);
                long all = 0L; long[] cum = new long[bins];
                for (int i = 0; i < bins; i++) { all += (long) h[0][i] + h[1][i] + h[2][i]; cum[i] = all; }
                float pct = com.particlesdevs.photoncamera.settings.PreferenceKeys.vivoHdrValue("highlight_pct", 99.5f) / 100f;
                if (all > 0L) pipeline.linearHighlight = percentile(cum, all, Math.max(0.9f, Math.min(0.9999f, pct)));
            } finally {
                high.close();
            }
            Log.d(Name, "HDR highlight level: " + pipeline.linearHighlight);
        }

        WorkingTexture = previousNode.WorkingTexture;
        glProg.closed = true;
    }

    /** Percentiles (10, 50, 90, 99) of the green channel of a combined histogram, divided by zoom. */
    private static float[] greenPercentiles(int[][] h, int bins, float zoom) {
        long total = 0L;
        long[] cumulative = new long[bins];
        for (int i = 0; i < bins; i++) { total += h[1][i]; cumulative[i] = total; }
        if (total <= 0L) return new float[]{0f, 0f, 0f, 0f};
        return new float[]{percentile(cumulative, total, 0.10f) / zoom, percentile(cumulative, total, 0.50f) / zoom,
                percentile(cumulative, total, 0.90f) / zoom, percentile(cumulative, total, 0.99f) / zoom};
    }

    /**
     * Bin where the cumulative count from the bottom crosses the requested
     * fraction of all samples; returns the bin value normalized to [0,1].
     */
    private static float percentile(long[] cumulative, long total, float frac) {
        long threshold = (long) (total * frac);
        for (int i = 0; i < cumulative.length; i++) {
            if (cumulative[i] > threshold) {
                return (float) i / (float) (cumulative.length - 1);
            }
        }
        return 1.0f;
    }
}
