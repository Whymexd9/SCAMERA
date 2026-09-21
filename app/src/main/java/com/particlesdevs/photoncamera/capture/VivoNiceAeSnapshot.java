package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

public final class VivoNiceAeSnapshot {
    private static final String PREFIX = "vivo.parameter.";
    private static final String[] FIELDS = {
            "VivoAlgoAECFrameControl", "VivoAlgoAECShortFrameControl",
            "VivoAlgoCaptureFrameControl", "rawHDRParams", "niceHdrExpEVMode",
            "rawHDRCaptureDrcGain"
    };
    public final long frameNumber, timestamp;
    public final int sessionGeneration;
    public final VivoNiceRequestPlan plan;

    private VivoNiceAeSnapshot(TotalCaptureResult result, int generation,
                               long timestamp, VivoNiceRequestPlan plan) {
        frameNumber = result.getFrameNumber();
        this.timestamp = timestamp;
        sessionGeneration = generation;
        this.plan = plan;
    }

    public static VivoNiceAeSnapshot read(TotalCaptureResult result, int generation) {
        if (result == null || result.getRequest() == null || result.getFrameNumber() < 0)
            throw new IllegalArgumentException("NICE AE: missing result identity");
        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (timestamp == null || timestamp <= 0)
            throw new IllegalArgumentException("NICE AE: missing sensor timestamp");
        Map<String, Object> fields = new LinkedHashMap<>();
        for (CaptureResult partial : result.getPartialResults()) {
            if (partial == null || partial.getFrameNumber() != result.getFrameNumber()
                    || partial.getSequenceId() != result.getSequenceId()
                    || !result.getRequest().equals(partial.getRequest()))
                throw new IllegalArgumentException("NICE AE: unrelated partial result");
            Long partialTimestamp = partial.get(CaptureResult.SENSOR_TIMESTAMP);
            if (partialTimestamp != null && !timestamp.equals(partialTimestamp))
                throw new IllegalArgumentException("NICE AE: inconsistent partial timestamp");
            collect(partial, fields);
        }
        collect(result, fields);
        ByteBuffer payload = ByteBuffer.allocate(VivoNiceRequestPlan.PAYLOAD_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        floats(payload, fields, FIELDS[0], 48);
        floats(payload, fields, FIELDS[1], 49);
        integers(payload, fields, FIELDS[2], 9);
        integers(payload, fields, FIELDS[3], 3);
        integers(payload, fields, FIELDS[4], 2);
        Object drc = fields.get(FIELDS[5]);
        if (!(drc instanceof Float)) throw invalidField(FIELDS[5]);
        payload.putInt(Float.floatToRawIntBits((Float) drc));
        payload.flip();
        return new VivoNiceAeSnapshot(result, generation, timestamp,
                VivoNiceRequestPlan.decode(payload));
    }

    private static void collect(CaptureResult result, Map<String, Object> fields) {
        for (CaptureResult.Key<?> key : result.getKeys()) {
            for (String name : FIELDS) {
                if (!key.getName().equals(PREFIX + name)) continue;
                Object value = result.get(key);
                // The final result overrides partial values, including malformed ones.
                if (value instanceof float[]) value = ((float[]) value).clone();
                else if (value instanceof int[]) value = ((int[]) value).clone();
                fields.put(name, value);
                break;
            }
        }
    }

    private static void floats(ByteBuffer out, Map<String, Object> fields, String name, int count) {
        Object value = fields.get(name);
        if (!(value instanceof float[]) || ((float[]) value).length != count) throw invalidField(name);
        // The last short-AEC word contains an integer, not a numeric float.
        for (float item : (float[]) value) out.putInt(Float.floatToRawIntBits(item));
    }

    private static void integers(ByteBuffer out, Map<String, Object> fields, String name, int count) {
        Object value = fields.get(name);
        if (!(value instanceof int[]) || ((int[]) value).length != count) throw invalidField(name);
        for (int item : (int[]) value) out.putInt(item);
    }

    private static IllegalArgumentException invalidField(String name) {
        return new IllegalArgumentException("NICE AE: missing or invalid " + PREFIX + name);
    }
}
