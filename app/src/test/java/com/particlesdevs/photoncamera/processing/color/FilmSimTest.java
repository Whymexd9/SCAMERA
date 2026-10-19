package com.particlesdevs.photoncamera.processing.color;

import org.junit.Test;

import static org.junit.Assert.*;

public class FilmSimTest {
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
                    if (v > .1f && v < .95f) {
                        final float spread = Math.max(o[0], Math.max(o[1], o[2])) - Math.min(o[0], Math.min(o[1], o[2]));
                        assertTrue(st.id + "/" + pr.id + " neutral at " + v + ": " + o[0] + " " + o[1] + " " + o[2], spread < .03f);
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
        assertEquals("endura_premier", s8.print.id);
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
