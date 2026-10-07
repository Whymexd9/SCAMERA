package com.particlesdevs.photoncamera.ui.camera.views;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** P41: the ruler stops for a moment at the stock Xiaomi dial's marks (3.2x, 4.3x, 8.6x, 17.2x). */
public class ZoomDialDetentTest {
    private static final List<Float> STOPS = Arrays.asList(3.225f, 4.3f, 8.6f, 17.2f);
    private static final float HOLD = ZoomDialView.DETENT_HOLD_OCTAVES;

    @Test
    public void aStepAcrossAStopEndsOnIt() {
        float[] held = {0f};
        assertEquals(4.3f, ZoomDialView.detentStep(4.1f, 4.5f, STOPS, held, HOLD), 1e-5f);
        assertEquals("zooming out as well", 8.6f, ZoomDialView.detentStep(9f, 8f, STOPS, held, HOLD), 1e-5f);
        assertEquals("the nearest stop when a step crosses two", 4.3f, ZoomDialView.detentStep(4f, 9f, STOPS, held, HOLD), 1e-5f);
    }

    @Test
    public void theStopHoldsForAShortDragThenLetsGo() {
        float[] held = {0f};
        float z = 4.3f;
        float step = (float) Math.pow(2, HOLD / 3);
        z = ZoomDialView.detentStep(z, z * step, STOPS, held, HOLD);
        assertEquals(4.3f, z, 1e-5f);
        z = ZoomDialView.detentStep(z, z * step, STOPS, held, HOLD);
        assertEquals(4.3f, z, 1e-5f);
        z = ZoomDialView.detentStep(z, z * step * step, STOPS, held, HOLD);
        assertEquals("released", 4.3f * step * step, z, 1e-4f);
        assertEquals(0f, held[0], 0f);
    }

    @Test
    public void noStopsNoChange() {
        float[] held = {0f};
        assertEquals(4.5f, ZoomDialView.detentStep(4.1f, 4.5f, Collections.<Float>emptyList(), held, HOLD), 0f);
        assertEquals(4.5f, ZoomDialView.detentStep(4.1f, 4.5f, null, held, HOLD), 0f);
    }
}
