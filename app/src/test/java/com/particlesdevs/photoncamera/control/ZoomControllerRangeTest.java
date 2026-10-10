package com.particlesdevs.photoncamera.control;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P37: the zoom that belongs to the active module (the ruler starts from the module, not from 1x). */
public class ZoomControllerRangeTest {
    @Test
    public void teleModuleRange() {
        // A 2.8x module with a 5.9x module above it.
        assertTrue(ZoomController.inRange(2.8f, 2.8f, 5.9f));
        assertTrue(ZoomController.inRange(4.5f, 2.8f, 5.9f));
        assertFalse("1x on a tele is out of range: the ruler goes to 2.8x", ZoomController.inRange(1f, 2.8f, 5.9f));
        assertFalse(ZoomController.inRange(7f, 2.8f, 5.9f));
    }

    @Test
    public void topModuleHasNoUpperLimit() {
        assertTrue(ZoomController.inRange(20f, 5.9f, Float.MAX_VALUE));
        assertFalse(ZoomController.inRange(2.8f, 5.9f, Float.MAX_VALUE));
    }

    @Test
    public void moduleChangedFromOutsideTakesItsOwnRatio() {
        // Zoom 1x set for the main camera, the UW module (0.6x, main above it) became active: 1x is "in range" by the
        // rounding, but the zoom and residual belong to the main camera.
        assertTrue(ZoomController.needsSync("back_uw", "back_main", 1f, 0.6f, 1f));
        assertTrue("no module recorded yet", ZoomController.needsSync("back_tele", null, 2.8f, 2.8f, 5.9f));
        assertFalse("same module, zoom inside its range", ZoomController.needsSync("back_tele", "back_tele", 4f, 2.8f, 5.9f));
        assertTrue("same module, zoom below its ratio", ZoomController.needsSync("back_tele", "back_tele", 1f, 2.8f, 5.9f));
    }

    @Test
    public void roundingTolerance() {
        assertTrue(ZoomController.inRange(0.58f, 0.6f, 1f));
        assertTrue(ZoomController.inRange(1.04f, 0.6f, 1f));
    }

    /** Xiaomi 17 Ultra (2026-10-10): a duplicate module of the same ratio is not picked over the original or the active one. */
    @Test
    public void sameRatioPrefersTheActiveThenTheOriginal() {
        java.util.List<String> slots = java.util.Arrays.asList("back2", "back1", "back0", "back3");
        float[] ratios = {0.8f, 1f, 3.1f, 3.1f};
        boolean[] copies = {false, false, false, true};
        org.junit.Assert.assertEquals("back0", ZoomController.pick(slots, ratios, copies, 3.3f, "back1"));
        org.junit.Assert.assertEquals("back3", ZoomController.pick(slots, ratios, copies, 3.3f, "back3"));
        org.junit.Assert.assertEquals("back1", ZoomController.pick(slots, ratios, copies, 2f, "back0"));
        org.junit.Assert.assertEquals("back2", ZoomController.pick(slots, ratios, copies, 0.5f, "back0"));
    }

    /**
     * The longer module takes over 3 % past its ratio; the wider one at once below the active ratio (a module cannot show less
     * than its own ratio): no back-and-forth at the border and no snap back to the tele's 3.225x (owner's video 2026-10-10).
     */
    @Test
    public void upSwitchWaitsPastTheTargetDownSwitchDoesNot() {
        org.junit.Assert.assertTrue(ZoomController.holdsActive(3.25f, 1f, 3.225f));   // main crops on to 3.32x
        org.junit.Assert.assertTrue(ZoomController.holdsActive(3.3f, 1f, 3.225f));
        org.junit.Assert.assertFalse(ZoomController.holdsActive(3.33f, 1f, 3.225f));  // then the tele
        org.junit.Assert.assertFalse(ZoomController.holdsActive(3.16f, 3.225f, 1f));  // below the tele: the main camera at once
        org.junit.Assert.assertFalse(ZoomController.holdsActive(0.9f, 1f, 0.6f));
    }
}
