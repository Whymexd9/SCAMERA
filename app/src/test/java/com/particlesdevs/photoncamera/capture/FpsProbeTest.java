package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.util.Range;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;

/** P266 «Проверить частоты кадров»: each rate asked for a second, the delivered rate and the sensor mode reported. */
@RunWith(RobolectricTestRunner.class)
public class FpsProbeTest {
    private static final long MS = 1_000_000L;

    @SuppressWarnings("unchecked")
    @Test public void theCandidatesAreTheFixedRatesAndCommonOnes() {
        Range<Integer>[] r = new Range[]{new Range<>(7, 30), new Range<>(30, 30), new Range<>(60, 60)};
        assertArrayEquals(new int[]{15, 24, 30, 60, 90, 120}, FpsProbe.candidates(r));
        assertArrayEquals(new int[]{15, 24, 30, 60}, FpsProbe.candidates(null));
    }

    @Test public void theMedianIntervalIsTheDeliveredRate() {
        assertEquals(30.0, FpsProbe.fps(Arrays.asList(33_333_333L, 33_333_333L, 50_000_000L)), 0.01);
        assertEquals(0.0, FpsProbe.fps(java.util.Collections.emptyList()), 0);
    }

    @Test public void eachRateIsMeasuredAfterItSettles() {
        FpsProbe p = new FpsProbe(new int[]{30, 120});
        assertNull(p.current());
        assertTrue(p.next(0));
        assertEquals(Integer.valueOf(30), p.current());
        long ts = 0, now = 0;
        boolean done = false;
        while (!done) { now += 33 * MS; ts += 33_333_333L; done = p.frame(now, ts, 2); }
        assertTrue(p.next(now));
        assertEquals(Integer.valueOf(120), p.current());
        done = false;
        final long start = now;
        while (!done) { now += 17 * MS; ts += 16_666_667L; done = p.frame(now, ts, 2); } // the camera gives 60, not 120
        assertTrue(now - start >= FpsProbe.SETTLE_NS + FpsProbe.MEASURE_NS);
        assertFalse(p.next(now));
        assertEquals("30 → 30 (2), 120 → 60 (2)", p.result());
    }
}
