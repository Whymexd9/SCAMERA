package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VivoMtkIszTest {
    @Test
    public void labelsOfTheIszModules() {
        assertEquals("2×", VivoMtkIsz.label(2f));
        assertEquals("1,3×", VivoMtkIsz.label(0.63f * 2f));
        assertEquals("7×", VivoMtkIsz.label(3.51f * 2f));
        assertEquals("14×", VivoMtkIsz.label(3.51f * 4f));
    }
}
