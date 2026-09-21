package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureRequest;

/** NICE detector controls from the supplied stock Photo request/session log. */
public final class VivoNicePreview {
    private static final CaptureRequest.Key<Integer> MAGIC = new CaptureRequest.Key<>(
            "vivo.control.NiceMagicEnable", Integer.class);
    private static final CaptureRequest.Key<Integer> NICE = new CaptureRequest.Key<>(
            "vivo.capability.capture.nice", Integer.class);

    private VivoNicePreview() {}

    public static void applySession(CaptureRequest.Builder builder) {
        // Stock places this in the session's global parameters as well as preview.
        builder.get(MAGIC); // Resolve the vendor key before changing the builder.
        builder.set(MAGIC, 1);
    }

    public static void applyRepeating(CaptureRequest.Builder builder) {
        // Resolve both keys first. An absent key must not leave half a profile.
        Integer magic = builder.get(MAGIC);
        Integer nice = builder.get(NICE);
        try {
            builder.set(MAGIC, 1);
            builder.set(NICE, 1); // Stock AUTO; this is not an AE exposure array.
        } catch (RuntimeException failure) {
            try {
                builder.set(MAGIC, magic);
                builder.set(NICE, nice);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }
}
