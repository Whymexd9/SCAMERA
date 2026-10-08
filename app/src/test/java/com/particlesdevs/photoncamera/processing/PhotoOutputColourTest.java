package com.particlesdevs.photoncamera.processing;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorSpace;

import androidx.exifinterface.media.ExifInterface;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.avif.AvifEncoder;
import com.particlesdevs.photoncamera.processing.color.IccEmbed;
import com.particlesdevs.photoncamera.processing.color.IccProfiles;
import com.particlesdevs.photoncamera.processing.color.OutputColour;
import com.particlesdevs.photoncamera.processing.heif.Heic10Support;
import com.particlesdevs.photoncamera.processing.heif.P010;
import com.particlesdevs.photoncamera.processing.opengl.GLLimits;
import com.particlesdevs.photoncamera.processing.ultrahdr.GainMapComputer;
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
 * P46 in PhotoOutput: «Цветовое пространство» Display P3 tags every format (JPEG / Ultra HDR APP2, WebP ICCP, 10-bit HEIC nclx
 * 12 + ICC, AVIF CICP 12 + ICC), «HDR в HEIC / AVIF» writes the HLG picture (nclx / CICP 9/18/9) with an SDR fallback, and the
 * defaults write exactly the files of the four-argument save (no profile, the old encoder calls). The HEVC / AV1 encoders are
 * stubs (the host has neither; the real ones: tools/check_heic10.py, tools/check_avif.py).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhotoOutputColourTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private SettingsManager manager;
    private MockedStatic<PhotonCamera> camera;
    private PhotoOutput.TenBitHeicWriter realHeic;
    private PhotoOutput.AvifWriter realAvif;
    private PhotoOutput.HdrRenderer realHdr;
    private final List<OutputColour.Signal> heicColours = new ArrayList<>();
    private final List<Bitmap.Config> heicConfigs = new ArrayList<>();
    private final List<int[]> heicPixels = new ArrayList<>();
    private final List<AvifEncoder.Options> avifOptions = new ArrayList<>();
    private final List<Bitmap.Config> avifConfigs = new ArrayList<>();

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
        realHeic = PhotoOutput.tenBitHeic;
        realAvif = PhotoOutput.avifWriter;
        realHdr = PhotoOutput.hdrRenderer;
        AvifEncoder.setAvailableForTesting(true);
        PhotoOutput.tenBitHeic = (file, img, quality, exifBlock, timeoutMs, detail, colour) -> {
            heicColours.add(colour);
            heicConfigs.add(img.getConfig());
            heicPixels.add(pixels(img));
            write(file, "heic10");
            return true;
        };
        PhotoOutput.avifWriter = (img, file, options, exifBlock) -> {
            avifOptions.add(options);
            avifConfigs.add(img.getConfig());
            write(file, "avif");
            return new AvifEncoder.Result(new long[]{options.depth, 1, 1, 1, 4, 1});
        };
    }

    @After
    public void tearDown() {
        PhotoOutput.tenBitHeic = realHeic;
        PhotoOutput.avifWriter = realAvif;
        PhotoOutput.hdrRenderer = realHdr;
        AvifEncoder.setAvailableForTesting(null);
        Heic10Support.clearForTesting();
        GLLimits.setForTesting(null);
        camera.close();
    }

    private static void write(Path file, String text) {
        try {
            Files.write(file, text.getBytes(StandardCharsets.US_ASCII));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void prefs(String format, boolean alsoJpeg) {
        manager.getDefaultPreferences().edit().putString(PhotoFormat.KEY, format).putBoolean(PhotoFormat.KEY_HEIC_10BIT, true)
                .putBoolean(PhotoFormat.KEY_ALSO_JPEG, alsoJpeg).commit();
    }

    private static ParseExif.ExifData exif() {
        ParseExif.ExifData d = new ParseExif.ExifData();
        d.SENSITIVITY_TYPE = String.valueOf(ExifInterface.SENSITIVITY_TYPE_ISO_SPEED);
        d.PHOTOGRAPHIC_SENSITIVITY = "400";
        d.F_NUMBER = "1.8";
        d.EXPOSURE_TIME = "0.01";
        d.DATETIME = "2026:10:08 10:10:10";
        d.COMPRESSION = ParseExif.COMPRESSION_JPEG;
        d.COLOR_SPACE = "sRGB";
        return d;
    }

    private static Bitmap picture(int w, int h) {
        final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) b.setPixel(x, y, Color.rgb(x * 255 / w, y * 255 / h, 90));
        return b;
    }

    private static Bitmap tenBitPicture(int w, int h) {
        final int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) px[y * w + x] = P010.pack(x * 1023 / (w - 1), y * 1023 / (h - 1), 1023);
        final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_1010102);
        final ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        buf.asIntBuffer().put(px);
        b.copyPixelsFromBuffer(buf);
        return b;
    }

    private static int[] pixels(Bitmap b) {
        final ByteBuffer buf = ByteBuffer.allocateDirect(b.getByteCount()).order(ByteOrder.nativeOrder());
        b.copyPixelsToBuffer(buf);
        buf.rewind();
        final int[] out = new int[b.getWidth() * b.getHeight()];
        buf.asIntBuffer().get(out);
        return out;
    }

    /** A gain map of the image size, every value {@code v}, GainMapMax {@code max} stops. */
    private static GainMapComputer.Result gain(int w, int h, int v, float max) throws Exception {
        final Bitmap m = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        m.eraseColor(Color.rgb(v, v, v));
        final java.lang.reflect.Constructor<GainMapComputer.Result> c =
                GainMapComputer.Result.class.getDeclaredConstructor(Bitmap.class, float.class, float.class);
        c.setAccessible(true);
        return c.newInstance(m, 0f, max);
    }

    private static ColorSpace decodedSpace(Path file) {
        final BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.toString(), o);
        return o.outColorSpace;
    }

    // ------------------------------------------------------------------------------------------------ defaults

    @Test
    public void defaultJpegIsTheOldFileWithoutAProfile() throws Exception {
        prefs("jpeg", false);
        final Bitmap img = picture(48, 32);
        final Bitmap copy = img.copy(Bitmap.Config.ARGB_8888, false);
        final Path base = tmp.getRoot().toPath().resolve("IMG_D");
        final PhotoOutput.Result r = PhotoOutput.save(base, img, exif(), null, null, OutputColour.Space.SRGB);
        final Path old = tmp.getRoot().toPath().resolve("OLD.jpg");
        assertTrue(ImageSaver.Util.saveBitmapAsJPG(old, copy, PreferenceKeys.getJpegQuality(), exif(), true));
        final byte[] got = Files.readAllBytes(r.files.get(0));
        assertArrayEquals("the same bytes as the pre-P46 JPEG path", Files.readAllBytes(old), got);
        assertNotEquals("no Display P3 profile", Arrays.toString(IccProfiles.displayP3()), Arrays.toString(IccEmbed.jpegProfile(got)));
    }

    @Test
    public void defaultWebpIsTheOldFileWithoutAProfile() throws Exception {
        prefs("webp", false);
        final Bitmap img = picture(48, 32);
        final Bitmap copy = img.copy(Bitmap.Config.ARGB_8888, false);
        final PhotoOutput.Result r = PhotoOutput.save(tmp.getRoot().toPath().resolve("IMG_W"), img, exif(), null, null, OutputColour.Space.SRGB);
        final Path old = tmp.getRoot().toPath().resolve("OLD.webp");
        assertTrue(PhotoOutput.saveWebp(old, copy, PreferenceKeys.getWebpQuality(), false, exif()));
        final byte[] got = Files.readAllBytes(r.files.get(0));
        assertArrayEquals(Files.readAllBytes(old), got);
        assertNotEquals("no Display P3 profile", Arrays.toString(IccProfiles.displayP3()), Arrays.toString(IccEmbed.webpProfile(got)));
    }

    @Test
    public void defaultHeicAndAvifDeclareTheOldColour() throws Exception {
        Heic10Support.setForTesting(HW);
        prefs("heic", false);
        PhotoOutput.save(tmp.getRoot().toPath().resolve("IMG_H"), tenBitPicture(32, 32), exif(), null);
        assertEquals(1, heicColours.size());
        assertTrue(heicColours.get(0).isDefault());
        prefs("avif", false);
        PhotoOutput.save(tmp.getRoot().toPath().resolve("IMG_A"), tenBitPicture(32, 32), exif(), null);
        assertEquals(1, avifOptions.size());
        assertTrue(avifOptions.get(0).colour.isDefault());
        assertEquals(PreferenceKeys.getAvifOptions().describe(), avifOptions.get(0).describe());
    }

    // ------------------------------------------------------------------------------------------------ Display P3

    @Test
    public void displayP3JpegCarriesTheProfileAndAndroidReadsP3() throws Exception {
        prefs("jpeg", false);
        final PhotoOutput.Result r = PhotoOutput.save(tmp.getRoot().toPath().resolve("IMG_P"), picture(48, 32), exif(), null, null,
                OutputColour.Space.DISPLAY_P3);
        final Path f = r.files.get(0);
        final byte[] got = Files.readAllBytes(f);
        assertArrayEquals(IccProfiles.displayP3(), IccEmbed.jpegProfile(got));
        assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), decodedSpace(f));
        final ExifInterface e = new ExifInterface(f.toFile());
        assertEquals("EXIF kept next to the profile", "400", e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY));
        assertEquals("EXIF ColorSpace: uncalibrated (not sRGB)", "65535", e.getAttribute(ExifInterface.TAG_COLOR_SPACE));
        final Bitmap decoded = BitmapFactory.decodeFile(f.toString());
        assertEquals(48, decoded.getWidth());
    }

    @Test
    public void displayP3WebpCarriesTheProfile() throws Exception {
        prefs("webp", false);
        final PhotoOutput.Result r = PhotoOutput.save(tmp.getRoot().toPath().resolve("IMG_PW"), picture(48, 32), exif(), null, null,
                OutputColour.Space.DISPLAY_P3);
        final Path f = r.files.get(0);
        assertArrayEquals(IccProfiles.displayP3(), IccEmbed.webpProfile(Files.readAllBytes(f)));
        assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), decodedSpace(f));
        assertEquals("400", new ExifInterface(f.toFile()).getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY));
        assertNotNull(BitmapFactory.decodeFile(f.toString()));
    }

    @Test
    public void displayP3UltraHdrHasTheProfileInThePrimaryOnly() throws Exception {
        prefs("jpeg", false);
        final PhotoOutput.Result r = PhotoOutput.save(tmp.getRoot().toPath().resolve("IMG_U"), picture(48, 32), exif(),
                gain(48, 32, 128, 2f), null, OutputColour.Space.DISPLAY_P3);
        final byte[] f = Files.readAllBytes(r.files.get(0));
        assertArrayEquals(IccProfiles.displayP3(), IccEmbed.jpegProfile(f));
        // the gain-map image after the primary (its SOI right before its hdrgm XMP with GainMapMax) has none
        final int max = new String(f, StandardCharsets.ISO_8859_1).indexOf("hdrgm:GainMapMax");
        assertTrue(max > 0);
        int second = -1;
        for (int i = max; i > 2; i--) if ((f[i] & 0xFF) == 0xFF && (f[i + 1] & 0xFF) == 0xD8) { second = i; break; }
        assertTrue(second > 2);
        assertNotEquals("the gain map is not tagged P3", Arrays.toString(IccProfiles.displayP3()),
                Arrays.toString(IccEmbed.jpegProfile(Arrays.copyOfRange(f, second, f.length))));
        assertTrue(com.particlesdevs.photoncamera.gallery.helper.UltraHdrGalleryUtil.containsUltraHdrMarkers(f, Math.min(f.length, 65536)));
        assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), decodedSpace(r.files.get(0)));
    }

    @Test
    public void displayP3HeicAndAvifDeclareP3() throws Exception {
        Heic10Support.setForTesting(HW);
        prefs("heic", true);
        final PhotoOutput.Result h = PhotoOutput.save(tmp.getRoot().toPath().resolve("IMG_PH"), tenBitPicture(32, 32), exif(), null,
                null, OutputColour.Space.DISPLAY_P3);
        assertEquals(Arrays.asList(PhotoFormat.HEIC, PhotoFormat.JPEG).size(), h.files.size());
        assertEquals(12, heicColours.get(0).primaries);
        assertArrayEquals(IccProfiles.displayP3(), heicColours.get(0).icc());
        assertArrayEquals("the extra JPEG is P3 too", IccProfiles.displayP3(), IccEmbed.jpegProfile(Files.readAllBytes(h.files.get(1))));
        prefs("avif", false);
        PhotoOutput.save(tmp.getRoot().toPath().resolve("IMG_PA"), tenBitPicture(32, 32), exif(), null, null, OutputColour.Space.DISPLAY_P3);
        final OutputColour.Signal c = avifOptions.get(0).colour;
        assertEquals(12, c.primaries);
        assertEquals(13, c.transfer);
        assertEquals(1, c.matrix);
        assertArrayEquals(IccProfiles.displayP3(), c.icc());
    }

    // ------------------------------------------------------------------------------------------------ HDR

    @Test
    public void hdrHeicIsTheHlgPicture() throws Exception {
        Heic10Support.setForTesting(HW);
        prefs("heic", false);
        final Bitmap img = tenBitPicture(32, 16);
        final PhotoOutput.Result r = PhotoOutput.save(tmp.getRoot().toPath().resolve("IMG_HH"), img, exif(), null, gain(32, 16, 0, 2f),
                OutputColour.Space.SRGB);
        assertEquals(Collections.singletonList(PhotoFormat.HEIC.fileFor(tmp.getRoot().toPath().resolve("IMG_HH"))), r.files);
        assertEquals(1, heicColours.size());
        assertEquals(OutputColour.Signal.HLG.toString(), heicColours.get(0).toString());
        assertEquals(Bitmap.Config.RGBA_1010102, heicConfigs.get(0));
        // top-right pixel: (1023, 0, 1023) magenta; bottom-right (1023, 1023, 1023) white -> 75 % HLG
        final int white = heicPixels.get(0)[15 * 32 + 31];
        assertEquals(767, (white >>> 10) & 0x3FF, 2);
    }

    @Test
    public void failedHdrFallsBackToTheSdrHeic() throws Exception {
        Heic10Support.setForTesting(HW);
        prefs("heic", false);
        PhotoOutput.hdrRenderer = (base, gain, space) -> {
            throw new IllegalStateException("no memory");
        };
        final PhotoOutput.Result r = PhotoOutput.save(tmp.getRoot().toPath().resolve("IMG_HF"), tenBitPicture(32, 16), exif(), null,
                gain(32, 16, 0, 2f), OutputColour.Space.SRGB);
        assertEquals(1, r.files.size());
        assertEquals(1, heicColours.size());
        assertTrue("SDR 10-bit HEIC in the default colour", heicColours.get(0).isDefault());
    }

    @Test
    public void hdrAvifTakesTheHlgPictureAtItsDepth() throws Exception {
        prefs("avif", false);
        manager.getDefaultPreferences().edit().putString(PhotoFormat.KEY_AVIF_DEPTH, "12").commit();
        PhotoOutput.save(tmp.getRoot().toPath().resolve("IMG_HA"), picture(32, 16), exif(), null, gain(32, 16, 200, 3f),
                OutputColour.Space.DISPLAY_P3);
        assertEquals(1, avifOptions.size());
        assertEquals(OutputColour.Signal.HLG.toString(), avifOptions.get(0).colour.toString());
        assertEquals(12, avifOptions.get(0).depth);
        assertEquals(Bitmap.Config.RGBA_1010102, avifConfigs.get(0));
        // 8-bit AVIF cannot hold HDR: SDR (here the P3 colour)
        avifOptions.clear();
        manager.getDefaultPreferences().edit().putString(PhotoFormat.KEY_AVIF_DEPTH, "8").commit();
        PhotoOutput.save(tmp.getRoot().toPath().resolve("IMG_H8"), picture(32, 16), exif(), null, gain(32, 16, 200, 3f),
                OutputColour.Space.DISPLAY_P3);
        assertEquals(12, avifOptions.get(0).colour.primaries);
        assertEquals(Bitmap.Config.ARGB_8888, avifConfigs.get(1));
    }

    @Test
    public void hdrLeavesTheExtraJpegSdr() throws Exception {
        Heic10Support.setForTesting(HW);
        prefs("heic", true);
        final PhotoOutput.Result r = PhotoOutput.save(tmp.getRoot().toPath().resolve("IMG_HJ"), tenBitPicture(32, 16), exif(), null,
                gain(32, 16, 255, 2f), OutputColour.Space.SRGB);
        assertEquals(2, r.files.size());
        final byte[] jpeg = Files.readAllBytes(r.files.get(1));
        assertFalse("no Ultra HDR without its switch",
                com.particlesdevs.photoncamera.gallery.helper.UltraHdrGalleryUtil.containsUltraHdrMarkers(jpeg, Math.min(jpeg.length, 65536)));
        assertNotEquals(Arrays.toString(IccProfiles.displayP3()), Arrays.toString(IccEmbed.jpegProfile(jpeg)));
    }
}
