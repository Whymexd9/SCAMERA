package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** P78: a camera without OIS keeps the newest ring frame and then the steadiest ones by gyro shakiness. */
public class ZslSteadiestTest {
    @Test
    public void newestFirstThenLowestShake() {
        float[] shake = {9f, 4f, 1f, 7f, 1f, 3f};
        assertArrayEquals(new int[]{0, 2, 4, 5, 1, 3}, CaptureController.steadiestOrder(shake));
    }

    @Test
    public void unknownShakeKeepsNewest() {
        assertNull(CaptureController.steadiestOrder(new float[]{1f, Float.NaN, 2f}));
    }

    @Test
    public void ringGapScalesWithLongExposures() {
        assertEquals(CaptureController.ZSL_RING_MAX_GAP_NS, CaptureController.ringMaxGapNs(null));
    }
}
