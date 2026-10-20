package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import com.particlesdevs.photoncamera.processing.color.ColourLook;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P134: the RAW viewfinder takes the photo's white-balance look (ColourLook.whiteMatrix, row-major on linear sRGB) after the
 * camera's colour transform (column-major, as LiveRawRenderer uploads it): the uploaded matrix is the column-major form of
 * white x camera.
 */
public class LiveRawLookTest {
    private static float[] columnMajor(float[] rowMajor) {
        float[] out = new float[9];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) out[c * 3 + r] = rowMajor[r * 3 + c];
        return out;
    }

    @Test
    public void theLookFollowsTheCameraTransform() {
        float[] camera = {1.6f, -0.4f, -0.2f, -0.3f, 1.5f, -0.2f, 0.0f, -0.5f, 1.5f};   // row-major camera -> sRGB
        float[] white = {1.1f, 0.05f, 0.0f, 0.0f, 1.0f, 0.0f, 0.02f, 0.0f, 0.9f};      // row-major, linear sRGB
        float[] got = LiveRawRenderer.lookTransform(white, columnMajor(camera));
        assertArrayEquals(columnMajor(ColourLook.mul(white, camera)), got, 1e-6f);
    }

    /** P297: the first frame reads the look at once (the old Long.MIN_VALUE start overflowed and never did), then twice a second. */
    @Test
    public void theLookIsReadOnTheFirstFrameAndThenTwiceASecond() {
        assertTrue(LiveRawRenderer.lookDue(0L, LiveRawRenderer.LOOK_NEVER));
        assertTrue(LiveRawRenderer.lookDue(123_456_789L, LiveRawRenderer.LOOK_NEVER));
        assertFalse(LiveRawRenderer.lookDue(1_000L, 700L));
        assertTrue(LiveRawRenderer.lookDue(1_200L, 700L));
        assertFalse("the overflow P297 fixed", LiveRawRenderer.lookDue(123_456_789L, Long.MIN_VALUE));
    }

    @Test
    public void anIdentityLookKeepsTheCameraTransform() {
        float[] camera = {1.6f, -0.4f, -0.2f, -0.3f, 1.5f, -0.2f, 0.0f, -0.5f, 1.5f};
        float[] identity = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        assertArrayEquals(columnMajor(camera), LiveRawRenderer.lookTransform(identity, columnMajor(camera)), 0f);
    }
}
