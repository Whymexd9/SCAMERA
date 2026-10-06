package com.particlesdevs.photoncamera.processing.ultrahdr;

import android.graphics.Bitmap;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Orchestrates the full Ultra HDR encode:
 * <ol>
 *   <li>take the encoded gain map from PostPipeline's scene-anchored pass
 *       (pre-LTM scene luma, midtone-anchored to the stored base) and
 *       normalize it via {@link GainMapComputer#compute}</li>
 *   <li>compress the gain map to a JPEG, then compress the SDR base straight
 *       into the output with EXIF / XMP / MPF placed while it is written
 *       ({@link UltraHdrContainer#write})</li>
 * </ol>
 *
 * The primary JPEG of a 50 MP base is ~25 MB; it is never held in memory
 * ({@link #encodeToFile}); {@link #encode} holds it in one growable buffer plus
 * the returned copy. EXIF is generated on a
 * tiny JPEG and spliced in, instead of a temp-file round trip of the whole
 * image. The caller recycles the bitmaps.
 */
public final class UltraHdrEncoder {

    private static final String TAG = "UltraHdrEncoder";
    private static final int DEFAULT_QUALITY = 95;
    private static final int FILE_BUFFER_BYTES = 256 * 1024;

    private UltraHdrEncoder() {}

    /**
     * @param sdr  SDR display bitmap (ARGB_8888, sRGB)
     * @param gm   gain-map result produced by {@link GainMapComputer#compute}
     * @param exif optional EXIF to embed in the primary JPEG (may be null)
     * @return Ultra HDR JPEG bytes
     */
    public static byte[] encode(Bitmap sdr, GainMapComputer.Result gm, ParseExif.ExifData exif) {
        final byte[] gainMapJpeg = compressGainMap(gm, DEFAULT_QUALITY);
        final byte[] exifApp1 = exif != null ? exifSegment(exif, sdr.getWidth(), sdr.getHeight()) : null;
        // q95 camera JPEGs are typically 0.3-0.8 bytes/pixel: at most one growth step in the common case.
        final long estimate = Math.min(Integer.MAX_VALUE - 64L, (long) sdr.getWidth() * sdr.getHeight() / 2 + gainMapJpeg.length + 65536L);
        final PatchableOutputStream out = new PatchableOutputStream((int) estimate);
        try {
            UltraHdrContainer.write(out, out::patch, o -> compressPrimary(sdr, o, DEFAULT_QUALITY), exifApp1,
                    gainMapJpeg, gm.gainMapMin, gm.gainMapMax, gm.hdrCapacityMax);
        } catch (IOException e) {
            throw new RuntimeException("Ultra HDR encode failed", e);
        }
        return out.toByteArray();
    }

    /**
     * Writes the Ultra HDR JPEG straight into {@code file}: the SDR base is
     * encoded into the file (no in-memory JPEG), the gain map is appended and
     * the MPF directory patched in place. Peak extra memory is the gain-map JPEG
     * plus the write buffer. On failure the partial file is deleted and the
     * bitmap is left untouched, so the caller can still save a plain JPEG.
     */
    public static void encodeToFile(Path file, Bitmap sdr, GainMapComputer.Result gm, ParseExif.ExifData exif)
            throws IOException {
        encodeToFile(file, sdr, gm, exif, DEFAULT_QUALITY);
    }

    /** As above, with the JPEG quality of the base image and the gain map (P24 «Качество JPEG»). */
    public static void encodeToFile(Path file, Bitmap sdr, GainMapComputer.Result gm, ParseExif.ExifData exif, int quality)
            throws IOException {
        final byte[] gainMapJpeg = compressGainMap(gm, quality);
        final byte[] exifApp1 = exif != null ? exifSegment(exif, sdr.getWidth(), sdr.getHeight()) : null;
        boolean done = false;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            final OutputStream out = new BufferedOutputStream(Channels.newOutputStream(channel), FILE_BUFFER_BYTES);
            UltraHdrContainer.write(out, (offset, bytes) -> {
                final ByteBuffer patch = ByteBuffer.wrap(bytes);
                long at = offset;
                while (patch.hasRemaining()) at += channel.write(patch, at);
            }, o -> compressPrimary(sdr, o, quality), exifApp1, gainMapJpeg, gm.gainMapMin, gm.gainMapMax, gm.hdrCapacityMax);
            out.flush();
            done = true;
        } finally {
            if (!done) {
                try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            }
        }
    }

    // P24: jpegli 4:4:4 (JpegliEncoder). A failed base image fails the container; the caller then saves a plain JPEG.
    private static void compressPrimary(Bitmap bmp, OutputStream out, int quality) throws IOException {
        if (com.particlesdevs.photoncamera.processing.JpegliEncoder.available())
            com.particlesdevs.photoncamera.processing.JpegliEncoder.compress(bmp, quality, out);
        else
            com.particlesdevs.photoncamera.processing.JpegliEncoder.compressFallback(bmp, quality, out);
    }

    private static byte[] compressGainMap(GainMapComputer.Result gm, int quality) {
        if (com.particlesdevs.photoncamera.processing.JpegliEncoder.available()) {
            final ByteArrayOutputStream gainOut = new ByteArrayOutputStream();
            try {
                com.particlesdevs.photoncamera.processing.JpegliEncoder.compress(gm.gainMap, quality, gainOut);
                return gainOut.toByteArray();
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "jpegli gain map failed, Android encoder: " + e);
            }
        }
        final ByteArrayOutputStream gainOut = new ByteArrayOutputStream();
        try {
            com.particlesdevs.photoncamera.processing.JpegliEncoder.compressFallback(gm.gainMap, quality, gainOut);
        } catch (IOException e) {
            throw new RuntimeException("Failed to compress gain map", e);
        }
        return gainOut.toByteArray();
    }

    /**
     * Builds the EXIF APP1 segment of the primary on a tiny JPEG via
     * {@link ParseExif#setAllAttributes} (the same tags the plain JPEG path
     * writes). ExifInterface would take ImageWidth/ImageLength from the frame
     * header of the file it edits, so the real size is set explicitly. Returns
     * null (EXIF-less base, as before) when anything fails.
     */
    private static byte[] exifSegment(ParseExif.ExifData exif, int width, int height) {
        File tmp = null;
        Bitmap tiny = null;
        try {
            tmp = File.createTempFile("uhdr_exif_", ".jpg");
            tiny = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            try (OutputStream os = Files.newOutputStream(tmp.toPath())) {
                if (!tiny.compress(Bitmap.CompressFormat.JPEG, 50, os)) return null;
            }
            ExifInterface inter = ParseExif.setAllAttributes(tmp, exif);
            if (inter == null) return null;
            inter.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, String.valueOf(width));
            inter.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, String.valueOf(height));
            inter.saveAttributes();
            return UltraHdrContainer.findExifSegment(Files.readAllBytes(tmp.toPath()));
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "EXIF segment failed, Ultra HDR base without EXIF: " + Log.getStackTraceString(e));
            return null;
        } finally {
            if (tiny != null) tiny.recycle();
            if (tmp != null) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }

    /** In-memory output whose already written bytes can be patched (the MPF directory). */
    private static final class PatchableOutputStream extends ByteArrayOutputStream {
        PatchableOutputStream(int size) {
            super(size);
        }

        void patch(long offset, byte[] bytes) throws IOException {
            if (offset < 0 || offset + bytes.length > count) throw new IOException("patch outside the written data");
            System.arraycopy(bytes, 0, buf, (int) offset, bytes.length);
        }
    }
}
