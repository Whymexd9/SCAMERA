package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.util.Range;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** P98: vendor tags of the SCAM TEST reports (2026-10-11) - exposure ceilings, HAL motion, long-frame keys. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class VendorHintsTest {
    private static final long S = 1_000_000_000L;

    @Test
    public void vendorExposureCeilingRaisesTheShutterOnAStop() {
        // Xiaomi 17 Ultra main: 1 s declared, maxFrameDuration 1 s, vendor 100 s -> 32 s
        assertEquals(32 * S, (long) ExposureRanges.shutter(new Range<>(23_821L, S), S, 99_912_070_932L).getUpper());
        // OPPO X7U ultra-wide: 36 s declared, vendor 91 s -> 32 s stop is below the declared ceiling: declared stays
        assertEquals(36_313_721_080L, (long) ExposureRanges.shutter(new Range<>(102_090L, 36_313_721_080L), 36_313_808_440L, 91_482_643_490L).getUpper());
        // X200 Ultra: the vendor repeats the narrowed 0.33 s; the 33 s frame duration still gives 32 s
        assertEquals(32 * S, (long) ExposureRanges.shutter(new Range<>(43_125L, 327_675_052L), 33_024_381_611L, 327_675_052L).getUpper());
        // no vendor value: as before
        assertEquals(S, (long) ExposureRanges.shutter(new Range<>(23_821L, S), S, null).getUpper());
    }

    @Test
    public void halMotionCapsTheBracketShutterOnlyWhileMoving() {
        assertTrue(HalMotion.moving(0, 1, 0));
        assertTrue(HalMotion.moving(1, 0, 0));
        assertFalse(HalMotion.moving(0, 0, 0));
        assertEquals(125_000_000L, HybridPlan.handheldCap(10_000_000L, 0));
        assertEquals(40_000_000L, HybridPlan.handheldCap(10_000_000L, 40_000_000L));
        assertEquals(60_000_000L, HybridPlan.handheldCap(60_000_000L, 40_000_000L)); // never below N
        assertEquals(125_000_000L, HybridPlan.handheldCap(10_000_000L, 500_000_000L));
        assertEquals(0.9666f, HalMotion.shake(new float[]{0.018410832f, 0.470179f, 0.8433519f}), 1e-3f); // X9U handheld
    }

    @Test
    public void longExposureKeysOnlyPastTheFrameDurationLimit() {
        assertTrue(LongExposureKeys.needed(4 * S, S));
        assertFalse(LongExposureKeys.needed(S, S));
        assertFalse(LongExposureKeys.needed(4 * S, 33 * S)); // vivo: 4 s frames worked without the keys
        assertFalse(LongExposureKeys.needed(4 * S, null));
    }
}
