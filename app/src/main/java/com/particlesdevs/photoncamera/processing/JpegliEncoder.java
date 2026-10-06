package com.particlesdevs.photoncamera.processing;

import android.graphics.Bitmap;

import com.particlesdevs.photoncamera.util.Log;

import java.io.IOException;
import java.io.OutputStream;

/**
 * P24: the saved JPEGs (plain photo, Ultra HDR base image and gain map) are encoded by jpegli (native, scamera-jpeg.cpp),
 * YCbCr 4:4:4: Bitmap.compress always subsamples chroma 4:2:0. On the OPPO PHY110 a 12.6 MP photo at q98 is 4.55 MB in
 * ~160 ms, the size Bitmap.compress q98 4:2:0 gave for the same shot.
 */
public final class JpegliEncoder {
    private static final String TAG = "JpegliEncoder";
    private static final boolean AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("scameraJpeg");
            loaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "jpegli unavailable, Bitmap.compress (4:2:0) is used: " + t);
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private JpegliEncoder() {}

    public static boolean available() {
        return AVAILABLE;
    }

    /** Null on success, else the reason; bytes may already be in {@code out} when it fails. */
    private static native String encode(Bitmap bitmap, int quality, OutputStream out);

    /**
     * Writes {@code bitmap} as a 4:4:4 JPEG. Throws when the native encoder is missing or failed: the caller retries with
     * {@link #compressFallback} on a fresh stream (a failed encode may have written part of a file).
     */
    public static void compress(Bitmap bitmap, int quality, OutputStream out) throws IOException {
        if (!AVAILABLE) throw new IOException("jpegli unavailable");
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) throw new IOException("jpegli needs ARGB_8888, got " + bitmap.getConfig());
        String error = encode(bitmap, quality, out);
        if (error != null) throw new IOException("jpegli: " + error);
    }

    /** Android's encoder (4:2:0), the fallback when jpegli failed. */
    public static void compressFallback(Bitmap bitmap, int quality, OutputStream out) throws IOException {
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out))
            throw new IOException("JPEG encoder failed for " + bitmap.getWidth() + "x" + bitmap.getHeight());
    }
}
