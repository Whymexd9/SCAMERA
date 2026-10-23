package com.particlesdevs.photoncamera.processing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P320: whether a DCG frame's RAW codes are linear, proven from its own histogram before any merge math (owner's DCG rules:
 * «считать данные линейными, пока это не доказано гистограммой» is forbidden; a companded RAW is decompanded first).
 * <p>
 * A piecewise-linear companding curve keeps every code but packs more scene range into each code above a knee: the count
 * per code steps up by the slope ratio at the knee, at the SAME code in every colour, because the curve acts on codes. A step
 * of the scene itself sits at different codes in R, G and B (the raw channels of one object differ by the white balance). The
 * test votes per colour (the two greens pooled): at a code where green and another colour have enough samples and flat counts
 * on both sides, one colour without the step proves no knee there; R, G and B all stepping alike is a knee; two colours alike
 * decide nothing. LINEAR needs no knee and at least {@link #MIN_COVERAGE} of the occupied range's samples (above the black
 * floor, below the clip plateau) at codes the test could decide - a code it could not test is no proof. Otherwise NO_DATA.
 * <p>
 * {@link #expandLut} inverts the found curve (the slopes multiply from knee to knee), scaled to fit 16 bits.
 */
public final class RawLinearity {
    public enum Verdict { LINEAR, COMPANDED, NO_DATA }

    /** Counts per code step up by at least this at a knee (a 2:1 segment is the gentlest companding seen in sensors). */
    static final double MIN_STEP = 1.6;
    /** The phases agree on the step within this ratio. */
    static final double PHASE_AGREEMENT = 1.3;
    /** Counts within this ratio on either side of the knee: a step, not a slope. */
    static final double FLAT = 1.33;
    /** Samples a window of one phase needs to vote. */
    static final int MIN_WINDOW_SAMPLES = 400;
    /** Codes the occupied range must span (fewer: nothing to judge). */
    static final int MIN_RANGE = 64;
    /** The share of the occupied range's samples a LINEAR verdict needs at codes the test decided (daylight raw: the top codes hold green alone). */
    static final double MIN_COVERAGE = 0.85;

    private RawLinearity() {}

    public static final class Result {
        public final Verdict verdict;
        /** Knee codes (ascending) and the slope ratio above each; empty unless COMPANDED. */
        public final int[] knees;
        public final double[] ratios;
        /** The analysed code range and the highest code seen. */
        public final int low, high, maxCode;
        public final long samples;
        /** The share of the analysed range's samples at codes the knee test could decide. */
        public final double coverage;

        Result(Verdict verdict, int[] knees, double[] ratios, int low, int high, int maxCode, long samples, double coverage) {
            this.verdict = verdict; this.knees = knees; this.ratios = ratios;
            this.low = low; this.high = high; this.maxCode = maxCode; this.samples = samples; this.coverage = coverage;
        }

        public boolean linear() { return verdict == Verdict.LINEAR; }

        public String describe() {
            StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "RAW linearity: %s, codes %d..%d (max %d, %d samples, %.0f %% decided)",
                    verdict, low, high, maxCode, samples, coverage * 100));
            for (int i = 0; i < knees.length; i++) b.append(String.format(Locale.ROOT, ", knee %d x%.2f", knees[i], ratios[i]));
            return b.toString();
        }
    }

    /** Histogram of the four CFA phases (phase = (y & 1) * 2 + (x & 1)) of an unpacked 16-bit RAW. */
    public static int[][] histogram(ByteBuffer raw, int width, int height, int rowStrideBytes) {
        int[][] h = new int[4][65536];
        if (raw == null || width < 2 || height < 2) return h;
        ShortBuffer s = shorts(raw);
        final int stride = rowStrideBytes > 0 ? rowStrideBytes / 2 : width;
        for (int y = 0; y < height; y++) {
            final int row = y * stride;
            if (row + width > s.limit()) break;
            final int[] even = h[(y & 1) * 2], odd = h[(y & 1) * 2 + 1];
            for (int x = 0; x < width; x += 2) {
                even[s.get(row + x) & 0xFFFF]++;
                if (x + 1 < width) odd[s.get(row + x + 1) & 0xFFFF]++;
            }
        }
        return h;
    }

    public static Result analyze(ByteBuffer raw, int width, int height, int rowStrideBytes, float[] black, float white) {
        return analyze(histogram(raw, width, height, rowStrideBytes), black, white);
    }

    /** {@code black}: the four phases' black (or one value), {@code white}: the resolved white / clip of the stream. */
    public static Result analyze(int[][] hist, float[] black, float white) {
        long samples = 0;
        int maxCode = 0;
        for (int[] h : hist) for (int c = 0; c < h.length; c++) if (h[c] > 0) { samples += h[c]; maxCode = Math.max(maxCode, c); }
        float b = 0f;
        if (black != null && black.length > 0) { for (float v : black) b += v; b /= black.length; }
        final float top = Math.min(white > b ? white : maxCode, maxCode);
        final int low = (int) Math.ceil(b + 0.03 * (top - b)), high = (int) Math.floor(top - 0.03 * (top - b));
        if (high - low < MIN_RANGE || samples < 10_000)
            return new Result(Verdict.NO_DATA, new int[0], new double[0], low, high, maxCode, samples, 0);
        final long[][] prefix = colourPrefix(hist, high);
        final int w = Math.max(16, (high - low) / 48), step = Math.max(1, w / 4);
        // candidate codes; a run of them (gaps up to 2 windows) is one knee at its strongest step
        List<int[]> found = new ArrayList<>();      // {code, ratio x1000}
        int runBest = -1, runLast = -1; double runScore = 0;
        long decided = 0, scanned = 0;
        for (int k = low + 2 * w; k <= high - 2 * w; k += step) {
            final double score = step(prefix, k, w);
            long bin = 0;
            for (long[] pre : prefix) bin += count(pre, k - step / 2, k - step / 2 + step);
            scanned += bin;
            if (score >= 0) decided += bin;
            if (score <= 0) continue;
            if (runBest >= 0 && k - runLast > 2 * w) {
                found.add(knee(prefix, low, runBest, w));
                runBest = -1;
            }
            if (runBest < 0 || score > runScore) { runBest = k; runScore = score; }
            runLast = k;
        }
        if (runBest >= 0) found.add(knee(prefix, low, runBest, w));
        final double coverage = scanned > 0 ? (double) decided / scanned : 0;
        if (found.isEmpty())
            return new Result(coverage >= MIN_COVERAGE ? Verdict.LINEAR : Verdict.NO_DATA, new int[0], new double[0], low, high, maxCode,
                    samples, coverage);
        int[] knees = new int[found.size()];
        double[] ratios = new double[found.size()];
        for (int i = 0; i < knees.length; i++) { knees[i] = found.get(i)[0]; ratios[i] = snap(found.get(i)[1] / 1000.0); }
        return new Result(Verdict.COMPANDED, knees, ratios, low, high, maxCode, samples, coverage);
    }

    /**
     * Cumulative counts (index = code, up to {@code high}) of the three colours: [0] the two greens pooled (the diagonal phase pair
     * whose histograms agree best: 1 / 2 in RGGB / BGGR, 0 / 3 in GRBG / GBRG), [1] and [2] the other two phases.
     */
    static long[][] colourPrefix(int[][] hist, int high) {
        final boolean greens12 = distance(hist[1], hist[2]) <= distance(hist[0], hist[3]);
        final int g1 = greens12 ? 1 : 0, g2 = greens12 ? 2 : 3, x = greens12 ? 0 : 1, y = greens12 ? 3 : 2;
        long[][] prefix = new long[3][high + 2];
        for (int c = 0; c <= high; c++) {
            prefix[0][c + 1] = prefix[0][c] + at(hist[g1], c) + at(hist[g2], c);
            prefix[1][c + 1] = prefix[1][c] + at(hist[x], c);
            prefix[2][c + 1] = prefix[2][c] + at(hist[y], c);
        }
        return prefix;
    }

    private static long at(int[] h, int c) { return c < h.length ? h[c] : 0; }

    /** L1 distance of two histograms normalised to their totals. */
    private static double distance(int[] a, int[] b) {
        long ta = 0, tb = 0;
        for (int v : a) ta += v;
        for (int v : b) tb += v;
        if (ta == 0 || tb == 0) return Double.MAX_VALUE;
        double d = 0;
        for (int c = 0; c < Math.min(a.length, b.length); c++) d += Math.abs((double) a[c] / ta - (double) b[c] / tb);
        return d;
    }

    private static long count(long[] pre, int from, int to) {
        from = Math.max(0, from); to = Math.min(pre.length - 1, to);
        return to > from ? pre[to] - pre[from] : 0;
    }

    /**
     * The knee test at {@code k}: > 0 (the smallest colour step) when R, G and B all step up alike with flat counts around it; 0 when
     * it is decided there is none (a colour without the step, or colours stepping differently); -1 when it cannot be decided (green
     * or every other colour without enough samples or with sloping counts, or only two colours, stepping alike).
     */
    private static double step(long[][] prefix, int k, int w) {
        double min = Double.MAX_VALUE, max = 0;
        int known = 0;
        boolean green = false, without = false;
        for (int c = 0; c < prefix.length; c++) {
            long[] pre = prefix[c];
            long l2 = count(pre, k - 2 * w, k - w), l1 = count(pre, k - w, k), r1 = count(pre, k, k + w), r2 = count(pre, k + w, k + 2 * w);
            if (l1 < MIN_WINDOW_SAMPLES || r1 < MIN_WINDOW_SAMPLES || l2 < MIN_WINDOW_SAMPLES / 2 || r2 < MIN_WINDOW_SAMPLES / 2) continue;
            if (!(l1 <= l2 * FLAT && l2 <= l1 * FLAT && r2 <= r1 * FLAT && r1 <= r2 * FLAT)) continue; // the scene's slope: unknown
            known++;
            if (c == 0) green = true;
            double r = (double) r1 / l1;
            if (r < MIN_STEP) without = true;
            else { min = Math.min(min, r); max = Math.max(max, r); }
        }
        if (!green || known < 2) return -1;
        if (without || max > min * PHASE_AGREEMENT) return 0;
        return known == 3 ? min : -1;
    }

    private static int[] knee(long[][] prefix, int low, int k, int w) {
        final int code = refine(prefix, low, k, w);
        return new int[]{code, (int) Math.round(ratio(prefix, code, w) * 1000)};
    }

    private static double ratio(long[][] prefix, int k, int w) {
        List<Double> r = new ArrayList<>();
        for (long[] pre : prefix) {
            long l1 = count(pre, k - w, k), r1 = count(pre, k, k + w);
            if (l1 >= MIN_WINDOW_SAMPLES && r1 >= MIN_WINDOW_SAMPLES) r.add((double) r1 / l1);
        }
        r.sort(Double::compare);
        return r.isEmpty() ? 1 : r.get(r.size() / 2);
    }

    /** The exact knee: the code inside the coarse step where the pooled counts per code change the most (half-width windows). */
    private static int refine(long[][] prefix, int low, int k, int w) {
        int best = k; double bestJump = -1;
        final int half = Math.max(4, w / 2);
        for (int c = Math.max(low + half, k - w); c <= k + w; c++) {
            long left = 0, right = 0;
            for (long[] pre : prefix) { left += count(pre, c - half, c); right += count(pre, c, c + half); }
            if (left <= 0) continue;
            double jump = (double) right / left;
            if (jump > bestJump) { bestJump = jump; best = c; }
        }
        return best;
    }

    /** Sensor companding slopes are powers of two: a measured ratio within 15 % of one takes it. */
    static double snap(double r) {
        double p = Math.pow(2, Math.round(Math.log(r) / Math.log(2)));
        return Math.abs(r / p - 1) <= 0.15 ? p : Math.round(r * 20) / 20.0;
    }

    /**
     * The inverse of a companding curve with {@code knees} / {@code ratios}: code -> linear code, scaled so that {@code white}
     * lands at most at 65535. Index = companded code.
     */
    public static int[] expandLut(int[] knees, double[] ratios, float white) {
        double[] x = new double[65536];
        double slope = 1, at = 0;
        int k = 0, prev = 0;
        for (int c = 0; c < 65536; c++) {
            while (k < knees.length && c >= knees[k]) {
                at += (knees[k] - prev) * slope; prev = knees[k];
                slope *= ratios[k]; k++;
            }
            x[c] = at + (c - prev) * slope;
        }
        final int wi = (int) Math.max(1, Math.min(65535, Math.round(white)));
        final double scale = x[wi] > 65535 ? 65535 / x[wi] : 1;
        int[] lut = new int[65536];
        for (int c = 0; c < 65536; c++) lut[c] = (int) Math.min(65535, Math.round(x[c] * scale));
        return lut;
    }

    /** The 16-bit little-endian samples of {@code raw} from its first byte (its own position untouched). */
    private static ShortBuffer shorts(ByteBuffer raw) {
        ByteBuffer d = raw.duplicate();
        d.position(0);
        return d.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
    }

    /** Applies {@code lut} to an unpacked 16-bit RAW in place. */
    public static void expand(ByteBuffer raw, int[] lut) {
        if (raw == null || lut == null) return;
        ShortBuffer s = shorts(raw);
        for (int i = 0; i < s.limit(); i++) s.put(i, (short) lut[s.get(i) & 0xFFFF]);
    }
}
