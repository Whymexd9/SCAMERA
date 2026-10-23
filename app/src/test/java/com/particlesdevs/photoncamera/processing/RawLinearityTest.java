package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.junit.Test;

/**
 * P320: a DCG frame counts as linear only when its histogram proves it; a companding curve shows as a count step at one code in
 * every CFA phase, a scene step as a step in one channel only.
 */
public class RawLinearityTest {
    private static final int W = 1024, H = 768, BLACK = 1024;
    /** Raw gains of the four phases (R, Gr, Gb, B) for one scene: the channels of one object sit at different codes. */
    private static final double[] GAIN = {0.55, 1.0, 1.0, 0.7};

    /** A linear 16-bit frame: per pixel a scene level (uniform over the range, smooth density) times its phase gain. */
    private static double[] scene(long seed) {
        Random r = new Random(seed);
        double[] v = new double[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                double level = Math.pow(r.nextDouble(), 1.6);          // more shadows than highlights, no step anywhere
                v[y * W + x] = level * GAIN[(y & 1) * 2 + (x & 1)];
            }
        return v;
    }

    private static ByteBuffer frame(double[] linear, int fullScale, java.util.function.IntUnaryOperator curve) {
        ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < W * H; i++) {
            int code = (int) Math.round(linear[i] * fullScale);
            b.putShort(i * 2, (short) Math.min(65535, BLACK + curve.applyAsInt(code)));
        }
        return b;
    }

    /** Sensor-style companding of a 16x range into 4 segments: slopes 1, 1/2, 1/4, 1/8 above codes 2048, 4096, 6144 (above black). */
    private static int compand(int x) {
        if (x < 2048) return x;
        if (x < 2048 + 4096) return 2048 + (x - 2048) / 2;
        if (x < 2048 + 4096 + 8192) return 4096 + (x - 6144) / 4;
        return 6144 + (x - 14336) / 8;
    }

    @Test public void aLinearFrameIsProvenLinear() {
        ByteBuffer raw = frame(scene(1), 14000, x -> x);
        RawLinearity.Result r = RawLinearity.analyze(raw, W, H, W * 2, new float[]{BLACK, BLACK, BLACK, BLACK}, BLACK + 14000);
        assertEquals(r.describe(), RawLinearity.Verdict.LINEAR, r.verdict);
        assertEquals(0, r.knees.length);
    }

    @Test public void aCompandedFrameShowsItsKneesAndExpandsBack() {
        double[] linear = scene(2);
        // 16x the companded range: linear codes up to 2048 + 4096 + 8192 + 8 * 2048 = 30720 land at most at 8192
        ByteBuffer raw = frame(linear, 30000, RawLinearityTest::compand);
        float[] black = {BLACK, BLACK, BLACK, BLACK};
        RawLinearity.Result r = RawLinearity.analyze(raw, W, H, W * 2, black, BLACK + compand(30000));
        assertEquals(r.describe(), RawLinearity.Verdict.COMPANDED, r.verdict);
        assertEquals(r.describe(), 3, r.knees.length);
        int[] expect = {BLACK + 2048, BLACK + 4096, BLACK + 6144};
        for (int i = 0; i < 3; i++) {
            assertEquals(r.describe(), expect[i], r.knees[i], 24);
            assertEquals(r.describe(), 2.0, r.ratios[i], 1e-9);
        }
        int[] lut = RawLinearity.expandLut(r.knees, r.ratios, BLACK + compand(30000));
        // the expanded codes are linear again: a companded code maps back to its linear code (one scale for all)
        double scale = (double) (lut[BLACK + compand(30000)] - lut[BLACK]) / 30000;
        for (int x : new int[]{500, 3000, 9000, 20000, 29000}) {
            double back = (lut[BLACK + compand(x)] - lut[BLACK]) / scale;
            assertEquals("linear code " + x, x, back, 0.01 * x + 16);
        }
        RawLinearity.expand(raw, lut);
        RawLinearity.Result after = RawLinearity.analyze(raw, W, H, W * 2,
                new float[]{lut[BLACK], lut[BLACK], lut[BLACK], lut[BLACK]}, lut[BLACK + compand(30000)]);
        assertTrue(after.describe(), after.verdict != RawLinearity.Verdict.COMPANDED);
    }

    /** A step of the scene in one channel (a large red surface starting at one level) is not a knee. */
    @Test public void aSceneStepInOneChannelIsNotAKnee() {
        double[] linear = scene(3);
        Random r = new Random(4);
        for (int y = 0; y < H; y += 2)
            for (int x = 0; x < W; x += 2)
                if (r.nextDouble() < 0.5) linear[y * W + x] = 0.35 + 0.3 * r.nextDouble();   // red phase only
        ByteBuffer raw = frame(linear, 14000, x -> x);
        RawLinearity.Result res = RawLinearity.analyze(raw, W, H, W * 2, new float[]{BLACK, BLACK, BLACK, BLACK}, BLACK + 14000);
        assertEquals(res.describe(), RawLinearity.Verdict.LINEAR, res.verdict);
    }

    /**
     * A knee where only green has samples (red and blue sit far lower): it cannot be seen, so the frame is not proven linear -
     * an untested code is no proof (review of P320).
     */
    @Test public void aKneeOnlyGreenReachesIsNoProof() {
        double[] linear = scene(5);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                final int phase = (y & 1) * 2 + (x & 1);
                if (phase == 0 || phase == 3) linear[y * W + x] *= 0.25 / GAIN[phase];   // red and blue: a quarter of green
            }
        ByteBuffer raw = frame(linear, 16000, c -> c < 6000 ? c : 6000 + (c - 6000) / 2);
        RawLinearity.Result r = RawLinearity.analyze(raw, W, H, W * 2, new float[]{BLACK, BLACK, BLACK, BLACK}, BLACK + 11000);
        assertTrue(r.describe(), r.verdict != RawLinearity.Verdict.LINEAR);
    }

    /** A step red and green share while blue has no samples there: two colours alike decide nothing, it is no knee. */
    @Test public void aStepOfTwoColoursIsNotAKnee() {
        double[] linear = scene(6);
        Random r = new Random(8);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                final int phase = (y & 1) * 2 + (x & 1);
                if (phase == 3) { linear[y * W + x] *= 0.2 / GAIN[3]; continue; }               // blue far below
                linear[y * W + x] = linear[y * W + x] / GAIN[phase];                           // red = green
                if (r.nextDouble() < 0.4) linear[y * W + x] = 0.5 + 0.45 * r.nextDouble();     // the same step in red and green
            }
        ByteBuffer raw = frame(linear, 14000, c -> c);
        RawLinearity.Result res = RawLinearity.analyze(raw, W, H, W * 2, new float[]{BLACK, BLACK, BLACK, BLACK}, BLACK + 14000);
        assertTrue(res.describe(), res.verdict != RawLinearity.Verdict.COMPANDED);
    }

    @Test public void aFlatFrameHasNoVerdict() {
        ByteBuffer b = ByteBuffer.allocateDirect(64 * 64 * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 64 * 64; i++) b.putShort(i * 2, (short) (BLACK + 10));
        RawLinearity.Result r = RawLinearity.analyze(b, 64, 64, 128, new float[]{BLACK}, BLACK + 14000);
        assertEquals(RawLinearity.Verdict.NO_DATA, r.verdict);
    }

    @Test public void slopesSnapToPowersOfTwo() {
        assertEquals(2.0, RawLinearity.snap(1.86), 0);
        assertEquals(4.0, RawLinearity.snap(4.3), 0);
        assertEquals(3.0, RawLinearity.snap(3.0), 0);
        assertArrayEquals(new int[]{0, 1, 2}, java.util.Arrays.copyOf(RawLinearity.expandLut(new int[0], new double[0], 65535), 3));
    }
}
