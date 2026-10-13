package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.util.Range;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.Log;

/**
 * P87 The ISO and shutter range of the manual rulers (owner, 2026-10-11: «минимальные и максимальные значения ISO и выдержки
 * неверные, значения постоянно разные на одном модуле»). The vivo X200 Ultra declares ISO 1144..3200 and 1/23188..0.33 s to
 * apps (its tele 400..800) while its stock pro mode runs ISO 72..12800 (main, ultra-wide) / 50..3200 (tele) and up to 32 s,
 * and the ruler followed the declared range widened by whatever ISO the AE had shown so far (1144, then 5359, ...). Per module,
 * the widest of:
 * <ul>
 *   <li>the declared SENSOR_INFO ranges;</li>
 *   <li>the vendor's pro-mode range where it publishes one (vivo com.vivo.pro_isoMin / pro_isoMax; P98: OPPO
 *       com.oplus.pro.extension.iso.range, the Find X7 Ultra main 50..12800 against 100..6400 declared);</li>
 *   <li>P98: a shutter up to the vendor's exposure range (org.codeaurora.qcamera3.iso_exp_priority.exposure_time_range: X7U
 *       ultra-wide 91 s against 36 s declared; xiaomi.sensor.info.exposureTimeRange: 17 Ultra 36..180 s against 1 s), on a
 *       photographic stop; frames past the declared ceiling carry the Qualcomm FPS-limit keys (CaptureController);</li>
 *   <li>a shutter up to SENSOR_INFO_MAX_FRAME_DURATION when the declared ceiling is far below it (vivo: 0.33 s declared,
 *       frames up to 33 s), on a photographic stop;</li>
 *   <li>the ISO extremes the module's own AE was seen to use, kept per camera across sessions (the vivo tele: no vendor key,
 *       AE at ISO 50 in daylight), so the range grows to the real one once and then stays.</li>
 * </ul>
 */
public final class ExposureRanges {
    private static final String TAG = "ExposureRanges";
    private static final String PREFS = "scam_exposure_ranges";

    private ExposureRanges() {}

    /** ISO range from the parts; null parts are absent, learned 0 = none. */
    static Range<Integer> iso(@Nullable Range<Integer> declared, @Nullable Integer vendorMin, @Nullable Integer vendorMax,
                              int learnedMin, int learnedMax) {
        int lo = declared != null ? declared.getLower() : 100, hi = declared != null ? declared.getUpper() : 3200;
        if (vendorMin != null && vendorMax != null && vendorMin > 0 && vendorMax > vendorMin) {
            lo = Math.min(lo, vendorMin);
            hi = Math.max(hi, vendorMax);
        }
        if (learnedMin > 0) lo = Math.min(lo, learnedMin);
        if (learnedMax > 0) hi = Math.max(hi, learnedMax);
        lo = Math.max(1, lo);
        return new Range<>(lo, Math.max(lo, hi));
    }

    /** The photographic stops a frame-duration ceiling is rounded down to (s). */
    private static final double[] LONG_STOPS = {0.5, 1, 2, 4, 8, 10, 15, 20, 25, 30, 32};

    /** Shutter range: the declared one, its ceiling raised to the frame-duration limit when that is 4x or more above it. */
    static Range<Long> shutter(@Nullable Range<Long> declared, @Nullable Long maxFrameDurationNs) {
        return shutter(declared, maxFrameDurationNs, null);
    }

    /**
     * P98: also raised to the vendor's exposure ceiling ({@code vendorMaxNs}, may be null) when that is above the declared one,
     * on a stop. A vendor ceiling below the declared one (the X200 Ultra repeats its narrowed 0.33 s) changes nothing.
     */
    static Range<Long> shutter(@Nullable Range<Long> declared, @Nullable Long maxFrameDurationNs, @Nullable Long vendorMaxNs) {
        long lo = declared != null ? declared.getLower() : 100_000L, hi = declared != null ? declared.getUpper() : 1_000_000_000L;
        long ceiling = 0;
        if (maxFrameDurationNs != null && maxFrameDurationNs >= 4 * hi) ceiling = maxFrameDurationNs;
        if (vendorMaxNs != null && vendorMaxNs > hi) ceiling = Math.max(ceiling, vendorMaxNs);
        if (ceiling > 0) hi = Math.max(hi, stopBelow(ceiling));
        lo = Math.max(1, lo);
        return new Range<>(lo, Math.max(lo, hi));
    }

    private static long stopBelow(long ceilingNs) {
        long stop = 0;
        for (double s : LONG_STOPS) {
            long ns = Math.round(s * 1e9);
            if (ns <= ceilingNs) stop = ns;
        }
        return stop;
    }

    /** P98: the vendor exposure ceilings a camera publishes (Qualcomm priority range, Xiaomi sensor info); null when none. */
    @Nullable
    static Long vendorExposureMax(@Nullable CameraCharacteristics c) {
        if (c == null) return null;
        Long best = null;
        for (String name : new String[]{"org.codeaurora.qcamera3.iso_exp_priority.exposure_time_range", "xiaomi.sensor.info.exposureTimeRange"}) {
            long[] v = vendorLongs(c, name);
            if (v != null && v.length >= 2 && v[1] > 0 && (best == null || v[1] > best)) best = v[1];
        }
        return best;
    }

    /** P98: the OPPO pro mode's ISO range (com.oplus.pro.extension.iso.range, int32[2]); null when the camera has none. */
    @Nullable
    static int[] oppoProIso(@Nullable CameraCharacteristics c) {
        if (c == null) return null;
        int[] v = vendorInts(c, "com.oplus.pro.extension.iso.range");
        return v != null && v.length >= 2 && v[0] > 0 && v[1] > v[0] ? new int[]{v[0], v[1]} : null;
    }

    /** The module's ISO range for the ruler. */
    public static Range<Integer> iso(@Nullable CameraCharacteristics c, @Nullable String cameraId) {
        if (c == null) return iso(null, null, null, 0, 0);
        int[] learned = learned(cameraId);
        Integer vMin = vendorInt(c, "com.vivo.pro_isoMin"), vMax = vendorInt(c, "com.vivo.pro_isoMax");
        if (vMin == null || vMax == null) {
            int[] oppo = oppoProIso(c);
            if (oppo != null) { vMin = oppo[0]; vMax = oppo[1]; }
        }
        if (vMin == null || vMax == null) {
            int[] known = knownProRange(android.os.Build.DEVICE, focalLength(c));
            if (known != null) { vMin = known[0]; vMax = known[1]; }
        }
        Range<Integer> out = iso(c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE), vMin, vMax, learned[0], learned[1]);
        logOnce("iso " + cameraId, "camera " + cameraId + " ISO declared " + c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                + " pro " + vMin + ".." + vMax + " AE seen " + learned[0] + ".." + learned[1] + " -> " + out);
        return out;
    }

    /**
     * The stock pro mode's ISO range of a module that publishes no vendor range (owner's screenshots, 2026-10-11): the
     * vivo X200 Ultra (PD2454) tele, 22.48 mm, ISO 50..3200 (it declares 400..800). Null elsewhere.
     */
    @Nullable
    static int[] knownProRange(@Nullable String device, float focalMm) {
        if ("PD2454".equalsIgnoreCase(device) && Math.abs(focalMm - 22.48f) < 0.05f) return new int[]{50, 3200};
        return null;
    }

    private static float focalLength(CameraCharacteristics c) {
        float[] f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        return f != null && f.length > 0 ? f[0] : 0f;
    }

    private static final java.util.Map<String, String> LOGGED = new java.util.concurrent.ConcurrentHashMap<>();

    private static void logOnce(String key, String line) {
        if (!line.equals(LOGGED.put(key, line))) Log.i(TAG, line);
    }

    /** The module's shutter range for the ruler. */
    public static Range<Long> shutter(@Nullable CameraCharacteristics c) {
        if (c == null) return shutter(null, null);
        final Range<Long> declared = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        final Long maxFrame = c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
        final Long vendor = vendorExposureMax(c);
        final Range<Long> out = shutter(declared, maxFrame, vendor);
        logOnce("shutter " + System.identityHashCode(c), "shutter declared " + declared + " maxFrameDuration " + maxFrame
                + " vendor " + vendor + " -> " + out);
        return out;
    }

    @Nullable
    private static int[] vendorInts(CameraCharacteristics c, String name) {
        try {
            for (CameraCharacteristics.Key<?> k : c.getKeys()) {
                if (!name.equals(k.getName())) continue;
                Object v = c.get(k);
                if (v instanceof int[]) return (int[]) v;
            }
            return c.get(new CameraCharacteristics.Key<>(name, int[].class));
        } catch (RuntimeException ignored) {
            return null; // no such vendor tag on this camera
        }
    }

    @Nullable
    private static long[] vendorLongs(CameraCharacteristics c, String name) {
        try {
            for (CameraCharacteristics.Key<?> k : c.getKeys()) {
                if (!name.equals(k.getName())) continue;
                Object v = c.get(k);
                if (v instanceof long[]) return (long[]) v;
            }
            return c.get(new CameraCharacteristics.Key<>(name, long[].class));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static Integer vendorInt(CameraCharacteristics c, String name) {
        try {
            for (CameraCharacteristics.Key<?> k : c.getKeys()) {
                if (!name.equals(k.getName())) continue;
                Object v = c.get(k);
                if (v instanceof Integer) return (Integer) v;
                if (v instanceof int[] && ((int[]) v).length > 0) return ((int[]) v)[0];
            }
            Object v = c.get(new CameraCharacteristics.Key<>(name, int[].class));
            if (v instanceof int[] && ((int[]) v).length > 0) return ((int[]) v)[0];
        } catch (RuntimeException ignored) {
            // no such vendor tag on this camera
        }
        return null;
    }

    // ------------------------------------------------------------------ the AE's own ISO extremes, per camera

    private static final java.util.Map<String, int[]> LEARNED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, int[]> LOGGED_AE = new java.util.concurrent.ConcurrentHashMap<>();

    private static SharedPreferences prefs() {
        Context c = com.particlesdevs.photoncamera.app.ScameraApp.getAppContext();
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int[] learned(@Nullable String cameraId) {
        if (cameraId == null) return new int[2];
        return LEARNED.computeIfAbsent(cameraId, id -> {
            SharedPreferences p = prefs();
            return p == null ? new int[2] : new int[]{p.getInt(id + ".isoMin", 0), p.getInt(id + ".isoMax", 0)};
        });
    }

    /**
     * An ISO the camera's own AE used on {@code cameraId} (a viewfinder result with AE on): kept when it extends the module's
     * known extremes. Only AE results: a manual request's ISO says nothing about the sensor.
     */
    public static void observeAutoIso(@Nullable String cameraId, int iso) {
        if (cameraId == null || iso <= 0 || iso > 1_000_000) return;
        int[] m = learned(cameraId);
        boolean changed = false;
        synchronized (m) {
            if (m[0] == 0 || iso < m[0]) { m[0] = iso; changed = true; }
            if (iso > m[1]) { m[1] = iso; changed = true; }
        }
        if (!changed) return;
        final int[] logged = LOGGED_AE.computeIfAbsent(cameraId, id -> new int[2]);
        final boolean worth = logged[0] == 0 || m[0] < logged[0] * 0.9 || m[1] > logged[1] * 1.1;
        SharedPreferences p = prefs();
        if (p != null) p.edit().putInt(cameraId + ".isoMin", m[0]).putInt(cameraId + ".isoMax", m[1]).apply();
        if (worth) {
            logged[0] = m[0];
            logged[1] = m[1];
            Log.i(TAG, "camera " + cameraId + " AE ISO seen " + m[0] + ".." + m[1]);
        }
    }

    /** Tests. */
    static void resetForTest() { LEARNED.clear(); }
}
