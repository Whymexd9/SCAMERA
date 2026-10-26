package com.particlesdevs.photoncamera.gallery.glide;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;
import com.particlesdevs.photoncamera.gallery.raw.LibRawBridge;
import com.particlesdevs.photoncamera.gallery.raw.RawFormats;
import com.particlesdevs.photoncamera.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * P352 (owner, 2026-10-26: «галерея должна работать с любыми Рав форматами»): the grid's and the viewer's picture of a camera
 * RAW other than DNG (CR2 / CR3, NEF, ARW, RAF, ORF, RW2, PEF, SRW, 3FR, IIQ, MRW, X3F...), recognised by its first bytes
 * ({@link RawFormats#looksCameraRaw}), through LibRaw: the embedded thumbnail when it is large enough for the request, else
 * LibRaw's half-size render with the camera's white balance; turned by the RAW's orientation. The stream goes to a temporary
 * file LibRaw reads (as DngGlideDecoder does).
 */
public final class RawGlideDecoder implements ResourceDecoder<InputStream, Bitmap> {
    private static final String TAG = "RawGlideDecoder";
    /** Header bytes the RAW / DNG checks look at (the first IFD of a TIFF-based RAW sits in them). */
    static final int HEAD = 64 * 1024;
    private final Context context;
    private final BitmapPool pool;

    public RawGlideDecoder(Context context, BitmapPool pool) {
        this.context = context.getApplicationContext();
        this.pool = pool;
    }

    /** Up to {@code max} first bytes of the stream (Glide rewinds the stream for the next decoder). */
    static byte[] head(InputStream in, int max) throws IOException {
        byte[] h = new byte[max];
        int n = 0;
        while (n < max) {
            int r = in.read(h, n, max - n);
            if (r < 0) break;
            n += r;
        }
        return n == max ? h : Arrays.copyOf(h, n);
    }

    @Override
    public boolean handles(@NonNull InputStream source, @NonNull Options options) throws IOException {
        if (!LibRawBridge.available()) return false;
        byte[] h = head(source, HEAD);
        return RawFormats.looksCameraRaw(h, h.length);
    }

    @Nullable
    @Override
    public Resource<Bitmap> decode(@NonNull InputStream source, int width, int height, @NonNull Options options) throws IOException {
        final long t0 = System.nanoTime();
        File tmp = File.createTempFile("raw", ".tmp", context.getCacheDir());
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                for (int r; (r = source.read(buf)) > 0; ) out.write(buf, 0, r);
            }
            Bitmap bitmap;
            String how;
            try (LibRawBridge.RawFile raw = LibRawBridge.open(tmp)) {
                int need = Math.min(Math.max(width, height) > 0 ? Math.max(width, height) : 2048, 4096);
                int[] ts = raw.thumbnailSize();
                Bitmap thumb = Math.max(ts[0], ts[1]) >= 0.9 * Math.min(need, 1024) ? raw.thumbnail(need) : null;
                if (thumb != null) {
                    bitmap = DngGlideDecoder.orient(thumb, raw.orientation());
                    how = "thumbnail " + thumb.getWidth() + "x" + thumb.getHeight();
                } else {
                    bitmap = raw.render(); // LibRaw turns it itself
                    if (bitmap == null) {
                        Bitmap small = raw.thumbnail(need);
                        if (small == null) throw new IOException("RAW: no picture");
                        bitmap = DngGlideDecoder.orient(small, raw.orientation());
                    }
                    how = "LibRaw render " + bitmap.getWidth() + "x" + bitmap.getHeight();
                }
            }
            Log.d(TAG, how + " in " + (System.nanoTime() - t0) / 1000000 + " ms (request " + width + "x" + height + ")");
            return BitmapResource.obtain(bitmap, pool);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
