package com.particlesdevs.photoncamera.processing.ml;

import android.graphics.Bitmap;
import com.particlesdevs.photoncamera.util.Log;

/** Final resize of a successful Vivo result, before gain-map generation / encoding. */
public final class VivoPostDownscale {
    private VivoPostDownscale() {}
    private static final class Native {
        static { System.loadLibrary("lanczosDownscale"); }
        static void load() {}
    }
    private static native boolean nativeResize(Bitmap source, Bitmap destination, int lobes);

    /**
     * Resize to an explicit size in linear light. kernel: "lanczos" (3 lobes), "bicubic" (Catmull-Rom, via the Lanczos
     * path with 2 lobes), "area" (box average), "bilinear". Downscale only (the native filter supports <= 4:1).
     */
    public static Bitmap resizeTo(Bitmap source, int width, int height, String kernel) {
        if (width >= source.getWidth() && height >= source.getHeight()) return source;
        if (width * 4 < source.getWidth() || height * 4 < source.getHeight()) throw new IllegalArgumentException("Resize beyond 4:1");
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
        Log.i("VivoDownscale", kernel + ": " + source.getWidth() + "x" + source.getHeight() + " -> " + width + "x" + height
                + " ms=" + (System.nanoTime() - started) / 1000000);
        return result;
    }
}
