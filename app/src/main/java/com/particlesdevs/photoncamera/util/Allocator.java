package com.particlesdevs.photoncamera.util;

import java.nio.ByteBuffer;
public class Allocator{
    static {
        System.loadLibrary("allocator");
    }

    public static boolean binning = false;

    public native static ByteBuffer allocate(int capacity);
    /** Bits packed RAW10 / RAW12 samples are shifted left by on unpacking (the camera's declared levels are wider). */
    public native static void setPackedShift(int raw10, int raw12);

    public native static ByteBuffer allocateAndCopy(int capacity, ByteBuffer origin, int offset);
    public native static ByteBuffer allocateAndCopyConvert(int capacity, ByteBuffer origin, int width, int row_stride, int offset);
    public native static ByteBuffer allocateAndCopyConvertBinning(int capacity, ByteBuffer origin, int width, int row_stride, int offset);
    /** RAW12 (MIPI packed, 3 bytes per 2 pixels) to uint16. */
    public native static ByteBuffer allocateAndCopyConvert12(int capacity, ByteBuffer origin, int width, int row_stride, int offset);
    public native static ByteBuffer allocateAndCopyConvert12Binning(int capacity, ByteBuffer origin, int width, int row_stride, int offset);
    /** Packed MIPI RAW formats that are unpacked to uint16 on copy. */
    public static boolean isPackedRaw(int format) { return format == 0x25 || format == 0x26 || RawUnpack.isRaw14(format); }
    public native static ByteBuffer allocateAndCopyBinning(int capacity, ByteBuffer origin, int width, int height, int row_stride);

    /** Reconstructs a synthetic Bayer SR mosaic while preserving CFA phases. */
    public native static ByteBuffer reconstructMosaicSr(ByteBuffer origin,
                                                        int inputWidth, int inputHeight,
                                                        int outputWidth, int outputHeight,
                                                        int kernel);

    public native static void free(ByteBuffer buffer);

    // P30: shot arenas (allocator.cpp, ShotArena): one memfd holding the frames of a shot, shared with the merge worker.
    public native static int arenaCreate(long bytes);
    public native static ByteBuffer arenaCopy(int id, long offset, ByteBuffer origin, int originOffset, int bytes);
    public native static ByteBuffer arenaCopyUnpack(int id, long offset, ByteBuffer origin, int originOffset, int format, int width, int rowStride, int height);
    public native static boolean arenaWrite(int id, long offset, ByteBuffer origin);
    public native static long[] arenaOf(ByteBuffer buffer);
    public native static int arenaFd(int id);
    /** P371: {@code bytes} of the memfd {@code fd} (the merge worker's result; the caller keeps the fd) as an arena; 0: failed. */
    public native static int arenaAdopt(int fd, long bytes);
    /** P371 (review B1): the pages of {offset, bytes} of an adopted arena go back (read already); false: refused (a view, bounds). */
    public native static boolean arenaPunch(int id, long offset, long bytes);
    public native static void arenaRelease(int id);
    /** P91: one more view of a slot (a ring frame handed to a shot); the slot stays pinned until the view is freed. */
    public native static ByteBuffer arenaView(int id, long offset, int bytes);
    /** P91: views left of the slot at {@code offset}; 0 = free to write again, -1 = no such arena. */
    public native static int arenaSlotRefs(int id, long offset);
    public native static long getMemoryCount();
}
