package com.particlesdevs.photoncamera.capture;

import java.util.ArrayDeque;
import java.util.HashSet;

/**
 * STILL requests of a series; each RAW and its result must share the sensor timestamp. By default one request is in flight at
 * a time. P179 (owner, 2026-10-16: «Почему каждый кадр так долго снимался? Исправь»): a series may keep up to
 * {@code maxInFlight} requests queued, so they reach the sensor back to back instead of each one waiting behind the repeating
 * preview requests the HAL already holds (200 MP: about 13 preview frames, 5 s, per frame one at a time). Every frame is still
 * matched on its own (started, RAW, result, JPEG when asked for, sequence), and the frames complete in their order.
 * P222: the listener hears when a frame becomes the oldest one in flight ({@link Listener#head}), the moment its own timeout
 * starts (one in flight: right after its submit, as before).
 */
public final class SerialRawCapture {
    public interface Listener {
        void submit(int index);
        void complete();
        void failed(String reason);
        /** P222: frame {@code index} is now the oldest one not complete (once per frame, after its submit). */
        default void head(int index) { }
    }

    private static final class Frame {
        final int index;
        long timestamp;
        boolean raw, result, jpeg, sequence;

        Frame(int index) {
            this.index = index;
        }
    }

    private final int count;
    private final boolean requiresJpeg;
    private int maxInFlight;
    private final Listener listener;
    private final HashSet<Long> timestamps = new HashSet<>();
    /** The submitted frames not yet complete, in their order. */
    private final ArrayDeque<Frame> pending = new ArrayDeque<>();
    /** JPEG timestamps that came before their frame's start. */
    private final HashSet<Long> earlyJpeg = new HashSet<>();
    private int next, done, head = -1;
    private boolean started, finished;

    public SerialRawCapture(int count, Listener listener) {
        this(count, true, listener);
    }

    public SerialRawCapture(int count, boolean requiresJpeg, Listener listener) {
        this(count, requiresJpeg, 1, listener);
    }

    /** Fewer requests in flight (before the first is submitted): a later still built from an earlier one's result. */
    void limitInFlight(int max) {
        maxInFlight = Math.max(1, Math.min(maxInFlight, max));
    }

    public SerialRawCapture(int count, boolean requiresJpeg, int maxInFlight, Listener listener) {
        if (count < 1 || count > 64 || listener == null || maxInFlight < 1) throw new IllegalArgumentException("Invalid serial RAW series");
        this.count = count;
        this.requiresJpeg = requiresJpeg;
        this.maxInFlight = maxInFlight;
        this.listener = listener;
    }

    public synchronized void start() {
        if (started || finished) throw new IllegalStateException("Series already started");
        started = true;
        fill();
    }

    public synchronized void started(int requestIndex, long sensorTimestamp) {
        Frame f = byIndex(requestIndex);
        if (finished || f == null) return;
        if (sensorTimestamp <= 0 || f.timestamp != 0 || !timestamps.add(sensorTimestamp)) {
            fail("Invalid or duplicate RAW capture timestamp");
            return;
        }
        f.timestamp = sensorTimestamp;
        f.jpeg = earlyJpeg.remove(sensorTimestamp);
        check();
    }

    public synchronized boolean acceptsRaw(long sensorTimestamp) {
        Frame f = byTimestamp(sensorTimestamp);
        return !finished && f != null && !f.raw;
    }

    public synchronized void raw(long sensorTimestamp) {
        if (!acceptsRaw(sensorTimestamp)) return;
        byTimestamp(sensorTimestamp).raw = true;
        check();
    }

    public synchronized void result(int requestIndex, long sensorTimestamp) {
        Frame f = byIndex(requestIndex);
        if (finished || f == null) return;
        if (f.timestamp <= 0 || sensorTimestamp != f.timestamp || f.result) {
            fail("RAW result does not match its capture start");
            return;
        }
        f.result = true;
        check();
    }

    public synchronized void jpeg(long sensorTimestamp) {
        if (finished || !started || sensorTimestamp <= 0) return;
        Frame f = byTimestamp(sensorTimestamp);
        if (f != null) {
            f.jpeg = true;
            check();
            return;
        }
        // before its frame started: kept for the frame that starts at it (a mismatched one is ignored)
        for (Frame p : pending) if (p.timestamp == 0) { earlyJpeg.add(sensorTimestamp); return; }
    }

    public synchronized void sequenceCompleted(int requestIndex) {
        Frame f = byIndex(requestIndex);
        if (finished || f == null) return;
        f.sequence = true;
        check();
    }

    /** The oldest frame not yet complete (-1 before the start, the count once all are done). */
    public synchronized int index() {
        if (!started) return -1;
        return pending.isEmpty() ? done : pending.peekFirst().index;
    }

    public synchronized boolean finished() { return finished; }

    public synchronized void fail(String reason) {
        if (finished) return;
        finished = true;
        listener.failed(reason);
    }

    public synchronized void cancel() { finished = true; }

    private Frame byIndex(int index) {
        for (Frame f : pending) if (f.index == index) return f;
        return null;
    }

    private Frame byTimestamp(long timestamp) {
        if (timestamp <= 0) return null;
        for (Frame f : pending) if (f.timestamp == timestamp) return f;
        return null;
    }

    private boolean complete(Frame f) {
        return f.raw && f.result && (!requiresJpeg || f.jpeg) && f.sequence;
    }

    private void check() {
        while (!pending.isEmpty() && complete(pending.peekFirst())) {
            pending.pollFirst();
            done++;
        }
        if (done == count) {
            finished = true;
            listener.complete();
            return;
        }
        fill();
    }

    private void fill() {
        while (!finished && pending.size() < maxInFlight && next < count) {
            Frame f = new Frame(next++);
            pending.addLast(f);
            listener.submit(f.index);
        }
        final Frame oldest = pending.peekFirst();
        if (!finished && oldest != null && oldest.index != head) {
            head = oldest.index;
            listener.head(head);
        }
    }
}
