package com.particlesdevs.photoncamera.gallery.glide;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.os.Build;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;
import com.particlesdevs.photoncamera.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Glide decoder for AVIF images on Android 12+ (API 31+) using {@link ImageDecoder}.
 * Android platform {@link android.graphics.BitmapRegionDecoder} and {@link android.graphics.BitmapFactory}
 * do not support AVIF, which causes black screens in the gallery and blank thumbnails without this decoder.
 */
public final class AvifGlideDecoder implements ResourceDecoder<InputStream, Bitmap> {
    private static final String TAG = "AvifGlideDecoder";
    private final Context context;
    private final BitmapPool pool;

    public AvifGlideDecoder(Context context, BitmapPool pool) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.pool = pool;
    }

    @Override
    public boolean handles(@NonNull InputStream source, @NonNull Options options) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null;
        }
        final long t0 = System.nanoTime();
        File tmp = File.createTempFile("avif", ".tmp", context.getCacheDir());
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                for (int r; (r = source.read(buf)) > 0; ) {
                    out.write(buf, 0, r);
                }
            }
            ImageDecoder.Source decoderSource = ImageDecoder.createSource(tmp);
            Bitmap bitmap = ImageDecoder.decodeBitmap(decoderSource, (decoder, info, s) -> {
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
            if (bitmap == null) {
                throw new IOException("AVIF ImageDecoder returned null");
            }
            Log.d(TAG, "decoded " + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms (request " + width + "x" + height + ")");
            return BitmapResource.obtain(bitmap, pool);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return false;
            }
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return null;
            }
            final long t0 = System.nanoTime();
            ImageDecoder.Source decoderSource = ImageDecoder.createSource(source);
            Bitmap bitmap = ImageDecoder.decodeBitmap(decoderSource, (decoder, info, s) -> {
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
            if (bitmap == null) {
                throw new IOException("AVIF ImageDecoder returned null");
            }
            Log.d(TAG, "decoded " + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms (request " + width + "x" + height + ")");
            return BitmapResource.obtain(bitmap, pool);
        }
    }
}
