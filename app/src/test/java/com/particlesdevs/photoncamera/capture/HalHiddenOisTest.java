package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The OPPO Find X Ultras have OIS on every module although Camera2 lists OFF only (owner, SCAM TEST reports 2026-10-11). */
public class HalHiddenOisTest {
    @Test
    public void oppoFindXUltrasHaveOis() {
        assertTrue(CaptureController.halHiddenOis("OPPO", "PHY110"));
        assertTrue(CaptureController.halHiddenOis("OPPO", "PKJ110"));
        assertTrue(CaptureController.halHiddenOis("oppo", "cph2841"));
        assertTrue(CaptureController.halHiddenOis("OnePlus", "CPH2747"));
    }

    @Test
    public void otherPhonesFollowCamera2() {
        assertFalse(CaptureController.halHiddenOis("Xiaomi", "2201116TG"));
        assertFalse(CaptureController.halHiddenOis("OPPO", "CPH2747"));
        assertFalse(CaptureController.halHiddenOis("OPPO", "PLG110")); // Find X9 Pro: OFF in the templates as well
        assertFalse(CaptureController.halHiddenOis("vivo", "V2454A"));
        assertFalse(CaptureController.halHiddenOis("OPPO", null));
    }
}
