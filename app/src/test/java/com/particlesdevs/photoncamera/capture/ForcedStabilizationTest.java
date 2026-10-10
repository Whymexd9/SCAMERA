package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P54b: the forced stabilisation is for the vivo X300 Ultra only by default, and it skips the shot's HAL flush. */
public class ForcedStabilizationTest {
    @Test
    public void onlyTheX300UltraByDefault() {
        assertTrue(ForcedStabilization.phone("vivo", "V2562", "V2562"));
        assertTrue(ForcedStabilization.phone("VIVO", "v2562", null));
        assertTrue(ForcedStabilization.phone("vivo", "PD2562X", "V2562"));
        assertFalse(ForcedStabilization.phone("vivo", "PD2454", "V2502A"));
        assertFalse(ForcedStabilization.phone("OPPO", "V2562", "V2562"));
        assertFalse(ForcedStabilization.phone(null, null, null));
    }

    @Test
    public void forcedStabilisationSkipsTheFlush() {
        assertTrue(ForcedStabilization.flush(true, false));
        assertFalse(ForcedStabilization.flush(true, true));
        assertFalse(ForcedStabilization.flush(false, false));
        assertFalse(ForcedStabilization.flush(false, true));
    }

    /** P54c: the AE restore frame leaves the viewfinder only on the forced phone, with the RAW stream in the repeating request. */
    @Test
    public void aeRestoreFrameSkipsTheViewfinderOnlyWhenSafe() {
        assertTrue(ForcedStabilization.dropViewfinder(true, true, true, false));
        assertFalse(ForcedStabilization.dropViewfinder(false, true, true, false));
        assertFalse(ForcedStabilization.dropViewfinder(true, false, true, false));
        // Without the RAW stream the frame would have no target left.
        assertFalse(ForcedStabilization.dropViewfinder(true, true, false, false));
        assertFalse(ForcedStabilization.dropViewfinder(true, true, true, true));
    }

    /** Test that OIS is detected correctly for both Qualcomm [0, 1] and MediaTek/Vivo [1] HALs. */
    @Test
    public void oisSupportDetectionHandlesVariousHalModes() {
        assertTrue(CaptureController.isOisSupported(new int[]{1}));
        assertTrue(CaptureController.isOisSupported(new int[]{0, 1}));
        assertFalse(CaptureController.isOisSupported(new int[]{0}));
        assertFalse(CaptureController.isOisSupported(new int[0]));
        assertFalse(CaptureController.isOisSupported((int[]) null));
    }

    /**
     * P54d's photo-mode stabilisation is for vivo only: on the Xiaomi 13 Ultra and the OPPO Find X9 Ultra (2026-10-10) a photo
     * session with it (and its session parameters) stalled or killed the HAL. Video keeps ON everywhere. (Build.BRAND is not
     * vivo in the unit tests.)
     */
    @Test
    public void photoPreviewStabilisationOnlyOnVivo() {
        android.hardware.camera2.CameraCharacteristics chars = org.mockito.Mockito.mock(android.hardware.camera2.CameraCharacteristics.class);
        org.mockito.Mockito.when(chars.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES))
                .thenReturn(new int[]{0, 1, 2});
        assertFalse(CaptureController.vivoBrand());
        assertEquals(0, CaptureController.getPreferredVideoStabilizationMode(chars, false, true));
        assertEquals(1, CaptureController.getPreferredVideoStabilizationMode(chars, true, true));
        assertEquals(0, CaptureController.getPreferredVideoStabilizationMode(chars, true, false));
    }

    /** The RAW viewfinder's dynamic black: a drift is taken, another scale (16 for 64, Realme GT8 Pro) is not. */
    @Test
    public void dynamicBlackOnlyNearTheStaticOne() {
        float[] fixed = {64, 64, 64, 64};
        assertTrue(CaptureController.plausibleDynamicBlack(new float[]{60, 61, 60, 62}, fixed));
        assertFalse(CaptureController.plausibleDynamicBlack(new float[]{16, 16, 16, 16}, fixed));
        assertTrue(CaptureController.plausibleDynamicBlack(new float[]{16, 16, 16, 16}, new float[]{0, 0, 0, 0}));
    }
}
