package com.particlesdevs.photoncamera.processing;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;

import androidx.exifinterface.media.ExifInterface;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.opengl.GLLimits;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** «Формат фото»: format rules, file naming, the save plan and the real WebP / JPEG writes with their EXIF. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhotoFormatTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private SettingsManager manager;
    private MockedStatic<PhotonCamera> camera;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
        camera = mockStatic(PhotonCamera.class);
        camera.when(PhotonCamera::getAppContext).thenReturn(context);
        camera.when(PhotonCamera::getResourcesStatic).thenReturn(context.getResources());
        // PreferenceKeys.Key resolves its key strings once per sandbox: they must be the real ones for the next test class.
        camera.when(() -> PhotonCamera.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(PhotonCamera::getSettingsManagerStatic).thenReturn(manager);
        PhotonCamera app = mock(PhotonCamera.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> PhotonCamera.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        GLLimits.setForTesting(GLLimits.of(16384, 16384, 16384, 16384));
    }

    @After
    public void tearDown() {
        GLLimits.setForTesting(null);
        camera.close();
    }

    @Test
    public void storedValuesAndApiLevel() {
        assertEquals(PhotoFormat.JPEG, PhotoFormat.parse(null));
        assertEquals(PhotoFormat.JPEG, PhotoFormat.parse("jxl"));
        assertEquals(PhotoFormat.HEIC, PhotoFormat.parse(" HEIC "));
        assertEquals(PhotoFormat.WEBP, PhotoFormat.parse("webp"));
        assertEquals(PhotoFormat.JPEG, PhotoFormat.effective(PhotoFormat.HEIC, 27));
        assertEquals(PhotoFormat.HEIC, PhotoFormat.effective(PhotoFormat.HEIC, 28));
        assertEquals(PhotoFormat.WEBP, PhotoFormat.effective(PhotoFormat.WEBP, 26));
        // the preference values of res/xml/preferences.xml
        for (PhotoFormat f : PhotoFormat.values()) assertEquals(f, PhotoFormat.parse(f.value));
    }

    @Test
    public void fileNamesAndMimeTypes() {
        Path base = Paths.get("/storage/DCIM/Camera/IMG_20261008_101010_1");
        assertEquals("IMG_20261008_101010_1.jpg", PhotoFormat.JPEG.fileFor(base).getFileName().toString());
        assertEquals("IMG_20261008_101010_1.heic", PhotoFormat.HEIC.fileFor(base).getFileName().toString());
        assertEquals("IMG_20261008_101010_1.webp", PhotoFormat.WEBP.fileFor(base).getFileName().toString());
        assertEquals(base.getParent(), PhotoFormat.HEIC.fileFor(base).getParent());
        assertEquals("image/jpeg", PhotoFormat.mimeForName("IMG.JPG"));
        assertEquals("image/jpeg", PhotoFormat.mimeForName("a.jpeg"));
        assertEquals("image/heic", PhotoFormat.mimeForName("/x/IMG.heic"));
        assertEquals("image/heif", PhotoFormat.mimeForName("IMG.HEIF"));
        assertEquals("image/webp", PhotoFormat.mimeForName("IMG.webp"));
        assertEquals("image/x-adobe-dng", PhotoFormat.mimeForName("IMG.dng"));
        assertNull(PhotoFormat.mimeForName("notes.txt"));
        assertNull(PhotoFormat.mimeForName("dir.webp/file"));
        for (String name : Arrays.asList("a.jpg", "a.JPEG", "a.dng", "a.heic", "a.HEIF", "a.webp"))
            assertTrue(name, PhotoFormat.isGalleryFile(name));
        for (String name : Arrays.asList("a.png", "a.txt", "heic", "a.jxl", null))
            assertFalse(String.valueOf(name), PhotoFormat.isGalleryFile(name));
        assertFalse(PhotoFormat.decodable("a.heic", 27));
        assertTrue(PhotoFormat.decodable("a.heic", 28));
        assertTrue(PhotoFormat.decodable("a.webp", 26));
        assertTrue(PhotoFormat.isModernPhoto("a.webp"));
        assertTrue(PhotoFormat.isModernPhoto("a.heif"));
        assertFalse(PhotoFormat.isModernPhoto("a.jpg"));
        assertFalse(PhotoFormat.isModernPhoto("a.dng"));
    }

    @Test
    public void savePlanAndUltraHdr() {
        assertEquals(Collections.singletonList(PhotoFormat.JPEG), PhotoOutput.plan(PhotoFormat.JPEG, false, 4000, 3000));
        assertEquals(Collections.singletonList(PhotoFormat.JPEG), PhotoOutput.plan(PhotoFormat.JPEG, true, 4000, 3000));
        assertEquals(Collections.singletonList(PhotoFormat.HEIC), PhotoOutput.plan(PhotoFormat.HEIC, false, 8160, 6120));
        assertEquals(Arrays.asList(PhotoFormat.HEIC, PhotoFormat.JPEG), PhotoOutput.plan(PhotoFormat.HEIC, true, 8160, 6120));
        assertEquals(Arrays.asList(PhotoFormat.WEBP, PhotoFormat.JPEG), PhotoOutput.plan(PhotoFormat.WEBP, true, 16383, 12288));
        // WebP holds at most 16383 px per side: a 200 MP photo of 16384 px is saved as JPEG.
        assertEquals(Collections.singletonList(PhotoFormat.JPEG), PhotoOutput.plan(PhotoFormat.WEBP, false, 16384, 12288));
        assertEquals(Collections.singletonList(PhotoFormat.JPEG), PhotoOutput.plan(PhotoFormat.WEBP, true, 12288, 16384));
        assertTrue(PhotoFormat.ultraHdrApplies(true, PhotoFormat.JPEG, false));
        assertFalse(PhotoFormat.ultraHdrApplies(true, PhotoFormat.HEIC, false));
        assertTrue(PhotoFormat.ultraHdrApplies(true, PhotoFormat.WEBP, true));
        assertFalse(PhotoFormat.ultraHdrApplies(false, PhotoFormat.JPEG, true));
    }

    @Test
    public void saveModeLabels() {
        assertEquals("JPEG", PhotoFormat.JPEG.saveModeShort(0));
        assertEquals("R+J", PhotoFormat.JPEG.saveModeShort(1));
        assertEquals("RAW", PhotoFormat.JPEG.saveModeShort(2));
        assertEquals("HEIC", PhotoFormat.HEIC.saveModeShort(0));
        assertEquals("R+H", PhotoFormat.HEIC.saveModeShort(1));
        assertEquals("WEBP", PhotoFormat.WEBP.saveModeShort(0));
        assertEquals("R+W", PhotoFormat.WEBP.saveModeShort(1));
        assertEquals("RAW", PhotoFormat.WEBP.saveModeShort(2));
        assertEquals("RAW + JPEG", PhotoFormat.JPEG.saveModeLong(1));
        assertEquals("RAW + HEIC", PhotoFormat.HEIC.saveModeLong(1));
        assertEquals("WebP", PhotoFormat.WEBP.saveModeLong(0));
        assertEquals("RAW", PhotoFormat.HEIC.saveModeLong(2));
    }

    @Test
    public void webpEncodingPerAndroidVersion() {
        assertArrayEquals(new Object[]{"WEBP_LOSSY", 90}, PhotoOutput.webpEncoding(false, 90, 30));
        assertArrayEquals(new Object[]{"WEBP_LOSSLESS", PhotoOutput.WEBP_LOSSLESS_EFFORT}, PhotoOutput.webpEncoding(true, 40, 35));
        // Android 8-10: one WEBP format, lossless exactly at quality 100, so a lossy one stays below it.
        assertArrayEquals(new Object[]{"WEBP", 100}, PhotoOutput.webpEncoding(true, 40, 29));
        assertArrayEquals(new Object[]{"WEBP", 99}, PhotoOutput.webpEncoding(false, 100, 26));
        assertArrayEquals(new Object[]{"WEBP_LOSSY", 1}, PhotoOutput.webpEncoding(false, 0, 33));
        for (Object[] e : new Object[][]{PhotoOutput.webpEncoding(false, 90, 30), PhotoOutput.webpEncoding(true, 90, 30),
                PhotoOutput.webpEncoding(false, 90, 26)})
            assertNotNull(Bitmap.CompressFormat.valueOf((String) e[0]));
        assertTrue(PhotoOutput.heicTimeoutMs(8160, 6120) > PhotoOutput.heicTimeoutMs(4000, 3000));
    }

    @Test
    public void exifDataBlockIsExifHeaderPlusTiff() {
        byte[] tiff = {'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] app1 = new byte[4 + 6 + tiff.length];
        app1[0] = (byte) 0xFF;
        app1[1] = (byte) 0xE1;
        app1[2] = 0;
        app1[3] = (byte) (2 + 6 + tiff.length);
        System.arraycopy("Exif\0\0".getBytes(StandardCharsets.US_ASCII), 0, app1, 4, 6);
        System.arraycopy(tiff, 0, app1, 10, tiff.length);
        byte[] block = ExifBlock.exifDataBlock(app1);
        assertNotNull(block);
        assertEquals("Exif\0\0II", new String(block, 0, 8, StandardCharsets.US_ASCII));
        assertEquals(6 + tiff.length, block.length);
        assertNull(ExifBlock.exifDataBlock(null));
        app1[4] = 'X';
        assertNull(ExifBlock.exifDataBlock(app1));
        // The block the camera builds from ParseExif: a JPEG APP1 whose data block starts with the TIFF header.
        byte[] real = ExifBlock.exifDataBlock(ExifBlock.app1Segment(exif(), 4000, 3000));
        assertNotNull(real);
        assertEquals("Exif\0\0", new String(real, 0, 6, StandardCharsets.US_ASCII));
        assertTrue(real[6] == 'I' && real[7] == 'I' || real[6] == 'M' && real[7] == 'M');
    }

    @Test
    public void webpPhotoWithExif() throws Exception {
        manager.getDefaultPreferences().edit().putString(PhotoFormat.KEY, "webp").putString(PhotoFormat.KEY_WEBP_QUALITY, "80").commit();
        Path base = tmp.getRoot().toPath().resolve("IMG_1");
        Bitmap bmp = picture(64, 48);
        PhotoOutput.Result result = PhotoOutput.save(base, bmp, exif(), null);
        assertTrue(bmp.isRecycled());
        assertEquals(Collections.singletonList(PhotoFormat.WEBP.fileFor(base)), result.files);
        byte[] data = Files.readAllBytes(result.files.get(0));
        assertEquals("RIFF", new String(data, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WEBP", new String(data, 8, 4, StandardCharsets.US_ASCII));
        ExifInterface exif = new ExifInterface(result.files.get(0).toFile());
        assertEquals(Build.BRAND, exif.getAttribute(ExifInterface.TAG_MAKE));
        assertEquals("64", exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH));
        assertEquals("48", exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH));
        assertEquals("400", exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY));
        // Pixels are stored rotated (no Orientation tag), as in the JPEG.
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED));
    }

    @Test
    public void losslessWebpAndExtraJpeg() throws Exception {
        manager.getDefaultPreferences().edit().putString(PhotoFormat.KEY, "webp").putBoolean(PhotoFormat.KEY_WEBP_LOSSLESS, true)
                .putBoolean(PhotoFormat.KEY_ALSO_JPEG, true).commit();
        Path base = tmp.getRoot().toPath().resolve("IMG_2");
        PhotoOutput.Result result = PhotoOutput.save(base, picture(40, 30), exif(), null);
        assertEquals(Arrays.asList(PhotoFormat.WEBP.fileFor(base), PhotoFormat.JPEG.fileFor(base)), result.files);
        // the photo format is announced last, so the camera thumbnail ends on it
        assertEquals(Arrays.asList(PhotoFormat.JPEG.fileFor(base), PhotoFormat.WEBP.fileFor(base)), result.notifyOrder());
        byte[] jpeg = Files.readAllBytes(result.files.get(1));
        assertEquals(0xFF, jpeg[0] & 0xFF);
        assertEquals(0xD8, jpeg[1] & 0xFF);
        assertEquals(Build.BRAND, new ExifInterface(result.files.get(1).toFile()).getAttribute(ExifInterface.TAG_MAKE));
        assertEquals(Build.BRAND, new ExifInterface(result.files.get(0).toFile()).getAttribute(ExifInterface.TAG_MAKE));
    }

    @Test
    public void heicWithoutAnEncoderIsSavedAsJpegOnce() {
        // The host has no HEVC encoder: HeifWriter fails and the photo is a JPEG, never lost and never written twice.
        for (boolean alsoJpeg : new boolean[]{false, true}) {
            manager.getDefaultPreferences().edit().putString(PhotoFormat.KEY, "heic").putBoolean(PhotoFormat.KEY_ALSO_JPEG, alsoJpeg).commit();
            Path base = tmp.getRoot().toPath().resolve("IMG_H" + alsoJpeg);
            Bitmap bmp = picture(32, 32);
            PhotoOutput.Result result = PhotoOutput.save(base, bmp, exif(), null);
            assertTrue(bmp.isRecycled());
            assertEquals(Collections.singletonList(PhotoFormat.JPEG.fileFor(base)), result.files);
            assertFalse(Files.exists(PhotoFormat.HEIC.fileFor(base)));
        }
    }

    @Test
    public void webpAboveItsSideLimitIsSavedAsJpeg() {
        manager.getDefaultPreferences().edit().putString(PhotoFormat.KEY, "webp").commit();
        Path base = tmp.getRoot().toPath().resolve("IMG_W");
        PhotoOutput.Result result = PhotoOutput.save(base, Bitmap.createBitmap(16384, 2, Bitmap.Config.ARGB_8888), exif(), null);
        assertEquals(Collections.singletonList(PhotoFormat.JPEG.fileFor(base)), result.files);
    }

    @Test
    public void jpegDefaultUnchanged() {
        Path base = tmp.getRoot().toPath().resolve("IMG_J");
        assertEquals(PhotoFormat.JPEG, PreferenceKeys.getPhotoFormat());
        PhotoOutput.Result result = PhotoOutput.save(base, picture(16, 16), exif(), null);
        assertEquals(Collections.singletonList(PhotoFormat.JPEG.fileFor(base)), result.files);
        List<Path> order = result.notifyOrder();
        assertEquals(result.files, order);
    }

    private static Bitmap picture(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) b.setPixel(x, y, Color.rgb(x * 255 / w, y * 255 / h, 128));
        return b;
    }

    private static ParseExif.ExifData exif() {
        ParseExif.ExifData d = new ParseExif.ExifData();
        d.SENSITIVITY_TYPE = String.valueOf(ExifInterface.SENSITIVITY_TYPE_ISO_SPEED);
        d.PHOTOGRAPHIC_SENSITIVITY = "400";
        d.F_NUMBER = "1.8";
        d.FOCAL_LENGTH = "690/100";
        d.APERTURE_VALUE = "1.8";
        d.EXPOSURE_TIME = "0.01";
        d.DATETIME = "2026:10:08 10:10:10";
        d.COMPRESSION = ParseExif.COMPRESSION_JPEG;
        d.COLOR_SPACE = "sRGB";
        d.EXIF_VERSION = "0231";
        d.IMAGE_DESCRIPTION = "test";
        return d;
    }
}
