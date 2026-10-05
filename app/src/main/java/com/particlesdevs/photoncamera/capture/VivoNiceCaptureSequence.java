package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class VivoNiceCaptureSequence {
    private final List<ImageFrame.NiceCaptureTag> tags = new ArrayList<>();
    private final Map<Integer, CaptureResult> results = new HashMap<>();
    private final HashSet<Long> timestamps = new HashSet<>();
    private final HashSet<Long> zslTimestamps = new HashSet<>();
    private String failure;
    private long shutterTimestamp;
    public final int futureCount, frameCount;
    // LMC hybrid: the post-shutter frames (Bento ultrashort, Shasta bracketed) are extras on top of the buffered N frames. A
    // request whose RAW the HAL lost or failed, or whose exposure missed the plan, is dropped and the shot goes on (vivo X300
    // Ultra ultrawide: the HAL returned 1 of 5 post-shutter RAWs and the whole shot was discarded). SCAM HDR stays strict.
    private boolean optionalFuture;
    private final TreeMap<Integer, String> dropped = new TreeMap<>();
    private int boundFuture;

    public static VivoNiceCaptureSequence stockZsl(List<CaptureRequest> requests,List<ImageFrame> past,long shutterTimestamp) {
        if(shutterTimestamp<=0 || past.size()<4 || past.size()>50 || requests.size()<2 || requests.size()>3)
            throw new IllegalArgumentException("NICE ZSL requires 4..50 past N and S/ES (and L) bracket requests");
        // Two requests: L is built from the buffered N frames (no L after the shutter).
        ImageFrame.CaptureRole[] roles=requests.size()==3
                ?new ImageFrame.CaptureRole[]{ImageFrame.CaptureRole.LONG,ImageFrame.CaptureRole.SHORT,ImageFrame.CaptureRole.EXTRA_SHORT}
                :new ImageFrame.CaptureRole[]{ImageFrame.CaptureRole.SHORT,ImageFrame.CaptureRole.EXTRA_SHORT};
        for(int i=0;i<roles.length;i++) {
            Object tag=requests.get(i).getTag();
            if(!(tag instanceof ImageFrame.NiceCaptureTag) || ((ImageFrame.NiceCaptureTag)tag).role!=roles[i])
                throw new IllegalArgumentException("NICE ZSL must not request replacement normal frames");
        }
        for(ImageFrame frame:past) {
            CaptureResult result=frame.getMatchedCaptureMetadata();
            Long timestamp=result==null?null:result.get(CaptureResult.SENSOR_TIMESTAMP);
            if(frame.timestamp<=0 || frame.timestamp>shutterTimestamp || timestamp==null || timestamp!=frame.timestamp)
                throw new IllegalArgumentException("NICE ZSL RAW is not matched to the shutter cutoff");
        }
        VivoNiceCaptureSequence sequence=new VivoNiceCaptureSequence(requests,past);
        sequence.shutterTimestamp=shutterTimestamp;
        return sequence;
    }

    /** LMC hybrid: buffered N frames plus any post-shutter requests (ultrashort, bracketed, extra normal), in plan order. */
    public static VivoNiceCaptureSequence hybridZsl(List<CaptureRequest> requests,List<ImageFrame> past,long shutterTimestamp) {
        if(shutterTimestamp<=0 || past.size()<1 || past.size()>64 || requests.isEmpty() || requests.size()>8)
            throw new IllegalArgumentException("NICE hybrid ZSL requires 1..64 past N and 1..8 bracket requests");
        for(CaptureRequest request:requests) {
            Object tag=request.getTag();
            if(!(tag instanceof ImageFrame.NiceCaptureTag))throw new IllegalArgumentException("NICE hybrid request has no role");
        }
        for(ImageFrame frame:past) {
            CaptureResult result=frame.getMatchedCaptureMetadata();
            Long timestamp=result==null?null:result.get(CaptureResult.SENSOR_TIMESTAMP);
            if(frame.timestamp<=0 || frame.timestamp>shutterTimestamp || timestamp==null || timestamp!=frame.timestamp)
                throw new IllegalArgumentException("NICE ZSL RAW is not matched to the shutter cutoff");
        }
        VivoNiceCaptureSequence sequence=new VivoNiceCaptureSequence(requests,past);
        sequence.shutterTimestamp=shutterTimestamp;
        sequence.optionalFuture=true;
        return sequence;
    }

    // Stock normal-back: all four N and L/S/ES are requested after the shutter.
    public static VivoNiceCaptureSequence stockNormalBack(List<CaptureRequest> requests,long shutterTimestamp) {
        if(shutterTimestamp<=0 || requests.size()!=7)
            throw new IllegalArgumentException("NICE normal-back requires seven bracket requests");
        for(int i=0;i<7;i++) {
            ImageFrame.CaptureRole role=i<4?ImageFrame.CaptureRole.NORMAL:i==4?ImageFrame.CaptureRole.LONG
                    :i==5?ImageFrame.CaptureRole.SHORT:ImageFrame.CaptureRole.EXTRA_SHORT;
            Object tag=requests.get(i).getTag();
            if(!(tag instanceof ImageFrame.NiceCaptureTag) || ((ImageFrame.NiceCaptureTag)tag).role!=role)
                throw new IllegalArgumentException("NICE normal-back request order");
        }
        VivoNiceCaptureSequence sequence=new VivoNiceCaptureSequence(requests,new ArrayList<>());
        sequence.shutterTimestamp=shutterTimestamp;
        return sequence;
    }

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

    /**
     * A submitted request lost its RAW buffer, failed in the HAL or missed its planned exposure. A strict series fails; the
     * hybrid drops that frame (its result, if any, is ignored) and merges the frames that arrived.
     */
    public synchronized void lost(CaptureRequest request, String reason) {
        Object value = request == null ? null : request.getTag();
        if (!optionalFuture || !(value instanceof ImageFrame.NiceCaptureTag)) { failed(reason); return; }
        ImageFrame.NiceCaptureTag tag = (ImageFrame.NiceCaptureTag) value;
        if (tag.index >= tags.size() || tags.get(tag.index) != tag) { failed("lost frame from another request/series"); return; }
        if (!dropped.containsKey(tag.index)) dropped.put(tag.index, tag.role + ": " + reason);
        results.remove(tag.index);
    }

    public synchronized boolean isOptionalFuture() { return optionalFuture; }
    public synchronized int droppedCount() { return dropped.size(); }
    /** Post-shutter frames bound to a RAW by the last bindAndValidate. */
    public synchronized int boundFutureCount() { return boundFuture; }
    public synchronized String droppedSummary() {
        if (dropped.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, String> e : dropped.entrySet())
            out.append(out.length() == 0 ? "" : "; ").append('#').append(e.getKey()).append(' ').append(e.getValue());
        return out.toString();
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
        if (optionalFuture && dropped.containsKey(tag.index)) return; // dropped frame: its metadata is not used
        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
        Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (timestamp == null || timestamp <= 0 || exposure == null || exposure <= 0
                || iso == null || iso <= 0 || results.containsKey(tag.index)
                || (shutterTimestamp>0 && timestamp<=shutterTimestamp)
                || zslTimestamps.contains(timestamp) || !timestamps.add(timestamp)) {
            failed("duplicate or invalid capture metadata"); return;
        }
        results.put(tag.index, result);
    }

    public synchronized void requireCompleteMetadata() {
        if (failure != null || (!optionalFuture && results.size() != futureCount))
            throw new IllegalStateException("NICE: incomplete results " + results.size() + "/"
                    + futureCount + (failure == null ? "" : "; " + failure));
    }

    /**
     * Binds every post-shutter RAW to its request result. Returns the RAWs to discard: none for a strict series (any mismatch
     * throws); for the hybrid the post-shutter RAWs without a usable result (dropped or never reported). The buffered N
     * frames are required in both modes.
     */
    public synchronized List<ImageFrame> bindAndValidate(List<ImageFrame> frames) {
        requireCompleteMetadata();
        if (!optionalFuture && frames.size() != frameCount)
            throw new IllegalStateException("NICE: incomplete RAW series " + frames.size() + "/" + frameCount);
        List<ImageFrame> discard = new ArrayList<>();
        int bound = 0;
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
                if (result == null) {
                    if (!optionalFuture) throw new IllegalStateException("NICE: RAW has no matching request result");
                    discard.add(frame);
                    continue;
                }
                frame.setCaptureMetadata(result);
                bound++;
            }
        }
        if (!seen.containsAll(zslTimestamps) || (!optionalFuture && !byTimestamp.isEmpty()))
            throw new IllegalStateException("NICE: missing requested RAW");
        // hybrid: a result whose RAW never arrived is a dropped frame as well
        for (Map.Entry<Integer, CaptureResult> e : results.entrySet())
            if (optionalFuture && byTimestamp.containsValue(e.getValue()) && !dropped.containsKey(e.getKey()))
                dropped.put(e.getKey(), tags.get(e.getKey()).role + ": RAW missing");
        boundFuture = bound;
        return discard;
    }
}
