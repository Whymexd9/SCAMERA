package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.util.Range;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** P86 «Движение»: the viewfinder / ring rate and the merge keys the mode replaces. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MotionModeTest {
    @SuppressWarnings("unchecked")
    private static Range<Integer>[] ranges(int... lohi) {
        Range<Integer>[] out = new Range[lohi.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = new Range<>(lohi[2 * i], lohi[2 * i + 1]);
        return out;
    }

    @Test
    public void fastestFixedRateWithinTheRawStream() {
        // OPPO Find X8 Ultra (SCAM TEST 2026-10-11)
        Range<Integer>[] x8u = ranges(10, 10, 15, 15, 10, 22, 10, 24, 24, 24, 10, 30, 30, 30, 10, 60, 60, 60);
        assertEquals(new Range<>(60, 60), MotionMode.fpsRange(x8u, 0, 0));    // RAW limit unknown: the camera's maximum
        assertEquals(new Range<>(60, 60), MotionMode.fpsRange(x8u, 60f, 0));
        assertEquals(new Range<>(30, 30), MotionMode.fpsRange(x8u, 30f, 0));   // RAW at 30 fps at most
        assertEquals(new Range<>(24, 24), MotionMode.fpsRange(x8u, 60f, 24));  // the user's choice
        // vivo X200 Ultra: no 60, [30, 30]
        assertEquals(new Range<>(30, 30), MotionMode.fpsRange(ranges(5, 7, 10, 10, 5, 30, 7, 30, 30, 30), 0, 0));
        assertNull(MotionMode.fpsRange(ranges(5, 30, 7, 30), 0, 0));
        assertNull(MotionMode.fpsRange(null, 0, 0));
    }

    @Test
    public void aFastSeriesTakesFewerFramesBehindAQueue() {
        // P113: an empty merge queue keeps the setting; 2-3 waiting shots: 4 frames; more: 2
        assertEquals(8, MotionMode.framesForQueue(8, 0));
        assertEquals(8, MotionMode.framesForQueue(8, 1));
        assertEquals(4, MotionMode.framesForQueue(8, 2));
        assertEquals(4, MotionMode.framesForQueue(8, 3));
        assertEquals(2, MotionMode.framesForQueue(8, 4));
        assertEquals(2, MotionMode.framesForQueue(8, 9));
        assertEquals(3, MotionMode.framesForQueue(3, 2)); // never more than the setting
        assertEquals(1, MotionMode.framesForQueue(1, 5));
    }

    @Test
    public void replacesOnlyTheMergeKeysOfTheMode() {
        assertTrue(MotionMode.replaces("zsl_frames"));
        assertTrue(MotionMode.replaces("shasta"));
        assertEquals(8f, MotionMode.DEFAULTS.get("zsl_frames"), 0f);
        assertEquals(0f, MotionMode.DEFAULTS.get("shasta"), 0f);
        assertTrue(!MotionMode.replaces("ark_sharp_rl3_amount"));
    }
}
