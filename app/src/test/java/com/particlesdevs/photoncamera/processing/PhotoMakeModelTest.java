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
import com.particlesdevs.photoncamera.gallery.helper.ImageHeaderSize;
import com.particlesdevs.photoncamera.gallery.ui.PhotoInfo;
import com.particlesdevs.photoncamera.util.DeviceName;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowSystemProperties;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * P196: our photo writer stores the phone (EXIF Make / Model: the market name when the system has one) in every format with
 * EXIF - the plain JPEG, the WebP, and the EXIF block of the Ultra HDR JPEG / HEIC / AVIF - and the gallery reads it back
 * with the size the file really stores.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhotoMakeModelTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() {
        ShadowBuild.setBrand("vivo");
        ShadowBuild.setManufacturer("vivo");
        ShadowBuild.setModel("V2446A");
        DeviceName.forget();
    }

    @After
    public void tearDown() {
        ShadowSystemProperties.reset();
        DeviceName.forget();
    }

    private static ParseExif.ExifData exif() {
        ParseExif.ExifData d = new ParseExif.ExifData();
        d.SENSITIVITY_TYPE = String.valueOf(ExifInterface.SENSITIVITY_TYPE_ISO_SPEED);
        d.PHOTOGRAPHIC_SENSITIVITY = "50";
        d.F_NUMBER = "2.27";
        d.FOCAL_LENGTH = "2250/100";
        d.EXPOSURE_TIME = String.valueOf(1 / 852.0);
        d.DATETIME = "2026:10:17 11:56:00";
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
    public void modelIsTheMarketNameWhenTheSystemHasOne() {
        ShadowSystemProperties.override("ro.vivo.market.name", "vivo X200 Ultra");
        DeviceName.forget();
        ParseExif.ExifData d = new ParseExif.ExifData();
        assertEquals("vivo", d.MAKE);
        assertEquals("vivo X200 Ultra", d.MODEL);
        assertEquals("vivo X200 Ultra", PhotoInfo.device(d.MAKE, d.MODEL));
    }

    @Test
    public void modelIsBuildModelWithoutAMarketName() {
        ParseExif.ExifData d = new ParseExif.ExifData();
        assertEquals("vivo", d.MAKE);
        assertEquals("V2446A", d.MODEL);
        assertEquals("vivo V2446A", PhotoInfo.device(d.MAKE, d.MODEL));
    }

    @Test
    public void everyWriterStoresMakeModelAndTheGallerySeesTheRealSize() throws Exception {
        ShadowSystemProperties.override("ro.vivo.market.name", "vivo X200 Ultra");
        DeviceName.forget();
        // plain JPEG
        Path jpg = tmp.getRoot().toPath().resolve("a.jpg");
        assertTrue(ImageSaver.Util.saveBitmapAsJPG(jpg, picture(96, 72), 95, exif(), true));
        assertTags(new ExifInterface(jpg.toFile()));
        assertSize(jpg, 96, 72);
        // WebP (lossy and lossless): ExifInterface writes the EXIF chunk
        for (boolean lossless : new boolean[]{false, true}) {
            Path webp = tmp.getRoot().toPath().resolve("a" + lossless + ".webp");
            assertTrue(PhotoOutput.saveWebp(webp, picture(80, 60), 90, lossless, exif()));
            assertTags(new ExifInterface(webp.toFile()));
            assertSize(webp, 80, 60);
        }
        // the EXIF block of the Ultra HDR JPEG / HEIC (8 and 10-bit) / AVIF: put in front of a JPEG to read it
        byte[] app1 = ExifBlock.app1Segment(exif(), 8192, 6144);
        assertNotNull(app1);
        assertNotNull(ExifBlock.exifDataBlock(app1));
        Path tiny = tmp.getRoot().toPath().resolve("tiny.jpg");
        try (OutputStream o = Files.newOutputStream(tiny)) {
            assertTrue(picture(16, 16).compress(Bitmap.CompressFormat.JPEG, 80, o));
        }
        byte[] t = Files.readAllBytes(tiny);
        Path block = tmp.getRoot().toPath().resolve("block.jpg");
        try (OutputStream o = Files.newOutputStream(block)) {
            o.write(t, 0, 2);
            o.write(app1);
            o.write(Arrays.copyOfRange(t, 2, t.length));
        }
        ExifInterface b = new ExifInterface(block.toFile());
        assertTags(b);
        assertSize(block, 16, 16); // the gallery shows what the file stores, not the block's 8192 x 6144
    }

    private static void assertTags(ExifInterface e) {
        assertEquals("vivo", e.getAttribute(ExifInterface.TAG_MAKE));
        assertEquals("vivo X200 Ultra", e.getAttribute(ExifInterface.TAG_MODEL));
        assertEquals("50", e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY));
        assertNotNull(e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME));
        assertNotNull(e.getAttribute(ExifInterface.TAG_F_NUMBER));
        assertNotNull(e.getAttribute(ExifInterface.TAG_FOCAL_LENGTH));
    }

    private static void assertSize(Path file, int w, int h) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            assertArrayEquals(file.getFileName().toString(), new int[]{w, h}, ImageHeaderSize.read(in));
        }
    }
}
