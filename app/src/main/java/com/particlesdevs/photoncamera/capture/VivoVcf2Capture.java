package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.os.Handler;

public final class VivoVcf2Capture implements VivoVcf2Device.Listener, AutoCloseable {
    public interface Listener {
        void onComplete(long id, byte[] jpeg, long sensorTimestamp);
        void onFailure(long id, String reason);
    }

    private final Handler handler;
    private final Listener listener;
    private VivoVcf2Device device;
    private VivoVcf2Root root;
    private VivoVcf2Request request;
    private long sensorTimestamp;
    private byte[] jpeg;
    private Runnable timeout;
    private boolean closed;
    private String deviceError;

    public VivoVcf2Capture(Context context, Handler handler, Listener listener)
            throws ReflectiveOperationException {
        this.handler = handler;
        this.listener = listener;
        try {
            device = VivoVcf2Device.open(context, handler, this);
        } catch (NoSuchMethodException blockedFramework) {
            android.util.Log.i("NICE_CAPTURE", "VCF2 reflection unavailable; connecting root receiver");
            root = new VivoVcf2Root(context, handler, new VivoVcf2Root.Listener() {
                @Override public void onJpeg(long id, byte[] bytes) {
                    synchronized (VivoVcf2Capture.this) {
                        if (!accepts(id) || jpeg != null) return;
                        jpeg = bytes;
                    }
                    complete(id);
                }
                @Override public void onFinal(long id, long timestamp) { acceptTimestamp(id, timestamp); }
                @Override public void onFailure(long id, String reason) {
                    synchronized (VivoVcf2Capture.this) {
                        if (closed) return;
                        if (id == 0) {
                            deviceError = reason;
                            id = request == null ? 0 : request.captureId;
                        }
                    }
                    if (id != 0) fail(id, reason);
                }
            });
        }
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
            if (root == null) {
                request.submit(session, callback, handler);
            } else {
                root.arm(id, () -> {
                    synchronized (VivoVcf2Capture.this) {
                        if (closed || request == null || request.captureId != id) return;
                        try {
                            request.submit(session, callback, handler);
                            android.util.Log.i("NICE_CAPTURE", "VCF2 submitted captureId=" + id
                                    + " Camera2Requests=1 receiver=root");
                        } catch (CameraAccessException | RuntimeException failure) {
                            fail(id, "VCF2 submit: " + failure);
                        }
                    }
                });
            }
        } catch (java.io.IOException failure) {
            retire();
            throw new IllegalStateException("VCF2 root arm failed", failure);
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
        if (request != null) {
            if (root != null) root.retire(request.captureId);
            request.close();
        }
        request = null;
        sensorTimestamp = 0;
        jpeg = null;
    }

    public void fail(long id, String reason) {
        synchronized (this) {
            // Initialization/arming can fail before Camera2 submission.
            if (closed || request == null || request.captureId != id) return;
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
            if (!accepts(id) || sensorTimestamp != 0) return;
        }
        Long timestamp = value == null ? null : value.get(CaptureResult.SENSOR_TIMESTAMP);
        acceptTimestamp(id, timestamp == null ? 0 : timestamp);
    }

    private void acceptTimestamp(long id, long timestamp) {
        synchronized (this) { if (!accepts(id)) return; }
        if (timestamp <= 0) {
            fail(id, "VCF2: final result has no sensor timestamp");
            return;
        }
        synchronized (this) {
            if (!accepts(id) || sensorTimestamp != 0) return;
            sensorTimestamp = timestamp;
        }
        complete(id);
    }

    private void complete(long id) {
        final long timestamp;
        final byte[] bytes;
        synchronized (this) {
            if (!accepts(id) || sensorTimestamp == 0 || jpeg == null) return;
            timestamp = sensorTimestamp;
            bytes = jpeg;
            retire();
        }
        listener.onComplete(id, bytes, timestamp);
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
        final VivoVcf2Root ownedRoot;
        synchronized (this) {
            closed = true;
            retire();
            owned = device;
            device = null;
            ownedRoot = root;
            root = null;
        }
        if (ownedRoot != null) ownedRoot.close();
        if (owned != null) try { owned.close(); }
        catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
}
