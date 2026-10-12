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
        return getPackage(context, KEY_OPLUS_PACKAGE);
    }

    // The owner's spoof settings win (owner, 2026-10-13: «я разрешаю работу с любым пакетом»); with the spoof off, the X500 Pro
    // Max's DCG / LOFIC switch opens the cameras as the vivo camera (Dcg14Mode.spoofPackage)
    static String getGenericPackage(Context context) {
        String dcg = enabled(context) ? null : com.particlesdevs.photoncamera.capture.Dcg14Mode.spoofPackage();
        return dcg != null ? dcg : getPackage(context, KEY_GENERIC_PACKAGE);
    }

    static String getBinderPackage(Context context) {
        String dcg = enabled(context) ? null : com.particlesdevs.photoncamera.capture.Dcg14Mode.spoofPackage();
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
