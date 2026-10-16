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

    /** P148: a copy with a lens crop is named after its zoom and keeps the crop; a copy of a copy multiplies the crops. */
    @Test
    public void aCopyWithACropTakesTheCroppedZoomAndKeepsItsCrop() {
        Map<String, Object> p = x7();
        Map<String, Object> w = ModuleDuplicates.copyOf(p, "back1", "back4", "1×", 1f, 1f, 1.3f);
        assertEquals("1.3×", w.get("module_name_back4"));
        assertEquals("1.3", w.get("module_zoom_back4"));
        assertEquals("1.3", w.get("module_crop_back4"));
        assertEquals("2", w.get("module_id_back4"));
        assertEquals("64", w.get("pref_sensorconfig_back4_blackleveloverride"));
        p.putAll(w);
        Map<String, Object> again = ModuleDuplicates.copyOf(p, "back4", "back5", "1.3×", 1.3f, 1.3f, 1.2f);
        assertEquals("1.56", again.get("module_zoom_back5"));
        assertEquals("1.56", again.get("module_crop_back5"));
        Map<String, Object> plainOfCopy = ModuleDuplicates.copyOf(p, "back4", "back5", "1.3×", 1.3f, 1.3f, 1f);
        assertEquals("a plain duplicate of a cropped copy keeps its crop", "1.3", plainOfCopy.get("module_crop_back5"));
        assertEquals("1.3× (2)", plainOfCopy.get("module_name_back5"));
        Map<String, Object> plain = ModuleDuplicates.copyOf(x7(), "back1", "back4", "1×", 1f);
        assertFalse("a plain duplicate has no crop", plain.containsKey("module_crop_back4"));
        assertTrue("the crop goes with a deleted copy", ModuleDuplicates.removalOf(p, "back4").contains("module_crop_back4"));
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

    @Test public void expandedCapacityUsesOnlyTheNinthSlotAndKeepsTheEightOwners() {
        Map<String, Object> p = x7();
        for (int i = 0; i < 8; i++) p.put("module_visible_back" + i, true);
        p.put("module_auto_back8", "5");
        p.put("module_visible_back8", false);
        Map<String, Object> before = new HashMap<>(p);
        assertNull(ModuleDuplicates.freeSlot(p, "back1"));
        assertEquals("back8", ModuleDuplicates.freeSlot(p, "back1", 9));
        p.putAll(ModuleDuplicates.copyOf(p, "back1", "back8", "4×", 4f));
        for (Map.Entry<String, Object> entry : before.entrySet())
            if (!entry.getKey().endsWith("back8")) assertEquals(entry.getValue(), p.get(entry.getKey()));
    }
}
