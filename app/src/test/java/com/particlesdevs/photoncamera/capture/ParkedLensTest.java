package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.hardware.camera2.CaptureResult;

import org.junit.Test;

/**
 * P252 (OPPO Find X7 Ultra at night, 2026-10-19: CAF, PASSIVE_UNFOCUSED, the lens STATIONARY at 7.14 dpt beyond the declared
 * 5 dpt, 60 ms ISO 3842): the parked lens is taken to the hyperfocal distance after 1.5 s and given back when the scene gets
 * clearly brighter; a touch focus takes it from the fallback.
 */
public class ParkedLensTest {
    private static final int CAF = CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE, OFF = CaptureResult.CONTROL_AF_MODE_OFF,
            AUTO = CaptureResult.CONTROL_AF_MODE_AUTO, STILL = CaptureResult.LENS_STATE_STATIONARY;
    private static final long MS = 1_000_000L;
    private static final double NIGHT = 0.06 * 3842;

    @Test public void theRestStopIsTakenAfterASecondAndAHalf() {
        ParkedLens p = new ParkedLens();
        assertEquals(ParkedLens.NOTHING, p.observe(0, CAF, 7.14f, STILL, 5f, NIGHT));
        assertEquals(ParkedLens.NOTHING, p.observe(1400 * MS, CAF, 7.14f, STILL, 5f, NIGHT));
        assertEquals(ParkedLens.TAKE, p.observe(1600 * MS, CAF, 7.14f, STILL, 5f, NIGHT));
        assertTrue(p.active());
        // the frames still in flight with CAF do not give it back
        assertEquals(ParkedLens.NOTHING, p.observe(1700 * MS, CAF, 7.14f, STILL, 5f, NIGHT));
        assertEquals(ParkedLens.NOTHING, p.observe(1800 * MS, OFF, 0.06f, STILL, 5f, NIGHT));
        // brighter by 8x: back to the HAL's AF
        assertEquals(ParkedLens.RELEASE, p.observe(3000 * MS, OFF, 0.06f, STILL, 5f, NIGHT / 10));
        assertFalse(p.active());
        // and not taken again within the cooldown
        assertEquals(ParkedLens.NOTHING, p.observe(5000 * MS, CAF, 7.14f, STILL, 5f, NIGHT));
        assertEquals(ParkedLens.NOTHING, p.observe(7000 * MS, CAF, 7.14f, STILL, 5f, NIGHT));
        assertEquals(ParkedLens.TAKE, p.observe(8100 * MS, CAF, 7.14f, STILL, 5f, NIGHT));
    }

    @Test public void aLensInRangeOrMovingOrATouchFocusIsLeftAlone() {
        ParkedLens p = new ParkedLens();
        for (long t = 0; t < 5000; t += 100) assertEquals(ParkedLens.NOTHING, p.observe(t * MS, CAF, 4.9f, STILL, 5f, NIGHT));
        for (long t = 0; t < 5000; t += 100)
            assertEquals(ParkedLens.NOTHING, p.observe(t * MS, CAF, 7.14f, CaptureResult.LENS_STATE_MOVING, 5f, NIGHT));
        for (long t = 0; t < 5000; t += 100) assertEquals(ParkedLens.NOTHING, p.observe(t * MS, AUTO, 7.14f, STILL, 5f, NIGHT));
        // taken, then a touch focus (AUTO) after our AF OFF reached the frames: theirs
        ParkedLens q = new ParkedLens();
        q.observe(0, CAF, 7.14f, STILL, 5f, NIGHT);
        assertEquals(ParkedLens.TAKE, q.observe(2000 * MS, CAF, 7.14f, STILL, 5f, NIGHT));
        q.observe(2100 * MS, OFF, 0.06f, STILL, 5f, NIGHT);
        assertEquals(ParkedLens.YIELD, q.observe(2500 * MS, AUTO, 3f, STILL, 5f, NIGHT));
        assertFalse(q.active());
    }

    @Test public void theHyperfocalDistance() {
        // 9 mm f/1.6, 1.6 um pixels: H = 81 / (1.6 x 0.0032) + 9 = 15.83 m -> 0.063 dpt
        assertEquals(0.0632f, ParkedLens.hyperfocalDiopters(9f, 1.6f, 0.0016f, 5f), 0.001f);
        assertEquals(0f, ParkedLens.hyperfocalDiopters(0f, 1.6f, 0.0016f, 5f), 0f);
        assertTrue(ParkedLens.hyperfocalDiopters(9f, 1.6f, 0.0016f, 0.01f) <= 0.01f);
    }
}
