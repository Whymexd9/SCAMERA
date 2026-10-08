package com.particlesdevs.photoncamera.settings;

import android.os.Build;

import com.particlesdevs.photoncamera.util.Lang;

import java.util.Map;

/**
 * {@link SettingsAvailability} with the facts of this phone: the 10-bit HEIC encoder, the vivo stock AE observer, the
 * colour matrix that replaces «Цветовой метод», SCAM HDR (the 8 Elite) and the Xiaomi 17 Ultra's smooth zoom. They need
 * Android, and the host check (tools/check_settings_model.py) compiles SettingsAvailability on its own, so the settings
 * screen and the shade build it here.
 */
public final class DeviceAvailability {
    private DeviceAvailability() {}

    public static SettingsAvailability of(Map<String, ?> values) {
        return new SettingsAvailability(values)
                .heic10Unavailable(com.particlesdevs.photoncamera.processing.heif.Heic10Support.unavailableReason())
                .hdrUnavailable(com.particlesdevs.photoncamera.processing.color.HdrOutput.unavailableReason())
                .stockAeDevice(com.particlesdevs.photoncamera.capture.VivoStockAe.supportedDevice())
                .colorMethodOverride(colorMethodOverride())
                .scamHdrSupported(PreferenceKeys.isScamHdrSupported())
                .xiaomiSmoothZoom(com.particlesdevs.photoncamera.capture.XiaomiTeleZoom.phone());
    }

    /**
     * Why «Цветовой метод» does not act on this phone, or null. Parameters picks the colour transform from the choice, then
     * the OPPO Find X7 Ultra's tuned matrix (per colour temperature) forces «Характеристики», and the ISP matrix that vivo
     * reports with every capture result forces «Захват» (unless nice_dev.txt turns isp_ccm off).
     */
    static String colorMethodOverride() {
        if (com.particlesdevs.photoncamera.processing.render.OppoTunedColor.applies())
            return Lang.t("На этом OPPO цвет задаёт настроенная матрица OPPO по цветовой температуре: выбор не действует.",
                    "On this OPPO the tuned OPPO matrix for the colour temperature sets the colour: the choice has no effect.");
        if ("vivo".equalsIgnoreCase(Build.MANUFACTURER) && PreferenceKeys.niceInternalValue("isp_ccm", 1f) > 0f)
            return Lang.t("На vivo цвет задаёт матрица ISP из каждого кадра: выбор не действует.",
                    "On vivo the ISP matrix of each frame sets the colour: the choice has no effect.");
        return null;
    }
}
