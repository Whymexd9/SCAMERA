package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class PixelPro10Test {
    @Test
    public void onlyThePixel10ProAndProXl() {
        assertTrue(PixelPro10.device("Google", "mustang"));
        assertTrue(PixelPro10.device("google", "blazer"));
        assertFalse(PixelPro10.device("Google", "panther"));
        assertFalse(PixelPro10.device("samsung", "mustang"));
        assertFalse(PixelPro10.device(null, null));
    }

    @Test
    public void fieldsOfThe10ProXlCameras() {
        // SCAM TEST 2026-10-12: main 2 6.9 mm on 9.792 mm, 5 = its half-width crop, 4 tele 17.906 mm on 5.6448, 6 its crop, 3 / 9 UW
        assertEquals(1f, PixelPro10.field(6.9f, 9.792f, 6.9f, 9.792f), 1e-4f);
        assertEquals(2f, PixelPro10.field(6.9f, 4.896f, 6.9f, 9.792f), 1e-3f);
        assertEquals(4.50f, PixelPro10.field(17.906f, 5.6448f, 6.9f, 9.792f), 0.01f);
        assertEquals(9.00f, PixelPro10.field(17.906f, 2.8224f, 6.9f, 9.792f), 0.02f);
        assertEquals(0.51f, PixelPro10.field(2.02f, 5.6448f, 6.9f, 9.792f), 0.01f);
        assertEquals(1.02f, PixelPro10.field(2.02f, 2.8224f, 6.9f, 9.792f), 0.01f);
    }

    @Test
    public void cropsWithAFieldOfTheirOwnBecomeModules() {
        java.util.List<Float> strip = Arrays.asList(0.51f, 1f, 4.5f);
        assertTrue(PixelPro10.newField(2f, strip));
        assertTrue(PixelPro10.newField(9f, strip));
        assertFalse(PixelPro10.newField(1.02f, strip)); // the UW crop sits on the main's field
        assertFalse(PixelPro10.newField(0f, strip));
    }
}
