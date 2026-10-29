package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/**
 * P411 (owner, 2026-10-29: «Мерцание ламп ... возьми его работу из Арк камеры, у нас он работает неверно»): «Мерцание ламп» reaches
 * the camera's AE as the reference app does (CameraManager.applyAntibandingToBuilder: CONTROL_AE_ANTIBANDING_MODE from its
 * setting on the preview and every capture request, the repeating request rebuilt on a change). Before, the camera got AUTO
 * whatever was chosen (the setting moved only our own long shutters). «Авто» AUTO, 50 Hz / 60 Hz the HAL's 50HZ / 60HZ, «Выкл.»
 * OFF; a mode the camera does not list falls back to AUTO (then to the request's own).
 */
public final class Antibanding {
    private Antibanding() { }

    /** The CONTROL_AE_ANTIBANDING_MODE of «Мерцание ламп» {@code setting} (-1 auto, 100 = 50 Hz, 120 = 60 Hz, 0 off). */
    public static int mode(int setting) {
        if (setting == 100) return CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ;
        if (setting == 120) return CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ;
        if (setting == 0) return CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF;
        return CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO;
    }

    /** The mode {@code wanted} if {@code available} lists it (null / empty: taken as listed), else AUTO if listed; -1: none. */
    static int supported(int wanted, @Nullable int[] available) {
        if (available == null || available.length == 0) return wanted;
        boolean auto = false;
        for (int m : available) {
            if (m == wanted) return wanted;
            auto |= m == CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO;
        }
        return auto ? CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO : -1;
    }

    /** Sets «Мерцание ламп» on {@code builder} for the camera of {@code characteristics} (null: unchecked). */
    public static void apply(@Nullable CaptureRequest.Builder builder, @Nullable CameraCharacteristics characteristics) {
        if (builder == null) return;
        try {
            final int[] available = characteristics == null ? null
                    : characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES);
            final int m = supported(mode(PreferenceKeys.antibandingSetting()), available);
            if (m >= 0) builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, m);
        } catch (RuntimeException e) {
            android.util.Log.w("SCAM_CAPTURE", "antibanding not applied: " + e);
        }
    }
}
