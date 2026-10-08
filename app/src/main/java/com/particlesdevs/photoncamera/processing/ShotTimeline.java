package com.particlesdevs.photoncamera.processing;

import com.particlesdevs.photoncamera.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shot speed (wave 1, W1.0): the stage times of one shot, collected in memory and printed as ONE line
 * ({@code SHOT TIMELINE ms: ...}, every stage in ms after the shutter) once the shot is saved. OPPO's log flow control drops
 * hundreds of rows per shot, so the per-stage lines alone could not be added up; this one line survives it.
 * <p>
 * The capture marks go to the shot being captured ({@link #begin}, {@link #capture}); when the burst is queued for processing
 * its timeline moves with it ({@link #detach} on the camera thread, {@link #attach} on the processing thread), so a second
 * shot captured meanwhile starts its own. Processing marks ({@link #mark}) go to the shot being processed. Only the first
 * mark of a name counts. No effect on the image.
 */
public final class ShotTimeline {
    private static final String TAG = "ShotTimeline";
    private static ShotTimeline capturing, processing;

    private final Map<String, Long> marks = new LinkedHashMap<>();

    private ShotTimeline() {}

    private void put(String name, long nanos) {
        if (!marks.containsKey(name)) marks.put(name, nanos);
    }

    /** Starts the timeline of a new capture at the shutter press. */
    public static synchronized void begin(String name) {
        com.particlesdevs.photoncamera.ui.camera.views.viewfinder.VfDrawMeter.shot(); // P57: drawn viewfinder frames from here
        capturing = new ShotTimeline();
        capturing.put(name, android.os.SystemClock.elapsedRealtimeNanos());
    }

    /** A stage of the shot being captured (nothing without {@link #begin}). */
    public static synchronized void capture(String name) {
        if (capturing != null) capturing.put(name, android.os.SystemClock.elapsedRealtimeNanos());
    }

    /** The capture's timeline, handed to its processing task; the next shutter starts a new one. */
    public static synchronized ShotTimeline detach() {
        final ShotTimeline t = capturing;
        capturing = null;
        return t;
    }

    /** The processing thread takes over a detached timeline (null: a new one without capture marks). */
    public static synchronized void attach(ShotTimeline timeline) {
        processing = timeline != null ? timeline : new ShotTimeline();
    }

    /** A stage of the shot being processed. */
    public static synchronized void mark(String name) {
        if (processing == null) processing = new ShotTimeline();
        processing.put(name, android.os.SystemClock.elapsedRealtimeNanos());
    }

    /** Milliseconds from mark a to mark b of the shot being processed, -1 when either is missing. */
    public static synchronized long between(String a, String b) {
        if (processing == null) return -1;
        final Long ta = processing.marks.get(a), tb = processing.marks.get(b);
        return ta == null || tb == null ? -1 : (tb - ta) / 1_000_000;
    }

    /**
     * Worker report lines relayed by the client: the first line of each worker stage is a mark (its time is the relay time,
     * about 1 ms after the worker printed it).
     */
    public static void workerLine(String line) {
        if (line == null || line.length() < 8) return;
        final char c = line.charAt(0);
        if (c != 'H' && c != 'N') return;
        final String name;
        if (line.startsWith("NICE STOCK MOTION")) name = "w_align";
        else if (line.startsWith("HYBRID SHASTA")) name = "w_shasta";
        else if (line.startsWith("HYBRID BENTO")) name = "w_bento";
        else if (line.startsWith("HYBRID LOCAL ALIGN")) name = "w_f6";
        else if (line.startsWith("HYBRID GPU")) name = "w_gpu";
        else if (line.startsWith("HYBRID RAW CA")) name = "w_rawca";
        else if (line.startsWith("HYBRID STAGES")) name = "w_stages";
        else if (line.equals("NICE CAPTURE OK")) name = "w_ok";
        else return;
        mark(name);
    }

    /** Prints the timeline of the shot being processed as one line and closes it. */
    public static void print() {
        final String line;
        synchronized (ShotTimeline.class) {
            final ShotTimeline t = processing;
            processing = null;
            if (t == null || t.marks.isEmpty()) return;
            line = t.describe();
        }
        Log.i(TAG, line);
    }

    /** "SHOT TIMELINE ms: name=ms ..." in the order of the marks, relative to the first one, with the total. */
    String describe() {
        final StringBuilder sb = new StringBuilder("SHOT TIMELINE ms:");
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        for (long v : marks.values()) { first = Math.min(first, v); last = Math.max(last, v); }
        for (Map.Entry<String, Long> e : marks.entrySet())
            sb.append(' ').append(e.getKey()).append('=').append((e.getValue() - first) / 1_000_000);
        sb.append(" total=").append((last - first) / 1_000_000);
        return sb.toString();
    }
}
