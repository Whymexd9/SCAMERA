package com.particlesdevs.photoncamera.gallery.compare;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The compare panes follow each other through the photos (owner, 2026-10-13: «синхронный сдвиг фото с учётом разного
 * зума», «относительный зум не зависящий от разрешения»). */
public class PaneLinkTest {
    private static final float EPS = 1e-3f;

    /** A 12 MP and a 50 MP photo of one scene, both at fit in equal panes (1000 px wide). */
    @Test public void differentResolutionsKeepTheRelativeZoomAndThePlace() {
        float fit1 = 1000f / 4000f, fit2 = 1000f / 8160f;
        PaneLink link = PaneLink.asShown(fit1, 2000, 1500, fit2, 4080, 3060);
        // pane 1 zoomed to 3x its fit: pane 2 at 3x its own fit, not at pane 1's scale
        assertEquals(3f, link.scale2(3 * fit1) / fit2, EPS);
        // a place at 3/4 of photo 1 is at 3/4 of photo 2
        assertEquals(0.75f * 8160, link.x2(0.75f * 4000), 0.5f);
        assertEquals(0.25f * 6120, link.y2(0.25f * 3000), 0.5f);
        // and back
        assertEquals(0.75f * 4000, link.x1(link.x2(0.75f * 4000)), EPS);
        assertEquals(3 * fit1, link.scale1(link.scale2(3 * fit1)), EPS);
    }

    /** A 2x photo next to a 1x one (same resolution): the same scene point stays in the middle of both. */
    @Test public void aTwiceZoomedPhotoPansThroughItsOwnZoom() {
        float d = (float) Math.hypot(4000, 3000);
        PaneLink link = PaneLink.byFieldOfView(d, 2000, 1500, 23, d, 2000, 1500, 46);
        assertEquals(2f, link.a, EPS);
        // pane 1 pans 100 source px right of the centre: pane 2 moves 200 of its own px (the same scene point)
        assertEquals(2200f, link.x2(2100), EPS);
        // the scene as large on screen in both: pane 2's scale is half of pane 1's
        assertEquals(0.5f, link.scale2(1f), EPS);
    }

    /** A pane magnified while the switch was off: switched on, the panes keep that zoom ratio and offset. */
    @Test public void aZoomAndPlaceSetWhileUnlinkedAreKept() {
        // pane 1 at 2x and off-centre, pane 2 at fit, the same detail in the middle of both
        PaneLink link = PaneLink.asShown(0.5f, 1200, 900, 0.25f, 2000, 1500);
        // pane 2 pinched to 2x: pane 1 to 4x (the ratio stays 2)
        assertEquals(1.0f, link.scale1(0.5f), EPS);
        // a pan of pane 2 by 400 screen px (1600 source px at scale 0.25): pane 1 moves 400 screen px too (800 at 0.5)
        assertEquals(1200f + 800f, link.x1(2000 + 1600), EPS);
    }
}
