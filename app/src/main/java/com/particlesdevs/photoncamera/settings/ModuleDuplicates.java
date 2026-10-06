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
    private static final String[] SLOT_KEYS = {"module_id_", "module_name_", "module_label_", "module_zoom_", "module_sensorcrop_", "module_visible_"};

    private static boolean visible(Map<String, ?> p, String slot) { return Boolean.TRUE.equals(p.get("module_visible_" + slot)); }

    private static String camera(Map<String, ?> p, String slot) {
        Object manual = p.get("module_id_" + slot);
        if (manual != null && !manual.toString().trim().isEmpty()) return manual.toString().trim();
        Object auto = p.get("module_auto_" + slot);
        return auto == null ? slot : auto.toString();
    }

    /**
     * A hidden slot of the source's side for the copy: first one whose automatic camera another slot already shows (the filler
     * slots beyond the lens count), else any hidden one. Null when all eight are in use.
     */
    static String freeSlot(Map<String, ?> p, String source) {
        String side = source.startsWith("front") ? "front" : "back";
        List<String> hidden = new ArrayList<>();
        List<String> shownCameras = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String s = side + i;
            if (!p.containsKey("module_auto_" + s)) continue;
            if (visible(p, s)) shownCameras.add(camera(p, s)); else hidden.add(s);
        }
        for (String s : hidden) if (shownCameras.contains(String.valueOf(p.get("module_auto_" + s)))) return s;
        return hidden.isEmpty() ? null : hidden.get(0);
    }

    /** The writes that make {@code target} a duplicate of {@code source} (label / zoom as the source shows them). */
    static Map<String, Object> copyOf(Map<String, ?> p, String source, String target, String label, float zoom) {
        Map<String, Object> out = new HashMap<>();
        out.put("module_id_" + target, camera(p, source));
        out.put("module_name_" + target, label + " (2)");
        out.put("module_zoom_" + target, String.format(Locale.US, "%.3f", zoom).replaceAll("\\.?0+$", ""));
        Object crop = p.get("module_sensorcrop_" + source);
        if (crop != null) out.put("module_sensorcrop_" + target, crop);
        Object autoLabel = p.get("module_label_" + source);
        if (autoLabel != null) out.put("module_label_" + target, autoLabel);
        out.put("module_visible_" + target, true);
        String from = ModuleSensorSettings.prefix(source), to = ModuleSensorSettings.prefix(target);
        for (Map.Entry<String, ?> e : p.entrySet())
            if (e.getKey().startsWith(from)) out.put(to + e.getKey().substring(from.length()), e.getValue());
        out.put(MARK + target, source);
        return out;
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
