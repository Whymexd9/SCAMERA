package com.particlesdevs.photoncamera.settings;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * The shade's pinned tiles: an ordered list of preference keys, comma separated, in {@link #KEY} of the main settings.
 * A "ui_" key on purpose: every "pref_" key is per module when per-lens settings are on (ModuleProfiles.isLocal), and the
 * tiles must not change with the lens. Being in the main settings, the list travels in the config file's main part.
 * <p>
 * Nothing stored yet: {@link ShadeCatalog#defaultTiles()}. An empty string is a valid choice (every tile removed). Keys the
 * catalog does not know on this phone, repeats and everything after {@link ShadeCatalog#MAX_TILES} are dropped on read;
 * keys of removed settings are also dropped from the stored value by SettingsMigration.removeObsolete.
 */
public final class ShadeTiles {
    public static final String KEY = "ui_shade_tiles";

    private ShadeTiles() {}

    /** The stored list as written, or null when nothing was ever stored. */
    @Nullable
    public static List<String> stored(SharedPreferences prefs) {
        Object raw = PreferenceValue.get(prefs, KEY);
        return raw == null ? null : split(raw.toString());
    }

    /** The tiles to show: the stored keys (or the defaults) that {@code known} accepts, without repeats, at most 12. */
    public static List<String> load(SharedPreferences prefs, Predicate<String> known) {
        List<String> stored = stored(prefs);
        return clean(stored == null ? ShadeCatalog.defaultTiles() : stored, known);
    }

    /** Known keys in order, without repeats, the first {@link ShadeCatalog#MAX_TILES}. */
    public static List<String> clean(Collection<String> keys, Predicate<String> known) {
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            if (out.size() >= ShadeCatalog.MAX_TILES) break;
            if (key == null || key.isEmpty() || out.contains(key) || !known.test(key)) continue;
            out.add(key);
        }
        return out;
    }

    public static void save(SharedPreferences prefs, List<String> keys) {
        prefs.edit().putString(KEY, join(keys)).apply();
    }

    static String join(List<String> keys) {
        return String.join(",", keys);
    }

    static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String key = part.trim();
            if (!key.isEmpty()) out.add(key);
        }
        return out;
    }
}
