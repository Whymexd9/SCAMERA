package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import com.particlesdevs.photoncamera.util.Log;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P27: what the HAL really honours for manual (AE off) requests of one camera and RAW size (one sensor mode; modes are never
 * switched). Learned from the shot's own results and the AE restore frame: a gain cap needs two results clamped to the same
 * ISO at an exact shutter (one can be pipeline latency); a later request honoured at or above the cap clears it. Until a cap
 * is learned nothing changes: the planners keep today's ranges. The owner's 'LONG: ISO 320/640' case: with the cap learned,
 * the Hybrid's bracketed frames take the extra exposure from the shutter instead of a gain the HAL does not give.
 */
public final class ExposureLimits {
    private static final ConcurrentHashMap<String, ExposureLimits> MODES = new ConcurrentHashMap<>();
    public final String key;
    private int isoCap = Integer.MAX_VALUE, capCandidate, capVotes;
    private long shutterCap = Long.MAX_VALUE, shutterCandidate;
    private int shutterVotes;

    ExposureLimits(String key) { this.key = key; }

    public static ExposureLimits of(String cameraId, int rawWidth, int rawHeight, boolean maximumResolution) {
        return MODES.computeIfAbsent(cameraId + '/' + rawWidth + 'x' + rawHeight + (maximumResolution ? "/max" : ""), ExposureLimits::new);
    }

    /** Learned manual ISO ceiling, {@link Integer#MAX_VALUE} while none is known. */
    public synchronized int isoCap() { return isoCap; }
    /** Learned manual shutter ceiling (ns), {@link Long#MAX_VALUE} while none is known. */
    public synchronized long shutterCap() { return shutterCap; }

    /** A manual (AE off) request and its result. True when the HAL gave what was asked (1.5 % / 2 ISO rounding allowed). */
    public synchronized boolean observeManual(CaptureRequest request, CaptureResult result) {
        if (request == null || result == null) return false;
        Integer ae = request.get(CaptureRequest.CONTROL_AE_MODE);
        if (ae == null || ae != CaptureRequest.CONTROL_AE_MODE_OFF) return true;
        Integer qi = request.get(CaptureRequest.SENSOR_SENSITIVITY), ri = result.get(CaptureResult.SENSOR_SENSITIVITY);
        Long qt = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME), rt = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (qi == null || ri == null || qt == null || rt == null || qi <= 0 || ri <= 0 || qt <= 0 || rt <= 0) return false;
        final boolean isoOk = Math.abs(ri - qi) <= Math.max(2, qi * 0.015);
        final boolean timeOk = Math.abs((double) rt / qt - 1) <= 0.015;
        final String before = toString();
        if (isoOk) {
            if (qi >= isoCap) isoCap = Integer.MAX_VALUE;   // the HAL gives this gain after all
            if (ri.equals(capCandidate)) capVotes = 0;
        } else if (ri < qi && timeOk) {
            capVotes = ri == capCandidate ? capVotes + 1 : 1;
            capCandidate = ri;
            if (capVotes >= 2) isoCap = Math.min(isoCap, ri);
        }
        if (timeOk) {
            if (qt >= shutterCap) shutterCap = Long.MAX_VALUE;
        } else if (rt < qt) {
            shutterVotes = Math.abs((double) rt / Math.max(1, shutterCandidate) - 1) <= 0.015 ? shutterVotes + 1 : 1;
            shutterCandidate = rt;
            if (shutterVotes >= 2) shutterCap = Math.min(shutterCap, rt);
        }
        final String after = toString();
        if (!before.equals(after)) Log.i("SCAM_CAPTURE", "honoured exposure " + after);
        return isoOk && timeOk;
    }

    @Override public synchronized String toString() {
        return String.format(Locale.ROOT, "%s ISO cap %s shutter cap %s", key,
                isoCap == Integer.MAX_VALUE ? "-" : String.valueOf(isoCap), shutterCap == Long.MAX_VALUE ? "-" : shutterCap + " ns");
    }

    /** Tests: forget every learned limit. */
    static void resetForTest() { MODES.clear(); }
}
