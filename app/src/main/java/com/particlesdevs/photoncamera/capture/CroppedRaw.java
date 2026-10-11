package com.particlesdevs.photoncamera.capture;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.os.Build;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/**
 * P100 In-sensor zoom through the Android 14 stream use case CROPPED_RAW (Galaxy S24 Ultra: the main camera 0 / 20 / 23 and the
 * 5x tele 24 list it, every result carries android.scaler.rawCropRegion; SCAM TEST 2026-05-18). The RAW stream configured with
 * it follows the zoom: from about 2x the HAL reads the centre of the sensor at full resolution (remosaicked to Bayer) into the
 * same 4080x3060 RAW instead of cropping the binned frame, so a 2x photo has real 2x detail (owner: «нам нужен именно ISZ, а
 * не просто кроп с 12 мп»). rawCropRegion says which part of the active array a RAW frame holds; the photo's own zoom crop is
 * divided by that (no second crop), and ring frames of another crop are not merged with the base.
 * scam_dev "cropped_raw 0" turns it off.
 */
public final class CroppedRaw {
    /** SCALER_AVAILABLE_STREAM_USE_CASES_CROPPED_RAW (API 34). */
    static final long USE_CASE = 6L;
    /** A raw crop below this factor is the HAL's trim of the binned frame (S24U at 1x: 4016 of 4080 columns), not a zoom. */
    static final float MIN_FACTOR = 1.2f;

    private CroppedRaw() {}

    /** Whether this camera's RAW stream can take the CROPPED_RAW use case. */
    public static boolean supported(@Nullable CameraCharacteristics c) {
        if (c == null || Build.VERSION.SDK_INT < 34 || !PreferenceKeys.scamDevSwitch("cropped_raw", true)) return false;
        try {
            long[] cases = c.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES);
            if (cases == null) return false;
            for (long u : cases) if (u == USE_CASE) return true;
        } catch (RuntimeException ignored) {
            // key unknown on this platform
        }
        return false;
    }

    /** The frame's raw crop (null: none reported). */
    @Nullable
    public static Rect of(@Nullable CaptureResult r) {
        if (r == null || Build.VERSION.SDK_INT < 34) return null;
        try {
            return r.get(CaptureResult.SCALER_RAW_CROP_REGION);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** How much of a zoom the RAW already holds: the active width over the crop width, 1 below {@link #MIN_FACTOR}. */
    static float factor(int activeWidth, int cropWidth) {
        if (activeWidth <= 0 || cropWidth <= 0) return 1f;
        float f = (float) activeWidth / cropWidth;
        return f < MIN_FACTOR ? 1f : f;
    }

    public static float factor(@Nullable Rect crop, @Nullable Rect active) {
        return crop == null || active == null ? 1f : factor(active.width(), crop.width());
    }

    /** Whether two frames hold the same part of the sensor (both unknown counts as the same). */
    public static boolean same(@Nullable Rect a, @Nullable Rect b) {
        if (a == null || b == null) return a == b;
        return Math.abs(a.width() - b.width()) <= 8 && Math.abs(a.height() - b.height()) <= 8
                && Math.abs(a.centerX() - b.centerX()) <= 8 && Math.abs(a.centerY() - b.centerY()) <= 8;
    }
}
