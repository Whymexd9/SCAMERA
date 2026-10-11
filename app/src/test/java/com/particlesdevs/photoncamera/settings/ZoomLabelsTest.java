package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P100c: Galaxy S24 Ultra lens ratios (metadata: 13.6 / 22.3 / 61.0 / 112.7 mm) get the stock names; other brands keep theirs. */
public class ZoomLabelsTest {
    @Test
    public void samsungStockSteps() {
        assertEquals("0.6×", ZoomLabels.label(13.6f / 22.3f, "samsung"));
        assertEquals("1×", ZoomLabels.label(1f, "samsung"));
        assertEquals("3×", ZoomLabels.label(61.0f / 22.3f, "samsung"));
        assertEquals("5×", ZoomLabels.label(112.7f / 22.3f, "samsung"));
        assertEquals(3f, ZoomLabels.nominal(2.735f, "Samsung"), 1e-6);
        assertEquals(4.1f, ZoomLabels.nominal(4.1f, "samsung"), 1e-6); // no step close enough
    }

    @Test
    public void otherBrandsKeepTheMeasuredRatio() {
        assertEquals("2.7×", ZoomLabels.label(2.735f, "vivo"));
        assertEquals("3.7×", ZoomLabels.label(3.7f, "vivo"));
        assertEquals("2.8×", ZoomLabels.label(2.8f, "OPPO"));
        assertFalse(ZoomLabels.snaps("Xiaomi"));
        assertFalse(ZoomLabels.snaps(null));
        assertTrue(ZoomLabels.snaps("SAMSUNG"));
    }
}
