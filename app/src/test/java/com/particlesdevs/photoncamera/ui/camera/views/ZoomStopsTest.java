package com.particlesdevs.photoncamera.ui.camera.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** P343: the zoom strip shows one button per label. */
public class ZoomStopsTest {
    private static final Map<String, String> LABEL = new HashMap<>();
    private static final Map<String, Integer> RANK = new HashMap<>();
    static {
        // the owner's X200 Ultra strip: 0,4 | 0,8 | 0,8 | 1 | 2 | 2 | 2,4 | 4,8 | 9,6
        put("uw", "0.4×", 1); put("uwIsz", "0,8×", 2); put("uwCopy", "0.8x", 0); put("main", "1×", 1);
        put("mainIsz", "2×", 2); put("mainCrop", "2", 0); put("tele", "2.4×", 1); put("teleIsz", "4,8×", 2); put("tele4x", "9.6×", 2);
    }
    private static void put(String s, String l, int r) { LABEL.put(s, l); RANK.put(s, r); }
    private static final List<String> STRIP = Arrays.asList("uw", "uwIsz", "uwCopy", "main", "mainIsz", "mainCrop", "tele", "teleIsz", "tele4x");

    private static List<String> unique(List<String> strip, String active) {
        return ZoomStops.unique(strip, LABEL::get, RANK::get, active);
    }

    @Test public void oneButtonPerLabelTheBestRoute() {
        assertEquals(Arrays.asList("uw", "uwIsz", "main", "mainIsz", "tele", "teleIsz", "tele4x"), unique(STRIP, "main"));
    }

    @Test public void theActiveModuleKeepsItsStop() {
        assertEquals(Arrays.asList("uw", "uwCopy", "main", "mainIsz", "tele", "teleIsz", "tele4x"), unique(STRIP, "uwCopy"));
        assertEquals(Arrays.asList("uw", "uwIsz", "main", "mainCrop", "tele", "teleIsz", "tele4x"), unique(STRIP, "mainCrop"));
    }

    @Test public void modulesTheOwnerNamedStaySeparate() {
        put("night", "Ночь", 1); put("portrait", "Портрет 2×", 0); put("max", "Max", 1);
        List<String> strip = Arrays.asList("main", "night", "mainIsz", "portrait", "max");
        assertEquals(strip, unique(strip, "main"));
    }

    @Test public void labelKeys() {
        assertEquals(ZoomStops.key("0,8×"), ZoomStops.key("0.8x"));
        assertEquals(ZoomStops.key(" 2 × "), ZoomStops.key("2"));
        assertNotEquals(ZoomStops.key("2.4×"), ZoomStops.key("2×"));
        assertEquals("max", ZoomStops.key("Max"));
    }
}
