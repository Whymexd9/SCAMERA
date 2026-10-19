package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/** P288: the FFT diffusion - the Stockham passes, and the level plan against the reference's single full-size convolution. */
public class DiffusionFftTest {
    @Test
    public void stockhamMatchesTheDft() {
        final int n = 64;
        final Random rnd = new Random(3);
        final double[] re = new double[n], im = new double[n], r0 = new double[n], i0 = new double[n];
        for (int i = 0; i < n; i++) { re[i] = r0[i] = rnd.nextGaussian(); im[i] = i0[i] = rnd.nextGaussian(); }
        DiffusionFft.fft(re, im, 0, 1, n, -1, new double[n], new double[n], new double[n], new double[n]);
        for (int k = 0; k < n; k++) {
            double sr = 0, si = 0;
            for (int t = 0; t < n; t++) {
                final double a = -2 * Math.PI * k * t / n;
                sr += r0[t] * Math.cos(a) - i0[t] * Math.sin(a);
                si += r0[t] * Math.sin(a) + i0[t] * Math.cos(a);
            }
            assertEquals(sr, re[k], 1e-9);
            assertEquals(si, im[k], 1e-9);
        }
        DiffusionFft.fft(re, im, 0, 1, n, 1, new double[n], new double[n], new double[n], new double[n]);
        for (int i = 0; i < n; i++) assertEquals(r0[i], re[i] / n, 1e-9);
    }

    /** A night scene: dim texture, a few point lights and a bright window. */
    static float[] scene(int w, int h) {
        final Random rnd = new Random(11);
        final float[] a = new float[w * h * 3];
        for (int i = 0; i < a.length; i++) a[i] = 0.02f + 0.03f * rnd.nextFloat();
        final int[][] lights = {{60, 50}, {200, 120}, {250, 40}, {30, 200}};
        for (int[] l : lights) for (int c = 0; c < 3; c++) a[(l[1] * w + l[0]) * 3 + c] = c == 0 ? 400f : c == 1 ? 300f : 150f;
        for (int y = 150; y < 190; y++) for (int x = 120; x < 170; x++) for (int c = 0; c < 3; c++) a[(y * w + x) * 3 + c] = 4f;
        return a;
    }

    @Test
    public void theLevelPlanMatchesTheReferenceConvolution() {
        final int w = 320, h = 240;
        for (String id : LensFilters.IDS) {
            final LensFilters.Tune tune = LensFilters.Tune.defaults(id);
            // a frame as long as a 12 MP photo's: the PSF spreads over several levels (core on the image, bloom levels up)
            final double longPx = 4000;
            final DiffusionFft.Plan plan = DiffusionFft.plan(id, 1f, tune, w, h, longPx);
            assertNotNull(id, plan);
            int used = 0;
            for (DiffusionFft.Tier t : plan.tiers) if (!t.empty()) used++;
            assertTrue(id + " levels used " + used, used >= 2);
            final float[] base = scene(w, h), mine = base.clone(), ref = base.clone();
            DiffusionFft.apply(mine, w, h, plan);
            DiffusionFft.reference(ref, w, h, id, 1f, tune, longPx);
            // compare the glow (the scattered light) away from the lights' own pixels
            double worst = 0, peak = 0, energyMine = 0, energyRef = 0;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 3; c++) {
                final int i = (y * w + x) * 3 + c;
                energyMine += mine[i];
                energyRef += ref[i];
                if (base[i] > 1f) continue;
                peak = Math.max(peak, ref[i] - base[i] * (1 - plan.p));
                worst = Math.max(worst, Math.abs(mine[i] - ref[i]));
            }
            assertEquals(id + " energy", 1.0, energyMine / energyRef, 0.01);
            System.out.println(id + " glow error " + worst + " of peak " + peak + " used " + used);
            assertTrue(id + ": glow error " + worst + " of peak " + peak, worst < 0.02 * peak);
        }
    }

    @Test
    public void bigImagesAreTiled() {
        final DiffusionFft.Plan plan = DiffusionFft.plan(LensFilters.CINEBLOOM, .5f, LensFilters.Tune.defaults(LensFilters.CINEBLOOM),
                8160, 6144, 8160);
        assertNotNull(plan);
        final DiffusionFft.Tier t0 = plan.tiers[0];
        assertTrue("level 0 tiled", t0.tilesX * t0.tilesY > 1 && t0.grid == DiffusionFft.GRID);
        for (DiffusionFft.Tier t : plan.tiers) assertTrue("grid " + t.grid, t.grid <= DiffusionFft.GRID);
    }
}
