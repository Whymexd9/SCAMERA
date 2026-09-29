package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.hardware.camera2.CaptureResult;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Immutable per-RAW inputs to the recovered PD2454 VCF AE conversion.
 * These are measured vendor values, not requested exposure or ISO estimates.
 */
public final class VivoNiceAe {
    public static final int TRANSPORT_BYTES = 176;
    private static final CaptureResult.Key<float[]> AEC = new CaptureResult.Key<>(
            "vivo.control.Vivo3rdAlgoAECFrameControl", float[].class);
    private static final CaptureResult.Key<Float> HDR_DRC = new CaptureResult.Key<>(
            "vivo.parameter.rawHDRCaptureDrcGain", Float.class);
    private static final CaptureResult.Key<Float> CAPTURE_ADRC = new CaptureResult.Key<>(
            "vivo.feedback.aeAdrcGainCapture", Float.class);
    private static final CaptureResult.Key<int[]> SEAMLESS = new CaptureResult.Key<>(
            "vcf.parameter.seamlessMode", int[].class);
    public final long timestamp;
    public final int flags;
    private final int seamlessMode;
    private final float[] aec;
    private final float hdrDrc, captureAdrc;

    private static <T> T read(CaptureResult result, CaptureResult.Key<T> key, Class<T> type) {
        try { return type.cast(result.get(key)); }
        catch (IllegalArgumentException | ClassCastException unavailable) { return null; }
    }

    private VivoNiceAe(ImageFrame frame) {
        CaptureResult result = frame.getMatchedCaptureMetadata();
        if (result == null || frame.timestamp <= 0)
            throw new IllegalArgumentException("NICE AE metadata does not match RAW timestamp");
        timestamp = frame.timestamp;
        int state = 0;
        float[] value = read(result, AEC, float[].class);
        // Other slots include opaque data. Validate only the recovered inputs.
        boolean valid = value != null && value.length >= 35;
        if (valid) {
            for (int i : new int[]{0, 2, 6, 13, 14})
                valid &= Float.isFinite(value[i]) && (i == 0 || value[i] > 0f);
        }
        aec = valid ? Arrays.copyOf(value, 35) : new float[35];
        if (value != null) state |= valid ? 1 : 2;
        Float hdr = read(result, HDR_DRC, Float.class), capture = read(result, CAPTURE_ADRC, Float.class);
        hdrDrc = hdr != null && Float.isFinite(hdr) ? hdr : 0f;
        captureAdrc = capture != null && Float.isFinite(capture) ? capture : 0f;
        if (hdr != null) state |= Float.isFinite(hdr) ? 4 : 8;
        if (capture != null) state |= Float.isFinite(capture) ? 16 : 32;
        int[] modes = read(result, SEAMLESS, int[].class);
        seamlessMode = modes != null && modes.length >= 3 ? modes[0] : 0;
        if (modes != null) state |= modes.length >= 3 ? 64 : 128;
        flags = state;
    }

    public static VivoNiceAe fromFrame(ImageFrame frame) { return new VivoNiceAe(frame); }

    public boolean hasMeasuredExposure() { return (flags & 1) != 0; }

    public double measuredExposureProduct() {
        if (!hasMeasuredExposure())
            throw new IllegalStateException("NICE measured vendor AE unavailable");
        return (double) aec[14] * aec[2];
    }

    public void writeTransport(ByteBuffer destination) {
        if (destination.order() != ByteOrder.LITTLE_ENDIAN || destination.remaining() < TRANSPORT_BYTES)
            throw new IllegalArgumentException("NICE AE requires 176 little-endian bytes");
        destination.putLong(timestamp).putInt(flags).putInt(seamlessMode);
        for (float v : aec) destination.putFloat(v);
        destination.putFloat(hdrDrc).putFloat(captureAdrc).putInt(0).putLong(0);
    }

    public String describe() {
        return "AE timestamp=" + timestamp + " flags=" + flags
                + " vendorLux=" + ((flags & 1) != 0 ? aec[0] : "unavailable")
                + " rawHdrDrc=" + ((flags & 4) != 0 ? hdrDrc : "unavailable");
    }
}
