package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

/** P289 (owner: the film sliders «не работают нормально»): every film slider changes the settings' preview. */
public class FilmEffectsTest {
    @BeforeClass
    public static void assets() {
        FilmSpectralTest.assets();
    }

    static FilmSim.Settings set(float grain, float halation, float softness, float ev) {
        return new FilmSim.Settings(FilmSim.stock("kodak_portra_400"), FilmSim.print("kodak_portra_endura"), FilmSim.format("135"),
                grain, halation, softness, ev, 0, 0, 0, 0, 1);
    }

    /** A grey scene with a bright lamp. */
    static int[] scene(int w, int h) {
        final int[] px = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            final boolean lamp = Math.abs(x - w / 2) < 3 && Math.abs(y - h / 2) < 3;
            final int v = lamp ? 255 : 110 + (x * 7 + y * 3) % 20;
            px[y * w + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return px;
    }

    static double diff(int[] a, int[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++)
            for (int sh = 0; sh <= 16; sh += 8) s += Math.abs(((a[i] >> sh) & 255) - ((b[i] >> sh) & 255));
        return s / (a.length * 3.0);
    }

    @Test
    public void grainHalationAndSoftnessShowInThePreview() {
        final int w = 160, h = 120;
        final int[] base = scene(w, h);
        final int[] grain = FilmEffects.grain(base, w, h, set(1, 0, 0, 0));
        assertTrue("grain " + diff(grain, base), diff(grain, base) > 0.5);
        assertTrue("more grain, more change", diff(FilmEffects.grain(base, w, h, set(2, 0, 0, 0)), base) > diff(grain, base));
        // a weak antihalation layer (Gold 200), a dark street around the lamp
        final int[] dark = new int[w * h];
        for (int i = 0; i < dark.length; i++) dark[i] = 0xFF282828;
        for (int y = h / 2 - 4; y <= h / 2 + 4; y++) for (int x = w / 2 - 4; x <= w / 2 + 4; x++) dark[y * w + x] = 0xFFFFFFFF;
        final FilmSim.Settings gold = new FilmSim.Settings(FilmSim.stock("kodak_gold_200"), FilmSim.print("kodak_portra_endura"),
                FilmSim.format("135"), 0, 4, 0, 0, 0, 0, 0, 0, 1);
        final int[] halo = FilmEffects.light(dark, w, h, gold);
        final int ring = (h / 2) * w + w / 2 + 9;
        assertTrue("halation reddens the lamp's surroundings: " + Integer.toHexString(halo[ring]),
                ((halo[ring] >> 16) & 255) > ((dark[ring] >> 16) & 255) + 3 && ((halo[ring] >> 16) & 255) > (halo[ring] & 255) + 2);
        final int[] soft = FilmEffects.light(base, w, h, set(0, 0, 2, 0));
        assertTrue("softness " + diff(soft, base), diff(soft, base) > 0.2);
    }

    /** Settings audit 2026-10-21: «Сила симуляции» scales the halation, the softness and the grain, not only the colour. */
    @Test
    public void theStrengthScalesTheSpatialEffects() {
        final FilmSim.Settings full = new FilmSim.Settings(FilmSim.stock("kodak_gold_200"), FilmSim.print("kodak_portra_endura"),
                FilmSim.format("135"), 1, 2, 1.5f, 0, 0, 0, 1), half = new FilmSim.Settings(full.stock, full.print, full.format,
                1, 2, 1.5f, 0, 0, 0, .5f);
        for (int c = 0; c < 3; c++) assertEquals(full.halationAmounts()[c] / 2, half.halationAmounts()[c], 1e-6f);
        assertEquals(full.softnessApplied() / 2, half.softnessApplied(), 1e-6f);
        assertEquals(full.grainApplied() / 2, half.grainApplied(), 1e-6f);
        assertEquals(2 * full.stock.halation[0], full.halationAmounts()[0], 0f); // strength 1: the photo as before
        final int w = 160, h = 120;
        final int[] dark = new int[w * h];
        for (int i = 0; i < dark.length; i++) dark[i] = 0xFF282828;
        for (int y = h / 2 - 4; y <= h / 2 + 4; y++) for (int x = w / 2 - 4; x <= w / 2 + 4; x++) dark[y * w + x] = 0xFFFFFFFF;
        final double atFull = diff(FilmEffects.light(dark, w, h, full), dark), atHalf = diff(FilmEffects.light(dark, w, h, half), dark);
        assertTrue("half " + atHalf + " full " + atFull, atHalf > 0 && atHalf < atFull * .8);
    }

    /**
     * film/grain.glsl's grain field's standard deviation at a clump size (pixels): a smooth-step bilinear blend of unit lattice
     * values (its variance averaged over the pixel's phase in the cell), x 1.37, plus the octave of half the size at half the
     * weight, x 0.894 - from the shader's formulas, not from FilmEffects.
     */
    static double fieldSd(float cell) {
        return Math.sqrt((blendVariance(cell, 0) * 1.37 * 1.37 + .25 * blendVariance(.5f * cell, .5f * cell) * 1.37 * 1.37) * .894 * .894);
    }

    private static double blendVariance(float size, float offset) {
        final double c = Math.max(size, 1f);
        double v = 0;
        final int steps = 2000;
        for (int i = 0; i < steps; i++) {
            double q = (i + .5 + offset) / c;
            double f = q - Math.floor(q);
            f = f * f * (3 - 2 * f);
            v += (1 - f) * (1 - f) + f * f;
        }
        v /= steps;
        return v * v;
    }

    /**
     * P369 (viewfinder-bw-preview): the black-and-white stocks' grain and film sharpness in the preview, as the photo's FilmGrain
     * draws them: grain on every stock; on mid grey its density spread within 20 % of film/grain.glsl's (its sigma x the tonal
     * weight x the field's spread); «Мягкость плёнки» 2 softens an edge, 0 leaves it.
     */
    @Test
    public void theMonochromeStocksShowTheirGrainAndSoftness() {
        final int w = 160, h = 120;
        final int grey = 118;   // encoded 0.463: linear 0.181
        final int[] flat = new int[w * h];
        java.util.Arrays.fill(flat, 0xFF000000 | (grey << 16) | (grey << 8) | grey);
        for (FilmSim.Stock st : FilmSim.STOCKS) {
            if (st.spectral != null) continue;
            final FilmSim.Settings s = new FilmSim.Settings(st, FilmSim.print(""), FilmSim.format("135"), 1, 0, 0, 0, 0, 0, 1);
            final int[] g = FilmEffects.grain(flat, w, h, s);
            assertTrue(st.id + " has grain", diff(g, flat) > 0.3);
            final float um = FilmEffects.pixelUm(s);
            final double y = FilmEffects.decode(grey);
            final double stops = Math.log(y / .18) / Math.log(2);
            final double wt = st.reversal() ? .25 + .75 * Math.exp(-Math.pow(Math.log(y / .04) / Math.log(2) / 2.5, 2))
                    : .35 + .65 * Math.exp(-Math.pow(stops / 2.2, 2));
            final double want = FilmSim.classicGrainSigma(s, um) * wt * fieldSd(FilmSim.classicGrainCell(s, um));
            double sum = 0, sum2 = 0;
            for (int px : g) {
                final double d = Math.log10(Math.max(FilmEffects.decode((px >> 8) & 255), 1e-6) / y);
                sum += d;
                sum2 += d * d;
            }
            final double sd = Math.sqrt(Math.max(sum2 / g.length - (sum / g.length) * (sum / g.length), 0));
            System.out.println(String.format(java.util.Locale.ROOT, "%s: grain density sd %.4f, film/grain.glsl %.4f", st.id, sd, want));
            assertEquals(st.id + " grain on mid grey", want, sd, want * .2);
        }
        // softness: a black / white edge, no grain
        final int[] edge = new int[w * h];
        for (int i = 0; i < edge.length; i++) edge[i] = (i % w) < w / 2 ? 0xFF202020 : 0xFFE0E0E0;
        final FilmSim.Stock trix = FilmSim.stock("trix400");
        final int at = (h / 2) * w + w / 2 - 1;
        final int[] soft = FilmEffects.grain(edge, w, h, new FilmSim.Settings(trix, FilmSim.print(""), FilmSim.format("135"), 0, 0, 2, 0, 0, 0, 1));
        assertTrue("softness 2 blurs the edge: " + Integer.toHexString(soft[at]), (soft[at] & 255) > 0x20 + 8);
        final int[] hard = FilmEffects.grain(edge, w, h, new FilmSim.Settings(trix, FilmSim.print(""), FilmSim.format("135"), 0, 0, 0, 0, 0, 0, 1));
        assertArrayEquals("softness 0 and no grain: the picture as it was", edge, hard);
    }

    @Test
    public void filmExposureBrightensThePrint() {
        final float[] lo = FilmSim.apply(set(0, 0, 0, -1), .46f, .46f, .46f), mid = FilmSim.apply(set(0, 0, 0, 0), .46f, .46f, .46f),
                hi = FilmSim.apply(set(0, 0, 0, 1), .46f, .46f, .46f);
        assertTrue("-1 " + lo[1] + " 0 " + mid[1] + " +1 " + hi[1], lo[1] < mid[1] - .05f && hi[1] > mid[1] + .05f);
    }
}
