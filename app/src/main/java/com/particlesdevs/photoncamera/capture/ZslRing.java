package com.particlesdevs.photoncamera.capture;

import android.app.ActivityManager;
import android.content.Context;
import android.media.Image;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.processing.RingFrame;
import com.particlesdevs.photoncamera.util.Allocator;
import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.util.ShotArena;

import java.nio.ByteBuffer;

/**
 * P91 The ZSL ring in the app's own memory (owner, 2026-10-11: «гибрид буфера из нашей реализации, Motion Camera и ArkCam»).
 * <ul>
 *   <li>ArkCam 1.05 / MotionCam: every RAW is copied out of the camera's reader when it arrives and its Image goes back at
 *       once, so the reader holds a handful of buffers instead of the whole ring (ours held ring + 3 = 53 HAL buffers, ~1.2 GB of
 *       camera memory), and the ring keeps filling while a shot is in flight;</li>
 *   <li>MotionCam: the depth is bounded by a memory budget;</li>
 *   <li>ours: one memfd the merge worker maps (ShotArena): a ring frame of a plain RAW16 stream goes to the worker as it is (a
 *       pinned view of its slot, no copy after the press), other frames are converted into free slots of the same memfd, and the
 *       shot's post-shutter frames are written there too, so the whole burst reaches the worker without the transport copy;
 *       the frame selection (exposure tiers, stale gaps, steadiest frames) stays as it was.</li>
 * </ul>
 * A slot is written again only when no view of it is left (Allocator.arenaSlotRefs): a shot's frames stay pinned until the
 * merge frees them. The first {@link ShotArena#HEADER} bytes are the transport header (shots are merged one after another).
 */
public final class ZslRing {
    private static final String TAG = "SCAM_CAPTURE";
    /** Slots beyond the ring for one shot's own frames (the post-shutter series, converted ring frames) while the ring runs on. */
    static final int SPARE = 16;

    private final int id;
    private final long slotBytes;
    private final int slots;
    private final boolean[] ringOwned, reserved;
    private boolean closed;

    private ZslRing(int id, long slotBytes, int slots) {
        this.id = id;
        this.slotBytes = slotBytes;
        this.slots = slots;
        this.ringOwned = new boolean[slots];
        this.reserved = new boolean[slots];
    }

    /**
     * A ring for {@code ringFrames} frames of up to {@code frameBytes} (the larger of the camera's plane and its 16-bit unpacked
     * size) plus {@link #SPARE}, within the memory budget; null without a memfd (the caller keeps the camera's Images).
     */
    @Nullable
    public static ZslRing create(@Nullable Context context, int ringFrames, long frameBytes) {
        if (frameBytes <= 0 || ringFrames <= 0) return null;
        final long slot = (frameBytes + 4095) & ~4095L;
        final int wanted = ringFrames + SPARE;
        final int fit = budgetSlots(context, slot);
        final int count = Math.max(Math.min(8, ringFrames) + 4, Math.min(wanted, fit));
        final int id;
        try {
            id = Allocator.arenaCreate(ShotArena.HEADER + slot * count);
        } catch (Throwable t) {
            Log.w(TAG, "ZSL ring: no memfd (" + t + "), the camera's buffers are kept");
            return null;
        }
        if (id <= 0) return null;
        Log.i(TAG, "ZSL ring in memfd: " + count + " slots x " + (slot >> 20) + " MB (ring " + Math.max(1, count - SPARE)
                + " + shot " + Math.min(SPARE, count - 1) + ", asked " + ringFrames + ", budget " + fit + ")");
        return new ZslRing(id, slot, count);
    }

    /** MotionCam-style budget: at most 40 % of the memory available now (at least room for 12 slots). */
    static int budgetSlots(@Nullable Context context, long slotBytes) {
        long avail = 0;
        try {
            ActivityManager am = context == null ? null : (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                avail = info.availMem;
            }
        } catch (RuntimeException ignored) {
        }
        return budgetSlots(avail, slotBytes);
    }

    static int budgetSlots(long availBytes, long slotBytes) {
        if (availBytes <= 0 || slotBytes <= 0) return 64;
        return (int) Math.max(12, Math.min(128, availBytes * 2 / 5 / slotBytes));
    }

    /** How many frames the ring part holds. */
    public int ringSlots() {
        return Math.max(1, slots - SPARE);
    }

    public long slotBytes() {
        return slotBytes;
    }

    private long offset(int slot) {
        return ShotArena.HEADER + slot * slotBytes;
    }

    /** A free slot (not the ring's, not reserved for a shot, no view left), or -1. */
    private int freeSlotLocked() {
        for (int i = 0; i < slots; i++)
            if (!ringOwned[i] && !reserved[i] && Allocator.arenaSlotRefs(id, offset(i)) == 0) return i;
        return -1;
    }

    /** How many ring frames are held now. */
    public synchronized int held() {
        int n = 0;
        for (boolean b : ringOwned) if (b) n++;
        return n;
    }

    /**
     * Copies the camera's frame into a free slot and gives the Image back; null (Image untouched) when no slot is free (the
     * caller drops its oldest ring frame and retries) or the frame does not fit.
     */
    @Nullable
    public RingFrame store(Image image) {
        final Image.Plane plane = image.getPlanes()[0];
        final ByteBuffer src = plane.getBuffer();
        final int bytes = src.capacity();
        if (bytes <= 0 || bytes > slotBytes) return null;
        final int slot;
        synchronized (this) {
            if (closed) return null;
            slot = freeSlotLocked();
            if (slot < 0) return null;
            ringOwned[slot] = true;
        }
        final ByteBuffer view = Allocator.arenaCopy(id, offset(slot), src, 0, bytes);
        if (view == null) {
            synchronized (this) { ringOwned[slot] = false; }
            return null;
        }
        final Frame f = new Frame(slot, view, image.getTimestamp(), image.getFormat(), image.getWidth(), image.getHeight(),
                plane.getRowStride(), plane.getPixelStride());
        image.close();
        return f;
    }

    /**
     * Free slots of this memfd for one shot's own frames (ShotArena), up to {@code count}; null when none is free. The slots stay
     * reserved until the shot releases its arena, then their views pin them.
     */
    @Nullable
    public synchronized ShotArena reserve(int count, long frameBytes) {
        if (closed || frameBytes > slotBytes || count <= 0) return null;
        long[] offsets = new long[count];
        int got = 0;
        for (int i = 0; i < slots && got < count; i++) {
            if (!ringOwned[i] && !reserved[i] && Allocator.arenaSlotRefs(id, offset(i)) == 0) {
                reserved[i] = true;
                offsets[got++] = offset(i);
            }
        }
        if (got == 0) return null;
        if (got < count) offsets = java.util.Arrays.copyOf(offsets, got);
        final long[] mine = offsets;
        return ShotArena.inRing(id, slotBytes, frameBytes, mine, () -> unreserve(mine));
    }

    private synchronized void unreserve(long[] offsets) {
        for (long o : offsets) {
            final int slot = (int) ((o - ShotArena.HEADER) / slotBytes);
            if (slot >= 0 && slot < slots) reserved[slot] = false;
        }
    }

    /** The ring is no longer used (session closed): its memfd goes once the last view of it is freed. */
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        Allocator.arenaRelease(id);
    }

    /** A ring frame in a slot; close() drops the ring's view (a shot's own views keep the slot pinned). */
    private final class Frame implements RingFrame {
        private final int slot;
        private ByteBuffer view;
        private final long timestamp;
        private final int format, width, height, rowStride, pixelStride;

        Frame(int slot, ByteBuffer view, long timestamp, int format, int width, int height, int rowStride, int pixelStride) {
            this.slot = slot;
            this.view = view;
            this.timestamp = timestamp;
            this.format = format;
            this.width = width;
            this.height = height;
            this.rowStride = rowStride;
            this.pixelStride = pixelStride;
        }

        @Override public long getTimestamp() { return timestamp; }
        @Override public int getFormat() { return format; }
        @Override public int getWidth() { return width; }
        @Override public int getHeight() { return height; }
        @Override public int rowStride() { return rowStride; }
        @Override public int pixelStride() { return pixelStride; }

        @Override public synchronized ByteBuffer buffer() {
            if (view == null) throw new IllegalStateException("ring frame closed");
            ByteBuffer b = view.duplicate();
            b.order(view.order());
            b.clear();
            return b;
        }

        @Override public long[] arenaSlot() { return new long[]{id, offset(slot)}; }

        @Override public void close() {
            final ByteBuffer v;
            synchronized (this) {
                v = view;
                view = null;
            }
            if (v == null) return;
            Allocator.free(v);
            synchronized (ZslRing.this) { ringOwned[slot] = false; }
        }
    }
}
