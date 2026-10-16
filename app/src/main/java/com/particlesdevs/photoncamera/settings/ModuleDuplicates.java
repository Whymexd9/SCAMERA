package com.particlesdevs.photoncamera.settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Duplicate modules (P21): a second button on the same lens with its own requests and profile, e.g. the X7 Ultra's 1x with and
 * without the engineer-mode sensor mode, or vivo 2x / 10x. A duplicate takes a free (hidden) slot of the same side and copies
 * the source's camera ID, zoom, sensor crop, name + " (2)" and every per-slot sensor setting (vendor requests included:
 * {@code pref_sensorconfig_<slot>_*}). It is marked {@code module_duplicate_<slot>} = source, so it can be deleted again; an
 * original module cannot be deleted. Pure functions over the preference map, applied by {@link ModuleRegistry}.
 */
public final class ModuleDuplicates {
    private ModuleDuplicates() {}

    static final String MARK = "module_duplicate_";
    private static final String[] SLOT_KEYS = {"module_id_", "module_name_", "module_label_", "module_zoom_", "module_sensorcrop_", "module_visible_", "module_crop_"};

    private static boolean visible(Map<String, ?> p, String slot) { return Boolean.TRUE.equals(p.get("module_visible_" + slot)); }

    private static String camera(Map<String, ?> p, String slot) {
        Object manual = p.get("module_id_" + slot);
        if (manual != null && !manual.toString().trim().isEmpty()) return manual.toString().trim();
        Object auto = p.get("module_auto_" + slot);
        return auto == null ? slot : auto.toString();
    }

    /**
     * A hidden slot of the source's side for the copy: first one whose automatic camera another slot already shows (the filler
     * slots beyond the lens count), else any hidden one. Null when all slots are in use.
     */
    static String freeSlot(Map<String, ?> p, String source) {
        return freeSlot(p, source, 8);
    }

    static String freeSlot(Map<String, ?> p, String source, int capacity) {
        String side = source.startsWith("front") ? "front" : "back";
        List<String> hidden = new ArrayList<>();
        List<String> shownCameras = new ArrayList<>();
        for (int i = 0; i < capacity; i++) {
            String s = side + i;
            if (!p.containsKey("module_auto_" + s)) continue;
            if (visible(p, s)) shownCameras.add(camera(p, s)); else hidden.add(s);
        }
        // P148: an untouched filler first (a slot beyond the lens count: its automatic camera repeats an earlier slot's, and it is
        // no copy), so a lens module the owner hid keeps its settings while a filler is free; then as before
        for (String s : hidden) if (untouchedFiller(p, s)) return s;
        for (String s : hidden) if (shownCameras.contains(String.valueOf(p.get("module_auto_" + s)))) return s;
        return hidden.isEmpty() ? null : hidden.get(0);
    }

    /**
     * P148: a free place for a copy - a hidden filler slot nobody set up (no copy mark, no own Camera ID or name, no sensor
     * settings): the module list shows these as a count, not as modules.
     */
    static boolean untouchedFiller(Map<String, ?> p, String slot) {
        String side = slot.startsWith("front") ? "front" : "back";
        if (visible(p, slot) || !filler(p, side, slot) || p.containsKey(MARK + slot)) return false;
        Object id = p.get("module_id_" + slot), name = p.get("module_name_" + slot);
        if (id != null && !id.toString().trim().isEmpty()) return false;
        if (name != null && !name.toString().trim().isEmpty()) return false;
        String prefix = ModuleSensorSettings.prefix(slot);
        for (String k : p.keySet()) if (k.startsWith(prefix)) return false;
        return true;
    }

    /** P148: a slot whose automatic camera is already an earlier slot's of its side (ModuleRegistry repeats the last lens). */
    private static boolean filler(Map<String, ?> p, String side, String slot) {
        Object auto = p.get("module_auto_" + slot);
        if (auto == null) return false;
        final int index;
        try { index = Integer.parseInt(slot.substring(side.length())); } catch (NumberFormatException e) { return false; }
        for (int i = 0; i < index; i++) if (auto.equals(p.get("module_auto_" + side + i))) return true;
        return false;
    }

    /** The writes that make {@code target} a duplicate of {@code source} (label / zoom as the source shows them). */
    static Map<String, Object> copyOf(Map<String, ?> p, String source, String target, String label, float zoom) {
        return copyOf(p, source, target, label, zoom, 1f, 1f);
    }

    /**
     * P148: with a lens crop: the copy's zoom is the source's times {@code crop}, its own crop the source's times it, and it is
     * named after its zoom («1.3×»); crop 1 is the plain duplicate («1× (2)»).
     */
    static Map<String, Object> copyOf(Map<String, ?> p, String source, String target, String label, float zoom, float sourceCrop, float crop) {
        Map<String, Object> out = new HashMap<>();
        final boolean cropped = crop > 1.0001f;
        out.put("module_id_" + target, camera(p, source));
        out.put("module_name_" + target, cropped ? format(zoom * crop) + "×" : label + " (2)");
        out.put("module_zoom_" + target, format(cropped ? zoom * crop : zoom));
        if (cropped || sourceCrop > 1.0001f) out.put("module_crop_" + target, format(sourceCrop * (cropped ? crop : 1f)));
        Object sensorCrop = p.get("module_sensorcrop_" + source);
        if (sensorCrop != null) out.put("module_sensorcrop_" + target, sensorCrop);
        Object autoLabel = p.get("module_label_" + source);
        if (autoLabel != null) out.put("module_label_" + target, autoLabel);
        out.put("module_visible_" + target, true);
        String from = ModuleSensorSettings.prefix(source), to = ModuleSensorSettings.prefix(target);
        for (Map.Entry<String, ?> e : p.entrySet())
            if (e.getKey().startsWith(from)) out.put(to + e.getKey().substring(from.length()), e.getValue());
        out.put(MARK + target, source);
        return out;
    }

    /** A ratio as the module keys store it: up to three decimals, no trailing zeros («1.3», «2»). */
    static String format(float v) {
        return String.format(Locale.US, "%.3f", v).replaceAll("\\.?0+$", "");
    }

    /** Keys a deleted duplicate loses (its automatic camera stays: the slot is a filler again). */
    static List<String> removalOf(Map<String, ?> p, String slot) {
        List<String> keys = new ArrayList<>();
        for (String k : SLOT_KEYS) keys.add(k + slot);
        keys.add(MARK + slot);
        String prefix = ModuleSensorSettings.prefix(slot);
        for (String k : p.keySet()) if (k.startsWith(prefix)) keys.add(k);
        return keys;
    }

    static String sourceOf(Map<String, ?> p, String slot) {
        Object v = p.get(MARK + slot);
        return v == null ? null : v.toString();
    }
}
