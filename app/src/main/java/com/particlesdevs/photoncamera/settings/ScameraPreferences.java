package com.particlesdevs.photoncamera.settings;

import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.particlesdevs.photoncamera.app.PhotonCamera;

/** Preferences for SCAMERA processing extensions that do not belong to Photon settings. */
public final class ScameraPreferences {
    private ScameraPreferences() {}

    private static SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(
                PhotonCamera.getSettingsManagerStatic().getContext());
    }

    public static boolean quadBayerEnabled() {
        return PreferenceNumber.bool(com.particlesdevs.photoncamera.settings.PreferenceValue.get(prefs(), "scamera_quad_bayer_enabled"), false);
    }

    public static String quadBayerMode() {
        return text("scamera_quad_bayer_mode", "auto");
    }

    public static String quadDngMetadata() {
        return text("scamera_quad_dng_metadata", "auto");
    }

    public static boolean quadBayerDirectRequested() {
        String mode = quadBayerMode();
        return quadBayerEnabled() && ("auto".equals(mode) || "direct_quad".equals(mode));
    }

    public static boolean quadDng4x4(boolean isDirectQuad) {
        String mode = quadDngMetadata();
        return "qbcfa_4x4".equals(mode) || ("auto".equals(mode) && isDirectQuad);
    }






    private static String text(String key, String fallback) {
        Object value = com.particlesdevs.photoncamera.settings.PreferenceValue.get(prefs(), key); return value == null ? fallback : value.toString();
    }

    private static int intValue(String key, int fallback) {
        try {
            Object value = com.particlesdevs.photoncamera.settings.PreferenceValue.get(prefs(), key);
            if (value instanceof Number) return ((Number) value).intValue();
            if (value != null) return Integer.parseInt(value.toString());
        } catch (RuntimeException ignored) { }
        return fallback;
    }
}
