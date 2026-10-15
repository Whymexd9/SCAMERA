package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.exifinterface.media.ExifInterface;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.processing.color.IccEmbed;
import com.particlesdevs.photoncamera.processing.color.IccProfiles;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P147: the JPEG's EXIF written into the stream (APP1 right after SOI) instead of an ExifInterface rewrite of the finished
 * file - the same tags, one EXIF segment, the ICC profile kept.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class JpegExifStreamTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String[] TAGS = {ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH, ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_SOFTWARE};

    private static ParseExif.ExifData exif() {
        ParseExif.ExifData d = new ParseExif.ExifData();
        d.SENSITIVITY_TYPE = String.valueOf(ExifInterface.SENSITIVITY_TYPE_ISO_SPEED);
        d.PHOTOGRAPHIC_SENSITIVITY = "400";
        d.F_NUMBER = "1.8";
        d.EXPOSURE_TIME = "0.01";
        d.DATETIME = "2026:10:16 10:10:10";
        d.COMPRESSION = ParseExif.COMPRESSION_JPEG;
        d.COLOR_SPACE = "sRGB";
        return d;
    }

    private static Bitmap picture(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) b.setPixel(x, y, Color.rgb(x * 255 / w, y * 255 / h, 90));
        return b;
    }

    @Test
    public void exifInTheStreamHasTheTagsOfTheRewrite() throws Exception {
        Bitmap img = picture(64, 40);
        // the old way: the encoder's file, then ExifInterface rewrites it with the tags
        Path old = tmp.getRoot().toPath().resolve("old.jpg");
        try (OutputStream o = Files.newOutputStream(old)) {
            assertTrue(img.compress(Bitmap.CompressFormat.JPEG, 95, o));
        }
        ParseExif.ExifData d = exif();
        ExifInterface rewrite = ParseExif.setAllAttributes(old.toFile(), d);
        assertNotNull(rewrite);
        rewrite.saveAttributes();
        // the new way: ImageSaver with the APP1 in the stream
        Path now = tmp.getRoot().toPath().resolve("now.jpg");
        assertTrue(ImageSaver.Util.saveBitmapAsJPG(now, img.copy(Bitmap.Config.ARGB_8888, false), 95, exif(), true));
        ExifInterface a = new ExifInterface(old.toFile()), b = new ExifInterface(now.toFile());
        for (String tag : TAGS) assertEquals(tag, a.getAttribute(tag), b.getAttribute(tag));
        byte[] bytes = Files.readAllBytes(now);
        assertEquals("SOI", 0xD8, bytes[1] & 0xFF);
        assertEquals("EXIF APP1 right after SOI", 0xE1, bytes[3] & 0xFF);
        assertEquals('E', bytes[6]);
        assertEquals("one EXIF segment", 1, count(bytes, new byte[]{'E', 'x', 'i', 'f', 0, 0}));
    }

    @Test
    public void exifAndProfileTogether() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("p3.jpg");
        byte[] icc = IccProfiles.displayP3();
        assertTrue(ImageSaver.Util.saveBitmapAsJPG(file, picture(48, 32), 95, exif(), true, icc));
        byte[] bytes = Files.readAllBytes(file);
        assertArrayEquals("the profile", icc, IccEmbed.jpegProfile(bytes));
        assertEquals("400", new ExifInterface(file.toFile()).getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY));
        assertEquals("EXIF first", 0xE1, bytes[3] & 0xFF);
        assertEquals(1, count(bytes, new byte[]{'E', 'x', 'i', 'f', 0, 0}));
    }

    private static int count(byte[] data, byte[] pattern) {
        int n = 0;
        for (int i = 0; i + pattern.length <= data.length; i++) {
            boolean hit = true;
            for (int k = 0; k < pattern.length && hit; k++) hit = data[i + k] == pattern[k];
            if (hit) n++;
        }
        return n;
    }
}
