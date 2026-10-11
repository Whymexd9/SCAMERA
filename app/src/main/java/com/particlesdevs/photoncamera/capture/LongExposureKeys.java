package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureRequest;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/**
 * P98 Frames longer than the camera's declared frame-duration limit (the Xiaomi 17 Ultra: 1 s declared, its vendor exposure
 * range 36..180 s; SCAM TEST reports 2026-10-11). Every Qualcomm HAL of the reports carries
 * org.quic.camera.manualExposure.disableFPSLimits (byte) and overrideSensorFrameDuration (int64) in its request templates
 * (value 0; the 17 Ultra's stock app sends 0 in all its dumps): such a frame sets them to 1 and its exposure, every other frame
 * of the series back to 0. Only where the template has them (no MediaTek HAL). scam_dev "long_exposure_keys 0" turns it off.
 * Not yet seen on a phone: whether the HAL then honours the longer exposure (the result's exposure time tells).
 */
public final class LongExposureKeys {
    static final String DISABLE_FPS_LIMITS = "org.quic.camera.manualExposure.disableFPSLimits";
    static final String OVERRIDE_FRAME_DURATION = "org.quic.camera.manualExposure.overrideSensorFrameDuration";
    private static final CaptureRequest.Key<Byte> DISABLE = new CaptureRequest.Key<>(DISABLE_FPS_LIMITS, Byte.class);
    private static final CaptureRequest.Key<Long> OVERRIDE = new CaptureRequest.Key<>(OVERRIDE_FRAME_DURATION, Long.class);

    private LongExposureKeys() {}

    /** Whether a frame of {@code shutterNs} needs the keys: longer than the declared frame-duration limit. */
    static boolean needed(long shutterNs, Long maxFrameDurationNs) {
        return maxFrameDurationNs != null && maxFrameDurationNs > 0 && shutterNs > maxFrameDurationNs;
    }

    /** Sets (or resets) the keys on a series request of {@code shutterNs}; returns whether the frame got them. */
    public static boolean apply(CaptureRequest.Builder builder, long shutterNs, Long maxFrameDurationNs) {
        if (builder == null || !PreferenceKeys.scamDevSwitch("long_exposure_keys", true)) return false;
        final boolean on = needed(shutterNs, maxFrameDurationNs);
        try {
            if (builder.get(DISABLE) == null || builder.get(OVERRIDE) == null) return false; // not in this HAL's template
            builder.set(DISABLE, (byte) (on ? 1 : 0));
            builder.set(OVERRIDE, on ? shutterNs : 0L);
            if (on) builder.set(CaptureRequest.SENSOR_FRAME_DURATION, shutterNs);
            return on;
        } catch (RuntimeException unsupported) {
            return false;
        }
    }
}
