package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P378 (owner, 2026-10-28: the sky like the reference app's, «при этом чтобы в остальных сценариях ничего не сломалось»; «наш
 * тонмапинг не должен затемнять небо в контровых сценах»): the sky decision of the WHY tone's sky term on synthetic whyLow fixtures
 * (WhySkyMask.Cells.of, the CPU twin of why/skycells.glsl). No sky - an interior, a lamp room, a night, a white wall: no mask (the
 * photo bit for bit as before P378); a daylight sky over darker ground: a mask on the sky alone, not backlit; a sun clipped in the
 * sky and a window from a dark room: backlit (the sky's level kept). P378 review: a sun clipped without ultrashort frames over an
 * outdoor subject at 1/500 - 1/2000 s is backlit, clipped cumulus tops are not; a wire across the sky does not cut it, a sky over
 * 55 - 70 % of the frame is found; a front-lit face, a daylit white ceiling and a light surface joined below the sky stay out; the
 * viewfinder's cells (Cells.ofQuads) and the lab's frozen cells (pack / scaled) decide as the photo's.
 */
public class WhySkyMaskTest {
    static final int W = 256, H = 192;

    /** A whyLow of W x H, RGB. */
    static final class Img {
        final float[] px = new float[W * H * 3];

        void set(int x, int y, float r, float g, float b) {
            final int o = (y * W + x) * 3;
            px[o] = r;
            px[o + 1] = g;
            px[o + 2] = b;
        }

        float y(int x, int y) {
            final int o = (y * W + x) * 3;
            return 0.2126f * px[o] + 0.7152f * px[o + 1] + 0.0722f * px[o + 2];
        }
    }

    /** A smooth sky level at (x, y): soft clouds, a gentle gradient (log2 steps of ~0.02 a px). */
    static float cloud(int x, int y, float level) {
        return level * (float) (1.0 + 0.18 * Math.sin(x / 9.0) * Math.sin(y / 7.0) + 0.1 * Math.sin((x + 2 * y) / 23.0));
    }

    /** Textured ground (log-normal grain, ~0.5 stop rms per px). */
    static float grain(Random rnd, float level) {
        return level * (float) Math.exp(0.5 * rnd.nextGaussian());
    }

    /** The sky (top 35 % of the rows, pale blue, smooth) over textured ground at 0.02. */
    static Img skyOverGround(long seed) {
        final Img im = new Img();
        final Random rnd = new Random(seed);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (y < 0.35 * H) {
                    final float v = cloud(x, y, 0.45f);
                    im.set(x, y, 0.88f * v, 0.97f * v, 1.15f * v);
                } else {
                    final float v = grain(rnd, 0.02f);
                    im.set(x, y, 1.05f * v, v, 0.9f * v);
                }
            }
        }
        return im;
    }

    static WhySkyMask.Decision decide(Img im, int iso, double seconds, float aperture, int rotation, float ceiling) {
        final WhySkyMask.Cells c = WhySkyMask.Cells.of(im.px, W, H, 3, WhySkyMask.CLIP_LEVEL * ceiling);
        return WhySkyMask.decide(c, new WhySkyMask.Scene(WhySkyMask.evOffset(iso, seconds, aperture, 1f), WhySkyMask.topEdge(rotation, false), ceiling));
    }

    /** ISO 100, 1/2000 s, f/1.8: an overcast day (the sky at 0.45 of white is EV ~14.8). */
    static WhySkyMask.Decision daylight(Img im, int rotation, float ceiling) {
        return decide(im, 100, 1 / 2000.0, 1.8f, rotation, ceiling);
    }

    static float cell(WhySkyMask.Mask m, int cx, int cy, int channel) {
        return m.cells[4 * (cy * m.nx + cx) + channel];
    }

    @Test
    public void aDaylightSkyOverDarkerGroundIsTheSkyAlone() {
        final WhySkyMask.Decision d = daylight(skyOverGround(1), 0, 4f);
        assertNotNull(d.log, d.mask);
        final WhySkyMask.Mask m = d.mask;
        assertFalse("no sun, an outdoor subject: not backlit (" + d.log + ")", m.backlit);
        assertTrue("the sky ~35 % of the frame: " + m.coverage, m.coverage > 0.25f && m.coverage < 0.4f);
        assertEquals(64, m.nx);
        assertEquals(48, m.ny);
        // every cell of the ground (rows from 40 %) has no sky weight, every sky cell (rows to 30 %) a full one
        for (int cy = 0; cy < m.ny; cy++) {
            for (int cx = 0; cx < m.nx; cx++) {
                if (cy >= 0.4 * m.ny) assertEquals("ground cell " + cx + "," + cy, 0f, cell(m, cx, cy, 0), 0f);
                if (cy < 0.3 * m.ny) assertTrue("sky cell " + cx + "," + cy, cell(m, cx, cy, 0) > 0.9f);
            }
        }
        // the clear sky: inside the sky, not on its edge against the ground
        assertTrue(cell(m, 32, 4, 1) > 0.8f);
        assertEquals(0f, cell(m, 32, 16, 1), 0f);
        // the local sky level near the sky's own (log2 0.45 = -1.15)
        assertEquals(-1.15f, cell(m, 32, 4, 2), 0.25f);
    }

    @Test
    public void anInteriorHasNoSky() {
        // a room at 0.03 (textured), its lit ceiling smooth at 0.3 along the top, a window at 0.9; ISO 1600, 1/50 s, f/1.8
        final Img im = new Img();
        final Random rnd = new Random(2);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float v;
                if (y < 0.2 * H) v = cloud(x, y, 0.3f);
                else if (x > 0.55 * W && x < 0.85 * W && y > 0.3 * H && y < 0.7 * H) v = cloud(x, y, 0.9f);
                else v = grain(rnd, 0.03f);
                im.set(x, y, 1.1f * v, v, 0.8f * v);
            }
        }
        final WhySkyMask.Decision d = decide(im, 1600, 1 / 50.0, 1.8f, 0, 1.2f);
        assertNull("an interior: " + d.log, d.mask);
    }

    @Test
    public void aLampRoomHasNoSky() {
        // a lamp-lit ceiling (0.6, smooth) with clipped lamps in it, a darker room below; ISO 800, 1/30 s, f/1.8
        final Img im = new Img();
        final Random rnd = new Random(3);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float v = y < 0.4 * H ? cloud(x, y, 0.6f) : grain(rnd, 0.04f);
                if (y < 0.4 * H && (Math.hypot(x - 60, y - 30) < 6 || Math.hypot(x - 190, y - 30) < 6)) v = 4f;
                im.set(x, y, 1.15f * v, v, 0.75f * v);
            }
        }
        final WhySkyMask.Decision d = decide(im, 800, 1 / 30.0, 1.8f, 0, 4f);
        assertNull("a lamp room: " + d.log, d.mask);
    }

    @Test
    public void aNightHasNoSky() {
        // a dark sky (0.004) over darker ground with clipped street lamps; ISO 3200, 1/10 s, f/1.8
        final Img im = new Img();
        final Random rnd = new Random(4);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float v = y < 0.4 * H ? cloud(x, y, 0.004f) : grain(rnd, 0.0005f);
                if (y > 0.45 * H && y < 0.5 * H && x % 40 < 3) v = 4f;
                im.set(x, y, v, v, 1.2f * v);
            }
        }
        final WhySkyMask.Decision d = decide(im, 3200, 1 / 10.0, 1.8f, 0, 4f);
        assertNull("a night: " + d.log, d.mask);
    }

    @Test
    public void aWhiteWallHasNoSky() {
        // a white wall in daylight (smooth 0.5, a gentle gradient) over most of the frame, a darker floor below
        final Img im = new Img();
        final Random rnd = new Random(5);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                final float v = y < 0.8 * H ? 0.5f * (1f + 0.2f * x / W) : grain(rnd, 0.05f);
                im.set(x, y, v, v, v);
            }
        }
        final WhySkyMask.Decision d = daylight(im, 0, 1.2f);
        assertNull("a white wall: " + d.log, d.mask);
    }

    @Test
    public void aSunClippedInTheSkyIsBacklit() {
        // the sky fixture with the sun's disc clipped at the ceiling (4, two stops and more over the sky) and its glow
        final Img im = skyOverGround(6);
        for (int y = 0; y < 0.35 * H; y++) {
            for (int x = 0; x < W; x++) {
                final double r = Math.hypot(x - 170, y - 30);
                if (r < 8) im.set(x, y, 4f, 4f, 4f);
                else if (r < 30) {
                    final float g = (float) (0.45 + 3.0 * Math.exp(-(r - 8) / 6.0));
                    im.set(x, y, g * 1.05f, g, g * 0.9f);
                }
            }
        }
        final WhySkyMask.Decision d = daylight(im, 0, 4f);
        assertNotNull(d.log, d.mask);
        assertTrue("the sun touches the sky: backlit (" + d.log + ")", d.mask.backlit);
        // no clear sky within two cells of the core (its own landing and the guided share stay)
        final int sx = 170 / 4, sy = 30 / 4;
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++)
                assertEquals("clear sky beside the sun", 0f, cell(d.mask, sx + dx, sy + dy, 1), 0f);
        assertTrue("the clear sky far from it", cell(d.mask, 8, 4, 1) > 0.8f);
    }

    @Test
    public void aWindowFromADarkRoomIsBacklit() {
        // the owner's burst in miniature: a dark room (0.0008, textured), a window from the top edge with the sky in it (0.35);
        // ISO 100, 1/1000 s, f/1.8 - the sky EV ~13.4, the room EV ~4.6
        final Img im = new Img();
        final Random rnd = new Random(7);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (x > 0.3 * W && x < 0.7 * W && y < 0.45 * H) {
                    final float v = cloud(x, y, 0.35f);
                    im.set(x, y, 0.9f * v, 0.97f * v, 1.12f * v);
                } else {
                    final float v = grain(rnd, 0.0008f);
                    im.set(x, y, v, v, v);
                }
            }
        }
        final WhySkyMask.Decision d = decide(im, 100, 1 / 1000.0, 1.8f, 0, 1.5f);
        assertNotNull(d.log, d.mask);
        assertTrue("an indoor-dim subject under the sky: backlit (" + d.log + ")", d.mask.backlit);
        assertTrue(d.mask.subjectEv < WhySkyMask.SHADE_EV);
    }

    @Test
    public void aSunAtTheSensorsFloorIsBacklitAtAnyCeiling() {
        // the owner's sun shot in kind: 1/8850 s, f/1.8, ISO 100, no Bento - the ceiling (1.2) under a stop over the sky, but
        // anything clipped at that exposure is brighter than any cloud (EV ~18.3)
        final Img im = skyOverGround(12);
        for (int y = 0; y < 0.35 * H; y++)
            for (int x = 0; x < W; x++)
                if (Math.hypot(x - 120, y - 30) < 9) im.set(x, y, 1.2f, 1.2f, 1.2f);
        final WhySkyMask.Decision d = decide(im, 100, 1 / 8850.0, 1.8f, 0, 1.2f);
        assertNotNull(d.log, d.mask);
        assertTrue("a clipped sun at EV 18+: backlit (" + d.log + ")", d.mask.backlit);
    }

    @Test
    public void aCloudAtALowCeilingIsNoCore() {
        // an overcast sky without Bento: its brightest clouds clipped at a ceiling under a stop over the sky - not a sun
        final Img im = skyOverGround(8);
        for (int y = 0; y < 0.3 * H; y++)
            for (int x = 0; x < W; x++)
                if (Math.hypot(x - 100, y - 25) < 10) im.set(x, y, 1.0f, 1.0f, 1.0f);
        final WhySkyMask.Decision d = daylight(im, 0, 1.0f);
        assertNotNull(d.log, d.mask);
        assertFalse("a clipped cloud is not a backlit sun (" + d.log + ")", d.mask.backlit);
    }

    @Test
    public void theSkyIsFoundAtThePhotosTopEdge() {
        // the sky along the last column of whyLow: the photo's top for a shot turned 270 degrees, the bottom for one turned 90
        final Img src = skyOverGround(9), im = new Img();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                // the sky fixture's rows run along x from the right edge (y = 0 of the fixture at x = W - 1)
                final int sy = (int) ((W - 1 - x) * (H - 1) / (double) (W - 1)), sx = (int) (y * (W - 1) / (double) (H - 1));
                final int o = (sy * W + sx) * 3;
                im.set(x, y, src.px[o], src.px[o + 1], src.px[o + 2]);
            }
        }
        assertNotNull("top = the last column (270)", daylight(im, 270, 4f).mask);
        assertNull("top = column 0 (90): the ground there", daylight(im, 90, 4f).mask);
    }

    @Test
    public void theTopEdgeFollowsTheRotationPass() {
        assertEquals(WhySkyMask.TOP_ROW0, WhySkyMask.topEdge(0, false));
        assertEquals(WhySkyMask.TOP_COL0, WhySkyMask.topEdge(90, false));
        assertEquals(WhySkyMask.TOP_ROWN, WhySkyMask.topEdge(180, false));
        assertEquals(WhySkyMask.TOP_COLN, WhySkyMask.topEdge(270, false));
        assertEquals(WhySkyMask.TOP_ROWN, WhySkyMask.topEdge(0, true));
        assertEquals(WhySkyMask.TOP_ROW0, WhySkyMask.topEdge(180, true));
        assertEquals(WhySkyMask.TOP_COL0, WhySkyMask.topEdge(90, true));
        assertEquals(WhySkyMask.TOP_COLN, WhySkyMask.topEdge(-90, false));
    }

    @Test
    public void theAbsoluteExposureOfWhite() {
        // ISO 12232 saturation: EV100 of base white = log2(N^2 / t) - log2(ISO / 100) + log2(9.6)
        assertEquals(Math.log(400 * 9.6) / Math.log(2), WhySkyMask.evOffset(100, 0.01, 2f, 1f), 1e-4);
        assertEquals(WhySkyMask.evOffset(100, 0.01, 2f, 1f) - 1f, WhySkyMask.evOffset(200, 0.01, 2f, 1f), 1e-4);
        assertEquals(WhySkyMask.evOffset(100, 0.01, 2f, 1f) - 1f, WhySkyMask.evOffset(100, 0.01, 2f, 2f), 1e-4);
        assertTrue(Float.isNaN(WhySkyMask.evOffset(0, 0.01, 2f, 1f)));
        assertTrue(Float.isNaN(WhySkyMask.evOffset(100, 0, 2f, 1f)));
        assertNull("an unknown exposure: no sky", WhySkyMask.decide(WhySkyMask.Cells.of(skyOverGround(10).px, W, H, 3, 3.6f),
                new WhySkyMask.Scene(Float.NaN, WhySkyMask.TOP_ROW0, 4f)).mask);
    }

    @Test
    public void theGpuReadBackGivesTheSameCells() {
        // Cells.fromGpu (why/skycells.glsl's two RGBA read-backs) and Cells.of agree on what they share
        final Img im = skyOverGround(11);
        final WhySkyMask.Cells c = WhySkyMask.Cells.of(im.px, W, H, 3, 3.6f);
        final float[] a = new float[4 * c.nx * c.ny], b = new float[4 * c.nx * c.ny];
        for (int i = 0; i < c.nx * c.ny; i++) {
            a[4 * i] = c.r[i];
            a[4 * i + 1] = c.g[i];
            a[4 * i + 2] = c.b[i];
            a[4 * i + 3] = c.l[i];
            b[4 * i] = c.sd[i] * c.sd[i] + c.l[i] * c.l[i];
            b[4 * i + 1] = c.tex[i];
            b[4 * i + 2] = c.max[i];
            b[4 * i + 3] = c.clip[i];
        }
        final WhySkyMask.Cells g = WhySkyMask.Cells.fromGpu(W, H, a, b);
        for (int i = 0; i < c.nx * c.ny; i++) assertEquals(c.sd[i], g.sd[i], 2e-3f);
        assertEquals(daylight(im, 0, 4f).log, WhySkyMask.decide(g, new WhySkyMask.Scene(WhySkyMask.evOffset(100, 1 / 2000.0, 1.8f, 1f),
                WhySkyMask.TOP_ROW0, 4f)).log);
    }

    // ---- P378 review

    /** The sun: its disc far over the ceiling, an aureole a (r0 / r)^2 and a veil e^(-r / scale), everything clipped at the ceiling. */
    static void sun(Img im, int sx, int sy, float ceiling, float aureole, double r0, double veilScale, float veil) {
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                final double r = Math.max(Math.hypot(x - sx, y - sy), 0.5);
                final int o = (y * W + x) * 3;
                float g = (float) (aureole * Math.pow(r0 / Math.max(r, r0), 2) + veil * Math.exp(-r / veilScale));
                if (r < r0) g = 50f;
                im.set(x, y, Math.min(ceiling, im.px[o] + 1.05f * g), Math.min(ceiling, im.px[o + 1] + g), Math.min(ceiling, im.px[o + 2] + 0.92f * g));
            }
        }
    }

    /** A sun's disc clipped at the ceiling with an exponential glow (tools/check_why_sky.py's sun, clipped there too). */
    static void sunExp(Img im, int sx, int sy, float ceiling, float glow, double rCore, double scale) {
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                final double r = Math.hypot(x - sx, y - sy);
                final int o = (y * W + x) * 3;
                if (r < rCore) im.set(x, y, ceiling, ceiling, ceiling);
                else if (r < rCore + 6 * scale) {
                    final float g = (float) (glow * Math.exp(-(r - rCore) / scale));
                    im.set(x, y, Math.min(ceiling, im.px[o] + 1.05f * g), Math.min(ceiling, im.px[o + 1] + g), Math.min(ceiling, im.px[o + 2] + 0.92f * g));
                }
            }
        }
    }

    /** The sky over the top 45 % (0.45, pale blue) over outdoor ground at {@code ground} (log-normal grain). */
    static Img outdoor(long seed, float ground) {
        final Img im = new Img();
        final Random rnd = new Random(seed);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (y < 0.45 * H) {
                    final float v = cloud(x, y, 0.45f);
                    im.set(x, y, 0.88f * v, 0.97f * v, 1.15f * v);
                } else {
                    final float v = grain(rnd, ground);
                    im.set(x, y, 1.05f * v, v, 0.9f * v);
                }
            }
        }
        return im;
    }

    @Test
    public void aSunClippedWithoutUltrashortFramesOverAnOutdoorSubjectIsBacklit() {
        // P378 review: no Bento (ES refused, the motion mode) - the sun and its glow clip at the N frames' ceiling 1.2, within 2.5
        // stops of the sky and under EV 17.5 at 1/500 - 1/2000 s; the subject outdoors (0.03 - 0.06 of white: 3 - 4 stops under)
        for (double t : new double[]{1 / 500.0, 1 / 1000.0, 1 / 2000.0}) {
            for (float ground : new float[]{0.03f, 0.06f}) {
                final Img a = outdoor(6, ground);
                sunExp(a, 170, 30, 1.2f, 2.0f, 8, 6);
                final Img b = outdoor(6, ground);
                sun(b, 170, 30, 1.2f, 1.5f, 3, 10, 0.4f);
                final Img c = outdoor(6, ground);
                sun(c, 60, 12, 1.2f, 3f, 1.5, 6, 0.3f);
                for (Img im : new Img[]{a, b, c}) {
                    final WhySkyMask.Decision d = decide(im, 100, t, 1.8f, 0, 1.2f);
                    assertNotNull(d.log, d.mask);
                    assertTrue("1/" + Math.round(1 / t) + " s, ground " + ground + ": backlit (" + d.log + ")", d.mask.backlit);
                }
            }
        }
    }

    @Test
    public void clippedCumulusTopsAreNotASun() {
        // textured domes whose brightest parts clip at a low ceiling (1.2): a cliff into the cloud's body, no glow round them
        for (long seed : new long[]{11, 12, 13, 14}) {
            final Img im = outdoor(6, 0.03f);
            final Random rnd = new Random(seed);
            for (int k = 0; k < 6; k++) {
                final int cx = 20 + rnd.nextInt(216), cy = 10 + rnd.nextInt(60);
                final double rx = 10 + rnd.nextInt(14), ry = 6 + rnd.nextInt(8);
                for (int y = 0; y < 0.45 * H; y++) {
                    for (int x = 0; x < W; x++) {
                        final double dd = Math.hypot((x - cx) / rx, (y - cy) / ry);
                        if (dd >= 1) continue;
                        float v = (float) (0.55 + 0.9 * (1 - dd) * (1 + 0.25 * Math.sin(x / 2.7 + y / 3.1) * Math.sin(y / 1.9)));
                        v = Math.max(v, im.px[(y * W + x) * 3 + 1]);
                        im.set(x, y, Math.min(1.2f, v), Math.min(1.2f, v), Math.min(1.2f, 0.99f * v));
                    }
                }
            }
            final WhySkyMask.Decision d = daylight(im, 0, 1.2f);
            assertNotNull(d.log, d.mask);
            assertFalse("clipped cumulus tops, seed " + seed + " (" + d.log + ")", d.mask.backlit);
        }
    }

    @Test
    public void aWireAcrossTheSkyDoesNotCutIt() {
        // P378 review (the owner's flats: two power lines over the whole width): the sky below them is the sky as above them
        final Img clear = outdoor(9, 0.03f), wires = outdoor(9, 0.03f);
        for (int x = 0; x < W; x++) {
            for (int y = 28; y < 30; y++) wires.set(x, y, 0.02f, 0.02f, 0.02f);
            for (int y = 40; y < 42; y++) wires.set(x, y, 0.02f, 0.02f, 0.02f);
            final int yd = (int) (10 + 0.25 * x);
            if (yd < 80) {
                wires.set(x, yd, 0.02f, 0.02f, 0.02f);
                wires.set(x, yd + 1, 0.02f, 0.02f, 0.02f);
            }
        }
        final WhySkyMask.Mask a = daylight(clear, 0, 1.2f).mask, b = daylight(wires, 0, 1.2f).mask;
        assertNotNull(a);
        assertNotNull(b);
        assertTrue("coverage " + b.coverage + " against " + a.coverage, b.coverage > 0.95f * a.coverage);
        // every sky cell clear of the wires (rows of 0 .. 40 % away from them) as the sky without them
        for (int cy = 0; cy < 0.4 * b.ny; cy++) {
            for (int cx = 0; cx < b.nx; cx++) {
                final int py = cy * 4 + 2;
                if (Math.abs(py - 29) < 6 || Math.abs(py - 41) < 6 || Math.abs(py - (10 + 0.25 * (cx * 4 + 2))) < 6) continue;
                assertTrue("sky cell " + cx + "," + cy + " beyond the wires", cell(b, cx, cy, 0) > 0.9f);
            }
        }
        assertFalse(b.backlit);
    }

    @Test
    public void aSkyOverMostOfTheFrameIsFound() {
        // P378 review: over the whole frame's median the term was off from 50 % of sky up; over the subject half's it holds to 70 %
        for (double rows : new double[]{0.55, 0.6, 0.65, 0.7}) {
            final Img im = new Img();
            final Random rnd = new Random(9);
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    if (y < rows * H) {
                        final float v = cloud(x, y, 0.45f);
                        im.set(x, y, 0.88f * v, 0.97f * v, 1.15f * v);
                    } else {
                        final float v = grain(rnd, 0.03f);
                        im.set(x, y, 1.05f * v, v, 0.9f * v);
                    }
                }
            }
            final WhySkyMask.Decision d = daylight(im, 0, 1.2f);
            assertNotNull(rows + ": " + d.log, d.mask);
            assertEquals("sky over " + rows + " of the frame: " + d.log, rows, d.mask.coverage, 0.04);
        }
    }

    @Test
    public void aFrontLitFaceAgainstTheSkyIsNotSky() {
        // P378 review: a light-skin face 0.5 - 1.3 stops under an overcast sky, dark hair, a dark jacket; ISO 100, 1/2000 s
        for (float face : new float[]{0.18f, 0.25f, 0.32f}) {
            final Img im = new Img();
            final Random rnd = new Random(2);
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    final double dx = (x - 128) / 28.0, dy = (y - 50) / 32.0, r = Math.hypot(dx, dy);
                    if (y > 80 && Math.abs(x - 128) < 70) {
                        final float v = grain(rnd, 0.02f);
                        im.set(x, y, v, v, v);
                    } else if (r < 1.0 && dy < -0.55) {
                        final float v = grain(rnd, 0.01f);
                        im.set(x, y, v, v, 0.9f * v);
                    } else if (r < 1.0) {
                        final float v = face * (float) (1 - 0.15 * r * r);
                        im.set(x, y, 1.45f * v, 0.95f * v, 0.72f * v);
                    } else if (y < 0.4 * H || Math.abs(x - 128) >= 70) {
                        final float v = y < 0.4 * H ? cloud(x, y, 0.45f) : grain(rnd, 0.03f);
                        if (y < 0.4 * H) im.set(x, y, 0.88f * v, 0.97f * v, 1.15f * v);
                        else im.set(x, y, 1.05f * v, v, 0.9f * v);
                    } else {
                        final float v = grain(rnd, 0.03f);
                        im.set(x, y, 1.05f * v, v, 0.9f * v);
                    }
                }
            }
            final WhySkyMask.Decision d = daylight(im, 0, 1.2f);
            assertNotNull(d.log, d.mask);
            for (int cy = 15; cy <= 20; cy++)
                for (int cx = 29; cx <= 34; cx++) assertEquals("face " + face + " cell " + cx + "," + cy, 0f, cell(d.mask, cx, cy, 0), 0.02f);
        }
        // the colour gate itself: skin and a red brick none, the clouds' cast and the blue sky all
        assertEquals(0f, WhySkyMask.colourGate(1.45f, 0.95f, 0.72f), 0.01f);
        assertEquals(0f, WhySkyMask.colourGate(1.6f, 0.8f, 0.6f), 0.01f);
        assertEquals(0f, WhySkyMask.colourGate(1.1f, 1f, 0.85f), 0.01f);
        assertEquals(1f, WhySkyMask.colourGate(1.02f, 1f, 0.985f), 0.01f);
        assertEquals(1f, WhySkyMask.colourGate(0.95f, 1f, 1.07f), 0.01f);
        assertEquals(1f, WhySkyMask.colourGate(0.72f, 0.9f, 1.3f), 0.01f);
    }

    @Test
    public void aDaylitWhiteCeilingIsNoSky() {
        // P378 review: a white ceiling at 0.6 along the top of a daylit room (EV ~10.7 at 1/100 s), furniture at 0.08 below
        for (double[] e : new double[][]{{100, 1 / 100.0}, {100, 1 / 50.0}, {200, 1 / 60.0}}) {
            final Img im = new Img();
            final Random rnd = new Random(4);
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    final float v = y < 0.3 * H ? 0.6f * (float) (1.0 - 0.3 * y / (0.3 * H)) : grain(rnd, 0.08f);
                    im.set(x, y, 1.05f * v, v, 0.92f * v);
                }
            }
            assertNull("ISO " + (int) e[0] + ", 1/" + Math.round(1 / e[1]) + " s", decide(im, (int) e[0], e[1], 1.8f, 0, 1.2f).mask);
        }
    }

    @Test
    public void aLightSurfaceJoinedBelowTheSkyIsCutOff() {
        // P378 review: a plain white facade (0.30 / 0.36 under an overcast sky of 0.45), snow (0.40 under 0.55), light plaster
        // (warm) joined below the sky over the top 25 %, dark ground under them
        final float[][] cases = {{0.45f, 0.30f, 1f, 1f, 0.98f}, {0.45f, 0.36f, 1f, 1f, 0.98f}, {0.55f, 0.40f, 0.97f, 1f, 1.05f}, {0.45f, 0.32f, 1.12f, 1f, 0.84f}};
        for (float[] f : cases) {
            final Img im = new Img();
            final Random rnd = new Random(7);
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    if (y < 0.25 * H) {
                        final float v = cloud(x, y, f[0]);
                        im.set(x, y, 0.95f * v, v, 1.06f * v);
                    } else if (y < 0.45 * H) {
                        final float v = f[1] * (float) (1 + 0.03 * rnd.nextGaussian());
                        im.set(x, y, f[2] * v, f[3] * v, f[4] * v);
                    } else {
                        final float v = grain(rnd, 0.03f);
                        im.set(x, y, v, v, v);
                    }
                }
            }
            final WhySkyMask.Decision d = daylight(im, 0, 1.2f);
            assertNotNull(d.log, d.mask);
            for (int cx = 0; cx < d.mask.nx; cx++) {
                for (int cy = 0; cy < 0.2 * d.mask.ny; cy++) assertTrue("sky " + cx + "," + cy + ": " + d.log, cell(d.mask, cx, cy, 0) > 0.9f);
                for (int cy = (int) (0.3 * d.mask.ny) + 1; cy < 0.42 * d.mask.ny; cy++)
                    assertEquals("surface " + f[1] + " cell " + cx + "," + cy + ": " + d.log, 0f, cell(d.mask, cx, cy, 0), 0f);
            }
        }
    }

    @Test
    public void theViewfindersQuadsDecideAsThePhotosCells() {
        // LiveWhyMeter's cells: 2 x 2 adjacent quads (whyLow px) at the centre of each of 128 x 96 cells; a sky cell is 2 x 2 of them
        final Img im = skyOverGround(13);
        final int mx = 128, my = 96, pitch = W / mx;
        final float[] quads = new float[mx * my * 12];
        for (int cy = 0; cy < my; cy++) {
            for (int cx = 0; cx < mx; cx++) {
                for (int q = 0; q < 4; q++) {
                    final int x = cx * pitch + (q & 1), y = cy * pitch + (q >> 1), o = (y * W + x) * 3;
                    System.arraycopy(im.px, o, quads, (cy * mx + cx) * 12 + 3 * q, 3);
                }
            }
        }
        final WhySkyMask.Cells c = WhySkyMask.Cells.ofQuads(quads, mx, my, WhySkyMask.CLIP_LEVEL * 4f);
        assertEquals(64, c.nx);
        assertEquals(48, c.ny);
        final WhySkyMask.Decision vf = WhySkyMask.decide(c, new WhySkyMask.Scene(WhySkyMask.evOffset(100, 1 / 2000.0, 1.8f, 1f), WhySkyMask.TOP_ROW0, 4f));
        final WhySkyMask.Decision photo = daylight(im, 0, 4f);
        assertNotNull(vf.log, vf.mask);
        assertEquals(vf.log + " / " + photo.log, photo.mask.coverage, vf.mask.coverage, 0.03f);
        assertEquals(photo.mask.skyLog2, vf.mask.skyLog2, 0.1f);
        assertFalse(vf.mask.backlit);
    }

    @Test
    public void theLabsFrozenCellsComeBackAndScale() {
        // P277 / P378 review: the whole frame's cells (LabFrame) give the same decision; another «Масштаб входа, EV» scales them
        final Img im = skyOverGround(14);
        final WhySkyMask.Cells c = WhySkyMask.Cells.of(im.px, W, H, 3, WhySkyMask.CLIP_LEVEL * 4f);
        final WhySkyMask.Cells back = WhySkyMask.Cells.unpack(c.nx, c.ny, c.cell, c.step, c.pack());
        assertNotNull(back);
        final WhySkyMask.Scene scene = new WhySkyMask.Scene(WhySkyMask.evOffset(100, 1 / 2000.0, 1.8f, 1f), WhySkyMask.TOP_ROW0, 4f);
        assertEquals(WhySkyMask.decide(c, scene).log, WhySkyMask.decide(back, scene).log);
        assertNull(WhySkyMask.Cells.unpack(c.nx, c.ny, c.cell, c.step, new float[3]));
        // whyLow x 2 (one stop more input): the same sky at the same absolute EV
        final WhySkyMask.Cells two = c.scaled(2f);
        assertEquals(c.l[5] + 1f, two.l[5], 1e-5f);
        assertEquals(2f * c.g[5], two.g[5], 1e-6f);
        assertEquals(c.tex[5], two.tex[5], 0f);
        final WhySkyMask.Decision d2 = WhySkyMask.decide(two, new WhySkyMask.Scene(WhySkyMask.evOffset(100, 1 / 2000.0, 1.8f, 2f), WhySkyMask.TOP_ROW0, 8f));
        assertEquals(WhySkyMask.decide(c, scene).mask.coverage, d2.mask.coverage, 1e-6f);
    }
    @Test
    public void aDimRoomUnderTheSkyFadesTheLoweringOut() {
        // P378 review: a window over a room - the sky's lowering a ramp of the room's EV (in full under SHADE_EV, none from
        // SHADE_EV_HI): a room 0.5 EV brighter took the sky from x1.00 to x0.85 at one threshold
        float last = -1f;
        for (float ev = 7.5f; ev <= 11.01f; ev += 0.25f) {
            final Img im = skyOverGround(3);
            final Random rnd = new Random(4);
            final float level = 0.45f * (float) Math.pow(2, ev - 14.8);
            for (int y = (int) (0.35 * H) + 1; y < H; y++)
                for (int x = 0; x < W; x++) {
                    final float v = grain(rnd, level);
                    im.set(x, y, 1.05f * v, v, 0.9f * v);
                }
            final WhySkyMask.Decision d = daylight(im, 0, 1.2f);
            assertNotNull(d.log, d.mask);
            final float keep = d.mask.keep;
            if (ev <= 8.5f) assertEquals("a room at EV " + ev + ": " + d.log, 0f, keep, 1e-3f);
            if (ev >= 10.75f) assertEquals("a room at EV " + ev + ": " + d.log, 1f, keep, 1e-3f);
            assertTrue("never back (EV " + ev + ": " + keep + " after " + last + ")", keep >= last - 1e-4f);
            assertTrue("no switch: x" + keep + " after x" + last + " at EV " + ev, last < 0f || keep - last <= 0.35f);
            last = keep;
        }
    }
}
