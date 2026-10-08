package com.particlesdevs.photoncamera.gallery.glide;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;
import com.particlesdevs.photoncamera.gallery.dng.DngPreview;
import com.particlesdevs.photoncamera.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * P59 / P53: every TIFF-based file the gallery loads through Glide (DNG) is decoded by {@link DngPreview} instead of the
 * platform raw decoder (which showed Pixel 7 main-camera DNGs black): its embedded JPEG preview when it is large enough, else
 * a render of the raw data at the requested size. The stream goes to a temporary file that is mapped (no copy of a 25-100 MB
 * DNG on the heap). A file it cannot read fails here and Glide tries its own decoders as before.
 */
public final class DngGlideDecoder implements ResourceDecoder<InputStream, Bitmap> {
    private static final String TAG = "DngGlideDecoder";
    private final Context context;
    private final BitmapPool pool;

    public DngGlideDecoder(Context context, BitmapPool pool) {
        this.context = context.getApplicationContext();
        this.pool = pool;
    }

    @Override
    public boolean handles(@NonNull InputStream source, @NonNull Options options) throws IOException {
        byte[] head = new byte[4];
        int n = 0;
        while (n < 4) {
            int r = source.read(head, n, 4 - n);
            if (r < 0) break;
            n += r;
        }
        return DngPreview.isTiff(head, n);
    }

    @Nullable
    @Override
    public Resource<Bitmap> decode(@NonNull InputStream source, int width, int height, @NonNull Options options) throws IOException {
        final long t0 = System.nanoTime();
        File tmp = File.createTempFile("dng", ".tmp", context.getCacheDir());
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                for (int r; (r = source.read(buf)) > 0; ) out.write(buf, 0, r);
            }
            Bitmap bitmap;
            String how;
            try (RandomAccessFile raf = new RandomAccessFile(tmp, "r"); FileChannel ch = raf.getChannel()) {
                MappedByteBuffer map = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
                DngPreview.Result r = DngPreview.read(map, Math.max(width, 0), Math.max(height, 0));
                if (r.isJpeg()) {
                    bitmap = jpeg(map, r, width, height);
                    how = "embedded preview " + r.width + "x" + r.height;
                } else {
                    bitmap = Bitmap.createBitmap(r.argb, r.width, r.height, Bitmap.Config.ARGB_8888);
                    how = "raw render " + r.width + "x" + r.height;
                }
                if (bitmap == null) throw new IOException("DNG preview not decodable");
                bitmap = orient(bitmap, r.orientation);
            }
            Log.d(TAG, how + " in " + (System.nanoTime() - t0) / 1000000 + " ms (request " + width + "x" + height + ")");
            return BitmapResource.obtain(bitmap, pool);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static Bitmap jpeg(ByteBuffer map, DngPreview.Result r, int width, int height) {
        byte[] data = new byte[r.jpegLength];
        ByteBuffer d = map.duplicate();
        d.position(r.jpegOffset);
        d.get(data);
        BitmapFactory.Options o = new BitmapFactory.Options();
        int sample = 1;
        if (width > 0 && height > 0)
            while (r.width / (sample * 2) >= width && r.height / (sample * 2) >= height) sample *= 2;
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    /** TIFF orientation 1..8 applied to the pixels. */
    static Bitmap orient(Bitmap src, int orientation) {
        if (orientation <= 1 || orientation > 8) return src;
        Matrix m = new Matrix();
        switch (orientation) {
            case 2: m.setScale(-1, 1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setScale(1, -1); break;
            case 5: m.setRotate(90); m.postScale(-1, 1); break;
            case 6: m.setRotate(90); break;
            case 7: m.setRotate(-90); m.postScale(-1, 1); break;
            case 8: m.setRotate(-90); break;
            default: return src;
        }
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src) src.recycle();
        return out;
    }
}
