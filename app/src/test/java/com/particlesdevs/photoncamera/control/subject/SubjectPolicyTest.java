package com.particlesdevs.photoncamera.control.subject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P42: manual values win, primary face choice with hysteresis, region update gate. */
public class SubjectPolicyTest {
    @Test
    public void manualFocusAndExposureWin() {
        assertTrue(SubjectPolicy.afAllowed(4, 1, true));       // CONTINUOUS_PICTURE
        assertTrue(SubjectPolicy.afAllowed(1, 1, true));       // AUTO
        assertTrue(SubjectPolicy.afAllowed(null, 1, true));
        assertFalse("manual focus (AF_MODE_OFF)", SubjectPolicy.afAllowed(0, 1, true));
        assertFalse("no AF regions on this module", SubjectPolicy.afAllowed(4, 0, true));
        assertFalse("fixed-focus lens", SubjectPolicy.afAllowed(4, 1, false));
        assertTrue(SubjectPolicy.aeAllowed(1, 1));
        assertFalse("manual ISO / shutter (AE_MODE_OFF)", SubjectPolicy.aeAllowed(0, 1));
        assertFalse(SubjectPolicy.aeAllowed(1, 0));
    }

    @Test
    public void trackingPreference() {
        assertEquals("tap", SubjectPolicy.normalizeTracking("tap"));
        assertEquals("off", SubjectPolicy.normalizeTracking("off"));
        assertEquals(SubjectPolicy.TRACK_DEFAULT, SubjectPolicy.normalizeTracking(null));
        assertEquals(SubjectPolicy.TRACK_DEFAULT, SubjectPolicy.normalizeTracking("x"));
    }

    @Test
    public void primaryIsTheBigCentralConfidentFace() {
        float[] rects = {
                0.05f, 0.05f, 0.15f, 0.15f,   // small, corner
                0.35f, 0.35f, 0.65f, 0.65f,   // big, central
                0.70f, 0.40f, 0.80f, 0.50f};  // small, side
        int[] scores = {90, 90, 90}; // similar confidence: size and centre decide
        assertEquals(1, SubjectPolicy.pickPrimary(rects, scores, 3, -1, -1));
    }

    @Test
    public void tinyOrZeroScoreFacesAreIgnored() {
        float[] rects = {0.4f, 0.4f, 0.41f, 0.41f, 0.1f, 0.1f, 0.3f, 0.3f};
        int[] scores = {100, 0};
        assertEquals(-1, SubjectPolicy.pickPrimary(rects, scores, 2, -1, -1));
    }

    @Test
    public void primaryHasHysteresis() {
        float[] rects = {
                0.10f, 0.40f, 0.30f, 0.60f,   // the current primary (left)
                0.60f, 0.40f, 0.82f, 0.62f};  // a slightly better face appears
        int[] scores = {80, 85};
        assertEquals("first choice", 1, SubjectPolicy.pickPrimary(rects, scores, 2, -1, -1));
        assertEquals("kept while only slightly worse", 0, SubjectPolicy.pickPrimary(rects, scores, 2, 0.2f, 0.5f));
        // A much better face takes over.
        float[] bigger = {0.10f, 0.40f, 0.30f, 0.60f, 0.30f, 0.20f, 0.80f, 0.80f};
        assertEquals(1, SubjectPolicy.pickPrimary(bigger, scores, 2, 0.2f, 0.5f));
    }

    @Test
    public void regionGate() {
        int[] last = {1000, 1000, 400, 400};
        int[] none = {0, 0, 0, 0};
        int[] same = {1010, 1005, 400, 400};
        int[] moved = {1300, 1000, 400, 400};
        int[] grown = {900, 900, 600, 600};
        assertTrue("first write", SubjectPolicy.regionChanged(same, none, 4000, 0, 0, 250, 0.04f, 0.3f));
        assertFalse("jitter", SubjectPolicy.regionChanged(same, last, 4000, 1000, 0, 250, 0.04f, 0.3f));
        assertTrue("moved 7.5 %", SubjectPolicy.regionChanged(moved, last, 4000, 1000, 0, 250, 0.04f, 0.3f));
        assertFalse("too soon", SubjectPolicy.regionChanged(moved, last, 4000, 1100, 1000, 250, 0.04f, 0.3f));
        assertTrue("size +125 %", SubjectPolicy.regionChanged(grown, last, 4000, 1000, 0, 250, 0.04f, 0.3f));
    }
}
