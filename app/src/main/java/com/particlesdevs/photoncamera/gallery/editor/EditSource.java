package com.particlesdevs.photoncamera.gallery.editor;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.util.Size;

import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.FutureTarget;
import com.particlesdevs.photoncamera.gallery.dng.DngColor;
import com.particlesdevs.photoncamera.gallery.dng.DngRaw;
import com.particlesdevs.photoncamera.gallery.raw.LibRawBridge;
import com.particlesdevs.photoncamera.gallery.raw.RawFormats;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * P351 / P352: where the editor's pixels come from, for the preview (a downscaled decode, at most {@link #PREVIEW_SIDE}) and for
 * the export (a {@link Reader} handing out regions). The preview and the export of a source always take the same route, so they
 * agree on orientation and colour:
 * <ul>
 *   <li>JPEG: BitmapFactory with a power-of-two sample (preview) / BitmapRegionDecoder tiles (export), as stored, turned by the
 *       EXIF orientation in the shader; an Ultra HDR JPEG is its SDR base picture (the copy is SDR, no gain map).</li>
 *   <li>HEIC / HEIF, WebP, PNG, GIF (first frame), AVIF (Android 9+): ImageDecoder in software; the preview in linear extended
 *       sRGB half floats (10-bit and Display P3 keep their precision and gamut), the export in the file's own colour space (P3
 *       through its matrix). Whether the platform already turned the picture is measured once against the gallery's own Glide
 *       thumbnail ({@link #orientationMatch}).</li>
 *   <li>DNG: DngRaw (the raw data, P351); other camera RAWs and DNGs DngRaw refuses: LibRaw (P352). Linear camera RGB, scene-
 *       referred, white balance by the DNG matrices.</li>
 *   <li>Anything else, or when the above fail: Glide (the gallery's decoders, turned, 8-bit sRGB).</li>
 * </ul>
 * P367: the editor's zoom reads the original through the same {@link Reader} (EditZoomLoader), a region at a time
 * ({@link Reader#decode} on its worker thread, {@link Region#upload} on the GL thread).
 */
public final class EditSource implements Closeable {
    private static final String TAG = "EditSource";
    public static final int PREVIEW_SIDE = 2048;
    /** Pixels the export writes at most (and decodes at most for a non-tiled format); lowered when memory is short. */
    public static final long OUT_MAX = 64_000_000L;
    /** RAW samples decoded at full resolution at most for the export (2 bytes each); more is binned 2x2. */
    static final long RAW_MAX = 64_000_000L;

    public enum Route {JPEG, DECODER, GLIDE, DNG, LIBRAW}

    public final Uri uri;
    public final String name;
    /** The file path MediaStore gave (Android 8 / 9 write the copy next to it), null for none. */
    @Nullable public String absolutePath;
    Route route;
    /** The original's size as the route stores it (before {@link #orientation}). */
    int fullW, fullH;
    /** EXIF / TIFF orientation the shader applies (1 when the decoder turned the picture). */
    int orientation = 1;
    @Nullable Bitmap preview;
    @Nullable DngRaw.Image previewRaw;
    EditMath.SourceColour colour;
    /** A line for the UI (a RAW's limits), null for none. */
    @Nullable String note;
    @Nullable private File copy;

    private EditSource(Uri uri, String name) {
        this.uri = uri;
        this.name = name != null ? name : "";
    }

    public boolean isRaw() {
        return route == Route.DNG || route == Route.LIBRAW;
    }

    /** The oriented size of the original (what the gallery shows), in the route's pixels. */
    public int orientedWidth() {
        return CropMath.swapsSides(orientation) ? fullH : fullW;
    }

    public int orientedHeight() {
        return CropMath.swapsSides(orientation) ? fullW : fullH;
    }

    /** The preview's texture size (as stored). */
    int previewWidth() {
        return previewRaw != null ? previewRaw.width : preview != null ? preview.getWidth() : 1;
    }

    int previewHeight() {
        return previewRaw != null ? previewRaw.height : preview != null ? preview.getHeight() : 1;
    }

    // ---------------------------------------------------------------- open

    /** Decodes the preview (a worker thread). */
    public static EditSource open(Context context, Uri uri, String name, @Nullable String mime) throws IOException {
        EditSource s = new EditSource(uri, name);
        String ext = RawFormats.extensionOf(name);
        byte[] head = head(context, uri);
        boolean dng = RawFormats.looksDng(head, head.length) || (RawFormats.isDngName(name) && !RawFormats.looksCameraRaw(head, head.length));
        boolean otherRaw = !dng && (RawFormats.looksCameraRaw(head, head.length) || RawFormats.isRawName(name) || RawFormats.isRawMime(mime));
        if (dng || otherRaw) {
            try {
                s.openRaw(context, dng);
                return s;
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "RAW route failed for " + name + ": " + e + " - the gallery's picture instead");
                s.note = Lang.t("RAW-данные не прочитаны: правка по встроенному превью", "RAW data not read: editing the embedded preview");
            }
        } else if (isJpeg(head) || ext.equals("jpg") || ext.equals("jpeg")) {
            try {
                s.openJpeg(context);
                return s;
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "JPEG route failed for " + name + ": " + e);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                s.openDecoder(context);
                return s;
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "ImageDecoder route failed for " + name + ": " + e);
            }
        }
        try {
            s.openGlide(context);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            s.close(); // every route failed: the DNG's cache copy (openRaw) goes too
            throw e;
        }
        return s;
    }

    static boolean isJpeg(byte[] h) {
        return h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
    }

    static byte[] head(Context c, Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return new byte[0];
            byte[] h = new byte[64 * 1024];
            int n = 0;
            for (int r; n < h.length && (r = in.read(h, n, h.length - n)) > 0; ) n += r;
            return java.util.Arrays.copyOf(h, n);
        } catch (IOException | RuntimeException e) {
            return new byte[0];
        }
    }

    private void openJpeg(Context c) throws IOException {
        route = Route.JPEG;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0) throw new IOException("JPEG: no size");
        fullW = o.outWidth;
        fullH = o.outHeight;
        int sample = 1;
        while (Math.max(fullW, fullH) / (sample * 2) >= PREVIEW_SIDE) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, d);
        }
        if (b == null) throw new IOException("JPEG: no picture");
        preview = normalise(b);
        orientation = exifOrientation(c, uri);
        colour = colourOf(preview);
    }

    private void openDecoder(Context c) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) throw new IOException("no ImageDecoder");
        route = Route.DECODER;
        final int[] full = new int[2];
        ImageDecoder.Source src = ImageDecoder.createSource(c.getContentResolver(), uri);
        Bitmap b = ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            Size size = info.getSize();
            full[0] = size.getWidth();
            full[1] = size.getHeight();
            float k = Math.min(1f, PREVIEW_SIDE / (float) Math.max(full[0], full[1]));
            if (k < 1f) decoder.setTargetSize(Math.max(1, Math.round(full[0] * k)), Math.max(1, Math.round(full[1] * k)));
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB));
        });
        fullW = full[0];
        fullH = full[1];
        preview = normalise(b);
        colour = colourOf(preview);
        orientation = 1;
        Bitmap ref = glideThumb(c, 96);
        if (ref != null) {
            orientation = orientationMatch(ref, preview);
            if (orientation != 1) Log.d(TAG, "decoder picture still to be turned: orientation " + orientation);
        }
    }

    private void openGlide(Context c) throws IOException {
        route = Route.GLIDE;
        Bitmap b = glideDecode(c, uri, PREVIEW_SIDE, PREVIEW_SIDE);
        if (b == null) throw new IOException(Lang.t("Не удалось открыть снимок", "Cannot open the photo"));
        preview = normalise(b);
        colour = colourOf(preview);
        orientation = 1;
        int[] size = headerSize(c);
        if (size != null && Math.abs(size[0] / (double) size[1] - preview.getWidth() / (double) preview.getHeight()) < 0.02) {
            fullW = size[0];
            fullH = size[1];
        } else if (size != null) {
            fullW = size[1];
            fullH = size[0];
        } else {
            fullW = preview.getWidth();
            fullH = preview.getHeight();
        }
    }

    private void openRaw(Context c, boolean dng) throws IOException {
        DngRaw.Image img = null;
        if (dng) {
            try {
                copy = copyToCache(c);
                ByteBuffer map = map(copy);
                DngRaw.Info info = DngRaw.probe(map);
                int bin = 1;
                while (true) {
                    int[] sz = info.size(bin);
                    if (Math.max(sz[0], sz[1]) <= PREVIEW_SIDE * 1.25 || bin >= 16) break;
                    bin *= 2;
                }
                img = DngRaw.decode(map, bin);
                route = Route.DNG;
                fullW = info.size(1)[0];
                fullH = info.size(1)[1];
                if (info.cell == 2) note = Lang.t("RAW Quad: правка в половинном разрешении (ячейки 2×2 усреднены)",
                        "Quad RAW: edited at half resolution (2x2 cells averaged)");
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "DngRaw: " + e + " - LibRaw");
                img = null;
            }
        }
        if (img == null) {
            LibRawBridge.RawFile raw = LibRawBridge.open(c, uri);
            try {
                boolean bayer = raw.bayerPhase() >= 0;
                int bin = 1;
                if (bayer) while (Math.max(raw.width(), raw.height()) / (bin * 2) > PREVIEW_SIDE * 1.25 && bin < 16) bin *= 2;
                img = raw.decode(bayer ? bin : 2);
                route = Route.LIBRAW;
                fullW = bayer ? raw.width() / 2 * 2 : raw.width();
                fullH = bayer ? raw.height() / 2 * 2 : raw.height();
                if (!bayer) note = Lang.t("RAW без фильтра Байера: проявка LibRaw", "Non-Bayer RAW: developed by LibRaw");
            } finally {
                if (img == null || img.owner != raw) raw.close();
            }
        }
        previewRaw = img;
        orientation = img.info.orientation;
        colour = new RawColour(img.info.colour, (float) img.info.baselineExposure + img.autoLiftStops);
    }

    // ---------------------------------------------------------------- colour

    /** A RAW's colour: as-shot WB from the camera neutral, Температура / Оттенок as CCT (mired) / Duv offsets, the DNG matrices. */
    static final class RawColour implements EditMath.SourceColour {
        final DngColor colour;
        final float baseStops;
        final double t0, duv0;

        RawColour(DngColor colour, float baseStops) {
            this.colour = colour;
            this.baseStops = baseStops;
            if (colour.hasMatrices()) {
                double[] cd = DngColor.cctDuv(colour.xyForNeutral(colour.asShotNeutral));
                t0 = cd[0];
                duv0 = cd[1];
            } else {
                t0 = 0;
                duv0 = 0;
            }
        }

        @Override public boolean linear() { return true; }

        @Override public boolean scene() { return true; }

        @Override public float baseStops() { return baseStops; }

        /** The white the sliders ask for: {CCT K, Duv}; null without matrices. */
        @Nullable
        double[] white(EditState s) {
            if (!colour.hasMatrices()) return null;
            double mired = 1e6 / t0 - s.get(EditState.Param.TEMPERATURE) * 1.5;
            mired = Math.max(1e6 / 50000, Math.min(1e6 / 1800, mired));
            return new double[]{1e6 / mired, duv0 + s.get(EditState.Param.TINT) * 0.0005};
        }

        @Override
        public void colour(EditState s, float[] wbMul, float[] toWork) {
            double[] w = white(s);
            double[] n;
            double[] m;
            if (w != null) {
                n = colour.neutralForXy(DngColor.xyFromCctDuv(w[0], w[1]));
                m = colour.cameraToSrgb(n);
            } else {
                float[] g = EditMath.wbGains(s.get(EditState.Param.TEMPERATURE) / 100f, s.get(EditState.Param.TINT) / 100f);
                double[] a = colour.asShotNeutral;
                n = new double[]{a[0] / g[0], a[1] / g[1], a[2] / g[2]};
                m = new double[]{1 / n[0], 0, 0, 0, 1 / n[1], 0, 0, 0, 1 / n[2]};
            }
            for (int i = 0; i < 3; i++) wbMul[i] = (float) (1 / Math.max(n[i], 1e-9));
            for (int r = 0; r < 3; r++)
                for (int k = 0; k < 3; k++) toWork[r * 3 + k] = (float) (m[r * 3 + k] * n[k]);
        }
    }

    /** The colour model of a decoded bitmap: linear F16, extended sRGB, sRGB or Display P3. */
    static EditMath.SourceColour colourOf(Bitmap b) {
        ColorSpace cs = b.getColorSpace();
        boolean linear = cs != null && cs.equals(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
                || cs != null && cs.equals(ColorSpace.get(ColorSpace.Named.LINEAR_SRGB));
        boolean p3 = cs != null && cs.equals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
        return EditMath.display(p3, linear);
    }

    /** ARGB_8888 or RGBA_F16 in a colour space colourOf knows; anything else drawn into sRGB 8-bit. */
    static Bitmap normalise(Bitmap b) {
        ColorSpace cs = b.getColorSpace();
        boolean known = cs == null || cs.equals(ColorSpace.get(ColorSpace.Named.SRGB)) || cs.equals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
                || cs.equals(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)) || cs.equals(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
                || cs.equals(ColorSpace.get(ColorSpace.Named.LINEAR_SRGB));
        boolean config = b.getConfig() == Bitmap.Config.ARGB_8888 || b.getConfig() == Bitmap.Config.RGBA_F16;
        if (known && config) return b;
        Bitmap out = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(b, 0, 0, null);
        if (out != b) b.recycle();
        return out;
    }

    // ---------------------------------------------------------------- orientation

    static int exifOrientation(Context c, Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return 1;
            int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            return o >= 1 && o <= 8 ? o : 1;
        } catch (IOException | RuntimeException e) {
            return 1;
        }
    }

    /**
     * The orientation (1..8) that turns {@code src} into the picture of {@code ref} (the gallery's own decode): 16 x 16 luma
     * cells compared under each of the 8 transforms, the smallest difference wins (1 on a tie).
     */
    static int orientationMatch(Bitmap ref, Bitmap src) {
        Bitmap r = Bitmap.createScaledBitmap(ref, 16, 16, true);
        Bitmap s = Bitmap.createScaledBitmap(src.getConfig() == Bitmap.Config.ARGB_8888 ? src : src.copy(Bitmap.Config.ARGB_8888, false), 32, 32, true);
        float[] lr = new float[256];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) lr[y * 16 + x] = luma(r.getPixel(x, y));
        int best = 1;
        double bestErr = Double.MAX_VALUE;
        for (int o = 1; o <= 8; o++) {
            CropMath.Affine inv = CropMath.orientationToDisplay(o).inverse();
            double err = 0;
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 16; x++) {
                    double[] uv = inv.apply((x + 0.5) / 16, (y + 0.5) / 16);
                    int sx = Math.max(0, Math.min(31, (int) (uv[0] * 32))), sy = Math.max(0, Math.min(31, (int) (uv[1] * 32)));
                    err += Math.abs(luma(s.getPixel(sx, sy)) - lr[y * 16 + x]);
                }
            if (err < bestErr - 1e-6) {
                bestErr = err;
                best = o;
            }
        }
        return best;
    }

    private static float luma(int argb) {
        return 0.299f * ((argb >> 16) & 0xFF) + 0.587f * ((argb >> 8) & 0xFF) + 0.114f * (argb & 0xFF);
    }

    // ---------------------------------------------------------------- Glide

    @Nullable
    static Bitmap glideDecode(Context c, Uri uri, int w, int h) {
        FutureTarget<Bitmap> f = Glide.with(c.getApplicationContext()).asBitmap().load(uri)
                .diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true).disallowHardwareConfig()
                .downsample(DownsampleStrategy.CENTER_INSIDE).submit(w, h);
        try {
            Bitmap b = f.get();
            // a copy we own (Glide pools the one it returned)
            Bitmap own = b.copy(b.getConfig() == Bitmap.Config.RGBA_F16 ? Bitmap.Config.RGBA_F16 : Bitmap.Config.ARGB_8888, false);
            Glide.with(c.getApplicationContext()).clear(f);
            return own;
        } catch (Exception e) {
            Log.w(TAG, "Glide decode: " + e);
            Glide.with(c.getApplicationContext()).clear(f);
            return null;
        }
    }

    @Nullable
    private Bitmap glideThumb(Context c, int side) {
        return glideDecode(c, uri, side, side);
    }

    @Nullable
    private int[] headerSize(Context c) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            return in != null ? com.particlesdevs.photoncamera.gallery.helper.ImageHeaderSize.read(in) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- files

    private File copyToCache(Context c) throws IOException {
        File f = File.createTempFile("edit", ".dng", c.getCacheDir());
        try (InputStream in = c.getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(f)) {
            if (in == null) throw new IOException("cannot read " + uri);
            byte[] buf = new byte[1 << 16];
            for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            throw e;
        }
        return f;
    }

    static ByteBuffer map(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r"); FileChannel ch = raf.getChannel()) {
            return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        }
    }

    @Override
    public void close() {
        if (previewRaw != null) previewRaw.close();
        if (copy != null) {
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
            copy = null;
        }
    }

    // ---------------------------------------------------------------- export reader

    /** Regions of the original for the export, in the reader's pixels (stored orientation as {@link #orientation} says). */
    interface Reader extends Closeable {
        int width();

        int height();

        /** A texture of the region (the caller deletes it unless {@link #ownsTextures()}); its colour in {@code colour[0]}. */
        int upload(EditRenderer r, int x, int y, int w, int h, EditMath.SourceColour[] colour) throws IOException;

        default boolean ownsTextures() { return false; }

        /** Regions start on even pixels (a CFA). */
        default boolean evenAligned() { return false; }

        /** P367: {@link #decode} levels are whole numbers (a RAW is binned, not resampled). */
        default boolean integerLevels() { return false; }

        /** P367: the JPEG decoder's power-of-two sample ({@link #regionSample}), 1 for the other readers. */
        default int sample() { return 1; }

        /** Why the export is smaller than the original, null when it is not. */
        @Nullable String shrinkNote();

        /**
         * P367: the region {@code x, y, w, h} (reader px) decoded on the calling thread - the zoom's worker, never the GL or UI
         * thread - into a CPU picture of about {@code level} reader px a texel (1: full resolution), its own copy (closing the
         * reader does not touch it); {@link Region#upload} puts it on the GPU later. The same pixels {@link #upload} gives an
         * export tile.
         */
        Region decode(int x, int y, int w, int h, double level) throws IOException;
    }

    /**
     * P367: a region of the original on the CPU, between the zoom's worker (decode) and the GL thread (upload): the reader px
     * it covers ({@code x, y, w, h}), its size in texels ({@code texW x texH}, {@code level} reader px a texel), its colour.
     */
    static final class Region {
        final int x, y, w, h, texW, texH;
        final double level;
        final EditMath.SourceColour colour;
        @Nullable private Bitmap bitmap;
        @Nullable private DngRaw.Image raw;

        Region(int x, int y, int w, int h, int texW, int texH, EditMath.SourceColour colour, @Nullable Bitmap bitmap, @Nullable DngRaw.Image raw) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.texW = Math.max(1, texW);
            this.texH = Math.max(1, texH);
            this.level = w / (double) this.texW;
            this.colour = colour;
            this.bitmap = bitmap;
            this.raw = raw;
        }

        /**
         * The texture (GL thread): a picture's own (the caller deletes it), a RAW's demosaiced into {@code r}'s target (valid until
         * r's next rawSource; r owns it, {@link #rendererOwnsTexture}).
         */
        int upload(EditRenderer r) {
            if (raw != null) return r.rawSource(raw, 0, 0, raw.width, raw.height);
            if (bitmap != null && !bitmap.isRecycled()) return EditRenderer.bitmapSource(bitmap);
            return 0;
        }

        boolean rendererOwnsTexture() {
            return raw != null;
        }

        /** The CPU picture's bytes. */
        long bytes() {
            if (raw != null && raw.data != null) return raw.data.capacity() * 2L;
            return bitmap != null ? bitmap.getAllocationByteCount() : 0;
        }

        /** Drops the CPU picture (after the upload, or when it is not wanted any more). */
        void free() {
            if (bitmap != null) bitmap.recycle();
            bitmap = null;
            raw = null;
        }
    }

    /** Free memory for the export's buffers (bytes; a bit under what the system says is available). */
    static long memoryBudget(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(mi);
        long avail = mi.availMem > 0 ? mi.availMem : 1L << 30;
        return (long) (avail * 0.45);
    }

    /** Opens the reader for an export that writes at most {@code outMax} pixels of a crop covering {@code cropFraction}. */
    Reader openReader(Context c, double cropFraction, long outMax) throws IOException {
        switch (route) {
            case JPEG: return new RegionReader(c, cropFraction, outMax);
            case DNG: case LIBRAW: return new RawReader(c);
            case DECODER: return new BitmapReader(c, true, outMax);
            default: return new BitmapReader(c, false, outMax);
        }
    }

    /**
     * The JPEG reader's power-of-two sample for an export of at most {@code outMax} pixels of a crop covering
     * {@code cropFraction} of a {@code fullW x fullH} picture: tiles are decoded at most 2x the output on each side (P367: the
     * zoom's reader takes the same, and opens again when a new crop changes it).
     */
    static int regionSample(int fullW, int fullH, double cropFraction, long outMax) {
        double crop = (double) fullW * fullH * cropFraction;
        int s = 1;
        while (crop / ((double) s * s) > outMax * 4.0 && s < 1 << 12) s *= 2;
        return s;
    }

    /** JPEG tiles from BitmapRegionDecoder with a power-of-two sample (a 200 MP crop over the budget at 1/2 ...). */
    private final class RegionReader implements Reader {
        final BitmapRegionDecoder decoder;
        final InputStream stream;
        final int sample;

        @SuppressWarnings("deprecation")
        RegionReader(Context c, double cropFraction, long outMax) throws IOException {
            stream = c.getContentResolver().openInputStream(uri);
            if (stream == null) throw new IOException("cannot read " + uri);
            decoder = BitmapRegionDecoder.newInstance(stream, false);
            if (decoder == null) throw new IOException("no region decoder");
            sample = regionSample(fullW, fullH, cropFraction, outMax);
        }

        @Override public int width() { return fullW / sample; }

        @Override public int height() { return fullH / sample; }

        @Override public int sample() { return sample; }

        @Override
        public int upload(EditRenderer r, int x, int y, int w, int h, EditMath.SourceColour[] colour) throws IOException {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Rect rect = new Rect(x * sample, y * sample, Math.min(fullW, (x + w) * sample), Math.min(fullH, (y + h) * sample));
            Bitmap b = decoder.decodeRegion(rect, o);
            if (b == null) throw new IOException("region " + rect + " not decoded");
            b = normalise(b);
            if (b.getWidth() != w || b.getHeight() != h) {
                Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
                if (s != b) b.recycle();
                b = s;
            }
            colour[0] = colourOf(b);
            int t = EditRenderer.bitmapSource(b);
            b.recycle();
            return t;
        }

        /** P367: the region with the decoder's power-of-two sample up to {@code level}, the rest by a filtered resize. */
        @Override
        public Region decode(int x, int y, int w, int h, double level) throws IOException {
            int p = 1;
            while (p * 2 <= level + 1e-6) p *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample * p;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Rect rect = new Rect(x * sample, y * sample, Math.min(fullW, (x + w) * sample), Math.min(fullH, (y + h) * sample));
            Bitmap b = decoder.decodeRegion(rect, o);
            if (b == null) throw new IOException("region " + rect + " not decoded");
            b = normalise(b);
            int tw = Math.max(1, (int) Math.round(w / level)), th = Math.max(1, (int) Math.round(h / level));
            if (b.getWidth() != tw || b.getHeight() != th) {
                Bitmap s = Bitmap.createScaledBitmap(b, tw, th, true);
                if (s != b) b.recycle();
                b = s;
            }
            return new Region(x, y, w, h, b.getWidth(), b.getHeight(), colourOf(b), b, null);
        }

        @Nullable
        @Override
        public String shrinkNote() {
            return sample > 1 ? Lang.t("уменьшено в " + sample + " раза по стороне (предел памяти)",
                    "reduced " + sample + "x on each side (memory limit)") : null;
        }

        @Override
        public void close() throws IOException {
            decoder.recycle();
            stream.close();
        }
    }

    /** The whole picture decoded once (at most {@code outMax} pixels), tiles cut from it. */
    private final class BitmapReader implements Reader {
        final Bitmap full;
        final boolean shrunk;

        BitmapReader(Context c, boolean decoder, long outMax) throws IOException {
            double k = Math.min(1.0, Math.sqrt(outMax / ((double) fullW * fullH)));
            long bytes = (long) (fullW * k) * (long) (fullH * k) * 8L;
            long budget = memoryBudget(c);
            if (bytes > budget) k *= Math.sqrt(budget / (double) bytes);
            final int tw = Math.max(1, (int) Math.round(fullW * k)), th = Math.max(1, (int) Math.round(fullH * k));
            Bitmap b = null;
            if (decoder && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source src = ImageDecoder.createSource(c.getContentResolver(), uri);
                b = ImageDecoder.decodeBitmap(src, (d, info, s) -> {
                    d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    if (tw != info.getSize().getWidth() || th != info.getSize().getHeight()) d.setTargetSize(tw, th);
                });
            } else {
                b = glideDecode(c, uri, tw, th);
            }
            if (b == null) throw new IOException("no picture");
            full = normalise(b);
            shrunk = k < 0.999;
        }

        @Override public int width() { return full.getWidth(); }

        @Override public int height() { return full.getHeight(); }

        @Override
        public int upload(EditRenderer r, int x, int y, int w, int h, EditMath.SourceColour[] colour) {
            Bitmap part = Bitmap.createBitmap(full, x, y, w, h);
            colour[0] = colourOf(full);
            int t = EditRenderer.bitmapSource(part);
            if (part != full) part.recycle();
            return t;
        }

        /** P367: the region cut from the decoded picture (a copy), resized with filtering when {@code level} > 1. */
        @Override
        public Region decode(int x, int y, int w, int h, double level) {
            int tw = Math.max(1, (int) Math.round(w / level)), th = Math.max(1, (int) Math.round(h / level));
            Bitmap part;
            if (tw == w && th == h) part = Bitmap.createBitmap(full, x, y, w, h);
            else {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setScale(tw / (float) w, th / (float) h);
                part = Bitmap.createBitmap(full, x, y, w, h, m, true);
            }
            if (part == full) part = full.copy(full.getConfig(), false);
            part = normalise(part);
            if (part.getWidth() != tw || part.getHeight() != th) {
                Bitmap s = Bitmap.createScaledBitmap(part, tw, th, true);
                if (s != part) part.recycle();
                part = s;
            }
            return new Region(x, y, w, h, part.getWidth(), part.getHeight(), colourOf(full), part, null);
        }

        @Nullable
        @Override
        public String shrinkNote() {
            return shrunk ? Lang.t("уменьшено до " + full.getWidth() + "×" + full.getHeight() + " (предел памяти)",
                    "reduced to " + full.getWidth() + "x" + full.getHeight() + " (memory limit)") : null;
        }

        @Override
        public void close() {
            full.recycle();
        }
    }

    /** The RAW decoded again for the export (full resolution; 2x2 binned over {@link #RAW_MAX} samples). */
    private final class RawReader implements Reader {
        final DngRaw.Image img;
        final boolean binned;

        RawReader(Context c) throws IOException {
            DngRaw.Image decoded;
            boolean bin2;
            if (route == Route.DNG) {
                ByteBuffer map = map(copy);
                DngRaw.Info info = DngRaw.probe(map);
                int[] s1 = info.size(1);
                bin2 = (long) s1[0] * s1[1] > Math.min(RAW_MAX, memoryBudget(c) / 6);
                decoded = DngRaw.decode(map, bin2 ? 2 : 1);
            } else {
                LibRawBridge.RawFile raw = LibRawBridge.open(c, uri);
                decoded = null;
                try {
                    long px = (long) raw.width() * raw.height();
                    boolean bayer = raw.bayerPhase() >= 0;
                    bin2 = bayer ? px > Math.min(RAW_MAX, memoryBudget(c) / 6) : px * 8 > memoryBudget(c);
                    decoded = raw.decode(bin2 ? 2 : 1);
                } finally {
                    if (decoded == null || decoded.owner != raw) raw.close();
                }
            }
            img = decoded;
            binned = bin2;
        }

        @Override public int width() { return img.width; }

        @Override public int height() { return img.height; }

        @Override public boolean ownsTextures() { return true; }

        @Override public boolean evenAligned() { return img.info.kind == DngRaw.KIND_BAYER; }

        @Override public boolean integerLevels() { return true; }

        @Override
        public int upload(EditRenderer r, int x, int y, int w, int h, EditMath.SourceColour[] colour) {
            colour[0] = EditSource.this.colour;
            return r.rawSource(img, x, y, w, h);
        }

        /**
         * P367: the region's samples copied (level 1) or binned {@code level x level} (same-colour samples of a CFA, as DngRaw
         * bins) into an image of its own, so the GL thread never reads the reader's buffer; a Bayer region starts on even
         * samples and spans whole 2 x level cells (rounded up inside the picture).
         */
        @Override
        public Region decode(int x, int y, int w, int h, double level) throws IOException {
            boolean cfa = img.info.kind != DngRaw.KIND_RGB;
            int l = Math.max(1, (int) Math.round(level));
            if (cfa && ((x | y) & 1) != 0) {
                w += x & 1;
                h += y & 1;
                x &= ~1;
                y &= ~1;
            }
            int step = cfa ? 2 * l : l;
            int cw = (w + step - 1) / step * step, ch = (h + step - 1) / step * step;
            if (x + cw > img.width) cw = (img.width - x) / step * step;
            if (y + ch > img.height) ch = (img.height - y) / step * step;
            if (cw < step || ch < step) throw new IOException("RAW region " + w + "x" + h + " too small at level " + l);
            int ow = cw / l, oh = ch / l;
            DngRaw.Image part = new DngRaw.Image();
            part.info = img.info;
            part.width = ow;
            part.height = oh;
            part.bin = img.bin * l;
            part.phase = img.phase;
            part.scale = img.scale;
            part.autoLiftStops = img.autoLiftStops;
            part.data = EditZoom.bin(img.data, img.width, cfa ? 1 : 3, x, y, ow, oh, l, cfa);
            return new Region(x, y, cw, ch, ow, oh, EditSource.this.colour, null, part);
        }

        @Nullable
        @Override
        public String shrinkNote() {
            return binned ? Lang.t("RAW сохранён в половинном разрешении (предел памяти)", "RAW saved at half resolution (memory limit)") : null;
        }

        @Override
        public void close() {
            img.close();
        }
    }
}
