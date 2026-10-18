package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cameras that run the plain Camera2 preview (no vivo stock preview profile, no vivo EIS session key / request key).
 * Owner, 2026-10-19 (vivo X300, V2515): every camera's first session after the app started failed in endConfigure (-38) with
 * the vivo EIS session key, the HAL then closed the device (onError 4) and the owner saw «Сбой камеры (код 4), перезапуск»
 * - or «Модуль 3× не запускается … возврат к предыдущему» - before the plain preview ran. A refused session is now kept
 * across app runs for this firmware (Build.FINGERPRINT: an update may accept the key again, it is tried once more then).
 */
final class PlainPreviewCameras {
    private static final String PREFS = "scam_plain_preview";
    private static final String KEY_FINGERPRINT = "fingerprint";
    private static final String KEY_CAMERAS = "cameras";

    private static final Set<String> CAMERAS = ConcurrentHashMap.newKeySet();
    private static volatile boolean sLoaded;

    private PlainPreviewCameras() {
    }

    static boolean contains(String cameraId) {
        load();
        return cameraId != null && CAMERAS.contains(cameraId);
    }

    /** For this process only (a payload seen packed under the stock profile); true when it was not in the set yet. */
    static boolean add(String cameraId) {
        load();
        return cameraId != null && CAMERAS.add(cameraId);
    }

    /** The camera refused its session with the vivo keys: kept for this firmware as well; true when it was not in the set yet. */
    static boolean addRefused(String cameraId) {
        load();
        if (cameraId == null || !CAMERAS.add(cameraId)) return false;
        final SharedPreferences p = prefs();
        if (p != null) {
            final String fingerprint = android.os.Build.FINGERPRINT;
            final Set<String> stored = stored(fingerprint, p.getString(KEY_FINGERPRINT, null), p.getString(KEY_CAMERAS, null));
            stored.add(cameraId);
            p.edit().putString(KEY_FINGERPRINT, fingerprint).putString(KEY_CAMERAS, join(stored)).apply();
        }
        return true;
    }

    private static void load() {
        if (sLoaded) return;
        synchronized (CAMERAS) {
            if (sLoaded) return;
            final SharedPreferences p = prefs();
            if (p != null) {
                final Set<String> stored = stored(android.os.Build.FINGERPRINT, p.getString(KEY_FINGERPRINT, null), p.getString(KEY_CAMERAS, null));
                if (!stored.isEmpty()) com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE",
                        "cameras " + stored + " refused the vivo preview keys on this firmware: plain Camera2 preview from the start");
                CAMERAS.addAll(stored);
            }
            sLoaded = true;
        }
    }

    @Nullable
    private static SharedPreferences prefs() {
        try {
            final Context c = com.particlesdevs.photoncamera.app.ScameraApp.getAppContext();
            return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The stored cameras when they were stored on this firmware, none after a firmware change. */
    static Set<String> stored(@Nullable String fingerprint, @Nullable String storedFingerprint, @Nullable String cameras) {
        final Set<String> out = new TreeSet<>();
        if (fingerprint == null || !fingerprint.equals(storedFingerprint) || cameras == null) return out;
        for (String id : cameras.split(",")) {
            final String t = id.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static String join(Set<String> cameras) {
        return String.join(",", new TreeSet<>(cameras));
    }

    /** Tests only. */
    static void resetForTest() {
        CAMERAS.clear();
        sLoaded = true;
    }
}
