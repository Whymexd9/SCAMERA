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
 * P91 The ZSL ring in the app's own memory (owner, 2026-10-11: «гибрид буфера из нашей реализации, Motion Camera и WhyCam»).
 * <ul>
 *   <li>WhyCam 1.05 / MotionCam: every RAW is copied out of the camera's reader when it arrives and its Image goes back at
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
    /** Slots beyond the ring part ({@link #SPARE}; a native FULLRES ring its own, {@link #fullResSlots}). */
    private final int spare;
    /** P198: the ring of a native 50 MP FULLRES session ({@link #createFullRes}). */
    private final boolean fullRes;
    private final boolean[] ringOwned, reserved;
    private boolean closed;

    private ZslRing(int id, long slotBytes, int slots, int spare, boolean fullRes) {
        this.id = id;
        this.slotBytes = slotBytes;
        this.slots = slots;
        this.spare = spare;
        this.fullRes = fullRes;
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
        final int id = memfd(slot, count);
        if (id <= 0) return null;
        Log.i(TAG, "ZSL ring in memfd: " + count + " slots x " + (slot >> 20) + " MB (ring " + Math.max(1, count - SPARE)
                + " + shot " + Math.min(SPARE, count - 1) + ", asked " + ringFrames + ", budget " + fit + ")");
        return new ZslRing(id, slot, count, SPARE, false);
    }

    private static int memfd(long slot, int count) {
        try {
            return Allocator.arenaCreate(ShotArena.HEADER + slot * count);
        } catch (Throwable t) {
            Log.w(TAG, "ZSL ring: no memfd (" + t + "), the camera's buffers are kept");
            return 0;
        }
    }

    /**
     * P198 (owner, 2026-10-17: «сделай так чтобы 50мп снимались в zsl и только 200мп снимались в psl»): the ring of a native 50 MP
     * FULLRES session. Its frames are 4x the 12 MP ones (8192 x 6144 x 2 B = 96 MB a slot), so it is sized by {@link #fullResSlots}
     * instead of the 12 MP rule (40 % of the memory available, at least 12 slots, 16 of them spare: 2.4 GB with 6 GB available, for a
     * ring of 9 frames); null without a memfd: such a session keeps
     * no ring (its reader holds 4 images, a held one would stall the viewfinder) and its shots take the series after the shutter.
     */
    @Nullable
    public static ZslRing createFullRes(@Nullable Context context, int ringSetting, int wanted, long frameBytes) {
        if (frameBytes <= 0) return null;
        final long slot = (frameBytes + 4095) & ~4095L;
        final long avail = availableBytes(context);
        final int[] split = fullResSlots(avail, slot, ringSetting, wanted);
        final int count = split[0] + split[1];
        final int id = memfd(slot, count);
        if (id <= 0) return null;
        Log.i(TAG, "50 MP ZSL ring in memfd: " + count + " slots x " + (slot >> 20) + " MB (ring " + split[0] + " + shot " + split[1]
                + ", asked min(ring setting " + ringSetting + ", N " + wanted + " + " + FULLRES_RING_SLACK + "), cap "
                + fullResCap(avail, slot) + " slots = min(" + FULLRES_SLOTS_MAX + ", a quarter of " + (avail >> 20) + " MB available))");
        return new ZslRing(id, slot, count, split[1], true);
    }

    /** P198: at most this many 50 MP slots (16 x 96 MB = 1.5 GB, the bytes of the 12 MP ring's 66 x 24 MB). */
    public static final int FULLRES_SLOTS_MAX = 16;
    /** P198: at least this many (a ring of 2 frames and 2 slots for the shot) whatever the memory says. */
    public static final int FULLRES_SLOTS_MIN = 4;
    /** P198: slots for a 50 MP shot's own frames: the 12 MP Hybrid plan's 3 ultrashort and 3 bracketed frames. */
    public static final int FULLRES_SHOT_SLOTS = 6;
    /** P198: ring frames beyond the shot's N (the selection drops frames of another exposure or behind a gap). */
    public static final int FULLRES_RING_SLACK = 2;

    /**
     * P198: {ring frames, shot slots} of a 50 MP ring. All of it within a quarter of the memory available (the camera's own four
     * 96 MB buffers, the HAL's and the 50 MP merge, P191's 80 % of MemAvailable, need the rest; the 12 MP rule takes 40 %) and
     * {@link #FULLRES_SLOTS_MAX}; the shot's slots up to half of that; the ring the shot's N plus {@link #FULLRES_RING_SLACK}, within
     * the ring setting and what is left. A slot the ring never reaches is never written (no memory).
     */
    public static int[] fullResSlots(long availBytes, long slotBytes, int ringSetting, int wanted) {
        final int total = fullResCap(availBytes, slotBytes);
        final int shot = Math.min(FULLRES_SHOT_SLOTS, total / 2);
        final int ring = Math.max(1, Math.min(Math.min(Math.max(1, ringSetting), Math.max(1, wanted) + FULLRES_RING_SLACK), total - shot));
        return new int[]{ring, shot};
    }

    /** P198: the slots of a 50 MP ring the memory allows (unknown memory: the maximum). */
    static int fullResCap(long availBytes, long slotBytes) {
        if (availBytes <= 0 || slotBytes <= 0) return FULLRES_SLOTS_MAX;
        return (int) Math.max(FULLRES_SLOTS_MIN, Math.min(FULLRES_SLOTS_MAX, availBytes / 4 / slotBytes));
    }

    /** MotionCam-style budget: at most 40 % of the memory available now (at least room for 12 slots). */
    static int budgetSlots(@Nullable Context context, long slotBytes) {
        return budgetSlots(availableBytes(context), slotBytes);
    }

    private static long availableBytes(@Nullable Context context) {
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
        return avail;
    }

    static int budgetSlots(long availBytes, long slotBytes) {
        if (availBytes <= 0 || slotBytes <= 0) return 64;
        return (int) Math.max(12, Math.min(128, availBytes * 2 / 5 / slotBytes));
    }

    /** How many frames the ring part holds. */
    public int ringSlots() {
        return Math.max(1, slots - spare);
    }

    /** P198: a native 50 MP FULLRES session's ring. */
    public boolean fullRes() {
        return fullRes;
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
        return store(image, false);
    }

    /**
     * {@link #store(Image)}; {@code unpack}: P365 (owner, 2026-10-27: «В арк камере тоже есть живой видоискатель, сделай его
     * такимже по требованиям как там»; scam_dev zsl_ring_unpack) a packed RAW10 / RAW12 frame is unpacked into its slot as it
     * arrives (Allocator.arenaCopyUnpack, the decode of ImageFrame.materialize's after the press) and reported with RAW_SENSOR
     * geometry (rowStride w * 2), so the worker gets a zero-copy view of it (ImageFrame.viewable) and the viewfinder can draw
     * the slot itself; {@link #sourceFormat} keeps the stream's format for the merge's one-layout rule.
     */
    @Nullable
    public RingFrame store(Image image, boolean unpack) {
        final Image.Plane plane = image.getPlanes()[0];
        final ByteBuffer src = plane.getBuffer();
        final int bytes = src.capacity();
        final int format = image.getFormat(), width = image.getWidth(), height = image.getHeight();
        final boolean unpacking = unpack && unpacks(format, width);
        final long need = unpacking ? (long) width * height * 2 : bytes;
        if (bytes <= 0 || need > slotBytes) return null;
        final int slot;
        synchronized (this) {
            if (closed) return null;
            slot = freeSlotLocked();
            if (slot < 0) return null;
            ringOwned[slot] = true;
        }
        final ByteBuffer view = unpacking
                ? Allocator.arenaCopyUnpack(id, offset(slot), src, 0, format, width, plane.getRowStride(), height)
                : Allocator.arenaCopy(id, offset(slot), src, 0, bytes);
        if (view == null) {
            synchronized (this) { ringOwned[slot] = false; }
            return null;
        }
        final Frame f = unpacking
                ? new Frame(slot, view, image.getTimestamp(), android.graphics.ImageFormat.RAW_SENSOR, width, height, width * 2, 2, format)
                : new Frame(slot, view, image.getTimestamp(), format, width, height, plane.getRowStride(), plane.getPixelStride(), format);
        image.close();
        return f;
    }

    /** P365: a stream format the ring unpacks into its slot (MIPI RAW10 / RAW12 of whole 4-pixel groups). */
    static boolean unpacks(int format, int width) {
        return (format == android.graphics.ImageFormat.RAW10 || format == android.graphics.ImageFormat.RAW12) && width >= 4 && width % 4 == 0;
    }

    /** P365: the frame was unpacked into its slot on arrival (RAW_SENSOR geometry, a packed stream). */
    public static boolean unpacked(RingFrame frame) {
        return frame instanceof Frame && ((Frame) frame).sourceFormat != frame.getFormat();
    }

    /**
     * P365: the format the frame was read in from the camera ({@code fallback} for any other frame): ImageFrame.sourceFormat of a
     * frame unpacked on arrival is its stream's RAW10 / RAW12, as the post-shutter frames of the same stream (ScamHybridBurst
     * keepOneLayout merges one layout).
     */
    public static int sourceFormat(RingFrame frame, int fallback) {
        return unpacked(frame) ? ((Frame) frame).sourceFormat : fallback;
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
        /** P365: the camera's format of the frame (RAW10 / RAW12 of a frame unpacked on arrival; else {@code format}). */
        private final int sourceFormat;

        Frame(int slot, ByteBuffer view, long timestamp, int format, int width, int height, int rowStride, int pixelStride, int sourceFormat) {
            this.slot = slot;
            this.view = view;
            this.timestamp = timestamp;
            this.format = format;
            this.width = width;
            this.height = height;
            this.rowStride = rowStride;
            this.pixelStride = pixelStride;
            this.sourceFormat = sourceFormat;
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
