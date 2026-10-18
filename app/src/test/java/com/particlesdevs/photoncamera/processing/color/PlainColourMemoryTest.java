package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * P212g (W7 = M12): the plain-session colour table of a camera - converged plain results binned by ln(r / b), persisted with the
 * firmware - and the matrix it gives a mosaic photo's neutral (the GCam CcmTable interpolation, the nearest bin alone, none far
 * away); the M12 VERIFY cases (rows sum to 1, nearest-only fallback, identity rejected, a held-out light).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class PlainColourMemoryTest {
    private static final String FW = "vivo/PD2454/PD2454:16/202";
    private SharedPreferences prefs;

    @Before public void setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("plain_colour_memory_test", 0);
        prefs.edit().clear().commit();
        PlainColourMemory.useStoreForTest(prefs, FW);
    }

    @After public void tearDown() {
        PlainColourMemory.resetForTest();
    }

    private static float[] q128(int... v) {
        final float[] m = new float[9];
        for (int i = 0; i < 9; i++) m[i] = v[i] / 128f;
        return m;
    }

    /** The X300 Ultra's plain ISP matrix (SCAMERA-debug.log (7).txt:1157) at its neutral 1 / 2.080, 1 / 1.677 (ln(r/b) -0.216). */
    private static final float[] DAY = q128(167, -37, -2, -19, 155, -7, 2, -59, 184);
    private static final float DAY_R = 1f / 2.080f, DAY_B = 1f / 1.677f;
    /** Another logged vivo ISP matrix (reports, gains R 2.229 / B 1.303: ln(r/b) -0.537). */
    private static final float[] COOL = q128(169, -38, -3, -26, 162, -9, 3, -86, 211);
    private static final float COOL_R = 1f / 2.229f, COOL_B = 1f / 1.303f;

    private static long t = 10_000L;

    private static void offer(String camera, float r, float b, float[] m, int cct, int times) {
        for (int i = 0; i < times; i++) assertTrue(PlainColourMemory.offer(camera, r, b, m, cct, t += 1_000L));
    }

    private static float[] neutral(double lnRB) {
        return new float[]{(float) Math.exp(lnRB / 2), 1f, (float) Math.exp(-lnRB / 2)};
    }

    private static void assertRowsSumToOne(float[] m) {
        for (int r = 0; r < 3; r++) assertEquals("row " + r, 1f, m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2], 1e-5f);
    }

    /** M12 VERIFY 1: between two bins the outer columns are interpolated and every row sums to 1 (a neutral stays neutral). */
    @Test public void betweenTwoBinsTheRowsSumToOne() {
        offer("5", DAY_R, DAY_B, DAY, 6000, 4);
        offer("5", COOL_R, COOL_B, COOL, 8000, 4);
        final double lo = Math.log(COOL_R / COOL_B), hi = Math.log(DAY_R / DAY_B);
        final double x = -0.30; // 0.084 from the daylight bin: interpolated
        final PlainColourMemory.Lookup l = PlainColourMemory.lookup("5", neutral(x));
        assertNotNull(l);
        assertRowsSumToOne(l.ccm);
        final double tt = (x - lo) / (hi - lo);
        for (int r = 0; r < 3; r++) {
            final double coolSum = COOL[r * 3] + COOL[r * 3 + 1] + COOL[r * 3 + 2], daySum = DAY[r * 3] + DAY[r * 3 + 1] + DAY[r * 3 + 2];
            assertEquals(COOL[r * 3] / coolSum + tt * (DAY[r * 3] / daySum - COOL[r * 3] / coolSum), l.ccm[r * 3], 1e-4);
            assertEquals(COOL[r * 3 + 2] / coolSum + tt * (DAY[r * 3 + 2] / daySum - COOL[r * 3 + 2] / coolSum), l.ccm[r * 3 + 2], 1e-4);
        }
        // the labels in mired: 125 (8000 K) to 166.7 (6000 K)
        assertEquals(1e6 / (125 + tt * (1e6 / 6000 - 125)), l.cct, 1.0);
        assertTrue(l.how, l.how.contains("between its bins"));
        // the ColorSpaceTransform keeps it within 1/1024
        final android.hardware.camera2.params.ColorSpaceTransform cst = l.transform();
        for (int i = 0; i < 9; i++) assertEquals(l.ccm[i], cst.getElement(i % 3, i / 3).floatValue(), 1f / 1024f);
    }

    /** M12 VERIFY 1: the nearest bin alone when it is more than 0.15 away or the neutral is beyond the ends; none beyond 0.4. */
    @Test public void theNearestBinAloneAndNoneFarAway() {
        offer("5", DAY_R, DAY_B, DAY, 0, 4);
        offer("5", COOL_R, COOL_B, COOL, 0, 4);
        final PlainColourMemory.Lookup gap = PlainColourMemory.lookup("5", neutral(-0.38)); // 0.157 / 0.164 from the two
        assertNotNull(gap);
        assertTrue(gap.how, gap.how.contains("alone"));
        assertRowsSumToOne(gap.ccm);
        assertEquals(COOL[0] / ((COOL[0] + COOL[1] + COOL[2])), gap.ccm[0], 1e-5f);
        assertEquals("no label, no colour temperature", 0f, gap.cct, 0f);
        final PlainColourMemory.Lookup warmer = PlainColourMemory.lookup("5", neutral(0.10)); // beyond the warm end, 0.316 away
        assertNotNull(warmer);
        assertTrue(warmer.how.contains("alone"));
        assertEquals(DAY[0] / (DAY[0] + DAY[1] + DAY[2]), warmer.ccm[0], 1e-5f);
        assertNull("0.42 away: the DNG matrices", PlainColourMemory.lookup("5", neutral(0.20)));
        assertNull("another camera has no table", PlainColourMemory.lookup("3", neutral(-0.3)));
    }

    /** A bin of fewer than 3 samples is not used; an identity or a broken matrix, a NaN neutral never get in. */
    @Test public void fewSamplesIdentityAndBadInputAreLeftOut() {
        offer("4", DAY_R, DAY_B, DAY, 0, 2);
        assertNull("2 samples", PlainColourMemory.lookup("4", new float[]{DAY_R, 1f, DAY_B}));
        offer("4", DAY_R, DAY_B, DAY, 0, 1);
        assertNotNull("3 samples", PlainColourMemory.lookup("4", new float[]{DAY_R, 1f, DAY_B}));
        assertFalse("identity", PlainColourMemory.offer("4", 0.5f, 0.5f, new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, 0, t += 1000));
        assertFalse("a row summing to 2", PlainColourMemory.offer("4", 0.5f, 0.5f, new float[]{2, 0, 0, 0, 1, 0, 0, 0, 1.1f}, 0, t += 1000));
        assertFalse("NaN", PlainColourMemory.offer("4", 0.5f, 0.5f, new float[]{Float.NaN, 0, 0, 0, 1, 0, 0, 0, 1}, 0, t += 1000));
        assertFalse("NaN neutral", PlainColourMemory.offer("4", Float.NaN, 0.5f, DAY, 0, t += 1000));
        assertNull(PlainColourMemory.lookup("4", new float[]{Float.NaN, 1f, 0.5f}));
        assertEquals(1, PlainColourMemory.size("4"));
    }

    /** M12 corr. 3: the AWB label counts only after convergence and never as the 3745 K init signature (nor out of range). */
    @Test public void theInitSignatureIsNoLabel() {
        assertEquals(0, PlainColourMemory.label(new int[]{3745}));
        assertEquals(0, PlainColourMemory.label(new int[]{0}));
        assertEquals(0, PlainColourMemory.label(null));
        assertEquals(0, PlainColourMemory.label(new int[]{40000}));
        assertEquals(5546, PlainColourMemory.label(new int[]{5546}));
        offer("2", 0.408f, 0.614f, DAY, 5546, 3);
        offer("2", 0.408f, 0.614f, DAY, 0, 3);
        final List<double[]> labelled = PlainColourMemory.labelled("2", 5000f, 6000f);
        assertEquals(1, labelled.size());
        assertEquals(3, labelled.get(0)[2], 0);
        assertEquals(1e6 / 5546, labelled.get(0)[1], 1e-3);
        assertTrue(PlainColourMemory.labelled("2", 6000f, 7000f).isEmpty());
    }

    /** The table survives a new process; another firmware forgets it; one sample a second. */
    @Test public void storedWithTheFirmware() {
        offer("5", DAY_R, DAY_B, DAY, 6200, 6); // 6 samples over 6 s: written at least once after the first
        PlainColourMemory.useStoreForTest(prefs, FW); // a new process, the same store
        final PlainColourMemory.Lookup l = PlainColourMemory.lookup("5", new float[]{DAY_R, 1f, DAY_B});
        assertNotNull(l);
        assertEquals(6200f, l.cct, 1f);
        PlainColourMemory.useStoreForTest(prefs, "vivo/PD2454/PD2454:16/203"); // an update
        assertNull(PlainColourMemory.lookup("5", new float[]{DAY_R, 1f, DAY_B}));
        assertFalse(prefs.contains("5"));
        assertTrue(PlainColourMemory.due("5", 100_000L));
        assertFalse("within the second", PlainColourMemory.due("5", 100_500L));
        assertTrue(PlainColourMemory.due("5", 101_000L));
    }

    /** At most 24 bins a camera: the least seen gives way. */
    @Test public void theLeastSeenBinGivesWay() {
        for (int i = 0; i < 25; i++) {
            final double x = -1.2 + 0.05 * i;
            offer("6", (float) Math.exp(x / 2), (float) Math.exp(-x / 2), DAY, 0, i == 3 ? 1 : 2);
        }
        assertEquals(PlainColourMemory.MAX_BINS, PlainColourMemory.size("6"));
        assertTrue(PlainColourMemory.format(PlainColourMemory.parse(prefs.getString("6", "|").split("\\|", 2)[1])).length() > 0);
    }

    /**
     * M12 VERIFY 2 (offline hold-out): one camera's logged daylight ISP results (reports: the same matrix at gains R 2.65-2.90 /
     * B 1.25-1.50) - each held-out light gets its own matrix back from the others within a Frobenius distance of 0.03.
     */
    @Test public void aHeldOutLightGetsItsMatrixBack() {
        final float[] m = q128(166, -43, 5, -14, 163, -21, 2, -51, 177);
        final float[][] gains = {{2.650f, 1.372f}, {2.896f, 1.322f}, {2.859f, 1.284f}, {2.857f, 1.245f}, {2.770f, 1.308f},
                {2.759f, 1.497f}, {2.679f, 1.345f}};
        for (int out = 0; out < gains.length; out++) {
            PlainColourMemory.useStoreForTest(prefs, FW);
            prefs.edit().clear().commit();
            PlainColourMemory.useStoreForTest(prefs, FW);
            for (int i = 0; i < gains.length; i++)
                if (i != out) offer("7", 1f / gains[i][0], 1f / gains[i][1], m, 0, 3);
            final PlainColourMemory.Lookup l = PlainColourMemory.lookup("7", new float[]{1f / gains[out][0], 1f, 1f / gains[out][1]});
            assertNotNull("held out " + out, l);
            double f = 0;
            for (int r = 0; r < 3; r++) {
                final double sum = m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2];
                for (int c = 0; c < 3; c++) f += Math.pow(l.ccm[r * 3 + c] - m[r * 3 + c] / sum, 2);
            }
            assertTrue("held out " + out + ": " + Math.sqrt(f), Math.sqrt(f) < 0.03);
        }
    }
}
