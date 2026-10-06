package com.particlesdevs.photoncamera.util;

import android.graphics.ImageFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Packed MIPI RAW10 / RAW12 rows to uint16 (the layout of {@code ImageFormat.RAW_SENSOR}), into a caller's buffer: the RAW
 * viewfinder and the mosaic measurement develop RAW10 / RAW12 streams the same way as RAW_SENSOR (owner, 2026-10-06: the
 * Find X8 Ultra streams RAW10 by default). Native (liballocator, rows on several cores) with a Java fallback of the same bit
 * layout as {@code Allocator.allocateAndCopyConvert / Convert12}.
 */
public final class RawUnpack {
    private static final boolean NATIVE;
    static {
        boolean loaded;
        try { System.loadLibrary("allocator"); loaded = true; } catch (Throwable t) { loaded = false; }
        NATIVE = loaded;
    }
    private RawUnpack() {}

    private static native boolean nativeUnpack(ByteBuffer src, int format, int width, int height, int rowStride, ByteBuffer dst);

    /** Bytes of the image data of one packed row (without padding), 0 when the format is not packed. */
    public static int packedRowBytes(int format, int width) {
        if (format == ImageFormat.RAW10) return width * 10 / 8;
        if (format == ImageFormat.RAW12) return width * 12 / 8;
        return 0;
    }

    public static boolean isPacked(int format) { return format == ImageFormat.RAW10 || format == ImageFormat.RAW12; }

    /**
     * Unpacks {@code height} rows of {@code src} (from its position, {@code rowStride} bytes apart) into {@code dst} from 0,
     * {@code width} uint16 per row in native order. False (dst unchanged) when the geometry does not fit.
     */
    public static boolean unpack(ByteBuffer src, int format, int width, int height, int rowStride, ByteBuffer dst) {
        final int rowBytes = packedRowBytes(format, width);
        if (rowBytes <= 0 || width < 4 || height < 1 || (format == ImageFormat.RAW10 ? width % 4 : width % 2) != 0
                || rowStride < rowBytes || src == null || dst == null) return false;
        if ((long) (height - 1) * rowStride + rowBytes > src.remaining() || (long) width * height * 2 > dst.capacity()) return false;
        if (NATIVE && src.isDirect() && dst.isDirect()) {
            ByteBuffer s = src.slice();
            if (nativeUnpack(s, format, width, height, rowStride, dst)) return true;
        }
        final int base = src.position();
        ByteBuffer out = dst.duplicate().order(ByteOrder.nativeOrder());
        out.clear();
        for (int y = 0; y < height; y++) {
            int at = base + y * rowStride;
            if (format == ImageFormat.RAW10) {
                for (int x = 0; x < width; x += 4, at += 5) {
                    int b4 = src.get(at + 4) & 0xff;
                    out.putShort((short) (((src.get(at) & 0xff) << 2) | (b4 & 3)));
                    out.putShort((short) (((src.get(at + 1) & 0xff) << 2) | ((b4 >> 2) & 3)));
                    out.putShort((short) (((src.get(at + 2) & 0xff) << 2) | ((b4 >> 4) & 3)));
                    out.putShort((short) (((src.get(at + 3) & 0xff) << 2) | (b4 >> 6)));
                }
            } else {
                for (int x = 0; x < width; x += 2, at += 3) {
                    int b2 = src.get(at + 2) & 0xff;
                    out.putShort((short) (((src.get(at) & 0xff) << 4) | (b2 & 0x0f)));
                    out.putShort((short) (((src.get(at + 1) & 0xff) << 4) | (b2 >> 4)));
                }
            }
        }
        return true;
    }
}
