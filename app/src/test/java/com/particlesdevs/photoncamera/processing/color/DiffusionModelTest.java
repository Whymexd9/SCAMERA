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

    /** P380: the film's diffusion at spektrafilm's grades - its Pro-Mist 2.0 (the owner's pair) and the default 0.5. */
    @Test public void theFilmsGradesAreSpektrafilmsShares() {
        // diffusion.py _strength_to_scatter at 3bb2c2d: pro_mist 2.0 -> 0.7875, 0.5 -> 0.3675; black_pro_mist 0.75 -> 0.3502
        assertEquals(0.7875, DiffusionModel.scatterAtGrade(LensFilters.PEARLESCENT, 2.0), 1e-12);
        assertEquals(0.3675, DiffusionModel.scatterAtGrade(LensFilters.PEARLESCENT, 0.5), 1e-12);
        assertEquals(0.4903421251514429, DiffusionModel.scatterAtGrade(LensFilters.PEARLESCENT, 0.75), 1e-12);
        assertEquals(0.35024437510817347, DiffusionModel.scatterAtGrade(LensFilters.BLACK_PRO_MIST, 0.75), 1e-12);
        assertEquals(0.5625, DiffusionModel.scatterAtGrade(LensFilters.BLACK_PRO_MIST, 2.0), 1e-12);
        assertEquals(0.0, DiffusionModel.scatterAtGrade(LensFilters.PEARLESCENT, 0), 0);
        assertEquals(0.0, DiffusionModel.scatterAtGrade("", 2.0), 0);
        // the lens filter's strength is its grade: the same share, bit for bit
        for (String id : LensFilters.IDS)
            for (float st = 0f; st <= 1.0001f; st += 1 / 64f)
                assertEquals(id + " " + st, Double.doubleToLongBits(DiffusionModel.scatterAtGrade(id, DiffusionModel.grade(st))),
                        Double.doubleToLongBits(DiffusionModel.scatter(id, st)));
    }

    /** P380: the lens filter's shape on its 36 mm frame as before (the old formula), the film's on its format's frame. */
    @Test public void theFrameSizesThePsf() {
        for (String id : LensFilters.IDS) {
            final LensFilters.Tune t = LensFilters.Tune.defaults(id);
            t.radius = .63f;
            final DiffusionModel.Family f = DiffusionModel.family(id);
            final DiffusionModel.Shape now = DiffusionModel.shape(f, t, 4096), at = DiffusionModel.shape(f, t, 4096, DiffusionModel.FRAME_UM);
            // today's formula: px = longPx / 36000 x size, every group's lambda times it
            final double px = 4096 / 36000.0 * Math.pow(2.0, (t.radius - 0.5) * 2.0);
            final double[][][] groups = {DiffusionModel.expand(f.core, false), DiffusionModel.expand(f.halo, false), DiffusionModel.expand(f.bloom, true)};
            final double[][][] mine = {now.core, now.halo, now.bloom}, again = {at.core, at.halo, at.bloom};
            for (int g = 0; g < 3; g++) for (int k = 0; k < groups[g][0].length; k++) {
                assertEquals(id, Double.doubleToLongBits(groups[g][0][k] * px), Double.doubleToLongBits(mine[g][0][k]));
                assertEquals(id, Double.doubleToLongBits(mine[g][0][k]), Double.doubleToLongBits(again[g][0][k]));
                assertEquals(id, Double.doubleToLongBits(mine[g][1][k]), Double.doubleToLongBits(again[g][1][k]));
            }
            assertEquals(Double.doubleToLongBits(now.warmth), Double.doubleToLongBits(at.warmth));
        }
        // IMAX 70 (70.41 mm) on a 4096 px photo: Pro-Mist's halo centre (150 um) at 8.73 px, half its lens filter's 17.07 px
        final DiffusionModel.Shape imax = DiffusionModel.shape(DiffusionModel.family(LensFilters.PEARLESCENT),
                LensFilters.Tune.defaults(LensFilters.PEARLESCENT), 4096, 70410);
        assertEquals(8.73, imax.halo[0][1], 0.005);
        assertEquals(150 * 4096 / 36000.0, DiffusionModel.shape(DiffusionModel.family(LensFilters.PEARLESCENT),
                LensFilters.Tune.defaults(LensFilters.PEARLESCENT), 4096).halo[0][1], 1e-9);
    }

    /**
     * P380: the PSF's radial profile against spektrafilm's diffusion_filter_radial_profile (3bb2c2d, 'total_per_channel', per um²,
     * the families at their own warmth) at 0 .. 3000 um.
     */
    @Test public void theRadialProfileIsSpektrafilms() {
        final double[] r = {0.0, 5.0, 20.0, 75.0, 150.0, 400.0, 1000.0, 3000.0};
        final String[] ids = {LensFilters.GLIMMERGLASS, LensFilters.BLACK_PRO_MIST, LensFilters.PEARLESCENT, LensFilters.CINEBLOOM};
        final double[][][] ref = {
                {{0.001320865359464584, 0.0006885364796365714, 0.0001272223918478813, 5.368965810474629e-06, 1.0003831071941438e-06, 6.916123620127607e-08, 2.1884887941352516e-09, 2.6001092111297847e-11},
                 {0.001320865359464584, 0.0006885364796365714, 0.0001272223918478813, 5.368965810474629e-06, 1.0003831071941438e-06, 6.916123620127607e-08, 2.1884887941352516e-09, 2.6001092111297847e-11},
                 {0.001320865359464584, 0.0006885364796365714, 0.0001272223918478813, 5.368965810474629e-06, 1.0003831071941438e-06, 6.916123620127607e-08, 2.1884887941352516e-09, 2.6001092111297847e-11}},
                {{0.0003414017644595338, 0.0002259516332706309, 7.198195250833786e-05, 5.535632321993542e-06, 1.578142967310135e-06, 2.559916495553707e-07, 1.0685108448549736e-08, 6.284694644547075e-11},
                 {0.0003491462395816612, 0.00023288416448974045, 7.693924331504315e-05, 6.891026526836188e-06, 1.6949083374957349e-06, 1.9491836265905552e-07, 8.011244328018885e-09, 6.277522988762043e-11},
                 {0.0003599211614907081, 0.00024252942531632763, 8.383634356785055e-05, 8.776792377052044e-06, 1.8573645047104821e-06, 1.099468330641822e-07, 4.291085551628133e-09, 6.267545032887214e-11}},
                {{0.0003099825760526435, 0.00019302284628802173, 5.255429378638086e-05, 3.4076908167651763e-06, 1.1300174329952496e-06, 2.8157656459602065e-07, 3.296825644656732e-08, 7.141667201858253e-10},
                 {0.00031169083910849655, 0.0001946154759137085, 5.384338854109589e-05, 3.989328225310919e-06, 1.3075439872535756e-06, 2.6035425026854124e-07, 2.8908503264433563e-08, 7.089963853357281e-10},
                 {0.0003140675529253355, 0.00019683130843640317, 5.5636911678090714e-05, 4.798562880678908e-06, 1.5545374540477687e-06, 2.3082755207378726e-07, 2.326015101103008e-08, 7.018028759790711e-10}},
                {{0.0001187432295984966, 8.49475846260094e-05, 3.2717868669064955e-05, 2.576222786636433e-06, 6.162531410876563e-07, 2.2232702177057446e-07, 4.9025694581579313e-08, 2.3411267847077333e-09},
                 {0.00012005515864309797, 8.619257722837768e-05, 3.3781198752259836e-05, 3.1666718129947046e-06, 8.704922671419978e-07, 2.1641614254302113e-07, 4.1779192329222637e-08, 2.2912687066350722e-09},
                 {0.00012182333117220636, 8.786974918798415e-05, 3.521153142595907e-05, 3.95574533047845e-06, 1.2058346194666614e-06, 2.062472900471813e-07, 3.24668275088118e-08, 2.229229051977994e-09}}};
        for (int f = 0; f < ids.length; f++) {
            // a frame as long in pixels as in micrometres: one pixel one micrometre
            final DiffusionModel.Shape s = DiffusionModel.shape(DiffusionModel.family(ids[f]), LensFilters.Tune.defaults(ids[f]), 1000, 1000);
            for (int c = 0; c < 3; c++) for (int i = 0; i < r.length; i++)
                assertEquals(ids[f] + " c" + c + " at " + r[i] + " um", ref[f][c][i], DiffusionModel.density(s, c, r[i]), ref[f][c][i] * 1e-9);
        }
    }

    /** P380: a lens filter and the film's diffusion in a row on the viewfinder: the levels fitted to the two PSFs convolved. */
    @Test public void twoDiffusionsCombine() {
        final int longPx = 2400;
        final double[] sig = DiffusionModel.mipSigmas(11);
        final float[][] lens = DiffusionModel.levelWeights(LensFilters.BLACK_PRO_MIST, .5f, LensFilters.Tune.defaults(LensFilters.BLACK_PRO_MIST), longPx, sig);
        final float[][] film = DiffusionModel.levelWeights(LensFilters.PEARLESCENT, DiffusionModel.scatterAtGrade(LensFilters.PEARLESCENT, 2.0),
                LensFilters.Tune.defaults(LensFilters.PEARLESCENT), longPx, 70410, sig);
        assertTrue(lens != null && film != null);
        final float[][] both = DiffusionModel.combine(lens, film, sig, longPx);
        assertTrue("either alone: the other's", DiffusionModel.combine(null, film, sig, longPx) == film && DiffusionModel.combine(lens, null, sig, longPx) == lens);
        // the combined PSF's encircled energy (each pair of levels a Gaussian of their widths in quadrature) against the fit
        final double[] radii = DiffusionModel.radii(longPx, 48);
        for (int c = 0; c < 3; c++) {
            double sum = 0;
            for (float[] k : both) { assertTrue(k[c] >= 0f); sum += k[c]; }
            assertEquals("energy kept", 1.0, sum, 1e-5);
            final double[] target = DiffusionModel.combinedEe(lens, film, sig, c, radii);
            for (int i = 0; i < radii.length; i++) {
                double fit = 0;
                for (int k = 0; k < sig.length; k++) fit += both[k][c] * DiffusionModel.eeGauss(radii[i], sig[k]);
                assertEquals("c" + c + " encircled energy at " + radii[i] + " px", target[i], fit, 0.01);
            }
            // more scattered than either alone: less energy kept within 4 px
            double e4 = 0, l4 = 0, f4 = 0;
            for (int k = 0; k < sig.length; k++) {
                e4 += both[k][c] * DiffusionModel.eeGauss(4, sig[k]);
                l4 += lens[k][c] * DiffusionModel.eeGauss(4, sig[k]);
                f4 += film[k][c] * DiffusionModel.eeGauss(4, sig[k]);
            }
            assertTrue("c" + c + " within 4 px " + e4 + " vs " + l4 + " / " + f4, e4 < Math.min(l4, f4));
        }
    }

    @Test public void theSameShareOfTheFrameAtAnySize() {
        LensFilters.Tune t = LensFilters.Tune.defaults(LensFilters.BLACK_PRO_MIST);
        DiffusionModel.Shape small = DiffusionModel.shape(DiffusionModel.family(LensFilters.BLACK_PRO_MIST), t, 1000);
        DiffusionModel.Shape large = DiffusionModel.shape(DiffusionModel.family(LensFilters.BLACK_PRO_MIST), t, 4000);
        assertEquals(DiffusionModel.ee(small, 1, 25), DiffusionModel.ee(large, 1, 100), 1e-9);
    }
}
