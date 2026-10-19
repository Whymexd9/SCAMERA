package com.particlesdevs.photoncamera.processing.color;

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
