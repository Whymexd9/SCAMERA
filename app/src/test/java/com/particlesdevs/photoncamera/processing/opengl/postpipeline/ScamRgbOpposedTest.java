package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** P362: the «Inpaint opposed» chrominance from the compute pass's group sums (float bits) and counts. */
public class ScamRgbOpposedTest {
    private static int[] group(float r, float g, float b, int nr, int ng, int nb) {
        return new int[]{Float.floatToIntBits(r), Float.floatToIntBits(g), Float.floatToIntBits(b), nr, ng, nb};
    }

    @Test
    public void sumsOverCounts() {
        final int[] a = group(1.5f, -0.5f, 0f, 3, 1, 0), b = group(0.5f, -0.5f, 0f, 1, 1, 0);
        final int[] v = new int[12];
        System.arraycopy(a, 0, v, 0, 6);
        System.arraycopy(b, 0, v, 6, 6);
        // R (1.5 + 0.5) / 4, G -1 / 2, B nothing counted: 0
        assertArrayEquals(new float[]{0.5f, -0.5f, 0f}, ScamRgb.opposedMeans(v, 2), 1e-6f);
    }

    @Test
    public void onlyClippedPixelsWithoutUltrashortContentCount() {
        // bits 0-2 clipped means, 3 border, 4 Bento mask, 5 ultrashort clipped mean
        final java.nio.ByteBuffer f = java.nio.ByteBuffer.wrap(new byte[]{0, 1, 2, 7, 8, 9, 16 | 1, 32 | 2, 16, (byte) 0x84});
        assertEquals(5L, ScamRgb.opposedPixels(f)); // 1, 2, 7, 9, 0x84 (bit 2 with bit 7)
    }

    @Test
    public void aNonFiniteGroupSumIsLeftOut() {
        final int[] v = group(Float.NaN, 2f, Float.POSITIVE_INFINITY, 0, 4, 0);
        assertArrayEquals(new float[]{0f, 0.5f, 0f}, ScamRgb.opposedMeans(v, 1), 1e-6f);
    }
}
