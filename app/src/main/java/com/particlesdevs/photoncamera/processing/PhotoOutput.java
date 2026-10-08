package com.particlesdevs.photoncamera.processing;

import android.graphics.Bitmap;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.exifinterface.media.ExifInterface;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.processing.avif.AvifEncoder;
import com.particlesdevs.photoncamera.processing.opengl.GLLimits;
import com.particlesdevs.photoncamera.processing.ultrahdr.GainMapComputer;
import com.particlesdevs.photoncamera.processing.ultrahdr.UltraHdrEncoder;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Writes the processed photo in the chosen format ({@link PhotoFormat}): JPEG (Ultra HDR when a gain map is given), HEIC
 * through androidx.heifwriter, WebP through Bitmap.compress, AVIF through the bundled libavif + libaom (AvifEncoder), plus
 * the optional extra JPEG («Также сохранять JPEG»). A HEIC / WebP / AVIF photo that cannot be written (no HEVC encoder, a
 * WebP side above 16383 px, an AVIF above 64 MP or beyond the free memory, an encoder error) is saved as JPEG instead, so
 * a shot is never lost to the codec. Pixels are stored rotated in every format, without an Orientation
 * tag (the JPEG path's convention), so all files of a shot show the same way.
 */
public final class PhotoOutput {
    private static final String TAG = "PhotoOutput";
    /** Effort of the lossless WebP on Android 11+ (0 fastest .. 100 smallest): a 50 MP photo stays within seconds. */
    static final int WEBP_LOSSLESS_EFFORT = 75;

    private PhotoOutput() {}

    /** The files of a shot, in write order: the photo format first, the JPEG (if any) after it. */
    public static List<PhotoFormat> plan(PhotoFormat format, boolean alsoJpeg, int width, int height) {
        List<PhotoFormat> out = new ArrayList<>(2);
        if (format != PhotoFormat.JPEG && format.fits(width, height)) out.add(format);
        if (out.isEmpty() || alsoJpeg) out.add(PhotoFormat.JPEG);
        return out;
    }

    /** Bitmap.compress format and quality of a WebP photo on {@code sdk}: {format name, quality}. */
    static Object[] webpEncoding(boolean lossless, int quality, int sdk) {
        int q = Math.max(1, Math.min(100, quality));
        if (sdk >= Build.VERSION_CODES.R) {
            return lossless ? new Object[]{"WEBP_LOSSLESS", WEBP_LOSSLESS_EFFORT} : new Object[]{"WEBP_LOSSY", q};
        }
        // Before Android 11 the one WEBP format is lossless exactly at quality 100: a lossy WebP stays below it.
        return new Object[]{"WEBP", lossless ? 100 : Math.min(q, 99)};
    }

    /** The files written, in write order (the photo format first). Empty when nothing could be saved. */
    public static final class Result {
        public final List<Path> files = new ArrayList<>(2);

        /** In notification order: the photo format last, so the camera thumbnail ends on it. */
        public List<Path> notifyOrder() {
            List<Path> order = new ArrayList<>(files);
            Collections.reverse(order);
            return order;
        }
    }

    /**
     * Saves {@code img} next to {@code base} (a path without extension) in the format of the settings and always recycles
     * the bitmap. {@code gain} (or null) makes the JPEG an Ultra HDR JPEG.
     */
    public static Result save(Path base, Bitmap img, ParseExif.ExifData exif, GainMapComputer.Result gain) {
        final PhotoFormat format = PreferenceKeys.getPhotoFormat();
        final boolean alsoJpeg = PreferenceKeys.isAlsoSaveJpeg();
        final int width = img.getWidth(), height = img.getHeight();
        final List<PhotoFormat> plan = plan(format, alsoJpeg, width, height);
        if (format != PhotoFormat.JPEG && !plan.contains(format))
            Log.w(TAG, format + " cannot hold " + width + "x" + height + " (" + format.limit() + "): saved as JPEG");
        if (PreferenceKeys.getChosenPhotoFormat() != format)
            Log.w(TAG, PreferenceKeys.getChosenPhotoFormat() + " is not available here (HEIC needs Android 9, AVIF Android 12 and its encoder): saved as JPEG");
        final Result result = new Result();
        try {
            for (int i = 0; i < plan.size(); i++) {
                final PhotoFormat f = plan.get(i);
                final boolean last = i == plan.size() - 1;
                final Path file = f.fileFor(base);
                final long start = System.nanoTime();
                boolean ok;
                switch (f) {
                    case HEIC:
                        ok = Build.VERSION.SDK_INT >= PhotoFormat.HEIC_MIN_SDK && saveHeic(file, img, PreferenceKeys.getHeicQuality(), exif);
                        break;
                    case WEBP:
                        ok = saveWebp(file, img, PreferenceKeys.getWebpQuality(), PreferenceKeys.isWebpLossless(), exif);
                        break;
                    case AVIF:
                        ok = saveAvif(file, img, PreferenceKeys.getAvifOptions(), exif);
                        break;
                    default:
                        ok = saveJpeg(file, img, exif, gain, last);
                        break;
                }
                Log.d(TAG, f + " " + (ok ? "saved" : "failed") + " in " + (System.nanoTime() - start) / 1000000 + " ms: " + file.getFileName());
                if (ok) {
                    result.files.add(file);
                } else if (f != PhotoFormat.JPEG && !plan.contains(PhotoFormat.JPEG)) {
                    Log.w(TAG, f + " encode failed: the photo is saved as JPEG");
                    plan.add(PhotoFormat.JPEG);
                }
            }
        } finally {
            if (!img.isRecycled()) img.recycle();
        }
        return result;
    }

    private static boolean saveJpeg(Path file, Bitmap img, ParseExif.ExifData exif, GainMapComputer.Result gain, boolean recycle) {
        if (exif == null) exif = new ParseExif.ExifData();
        exif.COMPRESSION = ParseExif.COMPRESSION_JPEG; // a HEIC / WebP written before cleared it
        final int quality = PreferenceKeys.getJpegQuality();
        if (gain != null) {
            try {
                UltraHdrEncoder.encodeToFile(file, img, gain, exif, quality);
                if (recycle) img.recycle();
                return true;
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "Ultra HDR encode failed, falling back to SDR JPEG: " + android.util.Log.getStackTraceString(e));
            }
        }
        return ImageSaver.Util.saveBitmapAsJPG(file, img, quality, exif, recycle);
    }

    /** WebP through Bitmap.compress, then the EXIF chunk through ExifInterface (it writes WebP). Keeps the bitmap. */
    static boolean saveWebp(Path file, Bitmap img, int quality, boolean lossless, ParseExif.ExifData exif) {
        if (!PhotoFormat.WEBP.fits(img.getWidth(), img.getHeight())) return false;
        final Object[] encoding = webpEncoding(lossless, quality, Build.VERSION.SDK_INT);
        boolean encoded = false;
        try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(file), ImageSaver.Util.SAVE_BUFFER_BYTES)) {
            @SuppressWarnings("deprecation")
            Bitmap.CompressFormat cf = Bitmap.CompressFormat.valueOf((String) encoding[0]);
            encoded = img.compress(cf, (Integer) encoding[1], out);
            out.flush();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.e(TAG, "WebP encode failed: " + android.util.Log.getStackTraceString(e));
        }
        if (!encoded) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            return false;
        }
        writeExif(file, exif, img.getWidth(), img.getHeight());
        return true;
    }

    /** EXIF of a WebP file through ExifInterface; a failure costs the EXIF, never the photo. */
    private static void writeExif(Path file, ParseExif.ExifData exif, int width, int height) {
        if (exif == null) return;
        exif.COMPRESSION = null; // Compression 6 (JPEG) describes JPEG data only
        try {
            ExifInterface inter = ParseExif.setAllAttributes(file.toFile(), exif);
            if (inter == null) return;
            // ExifInterface does not read the size of a WebP / HEIC bitstream: the gallery's EXIF info takes it from here.
            inter.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, String.valueOf(width));
            inter.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, String.valueOf(height));
            inter.saveAttributes();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "EXIF write failed: " + android.util.Log.getStackTraceString(e));
        }
    }

    /**
     * HEIC through androidx.heifwriter (bitmap input, grid tiles for large images, the platform HEVC / HEIC encoder) with
     * the EXIF block. Keeps the bitmap. False (partial file deleted) without an encoder or on any encoder error.
     */
    @RequiresApi(PhotoFormat.HEIC_MIN_SDK)
    static boolean saveHeic(Path file, Bitmap img, int quality, ParseExif.ExifData exif) {
        final int width = img.getWidth(), height = img.getHeight();
        if (!heicEncoderAvailable()) {
            Log.w(TAG, "HEIC: no HEIC / HEVC encoder on this device");
            return false;
        }
        // HeifWriter uploads the whole bitmap as one GL texture before it cuts the tiles.
        final int maxTexture = GLLimits.get().maxTextureSize;
        if (Math.max(width, height) > maxTexture) {
            Log.w(TAG, "HEIC: " + width + "x" + height + " is above the GL texture limit " + maxTexture);
            return false;
        }
        byte[] exifBlock = null;
        if (exif != null) {
            exif.COMPRESSION = null;
            exifBlock = ExifBlock.exifDataBlock(ExifBlock.app1Segment(exif, width, height));
        }
        androidx.heifwriter.HeifWriter writer = null;
        boolean ok = false;
        try {
            writer = new androidx.heifwriter.HeifWriter.Builder(file.toString(), width, height,
                    androidx.heifwriter.HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(Math.max(1, Math.min(100, quality)))
                    .setGridEnabled(true)
                    .setMaxImages(1)
                    .setPrimaryIndex(0)
                    .setRotation(0)
                    .build();
            writer.start();
            writer.addBitmap(img);
            if (exifBlock != null) writer.addExifData(0, exifBlock, 0, exifBlock.length);
            writer.stop(heicTimeoutMs(width, height));
            ok = Files.size(file) > 0;
        } catch (Exception | Error e) {
            Log.e(TAG, "HEIC encode failed: " + android.util.Log.getStackTraceString(e));
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) {}
            }
            if (!ok) {
                try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            }
        }
        return ok;
    }

    private static volatile Boolean heicEncoder;

    /**
     * Whether the platform has an encoder HeifWriter can use (HEIC image or HEVC video, as HeifEncoder looks them up).
     * Checked before HeifWriter is built: its constructor opens the output file (MediaMuxer) and starts a handler thread
     * before it looks for the encoder, and leaks both when there is none.
     */
    @RequiresApi(PhotoFormat.HEIC_MIN_SDK)
    static boolean heicEncoderAvailable() {
        Boolean known = heicEncoder;
        if (known != null) return known;
        boolean found = false;
        try {
            for (android.media.MediaCodecInfo info : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes())
                    if (type.equalsIgnoreCase(android.media.MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC)
                            || type.equalsIgnoreCase(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC)) found = true;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "codec list unavailable: " + e);
        }
        heicEncoder = found;
        return found;
    }

    /** Time limit of one HEIC encode: 30 s plus 1 s per megapixel. */
    static long heicTimeoutMs(int width, int height) {
        return 30_000L + (long) width * height / 1_000_000L * 1000L;
    }

    /** Free memory an AVIF encode leaves untouched (the camera keeps running). */
    static final long AVIF_MEMORY_RESERVE = 256L << 20;

    /**
     * AVIF through the bundled libavif + libaom (AvifEncoder) with the EXIF block. Keeps the bitmap. False (no file left)
     * without the encoder library, when the encode would not fit in the free memory, or on any encoder error.
     */
    static boolean saveAvif(Path file, Bitmap img, AvifEncoder.Options options, ParseExif.ExifData exif) {
        final int width = img.getWidth(), height = img.getHeight();
        if (!AvifEncoder.available()) {
            Log.w(TAG, "AVIF: encoder library unavailable");
            return false;
        }
        final long need = AvifEncoder.workingBytes(width, height, options.fullChroma()), free = availableMemory();
        if (!avifMemoryAllows(need, free)) {
            Log.w(TAG, "AVIF: " + width + "x" + height + " needs ~" + (need >> 20) + " MB, " + (free >> 20) + " MB available");
            return false;
        }
        byte[] exifBlock = null;
        if (exif != null) {
            exif.COMPRESSION = null; // Compression 6 (JPEG) describes JPEG data only
            exifBlock = ExifBlock.exifDataBlock(ExifBlock.app1Segment(exif, width, height));
        }
        try {
            AvifEncoder.Result r = AvifEncoder.encode(img, file, options, exifBlock);
            Log.d(TAG, "AVIF " + options.describe() + " (" + img.getConfig() + " " + width + "x" + height + "): " + r.depth + "-bit "
                    + (r.yuv444 ? "4:4:4" : "4:2:0") + ", RGB to YCbCr " + r.convertMs + " ms, AV1 " + r.encodeMs + " ms, "
                    + r.bytes / 1024 + " KB" + (exifBlock == null || r.exif ? "" : ", EXIF not stored"));
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.e(TAG, "AVIF encode failed: " + android.util.Log.getStackTraceString(e));
            return false;
        }
    }

    /** Whether an AVIF encode needing {@code need} bytes fits in {@code free} (-1: unknown, the encode is tried). */
    static boolean avifMemoryAllows(long need, long free) {
        return free < 0 || need + AVIF_MEMORY_RESERVE <= free;
    }

    /** ActivityManager.MemoryInfo.availMem, -1 when unknown. */
    private static long availableMemory() {
        try {
            android.content.Context context = com.particlesdevs.photoncamera.app.PhotonCamera.getAppContext();
            android.app.ActivityManager am = context == null ? null
                    : (android.app.ActivityManager) context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am == null) return -1;
            android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            return info.availMem > 0 ? info.availMem : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
