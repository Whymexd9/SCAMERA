package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureRequest;

public final class VivoVcf2PhotoProfile {
    private VivoVcf2PhotoProfile() { }

    public static void session(CaptureRequest.Builder builder, int width, int height,
                               String physicalId, boolean logicalCamera) {
        VivoNicePreview.applySession(builder);
        builder.set(new CaptureRequest.Key<>("com.vivo.current_ui_module", Integer.class), 1);
        // BaseCameraMode.setSessionStreamUsage: count, preview usage, capture usage.
        builder.set(new CaptureRequest.Key<>("vivo.control.streamsUsage", Integer[].class),
                new Integer[]{2, 1, 0});
        if (logicalCamera) {
            builder.set(new CaptureRequest.Key<>("vcf.parameter.SnapshotJpegStreamMap", int[].class),
                    new int[]{width, height, Integer.parseInt(physicalId)});
        }
    }

    public static void repeating(CaptureRequest.Builder builder) {
        VivoNicePreview.applyRepeating(builder);
        // Stock Vcf*CapabilityCommand forwards these Integer AUTO values unchanged.
        for (String name : new String[]{"vivo.capability.capture.hdr",
                "vivo.capability.capture.rawHdr", "vivo.capability.detect.hdr_highlight"})
            builder.set(new CaptureRequest.Key<>(name, Integer.class), 1);
    }

    public static void copyPreview(CaptureRequest source, CaptureRequest.Builder target) {
        for (CaptureRequest.Key<?> key : source.getKeys()) copy(source, target, key);
        target.set(CaptureRequest.CONTROL_CAPTURE_INTENT,
                CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
        target.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        target.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
    }

    private static <T> void copy(CaptureRequest source, CaptureRequest.Builder target,
                                 CaptureRequest.Key<T> key) {
        target.set(key, source.get(key));
    }
}
