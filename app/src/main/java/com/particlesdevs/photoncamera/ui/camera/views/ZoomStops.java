package com.particlesdevs.photoncamera.ui.camera.views;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * P343 (owner, 2026-10-26, X200 Ultra screenshot: the strip read 0,4 | 0,8 | 0,8 | 1× | 2 | 2 | 2,4 | 4,8 | 9,6, «добавь в план
 * исправить»): one button per stop. Two visible modules with the same button label (an ISZ module made by the vendor module
 * set-up next to an imported or earlier copy of the same ultra-wide / main ISZ) showed twice, and the second one opened the same
 * camera at the same zoom. Per label the strip keeps the module with the best route - an in-sensor crop (ISZ) before a lens's own
 * module before a digital copy -, the first in zoom order among equals; the active module always keeps its stop (the strip never
 * loses the selection). Modules the owner named differently stay separate buttons whatever their zoom. The others stay in the
 * module settings.
 */
public final class ZoomStops {
    private ZoomStops() {}

    /** The label as the strip compares it: case, spaces, the zoom sign and the decimal comma do not count («0,8×» = «0.8»). */
    public static String key(String label) {
        return label == null ? "" : label.trim().toLowerCase(Locale.ROOT).replace("×", "").replaceAll("(?<=\\d)x", "")
                .replaceAll("(?<=\\d),(?=\\d)", ".").replaceAll("\\s+", "");
    }

    /** The slots to show, in their order: one per label key. {@code rank} orders the routes (higher wins), {@code active} always wins. */
    public static List<String> unique(List<String> slots, Function<String, String> label, ToIntFunction<String> rank, String active) {
        Map<String, String> best = new LinkedHashMap<>();
        for (String slot : slots) {
            String k = key(label.apply(slot));
            String held = best.get(k);
            if (held == null) { best.put(k, slot); continue; }
            if (held.equals(active)) continue;
            if (slot.equals(active) || rank.applyAsInt(slot) > rank.applyAsInt(held)) best.put(k, slot);
        }
        List<String> out = new ArrayList<>();
        for (String slot : slots) if (slot.equals(best.get(key(label.apply(slot))))) out.add(slot);
        return out;
    }
}
