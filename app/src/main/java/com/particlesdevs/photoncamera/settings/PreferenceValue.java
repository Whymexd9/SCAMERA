package com.particlesdevs.photoncamera.settings;

import android.content.SharedPreferences;

import java.util.concurrent.ConcurrentHashMap;

/**
 * One stored preference value of any type, without SharedPreferences.getAll():
 * getAll() copies the whole map (hundreds of keys) on every call, and settings
 * were read that way dozens of times per preview frame (~10% of the camera
 * thread). The stored type of each key is remembered, so a read is one typed
 * lookup; a key whose type changed is simply re-probed.
 */
public final class PreferenceValue {
    private static final int STRING = 0, BOOLEAN = 1, INT = 2, FLOAT = 3, LONG = 4, SET = 5;
    private static final ConcurrentHashMap<String, Integer> TYPES = new ConcurrentHashMap<>();

    private PreferenceValue() {}

    /**
     * P277 (ScamLabRun): while the lab renders a saved shot, the overlaid preferences read as the shot's own snapshot (with the
     * tried value): {@code values} stands for the whole file (a key it lacks reads as unset). Null: the stored values.
     */
    private static volatile SharedPreferences overlaid;
    private static volatile java.util.Map<String, Object> overlay;

    public static void overlay(SharedPreferences prefs, java.util.Map<String, Object> values) {
        overlay = values;
        overlaid = values == null ? null : prefs;
    }

    public static boolean overlaid() {
        return overlay != null;
    }

    public static Object get(SharedPreferences prefs, String key) {
        final java.util.Map<String, Object> o = overlay;
        if (o != null && prefs != null && prefs == overlaid) return key == null ? null : o.get(key);
        if (prefs == null || key == null || !prefs.contains(key)) return null;
        Integer known = TYPES.get(key);
        if (known != null) {
            try { return read(prefs, key, known); } catch (ClassCastException changed) { TYPES.remove(key); }
        }
        for (int type = STRING; type <= SET; type++) {
            try {
                Object value = read(prefs, key, type);
                TYPES.put(key, type);
                return value;
            } catch (ClassCastException ignored) {
            }
        }
        return null;
    }

    private static Object read(SharedPreferences prefs, String key, int type) {
        switch (type) {
            case STRING: return prefs.getString(key, null);
            case BOOLEAN: return prefs.getBoolean(key, false);
            case INT: return prefs.getInt(key, 0);
            case FLOAT: return prefs.getFloat(key, 0f);
            case LONG: return prefs.getLong(key, 0L);
            default: return prefs.getStringSet(key, null);
        }
    }
}
