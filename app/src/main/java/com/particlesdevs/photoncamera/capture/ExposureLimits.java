package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.Log;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * P27: what the HAL really honours for manual (AE off) requests of one camera and RAW size (one sensor mode; modes are never
 * switched). Learned from the shot's own results and the AE restore frame: a gain cap needs two results clamped to the same
 * ISO at an exact shutter (one can be pipeline latency); a later result delivered above the cap clears it. Until a cap
 * is learned nothing changes: the planners keep today's ranges. The owner's 'LONG: ISO 320/640' case: with the cap learned,
 * the Hybrid's bracketed frames take the extra exposure from the shutter instead of a gain the HAL does not give.
 * <p>
 * P212a: the caps are stored per key (SharedPreferences {@link #PREFS}, with Build.FINGERPRINT and the time), so the first shot
 * after an app start already plans against them (OPPO Find X7 Ultra log22 18:17:37: 'LONG #18 delivered x1.409 of N (plan
 * x2.000; ISO 1600/2270) -> dropped', the cap of the native mode was learned only 292 s later). Another firmware forgets them, and
 * a cap not confirmed for {@link #MAX_AGE_MS} is forgotten too.
 */
public final class ExposureLimits {
    private static final ConcurrentHashMap<String, ExposureLimits> MODES = new ConcurrentHashMap<>();
    static final String PREFS = "exposure_limits";
    /** P212a: a stored cap older than this (14 days since it was learned or last confirmed) is forgotten. */
    static final long MAX_AGE_MS = 14L * 24 * 3600 * 1000;
    /** P212a: a confirmed cap refreshes its stored time at most this often. */
    private static final long CONFIRM_EVERY_MS = 6L * 3600 * 1000;
    private static volatile SharedPreferences store;
    private static volatile String fingerprint = android.os.Build.FINGERPRINT;
    private static volatile LongSupplier clock = System::currentTimeMillis;

    public final String key;
    private int isoCap = Integer.MAX_VALUE, capCandidate, capVotes;
    private long shutterCap = Long.MAX_VALUE, shutterCandidate;
    private int shutterVotes;
    private long storedMs;

    ExposureLimits(String key) { this.key = key; }

    public static ExposureLimits of(String cameraId, int rawWidth, int rawHeight, boolean maximumResolution) {
        return MODES.computeIfAbsent(cameraId + '/' + rawWidth + 'x' + rawHeight + (maximumResolution ? "/max" : ""), k -> {
            final ExposureLimits limits = new ExposureLimits(k);
            limits.load();
            return limits;
        });
    }

    /** Learned manual ISO ceiling, {@link Integer#MAX_VALUE} while none is known. */
    public synchronized int isoCap() { return isoCap; }
    /** Learned manual shutter ceiling (ns), {@link Long#MAX_VALUE} while none is known. */
    public synchronized long shutterCap() { return shutterCap; }

    /** A manual (AE off) request and its result. True when the HAL gave what was asked (1.5 % / 2 ISO rounding allowed). */
    public synchronized boolean observeManual(CaptureRequest request, CaptureResult result) {
        if (request == null || result == null) return false;
        Integer ae = request.get(CaptureRequest.CONTROL_AE_MODE);
        if (ae == null || ae != CaptureRequest.CONTROL_AE_MODE_OFF) return true;
        Integer qi = request.get(CaptureRequest.SENSOR_SENSITIVITY), ri = result.get(CaptureResult.SENSOR_SENSITIVITY);
        Long qt = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME), rt = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (qi == null || ri == null || qt == null || rt == null || qi <= 0 || ri <= 0 || qt <= 0 || rt <= 0) return false;
        final boolean isoOk = Math.abs(ri - qi) <= Math.max(2, qi * 0.015);
        final boolean timeOk = Math.abs((double) rt / qt - 1) <= 0.015;
        final String before = toString();
        // the HAL gives more gain after all - only a delivered ISO above the cap proves it. A request at the cap itself proves
        // nothing (P124, OPPO Find X7 Ultra log 2026-10-13 22:37: ISO 6400 honoured cleared the cap 6400 and the next 9600 learned
        // it again, the «Движение» shutter swung 25 <-> 33 ms with it), nor does one just above it answered at the cap within the
        // rounding (P212a, log28 23:00:45.162: a request of 1601-1624 answered with 1600 cleared the cap 1600, relearned 7 s
        // later; with the cap in the native AE every such clear would re-split upward and restart the rebuilds)
        // (beyond the rounding: a result that jitters a unit over the cap proves nothing either)
        if (isoCap != Integer.MAX_VALUE && ri > isoCap + Math.max(2, isoCap * 0.015)) isoCap = Integer.MAX_VALUE;
        if (isoOk) {
            if (ri.equals(capCandidate)) capVotes = 0;
        } else if (ri < qi && timeOk) {
            if (ri == isoCap) confirmed(); // P212a: clamped at the known cap again (the native AE's once-a-session probe)
            capVotes = ri == capCandidate ? capVotes + 1 : 1;
            capCandidate = ri;
            if (capVotes >= 2) isoCap = Math.min(isoCap, ri);
        }
        // P212a: the shutter cap the same way - a delivered shutter beyond the cap and the rounding clears it, a request above it
        // answered at the cap within the 1.5 % does not
        if (shutterCap != Long.MAX_VALUE && rt > shutterCap * 1.015) shutterCap = Long.MAX_VALUE;
        if (!timeOk && rt < qt) {
            shutterVotes = Math.abs((double) rt / Math.max(1, shutterCandidate) - 1) <= 0.015 ? shutterVotes + 1 : 1;
            shutterCandidate = rt;
            if (shutterVotes >= 2) shutterCap = Math.min(shutterCap, rt);
        }
        final String after = toString();
        if (!before.equals(after)) {
            Log.i("SCAM_CAPTURE", "honoured exposure " + after);
            save();
        }
        return isoOk && timeOk;
    }

    /** P212a: the stored caps of this key, unless another firmware or older than MAX_AGE_MS (then the stored line goes). */
    private synchronized void load() {
        final SharedPreferences s = store();
        if (s == null) return;
        try {
            final String line = s.getString(key, null);
            if (line == null) return;
            final String[] f = line.split(";", -1);
            final long now = clock.getAsLong();
            final long ms = f.length == 4 ? Long.parseLong(f[3]) : 0L;
            final String stale = f.length != 4 ? "unreadable" : !f[0].equals(String.valueOf(fingerprint)) ? "another firmware"
                    : ms <= 0 || now - ms > MAX_AGE_MS || ms > now + 60_000L ? "expired" : null;
            if (stale != null) {
                s.edit().remove(key).apply();
                Log.i("SCAM_CAPTURE", "honoured exposure " + key + ": stored limits dropped (" + stale + ")");
                return;
            }
            final int iso = Integer.parseInt(f[1]);
            final long shutter = Long.parseLong(f[2]);
            if (iso > 0) isoCap = iso;
            if (shutter > 0) shutterCap = shutter;
            capCandidate = iso > 0 ? iso : 0;
            storedMs = ms;
            Log.i("SCAM_CAPTURE", "honoured exposure " + this + " (stored " + (now - ms) / 60_000L + " min ago)");
        } catch (RuntimeException e) {
            s.edit().remove(key).apply();
        }
    }

    /** P212a: the caps as they are now ("fingerprint;iso;shutter;time", 0 = none); no line when nothing is learned. */
    private void save() {
        final SharedPreferences s = store();
        if (s == null) return;
        if (isoCap == Integer.MAX_VALUE && shutterCap == Long.MAX_VALUE) {
            storedMs = 0;
            s.edit().remove(key).apply();
            return;
        }
        storedMs = clock.getAsLong();
        s.edit().putString(key, fingerprint + ";" + (isoCap == Integer.MAX_VALUE ? 0 : isoCap) + ";"
                + (shutterCap == Long.MAX_VALUE ? 0 : shutterCap) + ";" + storedMs).apply();
    }

    private void confirmed() {
        if (clock.getAsLong() - storedMs >= CONFIRM_EVERY_MS) save();
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

    @Override public synchronized String toString() {
        return String.format(Locale.ROOT, "%s ISO cap %s shutter cap %s", key,
                isoCap == Integer.MAX_VALUE ? "-" : String.valueOf(isoCap), shutterCap == Long.MAX_VALUE ? "-" : shutterCap + " ns");
    }

    /** Tests: forget every learned limit (in memory; the store hook goes back to the app's). */
    static void resetForTest() {
        MODES.clear();
        store = null;
        fingerprint = android.os.Build.FINGERPRINT;
        clock = System::currentTimeMillis;
    }

    /** Tests: a store, firmware and clock in place of the app's (a new process: {@link #resetForTest} first). */
    static void useStoreForTest(SharedPreferences prefs, String firmware, LongSupplier now) {
        store = prefs;
        fingerprint = firmware;
        clock = now;
    }
}
