package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

/** P90: series frames on the viewfinder are brought back to the viewfinder's exposure; viewfinder frames stay as they are. */
public class ViewfinderGainTest {
    @After
    public void reset() {
        ViewfinderGain.resetForTest();
    }

    @Test
    public void seriesFramesAreScaledToTheViewfinderExposure() {
        ViewfinderGain.setReference(225 * 0.010);            // viewfinder ISO 225 at 1/100
        ViewfinderGain.record(1000L, 61 * 0.002305);           // Bento ES x0.063
        ViewfinderGain.record(2000L, 450 * 0.010);            // Shasta x2
        assertEquals(16f, ViewfinderGain.gainFor(1000L), 0f); // x15.99 -> clamped at 16
        assertEquals(0.5f, ViewfinderGain.gainFor(2000L), 1e-6f);
        assertEquals(1f, ViewfinderGain.gainFor(3000L), 0f);  // a viewfinder frame
        assertEquals(1f, ViewfinderGain.gainFor(0L), 0f);
    }

    @Test
    public void withoutAReferenceNothingIsScaled() {
        ViewfinderGain.record(1000L, 0.1);
        assertEquals(1f, ViewfinderGain.gainFor(1000L), 0f);
        assertEquals(1f, ViewfinderGain.gain(0, 1), 0f);
        assertEquals(1f / 16, ViewfinderGain.gain(1, 100), 1e-9f);
    }
}
