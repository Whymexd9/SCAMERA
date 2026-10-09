package com.particlesdevs.photoncamera.gallery.adapters;

import com.particlesdevs.photoncamera.processing.PhotoFormat;

/**
 * P59 Gallery image decode rules (WebP & HEIC stability, owner 2026-10-09):
 * <ul>
 *   <li>Only JPEG files use Android's tiled {@link android.graphics.BitmapRegionDecoder}. Tiled decoding is only mature
 *       for JPEG; on HEIC/HEIF it crashes inside native libheif / MediaCodec when decoding tiles concurrently across threads,
 *       and fails on 10-bit HEIC; on WebP it drops tiles or leaves the image blank on VP8X/ICCP files.</li>
 *   <li>Non-JPEG files (HEIC, WebP, AVIF, DNG) load directly through Glide into a single cached bitmap.</li>
 *   <li>Glide decodes are bounded to {@link #FALLBACK_MAX_SIDE} (4096 px): a 12 MP photo is 100% full uncompressed resolution,
 *       while a 50 MP photo uses ~48 MB instead of 200 MB, preventing OOM while retaining crisp detail.</li>
 *   <li>If a file is opened right after capture while still being written or finalized, decode is retried with growing pauses.</li>
 * </ul>
 */
final class GalleryDecodeRetry {
    static final int FALLBACK_MAX_SIDE = 4096;
    static final int MAX_RETRIES = 2;
    private static final long FIRST_DELAY_MS = 700;

    private GalleryDecodeRetry() {}

    /**
     * Whether this file should be decoded with the tiled decoder (BitmapRegionDecoder).
     * Only JPEG is safe for concurrent tiled decoding on Android.
     */
    static boolean shouldUseTiledDecode(String fileName) {
        String ext = PhotoFormat.extensionOf(fileName);
        return ext.equals("jpg") || ext.equals("jpeg");
    }

    /** A tile error triggers the bitmap fallback: only before the page shows an image, and once. */
    static boolean fallBackOnTileError(boolean imageShown, boolean alreadyFellBack) {
        return !imageShown && !alreadyFellBack;
    }

    /** Whether one more decode may be tried after {@code retriesDone} retries. */
    static boolean mayRetry(int retriesDone) {
        return retriesDone >= 0 && retriesDone < MAX_RETRIES;
    }

    /** Pause before retry number {@code retriesDone + 1}: 0.7 s, then 1.4 s. */
    static long delayMs(int retriesDone) {
        return FIRST_DELAY_MS << Math.max(0, Math.min(retriesDone, 4));
    }
}
