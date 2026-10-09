package com.particlesdevs.photoncamera.util;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P30: the RAW frames of one Hybrid shot in one memfd (allocator.cpp). Frames are copied out of the camera's reader straight
 * into their slot; the merge worker maps the same memfd (ScamHybridBurst.sharedBurst writes the header into the first
 * {@link #HEADER} bytes), so the ~680 MB copy into the transport before every merge is gone. A frame buffer here is an
 * arena view: Allocator.free drops its reference, the memfd goes once the shot has released the arena and every view is
 * freed. Any failure (no memfd, a frame larger than a slot, the arena full or released) gives null: the caller copies into a
 * plain native buffer as before.
 */
public final class ShotArena {
    /** Transport header and frame table (NCH v12: 128 + 32 B per frame, at most 64 frames) before the first slot. */
    public static final int HEADER = 64 << 10;
    private final int id;
    private final long slotBytes;
    private final long frameBytes;
    private final int slots;
    private final AtomicInteger next = new AtomicInteger();
    private volatile boolean released;

    private ShotArena(int id, long slotBytes, long frameBytes, int slots) {
        this.id = id; this.slotBytes = slotBytes; this.frameBytes = frameBytes; this.slots = slots;
    }

    /** An arena of {@code slots} frames of up to {@code frameBytes} each, or null. */
    public static ShotArena create(int slots, long frameBytes) {
        if (slots <= 0 || frameBytes <= 0 || slots > 64) return null;
        final long slot = (frameBytes + 4095) & ~4095L;
        final int id;
        try { id = Allocator.arenaCreate(HEADER + slot * slots); } catch (Throwable t) { return null; }
        return id > 0 ? new ShotArena(id, slot, frameBytes, slots) : null;
    }

    /** Copies {@code bytes} of {@code src} from {@code srcOffset} into the next slot; null: copy it elsewhere. */
    public ByteBuffer copy(ByteBuffer src, int srcOffset, int bytes) {
        if (released || bytes <= 0 || bytes > frameBytes) return null;
        final int slot = next.getAndIncrement();
        if (slot >= slots) return null;
        return Allocator.arenaCopy(id, HEADER + slot * slotBytes, src, srcOffset, bytes);
    }

    /** A packed RAW10 / RAW12 frame ({@code capacity} bytes of rows {@code rowStride} apart) unpacked into the next slot; null as copy(). */
    public ByteBuffer copyUnpacked(ByteBuffer src, int srcOffset, int format, int width, int rowStride, int capacity) {
        if (released || rowStride <= 0) return null;
        final int height = capacity / rowStride;
        if ((long) width * height * 2 > frameBytes || height < 1) return null;
        final int slot = next.getAndIncrement();
        if (slot >= slots) return null;
        return Allocator.arenaCopyUnpack(id, HEADER + slot * slotBytes, src, srcOffset, format, width, rowStride, height);
    }

    /** No more frames for this shot (the memfd stays until its frames are freed). */
    public void release() {
        if (released) return;
        released = true;
        Allocator.arenaRelease(id);
    }

    @Override public String toString() { return "arena " + id + " " + slots + " x " + (slotBytes >> 20) + " MB"; }
}
