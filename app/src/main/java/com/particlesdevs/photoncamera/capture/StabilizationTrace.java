package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.os.Build;
import android.os.SystemClock;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * P38: per-frame record of the preview stabilisation around a shot (vivo X300 Ultra: the preview stops being
 * stabilised after a hybrid shot). Every preview result keeps the requested and reported
 * LENS_OPTICAL_STABILIZATION_MODE / CONTROL_VIDEO_STABILIZATION_MODE, the request's AE mode (the AE restore frame is
 * AE OFF), the exposure, the OIS samples (when STATISTICS_OIS_DATA_MODE is on, nice_dev.txt "stab_trace_ois 1") and the
 * vendor result keys whose names mention OIS / EIS / stabilisation. A shot marks the timeline; one second after the shot
 * and after its last event (flush, AE restore, re-arm; at most {@link #MAX_AFTER_NS} after the shot) the frames from one
 * second before the shot on are written to the log (tag STAB_TRACE), together with the shot's events.
 *
 * Cost per frame: a handful of metadata reads into a fixed ring, no allocation and no string formatting; the dump copies
 * the window under the lock and formats it on its own thread, never on the camera callback thread.
 * nice_dev.txt "stab_trace 0" turns it off.
 */
final class StabilizationTrace {
    static final String TAG = "STAB_TRACE";
    private static final int CAPACITY = 256;          // > 2.5 s at 60 fps, 5 s at 46 fps
    private static final long WINDOW_NS = 1_000_000_000L;
    /** The window ends one second after the last event, but never later than this after the shot (fits the ring). */
    private static final long MAX_AFTER_NS = 3_000_000_000L;
    private static final int MAX_VENDOR_KEYS = 8;

    private static final class Frame {
        long arrivalNs, sensorTs, exposureNs;
        int reqOis = -1, reqVs = -1, reqAe = -1, resOis = -1, resVs = -1, iso = -1, intent = -1;
        int oisCount = -1;
        float oisMeanX, oisMeanY, oisRangeX, oisRangeY;
        Object[] vendor;

        Frame copy() {
            Frame c = new Frame();
            c.arrivalNs = arrivalNs; c.sensorTs = sensorTs; c.exposureNs = exposureNs;
            c.reqOis = reqOis; c.reqVs = reqVs; c.reqAe = reqAe; c.resOis = resOis; c.resVs = resVs; c.iso = iso; c.intent = intent;
            c.oisCount = oisCount; c.oisMeanX = oisMeanX; c.oisMeanY = oisMeanY; c.oisRangeX = oisRangeX; c.oisRangeY = oisRangeY;
            c.vendor = vendor == null ? null : vendor.clone();   // the ring reuses its array
            return c;
        }
    }

    /** The dumps are formatted and logged here, off the camera callback thread. */
    private static final java.util.concurrent.ExecutorService DUMP = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "STAB_TRACE-dump");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private final Frame[] ring = new Frame[CAPACITY];
    private int head, size;
    private final List<long[]> events = new ArrayList<>();      // {arrivalNs, index into eventNames}
    private final List<String> eventNames = new ArrayList<>();
    private long shotNs = -1, lastEventNs = -1;
    private volatile boolean enabled = true;
    private List<CaptureResult.Key<?>> vendorKeys;
    private String header = "";

    StabilizationTrace() {
        for (int i = 0; i < CAPACITY; i++) ring[i] = new Frame();
    }

    /** A new preview session: forget the old frames, describe the camera's stabilisation capabilities. */
    synchronized void startSession(String cameraId, CameraCharacteristics chars) {
        head = 0; size = 0; shotNs = -1; lastEventNs = -1; events.clear(); eventNames.clear(); vendorKeys = null;
        enabled = PreferenceKeys.niceDevSwitch("stab_trace", true);
        if (!enabled || chars == null) return;
        int[] ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        int[] vs = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        int[] oisData = Build.VERSION.SDK_INT >= 28 ? chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES) : null;
        header = "camera=" + cameraId + " availOis=" + Arrays.toString(ois) + " availVs=" + Arrays.toString(vs)
                + " availOisData=" + Arrays.toString(oisData);
    }

    /** True when the preview should ask for OIS samples (dev switch "stab_trace_ois 1" and the camera supports them). */
    static boolean wantsOisSamples(CameraCharacteristics chars) {
        if (Build.VERSION.SDK_INT < 28 || chars == null || !PreferenceKeys.niceDevSwitch("stab_trace_ois", false)) return false;
        int[] modes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES);
        if (modes == null) return false;
        for (int m : modes) if (m == CaptureRequest.STATISTICS_OIS_DATA_MODE_ON) return true;
        return false;
    }

    /** One preview result (the repeating preview and its one-shot frames, e.g. the AE restore). */
    void record(CaptureRequest request, CaptureResult result) {
        if (!enabled) return;
        long now = SystemClock.elapsedRealtimeNanos();
        boolean dump;
        synchronized (this) {
            if (vendorKeys == null) vendorKeys = findVendorKeys(result);
            Frame f = ring[head];
            head = (head + 1) % CAPACITY;
            if (size < CAPACITY) size++;
            f.arrivalNs = now;
            f.sensorTs = val(result.get(CaptureResult.SENSOR_TIMESTAMP), -1L);
            f.exposureNs = val(result.get(CaptureResult.SENSOR_EXPOSURE_TIME), -1L);
            f.iso = val(result.get(CaptureResult.SENSOR_SENSITIVITY), -1);
            f.reqOis = val(request.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE), -1);
            f.reqVs = val(request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE), -1);
            f.reqAe = val(request.get(CaptureRequest.CONTROL_AE_MODE), -1);
            f.intent = val(request.get(CaptureRequest.CONTROL_CAPTURE_INTENT), -1);
            f.resOis = val(result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE), -1);
            f.resVs = val(result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE), -1);
            f.oisCount = -1;
            if (Build.VERSION.SDK_INT >= 31) {
                android.hardware.camera2.params.OisSample[] samples = result.get(CaptureResult.STATISTICS_OIS_SAMPLES);
                if (samples != null) {
                    f.oisCount = samples.length;
                    float sx = 0, sy = 0, minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                    for (android.hardware.camera2.params.OisSample s : samples) {
                        float x = s.getXshift(), y = s.getYshift();
                        sx += x; sy += y;
                        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    }
                    int n = Math.max(1, samples.length);
                    f.oisMeanX = sx / n; f.oisMeanY = sy / n;
                    f.oisRangeX = samples.length == 0 ? 0 : maxX - minX;
                    f.oisRangeY = samples.length == 0 ? 0 : maxY - minY;
                }
            }
            if (vendorKeys.isEmpty()) f.vendor = null;
            else {
                if (f.vendor == null || f.vendor.length != vendorKeys.size()) f.vendor = new Object[vendorKeys.size()];
                for (int i = 0; i < vendorKeys.size(); i++) {
                    try { f.vendor[i] = result.get(vendorKeys.get(i)); } catch (RuntimeException e) { f.vendor[i] = null; }
                }
            }
            dump = shotNs >= 0 && (now - Math.max(shotNs, lastEventNs) >= WINDOW_NS || now - shotNs >= MAX_AFTER_NS);
        }
        if (dump) dump();
    }

    /** The shutter submitted the series: the frames from one second before to one second after it are dumped. */
    synchronized void markShot(String what) {
        if (!enabled) return;
        enabled = PreferenceKeys.niceDevSwitch("stab_trace", true);
        if (!enabled) return;
        if (shotNs >= 0) { events.clear(); eventNames.clear(); }  // a shot that is still open: restart the window
        shotNs = SystemClock.elapsedRealtimeNanos();
        lastEventNs = shotNs;
        events.add(new long[]{shotNs, eventNames.size()});
        eventNames.add(what);
    }

    /** An event inside the shot window (flush, AE restore frame, re-arm), printed between the frames. */
    synchronized void event(String what) {
        if (!enabled || shotNs < 0) return;
        lastEventNs = SystemClock.elapsedRealtimeNanos();
        events.add(new long[]{lastEventNs, eventNames.size()});
        eventNames.add(what);
    }

    /** Copies the shot window under the lock (cheap) and formats it on the dump thread. */
    private void dump() {
        final List<Frame> frames = new ArrayList<>();
        final List<long[]> ev;
        final List<String> evNames;
        final long shot;
        final String head0;
        synchronized (this) {
            if (shotNs < 0) return;
            shot = shotNs;
            long from = shotNs - WINDOW_NS;
            StringBuilder h = new StringBuilder("shot window ").append(header).append(" vendorKeys=");
            if (vendorKeys != null) for (CaptureResult.Key<?> k : vendorKeys) h.append(k.getName()).append(',');
            head0 = h.toString();
            int start = (head - size + CAPACITY) % CAPACITY;
            for (int i = 0; i < size; i++) {
                Frame f = ring[(start + i) % CAPACITY];
                if (f.arrivalNs >= from) frames.add(f.copy());
            }
            ev = new ArrayList<>(events);
            evNames = new ArrayList<>(eventNames);
            shotNs = -1;
            lastEventNs = -1;
            events.clear();
            eventNames.clear();
        }
        try {
            DUMP.execute(() -> format(shot, head0, frames, ev, evNames));
        } catch (RuntimeException rejected) {
            // Diagnostics only.
        }
    }

    private static void format(long shotNs, String head0, List<Frame> frames, List<long[]> events, List<String> eventNames) {
        StringBuilder out = new StringBuilder(16384);
        out.append(head0).append('\n');
        int ev = 0;
        int count = 0, oisOn = 0, vsOn = 0, after = 0, oisOnAfter = 0, vsOnAfter = 0;
        long prevTs = -1;
        for (Frame f : frames) {
            while (ev < events.size() && events.get(ev)[0] <= f.arrivalNs) {
                long[] e = events.get(ev++);
                out.append(String.format(Locale.US, "  %+6d ms EVENT %s%n", (e[0] - shotNs) / 1_000_000, eventNames.get((int) e[1])));
            }
            out.append(String.format(Locale.US, "  %+6d ms dts=%5.1f req[ois=%d vs=%d ae=%d intent=%d] res[ois=%d vs=%d] exp=%.2fms iso=%d",
                    (f.arrivalNs - shotNs) / 1_000_000, prevTs < 0 || f.sensorTs < 0 ? 0f : (f.sensorTs - prevTs) / 1e6f,
                    f.reqOis, f.reqVs, f.reqAe, f.intent, f.resOis, f.resVs, f.exposureNs / 1e6, f.iso));
            if (f.oisCount >= 0) out.append(String.format(Locale.US, " oisN=%d mean=(%.2f,%.2f) range=(%.2f,%.2f)",
                    f.oisCount, f.oisMeanX, f.oisMeanY, f.oisRangeX, f.oisRangeY));
            if (f.vendor != null) {
                out.append(" vendor=");
                for (Object v : f.vendor) out.append(format(v)).append('|');
            }
            out.append('\n');
            prevTs = f.sensorTs;
            count++;
            if (f.resOis == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON) oisOn++;
            if (f.resVs > 0) vsOn++;
            if (f.arrivalNs > shotNs) {
                after++;
                if (f.resOis == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON) oisOnAfter++;
                if (f.resVs > 0) vsOnAfter++;
            }
        }
        while (ev < events.size()) {
            long[] e = events.get(ev++);
            out.append(String.format(Locale.US, "  %+6d ms EVENT %s%n", (e[0] - shotNs) / 1_000_000, eventNames.get((int) e[1])));
        }
        out.append(String.format(Locale.US, "summary frames=%d oisOn=%d vsOn=%d | after shot frames=%d oisOn=%d vsOn=%d",
                count, oisOn, vsOn, after, oisOnAfter, vsOnAfter));
        // One log call per line: logcat truncates long messages.
        for (String line : out.toString().split("\n")) Log.i(TAG, line);
    }

    /** Result keys of the HAL whose names mention OIS / EIS / stabilisation (vendor diagnostics, e.g. vivo). */
    private static List<CaptureResult.Key<?>> findVendorKeys(CaptureResult result) {
        List<CaptureResult.Key<?>> keys = new ArrayList<>();
        try {
            for (CaptureResult.Key<?> k : result.getKeys()) {
                String name = k.getName();
                if (name.startsWith("android.")) continue;
                String lower = name.toLowerCase(Locale.US);
                if (lower.contains("ois") || lower.contains("eis") || lower.contains("stabil") || lower.contains("gyro")) {
                    keys.add(k);
                    if (keys.size() >= MAX_VENDOR_KEYS) break;
                }
            }
        } catch (RuntimeException ignored) { }
        return keys;
    }

    private static String format(Object v) {
        String s = formatFull(v);
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    private static String formatFull(Object v) {
        if (v == null) return "-";
        if (v instanceof int[]) return Arrays.toString((int[]) v);
        if (v instanceof long[]) return Arrays.toString((long[]) v);
        if (v instanceof float[]) return Arrays.toString((float[]) v);
        if (v instanceof byte[]) return Arrays.toString((byte[]) v);
        if (v instanceof double[]) return Arrays.toString((double[]) v);
        if (v instanceof Object[]) return Arrays.deepToString((Object[]) v);
        return String.valueOf(v);
    }

    private static int val(Integer v, int fallback) { return v == null ? fallback : v; }
    private static long val(Long v, long fallback) { return v == null ? fallback : v; }
}
