package com.particlesdevs.photoncamera.processing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The latest preview RAW frame and the sensor parameters needed to develop it,
 * handed from the capture thread to the GL preview thread.
 *
 * <p>This is what makes a live viewfinder possible at all: the viewfinder can
 * only show the pipeline's tone placement if it develops the same data the
 * pipeline will, and that means RAW, not the ISP's YUV. The frames are already
 * arriving - the ZSL ring is fed from the same stream - so no extra output
 * stream or HAL load is involved.
 *
 * <p>One frame is kept, not a queue: the viewfinder wants the newest frame, and
 * an older one is worthless the moment a newer arrives. The buffer is reused
 * across frames, so a 12.6 MP RAW costs one 25 MB allocation for the session
 * rather than one per frame.
 *
 * <p>Held statically because the producer (CaptureController's image callback)
 * and the consumer (the GL renderer) have no other connection, the same
 * arrangement {@link PreviewLook} uses.
 */
public final class LiveRawFrame {

    private static final Object LOCK = new Object();

    /**
     * Two buffers, not one. The capture thread writes while the GL thread is
     * uploading the previous frame, and sharing a single buffer let the upload
     * read bytes from two different exposures - which shows up as the
     * viewfinder flickering between colours rather than as a clean error.
     */
    private static ByteBuffer front;
    private static ByteBuffer back;
    private static int width, height, rowStride;
    private static int cfaPattern;
    private static float whiteLevel = 1023.0f;
    private static final float[] blackLevel = new float[] {0, 0, 0, 0};
    private static final float[] wbGains = new float[] {1, 1, 1};
    private static final float[] colorTransform = new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    /** Bumped on every published frame so the renderer can skip re-uploading. */
    private static int version = 0;
    private static volatile boolean enabled = false;

    private LiveRawFrame() {}

    /** The producer checks this before copying: no consumer, no copy. */
    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            synchronized (LOCK) {
                front = null;
                back = null;
                version++;
            }
        }
    }

    /**
     * Publish a frame. The plane is copied because the Image it came from is
     * recycled as soon as this returns.
     */
    public static void publish(ByteBuffer plane, int w, int h, int stride,
                               int cfa, float white, float[] black,
                               float[] gains, float[] ccm) {
        if (!enabled || plane == null) return;
        int needed = plane.remaining();
        ByteBuffer target;
        synchronized (LOCK) {
            if (back == null || back.capacity() < needed) {
                back = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder());
            }
            target = back;
        }
        // Filled outside the lock: the copy is tens of megabytes and the GL
        // thread only needs the lock long enough to swap references.
        target.clear();
        int savedPos = plane.position();
        target.put(plane);
        plane.position(savedPos);
        target.flip();
        synchronized (LOCK) {
            back = front;
            front = target;
            width = w;
            height = h;
            rowStride = stride;
            cfaPattern = cfa;
            whiteLevel = white;
            if (black != null && black.length >= 4) System.arraycopy(black, 0, blackLevel, 0, 4);
            if (gains != null && gains.length >= 3) System.arraycopy(gains, 0, wbGains, 0, 3);
            if (ccm != null && ccm.length >= 9) System.arraycopy(ccm, 0, colorTransform, 0, 9);
            version++;
        }
    }

    /** Snapshot for the GL thread; null when nothing has been published. */
    public static Frame acquire() {
        synchronized (LOCK) {
            if (front == null) return null;
            Frame f = new Frame();
            f.buffer = front.duplicate();
            f.buffer.position(0);
            f.width = width;
            f.height = height;
            f.rowStride = rowStride;
            f.cfaPattern = cfaPattern;
            f.whiteLevel = whiteLevel;
            f.blackLevel = blackLevel.clone();
            f.wbGains = wbGains.clone();
            f.colorTransform = colorTransform.clone();
            f.version = version;
            return f;
        }
    }

    public static int getVersion() {
        synchronized (LOCK) {
            return version;
        }
    }

    public static final class Frame {
        public ByteBuffer buffer;
        public int width, height, rowStride, cfaPattern, version;
        public float whiteLevel;
        public float[] blackLevel, wbGains, colorTransform;
    }
}
