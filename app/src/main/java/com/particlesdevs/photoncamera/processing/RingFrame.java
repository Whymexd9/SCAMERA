package com.particlesdevs.photoncamera.processing;

import android.media.Image;

import java.nio.ByteBuffer;

/**
 * P91 A RAW frame of the ZSL ring: either the camera's own Image (the ring as before) or a copy in a slot of the ring's memfd
 * (capture/ZslRing: the camera's buffer goes back at once, as in WhyCam 1.05 and MotionCam). The ring code and the shot's
 * frame selection see only this.
 */
public interface RingFrame {
    long getTimestamp();
    int getFormat();
    int getWidth();
    int getHeight();
    int rowStride();
    int pixelStride();
    /** The frame's bytes (plane 0 as the camera wrote it), position 0. */
    ByteBuffer buffer();
    /** {arena id, byte offset} of the bytes when they are a ring slot, else null. */
    long[] arenaSlot();
    /** Gives the frame back (the camera's buffer, or the ring's hold on the slot). */
    void close();

    /** The camera's Image as a ring frame. */
    static RingFrame of(Image image) {
        return new RingFrame() {
            @Override public long getTimestamp() { return image.getTimestamp(); }
            @Override public int getFormat() { return image.getFormat(); }
            @Override public int getWidth() { return image.getWidth(); }
            @Override public int getHeight() { return image.getHeight(); }
            @Override public int rowStride() { return image.getPlanes()[0].getRowStride(); }
            @Override public int pixelStride() { return image.getPlanes()[0].getPixelStride(); }
            @Override public ByteBuffer buffer() { return image.getPlanes()[0].getBuffer(); }
            @Override public long[] arenaSlot() { return null; }
            @Override public void close() { image.close(); }
        };
    }
}
