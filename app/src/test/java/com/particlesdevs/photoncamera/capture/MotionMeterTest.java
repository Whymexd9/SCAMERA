package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ShortBuffer;
import java.util.Random;

public class MotionMeterTest {
    private static final int W = 2048, H = 1536, BLACK = 64;
    private static final long FRAME = 33_333_333L;

    /** A smooth, non-repeating scene (no period within the +-64 px search). */
    private static double scene(double x, double y) {
        return 300 + 120 * Math.sin(x / 37.0) * Math.cos(y / 53.0) + 90 * Math.sin((x + 2 * y) / 71.0)
                + 70 * Math.cos((3 * x - y) / 113.0) + 50 * Math.sin(x * y / 90000.0);
    }

    /** A subject with its own texture. */
    private static double subject(double x, double y) {
        return 500 + 200 * Math.sin(x / 23.0) * Math.sin(y / 29.0) + 100 * Math.cos((x - y) / 41.0);
    }

    /**
     * A RAW frame: the scene shifted by ({@code panX}, 0), a 640 x 640 subject at (700 + {@code subjectX}, 500), times
     * {@code gain}, plus noise.
     */
    private static ShortBuffer frame(int panX, int subjectX, double gain, long seed) {
        return frame(panX, subjectX, gain, seed, 0);
    }

    /** ... with rows modulated by 100 Hz lamps ({@code bands}: the modulation, a band every 400 rows). */
    private static ShortBuffer frame(int panX, int subjectX, double gain, long seed, double bands) {
        Random noise = new Random(seed);
        short[] a = new short[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                final int sx = x - 700 - subjectX, sy = y - 500;
                double v = sx >= 0 && sx < 640 && sy >= 0 && sy < 640 ? subject(sx, sy) : scene(x - panX, y);
                v = BLACK + gain * v * (1 + bands * Math.sin(2 * Math.PI * y / 400.0)) + noise.nextGaussian() * 4;
                a[y * W + x] = (short) Math.max(0, Math.min(1023, Math.round(v)));
            }
        }
        return ShortBuffer.wrap(a);
    }

    private static MotionMeter.Sample measure(ShortBuffer a, ShortBuffer b) {
        MotionMeter.Thumb ta = MotionMeter.thumb(a, W, H, W, BLACK, 0), tb = MotionMeter.thumb(b, W, H, W, BLACK, FRAME);
        assertNotNull(ta);
        assertNotNull(tb);
        return MotionMeter.compare(ta, tb, FRAME, 0);
    }

    @Test
    public void aStillSceneDoesNotMove() {
        MotionMeter.Sample s = measure(frame(0, 0, 1, 1), frame(0, 0, 1, 2));
        assertNotNull(s);
        assertTrue(s.textured > 20);
        assertEquals(0, s.subjectPxPerS, 1);
        assertEquals(0, s.cameraPxPerS, 1);
    }

    @Test
    public void exposureChangeIsNoMotion() {
        // the shutter ceiling takes over: the same scene twice as bright (AE before, ceiling after) is not a moving scene
        MotionMeter.Sample s = measure(frame(0, 0, 0.5, 1), frame(0, 0, 1, 2));
        assertEquals(0, s.pxPerS(), 1);
    }

    @Test
    public void aMovingSubjectAgainstAStillBackground() {
        // 32 RAW px in one frame at 30 fps: 960 px/s, the camera still
        MotionMeter.Sample s = measure(frame(0, 0, 1, 1), frame(0, 32, 1, 2));
        assertEquals(960, s.subjectPxPerS, 960 * 0.25);
        assertEquals(0, s.cameraPxPerS, 1);
        assertTrue(s.moved >= 2);
    }

    @Test
    public void aPanMovesTheCameraNotTheSubject() {
        // the whole frame 16 px (a pan, or hand shake the OIS left): 480 px/s of the camera
        MotionMeter.Sample s = measure(frame(0, 0, 1, 1), frame(16, 16, 1, 2));
        assertEquals(480, s.cameraPxPerS, 480 * 0.25);
        assertEquals(0, s.subjectPxPerS, 480 * 0.25);
        assertEquals(480, s.pxPerS(), 480 * 0.25);
    }

    private static float[] rows(ShortBuffer raw) {
        float[] p = MotionMeter.rowProfile(MotionMeter.thumb(raw, W, H, W, BLACK, 0));
        assertNotNull(p);
        assertEquals(MotionMeter.PROFILE_BINS, p.length);
        return p;
    }

    @Test
    public void motionBelowOneThumbnailPixelIsMeasured() {
        MotionMeter.Sample s = measure(frame(0, 0, 1, 1), frame(4, 4, 1, 2));
        assertNotNull(s);
        assertEquals(120, s.cameraPxPerS, 40);
    }

    @Test
    public void quadAndTetraThumbnailsSampleEveryColourPhase() {
        for (int block : new int[]{2, 4}) {
            short[] raw = new short[W * H];
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
                raw[y * W + x] = (short) (BLACK + 100 + 100 * (((y / block) & 1) * 2 + ((x / block) & 1)));
            MotionMeter.Thumb t = MotionMeter.thumb(ShortBuffer.wrap(raw), W, H, W, BLACK, 0, block);
            assertNotNull(t);
            for (float value : t.v) assertEquals(1, value, 1e-6);
        }
    }

    @Test
    public void oneReliableSubjectTileInsideTheFocusRegionIsNotDiscarded() {
        final int side = 64;
        float[] a = new float[side * side], b = new float[side * side];
        java.util.Arrays.fill(a, 1f);
        java.util.Arrays.fill(b, 1f);
        for (int y = 30; y < 38; y++) for (int x = 30; x < 38; x++) {
            float v = 1f + (float) (0.4 * Math.sin(x * 1.1) * Math.cos(y * 0.7));
            a[y * side + x] = v;
            b[y * side + x + 1] = v;
        }
        MotionMeter.Thumb ta = new MotionMeter.Thumb(a, side, side, 0);
        MotionMeter.Thumb tb = new MotionMeter.Thumb(b, side, side, FRAME);
        MotionMeter.Sample s = MotionMeter.compare(ta, tb, FRAME, 123,
                new MotionMeter.Region(28f / side, 28f / side, 40f / side, 40f / side));
        assertNotNull(s);
        assertEquals(480, s.subjectPxPerS, 60);
        assertEquals(123, s.atNs);
    }

    @Test
    public void rowProfilesShowLampBandsNotASubject() {
        final float[] clean = rows(frame(0, 0, 1, 1));
        // a short frame under flickering lamps: 15 % bands
        assertTrue(com.particlesdevs.photoncamera.manual.MotionShutter.Controller.bandAmplitude(rows(frame(0, 0, 1, 2, 0.15)), clean) > 0.1);
        // the same scene a moment later, the subject moved and the exposure changed: no bands
        assertTrue(com.particlesdevs.photoncamera.manual.MotionShutter.Controller.bandAmplitude(rows(frame(0, 32, 1.3, 2)), clean) < 0.04);
    }

    @Test
    public void unusableFramesGiveNothing() {
        assertNull(MotionMeter.thumb(ShortBuffer.allocate(100), 100, 1, 100, BLACK, 0));
        assertNull(MotionMeter.thumb(ShortBuffer.allocate(W * 10), W, H, W, BLACK, 0)); // buffer too short
        short[] black = new short[W * H];
        java.util.Arrays.fill(black, (short) BLACK);
        assertNull(MotionMeter.thumb(ShortBuffer.wrap(black), W, H, W, BLACK, 0)); // nothing above black
        // a flat frame has no texture: no measurement
        short[] flat = new short[W * H];
        java.util.Arrays.fill(flat, (short) 400);
        MotionMeter.Thumb t = MotionMeter.thumb(ShortBuffer.wrap(flat), W, H, W, BLACK, 0);
        assertNull(MotionMeter.compare(t, t, FRAME, 0));
    }
}
