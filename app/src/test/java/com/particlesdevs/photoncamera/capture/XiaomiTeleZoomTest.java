package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class XiaomiTeleZoomTest {
    private static final float E = 1e-4f;

    @Test
    public void ownersValuesAt100mmAndTheOpticalEnds() {
        XiaomiTeleZoom.Plan p = XiaomiTeleZoom.plan(100f, false);
        assertEquals(4.30000019f, p.userZoom, E);
        assertEquals(1.34375f, p.zoomRatio, E);
        assertEquals(1f, p.residual, E);
        assertFalse(p.isz);
        XiaomiTeleZoom.Plan w = XiaomiTeleZoom.plan(75f, false);
        assertEquals(75f / (100f / 4.30000019f), w.userZoom, E);
        assertEquals(1.0078f, w.zoomRatio, 1e-3f);
        assertEquals("below the tele's widest: held at 75 mm", 75f, XiaomiTeleZoom.plan(60f, false).mm, E);
    }

    @Test
    public void between100And150TheOpticsStayAt100AndTheFrameIsCropped() {
        XiaomiTeleZoom.Plan p = XiaomiTeleZoom.plan(125f, false);
        assertEquals(4.30000019f, p.userZoom, E);
        assertEquals(1.25f, p.residual, E);
        assertEquals(1.34375f * 1.25f, p.zoomRatio, E);
        assertFalse(p.isz);
    }

    @Test
    public void iszFrom150WithItsOwnSmoothOpticalZoomAndHysteresis() {
        XiaomiTeleZoom.Plan on = XiaomiTeleZoom.plan(150f, false);
        assertTrue(on.isz);
        assertEquals("150 mm ISZ = the 75 mm optics x2", 75f / (100f / 4.30000019f), on.userZoom, E);
        assertEquals(1f, on.residual, E);
        XiaomiTeleZoom.Plan top = XiaomiTeleZoom.plan(200f, true);
        assertEquals(4.30000019f, top.userZoom, E);
        assertEquals(1f, top.residual, E);
        assertEquals(1.2f, XiaomiTeleZoom.plan(240f, true).residual, E);
        assertFalse("not yet on below 150", XiaomiTeleZoom.plan(148f, false).isz);
        assertTrue("stays on down to 145", XiaomiTeleZoom.plan(147f, true).isz);
        assertFalse("off below 145", XiaomiTeleZoom.plan(144f, true).isz);
    }

    @Test
    public void theFieldOfViewDoesNotJumpWhereIszTurnsOn() {
        XiaomiTeleZoom.Plan below = XiaomiTeleZoom.plan(149.9f, false), at = XiaomiTeleZoom.plan(150f, false);
        // equivalent focal length on the output: optical position x ISZ factor x crop
        float fBelow = Math.min(below.mm, 100f) * below.residual;
        float fAt = 2f * at.userZoom * (100f / 4.30000019f) * at.residual;
        assertEquals(fBelow, fAt, 0.2f);
    }
}
