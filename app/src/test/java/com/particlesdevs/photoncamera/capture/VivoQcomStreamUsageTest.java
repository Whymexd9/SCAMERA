package com.particlesdevs.photoncamera.capture;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;

public class VivoQcomStreamUsageTest {
    @Test
    public void preservesOutputOrderAndRawPurpose() {
        assertArrayEquals(new int[]{2, 1, 7}, VivoQcomModes.streamUsage(new int[]{1, 7}));
        assertArrayEquals(new int[]{3, 7, 2, 1}, VivoQcomModes.streamUsage(new int[]{7, 2, 1}));
        assertArrayEquals(new int[]{3, 1, 12, 6}, VivoQcomModes.streamUsage(new int[]{1, 12, 6}));
    }
}
