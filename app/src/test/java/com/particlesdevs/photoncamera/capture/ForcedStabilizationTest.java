package com.particlesdevs.photoncamera.capture;

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
}
