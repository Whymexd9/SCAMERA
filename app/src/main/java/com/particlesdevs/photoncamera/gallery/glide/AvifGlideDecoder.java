package com.particlesdevs.photoncamera.gallery.glide;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.os.Build;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;
import com.bumptech.glide.request.target.Target;
import com.particlesdevs.photoncamera.processing.avif.AvifDecoder;
import com.particlesdevs.photoncamera.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Glide decoder for AVIF images. Android's {@link android.graphics.BitmapRegionDecoder} and {@link android.graphics.BitmapFactory}
 * do not read AVIF (black screens and blank thumbnails without this decoder). Android 12+ (API 31+) first tries
 * {@link ImageDecoder}; P65 (owner, 2026-10-10: AVIF photos still blank in SGallery and its compare): when the platform cannot
 * decode the file, returns nothing or an all-black bitmap, and below Android 12, the app's own decoder
 * ({@link AvifDecoder}: libavif + libaom) takes it.
 */
public final class AvifGlideDecoder implements ResourceDecoder<InputStream, Bitmap> {
    private static final String TAG = "AvifGlideDecoder";
    private final BitmapPool pool;

    public AvifGlideDecoder(Context context, BitmapPool pool) {
        this.pool = pool;
    }

    /** Whether any decoder can read AVIF here: the platform (Android 12+) or the native one. */
    static boolean decodable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || AvifDecoder.available();
    }

    @Override
    public boolean handles(@NonNull InputStream source, @NonNull Options options) throws IOException {
        if (!decodable()) return false;
        final int readLimit = 64;
        source.mark(readLimit);
        try {
            byte[] header = new byte[readLimit];
            int n = 0;
            while (n < readLimit) {
                int r = source.read(header, n, readLimit - n);
                if (r < 0) break;
                n += r;
            }
            return isAvif(header, n);
        } finally {
            try {
                source.reset();
            } catch (IOException ignored) {
            }
        }
    }

    @Nullable
    @Override
    public Resource<Bitmap> decode(@NonNull InputStream source, int width, int height, @NonNull Options options) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[1 << 16];
        for (int r; (r = source.read(buf)) > 0; ) out.write(buf, 0, r);
        return BitmapResource.obtain(decodeBytes(out.toByteArray(), width, height), pool);
    }

    /** The platform decoder first (Android 12+), the native one when it fails; IOException when neither decodes. */
    static Bitmap decodeBytes(byte[] data, int width, int height) throws IOException {
        final long t0 = System.nanoTime();
        String platformError = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Bitmap bitmap = decodePlatform(ByteBuffer.wrap(data), width, height);
                if (bitmap != null && !looksBlank(bitmap)) {
                    Log.d(TAG, "decoded " + bitmap.getWidth() + "x" + bitmap.getHeight() + " in "
                            + (System.nanoTime() - t0) / 1_000_000 + " ms (request " + width + "x" + height + ")");
                    return bitmap;
                }
                platformError = bitmap == null ? "ImageDecoder returned null" : "ImageDecoder returned a blank bitmap";
                if (bitmap != null) bitmap.recycle();
            } catch (Exception | OutOfMemoryError e) {
                platformError = String.valueOf(e);
            }
            Log.w(TAG, "platform AVIF decode failed (" + platformError + "), native decode");
        }
        Bitmap bitmap = AvifDecoder.decodeBitmap(data, maxSide(width, height));
        if (bitmap == null) throw new IOException("AVIF decode failed" + (platformError != null ? " (" + platformError + ")" : ""));
        return bitmap;
    }

    /** The long side the native decode scales to: the request's (Glide's override), 0 for the original size. */
    @VisibleForTesting
    static int maxSide(int width, int height) {
        if (width == Target.SIZE_ORIGINAL || height == Target.SIZE_ORIGINAL || width <= 0 || height <= 0) return 0;
        return Math.max(width, height);
    }

    private static Bitmap decodePlatform(ByteBuffer data, int width, int height) throws IOException {
        ImageDecoder.Source decoderSource = ImageDecoder.createSource(data);
        return ImageDecoder.decodeBitmap(decoderSource, (decoder, info, s) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            if (width > 0 && height > 0) {
                int sample = 1;
                Size size = info.getSize();
                int w = size.getWidth();
                int h = size.getHeight();
                while (w / (sample * 2) >= width && h / (sample * 2) >= height) {
                    sample *= 2;
                }
                decoder.setTargetSampleSize(sample);
            }
        });
    }

    /** All of 64 sampled pixels transparent or black: a decode that produced nothing (a photo never is). */
    @VisibleForTesting
    static boolean looksBlank(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return true;
        for (int j = 0; j < 8; j++)
            for (int i = 0; i < 8; i++) {
                int p = bitmap.getPixel((2 * i + 1) * w / 16, (2 * j + 1) * h / 16);
                if ((p & 0x00FFFFFF) != 0) return false;
            }
        return true;
    }

    /**
     * Inspects an ISOBMFF header for AVIF / AVIS brands.
     * Checks major_brand at offset 8 and compatible_brands from offset 16 up to box size.
     */
    public static boolean isAvif(byte[] data, int length) {
        if (data == null || length < 12) return false;
        // Box type 'ftyp' at bytes 4..7
        if (data[4] != 'f' || data[5] != 't' || data[6] != 'y' || data[7] != 'p') {
            return false;
        }
        // Major brand at bytes 8..11
        if (isAvifBrand(data, 8)) {
            return true;
        }
        // Compatible brands at bytes 16..min(boxSize, length)
        int boxSize = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int limit = length;
        if (boxSize > 0 && boxSize < limit) {
            limit = boxSize;
        }
        for (int i = 16; i + 4 <= limit; i += 4) {
            if (isAvifBrand(data, i)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAvifBrand(byte[] data, int offset) {
        if (offset + 4 > data.length) return false;
        return data[offset] == 'a' && data[offset + 1] == 'v' && data[offset + 2] == 'i'
                && (data[offset + 3] == 'f' || data[offset + 3] == 's');
    }

    /**
     * Decoder for {@link ByteBuffer} sources of AVIF images.
     */
    public static final class ByteBufferDecoder implements ResourceDecoder<ByteBuffer, Bitmap> {
        private final BitmapPool pool;

        public ByteBufferDecoder(BitmapPool pool) {
            this.pool = pool;
        }

        @Override
        public boolean handles(@NonNull ByteBuffer source, @NonNull Options options) {
            if (!decodable()) return false;
            int len = source.remaining();
            if (len < 12) return false;
            byte[] header = new byte[Math.min(len, 64)];
            int pos = source.position();
            source.get(header);
            source.position(pos);
            return isAvif(header, header.length);
        }

        @Nullable
        @Override
        public Resource<Bitmap> decode(@NonNull ByteBuffer source, int width, int height, @NonNull Options options) throws IOException {
            byte[] data = new byte[source.remaining()];
            source.duplicate().get(data);
            return BitmapResource.obtain(decodeBytes(data, width, height), pool);
        }
    }
}
