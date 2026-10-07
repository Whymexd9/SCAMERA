package com.particlesdevs.photoncamera.processing;

import android.graphics.Bitmap;

import androidx.exifinterface.media.ExifInterface;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.processing.ultrahdr.UltraHdrContainer;
import com.particlesdevs.photoncamera.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * The EXIF of a photo as bytes, for containers that take it as a block instead of letting ExifInterface edit the file
 * (the Ultra HDR JPEG, HEIC). The tags are written by {@link ParseExif#setAllAttributes} - the same tags the plain JPEG
 * gets - into a tiny JPEG whose APP1 segment is then copied out.
 */
public final class ExifBlock {
    private static final String TAG = "ExifBlock";
    /** "Exif\0\0": the identifier of a JPEG APP1 EXIF segment, in front of the TIFF header. */
    private static final int APP1_HEADER = 4; // FF E1 + 2-byte length

    private ExifBlock() {}

    /**
     * Complete EXIF APP1 segment (FF E1, length, "Exif\0\0", TIFF data) for an image of {@code width} x {@code height}.
     * ExifInterface would take ImageWidth / ImageLength from the frame header of the tiny JPEG, so the real size is set
     * explicitly. No Orientation tag: every format stores the pixels already rotated (PostPipeline RotateWatermark).
     * Returns null (photo without EXIF, as before) when anything fails.
     */
    public static byte[] app1Segment(ParseExif.ExifData exif, int width, int height) {
        if (exif == null) return null;
        File tmp = null;
        Bitmap tiny = null;
        try {
            tmp = File.createTempFile("photo_exif_", ".jpg");
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
            Log.e(TAG, "EXIF block failed, photo without EXIF: " + Log.getStackTraceString(e));
            return null;
        } finally {
            if (tiny != null) tiny.recycle();
            if (tmp != null) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }

    /**
     * The EXIF data block of an APP1 segment: "Exif\0\0" followed by the TIFF header - the layout
     * androidx.heifwriter's addExifData expects (JEITA CP-3451C 4.5.2). Null when {@code app1} is not an EXIF APP1.
     */
    public static byte[] exifDataBlock(byte[] app1) {
        if (app1 == null || app1.length < APP1_HEADER + 6 + 8) return null;
        if ((app1[0] & 0xFF) != 0xFF || (app1[1] & 0xFF) != 0xE1) return null;
        if (app1[4] != 'E' || app1[5] != 'x' || app1[6] != 'i' || app1[7] != 'f' || app1[8] != 0 || app1[9] != 0) return null;
        return Arrays.copyOfRange(app1, APP1_HEADER, app1.length);
    }
}
