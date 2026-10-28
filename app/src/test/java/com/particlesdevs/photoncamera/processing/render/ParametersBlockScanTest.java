package com.particlesdevs.photoncamera.processing.render;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * P371 (speed): the black-floor scan of Parameters.refineBlackLevel with each sampled row copied into an array first (~20 ms
 * of single ShortBuffer reads in the shot's Java prep) gives what the scan of every sample through the buffer gave: the same
 * block means, padded and zero blocks, padding count, the two largest samples and the lowest block.
 */
public class ParametersBlockScanTest {
    /** The scan exactly as Parameters.refineBlackLevel ran it before P371 (a block at a time, a read per sample). */
    private static final class Old {
        float[][] means;
        boolean[][] low, zero;
        int padding, max, max2;
        float lowest = Float.MAX_VALUE;

        Old(ShortBuffer data, int width, int bw, int bh, float paddingBelow) {
            final int bs = 32, blocks = bw * bh;
            means = new float[4][blocks];
            low = new boolean[4][blocks];
            zero = new boolean[4][blocks];
            for (int c = 0; c < 4; c++) {
                int ox = c % 2, oy = c / 2;
                for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
                    long sum = 0;
                    boolean padded = false, allZero = true;
                    for (int y = 0; y < bs; y += 4) {
                        int row = (by * bs + y) * 2 + oy;
                        int base = row * width + bx * bs * 2 + ox;
                        for (int x = 0; x < bs; x += 4) {
                            int v = data.get(base + x * 2) & 0xffff;
                            sum += v;
                            if (v > max) { max2 = max; max = v; } else if (v > max2) max2 = v;
                            padded |= v < paddingBelow;
                            allZero &= v == 0;
                        }
                    }
                    final int i = by * bw + bx;
                    means[c][i] = sum / (float) ((bs / 4) * (bs / 4));
                    if (!allZero) lowest = Math.min(lowest, means[c][i]);
                    low[c][i] = padded;
                    zero[c][i] = allZero;
                    if (padded) padding++;
                }
            }
        }
    }

    private static ShortBuffer frame(int w, int h, long seed, int kind) {
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        Random r = new Random(seed);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int v;
            switch (kind) {
                case 0: v = 60 + r.nextInt(9) + ((x / 200 + y / 150) % 4) * 90; break; // the OPPO's drifted floor
                case 1: v = x < w - 96 && y < h - 72 ? 64 + r.nextInt(5) : 0; break; // the X200 Pro's zero padding
                case 2: v = 18 + r.nextInt(6); break; // a RAW without its black pedestal
                default: v = r.nextInt(70000); break; // anything, the 16-bit wrap and samples above the white
            }
            if (r.nextInt(5000) == 0) v = 1023 + r.nextInt(3000); // hot pixels: the two largest
            s.put(y * w + x, (short) v);
        }
        return b.asShortBuffer();
    }

    private static void assertSame(Old o, Parameters.BlockScan n) {
        assertEquals(o.padding, n.padding);
        assertEquals(o.max, n.max);
        assertEquals(o.max2, n.max2);
        assertEquals(Float.floatToIntBits(o.lowest), Float.floatToIntBits(n.lowest));
        for (int c = 0; c < 4; c++) {
            assertEquals(o.means[c].length, n.means[c].length);
            for (int i = 0; i < o.means[c].length; i++) {
                assertEquals(Float.floatToIntBits(o.means[c][i]), Float.floatToIntBits(n.means[c][i]));
                assertEquals(o.low[c][i], n.low[c][i]);
                assertEquals(o.zero[c][i], n.zero[c][i]);
            }
        }
    }

    @Test public void theBulkScanGivesWhatEverySampleGave() {
        final int[][] sizes = {{1024, 768}, {1000, 750}, {4096, 3072}, {384, 300}};
        for (int kind = 0; kind < 4; kind++) for (int[] size : sizes) {
            final int w = size[0], h = size[1], bw = w / 2 / 32, bh = h / 2 / 32;
            final ShortBuffer data = frame(w, h, 31L * kind + w, kind);
            for (float paddingBelow : new float[]{16f, -1f}) {
                final Old old = new Old(data, w, bw, bh, paddingBelow);
                assertSame(old, Parameters.BlockScan.of(data, w, bw, bh, paddingBelow, true));
                assertSame(old, Parameters.BlockScan.of(data, w, bw, bh, paddingBelow, false));
            }
            assertEquals("the scan leaves the frame's buffer as it was", 0, data.position());
        }
    }
}
