package com.particlesdevs.photoncamera.gallery.raw;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.gallery.dng.DngColor;
import com.particlesdevs.photoncamera.gallery.dng.DngRaw;
import com.particlesdevs.photoncamera.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * P352: the Java side of scamera-raw.cpp (LibRaw 0.21.3, LGPL-2.1 / CDDL): a camera RAW opened from a content URI (its file
 * descriptor as /proc/self/fd/N, so nothing is copied) or a file; metadata, the embedded thumbnail, and the decoded data as a
 * {@link DngRaw.Image} - a Bayer mosaic the editor demosaics on the GPU, or LibRaw's linear camera RGB for other sensors
 * (X-Trans, Foveon, sRAW). One {@link RawFile} is used from one thread at a time.
 */
public final class LibRawBridge {
    private static final String TAG = "LibRawBridge";
    private static final boolean AVAILABLE;

    static {
        boolean ok;
        try {
            System.loadLibrary("scameraRaw");
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "LibRaw unavailable: " + t);
            ok = false;
        }
        AVAILABLE = ok;
    }

    private LibRawBridge() {}

    public static boolean available() {
        return AVAILABLE;
    }

    static native long nOpen(String path);

    static native void nClose(long h);

    static native double[] nInfo(long h);

    static native String nText(long h);

    static native byte[] nThumb(long h, int[] info);

    static native int nMosaic(long h, ByteBuffer out, int bin, int outW, int outH);

    static native ByteBuffer nProcess(long h, boolean half, boolean display, int[] dims);

    // nInfo layout
    static final int I_W = 0, I_H = 1, I_FLIP = 2, I_ISO = 3, I_SHUTTER = 4, I_APERTURE = 5, I_FOCAL = 6, I_TIME = 7,
            I_BLACK = 8, I_MAX = 9, I_COLORS = 10, I_FILTERS = 11, I_PHASE = 12, I_CAM_MUL = 13, I_PRE_MUL = 17, I_CAM_XYZ = 21,
            I_THUMB_FORMAT = 30, I_THUMB_W = 31, I_THUMB_H = 32, I_DNG = 33;

    /** An open RAW. */
    public static final class RawFile implements Closeable {
        private long h;
        @Nullable private final ParcelFileDescriptor pfd;
        public final double[] info;
        public final String make, model, lens;

        RawFile(long h, @Nullable ParcelFileDescriptor pfd) {
            this.h = h;
            this.pfd = pfd;
            info = nInfo(h);
            String[] t = nText(h).split("\n", -1);
            make = t.length > 0 ? t[0].trim() : "";
            model = t.length > 1 ? t[1].trim() : "";
            lens = t.length > 2 ? t[2].trim() : "";
        }

        public int width() { return (int) info[I_W]; }

        public int height() { return (int) info[I_H]; }

        /** EXIF orientation of LibRaw's flip. */
        public int orientation() { return RawFormats.orientationOfFlip((int) info[I_FLIP]); }

        /** The Bayer phase (DngRaw codes), -1 for a sensor the GPU demosaic does not take (X-Trans, Foveon...). */
        public int bayerPhase() { return (int) info[I_PHASE]; }

        public float iso() { return (float) info[I_ISO]; }

        public float shutter() { return (float) info[I_SHUTTER]; }

        public float aperture() { return (float) info[I_APERTURE]; }

        public float focal() { return (float) info[I_FOCAL]; }

        /** The colour: cam_xyz as the D65 calibration, the as-shot neutral 1 / cam_mul (pre_mul when the camera gave none). */
        public DngColor colour() {
            double[] mul = {info[I_CAM_MUL], info[I_CAM_MUL + 1], info[I_CAM_MUL + 2]};
            if (mul[0] <= 0 || mul[1] <= 0 || mul[2] <= 0) mul = new double[]{info[I_PRE_MUL], info[I_PRE_MUL + 1], info[I_PRE_MUL + 2]};
            double[] n = mul[0] > 0 && mul[1] > 0 && mul[2] > 0 ? new double[]{mul[1] / mul[0], 1, mul[1] / mul[2]} : new double[]{1, 1, 1};
            double[] cm = new double[9];
            System.arraycopy(info, I_CAM_XYZ, cm, 0, 9);
            return DngColor.fromCamera(cm, n);
        }

        /** The embedded thumbnail as a bitmap (JPEG or 8-bit RGB), not yet turned; null when there is none. */
        @Nullable
        public Bitmap thumbnail(int sampleFor) {
            int[] ti = new int[4];
            byte[] b = nThumb(h, ti);
            if (b == null) return null;
            if (ti[0] == 1) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(b, 0, b.length, o);
                int s = 1;
                while (sampleFor > 0 && o.outWidth / (s * 2) >= sampleFor && o.outHeight / (s * 2) >= sampleFor) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                return BitmapFactory.decodeByteArray(b, 0, b.length, o);
            }
            int w = ti[1], hh = ti[2], c = Math.max(1, ti[3]);
            if (w <= 0 || hh <= 0 || (long) w * hh * c > b.length) return null;
            int[] px = new int[w * hh];
            for (int i = 0; i < w * hh; i++) {
                int r = b[i * c] & 0xFF, g = b[i * c + (c > 1 ? 1 : 0)] & 0xFF, bl = b[i * c + (c > 2 ? 2 : 0)] & 0xFF;
                px[i] = 0xFF000000 | r << 16 | g << 8 | bl;
            }
            return Bitmap.createBitmap(px, w, hh, Bitmap.Config.ARGB_8888);
        }

        /** The thumbnail's size {w, h} from the tags (0 when none). */
        public int[] thumbnailSize() {
            return new int[]{(int) info[I_THUMB_W], (int) info[I_THUMB_H]};
        }

        /** LibRaw's own 8-bit sRGB render (camera WB, turned, half size): the gallery's picture without a thumbnail. */
        @Nullable
        public Bitmap render() {
            int[] d = new int[4];
            ByteBuffer buf = nProcess(h, true, true, d);
            if (buf == null || d[3] != 8) return null;
            int w = d[0], hh = d[1], c = d[2];
            int[] px = new int[w * hh];
            buf.rewind();
            for (int i = 0; i < w * hh; i++) {
                int r = buf.get(i * c) & 0xFF, g = buf.get(i * c + (c > 1 ? 1 : 0)) & 0xFF, b = buf.get(i * c + (c > 2 ? 2 : 0)) & 0xFF;
                px[i] = 0xFF000000 | r << 16 | g << 8 | b;
            }
            return Bitmap.createBitmap(px, w, hh, Bitmap.Config.ARGB_8888);
        }

        /**
         * The data for the editor: a Bayer sensor's mosaic (bin x bin same-colour samples averaged, DngRaw's fixed point), else
         * LibRaw's linear camera RGB (half size when {@code bin} > 1; owned by this file, so the image keeps it open).
         */
        public DngRaw.Image decode(int bin) throws IOException {
            DngRaw.Info info = new DngRaw.Info();
            info.orientation = orientation();
            info.colour = colour();
            int phase = bayerPhase();
            DngRaw.Image img = new DngRaw.Image();
            img.info = info;
            img.bin = bin;
            if (phase >= 0) {
                info.kind = DngRaw.KIND_BAYER;
                info.cropWidth = width();
                info.cropHeight = height();
                int[] size = info.size(bin);
                ByteBuffer out = ByteBuffer.allocateDirect(size[0] * size[1] * 2).order(ByteOrder.nativeOrder());
                int p = nMosaic(h, out, bin, size[0], size[1]);
                if (p < 0) throw new IOException("LibRaw: no Bayer data");
                img.width = size[0];
                img.height = size[1];
                img.phase = p;
                img.data = out.asShortBuffer();
            } else {
                info.kind = DngRaw.KIND_RGB;
                int[] d = new int[4];
                ByteBuffer buf = nProcess(h, bin > 1, false, d);
                if (buf == null || d[3] != 16 || d[2] < 3) throw new IOException("LibRaw: no RGB data");
                img.width = d[0];
                img.height = d[1];
                info.cropWidth = d[0];
                info.cropHeight = d[1];
                img.data = buf.order(ByteOrder.nativeOrder()).asShortBuffer();
                img.scale = 1f / 65535f;
                img.owner = this;
                img.note = "xtrans";
            }
            img.autoLiftStops = DngRaw.autoLift(img);
            return img;
        }

        @Override
        public synchronized void close() {
            if (h != 0) {
                nClose(h);
                h = 0;
            }
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Opens a RAW from a content / file URI (read only). */
    public static RawFile open(Context context, Uri uri) throws IOException {
        if (!AVAILABLE) throw new IOException("LibRaw is not in this build");
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) return open(new File(uri.getPath()));
        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("no descriptor for " + uri);
        long h = nOpen("/proc/self/fd/" + pfd.getFd());
        if (h == 0) {
            pfd.close();
            throw new IOException("LibRaw cannot open " + uri);
        }
        return new RawFile(h, pfd);
    }

    public static RawFile open(File file) throws IOException {
        if (!AVAILABLE) throw new IOException("LibRaw is not in this build");
        long h = nOpen(file.getAbsolutePath());
        if (h == 0) throw new IOException("LibRaw cannot open " + file);
        return new RawFile(h, null);
    }
}
