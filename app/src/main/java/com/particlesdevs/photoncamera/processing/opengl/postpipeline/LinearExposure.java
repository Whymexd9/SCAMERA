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
            // NICE brightness targets are user settings (group "Светотень и тон").
            // Gain up to x128 by default: 16 capped every dim indoor/night NICE shot
            // (p50 ~0.001-0.002 needs x25..x50) and pictures came out darker than the scene.
            histSize = 1024;
            midAnchor = com.particlesdevs.photoncamera.settings.RawTherapeeSettings.number("pref_nice_ae_mid", 0.050f, 0.005f, 0.200f);
            highAnchor = com.particlesdevs.photoncamera.settings.RawTherapeeSettings.number("pref_nice_ae_high", 0.180f, 0.020f, 0.500f);
            gainMin = 1.0f;
            gainMax = com.particlesdevs.photoncamera.settings.RawTherapeeSettings.number("pref_nice_ae_gain_max", 128f, 1f, 256f);
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
        if (total > 0L) {
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
