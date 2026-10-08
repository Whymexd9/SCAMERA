package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;

import android.util.Size;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class SafeStreamSizesTest {
    private static final Size[] FRONT = {new Size(3264, 2448), new Size(2560, 1920), new Size(1920, 1440), new Size(1920, 1080),
            new Size(1440, 1080), new Size(1280, 960), new Size(640, 480)};

    /** vivo X200 Pro front camera, owner's log 2026-10-08: the 2560x1920 preview is replaced by a guaranteed 4:3 size. */
    @Test
    public void thePreviewOfTheX200ProFrontCameraGoesToAGuaranteedSize() {
        assertEquals(new Size(1440, 1080), SafeStreamSizes.capped(FRONT, new Size(2560, 1920), SafeStreamSizes.PREVIEW_AREA));
        assertEquals(new Size(1440, 1080), SafeStreamSizes.capped(FRONT, new Size(3264, 2448), SafeStreamSizes.PREVIEW_AREA));
    }

    @Test
    public void anotherAspectOnlyWhenNoneMatchesAndTheRequestWhenNothingFits() {
        Size[] wide = {new Size(1920, 1080), new Size(1280, 720)};
        assertEquals(new Size(1920, 1080), SafeStreamSizes.capped(wide, new Size(2560, 1920), SafeStreamSizes.PREVIEW_AREA));
        Size[] big = {new Size(4000, 3000)};
        assertEquals(new Size(2560, 1920), SafeStreamSizes.capped(big, new Size(2560, 1920), SafeStreamSizes.PREVIEW_AREA));
        assertEquals(new Size(2560, 1920), SafeStreamSizes.capped(null, new Size(2560, 1920), SafeStreamSizes.PREVIEW_AREA));
    }
}
