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

    static String getGenericPackage(Context context) {
        return getPackage(context, KEY_GENERIC_PACKAGE);
    }

    static String getBinderPackage(Context context) {
        return getPackage(context, KEY_BINDER_PACKAGE);
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
