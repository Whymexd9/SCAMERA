package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModuleDuplicatesTest {
    /** X7 Ultra: four rear lenses shown (back0..3), back4..7 filler slots repeating the last camera, hidden. */
    private static Map<String, Object> x7() {
        Map<String, Object> p = new HashMap<>();
        String[] cams = {"3", "2", "4", "5"};
        for (int i = 0; i < 8; i++) {
            p.put("module_auto_back" + i, cams[Math.min(i, 3)]);
            p.put("module_visible_back" + i, i < 4);
            p.put("module_label_back" + i, i < 4 ? new String[]{"0.6×", "1×", "2.8×", "5.9×"}[i] : "5.9×");
        }
        p.put("pref_sensorconfig_back1_tunablekeys", "[{\"name\":\"com.oplus.engineercamera.agingtest.mode.select\",\"value\":\"29\"}]");
        p.put("pref_sensorconfig_back1_blackleveloverride", "64");
        p.put("pref_sensorconfig_back2_blackleveloverride", "63");
        return p;
    }

    @Test
    public void aDuplicateTakesAFillerSlotAndCopiesCameraZoomAndRequests() {
        Map<String, Object> p = x7();
        String target = ModuleDuplicates.freeSlot(p, "back1");
        assertEquals("first hidden filler of the rear side", "back4", target);
        Map<String, Object> w = ModuleDuplicates.copyOf(p, "back1", target, "1×", 1f);
        assertEquals("2", w.get("module_id_back4"));
        assertEquals("1× (2)", w.get("module_name_back4"));
        assertEquals("1", w.get("module_zoom_back4"));
        assertEquals(Boolean.TRUE, w.get("module_visible_back4"));
        assertTrue(String.valueOf(w.get("pref_sensorconfig_back4_tunablekeys")).contains("mode.select"));
        assertEquals("64", w.get("pref_sensorconfig_back4_blackleveloverride"));
        assertFalse("another module's sensor settings stay", w.containsKey("pref_sensorconfig_back4_blackleveloverride_back2"));
        assertEquals("back1", w.get(ModuleDuplicates.MARK + "back4"));
    }

    @Test
    public void onlyDuplicatesAreDeletedAndTheSlotBecomesAFillerAgain() {
        Map<String, Object> p = x7();
        p.putAll(ModuleDuplicates.copyOf(p, "back1", "back4", "1×", 1f));
        assertEquals("back1", ModuleDuplicates.sourceOf(p, "back4"));
        assertNull("an original has no source", ModuleDuplicates.sourceOf(p, "back1"));
        List<String> gone = ModuleDuplicates.removalOf(p, "back4");
        assertTrue(gone.contains("module_id_back4") && gone.contains("module_name_back4") && gone.contains("module_zoom_back4"));
        assertTrue(gone.contains("pref_sensorconfig_back4_tunablekeys"));
        assertTrue(gone.contains(ModuleDuplicates.MARK + "back4"));
        assertFalse("the automatic camera of the slot stays", gone.contains("module_auto_back4"));
        assertFalse(gone.contains("pref_sensorconfig_back1_tunablekeys"));
    }

    @Test
    public void noFreeSlotWhenAllEightAreShown() {
        Map<String, Object> p = x7();
        for (int i = 0; i < 8; i++) p.put("module_visible_back" + i, true);
        assertNull(ModuleDuplicates.freeSlot(p, "back0"));
        assertEquals("the front side has its own slots", null, ModuleDuplicates.freeSlot(p, "front0"));
    }
}
