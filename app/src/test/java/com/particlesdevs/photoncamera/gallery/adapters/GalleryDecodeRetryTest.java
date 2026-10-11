package com.particlesdevs.photoncamera.gallery.adapters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P59: Gallery decode stability test for HEIC, WebP, AVIF, DNG and JPEG. */
public class GalleryDecodeRetryTest {
    @Test
    public void onlyJpegUsesTiledDecoder() {
        assertTrue(GalleryDecodeRetry.shouldUseTiledDecode("photo.jpg"));
        assertTrue(GalleryDecodeRetry.shouldUseTiledDecode("photo.jpeg"));
        assertTrue(GalleryDecodeRetry.shouldUseTiledDecode("DCIM/Camera/IMG_20261009_120000.JPG"));
        assertTrue(GalleryDecodeRetry.shouldUseTiledDecode("photo.JPEG"));
        assertFalse(GalleryDecodeRetry.shouldUseTiledDecode("photo.heic"));
        assertFalse(GalleryDecodeRetry.shouldUseTiledDecode("photo.HEIC"));
        assertFalse(GalleryDecodeRetry.shouldUseTiledDecode("photo.heif"));
        assertFalse(GalleryDecodeRetry.shouldUseTiledDecode("photo.webp"));
        assertFalse(GalleryDecodeRetry.shouldUseTiledDecode("photo.WEBP"));
        assertFalse(GalleryDecodeRetry.shouldUseTiledDecode("photo.avif"));
        assertFalse(GalleryDecodeRetry.shouldUseTiledDecode("photo.dng"));
        assertFalse(GalleryDecodeRetry.shouldUseTiledDecode(""));
        assertFalse(GalleryDecodeRetry.shouldUseTiledDecode(null));
    }

    @Test
    public void tileErrorFallsBackOnlyOnABlankPage() {
        assertTrue(GalleryDecodeRetry.fallBackOnTileError(false, false));
        assertFalse(GalleryDecodeRetry.fallBackOnTileError(true, false));
        assertFalse(GalleryDecodeRetry.fallBackOnTileError(false, true));
        assertFalse(GalleryDecodeRetry.fallBackOnTileError(true, true));
    }

    @Test
    public void retriesOutlastTheSlowestEncode() {
        for (int i = 0; i < 5; i++) assertTrue(GalleryDecodeRetry.mayRetry(i));
        assertFalse(GalleryDecodeRetry.mayRetry(5));
        assertFalse(GalleryDecodeRetry.mayRetry(-1));
        assertEquals(700, GalleryDecodeRetry.delayMs(0));
        assertEquals(1400, GalleryDecodeRetry.delayMs(1));
        long total = 0;
        for (int i = 0; i < 5; i++) total += GalleryDecodeRetry.delayMs(i);
        // P81: a 12 MP WebP took 7.4-7.7 s and a 12-bit AVIF 9.3 s to save on the X200 Ultra (log 2026-10-08 / 10)
        assertTrue(total > 2 * 9300);
        assertEquals(11200, GalleryDecodeRetry.delayMs(4));
        assertEquals(11200, GalleryDecodeRetry.delayMs(9));
    }

    @Test
    public void fallbackBitmapIsBounded() {
        assertEquals(4096, GalleryDecodeRetry.FALLBACK_MAX_SIDE);
    }
}
