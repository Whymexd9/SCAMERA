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
}
