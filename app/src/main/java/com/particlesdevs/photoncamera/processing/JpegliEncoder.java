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

    /**
     * Null on success, else the reason; bytes may already be in {@code out} when it fails. P147: {@code threads} > 1 encodes
     * a photo of 4 MP or more in strips on that many threads (one baseline JPEG with restart markers, the same pixels;
     * 3.6x faster and ~8 % smaller than the one-thread progressive file on the X200 Ultra, tools/jpeg-check); {@code mode[0]}
     * gets the threads used (1 = one thread).
     */
    private static native String encode(Bitmap bitmap, int quality, OutputStream out, int threads, int[] mode);

    /** P147: threads of the JPEG encode - every core, at most 8; scam_dev.txt "jpeg_threads" (1 = the one-thread encode). */
    static int threads() {
        final int cores = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        final int dev = Math.round(com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevNumber("jpeg_threads", 0f));
        return dev >= 1 ? Math.min(dev, 16) : cores;
    }

    /**
     * Writes {@code bitmap} as a 4:4:4 JPEG. Throws when the native encoder is missing or failed: the caller retries with
     * {@link #compressFallback} on a fresh stream (a failed encode may have written part of a file).
     */
    public static void compress(Bitmap bitmap, int quality, OutputStream out) throws IOException {
        if (!AVAILABLE) throw new IOException("jpegli unavailable");
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) throw new IOException("jpegli needs ARGB_8888, got " + bitmap.getConfig());
        final int[] mode = {1};
        final long started = System.nanoTime();
        String error = encode(bitmap, quality, out, threads(), mode);
        if (error != null) throw new IOException("jpegli: " + error);
        Log.d(TAG, "jpegli " + bitmap.getWidth() + "x" + bitmap.getHeight() + " q" + quality + " "
                + (mode[0] > 1 ? "strips on " + mode[0] + " threads" : "one thread") + " ms=" + (System.nanoTime() - started) / 1000000);
    }

    /** Android's encoder (4:2:0), the fallback when jpegli failed. */
    public static void compressFallback(Bitmap bitmap, int quality, OutputStream out) throws IOException {
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out))
            throw new IOException("JPEG encoder failed for " + bitmap.getWidth() + "x" + bitmap.getHeight());
    }
}
