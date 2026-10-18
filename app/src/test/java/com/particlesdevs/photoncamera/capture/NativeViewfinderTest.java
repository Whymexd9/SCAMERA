package com.particlesdevs.photoncamera.capture;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** P212h (A6 = M11): the OPPO native viewfinder's display gain range and the photo matrix's layout for the viewfinder. */
public class NativeViewfinderTest {
    @Test
    public void theGainCoversOnlyTheExposureTheModeCannotDeliver() {
        // daylight: no deficit - held to 1, today's viewfinder
        assertArrayEquals(new float[]{1f, 1f}, NativeViewfinder.displayGain(1, false, 0.0), 0f);
        assertArrayEquals("inside the gate", new float[]{1f, 1f}, NativeViewfinder.displayGain(1, false, 0.3), 0f);
        // night: the cap holds the ISO 2 EV short of the meter - up to 4x, never below 1
        float[] night = NativeViewfinder.displayGain(1, false, 2.0);
        assertEquals(1f, night[0], 0f);
        assertEquals(4f, night[1], 1e-5f);
        assertEquals((float) Math.pow(2, 0.31), NativeViewfinder.displayGain(1, false, 0.31)[1], 1e-5f);
        // a hand-set exposure (ISO and shutter) keeps gain 1, as before; mode 0 none
        assertNull(NativeViewfinder.displayGain(1, true, 2.0));
        assertNull(NativeViewfinder.displayGain(0, false, 2.0));
        // mode 2 (the verifier's variant): the meter's own law, unbounded here (LiveRawMeter's 0.1-5 stays)
        float[] free = NativeViewfinder.displayGain(2, false, 0.0);
        assertEquals(0f, free[0], 0f);
        assertTrue(Float.isInfinite(free[1]));
        assertNull(NativeViewfinder.displayGain(2, true, 0.0));
    }

    @Test
    public void theRowMajorPhotoMatrixGoesColumnMajorAsTheResultTransformIsUploaded() {
        float[] rowMajor = {1.6f, -0.6f, 0f, -0.2f, 1.4f, -0.2f, 0.04f, -0.6f, 1.56f};
        float[] col = NativeViewfinder.columnMajor(rowMajor);
        // the viewfinder fills ccm[col * 3 + row] = getElement(col, row) = M[row][col]
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) assertEquals(rowMajor[r * 3 + c], col[c * 3 + r], 0f);
        assertArrayEquals(rowMajor, NativeViewfinder.columnMajor(col), 0f);
    }

    @Test
    public void theMatrixIsRecomputedOnlyWhenTheNeutralMoves() {
        assertFalse(NativeViewfinder.moved(-0.5f, -0.4f, -0.5f, -0.4f));
        assertFalse(NativeViewfinder.moved(-0.5f, -0.4f, -0.505f, -0.395f));
        assertTrue(NativeViewfinder.moved(-0.5f, -0.4f, -0.52f, -0.4f));
        assertTrue(NativeViewfinder.moved(-0.5f, -0.4f, -0.5f, -0.38f));
        assertTrue("nothing yet", NativeViewfinder.moved(Float.NaN, Float.NaN, -0.5f, -0.4f));
    }
}
