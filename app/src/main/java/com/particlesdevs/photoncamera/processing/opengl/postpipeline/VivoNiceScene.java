package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.hardware.camera2.CaptureResult;
import com.particlesdevs.photoncamera.processing.ImageFrame;

/** Scene inputs from the same CaptureResult as the selected NICE RAW.
 * Key names/types follow the PD2454 stock VivoCaptureResultKey. This does not
 * decode Vivo3rdAlgoAECFrameControl or invent a lux value from ISO/exposure.
 */
public final class VivoNiceScene {
    private static final CaptureResult.Key<Float> LUX_NEW =
            new CaptureResult.Key<>("vivo.statsaec.AecLux", Float.class);
    private static final CaptureResult.Key<Float> LUX_OLD =
            new CaptureResult.Key<>("com.qti.chi.statsaec.AecLux", Float.class);
    private static final CaptureResult.Key<Float> ADRC =
            new CaptureResult.Key<>("vivo.feedback.AdrcGain", Float.class);

    public final long timestamp;
    public final Float luxIndex, adrcGain;
    public final String luxSource;
    public final boolean invalidLux, invalidAdrc;

    private VivoNiceScene(long timestamp, Float lux, Float adrc, String source) {
        this.timestamp = timestamp;
        invalidLux = lux != null && !Float.isFinite(lux);
        invalidAdrc = adrc != null && (!Float.isFinite(adrc) || adrc <= 0f);
        luxIndex = invalidLux ? null : lux;
        adrcGain = invalidAdrc ? null : adrc;
        luxSource = source;
    }

    private static Float read(CaptureResult result, CaptureResult.Key<Float> key) {
        try { return result.get(key); }
        catch (IllegalArgumentException | ClassCastException unavailable) { return null; }
    }

    public static VivoNiceScene fromReference(ImageFrame reference) {
        CaptureResult result = reference.getMatchedCaptureMetadata();
        if (result == null)
            throw new IllegalArgumentException("NICE scene metadata does not match RAW timestamp");
        Float lux = read(result, LUX_NEW);
        String source = lux == null ? null : LUX_NEW.getName();
        // Only absence permits an alias fallback. A malformed new-tag value
        // must not be silently replaced with a possibly unrelated old value.
        if (lux == null) {
            lux = read(result, LUX_OLD);
            if (lux != null) source = LUX_OLD.getName();
        }
        return new VivoNiceScene(reference.timestamp, lux, read(result, ADRC), source);
    }

    public String describe() {
        return "scene timestamp=" + timestamp + " AEC_lux=" + luxIndex
                + " luxSource=" + luxSource + " ADRC_gain=" + adrcGain
                + " invalidLux=" + invalidLux + " invalidAdrc=" + invalidAdrc;
    }
}
