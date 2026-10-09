package com.particlesdevs.photoncamera.processing.avif;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;

/**
 * P65: the app's own AVIF decode for the gallery (libscameraAvif: libavif + the libaom AV1 decoder). Android's ImageDecoder
 * reads AVIF only from Android 12 and not every file there (owner, 2026-10-10: AVIF photos stayed blank in SGallery);
 * AvifGlideDecoder falls back to this.
 */
public final class AvifDecoder {
    private static final String TAG = "AvifDecoder";
    private static final boolean LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary("scameraAvif");
            loaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "native AVIF decode unavailable: " + t);
            loaded = false;
        }
        LOADED = loaded;
    }

    private AvifDecoder() {}

    public static boolean available() {
        return LOADED;
    }

    private static native byte[] decode(byte[] data, int maxSide, int threads, int[] size, String[] error);

    /**
     * The first image of an AVIF file as an ARGB_8888 bitmap of at most maxSide px on its long side (0 = full size), or null
     * (the reason is logged).
     */
    @Nullable
    public static Bitmap decodeBitmap(byte[] data, int maxSide) {
        if (!LOADED || data == null || data.length == 0) return null;
        final long t0 = System.nanoTime();
        int[] size = new int[4];
        String[] error = new String[1];
        byte[] rgba;
        try {
            rgba = decode(data, Math.max(0, maxSide), Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())), size, error);
        } catch (Throwable t) {
            Log.w(TAG, "native AVIF decode failed: " + t);
            return null;
        }
        if (rgba == null || size[0] <= 0 || size[1] <= 0 || rgba.length < size[0] * size[1] * 4) {
            Log.w(TAG, "native AVIF decode failed: " + error[0]);
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba)); // ARGB_8888 memory is RGBA bytes
        Log.d(TAG, "decoded " + size[2] + "x" + size[3] + " -> " + size[0] + "x" + size[1] + " in "
                + (System.nanoTime() - t0) / 1_000_000 + " ms");
        return bitmap;
    }
}
