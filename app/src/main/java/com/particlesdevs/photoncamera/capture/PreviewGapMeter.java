package com.particlesdevs.photoncamera.capture;

import java.util.Arrays;
import java.util.Locale;

/**
 * P44: how long the viewfinder stands still around a shot, from the sensor timestamps of the preview results (owner: «сделай
 * так чтобы видоискатель у нас не замирал после спуска затвора»). One line per shot, tag {@link #TAG}:
 * <pre>
 * PREVIEW_GAP camera=5 flush=true lead=0 rearm=3 aeRestore=1 gap=990 ms (frame 33 ms): preview->series 469 ms, series 5 frames
 *   163 ms, series->preview 357 ms; later max gap 163 ms; preview requests failed 0; first series frame +363 ms after the submit
 * </pre>
 * - gap: the longest standstill of the viewfinder from the last preview frame before the submit to the first one after
 *   the series: normally the last frame before the series to the first after it; with lead preview frames (preview_lead)
 *   the wait for the lead frame may be the longer one;
 * - preview->series: that last preview frame to the start of the first post-shutter frame (the HAL queue flush drops the
 *   preview frames in flight, the HAL restarts its pipeline);
 * - series: the post-shutter frames (L / S / ES), which never reach the viewfinder (they flashed dark / bright there);
 * - series->preview: the start of the last post-shutter frame to the next preview frame (its own exposure + whatever the HAL
 *   needs to resume the preview);
 * - later max gap: the largest preview interval in about a second after that (e.g. the P38 re-arm flush);
 * - first series frame after the submit: the shot-start latency the flush buys (only when the sensor clock is the
 *   elapsedRealtime clock).
 * Plain Java on the camera callback thread: fixed arrays, one formatted line per shot.
 */
final class PreviewGapMeter {
    static final String TAG = "PREVIEW_GAP";
    private static final int PRE = 16;
    private static final int POST = 128;
    /** Preview frames after the series that close the window (about a second: the P38 re-arm falls inside). */
    static final int AFTER_FRAMES = 30;
    /** The window closes this long after the submit whatever arrived (a camera that stopped, a lost series). */
    static final long TIMEOUT_NS = 4_000_000_000L;

    /** The measured pieces of one shot (ms; -1 = unknown). */
    static final class Result {
        double gapMs = -1, leadMs = -1, seriesMs = -1, tailMs = -1, laterMs = -1, frameMs = -1;
        int seriesFrames;
    }

    private final long[] pre = new long[PRE];
    private int preHead, preSize;
    private long[] before = new long[0];
    private final long[] post = new long[POST];
    private int postSize;
    private boolean open;
    private long markNs;
    private boolean clockComparable;
    private int expected, seriesCount, failed;
    private long firstSeries = Long.MAX_VALUE, lastSeries = Long.MIN_VALUE;
    private String label = "";

    /** A new session: nothing of the old one is compared with the new frames. */
    synchronized void reset() {
        preHead = 0; preSize = 0; postSize = 0; open = false;
    }

    /**
     * The series of a shot was submitted ({@code nowNs} on the elapsedRealtime clock): from here the preview frames and the
     * series' capture starts are collected. {@code expected} post-shutter requests (0 = none, the shot is all ZSL).
     * Returns the line of a previous shot whose window was still open (closed early), or null.
     */
    synchronized String markShot(String label, long nowNs, int expected, boolean clockComparable) {
        String previous = open ? close() : null;
        this.label = label == null ? "" : label;
        this.markNs = nowNs;
        this.expected = Math.max(0, expected);
        this.clockComparable = clockComparable;
        before = chronologicalPre();
        postSize = 0; seriesCount = 0; failed = 0;
        firstSeries = Long.MAX_VALUE; lastSeries = Long.MIN_VALUE;
        open = true;
        return previous;
    }

    /** The capture start of one post-shutter frame of the shot. */
    synchronized void seriesStart(long sensorTs) {
        if (!open || sensorTs <= 0) return;
        seriesCount++;
        firstSeries = Math.min(firstSeries, sensorTs);
        lastSeries = Math.max(lastSeries, sensorTs);
    }

    /** A preview request failed (a flush drops the preview frames in flight this way). */
    synchronized void previewFailed() {
        if (open) failed++;
    }

    /** One preview result; returns the shot's line when this frame closes its window, else null. */
    synchronized String preview(long sensorTs, long nowNs) {
        if (sensorTs <= 0) return null;
        pre[preHead] = sensorTs;
        preHead = (preHead + 1) % PRE;
        if (preSize < PRE) preSize++;
        if (!open) return null;
        if (postSize < POST) post[postSize++] = sensorTs;
        boolean seriesDone = seriesCount >= expected;
        int after = 0;
        // Frames after the series (any frame when the shot had no post-shutter request; the sensor clock need not be ours).
        long from = expected > 0 && seriesCount > 0 ? lastSeries : Long.MIN_VALUE;
        for (int i = 0; i < postSize; i++) if (post[i] > from) after++;
        if ((seriesDone && after >= AFTER_FRAMES) || postSize >= POST || nowNs - markNs >= TIMEOUT_NS) return close();
        return null;
    }

    private long[] chronologicalPre() {
        long[] out = new long[preSize];
        int start = (preHead - preSize + PRE) % PRE;
        for (int i = 0; i < preSize; i++) out[i] = pre[(start + i) % PRE];
        Arrays.sort(out);
        return out;
    }

    private String close() {
        open = false;
        long[] after = Arrays.copyOf(post, postSize);
        Result r = measure(before, after, seriesCount > 0 ? firstSeries : 0, seriesCount > 0 ? lastSeries : 0, seriesCount);
        double latencyMs = clockComparable && seriesCount > 0 ? (firstSeries - markNs) / 1e6 : Double.NaN;
        return line(r, latencyMs, failed, expected, label);
    }

    /**
     * The pieces of the freeze from the preview timestamps before the submit ({@code before}) and after it ({@code after}),
     * and the capture starts of the first / last post-shutter frame ({@code seriesFrames} 0 = the shot had none: the largest
     * preview interval from the last frame before the submit on is the gap). Pure, for the tests.
     */
    static Result measure(long[] before, long[] after, long firstSeries, long lastSeries, int seriesFrames) {
        Result r = new Result();
        r.seriesFrames = seriesFrames;
        r.frameMs = medianIntervalMs(before);
        long[] all = new long[before.length + after.length];
        System.arraycopy(before, 0, all, 0, before.length);
        System.arraycopy(after, 0, all, before.length, after.length);
        Arrays.sort(all);
        all = distinct(all);
        long prev = -1, next = -1;
        if (seriesFrames > 0) {
            for (long t : all) {
                if (t < firstSeries) prev = t;
                else if (t > lastSeries) { next = t; break; }
            }
            if (prev > 0) r.leadMs = (firstSeries - prev) / 1e6;
            r.seriesMs = (lastSeries - firstSeries) / 1e6;
            if (next > 0) r.tailMs = (next - lastSeries) / 1e6;
            if (prev > 0 && next > 0) {
                // The freeze is the longest standstill from the last frame before the submit to the first one after the
                // series: prev -> next, or the wait for a lead preview frame queued ahead of the series (preview_lead).
                long from = before.length > 0 ? before[before.length - 1] : prev, longest = next - prev;
                for (int i = 1; i < all.length && all[i] <= prev; i++)
                    if (all[i - 1] >= from) longest = Math.max(longest, all[i] - all[i - 1]);
                r.gapMs = longest / 1e6;
            }
        } else {
            long lastBefore = before.length == 0 ? -1 : before[before.length - 1];
            long best = -1;
            for (int i = 1; i < all.length; i++) {
                if (lastBefore > 0 && all[i - 1] < lastBefore) continue;
                long d = all[i] - all[i - 1];
                if (d > best) { best = d; prev = all[i - 1]; next = all[i]; }
            }
            if (prev > 0 && next > 0) r.gapMs = (next - prev) / 1e6;
        }
        if (next > 0) {
            long later = -1, last = next;
            for (long t : all) {
                if (t <= next) continue;
                later = Math.max(later, t - last);
                last = t;
            }
            if (later > 0) r.laterMs = later / 1e6;
        }
        return r;
    }

    static String line(Result r, double latencyMs, int failed, int expected, String label) {
        StringBuilder b = new StringBuilder(TAG.length() + 200);
        b.append(label.isEmpty() ? "" : label + " ").append("gap=").append(ms(r.gapMs)).append(" ms (frame ").append(ms(r.frameMs)).append(" ms)");
        if (r.seriesFrames > 0) {
            b.append(": preview->series ").append(ms(r.leadMs)).append(" ms, series ").append(r.seriesFrames);
            if (r.seriesFrames != expected) b.append('/').append(expected);
            b.append(" frames ").append(ms(r.seriesMs)).append(" ms, series->preview ").append(ms(r.tailMs)).append(" ms");
        } else if (expected > 0) {
            b.append(": no series frame started (0/").append(expected).append(')');
        }
        b.append("; later max gap ").append(ms(r.laterMs)).append(" ms; preview requests failed ").append(failed);
        if (!Double.isNaN(latencyMs)) b.append(String.format(Locale.US, "; first series frame %+.0f ms after the submit", latencyMs));
        return b.toString();
    }

    private static String ms(double v) {
        return v < 0 ? "?" : String.format(Locale.US, "%.0f", v);
    }

    private static double medianIntervalMs(long[] ts) {
        if (ts.length < 2) return -1;
        long[] d = new long[ts.length - 1];
        for (int i = 1; i < ts.length; i++) d[i - 1] = ts[i] - ts[i - 1];
        Arrays.sort(d);
        return d[d.length / 2] / 1e6;
    }

    private static long[] distinct(long[] sorted) {
        int n = 0;
        for (int i = 0; i < sorted.length; i++) if (i == 0 || sorted[i] != sorted[i - 1]) sorted[n++] = sorted[i];
        return Arrays.copyOf(sorted, n);
    }
}
