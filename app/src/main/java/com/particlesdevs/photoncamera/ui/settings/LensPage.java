package com.particlesdevs.photoncamera.ui.settings;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * P413 (owner, 2026-10-30: «Сделай настройки линз аналогично Арк»): the lens page in the reference app's sections. The module's
 * generated sensor rows go to the section of their meaning; its camera keys, sensor mode, frame-rate check and reset stay in its
 * own category («Ключи камеры»), which rebuilds them.
 */
final class LensPage {
    static final String RAW = "lens_raw_category", LEVELS = "lens_levels_category", ISP = "lens_isp_category";

    /** Field (as the row's key ends), its section and the row of that section it follows (null: the section's start). */
    private static final String[][] MOVES = {
            {"ispsurface", RAW, "@cfa"},
            {"rawfps", RAW, "ispsurface"},
            {"fpsprobe", RAW, "rawfps"},
            {"rawviewfinder", RAW, "fpsprobe"},
            {"blackleveloverride", LEVELS, "pref_raw_black_from_data"},
            {"whiteleveloverride", LEVELS, "blackleveloverride"},
            {"oismode", ISP, null},
            {"sessiontype", ISP, "oismode"},
    };

    private LensPage() { }

    /** Moves {@code slot}'s sensor rows on {@code page} (the lens page) to their sections. */
    static void arrange(PreferenceGroup page, @Nullable String slot, String cfaKey) {
        if (slot == null) return;
        for (String[] m : MOVES) {
            Preference row = page.findPreference(key(slot, m[0]));
            PreferenceGroup section = page.findPreference(m[1]);
            if (row == null || section == null || row.getParent() == null) continue;
            String after = m[2] == null ? null : "@cfa".equals(m[2]) ? cfaKey : m[2].startsWith("pref_") ? m[2] : key(slot, m[2]);
            List<Preference> rows = new ArrayList<>();
            for (int i = 0; i < section.getPreferenceCount(); i++) rows.add(section.getPreference(i));
            rows.sort((a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
            int at = 0;
            for (int i = 0; i < rows.size(); i++) if (after != null && after.equals(rows.get(i).getKey())) at = i + 1;
            row.getParent().removePreference(row);
            rows.add(at, row);
            for (int i = 0; i < rows.size(); i++) rows.get(i).setOrder(i);
            section.addPreference(row);
        }
    }

    static String key(String slot, String field) {
        return "pref_sensorconfig_" + slot + "_" + field;
    }

    /** A module's generated sensor row (its own by its key, wherever the page put it). */
    static boolean sensorRow(@Nullable String key) {
        return key != null && key.startsWith("pref_sensorconfig_");
    }
}
