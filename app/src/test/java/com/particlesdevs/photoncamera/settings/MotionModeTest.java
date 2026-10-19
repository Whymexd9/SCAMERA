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
