package com.particlesdevs.photoncamera.settings;

import static com.particlesdevs.photoncamera.settings.ModuleChoice.FACING_BACK;
import static com.particlesdevs.photoncamera.settings.ModuleChoice.FACING_FRONT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ModuleChoiceTest {
    /** OPPO PHY110 on a cold start: camera 1 front, 2 main back, 3 ultra-wide; the teles 4 and 5 are missing. */
    private static Map<String, Integer> oppoColdStart() {
        Map<String, Integer> facing = new LinkedHashMap<>();
        facing.put("1", FACING_FRONT); // HashMap order put the front camera first: the old fallback opened it
        facing.put("2", FACING_BACK);
        facing.put("3", FACING_BACK);
        return facing;
    }

    @Test
    public void backModuleFallsBackToTheMainBackCameraNeverTheFront() {
        int wanted = ModuleChoice.wantedFacing("back3", null);
        assertEquals(FACING_BACK, wanted);
        assertEquals("2", ModuleChoice.fallbackCamera(oppoColdStart(), null, wanted));
    }

    @Test
    public void frontModuleFallsBackToTheFrontCamera() {
        assertEquals("1", ModuleChoice.fallbackCamera(oppoColdStart(), null, ModuleChoice.wantedFacing("front1", null)));
    }

    @Test
    public void noCameraOfTheWantedSideMeansNoFallback() {
        Map<String, Integer> onlyFront = Collections.singletonMap("1", FACING_FRONT);
        assertNull(ModuleChoice.fallbackCamera(onlyFront, null, FACING_BACK));
        assertNull(ModuleChoice.fallbackCamera(new HashMap<>(), null, FACING_BACK));
    }

    @Test
    public void fallbackTakesTheFirstIdNumericallyAndSkipsAuxiliaryStreams() {
        Map<String, Integer> facing = new HashMap<>();
        facing.put("10", FACING_BACK);
        facing.put("6", FACING_BACK);
        facing.put("2", FACING_BACK);
        facing.put("1", FACING_FRONT);
        assertEquals("2", ModuleChoice.fallbackCamera(facing, null, FACING_BACK));
        Set<String> mono = new HashSet<>(Collections.singletonList("2"));
        assertEquals("6", ModuleChoice.fallbackCamera(facing, mono, FACING_BACK));
    }

    @Test
    public void wantedFacingFollowsTheSlotThenTheKnownFacingThenBack() {
        assertEquals(FACING_BACK, ModuleChoice.wantedFacing("back0", FACING_FRONT));
        assertEquals(FACING_FRONT, ModuleChoice.wantedFacing("front0", FACING_BACK));
        assertEquals(FACING_FRONT, ModuleChoice.wantedFacing("4", FACING_FRONT));
        assertEquals(FACING_BACK, ModuleChoice.wantedFacing("4", null));
        assertEquals(FACING_BACK, ModuleChoice.wantedFacing(null, null));
    }

    /** OPPO PHY110 modules: back0 "2x" (camera 2, Quad mode), back1 0.6x, back2 5.9x, back3 2.8x, back4 1x (camera 2). */
    private static final List<String> SLOTS = Arrays.asList("back0", "back1", "back2", "back3", "back4", "front0");
    private static final Map<String, String> CAMERA = new HashMap<>();
    private static final Map<String, Float> ZOOM = new HashMap<>();
    static {
        String[][] rows = {{"back0", "2", "2"}, {"back1", "3", "0.6"}, {"back2", "5", "5.9"}, {"back3", "4", "2.8"},
                {"back4", "2", "1"}, {"front0", "1", "1"}};
        for (String[] r : rows) {
            CAMERA.put(r[0], r[1]);
            ZOOM.put(r[0], Float.parseFloat(r[2]));
        }
    }

    @Test
    public void fallbackModuleIsThePlainOneXModuleOfTheCamera() {
        Set<String> visible = new HashSet<>(SLOTS);
        Map<String, Integer> modes = Collections.singletonMap("back0", 29);
        assertEquals("back4", ModuleChoice.moduleFor(SLOTS, "back", "2", CAMERA, visible, ZOOM, modes));
        assertEquals("back4", ModuleChoice.moduleFor(SLOTS, "back", "2", CAMERA, visible, ZOOM, null));
        visible.remove("back4");
        assertEquals("back0", ModuleChoice.moduleFor(SLOTS, "back", "2", CAMERA, visible, ZOOM, modes));
        assertNull(ModuleChoice.moduleFor(SLOTS, "back", "1", CAMERA, visible, ZOOM, modes));
        assertEquals("front0", ModuleChoice.moduleFor(SLOTS, "front", "1", CAMERA, visible, ZOOM, modes));
    }

    /** Back0 "2x" -> flip to the front (the strip reconciles to front0) -> flip back: back0 again, not back4 "1x". */
    @Test
    public void flippingBackRestoresTheLastBackModule() {
        Map<String, String> store = new HashMap<>();
        for (String selected : new String[]{"back4", "back0", "front0"}) store.put(ModuleChoice.lastKey(selected), selected);
        Set<String> cameraIds = new HashSet<>(Arrays.asList("1", "2", "3", "4", "5"));
        String back = store.get("module_last_back");
        assertEquals("back0", ModuleChoice.flipTarget(back, "back", true, CAMERA.get(back), cameraIds));
        String front = store.get("module_last_front");
        assertEquals("front0", ModuleChoice.flipTarget(front, "front", true, CAMERA.get(front), cameraIds));
    }

    @Test
    public void flipMemoryIsDroppedWhenTheModuleCannotBeUsed() {
        Set<String> coldStartIds = new HashSet<>(Arrays.asList("1", "2", "3"));
        // the 2.8x tele's camera 4 is not listed yet: the side's camera opens and the strip picks a module
        assertNull(ModuleChoice.flipTarget("back3", "back", true, "4", coldStartIds));
        assertNull(ModuleChoice.flipTarget("back0", "back", false, "2", coldStartIds)); // hidden module
        assertNull(ModuleChoice.flipTarget("front0", "back", true, "1", coldStartIds)); // other side
        assertNull(ModuleChoice.flipTarget(null, "back", false, null, coldStartIds));   // nothing remembered
        assertNull(ModuleChoice.lastKey("2"));
        assertEquals("module_last_back", ModuleChoice.lastKey("back0"));
        assertEquals("module_last_front", ModuleChoice.lastKey("front1"));
    }
}
