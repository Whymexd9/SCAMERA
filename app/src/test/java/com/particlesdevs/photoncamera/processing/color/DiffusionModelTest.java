package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Owner, 2026-10-18: the diffusion filters as a physical model - energy kept, the PSF fitted by the pyramid's levels. */
public class DiffusionModelTest {
    @Test public void theGradeTableAndTheFamilies() {
        assertEquals(0.0, DiffusionModel.scatter(LensFilters.BLACK_PRO_MIST, 0f), 0);
        assertEquals("grade 1/2 (our 50 %)", 0.35 * 0.75, DiffusionModel.scatter(LensFilters.BLACK_PRO_MIST, 0.5f), 1e-9);
        assertEquals("grade 2 (100 %)", 0.75 * 1.00, DiffusionModel.scatter(LensFilters.CINEBLOOM, 1f), 1e-9);
        assertTrue(DiffusionModel.scatter(LensFilters.GLIMMERGLASS, 0.5f) < DiffusionModel.scatter(LensFilters.PEARLESCENT, 0.5f));
        for (String id : new String[]{LensFilters.BLACK_PRO_MIST, LensFilters.CINEBLOOM, LensFilters.GLIMMERGLASS,
                LensFilters.PEARLESCENT, LensFilters.FOG, LensFilters.DOUBLE_FOG})
            assertTrue(id, DiffusionModel.diffuses(id));
        for (String id : new String[]{LensFilters.GND, LensFilters.STREAK, LensFilters.PRISM, ""})
            assertFalse(id, DiffusionModel.diffuses(id));
    }

    @Test public void theWarmHaloKeepsItsEnergy() {
        double[] w = {1 / 3.0, 1 / 3.0, 1 / 3.0};
        for (int c = 0; c < 3; c++) {
            double[] h = DiffusionModel.haloWeights(w, 1.2, c);
            assertEquals(1.0, h[0] + h[1] + h[2], 1e-12);
        }
        assertTrue("red to the rim", DiffusionModel.haloWeights(w, 1, 0)[2] > w[2]);
        assertTrue("blue to the core", DiffusionModel.haloWeights(w, 1, 2)[0] > w[0]);
    }

    @Test public void theLevelsFitThePsf() {
        for (String id : new String[]{LensFilters.BLACK_PRO_MIST, LensFilters.CINEBLOOM, LensFilters.GLIMMERGLASS, LensFilters.FOG}) {
            LensFilters.Tune t = LensFilters.Tune.defaults(id);
            double[] sig = DiffusionModel.pyramidSigmas(11, 1.0);
            float[][] w = DiffusionModel.levelWeights(id, 0.5f, t, 4096, sig);
            double p = DiffusionModel.scatter(id, 0.5f);
            DiffusionModel.Shape s = DiffusionModel.shape(DiffusionModel.family(id), t, 4096);
            for (int c = 0; c < 3; c++) {
                double sum = 0;
                for (float[] k : w) { assertTrue(k[c] >= 0f); sum += k[c]; }
                assertEquals(id + " energy", 1.0, sum, 1e-5);
                for (double r = 0.5; r < 4096; r *= 1.3) {
                    double target = (1 - p) + p * DiffusionModel.ee(s, c, r), fit = 0;
                    for (int k = 0; k < sig.length; k++) fit += w[k][c] * DiffusionModel.eeGauss(r, sig[k]);
                    assertEquals(id + " encircled energy at " + r + " px", target, fit, 0.01);
                }
            }
        }
        assertNull(DiffusionModel.levelWeights(LensFilters.STREAK, 0.5f, LensFilters.Tune.defaults(LensFilters.STREAK), 4096,
                DiffusionModel.pyramidSigmas(11, 1.0)));
    }

    @Test public void theSameShareOfTheFrameAtAnySize() {
        LensFilters.Tune t = LensFilters.Tune.defaults(LensFilters.BLACK_PRO_MIST);
        DiffusionModel.Shape small = DiffusionModel.shape(DiffusionModel.family(LensFilters.BLACK_PRO_MIST), t, 1000);
        DiffusionModel.Shape large = DiffusionModel.shape(DiffusionModel.family(LensFilters.BLACK_PRO_MIST), t, 4000);
        assertEquals(DiffusionModel.ee(small, 1, 25), DiffusionModel.ee(large, 1, 100), 1e-9);
    }
}
