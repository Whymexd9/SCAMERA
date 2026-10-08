package com.particlesdevs.photoncamera.control.subject;

/**
 * Bridge between the viewfinder's GL thread and the subject tracker (P42). The renderer asks {@link #sink()} whether a
 * frame is wanted after it drew one, and hands over a downscaled RGBA copy of exactly what is on screen (ISP or
 * developed RAW, rotated, mirrored and zoomed as displayed). No sink — nothing is read back.
 */
public final class SubjectFrames {
    private SubjectFrames() {}

    /** Receives viewfinder frames; all calls come from the GL thread. */
    public interface Sink {
        /** Cheap check, every drawn frame: does the sink want a frame now (throttle inside). */
        boolean wantsFrame(long nowNs);

        /** A free buffer of at least {@code bytes}, or null to skip this frame (the worker is still busy). */
        byte[] obtainBuffer(int bytes);

        /**
         * A frame: RGBA8888, {@code width * height * 4} bytes in {@code rgba}, rows bottom-up (GL order). Ownership of
         * the buffer passes to the sink.
         */
        void onFrame(byte[] rgba, int width, int height);
    }

    private static volatile Sink sink;
    /** P60: a second consumer (FovSelfCheck), served when the primary one does not want the frame. */
    private static volatile Sink secondary;

    public static void setSecondarySink(Sink s) {
        secondary = s;
    }

    public static Sink secondarySink() {
        return secondary;
    }
    private static volatile boolean rawDisplayed;

    public static void setSink(Sink s) {
        sink = s;
    }

    /** Clears the sink only when it is still {@code s} (a newer one may already be registered). */
    public static void clearSink(Sink s) {
        if (sink == s) sink = null;
    }

    public static Sink sink() {
        return sink;
    }

    /** Set by the renderer: the developed RAW viewfinder is on screen (else the ISP preview). */
    public static void setRawDisplayed(boolean raw) {
        rawDisplayed = raw;
    }

    public static boolean rawDisplayed() {
        return rawDisplayed;
    }
}
