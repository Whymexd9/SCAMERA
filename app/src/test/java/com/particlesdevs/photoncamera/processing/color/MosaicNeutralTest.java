package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/** P121: the neutral of a Quad / Tetra frame measured on the frame, put on the camera's curve from plain-Bayer neutrals. */
public class MosaicNeutralTest {
    /** A BGGR mosaic of colour blocks {@code block} of a grey-ish scene lit with neutral (r, 1, b), ±4 % site gains and noise. */
    private static ByteBuffer mosaic(int w, int h, int block, float r, float b, long seed) {
        final ByteBuffer buf = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        final Random rnd = new Random(seed);
        final float black = 64, range = 1023 - 64;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            final int px = (x / block) & 1, py = (y / block) & 1;
            // BGGR: blue at (0,0), red at (1,1)
            final float colour = px == 0 && py == 0 ? b : px == 1 && py == 1 ? r : 1f;
            final float scene = 0.15f + 0.3f * (float) Math.abs(Math.sin(x * 0.01) * Math.cos(y * 0.013));
            final float site = 1f + 0.04f * (float) Math.sin(x * 7 + y * 3);
            final float v = black + range * scene * colour * site + (float) rnd.nextGaussian() * 2f;
            buf.putShort((y * w + x) * 2, (short) Math.max(0, Math.min(1023, Math.round(v))));
        }
        return buf;
    }

    @Test public void quadAndTetraNeutralIsMeasured() {
        final float[] black = {64, 64, 64, 64};
        for (int block : new int[]{2, 4}) {
            final float[] m = MosaicNeutral.measure(mosaic(1024, 768, block, 0.47f, 0.69f, block), 1024, 768, 2048, 3, black, 1023, block);
            assertNotNull(m);
            assertEquals("R/G block " + block, Math.log(0.47), Math.log(m[0]), 0.03);
            assertEquals("B/G block " + block, Math.log(0.69), Math.log(m[1]), 0.03);
        }
    }

    @Test public void clippedOrTinyFramesGiveNothing() {
        final float[] black = {64, 64, 64, 64};
        final ByteBuffer white = ByteBuffer.allocate(256 * 256 * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 256 * 256; i++) white.putShort(i * 2, (short) 1023);
        assertNull(MosaicNeutral.measure(white, 256, 256, 512, 3, black, 1023, 2));
        assertNull(MosaicNeutral.measure(white, 4, 4, 8, 3, black, 1023, 2));
    }

    @Test public void neutralGoesOnTheCameraCurve() {
        // the camera's plain-Bayer neutrals from tungsten to daylight
        final ArrayList<float[]> curve = new ArrayList<>();
        for (float[] p : new float[][]{{0.80f, 0.35f}, {0.60f, 0.50f}, {0.47f, 0.69f}, {0.40f, 0.85f}})
            curve.add(new float[]{(float) Math.log(p[0]), (float) Math.log(p[1])});
        // a measurement a little off the curve (a coloured scene) lands on its nearest point
        final float[] q = MosaicNeutral.project(curve, (float) Math.log(0.50), (float) Math.log(0.66));
        assertTrue(q[2] < 0.1f);
        assertEquals(0.47, Math.exp(q[0]), 0.04);
        assertEquals(0.69, Math.exp(q[1]), 0.05);
        // beyond the ends: the end point
        final float[] end = MosaicNeutral.project(curve, (float) Math.log(1.5), (float) Math.log(0.2));
        assertEquals(0.80, Math.exp(end[0]), 1e-3);
    }

    // -------------------------------------------------------------------------------------------------------------------------
    // P212f (W4 = M10): the robust statistic (MosaicNeutral.measure2), the M10 VERIFY cases

    /** What a synthetic scene puts at a site: its level (of white - black) before the colour, and its colour (r, b). */
    private interface Scene {
        /** {level, r, b} at site (x, y) of a w x h frame. */
        float[] at(int x, int y, int w, int h);
    }

    private static final float BLACK = 64, WHITE = 1023, RANGE = WHITE - BLACK;
    private static final float[] BLACKS = {BLACK, BLACK, BLACK, BLACK};

    /**
     * A BGGR mosaic of colour blocks {@code block}: {@code scene} times the colour of each phase, multiplied by {@code shade} (R,
     * G, B gains of the optics at the site, null: none), Gaussian noise of {@code noise} DN, clipped at white.
     */
    private static ByteBuffer frame(int w, int h, int block, Scene scene, Scene shade, float noise, long seed) {
        final ByteBuffer buf = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        final Random rnd = new Random(seed);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            final int px = (x / block) & 1, py = (y / block) & 1;
            final float[] s = scene.at(x, y, w, h);
            final boolean blue = px == 0 && py == 0, red = px == 1 && py == 1;
            float colour = blue ? s[2] : red ? s[1] : 1f;
            if (shade != null) {
                final float[] o = shade.at(x, y, w, h);
                colour *= blue ? o[2] : red ? o[1] : o[0];
            }
            final float v = BLACK + RANGE * s[0] * colour + (float) rnd.nextGaussian() * noise;
            buf.putShort((y * w + x) * 2, (short) Math.max(0, Math.min(1023, Math.round(v))));
        }
        return buf;
    }

    private static final float R0 = 0.47f, B0 = 0.69f;

    /** A textured grey scene (levels 0.15-0.45) under neutral (R0, B0). */
    private static final Scene GREY = (x, y, w, h) -> new float[]{0.15f + 0.3f * (float) Math.abs(Math.sin(x * 0.01) * Math.cos(y * 0.013)), R0, B0};

    private static MosaicNeutral.Setup noise(double s, double o) {
        final MosaicNeutral.Setup setup = new MosaicNeutral.Setup();
        setup.noiseS = s;
        setup.noiseO = o;
        return setup;
    }

    private static void near(String what, MosaicNeutral.Stat m, double r, double b, double tolerance) {
        assertNotNull(what, m);
        assertEquals(what + " R/G " + m, Math.log(r), Math.log(m.r), tolerance);
        assertEquals(what + " B/G " + m, Math.log(b), Math.log(m.b), tolerance);
    }

    @Test public void theRobustStatisticMeasuresQuadAndTetra() {
        for (int block : new int[]{2, 4}) {
            final MosaicNeutral.Stat m = MosaicNeutral.measure2(frame(1024, 768, block, GREY, null, 2f, block), 1024, 768, 2048, 3, BLACKS, WHITE,
                    block, noise(4e-5, 1e-6));
            near("block " + block, m, R0, B0, 0.01);
            assertTrue(m.cells > 1000);
            assertTrue("a confident statistic: " + m, m.confidence() > 0.5f);
        }
    }

    /**
     * M10 VERIFY: G sites clipped inside 5 % of the Quad blocks (a bright texture: one G site at white, its block mean at 0.85,
     * so the P121 block-mean test keeps it) leave the estimate within 1 %; P121's p = 4 statistic is pulled warm by them.
     */
    @Test public void clippedGreenSitesDoNotMoveTheEstimate() {
        final int w = 1024, h = 768, block = 2;
        final ByteBuffer clean = frame(w, h, block, GREY, null, 2f, 7);
        final ByteBuffer hit = clean.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer copy = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        copy.put(hit);
        final Random rnd = new Random(3);
        int clipped = 0;
        final int cells = (w / 4) * (h / 4);
        for (int c = 0; c < cells; c++) {
            if (rnd.nextFloat() >= 0.05f) continue;
            final int cx = c % (w / 4), cy = c / (w / 4);
            // BGGR blocks of 2x2 sites: G blocks at (1, 0) and (0, 1) of the cell; three G sites at 0.8, one at white
            for (int[] g : new int[][]{{1, 0}, {0, 1}}) {
                final int x0 = cx * 4 + g[0] * 2, y0 = cy * 4 + g[1] * 2;
                for (int i = 0; i < 4; i++) {
                    final int x = x0 + (i & 1), y = y0 + (i >> 1);
                    copy.putShort((y * w + x) * 2, (short) (i == 0 ? WHITE : Math.round(BLACK + 0.8f * RANGE)));
                }
            }
            // its R and B at the level the G would have (0.95 of the range)
            copy.putShort(((cy * 4) * w + cx * 4) * 2, (short) Math.round(BLACK + 0.95f * RANGE * B0));
            copy.putShort(((cy * 4) * w + cx * 4 + 1) * 2, (short) Math.round(BLACK + 0.95f * RANGE * B0));
            copy.putShort(((cy * 4 + 1) * w + cx * 4) * 2, (short) Math.round(BLACK + 0.95f * RANGE * B0));
            copy.putShort(((cy * 4 + 1) * w + cx * 4 + 1) * 2, (short) Math.round(BLACK + 0.95f * RANGE * B0));
            for (int i = 0; i < 4; i++)
                copy.putShort(((cy * 4 + 2 + (i >> 1)) * w + cx * 4 + 2 + (i & 1)) * 2, (short) Math.round(BLACK + 0.95f * RANGE * R0));
            clipped++;
        }
        assertTrue(clipped > cells / 25);
        final MosaicNeutral.Stat before = MosaicNeutral.measure2(clean, w, h, 2 * w, 3, BLACKS, WHITE, block, noise(4e-5, 1e-6));
        final MosaicNeutral.Stat after = MosaicNeutral.measure2(copy, w, h, 2 * w, 3, BLACKS, WHITE, block, noise(4e-5, 1e-6));
        near("with 5 % clipped G sites", after, before.r, before.b, 0.01);
        assertTrue("the clipped cells are counted: " + after, after.clipped > 0.04f);
        final float[] old = MosaicNeutral.measure(copy, w, h, 2 * w, 3, BLACKS, WHITE, block);
        assertTrue("P121's block-mean test keeps them (warm): " + Arrays.toString(old), Math.log(old[0] / R0) > 0.02);
    }

    /** M10 VERIFY: a grey ground under a bright blue sky over 30 % of the frame - the grey's neutral within 2 % (p = 4 fails). */
    @Test public void aBlueSkyDoesNotPullTheGrey() {
        final Scene sky = (x, y, w, h) -> y < 0.3f * h ? new float[]{0.7f, R0 * 0.7f, B0 * 1.6f} : GREY.at(x, y, w, h);
        final ByteBuffer f = frame(1024, 768, 2, sky, null, 2f, 11);
        near("grey under the sky", MosaicNeutral.measure2(f, 1024, 768, 2048, 3, BLACKS, WHITE, 2, noise(4e-5, 1e-6)), R0, B0, 0.02);
        final float[] old = MosaicNeutral.measure(f, 1024, 768, 2048, 3, BLACKS, WHITE, 2);
        assertTrue("P121's p = 4 goes blue: " + Arrays.toString(old), Math.log(old[1] / B0) > 0.1);
    }

    /** M10 VERIFY: a night frame at a median of 2 DN with 2 DN read noise a site: within 3 % (the dark cut left the lamps only). */
    @Test public void aDarkFrameIsMeasuredWithoutTheDarkCut() {
        final Scene dark = (x, y, w, h) -> new float[]{(2f + 1.5f * (float) Math.sin(x * 0.02) * (float) Math.cos(y * 0.017)) / RANGE, R0, B0};
        // the noise of a 2x2 block mean: 1 DN (O), a little shot noise (S)
        final double o = Math.pow(1.0 / RANGE, 2), s = 2e-5;
        for (int seed = 0; seed < 3; seed++) {
            final MosaicNeutral.Stat m = MosaicNeutral.measure2(frame(1024, 768, 2, dark, null, 2f, 100 + seed), 1024, 768, 2048, 3, BLACKS, WHITE, 2,
                    noise(s, o));
            near("night seed " + seed, m, R0, B0, 0.03);
            assertTrue("a night median: " + m, m.median < 0.005f);
        }
        assertNull("P121's dark cut gives nothing", MosaicNeutral.measure(frame(1024, 768, 2, dark, null, 2f, 100), 1024, 768, 2048, 3, BLACKS, WHITE, 2));
    }

    /** M10 VERIFY: vignetting (corners 1/3) and 6 % colour shading of R and B are undone by the lens shading gains. */
    @Test public void theLensShadingGainsUndoVignettingAndColourShading() {
        final Scene optics = (x, y, w, h) -> {
            final float dx = (x + 0.5f) / w - 0.5f, dy = (y + 0.5f) / h - 0.5f, q = (dx * dx + dy * dy) / 0.5f; // 1 at the corners
            final float v = 1f / (1f + 2f * q);
            return new float[]{v, v * (1f - 0.06f * q), v * (1f + 0.06f * q)};
        };
        final ByteBuffer f = frame(1024, 768, 2, GREY, optics, 2f, 21);
        // the map (17 x 13, copyGainFactors order R, Ge, Go, B): the inverse of the optics at the grid points
        final int cols = 17, rows = 13;
        final float[] gains = new float[4 * cols * rows];
        for (int j = 0; j < rows; j++) for (int i = 0; i < cols; i++) {
            final float u = i / (float) (cols - 1), v = j / (float) (rows - 1);
            final float[] o = optics.at(Math.round(u * 1024 - 0.5f), Math.round(v * 768 - 0.5f), 1024, 768);
            final int k = 4 * (j * cols + i);
            gains[k] = 1f / o[1];
            gains[k + 1] = 1f / o[0];
            gains[k + 2] = 1f / o[0];
            gains[k + 3] = 1f / o[2];
        }
        final MosaicNeutral.Setup shaded = noise(4e-5, 1e-6);
        shaded.gains = gains;
        shaded.gainCols = cols;
        shaded.gainRows = rows;
        final MosaicNeutral.Stat m = MosaicNeutral.measure2(f, 1024, 768, 2048, 3, BLACKS, WHITE, 2, shaded);
        near("with the map", m, R0, B0, 0.01);
        assertTrue(m.shaded);
        final MosaicNeutral.Stat raw = MosaicNeutral.measure2(f, 1024, 768, 2048, 3, BLACKS, WHITE, 2, noise(4e-5, 1e-6));
        assertTrue("without it the colour shading shows: " + raw,
                Math.abs(Math.log(raw.r / R0)) > 0.01 || Math.abs(Math.log(raw.b / B0)) > 0.01);
    }

    /** M10 VERIFY: the split sigma follows the injected noise (a flat field: 8x the noise, about 8x the sigma). */
    @Test public void theSplitSigmaTracksTheNoise() {
        final Scene flat = (x, y, w, h) -> new float[]{0.05f, R0, B0};
        double low = 0, high = 0;
        for (int seed = 0; seed < 4; seed++) {
            low += MosaicNeutral.measure2(frame(512, 384, 2, flat, null, 1f, 200 + seed), 512, 384, 1024, 3, BLACKS, WHITE, 2, noise(1e-6, 1e-6)).sigma();
            high += MosaicNeutral.measure2(frame(512, 384, 2, flat, null, 8f, 300 + seed), 512, 384, 1024, 3, BLACKS, WHITE, 2, noise(1e-6, 1e-6)).sigma();
        }
        final double ratio = high / low;
        assertTrue("sigma ratio " + ratio + " (" + low / 4 + " -> " + high / 4 + ")", ratio > 4 && ratio < 16);
    }

    /** P212f: only the output crop counts (a red border outside it changes nothing); the crop of a zoomed 4:3 photo. */
    @Test public void onlyTheOutputCropCounts() {
        final Scene border = (x, y, w, h) -> x < w / 8 || x >= 7 * w / 8 ? new float[]{0.4f, 2.5f, 0.3f} : GREY.at(x, y, w, h);
        final ByteBuffer f = frame(1024, 768, 2, border, null, 2f, 31);
        final MosaicNeutral.Setup crop = noise(4e-5, 1e-6).crop(1024, 768, 1.5f, false);
        assertEquals(1 / 6f, crop.left, 1e-4f);
        assertEquals(5 / 6f, crop.right, 1e-4f);
        near("inside the crop", MosaicNeutral.measure2(f, 1024, 768, 2048, 3, BLACKS, WHITE, 2, crop), R0, B0, 0.01);
        // ISZ 4:3 of a 16:9 frame: the centre 3/4 of the width; a 4:3 frame stays whole
        final MosaicNeutral.Setup wide = new MosaicNeutral.Setup().crop(4000, 2250, 1f, true);
        assertEquals(0.125f, wide.left, 1e-4f);
        assertEquals(0.875f, wide.right, 1e-4f);
        assertEquals(0f, wide.top, 1e-4f);
        final MosaicNeutral.Setup whole = new MosaicNeutral.Setup().crop(8192, 6144, 1f, true);
        assertEquals(0f, whole.left, 1e-4f);
        assertEquals(1f, whole.bottom, 1e-4f);
    }

    /** P212f: several N frames - the median of their logs, the sigma not below their spread. */
    @Test public void severalFramesCombine() {
        final ArrayList<MosaicNeutral.Stat> frames = new ArrayList<>();
        for (int seed = 0; seed < 3; seed++)
            frames.add(MosaicNeutral.measure2(frame(512, 384, 2, GREY, null, 4f, 400 + seed), 512, 384, 1024, 3, BLACKS, WHITE, 2, noise(4e-5, 1e-6)));
        final MosaicNeutral.Stat c = MosaicNeutral.combine(frames);
        near("3 frames", c, R0, B0, 0.01);
        assertEquals(3, c.frames);
        final double[] lr = new double[3];
        for (int i = 0; i < 3; i++) lr[i] = Math.log(frames.get(i).r);
        Arrays.sort(lr);
        assertEquals(lr[1], Math.log(c.r), 1e-6);
        assertNull(MosaicNeutral.combine(new ArrayList<>()));
        assertTrue(MosaicNeutral.combine(java.util.Collections.singletonList(frames.get(0))) == frames.get(0));
    }

    /** P212f: an all-white or tiny frame gives no statistic. */
    @Test public void clippedOrTinyFramesGiveNoStatistic() {
        final ByteBuffer white = ByteBuffer.allocate(256 * 256 * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 256 * 256; i++) white.putShort(i * 2, (short) 1023);
        assertNull(MosaicNeutral.measure2(white, 256, 256, 512, 3, BLACKS, WHITE, 2, null));
        assertNull(MosaicNeutral.measure2(white, 4, 4, 8, 3, BLACKS, WHITE, 2, null));
    }
}
