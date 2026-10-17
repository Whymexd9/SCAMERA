package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.hardware.camera2.params.ColorSpaceTransform;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** P192: the OPPO Find X7 Ultra's native FULLRES modes, measured on PHY110 (2026-10-17) and nowhere else. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class OppoNativeFullResTest {
    @Test public void anyOppoAndThePhy110() {
        assertTrue(OppoNativeFullRes.model("OPPO", "PHY110"));
        assertTrue(OppoNativeFullRes.model("oppo", "PHY110"));
        assertFalse("Find X8 Ultra is not the measured model", OppoNativeFullRes.model("OPPO", "PKJ110"));
        assertTrue("but an OPPO phone", OppoNativeFullRes.brand("OPPO"));
        assertFalse(OppoNativeFullRes.brand("vivo"));
        assertFalse(OppoNativeFullRes.brand("OnePlus"));
        assertFalse(OppoNativeFullRes.brand(null));
    }

    @Test public void theMeasuredModesOfThePhy110() {
        assertEquals(35, OppoNativeFullRes.mode("2"));
        assertEquals(16, OppoNativeFullRes.mode("3"));
        assertEquals(19, OppoNativeFullRes.mode("4"));
        assertEquals(17, OppoNativeFullRes.mode("5"));
        assertEquals(-1, OppoNativeFullRes.mode("0"));
        assertEquals(-1, OppoNativeFullRes.mode("1"));
    }

    /** A table of {count, 3, then w, h, fps of each row} with the given full-size rows (8192x6144) among 4096x3072 ones. */
    private static int[] table(int count, int fullFps, int... fullRows) {
        int[] t = new int[2 + count * 3];
        t[0] = count;
        t[1] = 3;
        for (int i = 0; i < count; i++) { t[2 + i * 3] = 4096; t[3 + i * 3] = 3072; t[4 + i * 3] = 30; }
        for (int r : fullRows) { t[2 + r * 3] = 8192; t[3 + r * 3] = 6144; t[4 + r * 3] = fullFps; }
        return t;
    }

    /** The PHY110's tables (SCAM TEST 2026-10-11): the measured modes are the full-size rows after row 0, first in order. */
    @Test public void theCandidatesAreTheFullSizeRowsAfterRowZero() {
        assertArrayEquals("main", new int[]{8192, 6144, 24, 35, 37}, OppoNativeFullRes.fullModes(table(38, 24, 0, 35, 37)));
        assertArrayEquals("ultra-wide", new int[]{8192, 6144, 15, 16}, OppoNativeFullRes.fullModes(table(18, 15, 0, 16)));
        assertArrayEquals("3x", new int[]{8192, 6144, 24, 19}, OppoNativeFullRes.fullModes(table(21, 24, 0, 19)));
        assertArrayEquals("6x", new int[]{8192, 6144, 15, 17, 20}, OppoNativeFullRes.fullModes(table(21, 15, 0, 17, 20)));
        assertNull("only the hardware-remosaic row (the front camera)", OppoNativeFullRes.fullModes(table(9, 24, 0)));
        assertNull(OppoNativeFullRes.fullModes(null));
        assertNull("short", OppoNativeFullRes.fullModes(new int[]{38, 3, 8192}));
    }

    @Test public void anIdentityTransformIsNotTheIspColour() {
        assertTrue(OppoNativeFullRes.identity(new ColorSpaceTransform(new int[]{1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1})));
        assertFalse(OppoNativeFullRes.identity(new ColorSpaceTransform(new int[]{18, 10, -5, 10, -3, 10, -2, 10, 15, 10, -3, 10, 0, 1, -6, 10, 16, 10})));
    }

    @Test public void theOwnNeutralIsSmoothedInTheLogDomain() {
        OppoNativeFullRes.forgetNeutral("t");
        OppoNativeFullRes.measuredNeutral("t", 0.5f, 0.6f);
        assertEquals(0.5f, OppoNativeFullRes.neutral("t")[0], 1e-6f);
        OppoNativeFullRes.measuredNeutral("t", 1.0f, 0.6f);
        assertEquals((float) Math.exp(0.7 * Math.log(0.5) + 0.3 * Math.log(1.0)), OppoNativeFullRes.neutral("t")[0], 1e-5f);
        OppoNativeFullRes.measuredNeutral("t", Float.NaN, 0.6f); // ignored
        assertTrue(OppoNativeFullRes.neutral("t")[0] < 1f);
        OppoNativeFullRes.forgetNeutral("t");
    }
}
