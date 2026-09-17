package com.particlesdevs.photoncamera.util;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Build;
import android.util.Base64;

import java.lang.reflect.Array;
import java.util.Locale;
import java.util.Map;

/** Read-only inventory. A matching name is NOT proof of a Samsung calibration layout. */
final class RemosaicMetadataProbe {
    private static final int MAX_BYTES_PER_TAG = 65536;
    private static final int MAX_BYTES_TOTAL = 262144;
    private final StringBuilder out = new StringBuilder();
    private int bytesWritten;

    static String describe(String cameraId, CameraCharacteristics characteristics,
                           TotalCaptureResult result) {
        RemosaicMetadataProbe probe = new RemosaicMetadataProbe();
        probe.out.append("--- remosaic-probe camera=").append(cameraId)
                .append(" frame=").append(result.getFrameNumber()).append(" ---\n")
                .append("Candidate metadata only; no stock library invoked. ")
                .append("Absent advertised tags do not prove absent HAL calibration.\n");
        if (characteristics != null) probe.characteristics(characteristics);
        probe.result("result", result);
        if (Build.VERSION.SDK_INT >= 28) {
            for (Map.Entry<String, CaptureResult> entry
                    : result.getPhysicalCameraResults().entrySet()) {
                probe.result("physical=" + entry.getKey(), entry.getValue());
            }
        }
        probe.out.append("--- remosaic-probe end ---\n");
        return probe.out.toString();
    }

    private static boolean candidate(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("remosaic") || n.contains("eeprom") || n.contains("otp")
                || n.contains("crosstalk") || n.contains("xcfa") || n.contains("seamless");
    }

    private void characteristics(CameraCharacteristics c) {
        out.append("characteristics candidates:\n");
        for (CameraCharacteristics.Key<?> key : c.getKeys()) {
            if (!candidate(key.getName())) continue;
            try { value("characteristics", key.getName(), c.get(key)); }
            catch (Exception e) { failure(key.getName(), e); }
        }
        out.append("advertised request controls (not set):\n");
        if (c.getAvailableCaptureRequestKeys() != null) {
            for (android.hardware.camera2.CaptureRequest.Key<?> key
                    : c.getAvailableCaptureRequestKeys()) {
                if (candidate(key.getName())) out.append(key.getName()).append('\n');
            }
        }
        out.append("advertised result candidates (may be absent in this frame):\n");
        if (c.getAvailableCaptureResultKeys() != null) {
            for (CaptureResult.Key<?> key : c.getAvailableCaptureResultKeys()) {
                if (candidate(key.getName())) out.append(key.getName()).append('\n');
            }
        }
    }

    private void result(String source, CaptureResult result) {
        out.append(source).append(" crop=")
                .append(result.get(CaptureResult.SCALER_CROP_REGION))
                .append(" gains=").append(result.get(CaptureResult.COLOR_CORRECTION_GAINS))
                .append(" iso=").append(result.get(CaptureResult.SENSOR_SENSITIVITY))
                .append(" exposure=").append(result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                .append('\n');
        for (CaptureResult.Key<?> key : result.getKeys()) {
            if (!candidate(key.getName())) continue;
            try { value(source, key.getName(), result.get(key)); }
            catch (Exception e) { failure(key.getName(), e); }
        }
    }

    private void failure(String name, Exception e) {
        out.append(name).append(" unreadable: ").append(e.getClass().getSimpleName()).append('\n');
    }

    private void value(String source, String name, Object value) {
        out.append(source).append(' ').append(name).append(' ');
        if (value == null) { out.append("null\n"); return; }
        out.append("type=").append(value.getClass().getTypeName());
        if (value instanceof byte[]) {
            byte[] data = (byte[]) value;
            out.append(" bytes=").append(data.length);
            if (data.length > MAX_BYTES_PER_TAG || data.length > MAX_BYTES_TOTAL - bytesWritten) {
                out.append(" payload omitted: budget\n");
                return;
            }
            bytesWritten += data.length;
            out.append(" encoding=base64\n");
            String encoded = Base64.encodeToString(data, Base64.NO_WRAP);
            // Chunks keep log lines manageable; concatenate payload lines to decode.
            for (int i = 0; i < encoded.length(); i += 1024) {
                out.append("payload ").append(encoded, i, Math.min(i + 1024, encoded.length()))
                        .append('\n');
            }
            out.append("payload-end\n");
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            out.append(" count=").append(length).append(" preview=");
            for (int i = 0; i < Math.min(length, 16); i++) {
                if (i != 0) out.append(',');
                out.append(Array.get(value, i));
            }
            out.append(length > 16 ? " ... (preview only)\n" : "\n");
        } else {
            String text = String.valueOf(value);
            out.append(" value=").append(text, 0, Math.min(text.length(), 512)).append('\n');
        }
    }
}
