package com.particlesdevs.photoncamera.capture;

import android.app.Application;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mockStatic;

/** P239 (speed-ux D1): the native 50 MP ring's copy rate and its rest without touch on a still phone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class FullResRingPolicyTest {
    private MockedStatic<PreferenceKeys> prefs;

    @Before public void setup() {
        prefs = mockStatic(PreferenceKeys.class);
        prefs.when(() -> PreferenceKeys.scamDevNumber(anyString(), anyFloat())).thenAnswer(i -> i.getArgument(1));
    }

    @After public void cleanup() { prefs.close(); }

    private static int copies(long frameNs, int frames, int fps) {
        long last = 0;
        int n = 0;
        for (int i = 0; i < frames; i++) {
            final long t = 1_000_000_000L + i * frameNs;
            if (FullResRingPolicy.rateDue(t, last, fps)) { last = t; n++; }
        }
        return n;
    }

    @Test public void theRingCopiesEightToTwelveFramesASecond() {
        assertEquals("24 fps: every second frame", 12, copies(41_666_667L, 24, 12));
        assertEquals("30 fps: every third", 10, copies(33_333_333L, 30, 12));
        assertEquals("20 fps (P224's floor): every second", 10, copies(50_000_000L, 20, 12));
        assertEquals("0: every frame, as before P239", 24, copies(41_666_667L, 24, 0));
        // the frames stay far inside the selection's stale gap: 83 - 100 ms apart against 500 ms
        assertTrue(1_000_000_000L / 10 < CaptureController.ZSL_RING_MAX_GAP_NS / 4);
        assertEquals(FullResRingPolicy.DEFAULT_FPS, FullResRingPolicy.fps());
        prefs.when(() -> PreferenceKeys.scamDevNumber(eq("fullres_ring_fps"), anyFloat())).thenReturn(0f);
        assertEquals(0, FullResRingPolicy.fps());
        prefs.when(() -> PreferenceKeys.scamDevNumber(eq("fullres_ring_fps"), anyFloat())).thenReturn(99f);
        assertEquals("at most 30", 30, FullResRingPolicy.fps());
    }

    @Test public void theRingRestsWithoutTouchOnAStillPhoneAndWakesOnATouchOrMotion() {
        final FullResRingPolicy p = new FullResRingPolicy();
        final long t0 = 100_000;
        assertFalse("still since now", p.idle(t0, 5, t0 - 60_000, 10));
        assertFalse(p.idle(t0 + 9_000, 5, t0 - 60_000, 10));
        assertTrue("10 s still, no touch for a minute", p.idle(t0 + 10_000, 5, t0 - 60_000, 10));
        assertFalse("a touch wakes it", p.idle(t0 + 11_000, 5, t0 + 10_500, 10));
        assertTrue("10 s after the touch, still all the while", p.idle(t0 + 20_600, 5, t0 + 10_500, 10));
        assertFalse("the phone moves", p.idle(t0 + 21_000, 80, t0 + 10_500, 10));
        assertFalse("still again: the still time starts over", p.idle(t0 + 22_000, 5, t0 + 10_500, 10));
        assertTrue(p.idle(t0 + 32_000, 5, t0 + 10_500, 10));
        assertFalse("0: never rests", new FullResRingPolicy().idle(t0 + 999_000, 0, 0, 0));
        assertFalse("no gyro: never rests", new FullResRingPolicy().idle(t0 + 999_000, -1, 0, 10));
        assertEquals(FullResRingPolicy.DEFAULT_IDLE_S, FullResRingPolicy.idleSeconds());
    }
}
