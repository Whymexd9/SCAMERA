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
 * <p>Model (the same as {@code detectMosaicBlock} in scam-hybrid.h): every 8x8 tile of the frame centre is fitted to the
 * colour-block models of block 1, 2 and 4 (each the mean of its four phase classes inside the tile). The CFA's own model leaves
 * only noise and texture; a wrong one also leaves the colour step between the classes. A tile votes when its best model leaves at
 * most 1/3 of the residual of the next one. Periodic scene detail at a period dividing 8 (bars, a zone plate, fabric) can make a
 * tile vote for a wrong model, but only where it is; summed phase means carried it into the whole answer. Confident with at
 * least 50 votes and 60 % of them for one block. Clipped and black tiles are skipped.
 */
public final class MosaicBlockDetector {
    private MosaicBlockDetector() {}

    public static final class Result {
        /** 1, 2 or 4; 1 also when the data gives no clear answer. */
        public final int block;
        public final boolean confident;
        /** Votes of the tiles for block 1, 2, 4. */
        public final int[] votes;
        /** Tiles fitted (black and clipped ones left out). */
        public final int tiles;
        Result(int block, boolean confident, int[] votes, int tiles) {
            this.block = block; this.confident = confident; this.votes = votes; this.tiles = tiles;
        }
        @Override public String toString() {
            return "block " + block + (confident ? " (confident" : " (no clear answer") + "; tile votes b1/b2/b4 "
                    + votes[0] + "/" + votes[1] + "/" + votes[2] + " of " + tiles + ")";
        }
    }

    public static Result detect(ByteBuffer raw, int width, int height, int rowStride, float black, int bandStep) {
        return detect(raw, width, height, rowStride, black, 1023f, bandStep);
    }

    /**
     * @param raw       uint16 little-endian sites, row-major
     * @param rowStride bytes per row
     * @param black     mean black level (DN)
     * @param white     white level (DN): a tile with a site at 95 % of it is clipped
     * @param bandStep  rows between the starts of the 8-row tile bands that are read (8 = every band; 32 reads a quarter)
     */
    public static Result detect(ByteBuffer raw, int width, int height, int rowStride, float black, float white, int bandStep) {
        int[] votes = new int[3];
        if (raw == null || width < 64 || height < 64 || rowStride < width * 2) return new Result(1, false, votes, 0);
        final ByteBuffer b = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final int base = b.position(), limit = b.limit();
        final int x0 = (width / 10) & ~7, x1 = (width * 9 / 10) & ~7, y0 = (height / 10) & ~7, y1 = (height * 9 / 10) & ~7;
        final int step = Math.max(8, bandStep & ~7);
        final int len = x1 - x0;
        final int clip = Math.round(black + 0.95f * (white - black));
        // Bands are read in order up to the first one that runs past the buffer (every later one does too: the offsets grow).
        int complete = 0;
        for (int band = y0; band + 8 <= y1; band += step) {
            if ((long) base + (long) (band + 7) * rowStride + x0 * 2L + len * 2L > limit) break;
            complete++;
        }
        // Shot speed: the bands are independent (integer votes and tile counts summed), split over the shared pool with
        // per-thread buffers, so the answer is the one of detectReference.
        final int bands = complete;
        final int chunks = Math.max(1, Math.min(bands, com.particlesdevs.photoncamera.util.ParallelWork.threads() * 4));
        final int[][] partVotes = new int[chunks][3];
        final int[] partTiles = new int[chunks];
        com.particlesdevs.photoncamera.util.ParallelWork.forEach(chunks, c -> {
            final ByteBuffer own = b.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            final short[][] rows = new short[8][len];
            final double[] tile = new double[64];
            final double[] scratch = new double[7];
            final int first = (int) ((long) bands * c / chunks), last = (int) ((long) bands * (c + 1) / chunks);
            int tiles = 0;
            for (int i = first; i < last; i++) {
                final int band = y0 + i * step;
                for (int k = 0; k < 8; k++) {
                    own.position((int) (base + (long) (band + k) * rowStride + x0 * 2L));
                    own.asShortBuffer().get(rows[k]);
                }
                for (int tx = 0; tx + 8 <= len; tx += 8) {
                    double s = 0;
                    boolean clipped = false;
                    for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                        int v = rows[y][tx + x] & 0xFFFF;
                        if (v >= clip) clipped = true;
                        tile[(y << 3) | x] = v - black;
                        s += v - black;
                    }
                    if (clipped || !(s > 128)) continue;
                    tiles++;
                    int vote = vote(tile, scratch);
                    if (vote >= 0) partVotes[c][vote]++;
                }
            }
            partTiles[c] = tiles;
        });
        int tiles = 0;
        for (int c = 0; c < chunks; c++) {
            tiles += partTiles[c];
            for (int i = 0; i < 3; i++) votes[i] += partVotes[c][i];
        }
        return decide(votes, tiles);
    }

    /** The original single-threaded detector, kept as the reference of {@link #detect} (ShotSpeedBitExactTest). */
    static Result detectReference(ByteBuffer raw, int width, int height, int rowStride, float black, float white, int bandStep) {
        int[] votes = new int[3];
        if (raw == null || width < 64 || height < 64 || rowStride < width * 2) return new Result(1, false, votes, 0);
        ByteBuffer b = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base = b.position();
        int x0 = (width / 10) & ~7, x1 = (width * 9 / 10) & ~7, y0 = (height / 10) & ~7, y1 = (height * 9 / 10) & ~7;
        int step = Math.max(8, bandStep & ~7);
        int len = x1 - x0;
        short[][] rows = new short[8][len];
        double[] tile = new double[64];
        int clip = Math.round(black + 0.95f * (white - black));
        int tiles = 0;
        for (int band = y0; band + 8 <= y1; band += step) {
            boolean complete = true;
            for (int k = 0; k < 8; k++) {
                long offset = base + (long) (band + k) * rowStride + x0 * 2L;
                if (offset + len * 2L > b.limit()) { complete = false; break; }
                b.position((int) offset);
                b.asShortBuffer().get(rows[k]);
            }
            if (!complete) break;
            for (int tx = 0; tx + 8 <= len; tx += 8) {
                double s = 0;
                boolean clipped = false;
                for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                    int v = rows[y][tx + x] & 0xFFFF;
                    if (v >= clip) clipped = true;
                    tile[(y << 3) | x] = v - black;
                    s += v - black;
                }
                if (clipped || !(s > 128)) continue;
                tiles++;
                int vote = vote(tile);
                if (vote >= 0) votes[vote]++;
            }
        }
        return decide(votes, tiles);
    }

    /** Class of site k (row-major 8x8) in the colour-block model of block 1, 2, 4 (index 0, 1, 2). */
    private static final int[][] CLS = new int[3][64];
    static {
        final int[] blocks = {1, 2, 4};
        for (int i = 0; i < 3; i++) for (int k = 0; k < 64; k++) CLS[i][k] = cls(k, blocks[i]);
    }

    /**
     * {@link #vote(double[])} without allocations: scratch holds the three residuals (0..2) and the four class means (3..6).
     * The same sums in the same order, so the same vote.
     */
    static int vote(double[] t, double[] scratch) {
        final double[] r = scratch;
        r[0] = r[1] = r[2] = 0;
        for (int i = 0; i < 3; i++) {
            final int[] cl = CLS[i];
            r[3] = r[4] = r[5] = r[6] = 0;
            for (int k = 0; k < 64; k++) r[3 + cl[k]] += t[k];
            for (int c = 0; c < 4; c++) r[3 + c] /= 16.0;
            for (int k = 0; k < 64; k++) { double d = t[k] - r[3 + cl[k]]; r[i] += d * d; }
        }
        int best = 0;
        for (int i = 1; i < 3; i++) if (r[i] < r[best]) best = i;
        double second = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) if (i != best) second = Math.min(second, r[i]);
        return r[best] * 3.0 < second ? best : -1;
    }

    /** The block (index 0, 1, 2 = block 1, 2, 4) one 8x8 tile votes for, or -1 when no model wins by 3x. */
    static int vote(double[] t) {
        double[] r = new double[3];
        int[] blocks = {1, 2, 4};
        for (int i = 0; i < 3; i++) {
            double[] cs = new double[4];
            for (int k = 0; k < 64; k++) cs[cls(k, blocks[i])] += t[k];
            for (int c = 0; c < 4; c++) cs[c] /= 16.0;
            for (int k = 0; k < 64; k++) { double d = t[k] - cs[cls(k, blocks[i])]; r[i] += d * d; }
        }
        int best = 0;
        for (int i = 1; i < 3; i++) if (r[i] < r[best]) best = i;
        double second = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) if (i != best) second = Math.min(second, r[i]);
        return r[best] * 3.0 < second ? best : -1;
    }

    static Result decide(int[] votes, int tiles) {
        int total = votes[0] + votes[1] + votes[2], best = 0;
        for (int i = 1; i < 3; i++) if (votes[i] > votes[best]) best = i;
        boolean confident = total >= 50 && votes[best] >= 0.6 * total;
        return new Result(confident ? new int[]{1, 2, 4}[best] : 1, confident, votes, tiles);
    }

    private static int cls(int k, int block) {
        return ((((k >> 3) / block) & 1) << 1) | (((k & 7) / block) & 1);
    }
}
