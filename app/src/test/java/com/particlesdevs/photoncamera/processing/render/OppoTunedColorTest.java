package com.particlesdevs.photoncamera.processing.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P20: the X7 Ultra's tuned colour table: CCT estimate and forward matrices. */
public class OppoTunedColorTest {
    private static final float[] FOCALS = {8.67f, 2.59f, 15.38f, 22.36f, 3.23f};
    private static final float[] D50 = {0.9642f, 1f, 0.8249f};

    private static float[] neutral(float lnRB) {
        // R/G and B/G with the given ln(R/B), G = 1
        float r = (float) Math.exp(lnRB / 2), b = (float) Math.exp(-lnRB / 2);
        return new float[]{r, 1f, b};
    }

    @Test
    public void cctFallsAsTheLightGetsWarmerAndStaysInTheTable() {
        for (float f : FOCALS) {
            float prev = Float.MAX_VALUE;
            for (float ln = -2f; ln <= 2.5f; ln += 0.05f) {
                float cct = OppoTunedColor.estimateCct(f, neutral(ln));
                assertTrue("finite " + f + " " + ln, Float.isFinite(cct));
                assertTrue("monotone " + f + " at " + ln + ": " + cct + " after " + prev, cct <= prev + 1e-3f);
                assertTrue(cct >= 2999f && cct <= 9010f);
                prev = cct;
            }
        }
    }

    @Test
    public void theMeasured6500KNeutralGivesAbout6500K() {
        assertEquals(6500f, OppoTunedColor.estimateCct(8.67f, neutral(-0.696f)), 10f); // the table puts 6500 K at 154 mired (6494 K)
        assertEquals(6500f, OppoTunedColor.estimateCct(2.59f, neutral(-0.613f)), 10f);
        assertEquals(6500f, OppoTunedColor.estimateCct(15.38f, neutral(-0.551f)), 10f);
    }

    @Test
    public void forwardMatricesKeepWhiteAreContinuousAndFinite() {
        for (float f : FOCALS) {
            float[] prev = null;
            for (float cct = 2500f; cct <= 9000f; cct += 25f) {
                float[] m = OppoTunedColor.forwardAt(f, cct);
                assertNotNull(m);
                for (int r = 0; r < 3; r++) {
                    float sum = m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2];
                    assertEquals("white row " + r + " at " + cct + " K, focal " + f, D50[r], sum, 2e-3f);
                }
                if (prev != null)
                    for (int k = 0; k < 9; k++)
                        assertTrue("continuous at " + cct + " K, focal " + f, Math.abs(m[k] - prev[k]) < 0.03f);
                prev = m;
            }
        }
        assertNull("no table: the HAL's matrix stays", OppoTunedColor.forwardAt(4.0f, 5000f));
    }
}
