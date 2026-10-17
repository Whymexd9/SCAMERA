package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * P211 (owner's X7 Ultra, 2026-10-18: «Тип сессии камеры» 32777 with FULLRES on rebooted the phone - kernel panic): the module's
 * session type reaches the session only as a vendor operation mode Android takes, and never in a FULLRES session.
 */
public class SessionOperationModeTest {
    @Test public void normalAndVendorModesPass() {
        assertEquals(0, CaptureController.sessionOperationMode(0, false));
        assertEquals(32777, CaptureController.sessionOperationMode(32777, false));
        assertEquals(0x8000, CaptureController.sessionOperationMode(0x8000, false));
        assertEquals(0xFFFF, CaptureController.sessionOperationMode(0xFFFF, false));
    }

    @Test public void fullResAlwaysRunsTheNormalMode() {
        assertEquals(0, CaptureController.sessionOperationMode(32777, true));
        assertEquals(0, CaptureController.sessionOperationMode(0x9002, true));
    }

    @Test public void modesAndroidRefusesForOurStreamsFallBackToNormal() {
        assertEquals("constrained high speed", 0, CaptureController.sessionOperationMode(1, false));
        assertEquals(0, CaptureController.sessionOperationMode(2, false));
        assertEquals(0, CaptureController.sessionOperationMode(0x7FFF, false));
        assertEquals(0, CaptureController.sessionOperationMode(-1, false));
        assertEquals(0, CaptureController.sessionOperationMode(0x10000, false));
    }
}
