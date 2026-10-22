package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P135: the WHY sharpening backs off on a noisy shot, by the SNR of the finish input the denoise measured. */
public class WhySharpenLowLightTest {
    @Test public void shareBySnr() {
        assertEquals("unknown SNR: all of it", 1f, WhyLumaSharpen.lowLightFactor(Float.NaN), 0f);
        assertEquals(1f, WhyLumaSharpen.lowLightFactor(0f), 0f);
        assertEquals("SCA_20261015_024212 (SNR 3.04)", WhyLumaSharpen.LOW_LIGHT_MIN, WhyLumaSharpen.lowLightFactor(3.04f), 0f);
        assertEquals(WhyLumaSharpen.LOW_LIGHT_MIN, WhyLumaSharpen.lowLightFactor(4f), 1e-6f);
        assertEquals(0.66f, WhyLumaSharpen.lowLightFactor(10f), 0.01f);
        assertEquals(0.84f, WhyLumaSharpen.lowLightFactor(16f), 0.01f);
        assertEquals("daylight (X300 Ultra 2x shot: SNR 58)", 1f, WhyLumaSharpen.lowLightFactor(58.16f), 0f);
        float last = 0f;
        for (float snr = 1f; snr < 40f; snr += 0.5f) {
            float f = WhyLumaSharpen.lowLightFactor(snr);
            assertTrue(f >= last && f <= 1f && f >= WhyLumaSharpen.LOW_LIGHT_MIN);
            last = f;
        }
    }
}
