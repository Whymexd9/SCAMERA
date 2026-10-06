package com.particlesdevs.photoncamera.capture;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P27 (critic M6): the vivo X300 Ultra lost the first post-shutter requests right after the HAL queue flush (log lines 265-325:
 * frames 27-30, the four N requests, lost, 31-33 arrived; the same at 91846fc: 34-37). Per camera, every finished series
 * records which request indices the HAL failed or whose RAW buffer it lost, and whether the queue was flushed before it. Two
 * flushed series in a row whose LEADING requests were lost (and a later request arrived) mark the camera: its later shots skip
 * the flush, about 0.25 s more shutter lag on that camera only; the sensor mode is untouched. A flushed series without a leading
 * loss clears the votes. Cameras without the pattern never change.
 */
public final class FlushLossStats {
    private static final ConcurrentHashMap<String, FlushLossStats> CAMERAS = new ConcurrentHashMap<>();
    static final int VOTES = 2;
    public final String camera;
    private int votes, shots, flushedShots, leadingLossShots;
    private boolean skip;

    FlushLossStats(String camera) { this.camera = camera; }

    public static FlushLossStats of(String camera) {
        return CAMERAS.computeIfAbsent(camera, FlushLossStats::new);
    }

    /** True once this camera showed the leading loss after a flush twice in a row: do not flush before its series. */
    public synchronized boolean skipFlush() { return skip; }

    /**
     * One finished series of {@code requests} post-shutter requests; {@code halLost}: indices the HAL failed or lost the RAW
     * of (not frames dropped for other reasons). Returns the log line.
     */
    public synchronized String record(boolean flushed, int requests, Set<Integer> halLost) {
        final TreeSet<Integer> lost = new TreeSet<>(halLost);
        int leading = 0;
        while (lost.contains(leading)) leading++;
        final boolean pattern = flushed && leading >= 1 && leading < requests;
        shots++;
        String change = "";
        if (flushed) {
            flushedShots++;
            if (pattern) {
                leadingLossShots++;
                votes++;
                if (votes >= VOTES && !skip) {
                    skip = true;
                    change = ": the HAL loses the first requests after a flush, later shots of this camera skip the flush";
                }
            } else votes = 0;
        }
        return String.format(Locale.ROOT, "flush loss camera=%s flushed=%b requests=%d halLost=%s leading=%d votes=%d/%d skip=%b"
                        + " (shots %d, flushed %d, leading loss %d)%s", camera, flushed, requests, lost, leading, votes, VOTES, skip,
                shots, flushedShots, leadingLossShots, change);
    }

    /** Tests: forget every camera. */
    static void resetForTest() { CAMERAS.clear(); }
}
