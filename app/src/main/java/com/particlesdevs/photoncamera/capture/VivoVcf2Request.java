package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

public final class VivoVcf2Request implements AutoCloseable {
    private static final CaptureRequest.Key<Long> VIF_ID = new CaptureRequest.Key<>(
            "vivo.capability.capture.id", Long.class);
    private static final CaptureRequest.Key<Long> GLOBAL_ID = new CaptureRequest.Key<>(
            "vivo.control.globalCaptureId", Long.class);
    private static final CaptureRequest.Key<int[]> LEGACY_CONTROL = new CaptureRequest.Key<>(
            "vivo.parameter.VivoAlgoCaptureFrameControl", int[].class);
    private static final CaptureRequest.Key<Integer[]> LEGACY_COUNTS = new CaptureRequest.Key<>(
            "vivo.control.RequestLeftInThisSnapshot", Integer[].class);
    private static final int NEW = 0, SUBMITTED = 1, CLOSED = 2;
    private final AtomicInteger state = new AtomicInteger(NEW);
    private final CaptureRequest request;
    public final long captureId;
    public final boolean vifEnabled;

    private VivoVcf2Request(CaptureRequest request, long captureId, boolean vifEnabled) {
        this.request = request;
        this.captureId = captureId;
        this.vifEnabled = vifEnabled;
    }

    // The caller supplies a fresh builder with the verified VCF2 template and
    // configured surfaces. The legacy NICE burst writer is not this template.
    public static VivoVcf2Request prepare(CaptureRequest.Builder builder,
                                          long captureId, boolean vifEnabled) {
        if (builder == null || captureId <= 0)
            throw new IllegalArgumentException("VCF2 requires a builder and positive capture ID");
        if (builder.get(LEGACY_CONTROL) != null || builder.get(LEGACY_COUNTS) != null)
            throw new IllegalArgumentException("Legacy frame schedule is not a VCF2 launch template");
        builder.set(VIF_ID, vifEnabled ? captureId : 0L);
        builder.set(GLOBAL_ID, captureId);
        return new VivoVcf2Request(builder.build(), captureId, vifEnabled);
    }

    public boolean acceptsVifCallback(long id) {
        return vifEnabled && state.get() == SUBMITTED && captureId == id;
    }

    public int submit(CameraCaptureSession session, CameraCaptureSession.CaptureCallback callback,
                      Handler handler) throws CameraAccessException {
        if (session == null || callback == null || handler == null)
            throw new IllegalArgumentException("VCF2 requires session, callback and handler");
        if (!state.compareAndSet(NEW, SUBMITTED))
            throw new IllegalStateException("VCF2 capture already submitted");
        // The HAL's internal series count must not become a Camera2 request count.
        // After an exception, a new transaction/ID is required: do not retry an
        // RPC that might already have reached the camera service.
        try {
            return session.captureBurst(Collections.singletonList(request), callback, handler);
        } catch (CameraAccessException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    // Retire callback identity; aborting the shared camera session is the caller's decision.
    @Override public void close() { state.set(CLOSED); }
}
