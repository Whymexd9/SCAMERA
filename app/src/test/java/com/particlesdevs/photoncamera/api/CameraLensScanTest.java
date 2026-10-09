package com.particlesdevs.photoncamera.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData;

import org.junit.Test;

/** P66: the camera scan tells a Pixel's sensor-crop IDs apart (Pixel 7 characteristics dump, 2026-10-09). */
public class CameraLensScanTest {
    private static CameraLensData lens(String id, int facing, float focal, float aperture, float sensorWidth) {
        CameraLensData d = new CameraLensData(id);
        d.setFacing(facing);
        d.setCameraFocalLength(focal);
        d.setCameraAperture(aperture);
        d.setFlashSupported(facing == 1);
        d.setCamera35mmFocalLength(36f / sensorWidth * focal); // as CameraManager2.createNewCameraLensData
        return d;
    }

    @Test
    public void pixelSevenTwoTimesCropIsItsOwnModule() {
        CameraLensData main = lens("0-2", 1, 6.81f, 1.85f, 9.792f), crop = lens("0-4", 1, 6.81f, 1.85f, 4.896f);
        assertTrue("the old rule took camera 4 for camera 2", CameraLensData.sameLens(main, crop, false));
        assertFalse("50 mm is not 25 mm", CameraLensData.sameLens(main, crop, true));
    }

    @Test
    public void pixelSevenFrontsAndRealDuplicates() {
        CameraLensData front1 = lens("0-1", 0, 2.74f, 2.2f, 4.1968f), front6 = lens("0-6", 0, 2.74f, 2.2f, 4.1968f),
                front5 = lens("0-5", 0, 2.74f, 2.2f, 4.6848f);
        assertTrue("camera 6 repeats camera 1", CameraLensData.sameLens(front1, front6, true));
        assertFalse("camera 5 is a wider field", CameraLensData.sameLens(front1, front5, true));
        assertFalse("another lens", CameraLensData.sameLens(front1, lens("0-3", 1, 2.35f, 2.2f, 5.04f), true));
    }
}
