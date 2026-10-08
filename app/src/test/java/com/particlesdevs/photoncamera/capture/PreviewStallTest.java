package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The preview stall watchdog (black viewfinder on the vivo 5x tele until the module was switched). */
public class PreviewStallTest {
    @Test
    public void aSessionWithoutFramesIsAStallAfterTheLimit() {
        assertFalse(PreviewStall.stalled(10_000 + 2_499, 0, 10_000, 33));
        assertTrue(PreviewStall.stalled(10_000 + 2_500, 0, 10_000, 33));
    }

    @Test
    public void framesThatStopMidSessionAreAStall() {
        assertFalse(PreviewStall.stalled(20_000, 18_000, 10_000, 33));
        assertTrue(PreviewStall.stalled(21_000, 18_000, 10_000, 33));
    }

    @Test
    public void longManualExposuresAreNotAStall() {
        // a 4 s manual exposure: results every 4 s are normal
        assertEquals(13_000, PreviewStall.limitMs(4_000));
        assertFalse(PreviewStall.stalled(30_000, 22_000, 10_000, 4_000));
        assertTrue(PreviewStall.stalled(35_000, 22_000, 10_000, 4_000));
    }

    @Test
    public void noSessionNoStallAndRestartsAreBounded() {
        assertFalse(PreviewStall.stalled(100_000, 0, 0, 33));
        assertTrue(PreviewStall.mayRestart(0));
        assertTrue(PreviewStall.mayRestart(PreviewStall.MAX_RESTARTS - 1));
        assertFalse(PreviewStall.mayRestart(PreviewStall.MAX_RESTARTS));
    }
}
