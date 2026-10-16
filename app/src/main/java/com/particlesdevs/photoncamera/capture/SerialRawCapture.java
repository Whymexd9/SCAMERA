package com.particlesdevs.photoncamera.capture;

import java.util.HashSet;

/** One in-flight STILL request; RAW and its result must share the sensor timestamp. */
public final class SerialRawCapture {
    public interface Listener {
        void submit(int index);
        void complete();
        void failed(String reason);
    }

    private final int count;
    private final boolean requiresJpeg;
    private final Listener listener;
    private final HashSet<Long> timestamps = new HashSet<>();
    private int index = -1;
    private long timestamp;
    private long jpegTimestamp;
    private boolean raw, result, jpeg, sequence, finished;

    public SerialRawCapture(int count, Listener listener) {
        this(count, true, listener);
    }

    public SerialRawCapture(int count, boolean requiresJpeg, Listener listener) {
        if (count < 1 || count > 64 || listener == null) throw new IllegalArgumentException("Invalid serial RAW series");
        this.count = count;
        this.requiresJpeg = requiresJpeg;
        this.listener = listener;
    }

    public synchronized void start() {
        if (index != -1 || finished) throw new IllegalStateException("Series already started");
        advance();
    }

    public synchronized void started(int requestIndex, long sensorTimestamp) {
        if (finished || requestIndex != index) return;
        if (sensorTimestamp <= 0 || timestamp != 0 || !timestamps.add(sensorTimestamp)) {
            fail("Invalid or duplicate RAW capture timestamp");
            return;
        }
        timestamp = sensorTimestamp;
        jpeg = jpegTimestamp == timestamp;
        check();
    }

    public synchronized boolean acceptsRaw(long sensorTimestamp) {
        return !finished && !raw && timestamp > 0 && timestamp == sensorTimestamp;
    }

    public synchronized void raw(long sensorTimestamp) {
        if (!acceptsRaw(sensorTimestamp)) return;
        raw = true;
        check();
    }

    public synchronized void result(int requestIndex, long sensorTimestamp) {
        if (finished || requestIndex != index) return;
        if (timestamp <= 0 || sensorTimestamp != timestamp || result) {
            fail("RAW result does not match its capture start");
            return;
        }
        result = true;
        check();
    }

    public synchronized void jpeg(long sensorTimestamp) {
        if (finished || index < 0 || sensorTimestamp <= 0 || jpeg
                || (timestamp > 0 && sensorTimestamp != timestamp)) return;
        jpegTimestamp = sensorTimestamp;
        jpeg = timestamp > 0 && sensorTimestamp == timestamp;
        check();
    }

    public synchronized void sequenceCompleted(int requestIndex) {
        if (finished || requestIndex != index) return;
        sequence = true;
        check();
    }

    public synchronized int index() { return index; }
    public synchronized boolean finished() { return finished; }

    public synchronized void fail(String reason) {
        if (finished) return;
        finished = true;
        listener.failed(reason);
    }

    public synchronized void cancel() { finished = true; }

    private void check() {
        if (raw && result && (!requiresJpeg || jpeg) && sequence) advance();
    }

    private void advance() {
        if (++index == count) {
            finished = true;
            listener.complete();
            return;
        }
        timestamp = 0;
        jpegTimestamp = 0;
        raw = result = jpeg = sequence = false;
        listener.submit(index);
    }
}
