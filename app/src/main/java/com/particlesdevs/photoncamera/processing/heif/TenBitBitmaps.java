package com.particlesdevs.photoncamera.processing.heif;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.os.Build;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * The RGBA_1010102 final image of the 10-bit HEIC (Android 13+) on its way from the post pipeline to the encoders.
 * Bitmap.createBitmap(source, x, y, w, h[, matrix, filter]) - and so createScaledBitmap - turns an RGBA_1010102 source into
 * ARGB_8888, and copyPixelsTo/FromBuffer only work on the whole bitmap: these helpers keep the config and move rows through
 * small strip bitmaps drawn with SRC (Skia copies equal configs in the same colour space unchanged: the sprite blit). Every
 * method also takes any other config and then behaves like the platform call it replaces.
 */
public final class TenBitBitmaps {
    /** Rows per strip of the row reader / writer and of {@link #rgba8}. */
    public static final int STRIP_ROWS = 64;

    private TenBitBitmaps() {}

    /** Whether {@code bitmap} is the 10-bit final image (RGBA_1010102, Android 13+). */
    public static boolean isTenBit(Bitmap bitmap) {
        return bitmap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && bitmap.getConfig() == Bitmap.Config.RGBA_1010102;
    }

    static Paint copyPaint() {
        final Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        p.setFilterBitmap(false);
        p.setDither(false);
        return p;
    }

    /** A new bitmap with the config, alpha and colour space of {@code like}. */
    public static Bitmap like(Bitmap like, int width, int height) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && like.getColorSpace() != null)
            return Bitmap.createBitmap(width, height, like.getConfig(), like.hasAlpha(), like.getColorSpace());
        return Bitmap.createBitmap(width, height, like.getConfig());
    }

    /** Bitmap.createBitmap(source, x, y, w, h) that keeps an RGBA_1010102 source 10-bit (exact copy of the pixels). */
    public static Bitmap crop(Bitmap source, int x, int y, int width, int height) {
        if (!isTenBit(source)) return Bitmap.createBitmap(source, x, y, width, height);
        final Bitmap out = like(source, width, height);
        new Canvas(out).drawBitmap(source, new Rect(x, y, x + width, y + height), new Rect(0, 0, width, height), copyPaint());
        return out;
    }

    /**
     * Bitmap.createScaledBitmap that keeps an RGBA_1010102 source 10-bit: the same canvas draw the platform makes (scale
     * matrix, bitmap filtering when {@code filter}), into a destination of the source's config.
     */
    public static Bitmap scaled(Bitmap source, int width, int height, boolean filter) {
        if (!isTenBit(source)) return Bitmap.createScaledBitmap(source, width, height, filter);
        if (width == source.getWidth() && height == source.getHeight()) return source;
        final Bitmap out = like(source, width, height);
        final Matrix m = new Matrix();
        m.setScale(width / (float) source.getWidth(), height / (float) source.getHeight());
        final Paint paint = new Paint();
        paint.setFilterBitmap(filter);
        final Canvas canvas = new Canvas(out);
        canvas.concat(m);
        canvas.drawBitmap(source, 0, 0, paint);
        return out;
    }

    /** An ARGB_8888 copy (sRGB, 8 bits) for the 8-bit encoders: JPEG, Ultra HDR, WebP, the 8-bit HEIC. */
    public static Bitmap toArgb8888(Bitmap source) {
        final Bitmap out = source.copy(Bitmap.Config.ARGB_8888, false);
        if (out == null) throw new IllegalStateException("no ARGB_8888 copy of " + source.getConfig());
        return out;
    }

    /**
     * Tightly packed RGBA8 bytes of any bitmap (direct buffer, position 0): the raw pixels of an ARGB_8888 bitmap, the
     * others converted to 8-bit sRGB strip by strip (no full-size 8-bit copy next to the source), a hardware bitmap through
     * a software copy.
     */
    public static ByteBuffer rgba8(Bitmap source) {
        final int w = source.getWidth(), h = source.getHeight();
        final ByteBuffer out = ByteBuffer.allocateDirect(w * h * 4);
        if (source.getConfig() == Bitmap.Config.ARGB_8888) {
            source.copyPixelsToBuffer(out);
            out.position(0);
            return out;
        }
        Bitmap src = source;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.getConfig() == Bitmap.Config.HARDWARE)
            src = source.copy(Bitmap.Config.ARGB_8888, false);
        try {
            if (src.getConfig() == Bitmap.Config.ARGB_8888) {
                src.copyPixelsToBuffer(out);
            } else {
                final Paint paint = copyPaint();
                Bitmap strip = null;
                for (int y = 0; y < h; y += STRIP_ROWS) {
                    final int rows = Math.min(STRIP_ROWS, h - y);
                    if (strip == null || strip.getHeight() != rows) {
                        if (strip != null) strip.recycle();
                        strip = Bitmap.createBitmap(w, rows, Bitmap.Config.ARGB_8888);
                    }
                    new Canvas(strip).drawBitmap(src, new Rect(0, y, w, y + rows), new Rect(0, 0, w, rows), paint);
                    strip.copyPixelsToBuffer(out);
                }
                if (strip != null) strip.recycle();
            }
        } finally {
            if (src != source) src.recycle();
        }
        out.position(0);
        return out;
    }

    /** Reads rows of a bitmap of any non-hardware config as raw 32-bit pixels (RGBA_1010102: R in the low 10 bits). */
    public static final class RowReader implements AutoCloseable {
        private final Bitmap source;
        private final Bitmap strip;
        private final Canvas canvas;
        private final Paint paint = copyPaint();
        private final ByteBuffer bytes;
        private final IntBuffer ints;
        private final int width;
        private final Rect from = new Rect(), to = new Rect();

        public RowReader(Bitmap source, int maxRows) {
            if (source.getRowBytes() != source.getWidth() * 4) throw new IllegalArgumentException("not a 32-bit bitmap: " + source.getConfig());
            this.source = source;
            this.width = source.getWidth();
            strip = like(source, width, Math.max(1, Math.min(maxRows, source.getHeight())));
            canvas = new Canvas(strip);
            bytes = ByteBuffer.allocateDirect(strip.getByteCount()).order(ByteOrder.nativeOrder());
            ints = bytes.asIntBuffer();
        }

        /** Copies rows {@code y .. y+rows-1} into {@code out} (row-major, {@code width} ints per row) from {@code offset}. */
        public void read(int y, int rows, int[] out, int offset) {
            for (int r = 0; r < rows; ) {
                final int n = Math.min(strip.getHeight(), rows - r);
                from.set(0, y + r, width, y + r + n);
                to.set(0, 0, width, n);
                // Reads of one source bitmap from several threads: the draw only reads it, the lock keeps Skia's
                // bookkeeping of the source (pixel ref, generation id) single-threaded.
                synchronized (source) {
                    canvas.drawBitmap(source, from, to, paint);
                }
                bytes.clear();
                strip.copyPixelsToBuffer(bytes);
                ints.clear();
                ints.get(out, offset + r * width, n * width);
                r += n;
            }
        }

        @Override
        public void close() {
            strip.recycle();
        }
    }

    /** Writes rows of raw 32-bit pixels into a bitmap of the same layout (the inverse of {@link RowReader}). */
    public static final class RowWriter implements AutoCloseable {
        private final Bitmap destination;
        private final Bitmap strip;
        private final Canvas canvas;
        private final Paint paint = copyPaint();
        private final ByteBuffer bytes;
        private final IntBuffer ints;
        private final int width;
        private final Rect from = new Rect(), to = new Rect();

        public RowWriter(Bitmap destination, int maxRows) {
            if (destination.getRowBytes() != destination.getWidth() * 4) throw new IllegalArgumentException("not a 32-bit bitmap");
            this.destination = destination;
            this.width = destination.getWidth();
            strip = like(destination, width, Math.max(1, Math.min(maxRows, destination.getHeight())));
            canvas = new Canvas(destination);
            bytes = ByteBuffer.allocateDirect(strip.getByteCount()).order(ByteOrder.nativeOrder());
            ints = bytes.asIntBuffer();
        }

        /** Rows {@code y .. y+rows-1} of the destination from {@code in} (row-major, {@code width} ints per row). */
        public void write(int y, int rows, int[] in, int offset) {
            for (int r = 0; r < rows; ) {
                final int n = Math.min(strip.getHeight(), rows - r);
                ints.clear();
                ints.put(in, offset + r * width, n * width);
                bytes.clear(); // copyPixelsFromBuffer takes the whole strip: rows past n are stale and not drawn
                strip.copyPixelsFromBuffer(bytes);
                from.set(0, 0, width, n);
                to.set(0, y + r, width, y + r + n);
                synchronized (destination) {
                    canvas.drawBitmap(strip, from, to, paint);
                }
                r += n;
            }
        }

        @Override
        public void close() {
            strip.recycle();
        }
    }
}
