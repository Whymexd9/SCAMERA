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
        return prefs().getBoolean("scamera_quad_bayer_enabled", false);
    }

    public static String quadBayerMode() {
        return prefs().getString("scamera_quad_bayer_mode", "auto");
    }

    public static String quadDngMetadata() {
        return prefs().getString("scamera_quad_dng_metadata", "auto");
    }

    public static boolean quadBayerDirectRequested() {
        String mode = quadBayerMode();
        return quadBayerEnabled() && ("auto".equals(mode) || "direct_quad".equals(mode));
    }

    public static boolean quadDng4x4(boolean isDirectQuad) {
        String mode = quadDngMetadata();
        return "qbcfa_4x4".equals(mode) || ("auto".equals(mode) && isDirectQuad);
    }

    public static boolean mosaicSrEnabled() {
        return prefs().getBoolean("scamera_mosaic_sr_enabled", false);
    }

    public static boolean mosaicSrUseForJpeg() {
        return prefs().getBoolean("scamera_mosaic_sr_jpeg", true);
    }

    public static float mosaicSrScale() {
        try {
            return Float.parseFloat(prefs().getString("scamera_mosaic_sr_scale", "1.41421356"));
        } catch (NumberFormatException ignored) {
            return 1.41421356f;
        }
    }

    public static int mosaicSrKernel() {
        String value = prefs().getString("scamera_mosaic_sr_kernel", "lanczos2");
        if ("bilinear".equals(value)) return 0;
        if ("catmull_rom".equals(value)) return 1;
        return 2;
    }

    public static boolean darktableEnabled() { return prefs().getBoolean("scamera_darktable_enabled", false); }
    public static float darktableExposure() { return intValue("scamera_darktable_exposure", 0) / 10.0f; }
    public static float darktableFilmicContrast() { return intValue("scamera_darktable_filmic_contrast", 100) / 100.0f; }
    public static float darktableShadows() { return intValue("scamera_darktable_shadows", 0) / 100.0f; }
    public static float darktableHighlights() { return intValue("scamera_darktable_highlights", 0) / 100.0f; }
    public static float darktableLocalContrast() { return intValue("scamera_darktable_local_contrast", 0) / 100.0f; }
    public static float darktableColorfulness() { return intValue("scamera_darktable_colorfulness", 0) / 100.0f; }
    public static float darktableHighlightReconstruction() { return intValue("scamera_darktable_highlight_reconstruction", 0) / 100.0f; }
    public static float darktableDiffuseSharpen() { return intValue("scamera_darktable_diffuse_sharpen", 0) / 100.0f; }
    public static float darktableProfiledDenoise() { return intValue("scamera_darktable_profiled_denoise", 0) / 100.0f; }
    public static float darktableToneShadows() { return intValue("scamera_darktable_tone_shadows", 0) / 100.0f; }
    public static float darktableToneMidtones() { return intValue("scamera_darktable_tone_midtones", 0) / 100.0f; }
    public static float darktableToneHighlights() { return intValue("scamera_darktable_tone_highlights", 0) / 100.0f; }
    public static float darktableBalanceShadows() { return intValue("scamera_darktable_balance_shadows", 0) / 100.0f; }
    public static float darktableBalanceMidtones() { return intValue("scamera_darktable_balance_midtones", 0) / 100.0f; }
    public static float darktableBalanceHighlights() { return intValue("scamera_darktable_balance_highlights", 0) / 100.0f; }
    public static float darktableColorReconstruction() { return intValue("scamera_darktable_color_reconstruction", 0) / 100.0f; }
    public static float darktableCalibrationTemperature() { return intValue("scamera_darktable_calibration_temperature", 0) / 100.0f; }
    public static float darktableCalibrationTint() { return intValue("scamera_darktable_calibration_tint", 0) / 100.0f; }
    public static float darktableColorRed() { return intValue("scamera_darktable_color_red", 0) / 100.0f; }
    public static float darktableColorGreen() { return intValue("scamera_darktable_color_green", 0) / 100.0f; }
    public static float darktableColorBlue() { return intValue("scamera_darktable_color_blue", 0) / 100.0f; }
    public static float darktableWideCa() { return intValue("scamera_darktable_wide_ca", 0) / 100.0f; }
    public static float darktableVignette() { return intValue("scamera_darktable_vignette", 0) / 100.0f; }
    public static float darktableHazeRemoval() { return intValue("scamera_darktable_haze", 0) / 100.0f; }
    public static float darktableTexture() { return intValue("scamera_darktable_texture", 0) / 100.0f; }

    private static int intValue(String key, int fallback) {
        try {
            Object value = prefs().getAll().get(key);
            if (value instanceof Number) return ((Number) value).intValue();
            if (value != null) return Integer.parseInt(value.toString());
        } catch (RuntimeException ignored) { }
        return fallback;
    }
}
