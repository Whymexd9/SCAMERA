package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.os.Build;
import android.os.SystemClock;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * P38: per-frame record of the preview stabilisation (vivo X300 Ultra: the preview stops being stabilised after a hybrid
 * shot). Every preview result keeps the requested and reported LENS_OPTICAL_STABILIZATION_MODE (OIS) /
 * CONTROL_VIDEO_STABILIZATION_MODE (EIS), the request's AE mode (the AE restore frame is AE OFF), the exposure, the crop region
 * (an EIS that moves the crop shows here), the OIS samples (STATISTICS_OIS_DATA_MODE: on by default on vivo when the camera
 * offers it, elsewhere nice_dev.txt "stab_trace_ois 1"), the hand shake from the phone's gyroscope over the frame and the vendor
 * result keys whose names mention OIS / EIS / stabilisation / gyro.
 *
 * What is logged (tag STAB_TRACE):
 * - at the session start: the camera's stabilisation capabilities, its vendor characteristic / request / result / session keys
 *   about stabilisation, and the first preview request's stabilisation keys with their values;
 * - every {@link #PERIOD_NS} a one-line summary (frames, OIS / EIS on, gyro shake, OIS travel, compensation, crop moves, the
 *   largest frame gap, time since the last shot), so the state long after a shot is visible too;
 * - per shot the frames from one second before to one second after its last event (flush, AE restore, re-arm; at most
 *   {@link #MAX_AFTER_NS} after the shot), with the events between them, and a before / after comparison.
 *
 * Reading it: hand shake (gyro) with OIS travel following it = OIS works; gyro shake with an OIS travel near 0 = OIS is off
 * whatever the result key says; OIS travel fine while the preview still jitters = the EIS (crop / warp) stopped.
 *
 * Cost per frame: a handful of metadata reads into a fixed ring, no allocation and no string formatting; the dumps copy under
 * the lock and format on their own thread, never on the camera callback thread. nice_dev.txt "stab_trace 0" turns it off.
 */
final class StabilizationTrace {
    static final String TAG = "STAB_TRACE";
    private static final int CAPACITY = 256;          // > 2.5 s at 60 fps, 5 s at 46 fps
    private static final long WINDOW_NS = 1_000_000_000L;
    /** The window ends one second after the last event, but never later than this after the shot (fits the ring). */
    private static final long MAX_AFTER_NS = 3_000_000_000L;
    /** One summary line this often while the preview runs. */
    static final long PERIOD_NS = 2_000_000_000L;
    private static final int MAX_VENDOR_KEYS = 12;
    /** Vendor keys may appear only after a few frames: keep looking this many frames while none is found. */
    private static final int VENDOR_SCAN_FRAMES = 30;

    static final class Frame {
        long arrivalNs, sensorTs, exposureNs;
        int reqOis = -1, reqVs = -1, reqAe = -1, resOis = -1, resVs = -1, iso = -1, intent = -1;
        int oisCount = -1;
        float oisMeanX, oisMeanY, oisRangeX, oisRangeY;
        /** Gyro over the frame interval: RMS rate (rad/s), angle swept (rad), samples; gyroN 0 = no gyro data. */
        float gyroRms, gyroAngle;
        int gyroN;
        int cropL = -1, cropT = -1, cropW = -1, cropH = -1;
        Object[] vendor;

        Frame copy() {
            Frame c = new Frame();
            c.arrivalNs = arrivalNs; c.sensorTs = sensorTs; c.exposureNs = exposureNs;
            c.reqOis = reqOis; c.reqVs = reqVs; c.reqAe = reqAe; c.resOis = resOis; c.resVs = resVs; c.iso = iso; c.intent = intent;
            c.oisCount = oisCount; c.oisMeanX = oisMeanX; c.oisMeanY = oisMeanY; c.oisRangeX = oisRangeX; c.oisRangeY = oisRangeY;
            c.gyroRms = gyroRms; c.gyroAngle = gyroAngle; c.gyroN = gyroN;
            c.cropL = cropL; c.cropT = cropT; c.cropW = cropW; c.cropH = cropH;
            c.vendor = vendor == null ? null : vendor.clone();   // the ring reuses its array
            return c;
        }

        float oisTravel() { return oisCount > 0 ? (float) Math.hypot(oisRangeX, oisRangeY) : -1f; }
    }

    /**
     * Aggregates of a run of frames (a period line, the before / after halves of a shot window). Plain Java, no Android.
     * Compensation = OIS travel per radian of hand rotation: it collapses when OIS stops while the hand keeps shaking.
     */
    static final class Stats {
        int frames, oisOn, vsOn, oisFrames, gyroFrames, cropMoves;
        double gyroRms, gyroAngle, oisTravel, maxGapMs;
        private long prevTs = -1;
        private int pl = -2, pt = -2, pw = -2, ph = -2;

        void add(Frame f) {
            frames++;
            if (f.resOis == 1) oisOn++;
            if (f.resVs > 0) vsOn++;
            if (f.gyroN > 0) { gyroFrames++; gyroRms += f.gyroRms; gyroAngle += f.gyroAngle; }
            if (f.oisCount > 0) { oisFrames++; oisTravel += f.oisTravel(); }
            if (prevTs > 0 && f.sensorTs > 0) maxGapMs = Math.max(maxGapMs, (f.sensorTs - prevTs) / 1e6);
            if (f.sensorTs > 0) prevTs = f.sensorTs;
            if (f.cropW > 0) {
                if (pl != -2 && (f.cropL != pl || f.cropT != pt || f.cropW != pw || f.cropH != ph)) cropMoves++;
                pl = f.cropL; pt = f.cropT; pw = f.cropW; ph = f.cropH;
            }
        }

        double meanGyroRms() { return gyroFrames == 0 ? -1 : gyroRms / gyroFrames; }
        double meanOisTravel() { return oisFrames == 0 ? -1 : oisTravel / oisFrames; }
        /** OIS travel (HAL units, usually pixels) per radian swept by the hand; -1 when unknown or the hand was still. */
        double compensation() {
            if (oisFrames == 0 || gyroFrames == 0 || gyroAngle / gyroFrames < 2e-4) return -1;
            return (oisTravel / oisFrames) / (gyroAngle / gyroFrames);
        }

        String line() {
            return String.format(Locale.US, "frames=%d oisOn=%d vsOn=%d gyroRms=%.4f rad/s gyroAngle=%.5f rad/frame oisTravel=%s comp=%s cropMoves=%d maxGap=%.1fms",
                    frames, oisOn, vsOn, meanGyroRms(), gyroFrames == 0 ? -1 : gyroAngle / gyroFrames,
                    oisFrames == 0 ? "n/a" : String.format(Locale.US, "%.2f", meanOisTravel()),
                    compensation() < 0 ? "n/a" : String.format(Locale.US, "%.0f", compensation()), cropMoves, maxGapMs);
        }

        /** A short reading of before vs after a shot, for the owner's log. */
        static String verdict(Stats before, Stats after) {
            if (before.frames == 0 || after.frames == 0) return "not enough frames";
            List<String> v = new ArrayList<>();
            if (before.oisOn > 0 && after.oisOn < after.frames / 2) v.add("OIS key off after the shot");
            if (before.vsOn > 0 && after.vsOn < after.frames / 2) v.add("EIS key off after the shot");
            double cb = before.compensation(), ca = after.compensation();
            if (cb > 0 && ca >= 0 && ca < cb * 0.4) v.add("OIS travel per hand rotation fell to " + Math.round(100 * ca / cb) + "% (OIS stopped compensating)");
            if (before.cropMoves > 0 && after.cropMoves == 0 && after.frames > 10) v.add("crop stopped moving (EIS crop frozen)");
            if (after.maxGapMs > 2.5 * Math.max(1, before.maxGapMs) && after.maxGapMs > 100) v.add(String.format(Locale.US, "preview gap %.0f ms", after.maxGapMs));
            return v.isEmpty() ? "no change seen in the keys / OIS data" : String.join("; ", v);
        }
    }

    /** The dumps are formatted and logged here, off the camera callback thread. */
    private static final java.util.concurrent.ExecutorService DUMP = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "STAB_TRACE-dump");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** Gyroscope accumulator between two preview frames (sensor thread writes, camera thread takes). */
    private final class GyroTap implements SensorEventListener {
        private double sumSq, angle;
        private int n;
        private long lastTs = -1;

        @Override public void onSensorChanged(SensorEvent e) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            double w = Math.sqrt(x * x + y * y + z * z);
            synchronized (this) {
                if (lastTs > 0) {
                    double dt = (e.timestamp - lastTs) / 1e9;
                    if (dt > 0 && dt < 0.1) angle += w * dt;
                }
                lastTs = e.timestamp;
                sumSq += w * w;
                n++;
            }
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        synchronized void take(Frame f) {
            f.gyroN = n;
            f.gyroRms = n == 0 ? 0 : (float) Math.sqrt(sumSq / n);
            f.gyroAngle = (float) angle;
            sumSq = 0; angle = 0; n = 0;
        }
    }

    private final Frame[] ring = new Frame[CAPACITY];
    private int head, size;
    private final List<long[]> events = new ArrayList<>();      // {arrivalNs, index into eventNames}
    private final List<String> eventNames = new ArrayList<>();
    private long shotNs = -1, lastEventNs = -1, lastShotNs = -1;
    private volatile boolean enabled = true;
    private List<CaptureResult.Key<?>> vendorKeys;
    private int vendorScans;
    private boolean firstRequestLogged;
    private String header = "";
    private Stats period = new Stats();
    private long periodStartNs = -1;
    private SensorManager sensors;
    private GyroTap gyro;

    StabilizationTrace() {
        for (int i = 0; i < CAPACITY; i++) ring[i] = new Frame();
    }

    /** A new preview session: forget the old frames, describe the camera's stabilisation capabilities. */
    synchronized void startSession(String cameraId, CameraCharacteristics chars) {
        head = 0; size = 0; shotNs = -1; lastEventNs = -1; events.clear(); eventNames.clear(); vendorKeys = null; vendorScans = 0;
        firstRequestLogged = false; period = new Stats(); periodStartNs = -1;
        enabled = PreferenceKeys.niceDevSwitch("stab_trace", true);
        if (!enabled || chars == null) { stopGyro(); return; }
        int[] ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        int[] vs = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        int[] oisData = Build.VERSION.SDK_INT >= 28 ? chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES) : null;
        header = "camera=" + cameraId + " availOis=" + Arrays.toString(ois) + " availVs=" + Arrays.toString(vs)
                + " availOisData=" + Arrays.toString(oisData);
        startGyro();
        final String h = header;
        final boolean hasGyro = gyro != null;
        final List<String> keys = new ArrayList<>();
        try {
            for (CameraCharacteristics.Key<?> k : chars.getKeys())
                if (isStabName(k.getName())) keys.add("char " + k.getName() + "=" + format(chars.get(k)));
            for (CaptureRequest.Key<?> k : chars.getAvailableCaptureRequestKeys())
                if (isStabName(k.getName())) keys.add("request key " + k.getName());
            for (CaptureResult.Key<?> k : chars.getAvailableCaptureResultKeys())
                if (isStabName(k.getName())) keys.add("result key " + k.getName());
            if (Build.VERSION.SDK_INT >= 28)
                for (CaptureRequest.Key<?> k : chars.getAvailableSessionKeys())
                    if (isStabName(k.getName())) keys.add("session key " + k.getName());
        } catch (RuntimeException ignored) { }
        submit(() -> {
            Log.i(TAG, "session " + h + " gyro=" + hasGyro);
            for (String k : keys) Log.i(TAG, "  " + k);
        });
    }

    /** The camera closed: stop listening to the gyroscope. */
    synchronized void stop() {
        stopGyro();
        shotNs = -1;
    }

    private void startGyro() {
        if (gyro != null) return;
        try {
            Context c = PhotonCamera.getAppContext();
            sensors = c == null ? null : (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            Sensor s = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (s == null) return;
            GyroTap tap = new GyroTap();
            if (sensors.registerListener(tap, s, SensorManager.SENSOR_DELAY_GAME)) gyro = tap;
        } catch (RuntimeException e) {
            gyro = null;
        }
    }

    private void stopGyro() {
        if (gyro != null && sensors != null) {
            try { sensors.unregisterListener(gyro); } catch (RuntimeException ignored) { }
        }
        gyro = null;
    }

    /** True when the preview should ask for OIS samples: vivo by default, elsewhere the dev switch "stab_trace_ois 1". */
    static boolean wantsOisSamples(CameraCharacteristics chars) {
        if (Build.VERSION.SDK_INT < 28 || chars == null || !PreferenceKeys.niceDevSwitch("stab_trace", true)
                || !PreferenceKeys.niceDevSwitch("stab_trace_ois", VivoNicePreview.supported())) return false;
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
        Runnable periodLine = null, firstRequest = null;
        synchronized (this) {
            if (vendorKeys == null || (vendorKeys.isEmpty() && vendorScans < VENDOR_SCAN_FRAMES)) {
                vendorKeys = findVendorKeys(result);
                vendorScans++;
            }
            if (!firstRequestLogged) {
                firstRequestLogged = true;
                firstRequest = describeRequest(request);
            }
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
            Rect crop = result.get(CaptureResult.SCALER_CROP_REGION);
            if (crop != null) { f.cropL = crop.left; f.cropT = crop.top; f.cropW = crop.width(); f.cropH = crop.height(); }
            else { f.cropL = f.cropT = f.cropW = f.cropH = -1; }
            if (gyro != null) gyro.take(f); else f.gyroN = 0;
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
            if (periodStartNs < 0) periodStartNs = now;
            period.add(f);
            if (now - periodStartNs >= PERIOD_NS) {
                final Stats s = period;
                final long sinceShot = lastShotNs < 0 ? -1 : (now - lastShotNs) / 1_000_000;
                final Object[] vendorNow = f.vendor == null ? null : f.vendor.clone();
                final int ro = f.reqOis, rv = f.reqVs, so = f.resOis, sv = f.resVs;
                periodLine = () -> {
                    StringBuilder b = new StringBuilder("period ").append(s.line())
                            .append(String.format(Locale.US, " last req[ois=%d vs=%d] res[ois=%d vs=%d]", ro, rv, so, sv))
                            .append(sinceShot < 0 ? " no shot yet" : " " + sinceShot + " ms after the last shot");
                    if (vendorNow != null) {
                        b.append(" vendor=");
                        for (Object v : vendorNow) b.append(format(v)).append('|');
                    }
                    Log.i(TAG, b.toString());
                };
                period = new Stats();
                periodStartNs = now;
            }
            dump = shotNs >= 0 && (now - Math.max(shotNs, lastEventNs) >= WINDOW_NS || now - shotNs >= MAX_AFTER_NS);
        }
        if (firstRequest != null) submit(firstRequest);
        if (periodLine != null) submit(periodLine);
        if (dump) dump();
    }

    /**
     * P54: the stabilisation keys of one request on one line ("ois=1 vivo.control.eis.config.enable=[5] ..."), for the requests
     * the shot path sets after the series (the re-armed repeating request): the session-start dump does not show them.
     */
    static String stabKeysLine(CaptureRequest request) {
        StringBuilder b = new StringBuilder();
        try {
            for (CaptureRequest.Key<?> k : request.getKeys()) {
                String name = k.getName();
                if (isStabName(name) || name.equals("android.control.captureIntent"))
                    b.append(b.length() == 0 ? "" : " ").append(name).append('=').append(format(request.get(k)));
            }
        } catch (RuntimeException ignored) { }
        return b.toString();
    }

    /** The stabilisation keys the preview actually asks for (standard and vendor), logged once per session. */
    private static Runnable describeRequest(CaptureRequest request) {
        final List<String> lines = new ArrayList<>();
        try {
            for (CaptureRequest.Key<?> k : request.getKeys()) {
                String name = k.getName();
                if (isStabName(name) || name.equals("android.control.captureIntent") || name.equals("android.control.mode"))
                    lines.add(name + "=" + format(request.get(k)));
            }
        } catch (RuntimeException ignored) { }
        return () -> {
            Log.i(TAG, "preview request stabilisation keys (" + lines.size() + "):");
            for (String l : lines) Log.i(TAG, "  " + l);
        };
    }

    /** The shutter submitted the series: the frames from one second before to one second after it are dumped. */
    synchronized void markShot(String what) {
        if (!enabled) return;
        enabled = PreferenceKeys.niceDevSwitch("stab_trace", true);
        if (!enabled) return;
        if (shotNs >= 0) { events.clear(); eventNames.clear(); }  // a shot that is still open: restart the window
        shotNs = SystemClock.elapsedRealtimeNanos();
        lastShotNs = shotNs;
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

    /** The stabilisation keys of a still request of the series, as an event of the shot window. */
    void seriesRequest(CaptureRequest request, int index) {
        if (!enabled || request == null) return;
        event(String.format(Locale.US, "series request %d ois=%d vs=%d ae=%d intent=%d", index,
                val(request.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE), -1),
                val(request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE), -1),
                val(request.get(CaptureRequest.CONTROL_AE_MODE), -1),
                val(request.get(CaptureRequest.CONTROL_CAPTURE_INTENT), -1)));
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
            StringBuilder h = new StringBuilder("shot window ").append(header).append(" gyro=").append(gyro != null).append(" vendorKeys=");
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
        submit(() -> format(shot, head0, frames, ev, evNames));
    }

    private static void submit(Runnable r) {
        try {
            DUMP.execute(r);
        } catch (RuntimeException rejected) {
            // Diagnostics only.
        }
    }

    private static void format(long shotNs, String head0, List<Frame> frames, List<long[]> events, List<String> eventNames) {
        StringBuilder out = new StringBuilder(16384);
        out.append(head0).append('\n');
        int ev = 0;
        Stats before = new Stats(), after = new Stats();
        long prevTs = -1;
        for (Frame f : frames) {
            while (ev < events.size() && events.get(ev)[0] <= f.arrivalNs) {
                long[] e = events.get(ev++);
                out.append(String.format(Locale.US, "  %+6d ms EVENT %s%n", (e[0] - shotNs) / 1_000_000, eventNames.get((int) e[1])));
            }
            out.append(String.format(Locale.US, "  %+6d ms dts=%5.1f req[ois=%d vs=%d ae=%d intent=%d] res[ois=%d vs=%d] exp=%.2fms iso=%d",
                    (f.arrivalNs - shotNs) / 1_000_000, prevTs < 0 || f.sensorTs < 0 ? 0f : (f.sensorTs - prevTs) / 1e6f,
                    f.reqOis, f.reqVs, f.reqAe, f.intent, f.resOis, f.resVs, f.exposureNs / 1e6, f.iso));
            if (f.gyroN > 0) out.append(String.format(Locale.US, " gyro=%.4f/%.5f(%d)", f.gyroRms, f.gyroAngle, f.gyroN));
            if (f.cropW > 0) out.append(String.format(Locale.US, " crop=%d,%d,%dx%d", f.cropL, f.cropT, f.cropW, f.cropH));
            if (f.oisCount >= 0) out.append(String.format(Locale.US, " oisN=%d mean=(%.2f,%.2f) range=(%.2f,%.2f)",
                    f.oisCount, f.oisMeanX, f.oisMeanY, f.oisRangeX, f.oisRangeY));
            if (f.vendor != null) {
                out.append(" vendor=");
                for (Object v : f.vendor) out.append(format(v)).append('|');
            }
            out.append('\n');
            prevTs = f.sensorTs;
            (f.arrivalNs > shotNs ? after : before).add(f);
        }
        while (ev < events.size()) {
            long[] e = events.get(ev++);
            out.append(String.format(Locale.US, "  %+6d ms EVENT %s%n", (e[0] - shotNs) / 1_000_000, eventNames.get((int) e[1])));
        }
        out.append("summary before ").append(before.line()).append('\n');
        out.append("summary after  ").append(after.line()).append('\n');
        out.append("verdict ").append(Stats.verdict(before, after));
        // One log call per line: logcat truncates long messages.
        for (String line : out.toString().split("\n")) Log.i(TAG, line);
    }

    /** Key names about stabilisation: OIS, EIS / video stabilisation, gyro, shake, warp. */
    static boolean isStabName(String name) {
        String lower = name.toLowerCase(Locale.US);
        return token(name, "ois") || token(name, "eis") || lower.contains("stabil") || lower.contains("gyro")
                || lower.contains("shake") || lower.contains("warp") || lower.contains("vstab");
    }

    /** "ois" / "eis" as a word of the key name ("vivo.ois.mode", "oisData", "enableOis", "EIS_LEVEL"), not inside "noise". */
    private static boolean token(String name, String word) {
        String lower = name.toLowerCase(Locale.US);
        for (int i = lower.indexOf(word); i >= 0; i = lower.indexOf(word, i + 1)) {
            if (i == 0 || !Character.isLetter(name.charAt(i - 1))) return true;
            // camelCase word start ("enableOis"); not "NOISE" / "noise"
            if (Character.isLowerCase(name.charAt(i - 1)) && Character.isUpperCase(name.charAt(i))) return true;
        }
        return false;
    }

    /** Result keys of the HAL whose names mention OIS / EIS / stabilisation (vendor diagnostics, e.g. vivo). */
    private static List<CaptureResult.Key<?>> findVendorKeys(CaptureResult result) {
        List<CaptureResult.Key<?>> keys = new ArrayList<>();
        try {
            for (CaptureResult.Key<?> k : result.getKeys()) {
                String name = k.getName();
                if (name.startsWith("android.")) continue;
                if (isStabName(name)) {
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
        if (v instanceof boolean[]) return Arrays.toString((boolean[]) v);
        if (v instanceof Object[]) return Arrays.deepToString((Object[]) v);
        return String.valueOf(v);
    }

    private static int val(Integer v, int fallback) { return v == null ? fallback : v; }
    private static long val(Long v, long fallback) { return v == null ? fallback : v; }
}
