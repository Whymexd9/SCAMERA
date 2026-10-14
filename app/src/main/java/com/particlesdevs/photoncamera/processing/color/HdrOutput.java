package com.particlesdevs.photoncamera.processing.color;

import android.os.Build;

import com.particlesdevs.photoncamera.processing.PhotoFormat;
import com.particlesdevs.photoncamera.processing.avif.AvifEncoder;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * When a shot gets «HDR в HEIC / AVIF» (P46; P125: the ISO 21496-1 gain map instead of the HLG picture): the switch is on and
 * the (effective) format can carry the map - HEIC through «HEIC 10 бит» (the app's own HEIF writer, Android 13), AVIF at any
 * depth (Android 12, libavif). Then the gain-map pass of Ultra HDR runs for the shot (Settings.gainMapPass) and the HEIC /
 * AVIF is the SDR photo with its gain map (PhotoOutput, IsoGainMap). The rules are plain functions; {@link #wanted()} reads
 * the stored settings, the HEIC encoder list only when the switch is on.
 */
public final class HdrOutput {
    private HdrOutput() {}

    /** AVIF (and so the AVIF gain map) is Android 12; the HEIC one needs the 10-bit HEIC (Android 13). */
    public static final int MIN_SDK = PhotoFormat.AVIF_MIN_SDK;

    /** Whether a shot is written as HDR: the switch, the format, the 10-bit HEIC route, the Android version. */
    public static boolean applies(boolean setting, PhotoFormat format, boolean heic10, AvifEncoder.Options avif, int sdk) {
        if (!setting || sdk < MIN_SDK) return false;
        if (format == PhotoFormat.HEIC) return heic10 && sdk >= AvifEncoder.TEN_BIT_MIN_SDK;
        if (format == PhotoFormat.AVIF) return avif != null;
        return false;
    }

    /** {@link #applies} for the stored settings on this phone. */
    public static boolean wanted() {
        if (!com.particlesdevs.photoncamera.settings.PreferenceKeys.isHdrOutputOn()) return false;
        final PhotoFormat format = com.particlesdevs.photoncamera.settings.PreferenceKeys.getPhotoFormat();
        final boolean heic10 = format == PhotoFormat.HEIC && com.particlesdevs.photoncamera.processing.heif.Heic10Support.wanted();
        final AvifEncoder.Options avif = format == PhotoFormat.AVIF ? com.particlesdevs.photoncamera.settings.PreferenceKeys.getAvifOptions() : null;
        return applies(true, format, heic10, avif, Build.VERSION.SDK_INT);
    }

    /** Why the HDR switch cannot act on Android {@code sdk} (null: it can, given the format rows), in the UI language. */
    public static String reason(int sdk) {
        if (sdk < MIN_SDK) return Lang.t("Нужен Android 12 или новее: HEIC / AVIF сохраняются как обычные SDR.",
                "Needs Android 12 or newer: HEIC / AVIF are saved as ordinary SDR files.");
        return null;
    }

    /** {@link #reason(int)} on this phone. */
    public static String unavailableReason() {
        return reason(Build.VERSION.SDK_INT);
    }
}
