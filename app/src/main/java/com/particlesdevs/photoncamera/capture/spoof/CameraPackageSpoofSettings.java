package com.particlesdevs.photoncamera.capture.spoof;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

final class CameraPackageSpoofSettings {
    static final String KEY_ENABLED = "pref_camera_package_spoof_enabled";
    static final String KEY_OPLUS_PACKAGE = "pref_oplus_spoof_package_key";
    static final String KEY_GENERIC_PACKAGE = "pref_generic_spoof_package_key";
    static final String KEY_BINDER_PACKAGE = "pref_binder_spoof_package_key";

    private CameraPackageSpoofSettings() {}

    static String getOplusPackage(Context context) {
        // P192: the OPPO Find X7 Ultra lists its telephotos (cameras 4 and 5) only to the Oplus package of the probe; the
        // owner's own spoof settings win
        if (!enabled(context) && com.particlesdevs.photoncamera.capture.OppoNativeFullRes.teleAccess())
            return com.particlesdevs.photoncamera.capture.OppoNativeFullRes.TELE_PACKAGE;
        return getPackage(context, KEY_OPLUS_PACKAGE);
    }

    /** P192: the Oplus package is set without the owner's switch (the OPPO Find X7 Ultra's telephotos). */
    static boolean oplusAuto(Context context) {
        return !enabled(context) && com.particlesdevs.photoncamera.capture.OppoNativeFullRes.teleAccess();
    }

    // The owner's spoof settings win (owner, 2026-10-13: «я разрешаю работу с любым пакетом»)
    static String getGenericPackage(Context context) {
        String dcg = enabled(context) ? null : com.particlesdevs.photoncamera.capture.VivoQcomModes.spoofPackage();
        return dcg != null ? dcg : getPackage(context, KEY_GENERIC_PACKAGE);
    }

    static String getBinderPackage(Context context) {
        String dcg = enabled(context) ? null : com.particlesdevs.photoncamera.capture.VivoQcomModes.spoofPackage();
        return dcg != null ? dcg : getPackage(context, KEY_BINDER_PACKAGE);
    }

    private static boolean enabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_ENABLED, false);
    }

    // With the master switch off (the default) every method gets our own package,
    // which the spoof classes treat as "no hook".
    private static String getPackage(Context context, String key) {
        String nativePackage = context.getPackageName();
        SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        if (!preferences.getBoolean(KEY_ENABLED, false)) {
            return nativePackage;
        }
        String configured = preferences.getString(key, nativePackage);
        if (configured == null || configured.trim().isEmpty()) {
            return nativePackage;
        }
        return configured.trim();
    }
}
