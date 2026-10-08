package com.particlesdevs.photoncamera.processing;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.exifinterface.media.ExifInterface;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.avif.AvifEncoder;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * AVIF photo output: the format's rules (extension, MIME type, Android 12+ with the encoder, 64 MP limit, labels), its
 * settings as the save path reads them, the save plan, the memory guard and the fallback to JPEG when the encoder is
 * missing or fails. The encoder itself is checked on the host by tools/check_avif.py (no native library in this JVM).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AvifOutputTest {
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
        AvifEncoder.setAvailableForTesting(null);
        GLLimits.setForTesting(null);
        camera.close();
    }

    @Test
    public void avifFieldsAndFileRules() {
        PhotoFormat a = PhotoFormat.AVIF;
        assertEquals("avif", a.value);
        assertEquals("avif", a.extension);
        assertEquals("image/avif", a.mime);
        assertEquals("AVIF", a.label);
        assertEquals(a, PhotoFormat.parse(" AVIF "));
        // the older codecs keep their order (their stored values are names, the shade's list values FormatChoice ordinals)
        assertEquals(Arrays.asList(PhotoFormat.JPEG, PhotoFormat.HEIC, PhotoFormat.WEBP, PhotoFormat.AVIF), Arrays.asList(PhotoFormat.values()));
        Path base = Paths.get("/storage/DCIM/Camera/IMG_20261008_101010_1");
        assertEquals("IMG_20261008_101010_1.avif", a.fileFor(base).getFileName().toString());
        assertEquals("image/avif", PhotoFormat.mimeForName("/storage/DCIM/Camera/IMG.AVIF"));
        assertTrue(PhotoFormat.isGalleryFile("a.avif"));
        assertTrue(PhotoFormat.isModernPhoto("a.Avif"));
        // the gallery decodes AVIF from Android 12 (ImageDecoder / BitmapFactory)
        assertFalse(PhotoFormat.decodable("a.avif", 30));
        assertTrue(PhotoFormat.decodable("a.avif", PhotoFormat.AVIF_MIN_SDK));
        assertEquals("AVIF", a.saveModeShort(0));
        assertEquals("R+A", a.saveModeShort(1));
        assertEquals("RAW", a.saveModeShort(2));
        assertEquals("AVIF", a.saveModeLong(0));
        assertEquals("RAW + AVIF", a.saveModeLong(1));
    }

    @Test
    public void offeredFromAndroid12WithTheEncoder() {
        assertFalse(PhotoFormat.avifOffered(30, true));
        assertFalse(PhotoFormat.avifOffered(35, false));
        assertTrue(PhotoFormat.avifOffered(31, true));
        assertEquals(PhotoFormat.JPEG, PhotoFormat.effective(PhotoFormat.AVIF, 30, true));
        assertEquals(PhotoFormat.JPEG, PhotoFormat.effective(PhotoFormat.AVIF, 35, false));
        assertEquals(PhotoFormat.AVIF, PhotoFormat.effective(PhotoFormat.AVIF, 31, true));
        assertEquals(PhotoFormat.HEIC, PhotoFormat.effective(PhotoFormat.HEIC, 35, false)); // the AVIF encoder concerns AVIF only
        // A stored AVIF choice is written as JPEG while the encoder is missing and kept for when it is back.
        manager.getDefaultPreferences().edit().putString(PhotoFormat.KEY, "avif").commit();
        AvifEncoder.setAvailableForTesting(false);
        assertEquals(PhotoFormat.JPEG, PreferenceKeys.getPhotoFormat());
        assertEquals(PhotoFormat.AVIF, PreferenceKeys.getChosenPhotoFormat());
        AvifEncoder.setAvailableForTesting(true);
        assertEquals(PhotoFormat.AVIF, PreferenceKeys.getPhotoFormat());
        assertFalse("Ultra HDR needs the extra JPEG", PhotoFormat.ultraHdrApplies(true, PhotoFormat.AVIF, false));
        assertTrue(PhotoFormat.ultraHdrApplies(true, PhotoFormat.AVIF, true));
    }

    @Test
    public void sizeLimitAndSavePlan() {
        assertTrue(PhotoFormat.AVIF.fits(8160, 6120)); // 50 MP sensors at full resolution
        assertTrue(PhotoFormat.AVIF.fits(8000, 8000)); // 64 MP
        assertFalse(PhotoFormat.AVIF.fits(16384, 12288)); // 200 MP
        assertFalse(PhotoFormat.AVIF.fits(0, 3000));
        assertEquals("max 64 MP", PhotoFormat.AVIF.limit());
        assertEquals("max side 16383", PhotoFormat.WEBP.limit());
        assertEquals("", PhotoFormat.JPEG.limit());
        assertEquals(Collections.singletonList(PhotoFormat.AVIF), PhotoOutput.plan(PhotoFormat.AVIF, false, 4096, 3072));
        assertEquals(Arrays.asList(PhotoFormat.AVIF, PhotoFormat.JPEG), PhotoOutput.plan(PhotoFormat.AVIF, true, 8160, 6120));
        assertEquals(Collections.singletonList(PhotoFormat.JPEG), PhotoOutput.plan(PhotoFormat.AVIF, false, 16384, 12288));
        assertEquals(Collections.singletonList(PhotoFormat.JPEG), PhotoOutput.plan(PhotoFormat.AVIF, true, 16384, 12288));
    }

    @Test
    public void settingsAsTheSavePathReadsThem() {
        assertEquals(90, PreferenceKeys.getAvifQuality());
        assertFalse(PreferenceKeys.isAvifLossless());
        assertEquals(PhotoFormat.AVIF_DEFAULT_DEPTH, PreferenceKeys.getAvifDepth());
        assertEquals(10, PreferenceKeys.getAvifDepth());
        assertTrue(PreferenceKeys.isAvifYuv444());
        assertEquals(PhotoFormat.AVIF_DEFAULT_SPEED, PreferenceKeys.getAvifSpeed());
        AvifEncoder.Options o = PreferenceKeys.getAvifOptions();
        assertEquals("10-bit 4:4:4 q90 speed 6", o.describe());
        assertTrue(o.threads >= 1);
        assertTrue(o.fullChroma());
        manager.getDefaultPreferences().edit().putString(PhotoFormat.KEY_AVIF_QUALITY, "250").putString(PhotoFormat.KEY_AVIF_DEPTH, "12")
                .putString(PhotoFormat.KEY_AVIF_CHROMA, "420").putString(PhotoFormat.KEY_AVIF_SPEED, "9").commit();
        assertEquals(100, PreferenceKeys.getAvifQuality());
        assertEquals(12, PreferenceKeys.getAvifDepth());
        assertFalse(PreferenceKeys.isAvifYuv444());
        assertEquals("12-bit 4:2:0 q100 speed 9", PreferenceKeys.getAvifOptions().describe());
        assertFalse(PreferenceKeys.getAvifOptions().fullChroma());
        // unknown stored values fall back to the defaults or the bounds
        manager.getDefaultPreferences().edit().putString(PhotoFormat.KEY_AVIF_DEPTH, "9").putString(PhotoFormat.KEY_AVIF_SPEED, "42")
                .putBoolean(PhotoFormat.KEY_AVIF_LOSSLESS, true).commit();
        assertEquals(10, PreferenceKeys.getAvifDepth());
        assertEquals(10, PreferenceKeys.getAvifSpeed());
        AvifEncoder.Options lossless = PreferenceKeys.getAvifOptions();
        assertEquals("lossless speed 10", lossless.describe());
        assertTrue("lossless is always 4:4:4", lossless.fullChroma());
        AvifEncoder.Options clamped = new AvifEncoder.Options(0, false, 7, false, -3, 0);
        assertEquals(1, clamped.quality);
        assertEquals(10, clamped.depth);
        assertEquals(0, clamped.speed);
        assertEquals(1, clamped.threads);
    }

    @Test
    public void memoryGuard() {
        // 12.6 MP at 4:4:4: ~450 MB of encoder memory, as measured on the host build
        long photo12 = AvifEncoder.workingBytes(4096, 3072, true);
        assertEquals(4096L * 3072 * 36, photo12);
        long photo50 = AvifEncoder.workingBytes(8160, 6120, true);
        assertTrue(AvifEncoder.workingBytes(8160, 6120, false) < photo50);
        assertTrue("unknown free memory: the encode is tried", PhotoOutput.avifMemoryAllows(photo50, -1));
        assertTrue(PhotoOutput.avifMemoryAllows(photo12, 2L << 30));
        assertFalse(PhotoOutput.avifMemoryAllows(photo50, 1L << 30));
        assertFalse("a reserve stays free", PhotoOutput.avifMemoryAllows(photo12, photo12));
    }

    @Test
    public void withoutTheEncoderLibraryAvifIsSavedAsJpegOnce() throws Exception {
        // This JVM has no libscameraAvif, as a phone where it did not load: AVIF chosen writes exactly one JPEG.
        AvifEncoder.setAvailableForTesting(null);
        assertFalse(AvifEncoder.available());
        for (boolean alsoJpeg : new boolean[]{false, true}) {
            manager.getDefaultPreferences().edit().putString(PhotoFormat.KEY, "avif").putBoolean(PhotoFormat.KEY_ALSO_JPEG, alsoJpeg).commit();
            Path base = tmp.getRoot().toPath().resolve("IMG_N" + alsoJpeg);
            Bitmap bmp = picture(32, 24);
            PhotoOutput.Result result = PhotoOutput.save(base, bmp, exif(), null);
            assertTrue(bmp.isRecycled());
            assertEquals(Collections.singletonList(PhotoFormat.JPEG.fileFor(base)), result.files);
            assertFalse(Files.exists(PhotoFormat.AVIF.fileFor(base)));
        }
    }

    @Test
    public void anEncoderFailureFallsBackToJpeg() throws Exception {
        // The encoder counts as available but fails (here the native call itself is missing): the photo is a JPEG with its
        // EXIF, no AVIF file is left, and with «Также сохранять JPEG» the JPEG is still written once.
        AvifEncoder.setAvailableForTesting(true);
        for (boolean alsoJpeg : new boolean[]{false, true}) {
            manager.getDefaultPreferences().edit().putString(PhotoFormat.KEY, "avif").putBoolean(PhotoFormat.KEY_ALSO_JPEG, alsoJpeg).commit();
            assertEquals(PhotoFormat.AVIF, PreferenceKeys.getPhotoFormat());
            Path base = tmp.getRoot().toPath().resolve("IMG_F" + alsoJpeg);
            Bitmap bmp = picture(40, 30);
            PhotoOutput.Result result = PhotoOutput.save(base, bmp, exif(), null);
            assertTrue(bmp.isRecycled());
            assertEquals(Collections.singletonList(PhotoFormat.JPEG.fileFor(base)), result.files);
            assertFalse(Files.exists(PhotoFormat.AVIF.fileFor(base)));
            byte[] jpeg = Files.readAllBytes(result.files.get(0));
            assertEquals(0xFF, jpeg[0] & 0xFF);
            assertEquals(0xD8, jpeg[1] & 0xFF);
            assertEquals("400", new ExifInterface(result.files.get(0).toFile()).getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY));
        }
        // the encoder reports the failure as an IOException and leaves no file behind
        Path file = tmp.getRoot().toPath().resolve("direct.avif");
        Files.write(file, new byte[]{1, 2, 3});
        Bitmap bmp = picture(8, 8);
        try {
            AvifEncoder.encode(bmp, file, PreferenceKeys.getAvifOptions(), null);
            fail("no native encoder in this JVM");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("AVIF"));
        }
        assertFalse(bmp.isRecycled());
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
        d.EXPOSURE_TIME = "0.01";
        d.DATETIME = "2026:10:08 10:10:10";
        d.COMPRESSION = ParseExif.COMPRESSION_JPEG;
        d.COLOR_SPACE = "sRGB";
        d.EXIF_VERSION = "0231";
        return d;
    }
}
