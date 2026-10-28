package com.particlesdevs.photoncamera.gallery.editor;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.particlesdevs.photoncamera.processing.JpegliEncoder;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * P351: «Сохранить копию» - the edit at full resolution into a NEW JPEG next to the original (the original is never written; a
 * DNG / RAW source stays as it is). Runs on a worker thread with its own EGL context and the same {@link EditRenderer} and
 * uniforms as the preview, so the copy looks like the preview.
 *
 * <p>Memory: the output is rendered in tiles of {@link #TILE} output pixels; each tile reads its source region plus an
 * overlap of {@link EditMath#overlapPx} (the reach of NR, the Gaussians and the demosaic) and writes into one ARGB_8888 bitmap
 * of the output, at most {@link EditSource#OUT_MAX} pixels (64 MP, 256 MB) and less when the system has less free memory;
 * a bigger crop (a 200 MP photo) is saved scaled down to that, the message says so. The local-tone bases (clarity, shadows /
 * highlights, dehaze) come from the 512 px proxy of the preview's decode, the same textures the preview used. JPEG quality 95
 * (jpegli 4:4:4, Bitmap.compress as the fallback), EXIF copied from the original with the orientation reset and the new size.
 * Ultra HDR gain maps and HDR renditions are not carried over: the copy is SDR (sRGB).
 */
public final class EditExporter {
    private static final String TAG = "EditExporter";
    static final int TILE = 1024;
    static final int QUALITY = 95;

    public interface Progress {
        /** 0..1, on the worker thread. */
        void progress(float fraction);
    }

    public static final class Result {
        public Uri uri;
        public String displayName, folder;
        public int width, height;
        /** Why the copy is smaller than the crop, or where it went; null when nothing to say. */
        @Nullable public String note;
    }

    private EditExporter() {}

    /** The whole export; throws with a message for the user. */
    public static Result export(Context context, EditSource source, EditState state, Progress progress) throws IOException {
        Context c = context.getApplicationContext();
        long budget = EditSource.memoryBudget(c);
        long outMax = outMax(budget);
        double cropFraction = Math.max(1e-6, (state.cropR - state.cropL) * (state.cropB - state.cropT));
        Bitmap out;
        String shrink;
        Egl egl = new Egl();
        EditRenderer r = new EditRenderer();
        EditSource.Reader reader = null;
        try {
            egl.make();
            r.init(c.getAssets());
            // the proxy from the preview's decode: the same bases as the preview
            int previewTex;
            boolean ownPreviewTex = source.previewRaw == null;
            if (source.previewRaw != null)
                previewTex = r.rawSource(source.previewRaw, 0, 0, source.previewRaw.width, source.previewRaw.height);
            else previewTex = EditRenderer.bitmapSource(source.preview);
            r.setProxySource(previewTex, source.previewWidth(), source.previewHeight());
            if (ownPreviewTex) EditGl.deleteTexture(previewTex);
            r.updateProxy(EditMath.Uniforms.of(state, source.colour));
            progress.progress(0.02f);

            reader = source.openReader(c, cropFraction, outMax);
            final int sw = reader.width(), sh = reader.height();
            final Plan plan = plan(state, sw, sh, source.orientation, outMax);
            double[] crop = CropMath.cropPixels(state, plan.ow, plan.oh);
            double k = Math.min(1.0, Math.sqrt(outMax / Math.max(1.0, crop[0] * crop[1])));
            final int W = plan.W, H = plan.H;
            shrink = reader.shrinkNote();
            if (k < 0.999) {
                String s = Lang.t("сохранено в " + W + "×" + H + " (предел памяти)", "saved at " + W + "x" + H + " (memory limit)");
                shrink = shrink == null ? s : shrink + "; " + s;
            }
            try {
                out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                throw new IOException(Lang.t("Не хватает памяти для копии " + W + "×" + H, "Not enough memory for a " + W + "x" + H + " copy"));
            }
            // output px -> crop uv -> base uv -> oriented uv -> source uv -> source px
            final CropMath.Affine outToBase = plan.outToBase, baseToImg = plan.baseToImg, imgToSrcUv = plan.imgToSrcUv;
            final CropMath.Affine outToSrcPx = plan.outToSrcPx;
            int overlap = EditMath.overlapPx(Math.max(sw, sh)) + 2;
            EditRenderer.Target tile = new EditRenderer.Target();
            ByteBuffer pixels = ByteBuffer.allocateDirect(TILE * TILE * 4).order(ByteOrder.LITTLE_ENDIAN);
            int[] argb = new int[TILE * TILE];
            int tilesX = (W + TILE - 1) / TILE, tilesY = (H + TILE - 1) / TILE, done = 0;
            EditMath.SourceColour[] colour = new EditMath.SourceColour[1];
            for (int ty = 0; ty < tilesY; ty++) {
                for (int tx = 0; tx < tilesX; tx++) {
                    int ox = tx * TILE, oy = ty * TILE, tw = Math.min(TILE, W - ox), th = Math.min(TILE, H - oy);
                    int[] reg = region(outToSrcPx, ox, oy, tw, th, overlap, sw, sh, reader.evenAligned());
                    int src = reader.upload(r, reg[0], reg[1], reg[2], reg[3], colour);
                    EditMath.Uniforms u = EditMath.Uniforms.of(state, colour[0] != null ? colour[0] : source.colour);
                    // tile texture uv -> source uv: the proxy covers the whole source
                    CropMath.Affine texToSrcUv = CropMath.Affine.scale(reg[2], reg[3]).then(CropMath.Affine.translate(reg[0], reg[1]))
                            .then(CropMath.Affine.scale(1.0 / sw, 1.0 / sh));
                    r.renderSource(src, reg[2], reg[3], Math.max(sw, sh), texToSrcUv, u, false);
                    if (!reader.ownsTextures()) EditGl.deleteTexture(src);
                    tile.ensure(tw, th, GLES30.GL_RGBA8, false, 1);
                    EditRenderer.Finish f = new EditRenderer.Finish();
                    f.fbo = tile.fbo;
                    f.w = tw;
                    f.h = th;
                    f.originX = ox;
                    f.originY = oy;
                    f.swap = true;
                    f.mode = 0;
                    f.outToBase = outToBase;
                    f.baseToImg = baseToImg;
                    f.imgToTex = imgToSrcUv.then(texToSrcUv.inverse());
                    f.outLongSide = plan.longSide();
                    r.latticeAspect = plan.latticeAspect();
                    r.finish(f, state, u);
                    pixels.clear();
                    GLES30.glReadPixels(0, 0, tw, th, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels);
                    IntBuffer ib = pixels.asIntBuffer();
                    ib.get(argb, 0, tw * th);
                    out.setPixels(argb, 0, tw, ox, oy, tw, th);
                    done++;
                    progress.progress(0.05f + 0.8f * done / (tilesX * tilesY));
                }
            }
            tile.release();
            EditGl.check("export");
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
            r.release();
            egl.release();
        }
        try {
            Result res = save(c, source, out, progress);
            res.note = shrink;
            return res;
        } finally {
            out.recycle();
        }
    }

    /**
     * The source rectangle {x, y, w, h} an output tile needs: its corners mapped back, the overlap around, inside the source
     * (P367: the math lives in {@link EditZoom#region}, shared with the zoom's full-resolution regions).
     */
    static int[] region(CropMath.Affine outToSrcPx, int ox, int oy, int tw, int th, int overlap, int sw, int sh, boolean even) {
        return EditZoom.region(outToSrcPx, ox, oy, tw, th, overlap, sw, sh, even);
    }

    /** The export's output pixel limit for this much free memory (P367: the zoom renders its grain for the same output). */
    static long outMax(long budget) {
        return Math.max(4_000_000L, Math.min(EditSource.OUT_MAX, budget / 10));
    }

    /** The copy's size {W, H} for a crop of {@code cropW x cropH} reader px: scaled down to {@code outMax} pixels at most. */
    static int[] outputSize(double cropW, double cropH, long outMax) {
        double k = Math.min(1.0, Math.sqrt(outMax / Math.max(1.0, cropW * cropH)));
        return new int[]{Math.max(1, (int) Math.round(cropW * k)), Math.max(1, (int) Math.round(cropH * k))};
    }

    /**
     * P367: the copy of {@code state} from a reader picture {@code sw x sh} stored with EXIF {@code orientation}, at most
     * {@code outMax} pixels: its size, the output px -> crop uv -> base uv -> oriented uv -> source uv -> source px chain and the
     * grain's output size / lattice aspect. {@link #export} renders with it; the zoom's full-resolution regions and the tests use
     * the same object, so the zoomed view is the copy's mapping by construction.
     */
    static final class Plan {
        final int W, H;
        /** The oriented reader picture (what the crop math works in). */
        final double ow, oh;
        final CropMath.Affine outToBase, baseToImg, imgToSrcUv, outToSrcPx;

        Plan(EditState state, int sw, int sh, int orientation, long outMax) {
            boolean swap = CropMath.swapsSides(orientation);
            ow = swap ? sh : sw;
            oh = swap ? sw : sh;
            double[] crop = CropMath.cropPixels(state, ow, oh);
            int[] size = outputSize(crop[0], crop[1], outMax);
            W = size[0];
            H = size[1];
            outToBase = CropMath.Affine.scale(1.0 / W, 1.0 / H).then(CropMath.cropToBase(state));
            baseToImg = CropMath.baseToOriented(state, ow, oh);
            imgToSrcUv = CropMath.orientationToDisplay(orientation).inverse();
            outToSrcPx = outToBase.then(baseToImg).then(imgToSrcUv).then(CropMath.Affine.scale(sw, sh));
        }

        /** The grain's output pixels on the long side (EditRenderer.Finish.outLongSide). */
        int longSide() {
            return Math.max(W, H);
        }

        /** The grain lattice's aspect (EditRenderer.latticeAspect): the copy's rounded W / H. */
        float latticeAspect() {
            return W / (float) H;
        }
    }

    static Plan plan(EditState state, int sw, int sh, int orientation, long outMax) {
        return new Plan(state, sw, sh, orientation, outMax);
    }

    // ---------------------------------------------------------------- saving

    /** «IMG_123.jpg» -> «IMG_123_edit.jpg» (the MediaStore numbers a clash). */
    static String copyName(String original) {
        String base = original == null || original.isEmpty() ? "SCAMERA" : original;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base + "_edit.jpg";
    }

    /** The folder (MediaStore relative path) of the original, null when it is not a MediaStore file. */
    @Nullable
    static String relativePathOf(Context c, Uri uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !"content".equals(uri.getScheme()) || !MediaStore.AUTHORITY.equals(uri.getAuthority()))
            return null;
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.RELATIVE_PATH}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (RuntimeException e) {
            Log.w(TAG, "relative path of " + uri + ": " + e);
        }
        return null;
    }

    /** Only DCIM / Pictures take images inserted by the app; elsewhere the copy goes to Pictures/SCAMERA. */
    static String targetFolder(@Nullable String relative) {
        if (relative != null && (relative.startsWith(Environment.DIRECTORY_DCIM + "/") || relative.startsWith(Environment.DIRECTORY_PICTURES + "/")))
            return relative;
        return Environment.DIRECTORY_PICTURES + "/SCAMERA/";
    }

    @Nullable
    private static Long dateTaken(Context c, Uri uri) {
        if (!"content".equals(uri.getScheme())) return null;
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{MediaStore.Images.Media.DATE_TAKEN}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    static Result save(Context c, EditSource source, Bitmap out, Progress progress) throws IOException {
        File tmp = File.createTempFile("edit", ".jpg", c.getCacheDir());
        try {
            try (OutputStream o = new FileOutputStream(tmp)) {
                JpegliEncoder.compress(out, QUALITY, o);
            } catch (IOException e) {
                Log.w(TAG, "jpegli failed, Bitmap.compress: " + e);
                try (OutputStream o = new FileOutputStream(tmp)) {
                    JpegliEncoder.compressFallback(out, QUALITY, o);
                }
            }
            progress.progress(0.92f);
            copyExif(c, source.uri, tmp, out.getWidth(), out.getHeight());
            Result r = insert(c, source, tmp);
            r.width = out.getWidth();
            r.height = out.getHeight();
            progress.progress(1f);
            return r;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** EXIF tags carried from the original to the copy (date, camera, exposure, lens, GPS, description). */
    static final String[] EXIF_TAGS = {
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_FLASH, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SPECIFICATION,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_SCENE_CAPTURE_TYPE, ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
    };

    static void copyExif(Context c, Uri from, File to, int w, int h) {
        try {
            ExifInterface dst = new ExifInterface(to.getAbsolutePath());
            try (InputStream in = c.getContentResolver().openInputStream(from)) {
                if (in != null) {
                    ExifInterface src = new ExifInterface(in);
                    for (String tag : EXIF_TAGS) {
                        String v = src.getAttribute(tag);
                        if (v != null) dst.setAttribute(tag, v);
                    }
                }
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "EXIF of the original: " + e);
            }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            dst.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, String.valueOf(w));
            dst.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, String.valueOf(h));
            dst.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, String.valueOf(w));
            dst.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, String.valueOf(h));
            dst.saveAttributes();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "EXIF of the copy: " + e);
        }
    }

    /**
     * The inserted row in the form the gallery's lists use (content://media/external/images/media/ID): the insert into
     * VOLUME_EXTERNAL_PRIMARY returns content://media/external_primary/..., which never equalled the listed row, so the copy was
     * listed twice (a hand-made entry next to the real one). The same row, still valid for the ACTION_EDIT result.
     */
    static Uri galleryUri(Uri inserted) {
        long id;
        try {
            id = ContentUris.parseId(inserted);
        } catch (RuntimeException e) {
            return inserted;
        }
        return id >= 0 ? ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id) : inserted;
    }

    /** MediaStore insert with IS_PENDING (Android 10+), else a file next to the original (or in Pictures/SCAMERA) and a scan. */
    static Result insert(Context c, EditSource source, File jpeg) throws IOException {
        Result r = new Result();
        r.displayName = copyName(source.name);
        ContentResolver resolver = c.getContentResolver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String folder = targetFolder(relativePathOf(c, source.uri));
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, r.displayName);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, folder);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Long taken = dateTaken(c, source.uri);
            if (taken != null) v.put(MediaStore.Images.Media.DATE_TAKEN, taken);
            Uri uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) throw new IOException(Lang.t("Галерея телефона не приняла копию", "The phone's media store refused the copy"));
            try (OutputStream o = resolver.openOutputStream(uri, "w"); InputStream in = new FileInputStream(jpeg)) {
                if (o == null) throw new IOException("cannot write " + uri);
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            } catch (IOException | RuntimeException e) {
                resolver.delete(uri, null, null);
                throw e;
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, v, null, null);
            try (Cursor cur = resolver.query(uri, new String[]{MediaStore.Images.Media.DISPLAY_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst() && cur.getString(0) != null) r.displayName = cur.getString(0);
            }
            r.uri = galleryUri(uri);
            r.folder = folder;
            return r;
        }
        // Android 8 / 9: the file next to the original when it is a file we can write, else Pictures/SCAMERA
        File dir = null;
        String path = source.absolutePath;
        if (path != null) dir = new File(path).getParentFile();
        if (dir == null || !dir.canWrite())
            dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SCAMERA");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File file = new File(dir, r.displayName);
        for (int i = 2; file.exists(); i++) file = new File(dir, r.displayName.replace("_edit.jpg", "_edit" + i + ".jpg"));
        try (InputStream in = new FileInputStream(jpeg); OutputStream o = new FileOutputStream(file)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        }
        r.displayName = file.getName();
        r.folder = dir.getAbsolutePath();
        final Uri[] scanned = new Uri[1];
        final Object lock = new Object();
        android.media.MediaScannerConnection.scanFile(c, new String[]{file.getAbsolutePath()}, new String[]{"image/jpeg"}, (p, uri) -> {
            synchronized (lock) {
                scanned[0] = uri;
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            try {
                if (scanned[0] == null) lock.wait(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        r.uri = scanned[0] != null ? scanned[0] : Uri.fromFile(file);
        return r;
    }

    // ---------------------------------------------------------------- EGL

    /** A pbuffer-backed OpenGL ES 3 context for the export thread. */
    static final class Egl {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;

        void make() throws IOException {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw new IOException("EGL init failed");
            int[] attribs = {EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] n = new int[1];
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) || n[0] == 0) throw new IOException("no EGL config");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT) throw new IOException("no GL ES 3 context");
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw new IOException("EGL make current failed");
        }

        void release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglReleaseThread();
            }
            display = EGL14.EGL_NO_DISPLAY;
            context = EGL14.EGL_NO_CONTEXT;
            surface = EGL14.EGL_NO_SURFACE;
        }
    }
}
