package com.particlesdevs.photoncamera.util;

import java.nio.ByteBuffer;
public class Allocator{
    static {
        System.loadLibrary("allocator");
    }

    public static boolean binning = false;

    public native static ByteBuffer allocate(int capacity);

    public native static ByteBuffer allocateAndCopy(int capacity, ByteBuffer origin, int offset);
    public native static ByteBuffer allocateAndCopyConvert(int capacity, ByteBuffer origin, int width, int row_stride, int offset);
    public native static ByteBuffer allocateAndCopyConvertBinning(int capacity, ByteBuffer origin, int width, int row_stride, int offset);
    /** RAW12 (MIPI packed, 3 bytes per 2 pixels) to uint16. */
    public native static ByteBuffer allocateAndCopyConvert12(int capacity, ByteBuffer origin, int width, int row_stride, int offset);
    public native static ByteBuffer allocateAndCopyConvert12Binning(int capacity, ByteBuffer origin, int width, int row_stride, int offset);
    /** Packed MIPI RAW formats that are unpacked to uint16 on copy. */
    public static boolean isPackedRaw(int format) { return format == 0x25 || format == 0x26; }
    public native static ByteBuffer allocateAndCopyBinning(int capacity, ByteBuffer origin, int width, int height, int row_stride);

    /** Reconstructs a synthetic Bayer SR mosaic while preserving CFA phases. */
    public native static ByteBuffer reconstructMosaicSr(ByteBuffer origin,
                                                        int inputWidth, int inputHeight,
                                                        int outputWidth, int outputHeight,
                                                        int kernel);

    public native static void free(ByteBuffer buffer);
    public native static long getMemoryCount();
}
