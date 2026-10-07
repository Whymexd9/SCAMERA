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
    public void roundingTolerance() {
        assertTrue(ZoomController.inRange(0.58f, 0.6f, 1f));
        assertTrue(ZoomController.inRange(1.04f, 0.6f, 1f));
    }
}
