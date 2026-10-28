package com.particlesdevs.photoncamera.processing.render;

import android.app.Application;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * RAW black level from the data floor (Parameters.refineBlackLevel). vivo X200 Pro (MediaTek, owner's log 2026-10-07): a
 * 4096x3072 RAW_SENSOR holds the image in 4000x3000 and zeros elsewhere; the zeros set black 0 and every photo came out pink.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class ParametersBlackFloorTest {
    private MockedStatic<PreferenceKeys> prefs;
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> appStatic;

    @Before public void setup() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        com.particlesdevs.photoncamera.settings.SettingsManager settings = new com.particlesdevs.photoncamera.settings.SettingsManager(context);
        appStatic = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getAppContext).thenReturn(context);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettingsManagerStatic).thenReturn(settings);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(new com.particlesdevs.photoncamera.api.Settings());
        prefs = mockStatic(PreferenceKeys.class);
        prefs.when(PreferenceKeys::isRawBlackFromData).thenReturn(true);
    }
    @After public void cleanup() { prefs.close(); appStatic.close(); }

    private static Parameters parameters() throws Exception {
        Parameters p = new Parameters();
        p.whiteLevel = 1023; p.blackLevel = new float[]{64, 64, 64, 64};
        Field override = Parameters.class.getDeclaredField("blackLevelOverride");
        override.setAccessible(true); override.setFloat(p, -1f);
        Field white = Parameters.class.getDeclaredField("whiteLevelOverride");
        white.setAccessible(true); white.setInt(p, -1);
        return p;
    }

    /** w x h RAW16, floor + noise inside validW x validH, padValue outside. */
    private static ByteBuffer raw(int w, int h, int validW, int validH, int floor, int padValue) {
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        Random r = new Random(7);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int v = x < validW && y < validH ? floor + r.nextInt(5) - 2 + ((x / 256 + y / 256) % 3) * 40 : padValue;
            s.put(y * w + x, (short) v);
        }
        return b;
    }

    @Test public void zeroPaddingDoesNotDriveBlackToZero() throws Exception {
        Parameters p = parameters();
        // 4096x3072 with the image in 4000x3000, like camera 2 of the X200 Pro (scaled by 1/4 both ways)
        p.refineBlackLevel(raw(1024, 768, 1000, 750, 64, 0), 1024, 768);
        for (float b : p.blackLevel) assertEquals(64f, b, 0f);
    }

    @Test public void realFloorBelowReportedBlackStillLowersIt() throws Exception {
        Parameters p = parameters();
        // OPPO Find X8 Ultra: the reported 64 sits above the data floor (~60): lowered as before, padding or not
        p.refineBlackLevel(raw(1024, 768, 1000, 750, 60, 0), 1024, 768);
        for (float b : p.blackLevel) assertEquals(60f, b, 1.01f);
        Parameters q = parameters();
        q.refineBlackLevel(raw(1024, 768, 1024, 768, 60, 0), 1024, 768);
        for (float b : q.blackLevel) assertEquals(60f, b, 1.01f);
    }

    @Test public void noisyDarkFrameKeepsTheDriftCorrection() throws Exception {
        // A night frame of the OPPO: floor 60 under heavy read noise (sigma 14 DN of 10 bits), so single samples dip below 16
        // (a quarter of black 64) now and then. A block with such a sample is left out, the rest still find the floor.
        final int w = 1024, h = 768;
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        Random r = new Random(11);
        int below = 0;
        for (int i = 0; i < w * h; i++) {
            int v = (int) Math.round(60 + 14 * r.nextGaussian());
            if (v < 16) below++;
            s.put(i, (short) Math.max(0, v));
        }
        assertTrue("the frame must hold samples below black / 4", below > 0);
        Parameters p = parameters();
        p.refineBlackLevel(b, w, h);
        // lowered from 64 as without the padding rule; the third-lowest block mean is a low noise quantile (about 56 here,
        // the same value the estimator gave before the rule)
        for (float v : p.blackLevel) assertTrue("black " + v, v > 52f && v < 62f);
    }
    /** w x h RAW16: pedestal + Gaussian noise (clipped at 0) where {@code dark}, pedestal + 300 elsewhere; zeros outside validW x validH. */
    private static ByteBuffer scene(int w, int h, int validW, int validH, int pedestal, double sigma, java.util.function.BiPredicate<Integer, Integer> dark) {
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        Random r = new Random(13);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int v = 0;
            if (x < validW && y < validH)
                v = (int) Math.max(0, Math.round(pedestal + (dark.test(x, y) ? 0 : 300) + sigma * r.nextGaussian()));
            s.put(y * w + x, (short) v);
        }
        return b;
    }

    @Test public void rawWithoutItsPedestalDropsTheBlackToTheData() throws Exception {
        // P114, OPPO Find X8 Ultra until a restart (owner's log 2026-10-13): black 64 reported, the data's pedestal gone; two
        // thirds of the frame in shadow. Every shadow block holds samples below 16, so the padding rule left out all of them
        // and kept 64 (in the owner's frames 18-23 DN): teal shadows. Now the black follows the data down to ~1 DN.
        Parameters p = parameters();
        p.refineBlackLevel(scene(1024, 768, 1024, 768, 0, 3, (x, y) -> (x / 64 + y / 64) % 3 != 0), 1024, 768);
        for (float v : p.blackLevel) assertTrue("black " + v, v < 5f);
    }

    @Test public void noisyNightWithPaddingKeepsItsPedestal() throws Exception {
        // A noisy night (sigma 30 DN) of a camera with zero padding, like the X200 Pro's 4096x3072 buffer holding a 4000x3000
        // image (scaled 1/4: 96 px and 72 rows of zeros): most blocks dip below 16, yet the pedestal is there; the padding and
        // its border blocks stay out, the black stays near 64 (it must not fall to the half-padding border blocks' ~30).
        Parameters p = parameters();
        p.refineBlackLevel(scene(1024, 768, 928, 696, 64, 30, (x, y) -> true), 1024, 768);
        for (float v : p.blackLevel) assertTrue("black " + v, v > 50f);
    }
    /**
     * P127: the shot's own frame holds samples its resolved white cannot (a session that had not shown its 14 bits yet): black
     * and white rescaled to that depth; a frame lit to 12 bits only whose darkest blocks sit on 64 x 16 is 14 bits too.
     */
    @Test public void shotFrameBeyondItsWhiteWidensTheLevels() throws Exception {
        com.particlesdevs.photoncamera.processing.RawLevels.newSession(true);
        Parameters p = parameters();
        p.levelBits = 10;
        // 14-bit DCG frame lit to 12 bits only: pedestal 1024, the lit half ~2500
        p.refineBlackLevel(lit(1024, 768, 1024, 1500, 20), 1024, 768);
        assertEquals(14, p.levelBits);
        assertEquals(16383, p.whiteLevel);
        for (float v : p.blackLevel) assertEquals(1024f, v, 30f);

        Parameters q = parameters();
        q.levelBits = 10;
        // a real 12-bit stream: pedestal 256, the lit half ~1756
        q.refineBlackLevel(lit(1024, 768, 256, 1500, 10), 1024, 768);
        assertEquals(12, q.levelBits);
        assertEquals(4095, q.whiteLevel);
        for (float v : q.blackLevel) assertEquals(256f, v, 10f);
    }

    /** w x h RAW16: the left half at the pedestal, the right half {@code light} above it, Gaussian noise. */
    private static ByteBuffer lit(int w, int h, int pedestal, int light, double sigma) {
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        Random r = new Random(17);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            s.put(y * w + x, (short) Math.max(0, Math.round(pedestal + (x < w / 2 ? 0 : light) + sigma * r.nextGaussian())));
        return b;
    }

    @Test public void tenBitFrameKeepsItsLevels() throws Exception {
        Parameters p = parameters();
        p.levelBits = 10;
        p.refineBlackLevel(scene(1024, 768, 1024, 768, 64, 5, (x, y) -> x < 512), 1024, 768);
        assertEquals(10, p.levelBits);
        assertEquals(1023, p.whiteLevel);
    }

    /**
     * P386: the daylight scene of the owner's 2.4x tele shot (vivo X200 Ultra camera 5, 2026-10-28 15:38, ISO 100), scaled 1/4: a
     * sky (G 350, R 146, B 240 DN), a sunlit brick wall (G 45, R 19, B 31) and below it trees - 64 px tiles of deep shade (each
     * colour cell either a dark gap, G 6 / R 2 / B 4, or a lit twig, G 30 / R 12 / B 20) next to tiles of open shade (G 24, R 10,
     * B 16). GRBG, the colour block {@code block} (2: Quad, each colour a 2x2 square of a 4x4 cell), the shot's read and shot
     * noise (0.133 + 0.0678 x DN^2 of 10 bits), {@code pedestal} added (0: the RAW without it).
     */
    private static ByteBuffer daylight(int block, int pedestal, long seed) {
        final int w = 1024, h = 768, cell = 2 * block;
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        Random r = new Random(seed);
        final double[][] gap = new double[w / cell][h / cell];
        for (int cy = 0; cy < h / cell; cy++) for (int cx = 0; cx < w / cell; cx++) gap[cx][cy] = r.nextDouble();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            // GRBG at the colour block: G top-left, R top-right, B bottom-left, G bottom-right
            final int phase = ((y / block) & 1) * 2 + ((x / block) & 1);
            final int colour = phase == 1 ? 0 : phase == 2 ? 2 : 1;
            final double[] rgb;
            if (y < 200) rgb = new double[]{146, 350, 240};
            else if (y < 500) rgb = new double[]{19, 45, 31};
            else if (((x / 64) + (y / 64)) % 2 == 0)
                rgb = gap[x / cell][y / cell] < 0.5 ? new double[]{2, 6, 4} : new double[]{12, 30, 20};
            else rgb = new double[]{10, 24, 16};
            final double signal = rgb[colour];
            final long v = Math.round(pedestal + signal + Math.sqrt(0.133 + 0.0678 * signal) * r.nextGaussian());
            s.put(y * w + x, (short) Math.max(0, Math.min(1023, v)));
        }
        return b;
    }

    @Test public void quadStreamWithoutItsPedestalInDaylightDropsTheBlackToItsDarkestSamples() throws Exception {
        // P386: read as plain Bayer every channel of the Quad stream was green; a third of its blocks held a sample below 16 (the
        // deep shade), the rest's floor was the open shade's green, ~24 DN - the shot's «data floor (24.0625)» - and every
        // channel lost 24 DN: the photo teal. Each colour's own sites, P315's proof of the lost pedestal and its darkest samples
        // (its block means still hold the twigs' light: ~6 DN of red) give the true black, ~0. The old code gave 23.7 here.
        Parameters p = parameters();
        p.refineBlackLevel(daylight(2, 0, 21), 1024, 768, 2);
        for (float v : p.blackLevel) assertTrue("black " + v, v < 3f);
        assertTrue(p.levelSources(), p.levelSources().contains("without its pedestal"));
    }

    @Test public void bayerStreamWithoutItsPedestalInDaylightDropsTheBlackToItsDarkestSamples() throws Exception {
        // the same daylight on a plain Bayer stream: a quarter of the blocks below 16, P114's padding rule took the floor of the
        // rest (the wall's red: 18.8 DN)
        Parameters p = parameters();
        p.refineBlackLevel(daylight(1, 0, 22), 1024, 768);
        for (float v : p.blackLevel) assertTrue("black " + v, v < 3f);
    }

    @Test public void daylightWithItsPedestalKeepsIt() throws Exception {
        // the same scenes with the 64 DN pedestal: nothing proves it lost, the darkest blocks sit on it
        for (int block : new int[]{1, 2}) {
            Parameters p = parameters();
            p.refineBlackLevel(daylight(block, 64, 23 + block), 1024, 768, block);
            for (float v : p.blackLevel) assertEquals("block " + block, 64f, v, 0f);
            assertFalse(p.levelSources(), p.levelSources().contains("without its pedestal"));
        }
    }

    @Test public void singleNoisySamplesAloneNeverDropTheBlack() throws Exception {
        // P386 takes P315's proof (non-zero samples below black / 4 in >= 2 % of the blocks) only with the darkest block means
        // below half the black: the OPPO night of noisyDarkFrameKeepsTheDriftCorrection (sigma 14 DN around 60) holds such samples
        // in ~5 % of its blocks, its block means stay near 60, and the black stays P114's floor of the blocks without them
        final int w = 1024, h = 768;
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        Random r = new Random(11);
        for (int i = 0; i < w * h; i++) s.put(i, (short) Math.max(0, (int) Math.round(60 + 14 * r.nextGaussian())));
        final Parameters.BlockScan scan = Parameters.BlockScan.of(b.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer(), w,
                w / 64, h / 64, 16f, true);
        final int blocks = 4 * (w / 64) * (h / 64);
        assertTrue("P315's proof alone would call it lost: " + scan.hits + " of " + blocks, scan.hits >= 0.02f * blocks);
        assertTrue("and P114's would not", scan.padding <= blocks / 2);
        Parameters p = parameters();
        p.refineBlackLevel(b, w, h);
        for (float v : p.blackLevel) assertTrue("black " + v, v > 52f && v < 62f);
        assertFalse(p.levelSources(), p.levelSources().contains("without its pedestal"));
    }
}
