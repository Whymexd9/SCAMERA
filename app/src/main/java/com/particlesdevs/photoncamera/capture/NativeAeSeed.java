package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P212b (M8): where the own AE of an OPPO native FULLRES session starts, per camera (never the global last preview exposure, which
 * any result of any camera overwrites). The P192 start was the plain session's HAL exposure, else 40 ms ISO 100: 20 of 49 sessions
 * of the owner's unit started from that default (cold starts straight into FULLRES), 3.2-3.4 EV over in daylight and 4-10 s to
 * settle, 4-6 EV under at night; the HAL starts sat 0.9 / 1.7 / 2.4 EV under where the native AE settles (its own law, log17
 * 312 -> 841, log21 967 -> 1240, log22 977 -> 1091). The first of these that is there:
 * <ol>
 * <li>the newer of
 * <ul><li>the last settled native exposure of the camera, stored ({@link #PREFS}, survives the process; at most
 * {@link #SETTLED_MAX_AGE_MS} old; moved by the change of the user's compensation), and</li>
 * <li>a plain-session RAW sample of the camera at most {@link #PLAIN_MAX_AGE_MS} old, metered as the native AE meters
 * (NativeRawExposure.meter2, block 1) and taken to the setpoint by the native law (multiplier) over the native / plain ratio
 * {@link #K};</li></ul></li>
 * <li>the camera's own last plain HAL exposure;</li>
 * <li>none: the constructor's default.</li>
 * </ol>
 * The split (the ISO cap, the flicker) is NativeRawExposure.seed's.
 */
final class NativeAeSeed {
    private NativeAeSeed() {}

    static final long PLAIN_MAX_AGE_MS = 5_000L, SETTLED_MAX_AGE_MS = 10 * 60_000L;
    /** A settled exposure is stored after this long settled, at most this often. */
    static final long SETTLED_HOLD_MS = 1_000L, STORE_EVERY_MS = 5_000L;
    /**
     * The native / plain ratio of the frames' level per exposure: about 1 on the PHY110 (probe RAWs, plain bin12 p50 0.0310 at
     * 1.090 ms ISO 101 vs native p50 0.0366 at 1.093 ms ISO 100, 0.98 normalised by the lux tag). Not learned (M8 made it
     * optional; a learned one would be clamped to [0.7, 1.4]).
     */
    static final double K = 1.0;
    static final String PREFS = "native_ae_seed";

    /** A start: the exposure (ISO-100 ns, 0 = the constructor's default) and where it came from (for the log). */
    static final class Seed {
        final double total;
        final String source;

        Seed(double total, String source) {
            this.total = total;
            this.source = source;
        }
    }

    /** Per camera: {exposure ISO-100 ns, p50, p98, elapsedRealtime ms} of the last plain sample; {exposure, ms} of its HAL. */
    private static final Map<String, double[]> PLAIN = new ConcurrentHashMap<>();
    private static final Map<String, double[]> PLAIN_HAL = new ConcurrentHashMap<>();
    private static volatile SharedPreferences store;

    /** A plain frame of the camera, AE converged, metered by the native meter ({@code total}: its exposure, ISO-100 ns). */
    static void plainSample(@Nullable String camera, float p50, float p98, double total, long nowMs) {
        if (camera == null || !(p50 >= 0f) || !(p98 >= 0f) || !(total > 0)) return;
        PLAIN.put(camera, new double[]{total, p50, p98, nowMs});
    }

    /** The camera's plain session result (its HAL's exposure). */
    static void plainHal(@Nullable String camera, long exposureNs, int iso, long nowMs) {
        if (camera == null || exposureNs <= 0 || iso <= 0) return;
        PLAIN_HAL.put(camera, new double[]{(double) exposureNs * iso / 100.0, nowMs});
    }

    /** The camera's native exposure settled ({@code total} ISO-100 ns delivered, at compensation {@code ev}), wall clock ms. */
    static void storeSettled(@Nullable String camera, double total, float ev, long wallMs) {
        final SharedPreferences s = store();
        if (camera == null || s == null || !(total > 0)) return;
        s.edit().putString(camera, String.format(Locale.ROOT, "%.1f;%.3f;%d", total, ev, wallMs)).apply();
    }

    /** The start of the camera's native AE at compensation {@code ev}: elapsedRealtime {@code nowMs}, wall clock {@code wallMs}. */
    static Seed pick(@Nullable String camera, float ev, long nowMs, long wallMs) {
        if (camera == null) return new Seed(0, "default");
        double settled = 0;
        long settledAt = Long.MIN_VALUE;
        final SharedPreferences s = store();
        if (s != null) {
            try {
                final String line = s.getString(camera, null);
                final String[] f = line == null ? null : line.split(";", -1);
                if (f != null && f.length == 3) {
                    final double total = Double.parseDouble(f[0]);
                    final float then = Float.parseFloat(f[1]);
                    final long at = Long.parseLong(f[2]);
                    if (total > 0 && at <= wallMs + 60_000L && wallMs - at <= SETTLED_MAX_AGE_MS) {
                        settled = total * Math.pow(2, ev - then);
                        settledAt = at;
                    }
                }
            } catch (RuntimeException ignored) {
                settled = 0;
            }
        }
        double plain = 0;
        long plainAt = Long.MIN_VALUE;
        final double[] p = PLAIN.get(camera);
        if (p != null && nowMs - (long) p[3] >= 0 && nowMs - (long) p[3] <= PLAIN_MAX_AGE_MS) {
            plain = p[0] * NativeRawExposure.multiplier((float) p[1], (float) p[2], ev) / K;
            plainAt = wallMs - (nowMs - (long) p[3]);
        }
        if (plain > 0 && (settled <= 0 || plainAt > settledAt))
            return new Seed(plain, String.format(Locale.ROOT, "plain RAW %.1f s ago: median %.4f p98 %.3f", (nowMs - p[3]) / 1000.0, p[1], p[2]));
        if (settled > 0)
            return new Seed(settled, String.format(Locale.ROOT, "settled %.0f s ago", (wallMs - settledAt) / 1000.0));
        final double[] hal = PLAIN_HAL.get(camera);
        if (hal != null && hal[0] > 0) return new Seed(hal[0], "the camera's plain HAL exposure");
        return new Seed(0, "default");
    }

    @Nullable
    private static SharedPreferences store() {
        SharedPreferences s = store;
        if (s != null) return s;
        final Context context = com.particlesdevs.photoncamera.app.ScameraApp.getAppContext();
        if (context == null) return null;
        try {
            s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (RuntimeException e) {
            return null;
        }
        store = s;
        return s;
    }

    /** Tests: no samples, the app's store. */
    static void resetForTest() {
        PLAIN.clear();
        PLAIN_HAL.clear();
        store = null;
    }

    static void useStoreForTest(SharedPreferences prefs) {
        store = prefs;
    }
}
