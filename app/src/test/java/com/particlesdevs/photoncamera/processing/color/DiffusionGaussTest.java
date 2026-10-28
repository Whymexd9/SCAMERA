package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P289: the Gaussian-cluster diffusion (the reference app's way) against the reference model's full-size convolution. */
public class DiffusionGaussTest {
    @Test
    public void matchesTheReferenceConvolution() {
        // a crop of a 12 MP photo's scale (the core 1 - 2 px, the bloom wider than the crop)
        final int w = 320, h = 240;
        for (String id : LensFilters.IDS) {
            final LensFilters.Tune tune = LensFilters.Tune.defaults(id);
            final double longPx = 4000;
            final DiffusionGauss.Plan plan = DiffusionGauss.plan(id, 1f, tune, w, h, longPx);
            assertNotNull(id, plan);
            assertTrue(id + " levels " + plan.top, plan.top >= 2);
            final float[] base = DiffusionFftTest.scene(w, h), mine = base.clone(), ref = base.clone();
            DiffusionGauss.apply(mine, w, h, plan);
            DiffusionFft.reference(ref, w, h, id, 1f, tune, longPx);
            double worst = 0, peak = 0, eMine = 0, eRef = 0, eBase = 0;
            for (int i = 0; i < base.length; i++) {
                eMine += mine[i];
                eRef += ref[i];
                eBase += base[i];
                final int px = i / 3, x = px % w, y = px / w;
                if (nearLight(x, y)) continue; // the core: the reference point-samples sub-pixel decays
                peak = Math.max(peak, ref[i] - base[i] * (1 - plan.p));
                worst = Math.max(worst, Math.abs(mine[i] - ref[i]));
            }
            System.out.println(id + " gauss glow error " + worst + " of peak " + peak + " energy " + eMine / eBase + " ref " + eRef / eBase);
            // the reference cuts the bloom at half the image and folds that energy back into the rest; the Gaussians keep it
            assertEquals(id + " energy kept", 1.0, eMine / eBase, 0.05);
            assertTrue(id + ": glow error " + worst + " of peak " + peak, worst < (LensFilters.CINEBLOOM.equals(id) ? 0.25 : 0.1) * peak);
        }
    }

    /**
     * P380: the lens filter's plan through the new overload (its strength's share, the 36 mm frame) is the plan as before, blur by
     * blur; the film's plan at IMAX 70 has every Gaussian at 36 / 70.41 of the lens filter's width (spektrafilm's pixel size).
     */
    /**
     * P380 review: the lens filter's plans of every family at 25 / 50 / 100 % as they were before P380 (hashes of p, the top level and
     * every blur's level, sigma and weights, taken from the code before it): the overloads the film's diffusion added keep them.
     */
    static final String[][] GOLDEN = {
            {"glimmer", "0.25", "1882133992344925853"},
            {"glimmer", "0.50", "-6126892540345085673"},
            {"glimmer", "1.00", "7377401307017079085"},
            {"bpm", "0.25", "-5580085289769385094"},
            {"bpm", "0.50", "6503697837032391954"},
            {"bpm", "1.00", "7962551453017064518"},
            {"pearl", "0.25", "-5376251782224529414"},
            {"pearl", "0.50", "342381403588339101"},
            {"pearl", "1.00", "6090726027662548556"},
            {"cinebloom", "0.25", "-3058448952726844796"},
            {"cinebloom", "0.50", "-2058900122120647856"},
            {"cinebloom", "1.00", "4942070641961814762"}};

    @Test
    public void theLensFiltersPlansAreTheOnesBeforeP380() {
        for (String[] g : GOLDEN) {
            final DiffusionGauss.Plan pl = DiffusionGauss.plan(g[0], Float.parseFloat(g[1]), LensFilters.Tune.defaults(g[0]), 4096, 3072, 4096);
            long h = Double.doubleToLongBits(pl.p) * 31 + pl.top;
            for (DiffusionGauss.Blur b : pl.blurs) {
                h = h * 31 + b.level;
                h = h * 31 + Float.floatToIntBits(b.sigma);
                for (float w : b.weight) h = h * 31 + Float.floatToIntBits(w);
            }
            assertEquals(g[0] + " at " + g[1], Long.parseLong(g[2]), h);
        }
    }

    @Test
    public void theLensFiltersPlanAndTheFilmsAtImax70() {
        final int w = 4096, h = 3072;
        for (String id : LensFilters.IDS) {
            final LensFilters.Tune tune = LensFilters.Tune.defaults(id);
            for (float st : new float[]{.25f, .5f, 1f}) {
                final DiffusionGauss.Plan before = DiffusionGauss.plan(id, st, tune, w, h, w);
                final DiffusionGauss.Plan now = DiffusionGauss.plan(id, DiffusionModel.scatter(id, st), tune, w, h, w, DiffusionModel.FRAME_UM);
                assertEquals(Double.doubleToLongBits(before.p), Double.doubleToLongBits(now.p));
                assertEquals(before.top, now.top);
                assertEquals(before.blurs.size(), now.blurs.size());
                for (int i = 0; i < before.blurs.size(); i++) {
                    assertEquals(before.blurs.get(i).level, now.blurs.get(i).level);
                    assertEquals(Float.floatToIntBits(before.blurs.get(i).sigma), Float.floatToIntBits(now.blurs.get(i).sigma));
                    assertArrayEquals(before.blurs.get(i).weight, now.blurs.get(i).weight, 0f);
                }
            }
            final double p = DiffusionModel.scatterAtGrade(id, 2.0);
            final DiffusionGauss.Plan lens = DiffusionGauss.plan(id, p, tune, w, h, w, DiffusionModel.FRAME_UM);
            final DiffusionGauss.Plan film = DiffusionGauss.plan(id, p, tune, w, h, w, 70410);
            assertEquals(lens.blurs.size(), film.blurs.size());
            for (int i = 0; i < lens.blurs.size(); i++) {
                final double a = width(lens.blurs.get(i)), b = width(film.blurs.get(i));
                assertEquals(id + " Gaussian " + i, a * 36000 / 70410, b, 1e-3 * a + 2e-3);
                assertArrayEquals(lens.blurs.get(i).weight, film.blurs.get(i).weight, 0f);
            }
            assertTrue(film.top <= lens.top);
        }
    }

    /** A plan Gaussian's width in full-size pixels (its residual on its level plus the box pyramid's own). */
    static double width(DiffusionGauss.Blur b) {
        final double s = b.sigma * (1 << b.level);
        return Math.sqrt(s * s + DiffusionGauss.boxVariance(b.level));
    }

    /**
     * P380: the film's diffusion (grade 2 on IMAX 70, the owner's pair's Pro-Mist 2.0 among them): the Gaussian plan (the GPU's,
     * on the CPU) against the FFT one and the reference's convolution, away from the lights' own cores (sub-pixel decays on this
     * frame: the reference point-samples them).
     */
    @Test
    public void theFilmsDiffusionMatchesTheReferenceConvolution() {
        final int w = 320, h = 240;
        final double longPx = 4000, frameUm = 70410;
        for (String id : LensFilters.IDS) {
            final LensFilters.Tune tune = LensFilters.Tune.defaults(id);
            final double p = DiffusionModel.scatterAtGrade(id, 2.0);
            final DiffusionGauss.Plan plan = DiffusionGauss.plan(id, p, tune, w, h, longPx, frameUm);
            assertNotNull(id, plan);
            final float[] base = DiffusionFftTest.scene(w, h), mine = base.clone(), ref = base.clone(), fft = base.clone();
            DiffusionGauss.apply(mine, w, h, plan);
            DiffusionFft.reference(ref, w, h, id, p, tune, longPx, frameUm);
            DiffusionFft.apply(fft, w, h, DiffusionFft.plan(id, p, tune, w, h, longPx, frameUm));
            double worst = 0, worstFft = 0, peak = 0, eMine = 0, eBase = 0;
            for (int i = 0; i < base.length; i++) {
                eMine += mine[i];
                eBase += base[i];
                final int px = i / 3, x = px % w, y = px / w;
                if (nearLight(x, y)) continue;
                peak = Math.max(peak, ref[i] - base[i] * (1 - plan.p));
                worst = Math.max(worst, Math.abs(mine[i] - fft[i]));
                worstFft = Math.max(worstFft, Math.abs(mine[i] - ref[i]));
            }
            System.out.println(id + " film IMAX 70: the Gaussian plan vs the FFT plan " + worst + ", vs the reference " + worstFft + " of peak " + peak);
            assertEquals(id + " energy kept", 1.0, eMine / eBase, 0.05);
            assertTrue(id + ": vs the FFT plan " + worst + " of peak " + peak, worst < (LensFilters.CINEBLOOM.equals(id) ? 0.25 : 0.1) * peak);
            assertTrue(id + ": vs the reference " + worstFft + " of peak " + peak, worstFft < (LensFilters.CINEBLOOM.equals(id) ? 0.25 : 0.2) * peak);
        }
    }

    static boolean nearLight(int x, int y) {
        final int[][] lights = {{60, 50}, {200, 120}, {250, 40}, {30, 200}};
        for (int[] l : lights) if (Math.abs(x - l[0]) <= 4 && Math.abs(y - l[1]) <= 4) return true;
        return x >= 116 && x <= 173 && y >= 146 && y <= 193; // the window's edge
    }

    @Test
    public void theFilterShowsOnASmallPreview() {
        // the settings' preview: a 360 x 240 sample with its own frame size - the glow must be there
        final int w = 360, h = 240;
        final float[] base = DiffusionFftTest.scene(320, 240), img = new float[w * h * 3];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) System.arraycopy(base, (y * 320 + Math.min(319, x)) * 3, img, (y * w + x) * 3, 3);
        for (String id : LensFilters.IDS) {
            final float[] a = img.clone();
            LensFilters.apply(a, w, h, id, .5f, LensFilters.Tune.defaults(id));
            final int near = (50 * w + 66) * 3; // six pixels from the light at (60, 50)
            assertTrue(id + " glows next to the light: " + a[near] + " vs " + img[near], a[near] > img[near] * 1.5f);
        }
    }
}
