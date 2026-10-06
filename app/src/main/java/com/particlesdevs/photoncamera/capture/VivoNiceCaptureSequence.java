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
import com.particlesdevs.photoncamera.util.Lang;

public final class VivoNiceCaptureSequence {
    private final List<ImageFrame.NiceCaptureTag> tags = new ArrayList<>();
    private final Map<Integer, CaptureResult> results = new HashMap<>();
    private final HashSet<Long> timestamps = new HashSet<>();
    private final HashSet<Long> zslTimestamps = new HashSet<>();
    private String failure;
    private long shutterTimestamp;
    public final int futureCount, frameCount;
    // P27: every series the camera takes is tolerant ("optional"). A request whose RAW the HAL lost or failed, whose result is
    // invalid or foreign, a duplicate or a missing RAW only drops that frame and the shot goes on with what arrived (vivo X300
    // Ultra ultrawide: the HAL returned 1 of 5 post-shutter RAWs; X300 Ultra 2026-10-06: 'NICE: incomplete results 3/7' lost the
    // shot). The merge decides what it can build from the frames (SCAM HDR falls back to the Hybrid merge). Only a series
    // built with the plain constructor stays strict (host checks of the binding rules).
    private boolean optionalFuture;
    private final TreeMap<Integer, String> dropped = new TreeMap<>();
    private final List<String> notes = new ArrayList<>();
    /** P27: role a frame really has by its measured exposure, when it differs from its request's (HybridPlan.classify). */
    private final Map<Integer, ImageFrame.CaptureRole> measuredRoles = new HashMap<>();
    /** Results bound to a RAW by the last bindAndValidate, by request index. */
    private final TreeMap<Integer, CaptureResult> bound = new TreeMap<>();
    private int boundFuture, zslPresent;

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
        sequence.optionalFuture=true;
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

    // Stock normal-back: all four N and L/S/ES are requested after the shutter (six requests: no L, it is built from the N).
    public static VivoNiceCaptureSequence stockNormalBack(List<CaptureRequest> requests,long shutterTimestamp) {
        if(shutterTimestamp<=0 || (requests.size()!=7 && requests.size()!=6))
            throw new IllegalArgumentException("NICE normal-back requires six or seven bracket requests");
        final boolean withLong=requests.size()==7;
        for(int i=0;i<requests.size();i++) {
            int slot=withLong||i<4?i:i+1;
            ImageFrame.CaptureRole role=slot<4?ImageFrame.CaptureRole.NORMAL:slot==4?ImageFrame.CaptureRole.LONG
                    :slot==5?ImageFrame.CaptureRole.SHORT:ImageFrame.CaptureRole.EXTRA_SHORT;
            Object tag=requests.get(i).getTag();
            if(!(tag instanceof ImageFrame.NiceCaptureTag) || ((ImageFrame.NiceCaptureTag)tag).role!=role)
                throw new IllegalArgumentException("NICE normal-back request order");
        }
        VivoNiceCaptureSequence sequence=new VivoNiceCaptureSequence(requests,new ArrayList<>());
        sequence.shutterTimestamp=shutterTimestamp;
        sequence.optionalFuture=true;
        return sequence;
    }

    /**
     * P27, Hybrid with an empty ZSL ring: N and the Bento / Shasta frames are all taken after the shutter, tolerant like
     * {@link #hybridZsl}; the merge uses whatever arrived (at least one RAW).
     */
    public static VivoNiceCaptureSequence hybridFuture(List<CaptureRequest> requests,long shutterTimestamp) {
        if(shutterTimestamp<=0 || requests.isEmpty() || requests.size()>16)
            throw new IllegalArgumentException("NICE hybrid normal-back requires 1..16 requests");
        VivoNiceCaptureSequence sequence=new VivoNiceCaptureSequence(requests,new ArrayList<>());
        sequence.shutterTimestamp=shutterTimestamp;
        sequence.optionalFuture=true;
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
        // Every reason is kept for the log (the first one used to hide the rest).
        if (failure == null) failure = reason;
        else if (failure.length() < 2000) failure += "; " + reason;
    }

    private void note(String what) {
        if (notes.size() < 32) notes.add(what);
    }

    /**
     * A submitted request lost its RAW buffer, failed in the HAL or missed its planned exposure. A strict series fails; the
     * hybrid drops that frame (its result, if any, is ignored) and merges the frames that arrived.
     */
    public synchronized void lost(CaptureRequest request, String reason) {
        Object value = request == null ? null : request.getTag();
        if (!optionalFuture) { failed(reason); return; }
        if (!(value instanceof ImageFrame.NiceCaptureTag)) { note("lost frame without identity: " + reason); return; }
        ImageFrame.NiceCaptureTag tag = (ImageFrame.NiceCaptureTag) value;
        if (tag.index >= tags.size() || tags.get(tag.index) != tag) { note("lost frame from another request/series: " + reason); return; }
        if (!dropped.containsKey(tag.index)) dropped.put(tag.index, tag.role + ": " + reason);
        results.remove(tag.index);
    }

    /**
     * P27: the series ends early (aborted, camera closed, watchdog): every request without a result counts as lost, so the
     * completion merges what arrived instead of waiting for frames that will not come.
     */
    public synchronized void abandonOutstanding(String reason) {
        for (ImageFrame.NiceCaptureTag tag : tags)
            if (!results.containsKey(tag.index) && !dropped.containsKey(tag.index))
                if (optionalFuture) dropped.put(tag.index, tag.role + ": " + reason); else failed(reason);
    }

    /**
     * P27: the frame of {@code request} arrived at another exposure than planned and is used in the role its measured exposure
     * gives (HybridPlan.classify), never dropped for it. The role reaches the frame in {@link #bindAndValidate}.
     */
    public synchronized void reclassify(CaptureRequest request, ImageFrame.CaptureRole role) {
        Object value = request == null ? null : request.getTag();
        if (!(value instanceof ImageFrame.NiceCaptureTag) || role == null) {
            if (optionalFuture) note("reclassified frame without identity"); else failed("reclassified frame without identity");
            return;
        }
        ImageFrame.NiceCaptureTag tag = (ImageFrame.NiceCaptureTag) value;
        if (tag.index >= tags.size() || tags.get(tag.index) != tag) {
            if (optionalFuture) note("reclassified frame from another series"); else failed("reclassified frame from another series");
            return;
        }
        if (role != tag.role) measuredRoles.put(tag.index, role); else measuredRoles.remove(tag.index);
    }

    public synchronized boolean isOptionalFuture() { return optionalFuture; }
    public synchronized int droppedCount() { return dropped.size(); }
    /** Post-shutter frames bound to a RAW by the last bindAndValidate. */
    public synchronized int boundFutureCount() { return boundFuture; }
    public synchronized String droppedSummary() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, String> e : dropped.entrySet())
            out.append(out.length() == 0 ? "" : "; ").append('#').append(e.getKey()).append(' ').append(e.getValue());
        for (String n : notes) out.append(out.length() == 0 ? "" : "; ").append(n);
        for (Map.Entry<Integer, ImageFrame.CaptureRole> e : measuredRoles.entrySet())
            out.append(out.length() == 0 ? "" : "; ").append('#').append(e.getKey()).append(' ').append(tags.get(e.getKey()).role)
               .append(" used as ").append(e.getValue());
        return out.toString();
    }
    /** RAWs that are part of the series and were present at the last bindAndValidate (buffered N + bound post-shutter). */
    public synchronized int presentCount() { return zslPresent + boundFuture; }

    /**
     * The result of the first post-shutter frame bound by the last bindAndValidate whose role (measured role first) is
     * {@code role}, in request order; {@code role == null}: any bound frame. Null when there is none.
     */
    public synchronized CaptureResult firstBoundResult(ImageFrame.CaptureRole role) {
        for (Map.Entry<Integer, CaptureResult> e : bound.entrySet()) {
            ImageFrame.CaptureRole actual = measuredRoles.containsKey(e.getKey()) ? measuredRoles.get(e.getKey()) : tags.get(e.getKey()).role;
            if (role == null || actual == role) return e.getValue();
        }
        return null;
    }

    public synchronized void completed(CaptureRequest request, CaptureResult result) {
        Object value = request.getTag();
        if (!(value instanceof ImageFrame.NiceCaptureTag)) {
            if (optionalFuture) { note("result without request identity ignored"); return; }
            failed("result without request identity"); return;
        }
        ImageFrame.NiceCaptureTag tag = (ImageFrame.NiceCaptureTag) value;
        if (tag.index >= tags.size() || tags.get(tag.index) != tag
                || result.getRequest() == null || result.getRequest().getTag() != tag) {
            if (optionalFuture) { note("result from another request/series ignored"); return; }
            failed("result from another request/series"); return;
        }
        if (optionalFuture && dropped.containsKey(tag.index)) return; // dropped frame: its metadata is not used
        if (optionalFuture && results.containsKey(tag.index)) { note("#" + tag.index + " second result ignored"); return; }
        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
        Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (timestamp == null || timestamp <= 0 || exposure == null || exposure <= 0
                || iso == null || iso <= 0 || results.containsKey(tag.index)
                || (shutterTimestamp>0 && timestamp<=shutterTimestamp)
                || zslTimestamps.contains(timestamp) || !timestamps.add(timestamp)) {
            // A frame whose metadata cannot be trusted is never matched to a RAW; a tolerant series goes on without it.
            if (optionalFuture) { lost(request, "invalid or duplicate capture metadata"); return; }
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
        int boundCount = 0, zslCount = 0;
        bound.clear();
        Map<Long, CaptureResult> byTimestamp = new HashMap<>();
        Map<CaptureResult, Integer> indexOf = new HashMap<>();
        for (Map.Entry<Integer, CaptureResult> e : results.entrySet()) {
            byTimestamp.put(e.getValue().get(CaptureResult.SENSOR_TIMESTAMP), e.getValue());
            indexOf.put(e.getValue(), e.getKey());
        }
        HashSet<Long> seen = new HashSet<>();
        for (ImageFrame frame : frames) {
            if (frame == null) {
                if (!optionalFuture) throw new IllegalStateException("NICE: missing RAW frame");
                note("null RAW frame dropped");
                discard.add(null);
                continue;
            }
            if (!seen.add(frame.timestamp)) {
                if (!optionalFuture) throw new IllegalStateException("NICE: duplicate RAW timestamp");
                note("duplicate RAW timestamp " + frame.timestamp + " dropped");
                discard.add(frame);
                continue;
            }
            if (frame.fromZsl) {
                if (!zslTimestamps.contains(frame.timestamp) || frame.getMatchedCaptureMetadata() == null) {
                    if (!optionalFuture) throw new IllegalStateException("NICE: unexpected buffered RAW");
                    note("unexpected buffered RAW " + frame.timestamp + " dropped");
                    discard.add(frame);
                    continue;
                }
                zslCount++;
            } else {
                CaptureResult result = byTimestamp.remove(frame.timestamp);
                if (result == null) {
                    if (!optionalFuture) throw new IllegalStateException("NICE: RAW has no matching request result");
                    discard.add(frame);
                    continue;
                }
                frame.setCaptureMetadata(result);
                Integer index = indexOf.get(result);
                frame.measuredRole = index == null ? null : measuredRoles.get(index);
                if (index != null) bound.put(index, result);
                boundCount++;
            }
        }
        if (!optionalFuture && (!seen.containsAll(zslTimestamps) || !byTimestamp.isEmpty()))
            throw new IllegalStateException("NICE: missing requested RAW");
        if (optionalFuture && zslCount < zslTimestamps.size())
            note((zslTimestamps.size() - zslCount) + " buffered N RAWs missing");
        // a result whose RAW never arrived is a dropped frame as well
        for (Map.Entry<Integer, CaptureResult> e : results.entrySet())
            if (optionalFuture && byTimestamp.containsValue(e.getValue()) && !dropped.containsKey(e.getKey()))
                dropped.put(e.getKey(), tags.get(e.getKey()).role + ": RAW missing");
        boundFuture = boundCount;
        zslPresent = zslCount;
        // The only case without a photo: not a single RAW of the series arrived.
        if (optionalFuture && zslCount + boundCount == 0)
            throw new IllegalStateException(Lang.t("NICE: камера не передала ни одного RAW-кадра", "NICE: the camera delivered no RAW frame")
                    + (dropped.isEmpty() ? "" : " (" + droppedSummary() + ")"));
        return discard;
    }
}
