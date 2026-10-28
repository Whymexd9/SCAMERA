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
    /**
     * Audit 2026-10-27 (lab-app-3): the lab's overlays are the render's own. They apply on the thread that set them, on the
     * threads it starts meanwhile (they inherit its scope) and on the pool items it hands out (ParallelWork carries the scope);
     * the camera, the settings and every other thread read the stored values while a lab render runs in the background. Each
     * overlay set makes a new token, so a thread kept from an earlier render (a lazily made pool) never matches again.
     */
    private static final InheritableThreadLocal<Object> SCOPE = new InheritableThreadLocal<>();
    private static volatile Object token;

    public static void overlay(SharedPreferences prefs, java.util.Map<String, Object> values) {
        if (values != null) enterScope();
        overlay = values;
        overlaid = values == null ? null : prefs;
    }

    /** Whether this thread reads the lab's overlay now. */
    public static boolean overlaid() {
        return overlay != null && inScope();
    }

    /** A new lab scope on this thread: it and the threads it starts from now on read the lab's overlays, no earlier thread. */
    public static void enterScope() {
        final Object t = new Object();
        SCOPE.set(t);
        token = t;
    }

    /** Whether this thread belongs to the lab render (its own thread, a thread it started, a pool item it handed out). */
    public static boolean inScope() {
        final Object t = token;
        return t != null && SCOPE.get() == t;
    }

    /** This thread's lab scope (null outside a lab render): ParallelWork hands it to the items it runs on its pool. */
    public static Object scope() {
        final Object t = token;
        return t != null && SCOPE.get() == t ? t : null;
    }

    /** A pool thread takes {@code scope} (from {@link #scope()}) for one item, or none (null): it keeps none of its own. */
    public static void adoptScope(Object scope) {
        if (scope == null) SCOPE.remove();
        else SCOPE.set(scope);
    }

    public static Object get(SharedPreferences prefs, String key) {
        final java.util.Map<String, Object> o = overlay;
        if (o != null && prefs != null && prefs == overlaid && inScope()) return key == null ? null : o.get(key);
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
