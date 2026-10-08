package com.particlesdevs.photoncamera.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FovSelfCheckTest {
    private static final int W = FovSelfCheck.W, H = 48;

    /** A textured scene seen with field 1/zoom around its centre, shifted by (dx, dy) of the reference px. */
    private static float[] view(float zoom, float dx, float dy) {
        float[] out = new float[W * H];
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) {
                double u = ((x - (W - 1) * 0.5) / zoom + dx) / W, v = ((y - (H - 1) * 0.5) / zoom + dy) / W;
                out[y * W + x] = (float) (100 + 40 * Math.sin(9 * u + 2 * Math.cos(7 * v)) + 30 * Math.cos(13 * v - 5 * u)
                        + 20 * Math.sin(23 * u * v + 3 * u));
            }
        return out;
    }

    @Test
    public void findsTheScaleOfADoubleCrop() {
        float[] r = FovSelfCheck.estimateScale(view(1f, 0, 0), view(2f, 0, 0), W, H);
        assertEquals(2f, r[0], 0.04f);
        assertTrue("clear peak " + r[1] + " / " + r[2], FovSelfCheck.clearPeak(r[1], r[2]));
    }

    @Test
    public void handheldShiftBetweenTheTwoViews() {
        float[] r = FovSelfCheck.estimateScale(view(1f, 0, 0), view(2f, 1.5f, -1f), W, H);
        assertEquals(2f, r[0], 0.05f);
        float[] same = FovSelfCheck.estimateScale(view(1f, 0, 0), view(1f, 2f, 1f), W, H);
        assertEquals(1f, same[0], 0.03f);
    }

    @Test
    public void unrelatedViewsGiveNoClearPeak() {
        float[] other = new float[W * H];
        for (int i = 0; i < other.length; ++i) other[i] = (float) ((i * 7919 % 251) ^ (i * 31 % 17));
        float[] r = FovSelfCheck.estimateScale(view(1f, 0, 0), other, W, H);
        assertTrue("ncc " + r[1] + " / " + r[2], !FovSelfCheck.clearPeak(r[1], r[2]));
    }

    @Test
    public void wholeCropsSnap() {
        assertEquals(2f, FovSelfCheck.snap(2.07f), 0f);
        assertEquals(4f, FovSelfCheck.snap(3.85f), 0f);
        assertEquals(1.5f, FovSelfCheck.snap(1.5f), 0f);
        assertEquals(1f, FovSelfCheck.snap(0.7f), 0f);
    }

    @Test
    public void grayFlipsRowsAndAverages() {
        byte[] rgba = new byte[4 * 2 * 4];
        for (int x = 0; x < 4; ++x) { // bottom row (GL first) white, top row black
            rgba[x * 4] = rgba[x * 4 + 1] = rgba[x * 4 + 2] = (byte) 255;
        }
        float[] g = FovSelfCheck.gray(rgba, 4, 2, 2, 2);
        assertEquals(0f, g[0], 1e-3f);
        assertEquals(255f, g[2], 0.5f);
    }
}
