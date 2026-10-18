package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VfDrawMeterTest {
    @Test
    public void lineReportsDrawnFramesRateGapAndDrawTime() {
        assertEquals("t=+4.0s drawn=58 (29.0 fps) maxGap=41ms draw=1.2/6.8ms",
                VfDrawMeter.line(4000, 2000, 58, 41, 1.24, 6.81));
        assertEquals("t=+0.0s drawn=0 (0.0 fps) maxGap=0ms draw=0.0/0.0ms", VfDrawMeter.line(0, 0, 0, 0, 0, 0));
    }

    /** P236: the fps over the last 2 s for the shot summary, from the recent draw times (any time, not only after a shot). */
    @Test
    public void fpsOverTheLastTwoSecondsCountsTheRecentDraws() {
        final long base = 10_000_000L; // far beyond anything an earlier test drew
        for (int i = 0; i < 60; i++) VfDrawMeter.recent(base + i * 33L); // 30 fps for ~2 s
        assertEquals(30.0, VfDrawMeter.fpsSince(base + 59 * 33L, 2000), 0.6);
        assertEquals("nothing drawn in the window", 0.0, VfDrawMeter.fpsSince(base + 10_000_000L, 2000), 1e-9);
        assertEquals("a frame newer than now is not counted", 0.0, VfDrawMeter.fpsSince(base - 10, 2000), 1e-9);
    }
}
