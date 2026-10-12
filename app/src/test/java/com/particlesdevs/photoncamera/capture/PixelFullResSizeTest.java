package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.util.Size;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PixelFullResSizeTest {
    // Pixel 10 Pro XL camera 0, max-resolution map (SCAM TEST 2026-10-12)
    private static final Size[] PRIVATE = {new Size(1280, 960), new Size(1344, 1008), new Size(1792, 1008),
            new Size(1440, 1080), new Size(1920, 1080)};

    @Test
    public void viewfinderTakesTheLargestOfTheRawAspect() {
        assertEquals(new Size(1440, 1080), PixelPro10.sameAspect(PRIVATE, new Size(8160, 6144)));
    }

    @Test
    public void anotherAspectFallsBackToTheLargest() {
        assertEquals(new Size(1920, 1080), PixelPro10.sameAspect(PRIVATE, new Size(1000, 1000)));
        assertEquals(new Size(1920, 1080), PixelPro10.sameAspect(PRIVATE, null));
    }

    @Test
    public void largestByArea() {
        assertEquals(new Size(8160, 6144), PixelPro10.largest(new Size[]{new Size(4080, 3072), new Size(8160, 6144)}));
        assertNull(PixelPro10.largest(null));
        assertNull(PixelPro10.sameAspect(new Size[0], null));
    }
}
