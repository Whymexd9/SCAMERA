package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureRequest;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The vivo SDK lease of FULLRES: VivoCameraManager values 40 (SDK RAW) = 1 and 33 (VCF) = 2 while our camera is open, the
 * values found before given back when it closes.
 * <p>
 * P179: the lease is journaled on disk before it is taken (owner pid, the values found) and the journal cleared once they
 * are back. A process killed while it holds the lease (LOW_MEMORY, a crash: no finally runs) leaves the journal; the next
 * start of the camera ({@link #recoverStale}) gives the values back - only while they are still the ones we set and no
 * camera is in use by anyone (another client's own lease is never reset), else the journal is just dropped.
 */
public final class VivoSdkRawSession implements AutoCloseable {
    interface Provider {
        int get(int key) throws Exception;
        void set(int key, int value) throws Exception;
        void settle();
    }

    /** P179: where the lease is written down: {pid, the SDK value found, the VCF value found}, or null. */
    interface Journal {
        @androidx.annotation.Nullable int[] read();
        void write(int pid, int sdk, int vcf);
        void clear();
    }

    static final int SDK_KEY = 40, VCF_KEY = 33, SDK_OURS = 1, VCF_OURS = 2;
    private static final String TAG = "VivoSdkRawSession";

    private static final AtomicLong CAPTURE_ID = new AtomicLong();
    private final Provider provider;
    @androidx.annotation.Nullable private final Journal journal;
    private final int previousSdk, previousVcf;
    private volatile boolean closed;
    private boolean restored;

    private VivoSdkRawSession(Provider provider, @androidx.annotation.Nullable Journal journal, int sdk, int vcf) {
        this.provider = provider;
        this.journal = journal;
        previousSdk = sdk;
        previousVcf = vcf;
    }

    static Provider systemProvider() throws Exception {
        Class<?> api = Class.forName("android.hardware.vivocamera.VivoCameraManager");
        Object manager = api.getMethod("getInstance").invoke(null);
        Method get = api.getMethod("getInfo", int.class);
        Method set = api.getMethod("setValue", int.class, int.class);
        return new Provider() {
            @Override public int get(int key) throws Exception { return (Integer) get.invoke(manager, key); }
            @Override public void set(int key, int value) throws Exception { set.invoke(manager, key, value); }
            @Override public void settle() { SystemClock.sleep(100); }
        };
    }

    public static VivoSdkRawSession open() throws Exception {
        return open(systemProvider(), PrefsJournal.get(), android.os.Process.myPid());
    }

    static VivoSdkRawSession open(Provider provider) throws Exception {
        return Lease.open(provider, null, 0);
    }

    static VivoSdkRawSession open(Provider provider, @androidx.annotation.Nullable Journal journal, int pid) throws Exception {
        return Lease.open(provider, journal, pid);
    }

    /**
     * The lease's steps as statics of their own class (a static mock of the session in the tests leaves them real).
     */
    static final class Lease {
        private Lease() {}

        static VivoSdkRawSession open(Provider provider, @androidx.annotation.Nullable Journal journal, int pid) throws Exception {
            int sdk = provider.get(SDK_KEY), vcf = provider.get(VCF_KEY);
            int[] stale = journal == null ? null : journal.read();
            if (stale != null && stale[0] != pid) {
                // a lease of a process that died: the values it found are the ones to give back, while ours are still set
                if (sdk == SDK_OURS && vcf == VCF_OURS) {
                    sdk = stale[1];
                    vcf = stale[2];
                }
                journal.clear();
            }
            if (journal != null) journal.write(pid, sdk, vcf);
            VivoSdkRawSession session = new VivoSdkRawSession(provider, journal, sdk, vcf);
            try {
                provider.set(VCF_KEY, VCF_OURS);
                provider.set(SDK_KEY, SDK_OURS);
                provider.settle();
                if (provider.get(SDK_KEY) != SDK_OURS || provider.get(VCF_KEY) != VCF_OURS)
                    throw new IllegalStateException("Vivo SDK RAW properties were not applied");
                return session;
            } catch (Exception failure) {
                try { session.close(); } catch (Exception restore) { failure.addSuppressed(restore); }
                throw failure;
            }
        }

        static void give(Provider provider, int sdk, int vcf) throws Exception {
            Exception failure = null;
            try { provider.set(VCF_KEY, vcf); } catch (Exception e) { failure = e; }
            try { provider.set(SDK_KEY, sdk); } catch (Exception e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
            provider.settle();
            if (failure != null) throw failure;
            if (provider.get(SDK_KEY) != sdk || provider.get(VCF_KEY) != vcf)
                throw new IllegalStateException("Vivo SDK RAW properties were not restored");
        }

        static String recoverStale(Provider provider, Journal journal, int pid, java.util.function.BooleanSupplier camerasIdle) {
            int[] stale = journal.read();
            if (stale == null || stale[0] == pid) return "none";
            try {
                if (provider.get(SDK_KEY) != SDK_OURS || provider.get(VCF_KEY) != VCF_OURS) {
                    journal.clear();
                    return "not ours";
                }
                if (!camerasIdle.getAsBoolean()) return "busy";
                give(provider, stale[1], stale[2]);
                journal.clear();
                return "restored";
            } catch (Exception e) {
                return "failed: " + e;
            }
        }
    }

    public void apply(CaptureRequest.Builder builder, boolean capture, long previewTimestamp) {
        if (closed) throw new IllegalStateException("Vivo SDK RAW session is closed");
        ints(builder, "com.vivo.current_ui_module", 16);
        builder.set(new CaptureRequest.Key<>("vivo.control.currentModeEx", long[].class), new long[]{256});
        ints(builder, "vivo.control.is_pro_mode", 1);
        ints(builder, "vivo.control.echo.mode", 1);
        ints(builder, "vivo.control.eis.config.enable", 0);
        ints(builder, "vivo.control.advance_fullsize", 1);
        ints(builder, "vivo.control.ai_highresolution", 1);
        ints(builder, "vivo.control.reprocessMode", 0);
        if (capture) {
            long id = CAPTURE_ID.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis()));
            longs(builder, "vivo.control.requestId", id);
            longs(builder, "vivo.control.globalCaptureId", id);
            longs(builder, "vivo.control.frameFlag", id);
            longs(builder, "vivo.control.displayTimeStamp", previewTimestamp);
            builder.set(new CaptureRequest.Key<>("vivo.control.isPAPNeeded", byte[].class), new byte[]{1});
            ints(builder, "vivo.control.sensorMode", 1);
            ints(builder, "vivo.control.isUpscale", 0);
            ints(builder, "vivo.control.isCapture", 0);
        }
    }

    public void applyStreamUsage(CaptureRequest.Builder builder) {
        ints(builder, "vivo.control.streamsUsage", 2, 1, 0);
    }

    public static void applyAutomaticExposure(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        // Stock Vivo Pro uses zero sensor values for AUTO instead of retaining the template's manual values.
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, 0);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 0L);
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 0L);
    }

    private static void ints(CaptureRequest.Builder builder, String name, int... values) {
        builder.set(new CaptureRequest.Key<>(name, int[].class), values);
    }

    private static void longs(CaptureRequest.Builder builder, String name, long value) {
        builder.set(new CaptureRequest.Key<>(name, long[].class), new long[]{value});
    }

    @Override public synchronized void close() throws Exception {
        if (restored) return;
        closed = true;
        Lease.give(provider, previousSdk, previousVcf);
        restored = true;
        if (journal != null) journal.clear();
    }

    /**
     * P179: a lease left by a process of ours that died gives the values back - while they are still ours and no camera is
     * in use (else another client may hold its own lease: the journal is dropped, nothing reset; with a camera busy it
     * waits for the next start). The result for the log: "none", "restored", "not ours", "busy" or "failed: ...".
     */
    static String recoverStale(Provider provider, Journal journal, int pid, java.util.function.BooleanSupplier camerasIdle) {
        return Lease.recoverStale(provider, journal, pid, camerasIdle);
    }

    /** {@link #recoverStale} on this phone at a camera start; cheap when nothing is journaled. */
    public static void recoverStale(android.content.Context context) {
        Journal journal = PrefsJournal.get();
        if (journal == null || journal.read() == null) return;
        String result;
        try {
            result = Lease.recoverStale(systemProvider(), journal, android.os.Process.myPid(), () -> camerasIdle(context));
        } catch (Exception e) {
            result = "failed: " + e;
        }
        if (!"none".equals(result)) com.particlesdevs.photoncamera.util.Log.w(TAG, "SDK lease of a dead process: " + result);
    }

    /** True when no camera of this phone is in use by anyone (the availability callbacks report each camera at once). */
    static boolean camerasIdle(android.content.Context context) {
        android.hardware.camera2.CameraManager manager = context.getSystemService(android.hardware.camera2.CameraManager.class);
        if (manager == null) return false;
        java.util.Set<String> busy = java.util.concurrent.ConcurrentHashMap.newKeySet();
        android.os.HandlerThread thread = new android.os.HandlerThread("SdkLeaseIdle");
        thread.start();
        android.hardware.camera2.CameraManager.AvailabilityCallback callback = new android.hardware.camera2.CameraManager.AvailabilityCallback() {
            @Override public void onCameraAvailable(String id) { busy.remove(id); }
            @Override public void onCameraUnavailable(String id) { busy.add(id); }
        };
        try {
            manager.registerAvailabilityCallback(callback, new android.os.Handler(thread.getLooper()));
            SystemClock.sleep(200);
            return busy.isEmpty();
        } catch (RuntimeException e) {
            return false;
        } finally {
            try { manager.unregisterAvailabilityCallback(callback); } catch (RuntimeException ignored) {}
            thread.quitSafely();
        }
    }

    /** The journal in the app's own preferences (written at once: the process may die right after). */
    static final class PrefsJournal implements Journal {
        private static final String FILE = "scam_sdk_lease", PID = "pid", SDK = "sdk", VCF = "vcf";
        private final android.content.SharedPreferences prefs;

        private PrefsJournal(android.content.SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @androidx.annotation.Nullable
        static PrefsJournal get() {
            android.content.Context c = com.particlesdevs.photoncamera.app.ScameraApp.getAppContext();
            return c == null ? null : new PrefsJournal(c.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE));
        }

        @androidx.annotation.Nullable
        @Override public int[] read() {
            if (!prefs.contains(PID)) return null;
            return new int[]{prefs.getInt(PID, 0), prefs.getInt(SDK, 0), prefs.getInt(VCF, 0)};
        }

        @Override public void write(int pid, int sdk, int vcf) {
            prefs.edit().putInt(PID, pid).putInt(SDK, sdk).putInt(VCF, vcf).commit();
        }

        @Override public void clear() {
            prefs.edit().clear().commit();
        }
    }
}
