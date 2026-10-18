package com.particlesdevs.photoncamera.ui.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * P214h: of the tele's two FULLRES buttons the one that lights while FULLRES is on - the chosen size, or the other when the
 * chosen one has no row left (the camera takes its other size then), none when both are off (both dimmed).
 */
public class FullResButtonTest {
    @Test
    public void theSizeThatRunsLights() {
        assertTrue("200 MP chosen", FullResButton.lights200(true, false, false));
        assertFalse("50 MP chosen", FullResButton.lights200(false, false, false));
        assertFalse("200 MP chosen but off: the 50 MP runs", FullResButton.lights200(true, false, true));
        assertTrue("50 MP chosen but off: the 200 MP runs", FullResButton.lights200(false, true, false));
        assertFalse("both off", FullResButton.lights200(true, true, true));
        assertFalse("both off", FullResButton.lights200(false, true, true));
    }
}
