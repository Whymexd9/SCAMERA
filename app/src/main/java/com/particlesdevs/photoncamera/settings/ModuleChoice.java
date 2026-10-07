package com.particlesdevs.photoncamera.settings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which camera and which module (slot) to use when the requested one cannot be used: the requested Camera ID is missing at
 * start, or the camera is flipped to the other side. Plain Java (no Android types), so the choices are unit tested.
 */
public final class ModuleChoice {
    /** CameraCharacteristics.LENS_FACING_FRONT. */
    public static final int FACING_FRONT = 0;
    /** CameraCharacteristics.LENS_FACING_BACK. */
    public static final int FACING_BACK = 1;

    private ModuleChoice() {}

    /** "back" / "front" for a module slot ("back3", "front0"), null for anything else (a bare Camera ID). */
    public static String side(String slot) {
        if (slot == null) return null;
        if (slot.startsWith("front")) return "front";
        if (slot.startsWith("back")) return "back";
        return null;
    }

    public static String side(int facing) {
        return facing == FACING_FRONT ? "front" : "back";
    }

    /**
     * Facing the requested module needs: its slot's side; else the facing known for the requested camera; else back (the
     * default camera of the app). A back module never asks for the front camera.
     */
    public static int wantedFacing(String activeSlot, Integer knownFacing) {
        String side = side(activeSlot);
        if ("front".equals(side)) return FACING_FRONT;
        if ("back".equals(side)) return FACING_BACK;
        if (knownFacing != null && knownFacing == FACING_FRONT) return FACING_FRONT;
        return FACING_BACK;
    }

    /** Camera IDs in numeric order ("2" before "10"), non-numeric IDs after them in text order. */
    static final Comparator<String> ID_ORDER = (a, b) -> {
        Long x = number(a), y = number(b);
        if (x != null && y != null) return Long.compare(x, y);
        if (x != null) return -1;
        if (y != null) return 1;
        return a.compareTo(b);
    };

    private static Long number(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Camera to open in place of a requested camera that has no characteristics (OPPO PHY110 cold start: the ID list is
     * [1, 2, 3], the selected 2.8x tele is camera 4): the main camera of the wanted side, that is the first camera of that
     * facing in ID order (the one CameraManager2 takes as 1x), skipping MONO / NIR auxiliary streams. Never a camera of the
     * other side; null when the wanted side has none.
     *
     * @param facingById LENS_FACING of every camera with characteristics
     * @param auxiliary  cameras that are auxiliary streams (may be null)
     */
    public static String fallbackCamera(Map<String, Integer> facingById, Set<String> auxiliary, int wantedFacing) {
        List<String> ids = new ArrayList<>(facingById.keySet());
        ids.sort(ID_ORDER);
        for (String id : ids) {
            Integer facing = facingById.get(id);
            if (facing != null && facing == wantedFacing && (auxiliary == null || !auxiliary.contains(id))) return id;
        }
        return null;
    }

    /**
     * Module that stands for {@code cameraId} on {@code side}: a visible slot of that side on that camera, preferring one
     * without a forced vendor sensor mode, then the zoom ratio nearest 1x, then slot order. Null when no visible slot uses
     * the camera.
     */
    public static String moduleFor(List<String> slots, String side, String cameraId, Map<String, String> cameraOf,
                                   Set<String> visible, Map<String, Float> zoom, Map<String, Integer> sensorMode) {
        String best = null;
        for (String slot : slots) {
            if (!side.equals(side(slot)) || !visible.contains(slot) || !cameraId.equals(cameraOf.get(slot))) continue;
            if (best == null || better(slot, best, zoom, sensorMode)) best = slot;
        }
        return best;
    }

    private static boolean better(String a, String b, Map<String, Float> zoom, Map<String, Integer> sensorMode) {
        boolean plainA = value(sensorMode, a, 0) == 0, plainB = value(sensorMode, b, 0) == 0;
        if (plainA != plainB) return plainA;
        float da = Math.abs(value(zoom, a, 1f) - 1f), db = Math.abs(value(zoom, b, 1f) - 1f);
        return da < db - 1e-4f;
    }

    private static <T> T value(Map<String, T> map, String key, T fallback) {
        T v = map == null ? null : map.get(key);
        return v == null ? fallback : v;
    }

    /** Preference holding the last module used on the slot's side ("module_last_back"), null for a non-slot. */
    public static String lastKey(String slot) {
        String side = side(slot);
        return side == null ? null : "module_last_" + side;
    }

    /**
     * Module to restore when the camera flips to {@code targetSide}: the module last used there, while it is still a
     * visible slot of that side whose Camera ID exists; else null (the side's camera is used and the module strip picks).
     */
    public static String flipTarget(String remembered, String targetSide, boolean rememberedVisible,
                                    String rememberedCamera, Collection<String> cameraIds) {
        if (remembered == null || targetSide == null || !targetSide.equals(side(remembered))) return null;
        if (!rememberedVisible || rememberedCamera == null || cameraIds == null || !cameraIds.contains(rememberedCamera)) return null;
        return remembered;
    }
}
