package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * P340 (owner, 2026-10-25: pairs against LMC 9.6, ArkCam and the stock camera): the fusion's darkest exposure reaches the Bento
 * headroom ceiling (WhyFusion.headroomExposure) and the detail delta grows with the finish SNR (WhyCombine.detailBoost).
 */
public class WhyPairsTest {
    @Test public void theDarkestExposureReachesTheHeadroomCeiling() {
        // the owner's sunsets: ae 2.0 / 1.16, hr 21 / 17.8 (the frame statistics gave 0.484 / 1.0)
        assertEquals(2f / (2.0015552f * 21.046875f), WhyFusion.headroomExposure(0.48407066f, 2.0015552f, 4f, 21.046875f), 1e-6f);
        assertEquals(2f / (1.1568732f * 17.8125f), WhyFusion.headroomExposure(1f, 1.1568732f, 4f, 17.8125f), 1e-6f);
        // P341: twice the ceiling (a rung in the glow kept); the statistics' exposure when that is darker already
        assertEquals(0.05f, WhyFusion.headroomExposure(0.05f, 2f, 4f, 10f), 0f);
        // without headroom (hr 0, or not above the clip) the statistics' exposure as before
        assertEquals(0.5f, WhyFusion.headroomExposure(0.5f, 2f, 4f, 0f), 0f);
        assertEquals(0.5f, WhyFusion.headroomExposure(0.5f, 2f, 4f, 4f), 0f);
        assertEquals(0.5f, WhyFusion.headroomExposure(0.5f, 0f, 4f, 20f), 0f);
        assertEquals(0.5f, WhyFusion.headroomExposure(0.5f, 2f, 4f, Float.NaN), 0f);
        // never brighter than the statistics' own
        assertEquals(0.01f, WhyFusion.headroomExposure(0.01f, 1f, 4f, 5f), 0f);
    }

    @Test public void theDetailGrowsWithTheFinishSnr() {
        assertEquals(1f, WhyCombine.detailBoost(Float.NaN), 0f);
        assertEquals(1f, WhyCombine.detailBoost(4f), 0f);
        assertEquals(1f, WhyCombine.detailBoost(20f), 0f);
        assertEquals(1.7f, WhyCombine.detailBoost(150f), 0f);
        assertEquals(1.7f, WhyCombine.detailBoost(683f), 0f);
        assertEquals(1.7f, WhyCombine.detailBoost(Float.POSITIVE_INFINITY), 0f);
        assertEquals(1.22f, WhyCombine.detailBoost(38.53f), 0.01f);  // the owner's ISO 2900 night
        float last = 0f;
        for (float snr = 1f; snr < 400f; snr *= 1.1f) {
            final float b = WhyCombine.detailBoost(snr);
            assertEquals("monotonic at " + snr, true, b >= last - 1e-6f);
            last = b;
        }
    }
}
