package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class VivoNiceCaptureSequence {
    private final List<ImageFrame.NiceCaptureTag> tags = new ArrayList<>();
    private final Map<Integer, CaptureResult> results = new HashMap<>();
    private final HashSet<Long> timestamps = new HashSet<>();
    private final HashSet<Long> zslTimestamps = new HashSet<>();
    private String failure;
    public final int futureCount, frameCount;

    public VivoNiceCaptureSequence(List<CaptureRequest> requests, List<ImageFrame> past) {
        long generation = -1;
        for (CaptureRequest request : requests) {
            Object value = request.getTag();
            if (!(value instanceof ImageFrame.NiceCaptureTag))
                throw new IllegalArgumentException("NICE request has no series identity");
            ImageFrame.NiceCaptureTag tag = (ImageFrame.NiceCaptureTag) value;
            if (tag.index != tags.size() || (!tags.isEmpty() && tag.generation != generation))
                throw new IllegalArgumentException("Mixed NICE request series");
            tags.add(tag);
            generation = tag.generation;
        }
        for (ImageFrame frame : past) {
            if (!frame.fromZsl || frame.getMatchedCaptureMetadata() == null
                    || frame.measuredExposure <= 0 || frame.measuredIso <= 0
                    || !zslTimestamps.add(frame.timestamp))
                throw new IllegalArgumentException("Invalid NICE buffered RAW");
        }
        futureCount = tags.size();
        frameCount = futureCount + zslTimestamps.size();
        if (frameCount == 0) throw new IllegalArgumentException("Empty NICE series");
    }

    public synchronized void failed(String reason) {
        if (failure == null) failure = reason;
    }

    public synchronized void completed(CaptureRequest request, CaptureResult result) {
        Object value = request.getTag();
        if (!(value instanceof ImageFrame.NiceCaptureTag)) {
            failed("result without request identity"); return;
        }
        ImageFrame.NiceCaptureTag tag = (ImageFrame.NiceCaptureTag) value;
        if (tag.index >= tags.size() || tags.get(tag.index) != tag
                || result.getRequest() == null || result.getRequest().getTag() != tag) {
            failed("result from another request/series"); return;
        }
        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
        Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (timestamp == null || timestamp <= 0 || exposure == null || exposure <= 0
                || iso == null || iso <= 0 || results.containsKey(tag.index)
                || zslTimestamps.contains(timestamp) || !timestamps.add(timestamp)) {
            failed("duplicate or invalid capture metadata"); return;
        }
        results.put(tag.index, result);
    }

    public synchronized void requireCompleteMetadata() {
        if (failure != null || results.size() != futureCount)
            throw new IllegalStateException("NICE: incomplete results " + results.size() + "/"
                    + futureCount + (failure == null ? "" : "; " + failure));
    }

    public synchronized void bindAndValidate(List<ImageFrame> frames) {
        requireCompleteMetadata();
        if (frames.size() != frameCount)
            throw new IllegalStateException("NICE: incomplete RAW series " + frames.size() + "/" + frameCount);
        Map<Long, CaptureResult> byTimestamp = new HashMap<>();
        for (CaptureResult result : results.values())
            byTimestamp.put(result.get(CaptureResult.SENSOR_TIMESTAMP), result);
        HashSet<Long> seen = new HashSet<>();
        for (ImageFrame frame : frames) {
            if (!seen.add(frame.timestamp)) throw new IllegalStateException("NICE: duplicate RAW timestamp");
            if (frame.fromZsl) {
                if (!zslTimestamps.contains(frame.timestamp) || frame.getMatchedCaptureMetadata() == null)
                    throw new IllegalStateException("NICE: unexpected buffered RAW");
            } else {
                CaptureResult result = byTimestamp.remove(frame.timestamp);
                if (result == null) throw new IllegalStateException("NICE: RAW has no matching request result");
                frame.setCaptureMetadata(result);
            }
        }
        if (!byTimestamp.isEmpty() || !seen.containsAll(zslTimestamps))
            throw new IllegalStateException("NICE: missing requested RAW");
    }
}
