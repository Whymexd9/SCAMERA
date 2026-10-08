package com.particlesdevs.photoncamera.processing;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.exifinterface.media.ExifInterface;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.heif.Heic10Support;
import com.particlesdevs.photoncamera.processing.heif.P010;
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * «HEIC 10 бит» in PhotoOutput: which HEIC path a shot takes, the fallbacks (10-bit -> 8-bit HeifWriter -> JPEG, never a
 * lost shot), the 8-bit copies for JPEG / WebP, and the default path untouched (an ARGB_8888 image never meets the 10-bit
 * writer or a copy). The 10-bit writer is a stub: the host has no HEVC encoder (the real one: tools/check_heic10.py).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhotoOutputHeic10Test {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private SettingsManager manager;
    private MockedStatic<PhotonCamera> camera;
    private PhotoOutput.TenBitHeicWriter realWriter;
    /** Calls of the stub writer: the bitmap config and the Exif block it got. */
    private final List<Bitmap.Config> configs = new ArrayList<>();
    private final List<byte[]> exifBlocks = new ArrayList<>();

    private static final Heic10Support.Encoder HW = new Heic10Support.Encoder("c2.test.hevc.encoder", true, true, true, true, true,
            0, 100, 1000, 100_000_000);

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
        realWriter = PhotoOutput.tenBitHeic;
    }

    @After
    public void tearDown() {
        PhotoOutput.tenBitHeic = realWriter;
        Heic10Support.clearForTesting();
        GLLimits.setForTesting(null);
        camera.close();
    }

    /** Stub writer: records the call and writes a marker file when {@code ok}. */
    private void stub(boolean ok) {
        PhotoOutput.tenBitHeic = (file, img, quality, exifBlock, timeoutMs, detail) -> {
            configs.add(img.getConfig());
            exifBlocks.add(exifBlock);
            assertTrue(timeoutMs >= 30_000);
            if (!ok) return false;
            try {
                Files.write(file, "heic10".getBytes(StandardCharsets.US_ASCII));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            detail.append("stub");
            return true;
        };
    }

    private void prefs(String format, boolean tenBit, boolean alsoJpeg) {
        manager.getDefaultPreferences().edit().putString(PhotoFormat.KEY, format).putBoolean(PhotoFormat.KEY_HEIC_10BIT, tenBit)
                .putBoolean(PhotoFormat.KEY_ALSO_JPEG, alsoJpeg).commit();
    }

    /** A 10-bit image with a smooth ramp: 10-bit values that 8 bits cannot hold. */
    private static Bitmap tenBitPicture(int w, int h) {
        final int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) px[y * w + x] = P010.pack(x * 1023 / (w - 1), y * 1023 / (h - 1), 513);
        final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_1010102);
        final ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        buf.asIntBuffer().put(px);
        b.copyPixelsFromBuffer(buf);
        return b;
    }

    private static Bitmap eightBitPicture(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) b.setPixel(x, y, Color.rgb(x * 255 / w, y * 255 / h, 128));
        return b;
    }

    @Test
    public void tenBitImageWithTheOptionGoesThroughTheTenBitWriter() throws Exception {
        Heic10Support.setForTesting(HW);
        prefs("heic", true, false);
        stub(true);
        final Path base = tmp.getRoot().toPath().resolve("IMG_10");
        final Bitmap img = tenBitPicture(64, 48);
        final PhotoOutput.Result result = PhotoOutput.save(base, img, exif(), null);
        assertTrue(img.isRecycled());
        assertEquals(Collections.singletonList(PhotoFormat.HEIC.fileFor(base)), result.files);
        assertEquals(Collections.singletonList(Bitmap.Config.RGBA_1010102), configs);
        final byte[] block = exifBlocks.get(0);
        assertNotNull(block);
        assertEquals("Exif\0\0", new String(block, 0, 6, StandardCharsets.US_ASCII));
        assertFalse(Files.exists(PhotoFormat.JPEG.fileFor(base)));
    }

    @Test
    public void failedTenBitEncodeFallsBackTo8BitHeicThenJpeg() throws Exception {
        Heic10Support.setForTesting(HW);
        prefs("heic", true, false);
        stub(false);
        final Path base = tmp.getRoot().toPath().resolve("IMG_F");
        final PhotoOutput.Result result = PhotoOutput.save(base, tenBitPicture(64, 48), exif(), null);
        assertEquals(1, configs.size());
        // the host has no HEVC encoder: HeifWriter fails as well and the shot is a JPEG, written from the 8-bit copy
        assertEquals(Collections.singletonList(PhotoFormat.JPEG.fileFor(base)), result.files);
        assertFalse(Files.exists(PhotoFormat.HEIC.fileFor(base)));
        final Bitmap jpeg = BitmapFactory.decodeFile(result.files.get(0).toString());
        assertEquals(64, jpeg.getWidth());
        // left-to-right red ramp of the 10-bit image, rounded to 8 bits (JPEG tolerance)
        assertEquals(0, Color.red(jpeg.getPixel(0, 24)), 6);
        assertEquals(255, Color.red(jpeg.getPixel(63, 24)), 6);
        assertEquals(128, Color.blue(jpeg.getPixel(32, 24)), 6);
    }

    @Test
    public void throwingTenBitWriterNeverLosesTheShot() {
        Heic10Support.setForTesting(HW);
        prefs("heic", true, false);
        PhotoOutput.tenBitHeic = (file, img, quality, exifBlock, timeoutMs, detail) -> {
            throw new IllegalStateException("codec died");
        };
        final Path base = tmp.getRoot().toPath().resolve("IMG_T");
        final PhotoOutput.Result result = PhotoOutput.save(base, tenBitPicture(32, 32), exif(), null);
        assertEquals(Collections.singletonList(PhotoFormat.JPEG.fileFor(base)), result.files);
    }

    @Test
    public void alsoJpegNextToTheTenBitHeic() throws Exception {
        Heic10Support.setForTesting(HW);
        prefs("heic", true, true);
        stub(true);
        final Path base = tmp.getRoot().toPath().resolve("IMG_J");
        final PhotoOutput.Result result = PhotoOutput.save(base, tenBitPicture(40, 30), exif(), null);
        assertEquals(Arrays.asList(PhotoFormat.HEIC.fileFor(base), PhotoFormat.JPEG.fileFor(base)), result.files);
        final byte[] jpeg = Files.readAllBytes(result.files.get(1));
        assertEquals(0xFF, jpeg[0] & 0xFF);
        assertEquals(0xD8, jpeg[1] & 0xFF);
        assertEquals("400", new ExifInterface(result.files.get(1).toFile()).getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY));
        assertNotNull(BitmapFactory.decodeFile(result.files.get(1).toString()));
    }

    @Test
    public void tenBitImageWithoutSupportIs8BitHeicOrJpeg() {
        Heic10Support.setForTesting(null); // no Main10 / P010 encoder
        prefs("heic", true, false);
        stub(true);
        final Path base = tmp.getRoot().toPath().resolve("IMG_N");
        final PhotoOutput.Result result = PhotoOutput.save(base, tenBitPicture(32, 32), exif(), null);
        assertTrue("the 10-bit writer is not used without support", configs.isEmpty());
        assertEquals(Collections.singletonList(PhotoFormat.JPEG.fileFor(base)), result.files);
    }

    @Test
    public void tenBitImageToWebpAndJpegFormats() throws Exception {
        Heic10Support.setForTesting(HW);
        stub(true);
        prefs("webp", true, false);
        Path base = tmp.getRoot().toPath().resolve("IMG_W");
        PhotoOutput.Result result = PhotoOutput.save(base, tenBitPicture(32, 24), exif(), null);
        assertEquals(Collections.singletonList(PhotoFormat.WEBP.fileFor(base)), result.files);
        Bitmap webp = BitmapFactory.decodeFile(result.files.get(0).toString());
        assertEquals(32, webp.getWidth());
        prefs("jpeg", true, false);
        base = tmp.getRoot().toPath().resolve("IMG_P");
        result = PhotoOutput.save(base, tenBitPicture(32, 24), exif(), null);
        assertEquals(Collections.singletonList(PhotoFormat.JPEG.fileFor(base)), result.files);
        assertTrue(configs.isEmpty());
    }

    @Test
    public void defaultPathNeverTouchesTheTenBitWriter() {
        // option off (default) with an 8-bit image, and option on with an 8-bit image (10-bit target unavailable)
        stub(true);
        Heic10Support.setForTesting(HW);
        for (boolean option : new boolean[]{false, true}) {
            prefs("heic", option, false);
            final Path base = tmp.getRoot().toPath().resolve("IMG_D" + option);
            final PhotoOutput.Result result = PhotoOutput.save(base, eightBitPicture(32, 32), exif(), null);
            assertEquals(Collections.singletonList(PhotoFormat.JPEG.fileFor(base)), result.files);
        }
        assertTrue(configs.isEmpty());
        manager.getDefaultPreferences().edit().clear().commit();
        assertFalse("10-bit HEIC is off by default", PreferenceKeys.isHeic10Bit());
        // an 8-bit image is its own 8-bit version: no copy
        final Bitmap eight = eightBitPicture(8, 8);
        final PhotoOutput.Pixels pixels = new PhotoOutput.Pixels(eight);
        assertFalse(pixels.tenBit);
        assertSame(eight, pixels.eightBit(true));
        assertFalse(eight.isRecycled());
        assertFalse(PhotoOutput.heic10Path(false, true));
        assertFalse(PhotoOutput.heic10Path(true, false));
        assertTrue(PhotoOutput.heic10Path(true, true));
    }

    @Test
    public void wantedFollowsTheSettingFormatAndDevice() {
        Heic10Support.setForTesting(HW);
        prefs("heic", false, false);
        assertFalse("off by default", Heic10Support.wanted());
        prefs("heic", true, false);
        assertTrue(Heic10Support.wanted());
        prefs("jpeg", true, false);
        assertFalse(Heic10Support.wanted());
        prefs("heic", true, false);
        Heic10Support.setForTesting(null);
        assertFalse(Heic10Support.wanted());
    }

    @Test
    public void tenBitCopyIsMadeOnceAndReleased() {
        final Bitmap ten = tenBitPicture(16, 16);
        final PhotoOutput.Pixels pixels = new PhotoOutput.Pixels(ten);
        assertTrue(pixels.tenBit);
        final Bitmap a = pixels.eightBit(false);
        assertEquals(Bitmap.Config.ARGB_8888, a.getConfig());
        assertSame(a, pixels.eightBit(true));
        assertTrue("the 10-bit image goes once its last 8-bit encode has its copy", ten.isRecycled());
        pixels.recycle();
        assertTrue(a.isRecycled());
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
