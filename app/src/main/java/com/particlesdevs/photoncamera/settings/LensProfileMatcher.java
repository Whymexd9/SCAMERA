package com.particlesdevs.photoncamera.settings;

import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps the module slots of a saved config onto the module slots of another phone by what the lens is, not by its slot
 * name or Camera ID: same facing, nearest 35 mm equivalent focal length (in log), the same role (main, ultra wide, tele,
 * sensor crop) preferred. Many-to-one is allowed (one source tele can feed a 3× and a 6× slot); a target with no lens of
 * its facing in the source gets none (the caller gives it the baseline). Pure Java: no Android types (the summary text
 * of {@link #describe} follows the UI language through Lang).
 */
public final class LensProfileMatcher {
    private LensProfileMatcher() {}

    public enum Role { FRONT, MAIN, UW, TELE, CROP }

    /** One module slot: its slot id, facing, 35 mm equivalent focal length, zoom ratio, sensor crop and label. */
    public static final class Lens {
        public final String slot, label;
        public final boolean front, crop;
        public final float focal, zoom;
        public Lens(String slot, boolean front, float focal, float zoom, boolean crop, String label) {
            this.slot = slot; this.front = front; this.focal = focal; this.zoom = zoom; this.crop = crop;
            this.label = label == null ? slot : label;
        }
        @Override public String toString() {
            return String.format(Locale.US, "%s(%s %.0fmm %.2fx%s)", slot, front ? "front" : "back", focal, zoom, crop ? " crop" : "");
        }
    }

    /** Focal length of the main back lens: the uncropped back slot whose zoom is nearest to 1×. */
    static float mainFocal(List<Lens> lenses) {
        Lens best = null;
        for (Lens l : lenses) {
            if (l.front || l.crop || !(l.focal > 0f)) continue;
            if (best == null || Math.abs(Math.log(l.zoom)) < Math.abs(Math.log(best.zoom))) best = l;
        }
        if (best == null) for (Lens l : lenses) if (!l.front && l.focal > 0f) { best = l; break; }
        return best == null ? 0f : best.focal;
    }

    static Role role(Lens l, float mainFocal) {
        if (l.front) return Role.FRONT;
        if (l.crop) return Role.CROP;
        if (mainFocal > 0f && l.focal < 0.8f * mainFocal) return Role.UW;
        if (mainFocal > 0f && l.focal > 1.25f * mainFocal) return Role.TELE;
        return Role.MAIN;
    }

    /** For every target slot (in order) the matched source slot, or null when the source has no lens of that facing. */
    public static Map<String, String> match(List<Lens> sources, List<Lens> targets) {
        float sMain = mainFocal(sources), tMain = mainFocal(targets);
        Map<String, String> out = new LinkedHashMap<>();
        for (Lens t : targets) {
            Role tr = role(t, tMain);
            Lens best = null;
            double bestCost = Double.MAX_VALUE;
            for (Lens s : sources) {
                if (s.front != t.front || !(s.focal > 0f) || !(t.focal > 0f)) continue;
                double cost = Math.abs(Math.log(t.focal / s.focal)) + (role(s, sMain) == tr ? 0.0 : 1.0);
                boolean better = best == null || cost < bestCost - 1e-6
                        || (Math.abs(cost - bestCost) <= 1e-6 && tieBreak(s, best, t) < 0);
                if (better) { best = s; bestCost = cost; }
            }
            out.put(t.slot, best == null ? null : best.slot);
        }
        return out;
    }

    /** On a tie: the same sensor crop as the target first, then the lower zoom. */
    private static int tieBreak(Lens a, Lens b, Lens target) {
        boolean ac = a.crop == target.crop, bc = b.crop == target.crop;
        if (ac != bc) return ac ? -1 : 1;
        return Float.compare(a.zoom, b.zoom);
    }

    /** "Основная 1× ← 1×, 3× ← 2.4×, Фронтальная ← Фронтальная"; unmatched targets read "← общие" ("← shared" in English). */
    public static String describe(Map<String, String> mapping, List<Lens> sources, List<Lens> targets) {
        Map<String, Lens> src = new LinkedHashMap<>();
        for (Lens s : sources) src.put(s.slot, s);
        List<String> parts = new ArrayList<>();
        for (Lens t : targets) {
            String from = mapping.get(t.slot);
            Lens s = from == null ? null : src.get(from);
            parts.add(t.label + " ← " + (s == null ? Lang.t("общие", "shared") : s.label));
        }
        return String.join(", ", parts);
    }
}
