package com.particlesdevs.photoncamera.processing;

import android.util.Pair;

import com.particlesdevs.photoncamera.BuildConfig;
import com.particlesdevs.photoncamera.util.BuildIdentity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P214c (quality.md 4): the Software and NoiseProfile tags of our DNGs, as plain arithmetic the unit tests check (DngCreator
 * loads its native library, so it cannot run on the JVM).
 *
 * <p>Software: the owner's SCA_20261017_153558.dng read "SCAMERA  (-1)" - DngCreator imported circleimageview's BuildConfig
 * (no VERSION_NAME, VERSION_CODE -1); P214b switched it to the app's. It now also names the commit, the 8 hex digits of
 * {@link BuildIdentity}, so a DNG the owner sends is tied to its build as a log is.
 *
 * <p>NoiseProfile: the DNG spec's per-plane model of the variance of the normalised level x = (v - black) / (white - black),
 * var(x) = S x + O. The merged DNG (P165) does not hold one frame's level: the worker writes m = x_N / H, the merge's level
 * x_N (1 = the N frames' white) over the headroom H, and x_N averages N_eff frames, var(x_N) = (S x_N + O) / N_eff. So
 * var(m) = var(x_N) / H^2 = S / (H N_eff) m + O / (H^2 N_eff). The tag held the sensor's one-frame model (S, O) instead:
 * {@code NoiseModeler.computeModel} is base / FrameNumberSelector.frameCount, which nothing sets any more (0 -> 1), and was
 * written before ScamProcessor's computeStackingNoiseModel, so the tag overstated the noise H N_eff times (that file: headroom
 * 4.32) and a raw converter's noise reduction smeared a clean merge. Two more factors of the same kind: the binned DNG of a merge
 * without the remosaic (P195, W/b x H/b, every site the mean of a b x b colour block: the worker's own model of those sites
 * is slope / b^2, offset / b^2) and the worker's measured correction of the HAL model (HYBRID NOISE CHECK "-> model xK", P77,
 * applied to slope and offset of every frame; the app's denoise multiplies the same base by it).
 */
public final class DngMetadata {
    private DngMetadata() {}

    /** The Software tag: {@code SCAMERA <versionName> (<versionCode>) <sha8>[+dirty]}, e.g. "SCAMERA 0.98 (30966) cab4480a". */
    public static String software() {
        return software(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA, BuildConfig.GIT_DIRTY);
    }

    static String software(String versionName, long versionCode, String sha, boolean dirty) {
        return "SCAMERA " + versionName + " (" + versionCode + ") " + BuildIdentity.shortSha(sha) + (dirty ? "+dirty" : "");
    }

    private static final String FACTORS = "HYBRID MERGE FACTORS:";
    private static final Pattern SHARE = Pattern.compile("(?<![0-9.])[0-9]+:(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern AVERAGE = Pattern.compile("average=(-?[0-9]+(?:\\.[0-9]+)?)");

    /**
     * N_eff of the last merge, from the worker's report line {@code HYBRID MERGE FACTORS: 1:0.32 2:0.30 ... average=0.386570}
     * (scam-hybrid.h robustShare[f]: donor f's accepted weight 0..1 averaged over the whole frame, after the dilation, its
     * scalar weight and the Bento mask): the base frame, weight 1, plus every donor's share - how many frames the merged
     * level averages. OPPO Find X7 Ultra log-2026-10-17, 17 merges: 1.5 (2 frames merged) to 21.6 (26 frames), 5.6 for the
     * 13 frames of the 50 MP shot at 23:10. The effective-frame map is no source for it: its codes are scaled so the median is
     * 64 whatever the burst (codeScale = 64 / median), and the "HYBRID EFFECTIVE MAP: median frames=" it is scaled by sums
     * the Sabre kernel's weights (4.3 to 89 on those merges: 12.4 for the 2 frames, 71.6 for 18), not frames.
     *
     * <p>Only this attempt's report counts (after the last "RETRY:" of a retried job, ScamNeuralClient), and the last line of
     * it: a native mosaic merge that failed is merged again on the sub-frame split, whose outputs the DNG holds. That split
     * (hybridReconstructMosaic, "HYBRID MOSAIC: ... merging M sub-frames on their bx grid") merges the b^2 sub-frames of
     * every frame as frames of their own, so its line counts sub-frames: divided by b^2 it counts frames again (the base's
     * sub-frames are 1 frame, a donor's its share), the same frame-only count as the other paths (the kernel's spatial
     * average is left out everywhere: the profile errs on the noisy side). A line without donors (one frame merged) gives 1.
     * NaN when the attempt has no such line (an older worker, a report cut at its size cap): the caller keeps the model it
     * had.
     */
    public static double mergeFrames(CharSequence report) {
        if (report == null) return Double.NaN;
        final String s = report.toString();
        final int retry = s.lastIndexOf("\nRETRY:\n");
        final int at = s.lastIndexOf(FACTORS);
        if (at < 0 || at < retry) return Double.NaN;
        final int end = s.indexOf('\n', at);
        final String line = s.substring(at + FACTORS.length(), end < 0 ? s.length() : end);
        final int avgAt = line.indexOf("average=");
        final Matcher m = SHARE.matcher(avgAt < 0 ? line : line.substring(0, avgAt));
        int donors = 0;
        double sum = 0;
        while (m.find()) {
            final double v = parse(m.group(1));
            if (!Double.isFinite(v)) continue;
            sum += Math.max(0, Math.min(1, v));
            ++donors;
        }
        if (donors == 0) return 1;
        // the average carries 6 decimals of the unrounded shares, the tokens 2
        final Matcher a = AVERAGE.matcher(line);
        if (a.find()) {
            final double avg = parse(a.group(1));
            if (Double.isFinite(avg)) sum = Math.max(0, Math.min(1, avg)) * donors;
        }
        // the sub-frame split's line of this merge: after the attempt's start and any earlier merge's line
        final int previous = s.lastIndexOf(FACTORS, at - 1);
        final int split = s.lastIndexOf(SPLIT, at);
        int perFrame = 1;
        if (split > Math.max(retry, previous)) {
            final int splitEnd = s.indexOf('\n', split);
            final Matcher g = SPLIT_GRID.matcher(s.substring(split, splitEnd < 0 || splitEnd > at ? at : splitEnd));
            if (g.find()) {
                int b;
                try { b = Integer.parseInt(g.group(1)); } catch (NumberFormatException e) { b = 1; }
                b = Math.max(1, Math.min(8, b));
                perFrame = b * b;
            }
        }
        return Math.max(1, (1 + sum) / perFrame);
    }

    private static final String SPLIT = "HYBRID MOSAIC: binned alignment";
    private static final Pattern SPLIT_GRID = Pattern.compile("sub-frames on their ([0-9]+)x grid");

    private static double parse(String v) {
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return Double.NaN; }
    }

    /**
     * NoiseProfile of a merged DNG: per plane (R, G, B) S' = k S / (H N A), O' = k O / (H^2 N A), from the one-frame model
     * {@code single} = {S_R, O_R, S_G, O_G, S_B, O_B}; k = the worker's noise factor, H = the headroom, N = the frames merged,
     * A = the sensor sites averaged into one DNG site (1, or b^2 of a DNG binned by b). H, N and A below 1, and every
     * non-finite one, count as 1; k outside 1/16..4 (the worker's own range) as 1. One frame written as it came
     * (k = H = N = A = 1) keeps the sensor's model.
     */
    public static double[] noiseProfile(double[] single, double noiseFactor, double headroom, double frames, double averagedSites) {
        if (single == null || single.length != 6) throw new IllegalArgumentException("noise model must hold 3 (S, O) pairs");
        final double k = Double.isFinite(noiseFactor) && noiseFactor >= 1.0 / 16 && noiseFactor <= 4 ? noiseFactor : 1;
        final double h = atLeastOne(headroom), n = atLeastOne(frames), a = atLeastOne(averagedSites);
        final double[] out = new double[6];
        for (int c = 0; c < 3; c++) {
            out[2 * c] = k * single[2 * c] / (h * n * a);
            out[2 * c + 1] = k * single[2 * c + 1] / (h * h * n * a);
        }
        return out;
    }

    private static double atLeastOne(double v) {
        return Double.isFinite(v) && v > 1 ? v : 1;
    }

    /** A NoiseModeler model (R, G, B pairs of slope / offset) as {S_R, O_R, S_G, O_G, S_B, O_B}; null when incomplete. */
    public static double[] planes(Pair<Double, Double>[] model) {
        if (model == null || model.length < 3) return null;
        final double[] out = new double[6];
        for (int c = 0; c < 3; c++) {
            if (model[c] == null || model[c].first == null || model[c].second == null) return null;
            out[2 * c] = model[c].first;
            out[2 * c + 1] = model[c].second;
        }
        return out;
    }
}
