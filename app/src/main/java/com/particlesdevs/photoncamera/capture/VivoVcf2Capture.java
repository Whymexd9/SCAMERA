package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.os.Handler;

public final class VivoVcf2Capture implements VivoVcf2Device.Listener, AutoCloseable {
    public interface Listener {
        void onComplete(long id, byte[] jpeg, CaptureResult result);
        void onFailure(long id, String reason);
    }

    private final Handler handler;
    private final Listener listener;
    private VivoVcf2Device device;
    private VivoVcf2Request request;
    private CaptureResult result;
    private byte[] jpeg;
    private Runnable timeout;
    private boolean closed;
    private String deviceError;

    public VivoVcf2Capture(Context context, Handler handler, Listener listener)
            throws ReflectiveOperationException {
        this.handler = handler;
        this.listener = listener;
        device = VivoVcf2Device.open(context, handler, this);
    }

    public synchronized void start(CaptureRequest.Builder builder, long id,
            CameraCaptureSession session, CameraCaptureSession.CaptureCallback callback)
            throws CameraAccessException {
        if (closed || deviceError != null)
            throw new IllegalStateException("VCF2 unavailable: " + deviceError);
        if (request != null) throw new IllegalStateException("VCF2 capture already pending");
        request = VivoVcf2Request.prepare(builder, id, true);
        timeout = () -> fail(id, "VCF2: timeout waiting for JPEG and final metadata");
        try {
            if (!handler.postDelayed(timeout, 180_000L))
                throw new IllegalStateException("VCF2 callback handler stopped");
            request.submit(session, callback, handler);
        } catch (CameraAccessException | RuntimeException failure) {
            retire();
            throw failure;
        }
    }

    private boolean accepts(long id) {
        return !closed && request != null && request.acceptsVifCallback(id);
    }

    private void retire() {
        if (timeout != null) handler.removeCallbacks(timeout);
        timeout = null;
        if (request != null) request.close();
        request = null;
        result = null;
        jpeg = null;
    }

    public void fail(long id, String reason) {
        synchronized (this) {
            if (!accepts(id)) return;
            retire();
        }
        listener.onFailure(id, reason);
    }

    @Override public void onBuffer(VivoVcf2Device.Buffer buffer) {
        synchronized (this) {
            if (!accepts(buffer.captureId) || jpeg != null) return;
        }
        final byte[] bytes;
        try { bytes = buffer.copyJpegBytes(); }
        catch (java.io.IOException | RuntimeException | LinkageError failure) {
            fail(buffer.captureId, "VCF2 JPEG: " + failure.getMessage());
            return;
        }
        synchronized (this) {
            if (!accepts(buffer.captureId) || jpeg != null) return;
            jpeg = bytes;
        }
        complete(buffer.captureId);
    }

    @Override public void onResult(long id, CaptureResult value, boolean partial) {
        if (partial) return;
        synchronized (this) {
            if (!accepts(id) || result != null) return;
        }
        Long timestamp = value == null ? null : value.get(CaptureResult.SENSOR_TIMESTAMP);
        if (timestamp == null || timestamp <= 0) {
            fail(id, "VCF2: final result has no sensor timestamp");
            return;
        }
        synchronized (this) {
            if (!accepts(id) || result != null) return;
            result = value;
        }
        complete(id);
    }

    private void complete(long id) {
        final CaptureResult metadata;
        final byte[] bytes;
        synchronized (this) {
            if (!accepts(id) || result == null || jpeg == null) return;
            metadata = result;
            bytes = jpeg;
            retire();
        }
        listener.onComplete(id, bytes, metadata);
    }

    @Override public void onError(int error) {
        final long id;
        synchronized (this) {
            deviceError = "device error " + error;
            id = request == null ? 0 : request.captureId;
        }
        if (id != 0) fail(id, "VCF2: " + deviceError);
    }
    @Override public void onCaptureFrameDone(long id) { }
    @Override public void onDeviceUpdate(int status) { }
    @Override public void onInfo(long id, int type, int value) { }
    @Override public void onVopCaptureDone(int reader, long id, int a, int b, int c) { }
    @Override public void onNotify(int type, int subtype) { }

    @Override public void close() {
        final VivoVcf2Device owned;
        synchronized (this) {
            closed = true;
            retire();
            owned = device;
            device = null;
        }
        if (owned != null) try { owned.close(); }
        catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
}
