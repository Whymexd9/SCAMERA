package com.particlesdevs.photoncamera.processing.ml;

import android.graphics.Bitmap;
import com.particlesdevs.photoncamera.util.Log;

/** Final resize of a successful Vivo result, before gain-map generation / encoding. */
public final class ScamPostDownscale {
    private ScamPostDownscale() {}
    private static final class Native {
        static { System.loadLibrary("lanczosDownscale"); }
        static void load() {}
    }
    private static native boolean nativeResize(Bitmap source, Bitmap destination, int lobes);
    private static native boolean nativeResample(Bitmap source, Bitmap destination, int lobes);

    /**
     * P226 «Увеличивать кадр с кропом»: a cropped photo brought back to {@code width} x {@code height}, up (at most 1:4 per side)
     * or down, with the Lanczos-2 window (bicubic-like, little ringing on an upscale) in linear light; 10-bit stays 10-bit.
     */
    public static Bitmap scaleTo(Bitmap source, int width, int height) {
        if (width == source.getWidth() && height == source.getHeight()) return source;
        if (width <= source.getWidth() && height <= source.getHeight()) return resizeTo(source, width, height, "bicubic");
        if (width > 4 * source.getWidth() || height > 4 * source.getHeight()) throw new IllegalArgumentException("Resize beyond 1:4");
        final long started = System.nanoTime();
        final Bitmap result;
        if (com.particlesdevs.photoncamera.processing.heif.TenBitBitmaps.isTenBit(source)) {
            result = lanczosTenBit(source, width, height, 2, true);
        } else {
            Native.load();
            result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            if (!nativeResample(source, result, 2)) {
                result.recycle();
                throw new IllegalStateException("Lanczos upscale failed");
            }
        }
        Log.i("ScamDownscale", "upscale (Lanczos-2): " + source.getWidth() + "x" + source.getHeight() + " -> " + width + "x" + height
                + " ms=" + (System.nanoTime() - started) / 1000000);
        return result;
    }

    /**
     * Resize to an explicit size in linear light. kernel: "lanczos" (3 lobes), "bicubic" (Catmull-Rom, via the Lanczos
     * path with 2 lobes), "area" (box average), "bilinear". Downscale only (the native filter supports <= 4:1).
     * An RGBA_1010102 source (10-bit HEIC) is resized by {@link #resizeTenBit} and stays 10-bit; ARGB_8888 as always.
     */
    public static Bitmap resizeTo(Bitmap source, int width, int height, String kernel) {
        if (width >= source.getWidth() && height >= source.getHeight()) return source;
        if (width * 4 < source.getWidth() || height * 4 < source.getHeight()) throw new IllegalArgumentException("Resize beyond 4:1");
        if (com.particlesdevs.photoncamera.processing.heif.TenBitBitmaps.isTenBit(source)) return resizeTenBit(source, width, height, kernel);
        final long started = System.nanoTime();
        Bitmap result;
        switch (kernel == null ? "lanczos" : kernel) {
            case "area": {
                // Box average = bilinear filtering of the half-size mip chain down to the target (createScaledBitmap, filter=true)
                Bitmap cur = source;
                while (cur.getWidth() >= 2 * width && cur.getHeight() >= 2 * height) {
                    Bitmap half = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
                    if (cur != source) cur.recycle();
                    cur = half;
                }
                result = Bitmap.createScaledBitmap(cur, width, height, true);
                if (cur != source && cur != result) cur.recycle();
                break;
            }
            case "bilinear":
                result = Bitmap.createScaledBitmap(source, width, height, true);
                break;
            default: {
                final int lobes = "bicubic".equals(kernel) ? 2 : 3;
                Native.load();
                result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                boolean success = false;
                try {
                    if (!nativeResize(source, result, lobes)) throw new IllegalStateException("Lanczos resize failed");
                    success = true;
                } finally {
                    if (!success) result.recycle();
                }
            }
        }
        Log.i("ScamDownscale", kernel + ": " + source.getWidth() + "x" + source.getHeight() + " -> " + width + "x" + height
                + " ms=" + (System.nanoTime() - started) / 1000000);
        return result;
    }

    /**
     * The same kernels on the RGBA_1010102 image of the 10-bit HEIC, keeping 10 bits: the native Lanczos takes only RGBA8, so
     * "lanczos" / "bicubic" run its Java port {@link Lanczos1010102} (same weights, linear light); "area" / "bilinear" make
     * the canvas draws of createScaledBitmap into RGBA_1010102 bitmaps (createScaledBitmap itself returns ARGB_8888).
     */
    static Bitmap resizeTenBit(Bitmap source, int width, int height, String kernel) {
        final long started = System.nanoTime();
        Bitmap result;
        switch (kernel == null ? "lanczos" : kernel) {
            case "area": {
                Bitmap cur = source;
                while (cur.getWidth() >= 2 * width && cur.getHeight() >= 2 * height) {
                    Bitmap half = com.particlesdevs.photoncamera.processing.heif.TenBitBitmaps.scaled(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
                    if (cur != source) cur.recycle();
                    cur = half;
                }
                result = com.particlesdevs.photoncamera.processing.heif.TenBitBitmaps.scaled(cur, width, height, true);
                if (cur != source && cur != result) cur.recycle();
                break;
            }
            case "bilinear":
                result = com.particlesdevs.photoncamera.processing.heif.TenBitBitmaps.scaled(source, width, height, true);
                break;
            default:
                result = lanczosTenBit(source, width, height, "bicubic".equals(kernel) ? 2 : 3, false);
        }
        Log.i("ScamDownscale", kernel + " (10-bit): " + source.getWidth() + "x" + source.getHeight() + " -> " + width + "x" + height
                + " ms=" + (System.nanoTime() - started) / 1000000);
        return result;
    }

    /** The Java Lanczos on an RGBA_1010102 bitmap ({@code up}: also an upscale). */
    private static Bitmap lanczosTenBit(Bitmap source, int width, int height, int lobes, boolean up) {
        final Bitmap out = com.particlesdevs.photoncamera.processing.heif.TenBitBitmaps.like(source, width, height);
        boolean success = false;
        try {
            final int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
            Lanczos1010102.resample(() -> {
                final com.particlesdevs.photoncamera.processing.heif.TenBitBitmaps.RowReader r =
                        new com.particlesdevs.photoncamera.processing.heif.TenBitBitmaps.RowReader(source, Lanczos1010102.READ_ROWS);
                return new Lanczos1010102.Rows() {
                    @Override public void read(int y, int rows, int[] o, int offset) { r.read(y, rows, o, offset); }
                    @Override public void close() { r.close(); }
                };
            }, source.getWidth(), source.getHeight(), () -> {
                final com.particlesdevs.photoncamera.processing.heif.TenBitBitmaps.RowWriter w =
                        new com.particlesdevs.photoncamera.processing.heif.TenBitBitmaps.RowWriter(out, Lanczos1010102.WRITE_ROWS);
                return new Lanczos1010102.Sink() {
                    @Override public void write(int y, int rows, int[] in, int offset) { w.write(y, rows, in, offset); }
                    @Override public void close() { w.close(); }
                };
            }, width, height, lobes, threads, up);
            success = true;
        } catch (Exception e) {
            throw new IllegalStateException("10-bit Lanczos resize failed", e);
        } finally {
            if (!success) out.recycle();
        }
        return out;
    }
}
