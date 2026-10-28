package com.particlesdevs.photoncamera.processing.color;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class FilmSimTest {
    @BeforeClass
    public static void assets() {
        FilmSpectralTest.assets(); // P286: the colour stocks read the reference model's data
    }

    static FilmSim.Settings set(String stock, String print) {
        return new FilmSim.Settings(FilmSim.stock(stock), FilmSim.print(print), FilmSim.format("135"), 1, 1, 1, 0, 0, 0, 1);
    }

    static float luma(float[] c) {
        return .2126f * c[0] + .7152f * c[1] + .0722f * c[2];
    }

    /** Every stock on every print: mid grey near mid grey and neutral, black dark, white light, the grey scale rising. */
    @Test public void everyStockRendersAMonotonicNeutralGreyScale() {
        for (FilmSim.Stock st : FilmSim.STOCKS) {
            for (FilmSim.Print pr : FilmSim.PRINTS) {
                final FilmSim.Settings s = set(st.id, pr.id);
                float last = -1;
                for (int i = 0; i <= 20; i++) {
                    final float v = i / 20f;
                    final float[] o = FilmSim.apply(s, v, v, v);
                    for (float c : o) assertTrue(st.id + "/" + pr.id + " finite", Float.isFinite(c) && c >= 0 && c <= 1);
                    final float y = luma(o);
                    assertTrue(st.id + "/" + pr.id + " grey scale rises at " + v + ": " + y + " after " + last, y >= last - 1e-3f);
                    last = y;
                    // our model is neutral over the scale; the reference model (P286) at mid grey only: a stock on a paper it is not
                    // balanced for keeps the reference's own tint in the shadows and highlights (X-Tra 400 on Endura Premier: blue whites)
                    if (st.spectral == null ? v > .1f && v < .95f : Math.abs(v - .45f) < .01f) {
                        final float spread = Math.max(o[0], Math.max(o[1], o[2])) - Math.min(o[0], Math.min(o[1], o[2]));
                        assertTrue(st.id + "/" + pr.id + " neutral at " + v + ": " + o[0] + " " + o[1] + " " + o[2],
                                spread < (st.spectral == null ? .03f : .05f));
                    }
                }
                final float mid = luma(FilmSim.apply(s, .46f, .46f, .46f));
                assertTrue(st.id + "/" + pr.id + " mid grey " + mid, mid > .28f && mid < .66f);
                assertTrue(st.id + "/" + pr.id + " black", luma(FilmSim.apply(s, 0, 0, 0)) < .25f);
                assertTrue(st.id + "/" + pr.id + " white", luma(FilmSim.apply(s, 1, 1, 1)) > .6f);
            }
        }
    }

    /** Colour stays colour (a red stays red, a sky stays blue) and black and white stocks are grey. */
    @Test public void huesStayAndMonochromeIsGrey() {
        for (FilmSim.Stock st : FilmSim.STOCKS) {
            final FilmSim.Settings s = set(st.id, "endura_premier");
            final float[] red = FilmSim.apply(s, .8f, .15f, .12f), sky = FilmSim.apply(s, .35f, .55f, .85f), leaf = FilmSim.apply(s, .3f, .6f, .2f);
            if (st.mono()) {
                assertEquals(st.id, red[0], red[2], 1e-3f);
                assertEquals(st.id, sky[0], sky[2], 1e-3f);
                continue;
            }
            assertTrue(st.id + " red " + red[0] + " " + red[1] + " " + red[2], red[0] > red[1] + .1f && red[0] > red[2] + .1f);
            assertTrue(st.id + " sky " + sky[0] + " " + sky[1] + " " + sky[2], sky[2] > sky[0] + .05f);
            assertTrue(st.id + " leaf " + leaf[0] + " " + leaf[1] + " " + leaf[2], leaf[1] > leaf[0] && leaf[1] > leaf[2]);
        }
    }

    /** The LUT is built quickly enough to make at a tap (33 points a side). */
    @Test public void theLutIsBuiltInAFractionOfASecond() {
        final long t0 = System.nanoTime();
        final FilmLut lut = FilmSim.lut(set("portra400", "endura_premier"), 33);
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(33, lut.size);
        assertEquals(33 * 33 * 33 * 3, lut.rgb.length);
        assertTrue("built in " + ms + " ms", ms < 4000);
        System.out.println("FilmSim LUT 33^3 in " + ms + " ms");
    }

    @Test public void effectsOfTheFormatScaleWithTheFrame() {
        final FilmSim.Settings s35 = set("portra400", "endura_premier");
        assertEquals(9f, s35.pixelUm(4000), .01f);
        final FilmSim.Settings s8 = new FilmSim.Settings(FilmSim.stock("portra400"), FilmSim.print("x"), FilmSim.format("super8"), 1, 1, 1, 0, 0, 0, 1);
        assertEquals(5.79f * 1000 / 4000, s8.pixelUm(4000), .01f);
        assertEquals("kodak_portra_endura", s8.print.id);
        assertEquals(0f, new FilmSim.Settings(FilmSim.stock("portra400"), FilmSim.print(null), FilmSim.format("off"), 1, 1, 1, 0, 0, 0, 1).pixelUm(4000), 0f);
    }

    /** The chain: the film LUT alone at full strength, at zero the identity; a user LUT after it. */
    @Test public void theChainComposesTheFilmAndTheLut() {
        final FilmLut film = FilmSim.lut(set("portra400", "endura_premier"), 9);
        final FilmLut same = FilmChain.compose(film, 1f, null, 0f);
        assertArrayEquals(film.rgb, same.rgb, 1e-6f);
        final FilmLut none = FilmChain.compose(film, 0f, null, 0f);
        assertArrayEquals(FilmLut.identity(9).rgb, none.rgb, 1e-6f);
        final FilmLut inverted = FilmLut.of(2, new float[]{1, 1, 1, 0, 1, 1, 1, 0, 1, 0, 0, 1, 1, 1, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0}, "invert");
        final FilmLut both = FilmChain.compose(film, 1f, inverted, 1f);
        for (int i = 0; i < film.rgb.length; i++) assertEquals(1f - film.rgb[i], both.rgb[i], 1e-5f);
    }

    /** Grain by Selwyn's law: a smaller pixel (a smaller format) shows more grain until the grain's own size. */
    @Test public void grainFollowsTheFormat() {
        final float s35 = com.particlesdevs.photoncamera.processing.opengl.postpipeline.FilmGrainAccess.sigma(5, 3, 9f);
        final float s8 = com.particlesdevs.photoncamera.processing.opengl.postpipeline.FilmGrainAccess.sigma(5, 3, 1.45f);
        assertTrue(s8 > s35 * 2.5f);
        assertEquals(5 / 1000f * 42.5f / 9f, s35, 1e-6f);
        assertEquals(5 / 1000f * 42.5f / 3f, s8, 1e-6f);
        assertEquals(1, com.particlesdevs.photoncamera.processing.opengl.postpipeline.FilmGrainAccess.level(.5f, 10));
        assertEquals(3, com.particlesdevs.photoncamera.processing.opengl.postpipeline.FilmGrainAccess.level(7f, 10));
        assertEquals(8, com.particlesdevs.photoncamera.processing.opengl.postpipeline.FilmGrainAccess.level(5000f, 10));
    }

    /** P369: the scene table at a scene colour, as why/combine FILM_SIM 1 looks it up (per-channel log coordinate, trilinear). */
    static float[] sampleScene(FilmLut lut, double r, double g, double b) {
        final double[] c = {r, g, b};
        final float[] u = new float[3];
        for (int i = 0; i < 3; i++)
            u[i] = (float) Math.max(0, Math.min(1, (Math.log(Math.max(c[i], 1e-7) / 0.184) / Math.log(2) - FilmSim.LOG_LO) / FilmSim.LOG_SPAN));
        return lut.sample(u[0], u[1], u[2]);
    }

    /**
     * P369 (G11): the 41^3 log table against the model it tabulates (FilmSpectral.Engine.render) between the nodes: 400 colours
     * from 0.003 to 6 (about -6 to +5 stops around mid grey), skin, sky, foliage and saturated light among them.
     */
    @Test public void sceneLutMatchesTheEngine() {
        final FilmSim.Settings s = set("kodak_portra_400", "kodak_portra_endura");
        final FilmLut lut = FilmSim.lutScene(s, FilmSim.SCENE_SIZE);
        assertNotNull(lut);
        assertEquals(FilmSim.SCENE_SIZE, lut.size);
        final java.util.Random rnd = new java.util.Random(369);
        double worst = 0, sum = 0;
        int n = 0;
        for (int i = 0; i < 400; i++) {
            final double base = 0.003 * Math.pow(6 / 0.003, rnd.nextDouble());
            final double r = base * (0.35 + 0.65 * rnd.nextDouble()), g = base * (0.35 + 0.65 * rnd.nextDouble()), b = base * (0.35 + 0.65 * rnd.nextDouble());
            final float[] want = FilmSim.applyScene(s, r, g, b), got = sampleScene(lut, r, g, b);
            for (int c = 0; c < 3; c++) {
                final double d = Math.abs(got[c] - want[c]) * 255;
                worst = Math.max(worst, d);
                sum += d;
                n++;
            }
        }
        System.out.println(String.format(java.util.Locale.ROOT, "scene LUT 41^3 vs the engine: max %.2f / 255, mean %.3f / 255", worst, sum / n));
        assertTrue("max " + worst, worst <= 2.5);
        assertTrue("mean " + sum / n, sum / n <= 0.4);
    }

    /**
     * P369 (the review's fidelity-2): saturated light far above mid grey - a red tail light, a blue LED, Bento highlights x ae x the
     * overflow's cut - with one channel up to +9.5 stops and the others far below: the model bleaches such a light toward white up
     * to about +10 stops, and a grid that ended at +6 kept its colour (Portra up to 89 / 255 off).
     */
    @Test public void saturatedLightAboveMidGreyMatchesTheEngine() {
        for (String st : new String[]{"kodak_portra_400", "kodak_vision3_250d"}) {
            final FilmSim.Settings s = set(st, "");
            final FilmLut lut = FilmSim.lutScene(s, FilmSim.SCENE_SIZE);
            assertNotNull(st, lut);
            final java.util.Random rnd = new java.util.Random(370);
            double worst = 0;
            for (int i = 0; i < 300; i++) {
                final double top = 0.184 * Math.pow(2, 4 + 5.5 * rnd.nextDouble());
                final double[] c = new double[3];
                final int lead = rnd.nextInt(3);
                for (int k = 0; k < 3; k++) c[k] = k == lead ? top : top * Math.pow(10, -4 * rnd.nextDouble());
                final float[] want = FilmSim.applyScene(s, c[0], c[1], c[2]), got = sampleScene(lut, c[0], c[1], c[2]);
                for (int k = 0; k < 3; k++) worst = Math.max(worst, Math.abs(got[k] - want[k]) * 255);
            }
            System.out.println(String.format(java.util.Locale.ROOT, "%s: saturated light +4 .. +9.5 stops, the table vs the engine max %.2f / 255", st, worst));
            assertTrue(st + " max " + worst, worst <= 3);
        }
    }

    /** P369 (B2): the AE's grey (WhyAe ae_target 0.15 x METER_TO_FILM) prints as the film's mid grey - a node of the table. */
    @Test public void aeTargetPrintsTheFilmsMidGrey() {
        for (String st : new String[]{"kodak_portra_400", "kodak_vision3_250d", "fujifilm_velvia_100", "kodak_gold_200"}) {
            final FilmSim.Settings s = set(st, "");
            final FilmLut lut = FilmSim.lutScene(s, FilmSim.SCENE_SIZE);
            final double grey = 0.15 * FilmSim.METER_TO_FILM;
            assertEquals(0.184, grey, 1e-6);
            final float[] got = sampleScene(lut, grey, grey, grey), want = FilmSim.applyScene(s, .184, .184, .184);
            for (int c = 0; c < 3; c++) assertEquals(st, want[c], got[c], 0.5f / 255);
            System.out.println(String.format(java.util.Locale.ROOT, "%s: the AE target prints %.1f / %.1f / %.1f of 255", st,
                    got[0] * 255, got[1] * 255, got[2] * 255));
        }
    }

    /** P369 (G6): our black-and-white model is rendered at its own mid grey (0.18): the AE's grey prints its mid grey too. */
    @Test public void theMonochromeModelsMidGreyNode() {
        for (String st : new String[]{"trix400", "tmax100", "hp5", "trix7266"}) {
            final FilmSim.Settings s = set(st, "");
            final FilmLut lut = FilmSim.lutScene(s, FilmSim.SCENE_SIZE);
            assertNotNull(st, lut);
            final float[] got = sampleScene(lut, .184, .184, .184);
            final float[] want = new FilmSim.Model(s.stock, FilmSim.MONO_PAPER, s.exposure, s.printEv, s.tone).render(new double[]{.18, .18, .18});
            for (int c = 0; c < 3; c++) assertEquals(st, FilmSim.encode(want[c]), got[c], 0.5f / 255);
            assertEquals(st + " grey", got[0], got[2], 1e-4f);
            // the display table's own grey: the display colour 0.46 (scene 0.18) prints the same
            final float[] display = FilmSim.apply(s, (float) FilmSim.encode(.18), (float) FilmSim.encode(.18), (float) FilmSim.encode(.18));
            assertEquals(st + " the display table's mid grey", display[1], got[1], 2f / 255);
        }
    }

    /** Values for a look at the model (printed, not asserted). */
    @Test public void printSamples() {
        for (String st : new String[]{"portra400", "ektar100", "velvia50", "vision500t", "trix400", "trix7266"}) {
            final FilmSim.Settings s = set(st, "endura_premier");
            final StringBuilder b = new StringBuilder(st + ":");
            for (float v : new float[]{0, .1f, .25f, .46f, .7f, .9f, 1f}) {
                final float[] o = FilmSim.apply(s, v, v, v);
                b.append(String.format(java.util.Locale.ROOT, " %.2f->%.3f", v, luma(o)));
            }
            final float[] red = FilmSim.apply(s, .8f, .15f, .12f);
            b.append(String.format(java.util.Locale.ROOT, " red %.2f %.2f %.2f", red[0], red[1], red[2]));
            System.out.println(b);
        }
    }
}
