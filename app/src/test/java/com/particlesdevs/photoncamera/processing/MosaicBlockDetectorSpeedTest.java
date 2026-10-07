package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Shot speed, wave 1 (W1.5): the parallel, allocation-free colour-block detector gives the votes, tile count and answer of
 * the single-threaded one it replaced, for every block, stride padding, band step, clipped tiles and a buffer cut short.
 */
public class MosaicBlockDetectorSpeedTest {
    private static ByteBuffer frame(int w, int h, int stridePad, int block, long seed, int limitRows) {
        Random r = new Random(seed);
        int stride = (w + stridePad) * 2;
        ByteBuffer b = ByteBuffer.allocateDirect(stride * h).order(ByteOrder.LITTLE_ENDIAN);
        double[] colour = {0.45, 1.0, 0.7};
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = (((y / block) & 1) << 1) | ((x / block) & 1);
            int c = p == 0 ? 0 : p == 3 ? 2 : 1;
            double scene = 200 + 600 * x / w + 150 * Math.sin(x * 0.11) * Math.cos(y * 0.07);
            if ((x / 64 + y / 64) % 5 == 0) scene = 2000;                 // clipped tiles
            if ((x / 32 + y / 48) % 7 == 0) scene = 2;                    // black tiles
            double v = 64 + scene * colour[c] + r.nextGaussian() * Math.sqrt(scene * colour[c] * 0.5 + 4);
            b.putShort(y * stride + x * 2, (short) Math.max(0, Math.min(1023, Math.round(v))));
        }
        if (limitRows > 0) b.limit(stride * limitRows);
        return b;
    }

    private static void assertSame(String what, MosaicBlockDetector.Result expected, MosaicBlockDetector.Result actual) {
        assertArrayEquals(what, expected.votes, actual.votes);
        assertEquals(what, expected.tiles, actual.tiles);
        assertEquals(what, expected.block, actual.block);
        assertEquals(what, expected.confident, actual.confident);
    }

    @Test public void parallelDetectorEqualsTheReference() {
        int[][] sizes = {{640, 480}, {1024, 768}, {328, 248}, {64, 64}};
        for (int[] s : sizes) for (int block : new int[]{1, 2, 4}) for (int pad : new int[]{0, 32}) for (int step : new int[]{8, 16, 32})
            for (int limit : new int[]{0, s[1] * 2 / 3}) {
                ByteBuffer b = frame(s[0], s[1], pad, block, s[0] * 7L + block * 3L + pad + step, limit);
                int stride = (s[0] + pad) * 2;
                String what = s[0] + "x" + s[1] + " block " + block + " pad " + pad + " step " + step + " limit " + limit;
                assertSame(what, MosaicBlockDetector.detectReference(b, s[0], s[1], stride, 64f, 1023f, step),
                        MosaicBlockDetector.detect(b, s[0], s[1], stride, 64f, 1023f, step));
            }
    }

    @Test public void allocationFreeVoteEqualsTheAllocatingOne() {
        Random r = new Random(11);
        double[] tile = new double[64], scratch = new double[7];
        for (int n = 0; n < 20000; n++) {
            int block = 1 << r.nextInt(3);
            for (int k = 0; k < 64; k++) {
                int p = ((((k >> 3) / block) & 1) << 1) | (((k & 7) / block) & 1);
                tile[k] = (r.nextInt(3) == 0 ? 0 : p * 40) + r.nextGaussian() * (1 + r.nextInt(30));
                if (r.nextInt(50) == 0) tile[k] = Math.round(tile[k]);
            }
            assertEquals("tile " + n, MosaicBlockDetector.vote(tile), MosaicBlockDetector.vote(tile, scratch));
        }
    }
}
