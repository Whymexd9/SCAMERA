package com.particlesdevs.photoncamera.processing.render;

import android.app.Application;
import com.particlesdevs.photoncamera.processing.PedestalMemory;
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
 * P386 (review of the first fix): the black of a RAW that came without its pedestal, on the reviewer's probe frames - a GRBG stream
 * at colour block 1 (Bayer) and 2 (Quad), declared black 64 of 1023, sigma^2 = read^2 + shot x signal. True black vs the black
 * taken, within ~2 DN wherever the frame decides it; a frame without deep shade decides nothing alone and takes its stream's floor
 * (PedestalMemory) once another frame of the stream proved the loss.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class ParametersPedestalLossTest {
    private MockedStatic<PreferenceKeys> prefs;
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> appStatic;
    private static int streams;

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
        prefs.when(() -> PreferenceKeys.scamDevSwitch(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
    }
    @After public void cleanup() { prefs.close(); appStatic.close(); }

    /** A stream key of its own for each use (the memory lives for the process). */
    private static String stream() { return "5|4096x3072|probe" + (++streams) + "|5"; }

    private static Parameters parameters() throws Exception {
        Parameters p = new Parameters();
        p.whiteLevel = 1023; p.blackLevel = new float[]{64, 64, 64, 64};
        Field override = Parameters.class.getDeclaredField("blackLevelOverride");
        override.setAccessible(true); override.setFloat(p, -1f);
        Field white = Parameters.class.getDeclaredField("whiteLevelOverride");
        white.setAccessible(true); white.setInt(p, -1);
        return p;
    }

    interface Scene { double[] rgb(int x, int y); }

    /** w x h GRBG at colour block {@code block}, {@code pedestal} added; the last {@code padRows} rows (or the first {@code padCols}
     * columns) uniform in [padLo, padHi]. */
    static ByteBuffer frame(int w, int h, int block, double pedestal, double read, double shot, long seed, Scene scene,
                            int padRows, int padCols, int padLo, int padHi) {
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        Random r = new Random(seed);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            long v;
            if (y >= h - padRows || x < padCols) v = padLo + r.nextInt(padHi - padLo + 1);
            else {
                final int phase = ((y / block) & 1) * 2 + ((x / block) & 1);
                final int colour = phase == 1 ? 0 : phase == 2 ? 2 : 1;
                final double sig = scene.rgb(x, y)[colour];
                v = Math.round(pedestal + sig + Math.sqrt(read * read + shot * sig) * r.nextGaussian());
            }
            s.put(y * w + x, (short) Math.max(0, Math.min(1023, v)));
        }
        return b;
    }

    static ByteBuffer frame(int block, double pedestal, double read, double shot, long seed, Scene scene, int padRows, int padLo, int padHi) {
        return frame(1024, 768, block, pedestal, read, shot, seed, scene, padRows, 0, padLo, padHi);
    }

    static float black(ByteBuffer f, int w, int h, int block, String stream) throws Exception {
        Parameters p = parameters();
        p.refineBlackLevel(f, w, h, block, stream);
        float max = 0;
        for (float v : p.blackLevel) max = Math.max(max, v);
        return max;
    }

    static float black(ByteBuffer f, int block, String stream) throws Exception { return black(f, 1024, 768, block, stream); }

    static final double READ = Math.sqrt(0.133), SHOT = 0.0678;   // the shot's noise (ISO 100, 10 bits)
    // the reviewer's scenes
    static final Scene SKY_WALL = (x, y) -> y < 300 ? new double[]{146, 350, 240} : new double[]{19, 45, 31};
    static final Scene FACADE = (x, y) -> new double[]{40 + 20 * ((x / 128) % 3), 96 + 48 * ((x / 128) % 3), 68 + 34 * ((x / 128) % 3)};
    static final Scene OPEN_SHADE = (x, y) -> y < 200 ? new double[]{146, 350, 240} : y < 500 ? new double[]{19, 45, 31} : new double[]{10, 24, 16};
    static final Scene NIGHT_C = (x, y) -> ((x / 64 + y / 64) % 3 == 0) ? new double[]{60, 120, 90} : new double[]{1, 3, 2};
    static final Scene NIGHT_C2 = (x, y) -> ((x / 64 + y / 64) % 3 == 0) ? new double[]{60, 120, 90} : new double[]{0, 0, 0};
    static final Scene NIGHT_E = (x, y) -> ((x / 64 + y / 64) % 4 == 0) ? new double[]{40, 90, 60} : new double[]{0.5, 1, 0.8};
    static final Scene NIGHT_F = (x, y) -> ((x / 64 + y / 64) % 4 == 0) ? new double[]{40, 90, 60} : new double[]{0, 0, 0};
    static final Scene DARK = (x, y) -> new double[]{0, 0, 0};

    @Test public void framesThatDecideTheirBlackGetItWithinTwoDn() throws Exception {
        for (int block : new int[]{1, 2}) {
            final String b = "block " + block + ": ";
            // C / C2: part of the pedestal lost (true black 20 / 24), a high-ISO night: the smallest samples' floor (4 / 13 DN in the
            // first fix) is not near zero, the block means' floor is the black
            assertEquals(b + "C partial loss 20, read 5", 20f, black(frame(block, 20, 5, 0.5, 5 + block, NIGHT_C, 0, 0, 0), block, null), 2f);
            assertEquals(b + "C2 partial loss 24, read 3", 24f, black(frame(block, 24, 3, 0.3, 5 + block, NIGHT_C2, 0, 0, 0), block, null), 2f);
            // D: the pedestal kept, the bottom 64 rows padded with 1..4 - an edge strip, not a lost pedestal (the first fix: 1.0)
            assertEquals(b + "D non-zero pad rows", 64f, black(frame(block, 64, READ, SHOT, 5 + block, SKY_WALL, 64, 1, 4), block, null), 0f);
            // D2: the same rows exactly 0 (the X200 Pro)
            assertEquals(b + "D2 zero pad rows", 64f, black(frame(block, 64, READ, SHOT, 5 + block, SKY_WALL, 64, 0, 0), block, null), 0f);
            // E: the pedestal lost, a high-ISO night
            assertEquals(b + "E lost night", 0f, black(frame(block, 0, 3, 0.4, 5 + block, NIGHT_E, 0, 0, 0), block, null), 2f);
            // F / G: the pedestal kept, noisy nights: the noise quantile of the darkest block means (as before P386)
            final float f = black(frame(block, 64, 8, 0.5, 5 + block, NIGHT_F, 0, 0, 0), block, null);
            assertTrue(b + "F kept, read 8: " + f, f >= 61f && f <= 64f);
            final float g = black(frame(block, 64, 20, 0.5, 5 + block, DARK, 0, 0, 0), block, null);
            assertTrue(b + "G kept, read 20: " + g, g >= 55f && g <= 64f);
            // H: a 64 -> 60 drift in daylight - no dark area to show it, the declared black stays
            assertEquals(b + "H drift 60", 64f, black(frame(block, 60, READ, SHOT, 5 + block, SKY_WALL, 0, 0, 0), block, null), 0f);
        }
    }

    @Test public void edgeStripsOfSmallValuesAreNeitherProofNorFloor() throws Exception {
        for (int block : new int[]{1, 2}) {
            // the pedestal kept, a noisy night, the bottom 100 rows 1..4 (a strip of 1.6 block rows: the straddling row too)
            final float night = black(frame(2048, 1536, block, 64, 8, 0.5, 31, NIGHT_F, 100, 0, 1, 4), 2048, 1536, block, null);
            assertTrue("block " + block + " night with a non-zero strip: " + night, night >= 61f);
            // the left 64 columns 2..5
            assertEquals(64f, black(frame(1024, 768, block, 64, READ, SHOT, 32, SKY_WALL, 0, 64, 2, 5), 1024, 768, block, null), 0f);
            // a dark part of the scene at an edge is no strip: the pedestal lost, the bottom 64 rows deep shade below a sunlit wall
            // (the block row inward is lit below half the black): the band still proves the loss
            final Scene foreground = (x, y) -> y >= 704 ? new double[]{0.5, 1, 0.8} : new double[]{19, 45, 31};
            assertEquals("block " + block + " a dark foreground band", 0f,
                    black(frame(1024, 768, block, 0, READ, SHOT, 33, foreground, 0, 0, 0, 0), 1024, 768, block, null), 2f);
        }
    }

    @Test public void aFullFramePartialLossKeepsTheBlockFloor() throws Exception {
        // P386 review, problem 2 at the real size (4096x3072 GRBG Quad, 12288 blocks): the noise tail of a black of 20 (read 5)
        // reaches 1 DN there - the smallest samples' floor alone would take 1 - in blocks that sit at ~22, not dark ones
        final float partial = black(frame(4096, 3072, 2, 20, 5, 0.5, 41, NIGHT_C, 0, 0, 0, 0), 4096, 3072, 2, null);
        assertEquals("partial loss, full frame", 20f, partial, 2f);
        // read noise 15 DN: the darkest block means are a noise quantile below 20, the samples' 1 is never taken
        final float noisy = black(frame(4096, 3072, 2, 20, 15, 0.5, 43, NIGHT_C, 0, 0, 0, 0), 4096, 3072, 2, null);
        assertTrue("partial loss, read 15: " + noisy, noisy > 12f && noisy <= 21f);
        final float lost = black(frame(4096, 3072, 2, 0, 3, 0.4, 42, NIGHT_E, 0, 0, 0, 0), 4096, 3072, 2, null);
        assertEquals("lost, full frame", 0f, lost, 2f);
    }

    /** The daylight with deep shade of ParametersBlackFloorTest (the owner's shot scaled 1/4): it proves the loss by itself. */
    static ByteBuffer daylight(int block, int pedestal, long seed) {
        final int w = 1024, h = 768, cell = 2 * block;
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        Random r = new Random(seed);
        final double[][] gap = new double[w / cell][h / cell];
        for (int cy = 0; cy < h / cell; cy++) for (int cx = 0; cx < w / cell; cx++) gap[cx][cy] = r.nextDouble();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
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

    @Test public void framesWithoutDeepShadeTakeTheirStreamsFloor() throws Exception {
        for (int block : new int[]{1, 2}) {
            final String stream = stream(), other = stream(), b = "block " + block + ": ";
            final ByteBuffer sky = frame(block, 0, READ, SHOT, 50 + block, SKY_WALL, 0, 0, 0);
            final ByteBuffer facade = frame(block, 0, READ, SHOT, 52 + block, FACADE, 0, 0, 0);
            final ByteBuffer shade = frame(block, 0, READ, SHOT, 54 + block, OPEN_SHADE, 0, 0, 0);
            // alone none of them can tell: their darkest blocks hold light (the first fix: 18.75 / 39.6 / 7.0)
            assertTrue(b + "A alone is an upper bound", black(sky, block, stream) > 10f);
            assertTrue(b + "A2 alone is an upper bound", black(facade, block, stream) > 30f);
            assertTrue(b + "B alone is an upper bound", black(shade, block, stream) > 5f);
            // a frame of the stream with deep shade proves the loss: its floor is remembered for the stream
            Parameters proof = parameters();
            proof.refineBlackLevel(daylight(block, 0, 60 + block), 1024, 768, block, stream);
            assertTrue(b + "the proof " + proof.blackLevel[0], proof.blackLevel[0] < 3f);
            assertTrue(b + "remembered", PedestalMemory.floor(stream, 1023f) < 3f);
            // now the frames without deep shade of that stream take it; another stream's do not
            assertEquals(b + "A with the stream's floor", 0f, black(sky, block, stream), 2f);
            assertEquals(b + "A2 with the stream's floor", 0f, black(facade, block, stream), 2f);
            assertEquals(b + "B with the stream's floor", 0f, black(shade, block, stream), 2f);
            assertTrue(b + "another stream", black(sky, block, other) > 10f);
            Parameters remembered = parameters();
            remembered.refineBlackLevel(sky, 1024, 768, block, stream);
            assertTrue(remembered.levelSources(), remembered.levelSources().contains("the stream's RAW without its pedestal"));
            // the pedestal comes back (P114): a frame whose darkest block means sit near the declared black keeps 64 and clears it
            assertEquals(b + "the pedestal back", 64f, black(daylight(block, 64, 70 + block), block, stream), 0f);
            assertTrue(b + "cleared", Float.isNaN(PedestalMemory.floor(stream, 1023f)));
            assertTrue(b + "A after the pedestal came back", black(sky, block, stream) > 10f);
        }
    }

    @Test public void aFrameWithItsPedestalNeverTakesTheStreamsFloor() throws Exception {
        // a stream with a remembered floor: its frames that sit on the pedestal (daylight, a noisy night, a 64 -> 60 drift) keep it
        for (int block : new int[]{1, 2}) {
            final String stream = stream();
            PedestalMemory.remember(stream, 1f, 1023f, "test");
            assertEquals(64f, black(frame(block, 64, READ, SHOT, 80 + block, SKY_WALL, 0, 0, 0), block, stream), 0f);
            PedestalMemory.remember(stream, 1f, 1023f, "test");
            final float night = black(frame(block, 64, 8, 0.5, 82 + block, NIGHT_F, 0, 0, 0), block, stream);
            assertTrue("night " + night, night >= 61f);
            PedestalMemory.remember(stream, 1f, 1023f, "test");
            final float drift = black(frame(block, 60, 1.5, SHOT, 84 + block, NIGHT_F, 0, 0, 0), block, stream);
            assertEquals("drift", 60f, drift, 1.5f);
            PedestalMemory.remember(stream, 1f, 1023f, "test");
            assertEquals("a non-zero edge strip", 64f, black(frame(block, 64, READ, SHOT, 86 + block, SKY_WALL, 64, 1, 4), block, stream), 0f);
        }
    }
}
