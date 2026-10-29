package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * P212h (A5 = M5): the noise model behind WhyAe's gain clamp is the merged image's, not one frame's. PostPipeline.computeNoise
 * hands WhyStats the single-frame model (effectiveStackSamples and multiFrameCount are never set on the SHDR route: every shot logs
 * «Noise rescale: effective=1.0»), so a night FULLRES photo of 8-20 merged N stopped at the one-frame clamp (log26 / log31 «ae=4.1440
 * ... gainClamp=4.144»; the owner's log-2026-10-17 «ae=1.1545 ... gainClamp=1.154» at ISO 6400 with 10-11 N).
 * <p>
 * The clamp alone divides the slope and offset by N_eff^0.8 (the 0.8 allows for correlated and fixed-pattern noise), so it rises by
 * N_eff^0.4; maxBoost 5, the hard 9x and every denoiser stay as they are (Parameters.effectiveStackSamples is not set: the denoisers'
 * base strength keeps the one-frame model, their per-pixel boost reads the map one-sided).
 * <p>
 * N_eff is the 25th percentile of the merge's effective-frame map on a 1/64 sample (every 8th pixel of every 8th row; 1 B a pixel,
 * 50 MB at 50 MP, read sparse on the CPU), only when the map is the output's size. The worker scales the map to its median coverage
 * (code 64 = its «HYBRID EFFECTIVE MAP: median frames=» value, not 1/8 frame as its first format was), and that coverage counts more
 * than frames on the native mosaic merges (22 for 8 N, 32.6 for 10 N in log-2026-10-17), so N_eff is the smaller of the absolute
 * coverage and the median pixel taken as all N, clamped to [1, merged N]. Without a map (200 MP: no map above 100 MP) it is 1.
 * <p>
 * scam_dev.txt "ae_clamp_stack": 1 (default) FULLRES shots (Parameters.fullResRequested), 2 every SHDR shot (the 12 MP A/B), 0 off.
 * The old-model run of post_ab keeps the one-frame clamp, so the A/B shows this change on one worker result.
 */
final class WhyNoiseClamp {
    private WhyNoiseClamp() {}

    /** The model divisor's exponent: N_eff^0.8 (the clamp rises by N_eff^0.4). */
    static final double EXPONENT = 0.8;
    /** Every STEP-th pixel of every STEP-th row: a 1/64 sample of the map. */
    static final int STEP = 8;
    /** The worker's code of its median coverage. */
    static final double MEDIAN_CODE = 64.0;

    /** scam_dev.txt "ae_clamp_stack" (0 off, 1 FULLRES, 2 every shot) for a shot that is FULLRES or not. */
    static boolean wanted(boolean fullRes) {
        final float mode = PreferenceKeys.scamDevNumber("ae_clamp_stack", 1f);
        return mode >= 2f || mode >= 1f && fullRes;
    }

    /**
     * N_eff of the merge's map ({@code width} x {@code height} codes, 0 = unknown): the 25th percentile of the non-zero codes of the
     * 1/64 sample, as frames the smaller of {@code code * medianFrames / 64} and {@code code * normals / 64}, clamped to [1, normals];
     * 1 without a map of that size, without the worker's median or without a non-zero code.
     */
    static float effectiveFrames(@Nullable ByteBuffer map, int width, int height, float medianFrames, int normals) {
        if (map == null || width <= 0 || height <= 0 || (long) width * height != map.capacity() || map.limit() != map.capacity() || normals <= 1
                || !(medianFrames > 0f) || Float.isInfinite(medianFrames)) return 1f;
        final int[] histogram = new int[256];
        long count = 0;
        for (int y = STEP / 2; y < height; y += STEP) {
            final int row = y * width;
            for (int x = STEP / 2; x < width; x += STEP) {
                final int v = map.get(row + x) & 255;
                if (v > 0) { histogram[v]++; count++; }
            }
        }
        if (count == 0) return 1f;
        final long target = (count + 3) / 4;
        int p25 = 1;
        long seen = 0;
        for (int v = 1; v < 256; v++) {
            seen += histogram[v];
            if (seen >= target) { p25 = v; break; }
        }
        final double frames = Math.min(p25 * (double) medianFrames, p25 * (double) normals) / MEDIAN_CODE;
        return (float) Math.max(1.0, Math.min(normals, frames));
    }

    /**
     * P391 (review): N_eff of the WHY fusion's noise caps - {@link #effectiveFrames} when the merge's map is the output's size and
     * the worker reported its median coverage, else (a 200 MP shot: no map above 100 MP; a report without the median) what the RAW
     * viewfinder expects of a handheld burst, 0.55 of the merged N frames (LiveWhyTone.mergedNoise), clamped to [1, normals]: N_eff 1
     * there gave a merged shot the one-frame noise and the caps cut its clean shadows' lift.
     */
    static float fusionFrames(@Nullable ByteBuffer map, int width, int height, float medianFrames, int normals) {
        final boolean usable = map != null && width > 0 && height > 0 && (long) width * height == map.capacity() && map.limit() == map.capacity()
                && medianFrames > 0f && !Float.isInfinite(medianFrames);
        if (usable) return effectiveFrames(map, width, height, medianFrames, normals);
        return normals > 1 ? Math.max(1f, Math.min(normals, EXPECTED_SHARE * normals)) : 1f;
    }

    /** P391: the share of each normal frame a handheld burst's merge keeps (LiveWhyTone's: HYBRID MERGE FACTORS of the owner's logs). */
    static final float EXPECTED_SHARE = 0.55f;

    /** The factor the clamp's slope and offset are multiplied by: N_eff^-0.8 (1 for N_eff 1). */
    static double modelScale(float effective) {
        return effective > 1f ? Math.pow(effective, -EXPONENT) : 1.0;
    }

    /**
     * Applies N_eff to {@code s} (its noiseS / noiseO, the clamp's only readers besides «has a model») and returns the log line, null
     * when nothing changed (off, the old model of post_ab, no model, N_eff 1).
     */
    @Nullable
    static String apply(WhyAe.Settings s, boolean fullRes, boolean legacy, @Nullable ByteBuffer map, int width, int height,
                        float medianFrames, int normals) {
        if (legacy || s == null || !(s.noiseS > 0 || s.noiseO > 0) || !wanted(fullRes)) return null;
        final float effective = effectiveFrames(map, width, height, medianFrames, normals);
        final double before = WhyAe.gainClamp(s.noiseS, s.noiseO, s.noiseMax);
        if (!(effective > 1f)) {
            return String.format(Locale.ROOT, "AE noise clamp: Neff=1 (%s) clamp %.3f kept", map == null ? "no frame map"
                    : (long) width * height != map.capacity() ? "frame map of another size" : !(medianFrames > 0f)
                    ? "no median coverage in the worker's report" : normals <= 1 ? "one N frame" : "map at one frame", before);
        }
        final double k = modelScale(effective);
        s.noiseS *= k;
        s.noiseO *= k;
        final double after = WhyAe.gainClamp(s.noiseS, s.noiseO, s.noiseMax);
        return String.format(Locale.ROOT, "AE noise clamp: Neff=%.2f (of %d N, median coverage %.2f) clamp %.3f -> %.3f",
                effective, normals, medianFrames, before, after);
    }
}
