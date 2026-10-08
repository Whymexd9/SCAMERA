package com.particlesdevs.photoncamera.control;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * A module whose vendor request puts the sensor into a full-resolution crop (ISZ) must not be cropped a second time (OPPO
 * Find X9 Ultra tele, 2x ISZ vendor tag on a 6x module of the 3x camera: the photo came out 2048x1536 instead of 4096x3072).
 */
public class ZoomStreamCropTest {
    @Test
    public void aVendorRequestedMosaicIsACropOfItsBlock() {
        assertEquals(2f, ZoomController.streamCropFor(2, true, false, null, false), 0f);
        assertEquals(4f, ZoomController.streamCropFor(4, true, false, null, false), 0f);
        // 6x module on the 3x camera, stream crop 2: nothing left to crop
        assertEquals(1f, ZoomController.effective(2f, 2f), 0f);
        // 10x on the same module: the remaining 10 / 6 is still cropped
        assertEquals(10f / 6f, ZoomController.effective(10f / 3f, 2f), 1e-5f);
    }

    @Test
    public void noCropWithoutEvidenceOrWhenTheModuleAlreadyAccountsForIt() {
        assertEquals(1f, ZoomController.streamCropFor(1, true, false, null, false), 0f);   // Bayer stream
        assertEquals(1f, ZoomController.streamCropFor(0, true, false, null, false), 0f);   // not measured yet
        assertEquals(1f, ZoomController.streamCropFor(2, false, false, null, false), 0f);  // no vendor request: a Quad sensor's own stream
        assertEquals(1f, ZoomController.streamCropFor(2, true, true, null, false), 0f);    // declared sensor crop (vivo forceSensorMode)
        assertEquals(1f, ZoomController.streamCropFor(2, true, false, Boolean.TRUE, false), 0f);
        assertEquals(1f, ZoomController.streamCropFor(2, true, false, Boolean.FALSE, false), 0f); // the owner says: no crop
        assertEquals(1f, ZoomController.streamCropFor(2, true, false, null, true), 0f);     // maximum-resolution (remosaic) stream
        assertEquals(1.5f, ZoomController.effective(1.5f, 1f), 0f);  // no stream crop: the residual as before
        assertEquals(1f, ZoomController.effective(0.5f, 1f), 0f);
    }
}
