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

    /**
     * P380 (owner, 2026-10-28: the pair with Unspektrawesome's SPEKTRA): the film's diffusion, its grade and the DIR couplers' amount
     * - stored keys (in KEYS: the lab, the backups, the pages' and the tray's resets), their parsing and clamps, the diffusion only a
     * LensFilters.IDS family, its share x the simulation's strength, its frame the format's.
     */
    @Test public void theDiffusionAndTheCouplersSettings() {
        final java.util.List<String> keys = java.util.Arrays.asList(FilmSim.KEYS);
        for (String k : new String[]{FilmSim.DIFFUSION_KEY, FilmSim.DIFFUSION_STRENGTH_KEY, FilmSim.DIR_KEY}) assertTrue(k, keys.contains(k));
        assertEquals(new java.util.HashSet<>(keys).size(), keys.size());
        assertEquals("the default grade, spektrafilm's 0.5", .5f, FilmSim.diffusionGrade(null), 0f);
        assertEquals(.5f, FilmSim.diffusionGrade("50"), 0f);
        assertEquals(2f, FilmSim.diffusionGrade("200"), 0f);
        assertEquals("clamped", 2f, FilmSim.diffusionGrade("350"), 0f);
        assertEquals(0f, FilmSim.diffusionGrade("-5"), 0f);
        assertEquals(.5f, FilmSim.diffusionGrade("abc"), 0f);
        assertEquals("the couplers at 100 % by default", 1f, FilmSim.couplers(null), 0f);
        assertEquals(0f, FilmSim.couplers("0"), 0f);
        assertEquals(.5f, FilmSim.couplers("50"), 0f);
        assertEquals("clamped at the stocks' own amount (review: greys go off above it)", 1f, FilmSim.couplers("260"), 0f);
        assertEquals(1f, FilmSim.couplers("150"), 0f);
        assertEquals(0f, FilmSim.couplers("-1"), 0f);
        final FilmSim.Stock portra = FilmSim.stock("kodak_portra_160");
        final FilmSim.Print premier = FilmSim.print("kodak_endura_premier");
        final FilmSim.Settings off = new FilmSim.Settings(portra, premier, FilmSim.format("imax70"), 1, 1, 1, 0, 0, 0, 0, 0, 1);
        assertEquals("", off.diffusion);
        assertEquals(0.0, off.diffusionP(), 0);
        assertEquals(1f, off.couplers, 0f);
        for (String id : LensFilters.IDS) {
            final FilmSim.Settings on = new FilmSim.Settings(portra, premier, FilmSim.format("imax70"), 1, 0, 0, 0, 0, 0, 0, 0, 1, id, 2f, 0f);
            assertEquals(id, on.diffusion);
            assertEquals(DiffusionModel.scatterAtGrade(id, 2.0), on.diffusionP(), 1e-12);
            final FilmSim.Settings half = new FilmSim.Settings(portra, premier, FilmSim.format("imax70"), 1, 0, 0, 0, 0, 0, 0, 0, .5f, id, 2f, 0f);
            assertEquals("x the simulation's strength, as the halation", .5 * DiffusionModel.scatterAtGrade(id, 2.0), half.diffusionP(), 1e-9);
        }
        assertEquals("the owner's pair: Pro-Mist 2.0", .7875, new FilmSim.Settings(portra, premier, FilmSim.format("imax70"), 1, 0, 0, 0, 0, 0,
                0, 0, 1, LensFilters.PEARLESCENT, 2f, 0f).diffusionP(), 1e-9);
        // only the offered diffusion families (a stored lens filter of another kind is none)
        for (String id : new String[]{LensFilters.FOG, LensFilters.STREAK, "nonsense", null})
            assertEquals(String.valueOf(id), "", new FilmSim.Settings(portra, premier, FilmSim.format("135"), 1, 1, 1, 0, 0, 0, 0, 0, 1, id, 1f, 1f).diffusion);
        // the frame: the format's long edge, the lens filter's 36 mm without a format
        assertEquals(70410.0, new FilmSim.Settings(portra, premier, FilmSim.format("imax70"), 1, 1, 1, 0, 0, 0, 1).diffusionFrameUm(), 0.01);
        assertEquals(36000.0, new FilmSim.Settings(portra, premier, FilmSim.format("135"), 1, 1, 1, 0, 0, 0, 1).diffusionFrameUm(), 0.01);
        assertEquals(DiffusionModel.FRAME_UM, new FilmSim.Settings(portra, premier, FilmSim.format("off"), 1, 1, 1, 0, 0, 0, 1).diffusionFrameUm(), 0);
        // the table's key: the couplers' amount its 8th field; a 7-field key (before P380) parses at 100 %
        final FilmSim.Settings dir0 = new FilmSim.Settings(portra, premier, FilmSim.format("135"), 1, 1, 1, 0, 0, 0, 0, 0, 1, "", .5f, 0f);
        assertEquals(8, dir0.lutKey().split("\\|", -1).length);
        assertTrue(dir0.lutKey(), dir0.lutKey().endsWith("|0.00"));
        assertNotEquals(dir0.lutKey(), off.lutKey());
        assertEquals("the diffusion is an effect, not the table's", off.lutKey(), new FilmSim.Settings(portra, premier, FilmSim.format("135"), 2, 0,
                3, 0, 0, 0, 0, 0, .4f, LensFilters.CINEBLOOM, 2f, 1f).lutKey());
        assertEquals(0f, FilmSim.Settings.forLut(dir0.lutKey(), 1).couplers, 0f);
        final String seven = "kodak_portra_160|kodak_endura_premier|0.00|0.00|0.00|0.00|0.00";
        final FilmSim.Settings old = FilmSim.Settings.forLut(seven, 1);
        assertNotNull(old);
        assertEquals(1f, old.couplers, 0f);
        assertEquals(seven + "|1.00", old.lutKey());
        assertEquals("kodak_endura_premier", old.print.id);
        assertEquals(.5f, FilmSim.Settings.forLut(seven + "|0.50", 1).couplers, 0f);
        assertEquals("over 100 % clamped", 1f, FilmSim.Settings.forLut(seven + "|1.50", 1).couplers, 0f);
        assertEquals("a key's further fields are left", .5f, FilmSim.Settings.forLut(seven + "|0.50|0.80|look|1.00", 1).couplers, 0f);
        // the couplers reach the colour: a red at 0 % is paler than at 100 %
        final float[] red0 = FilmSim.applyScene(dir0, .4, .1, .08), red1 = FilmSim.applyScene(off, .4, .1, .08);
        assertTrue((red0[0] - red0[2]) + " vs " + (red1[0] - red1[2]), red0[0] - red0[2] < red1[0] - red1[2] - .03f);
    }

    /**
     * P380: 65 mm and IMAX 70 (spektrafilm-ofx's long edges), the default found by its id, and the grain's final blur x (format /
     * 35 mm)^0.62 on every format (the reference stocks' grain; our black-and-white grain does not take it).
     */
    @Test public void theFormatsAndTheGrainBlur() {
        assertEquals(52.48f, FilmSim.format("65mm").longMm, 0f);
        assertEquals("65 mm", FilmSim.format("65mm").name);
        assertEquals(70.41f, FilmSim.format("imax70").longMm, 0f);
        assertEquals("IMAX 70", FilmSim.format("imax70").name);
        assertEquals("the default by its id", "135", FilmSim.format(null).id);
        assertEquals("135", FilmSim.format("no_such_format").id);
        assertEquals(FilmSim.DEFAULT_FORMAT, FilmSim.format("").id);
        final String[] ids = {"135", "8mm", "super8", "16mm", "super16", "super35", "645", "67", "65mm", "imax70"};
        final float[] scale = {1.018f, .29f, .33f, .47f, .53f, .81f, 1.34f, 1.54f, 1.29f, 1.54f};
        for (int i = 0; i < ids.length; i++) {
            final FilmSim.Format f = FilmSim.format(ids[i]);
            assertEquals(ids[i], f.id);
            assertEquals(ids[i], scale[i], FilmSim.grainBlurScale(f.longMm), .006f);
            assertEquals(ids[i], 11.8f * (float) Math.pow(f.longMm / 35.0, .62), FilmSim.grainBlurUm(f), 1e-4f);
        }
        // the blur's size in the picture still falls with a larger frame (its micrometres grow slower than the frame's)
        final float um35 = FilmSim.grainBlurUm(FilmSim.format("135")) / (36000f / 4000), umImax = FilmSim.grainBlurUm(FilmSim.format("imax70")) / (70410f / 4000);
        assertTrue(umImax + " px vs " + um35 + " px", umImax < um35);
        // our classic stocks' grain is FilmSim's own (no final blur)
        final FilmSim.Settings trix = new FilmSim.Settings(FilmSim.stock("trix400"), FilmSim.print(""), FilmSim.format("imax70"), 1, 1, 1, 0, 0, 0, 1);
        assertEquals(FilmSim.grainSigma(17f, 6f, 17.6f) * 2.7f * FilmSim.GRAIN_SCALE, FilmSim.classicGrainSigma(trix, 17.6f), 1e-7f);
    }

    /** P380: the recipe in the photo's description (only with a film: Parameters.filmLine). */
    @Test public void theRecipeInThePhoto() {
        final FilmSim.Settings theirs = new FilmSim.Settings(FilmSim.stock("kodak_portra_160"), FilmSim.print("kodak_endura_premier"),
                FilmSim.format("imax70"), 1, 0, 0, 0, 0, 0, 0, 0, 1, LensFilters.PEARLESCENT, 2f, 0f);
        final String r = theirs.recipe();
        for (String part : new String[]{"Kodak Portra 160", "print Kodak Endura Premier", "format IMAX 70", "diffusion Pro-Mist 2.00",
                "grain 100 %", "halation 0 %", "softness 0 %", "DIR couplers 0 %", "film exposure +0.0 EV", "print exposure +0.00 EV",
                "contrast 0", "enlarger M 0 Y 0 CC", "strength 100 %"})
            assertTrue(r + " has " + part, r.contains(part));
        for (char ch : r.toCharArray()) assertTrue("EXIF ASCII: " + r, ch >= 32 && ch < 127);
        assertTrue(new FilmSim.Settings(FilmSim.stock("kodak_portra_400"), FilmSim.print(""), FilmSim.format("67"), 1, 1, 1, 0, 0, 0, 1).recipe()
                .contains("format 6x7; diffusion none"));
        assertTrue(new FilmSim.Settings(FilmSim.stock("fujifilm_velvia_100"), FilmSim.print(""), FilmSim.format("off"), 1, 1, 1, 0, 0, 0, 1).recipe()
                .contains("print none (projected); format off"));
        assertEquals("\n Film=" + r, com.particlesdevs.photoncamera.processing.render.Parameters.filmLine(r));
        assertEquals("without a film: nothing", "", com.particlesdevs.photoncamera.processing.render.Parameters.filmLine(null));
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

    /**
     * Owner, 2026-10-28 («описание для параметров по длинному зажатию»): the directions ParamHelp states hold on the model -
     * the film's exposure up prints lighter, the print's exposure up prints darker, contrast up spreads the tones, and a slide
     * (not printed) ignores the print's exposure.
     */
    @Test public void theParameterDescriptionsHoldOnTheModel() {
        final FilmSim.Stock portra = FilmSim.stock("kodak_portra_400");
        final FilmSim.Print endura = FilmSim.print("kodak_portra_endura");
        final FilmSim.Format f135 = FilmSim.format("135");
        final FilmSim.Settings base = new FilmSim.Settings(portra, endura, f135, 1, 1, 1, 0, 0, 0, 1);
        final float grey = luma(FilmSim.applyScene(base, .184, .184, .184));
        final float up = luma(FilmSim.applyScene(new FilmSim.Settings(portra, endura, f135, 1, 1, 1, 1, 0, 0, 1), .184, .184, .184));
        assertTrue("film exposure +1: a lighter print (" + grey + " -> " + up + ")", up > grey + .02f);
        final float printUp = luma(FilmSim.applyScene(new FilmSim.Settings(portra, endura, f135, 1, 1, 1, 0, .5f, 0, 1), .184, .184, .184));
        assertTrue("print exposure +0.5: a darker print (" + grey + " -> " + printUp + ")", printUp < grey - .02f);
        // contrast up: denser (a darker grey), more saturated, more contrast in the highlights
        final FilmSim.Settings hard = new FilmSim.Settings(portra, endura, f135, 1, 1, 1, 0, 0, .6f, 1);
        assertTrue("contrast +60: darker", luma(FilmSim.applyScene(hard, .184, .184, .184)) < grey - .05f);
        final float[] red = FilmSim.applyScene(base, .3, .12, .08), hardRed = FilmSim.applyScene(hard, .3, .12, .08);
        assertTrue("contrast +60: more saturated", (hardRed[0] - hardRed[2]) / hardRed[0] > (red[0] - red[2]) / red[0] + .05f);
        final float lights = luma(FilmSim.applyScene(base, 1.6, 1.6, 1.6)) - luma(FilmSim.applyScene(base, .4, .4, .4));
        final float hardLights = luma(FilmSim.applyScene(hard, 1.6, 1.6, 1.6)) - luma(FilmSim.applyScene(hard, .4, .4, .4));
        assertTrue("contrast +60: more contrast in the highlights", hardLights > lights + .05f);
        for (FilmSim.Stock st : FilmSim.STOCKS) {
            final FilmSim.Print p = FilmSim.print("", st);
            final float a = luma(FilmSim.applyScene(new FilmSim.Settings(st, p, f135, 1, 1, 1, 0, 0, 0, 1), .184, .184, .184));
            final float ev = luma(FilmSim.applyScene(new FilmSim.Settings(st, p, f135, 1, 1, 1, 1, 0, 0, 1), .184, .184, .184));
            assertTrue(st.id + ": the film's exposure +1 is lighter on every stock (" + a + " -> " + ev + ")", ev > a + .02f);
            final float pr = luma(FilmSim.applyScene(new FilmSim.Settings(st, p, f135, 1, 1, 1, 0, .5f, 0, 1), .184, .184, .184));
            if (st.type == FilmSim.SLIDE || st.type == FilmSim.BW_REVERSAL)
                assertEquals(st.id + ": not printed, the print's exposure does nothing", a, pr, 1e-6f);
            else assertTrue(st.id + ": the print's exposure +0.5 is darker", pr < a - .02f);
            if (st.spectral == null) {
                final float tone = luma(FilmSim.applyScene(new FilmSim.Settings(st, p, f135, 1, 1, 1, 0, 0, .6f, 1), .184, .184, .184));
                assertEquals(st.id + ": black and white: the contrast row does nothing (the tray leaves it out)", a, tone, 1e-6f);
            }
        }
    }
}
