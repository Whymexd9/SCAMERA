package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureRequest;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

public final class VivoSdkRawSession implements AutoCloseable {
    interface Provider {
        int get(int key) throws Exception;
        void set(int key, int value) throws Exception;
        void settle();
    }

    private static final AtomicLong CAPTURE_ID = new AtomicLong();
    private final Provider provider;
    private final int previousSdk, previousVcf;
    private volatile boolean closed;
    private boolean restored;

    private VivoSdkRawSession(Provider provider, int sdk, int vcf) {
        this.provider = provider;
        previousSdk = sdk;
        previousVcf = vcf;
    }

    public static VivoSdkRawSession open() throws Exception {
        Class<?> api = Class.forName("android.hardware.vivocamera.VivoCameraManager");
        Object manager = api.getMethod("getInstance").invoke(null);
        Method get = api.getMethod("getInfo", int.class);
        Method set = api.getMethod("setValue", int.class, int.class);
        return open(new Provider() {
            @Override public int get(int key) throws Exception { return (Integer) get.invoke(manager, key); }
            @Override public void set(int key, int value) throws Exception { set.invoke(manager, key, value); }
            @Override public void settle() { SystemClock.sleep(100); }
        });
    }

    static VivoSdkRawSession open(Provider provider) throws Exception {
        VivoSdkRawSession session = new VivoSdkRawSession(provider, provider.get(40), provider.get(33));
        try {
            provider.set(33, 2);
            provider.set(40, 1);
            provider.settle();
            if (provider.get(40) != 1 || provider.get(33) != 2)
                throw new IllegalStateException("Vivo SDK RAW properties were not applied");
            return session;
        } catch (Exception failure) {
            try { session.close(); } catch (Exception restore) { failure.addSuppressed(restore); }
            throw failure;
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
        Exception failure = null;
        try { provider.set(33, previousVcf); } catch (Exception e) { failure = e; }
        try { provider.set(40, previousSdk); } catch (Exception e) {
            if (failure == null) failure = e; else failure.addSuppressed(e);
        }
        provider.settle();
        if (failure != null) throw failure;
        if (provider.get(40) != previousSdk || provider.get(33) != previousVcf)
            throw new IllegalStateException("Vivo SDK RAW properties were not restored");
        restored = true;
    }
}
