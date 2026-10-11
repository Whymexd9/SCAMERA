package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureResult;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P98 What the camera's own 3A says about motion, per capture result (vendor tags found in the SCAM TEST reports, 2026-10-11):
 * <ul>
 *   <li>com.qti.stats_control.ais_safe_exposure_time (long, ns; OPPO X7U / X8U): the exposure the HAL's anti-shake considers
 *       safe now;</li>
 *   <li>motion flags: com.qti.stats_control.motion_detected (int), com.oplus.rawhdr.shake.state (byte; the X9U reported 1
 *       handheld), vivo.feedback.motionResult (int);</li>
 *   <li>a per-frame shake value: com.oplus.gyro.data (3 floats, the length is used), vivo.feedback.gyroDetect (1 float, its
 *       magnitude) - only compared between frames of one burst, never against a fixed threshold.</li>
 * </ul>
 * A tag the HAL does not know is remembered and not asked again. scam_dev "hal_motion 0" turns all of it off.
 */
public final class HalMotion {
    private static final CaptureResult.Key<long[]> SAFE_EXPOSURE = new CaptureResult.Key<>("com.qti.stats_control.ais_safe_exposure_time", long[].class);
    private static final CaptureResult.Key<int[]> QTI_MOTION = new CaptureResult.Key<>("com.qti.stats_control.motion_detected", int[].class);
    private static final CaptureResult.Key<byte[]> OPLUS_SHAKE = new CaptureResult.Key<>("com.oplus.rawhdr.shake.state", byte[].class);
    private static final CaptureResult.Key<int[]> VIVO_MOTION = new CaptureResult.Key<>("vivo.feedback.motionResult", int[].class);
    private static final CaptureResult.Key<float[]> OPLUS_GYRO = new CaptureResult.Key<>("com.oplus.gyro.data", float[].class);
    private static final CaptureResult.Key<float[]> VIVO_GYRO = new CaptureResult.Key<>("vivo.feedback.gyroDetect", float[].class);
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();

    private HalMotion() {}

    private static boolean on() {
        return PreferenceKeys.scamDevSwitch("hal_motion", true);
    }

    @Nullable
    private static <T> T get(CaptureResult r, CaptureResult.Key<T> key) {
        if (r == null || MISSING.contains(key.getName())) return null;
        try {
            return r.get(key);
        } catch (RuntimeException unknown) {
            MISSING.add(key.getName());
            return null;
        }
    }

    /** Whether the HAL reports motion or hand shake on this frame (any of its flags). */
    public static boolean moving(@Nullable CaptureResult r) {
        if (r == null || !on()) return false;
        int[] q = get(r, QTI_MOTION);
        byte[] o = get(r, OPLUS_SHAKE);
        int[] v = get(r, VIVO_MOTION);
        return moving(q != null && q.length > 0 ? q[0] : 0, o != null && o.length > 0 ? o[0] : 0, v != null && v.length > 0 ? v[0] : 0);
    }

    static boolean moving(int qtiMotion, int oplusShake, int vivoMotion) {
        return qtiMotion != 0 || oplusShake != 0 || vivoMotion != 0;
    }

    /** The HAL's motion-safe exposure (ns), 0 when it publishes none. */
    public static long safeExposureNs(@Nullable CaptureResult r) {
        if (r == null || !on()) return 0;
        long[] s = get(r, SAFE_EXPOSURE);
        return s != null && s.length > 0 && s[0] > 0 ? s[0] : 0;
    }

    /**
     * The ceiling for lengthening a bracketed frame's shutter: the HAL's safe exposure while it reports motion, else 0 (no
     * change). Never below the N shutter (the caller keeps that).
     */
    public static long motionCapNs(@Nullable CaptureResult r) {
        return moving(r) ? safeExposureNs(r) : 0;
    }

    /** The HAL's shake value of a frame for ranking frames of one burst (larger = more shaken); NaN when it has none. */
    public static float shake(@Nullable CaptureResult r) {
        if (r == null || !on()) return Float.NaN;
        float[] g = get(r, OPLUS_GYRO);
        if (g != null && g.length >= 3) return shake(g);
        float[] v = get(r, VIVO_GYRO);
        if (v != null && v.length >= 1 && !Float.isNaN(v[0])) return Math.abs(v[0]);
        return Float.NaN;
    }

    static float shake(float[] xyz) {
        return (float) Math.sqrt((double) xyz[0] * xyz[0] + (double) xyz[1] * xyz[1] + (double) xyz[2] * xyz[2]);
    }
}
