package com.particlesdevs.photoncamera.processing.color;

import android.os.Build;

import com.particlesdevs.photoncamera.processing.PhotoFormat;
import com.particlesdevs.photoncamera.processing.avif.AvifEncoder;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * When a shot gets «HDR в HEIC / AVIF» (P46): the switch is on, the (effective) format can carry 10-bit HDR - HEIC through
 * «HEIC 10 бит» (HEVC Main10), AVIF at 10 / 12 bit or lossless - and the phone has RGBA_1010102 bitmaps (Android 13).
 * Then the gain-map pass of Ultra HDR runs for the shot (Settings.gainMapPass) and the HEIC / AVIF is written from
 * {@link HlgRendition}. The rules are plain functions; {@link #wanted()} reads the stored settings, the HEIC encoder list
 * only when the switch is on.
 */
public final class HdrOutput {
    private HdrOutput() {}

    /** RGBA_1010102 bitmaps (the HLG picture) are Android 13. */
    public static final int MIN_SDK = 33;

    /** Whether a shot is written as HDR: the switch, the format, its 10-bit route, the Android version. */
    public static boolean applies(boolean setting, PhotoFormat format, boolean heic10, AvifEncoder.Options avif, int sdk) {
        if (!setting || sdk < MIN_SDK) return false;
        if (format == PhotoFormat.HEIC) return heic10;
        if (format == PhotoFormat.AVIF) return avif != null && AvifEncoder.keepsTenBits(avif);
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
        if (sdk < MIN_SDK) return Lang.t("Нужен Android 13 или новее: HEIC / AVIF сохраняются как обычные SDR.",
                "Needs Android 13 or newer: HEIC / AVIF are saved as ordinary SDR files.");
        return null;
    }

    /** {@link #reason(int)} on this phone. */
    public static String unavailableReason() {
        return reason(Build.VERSION.SDK_INT);
    }
}
