package com.particlesdevs.photoncamera.processing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Colour block of a RAW stream, measured from its data: 1 = plain Bayer, 2 = Quad (2x2 same-colour sites), 4 = Tetra (4x4).
 *
 * <p>A sensor mode without remosaic (vivo forceSensorMode 5 / 7, OPPO's engineer-mode select, the ISZ modes) delivers the
 * sensor's native colour-block mosaic in a RAW_SENSOR stream that Camera2 still describes as Bayer. The ISP preview and a
 * plain-Bayer merge then render it purple with a lattice. The block is what the viewfinder and the hybrid need to treat it
 * right, and the metadata does not say it.
 *
 * <p>Model (the same as {@code detectMosaicBlock} in vivo-nice-hybrid.h): the 64 phase means (y mod 8, x mod 8) of the centre
 * of the frame, black subtracted, against the colour-block models of block 1, 2 and 4, each the mean of its four phase classes.
 * A mosaic fits its own model to the noise and every other model badly (the colour step between the classes); plain Bayer fits
 * block 1 only. Confident when the best model leaves at most 1/20 of the residual of the next one and its classes differ by at
 * least 0.5 % of the level.
 */
public final class MosaicBlockDetector {
    private MosaicBlockDetector() {}

    public static final class Result {
        /** 1, 2 or 4; 1 also when the data gives no clear model. */
        public final int block;
        public final boolean confident;
        /** Residual of the models of block 1, 2, 4 (squared DN). */
        public final double[] residual;
        /** Class contrast of the chosen model (squared DN). */
        public final double contrast;
        Result(int block, boolean confident, double[] residual, double contrast) {
            this.block = block; this.confident = confident; this.residual = residual; this.contrast = contrast;
        }
        @Override public String toString() {
            return "block " + block + (confident ? " (confident" : " (no clear model") + String.format(java.util.Locale.ROOT,
                    "; residual b1/b2/b4 %.3g/%.3g/%.3g, contrast %.3g)", residual[0], residual[1], residual[2], contrast);
        }
    }

    /**
     * @param raw       uint16 little-endian sites, row-major
     * @param rowStride bytes per row
     * @param black     mean black level (DN)
     * @param bandStep  rows between the starts of the 8-row bands that are read (8 = every row; 32 reads a quarter)
     */
    public static Result detect(ByteBuffer raw, int width, int height, int rowStride, float black, int bandStep) {
        if (raw == null || width < 64 || height < 64 || rowStride < width * 2) return new Result(1, false, new double[3], 0);
        ByteBuffer b = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base = b.position();
        int x0 = (width / 10) & ~7, x1 = (width * 9 / 10) & ~7, y0 = (height / 10) & ~7, y1 = (height * 9 / 10) & ~7;
        int step = Math.max(8, bandStep & ~7);
        double[] sum = new double[64];
        long[] cnt = new long[64];
        short[] row = new short[x1 - x0];
        for (int band = y0; band + 8 <= y1; band += step) {
            for (int y = band; y < band + 8; y++) {
                long offset = base + (long) y * rowStride + x0 * 2L;
                if (offset + row.length * 2L > b.limit()) break;
                b.position((int) offset);
                b.asShortBuffer().get(row);
                int py = (y & 7) << 3;
                double[] s = new double[8];
                for (int i = 0; i < row.length; i++) s[(x0 + i) & 7] += row[i] & 0xFFFF;
                for (int k = 0; k < 8; k++) { sum[py | k] += s[k]; cnt[py | k] += row.length / 8; }
            }
        }
        double[] m = new double[64];
        for (int k = 0; k < 64; k++) m[k] = cnt[k] > 0 ? sum[k] / cnt[k] - black : 0;
        return fit(m);
    }

    /** The model fit on 64 phase means (index (y&7)*8 + (x&7), black already subtracted). */
    static Result fit(double[] m) {
        double mean = 0;
        for (double v : m) mean += v / 64;
        int[] blocks = {1, 2, 4};
        double[] residual = new double[3], between = new double[3];
        for (int i = 0; i < 3; i++) {
            int bs = blocks[i];
            double[] cs = new double[4], cn = new double[4];
            for (int k = 0; k < 64; k++) { int c = cls(k, bs); cs[c] += m[k]; cn[c]++; }
            for (int k = 0; k < 64; k++) {
                double pr = cs[cls(k, bs)] / cn[cls(k, bs)];
                residual[i] += (m[k] - pr) * (m[k] - pr) / 64;
                between[i] += (pr - mean) * (pr - mean) / 64;
            }
        }
        int best = 0;
        for (int i = 1; i < 3; i++) if (residual[i] < residual[best]) best = i;
        double second = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) if (i != best) second = Math.min(second, residual[i]);
        boolean confident = mean > 2.0 && between[best] > 2.5e-5 * mean * mean && residual[best] * 20.0 < second;
        return new Result(confident ? blocks[best] : 1, confident, residual, between[best]);
    }

    private static int cls(int k, int block) {
        return ((((k >> 3) / block) & 1) << 1) | (((k & 7) / block) & 1);
    }
}
