package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.util.Pair;

import com.particlesdevs.photoncamera.BuildConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Random;

/**
 * P214c (quality.md 4): the DNG's Software tag names this APK, and the merged DNG's NoiseProfile describes what the file
 * holds - the merge's level over the headroom H, averaged over the N_eff frames the worker merged:
 * S' = S / (H N_eff), O' = O / (H^2 N_eff) (before: the one-frame model, H N_eff times too much noise).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DngMetadataTest {
    // the owner's SCA_20261017_153558.dng: NoiseProfile (0.00022342, 3.785e-07) x3, BaselineExposure 4424491/2097152 EV
    private static final double S = 0.00022342, O = 3.785e-07;
    private static final double H = Math.pow(2, 4424491.0 / 2097152.0); // 4.32
    // the OPPO Find X7 Ultra's 50 MP native merge of log-2026-10-17 23:10 (13 of 17 frames merged)
    private static final String FACTORS = "HYBRID MERGE FACTORS: 1:0.32 2:0.30 3:0.33 4:0.33 5:0.29 6:0.54 7:0.51 8:0.54 9:0.58 "
            + "10:0.57 11:0.00 12:0.32  average=0.386570";

    private static double[] owner() { return new double[]{S, O, S, O, S, O}; }

    @Test public void mergedProfileIsTheModelOverHeadroomAndFrames() {
        final double n = 5.63884;
        final double[] p = DngMetadata.noiseProfile(owner(), 1, H, n, 1);
        for (int c = 0; c < 3; c++) {
            assertEquals(S / (H * n), p[2 * c], 1e-15);
            assertEquals(O / (H * H * n), p[2 * c + 1], 1e-18);
        }
        // the old tag was H N_eff times too high in slope, H^2 N_eff in offset
        assertEquals(H * n, S / p[0], 1e-9);
        assertEquals(H * H * n, O / p[1], 1e-9);
        assertTrue(H > 4.31 && H < 4.33);
    }

    @Test public void planesKeepTheirOwnModel() {
        final double[] single = {2e-4, 4e-7, 1.5e-4, 3e-7, 2.5e-4, 5e-7};
        final double[] p = DngMetadata.noiseProfile(single, 1, 2, 8, 1);
        assertArrayEquals(new double[]{2e-4 / 16, 4e-7 / 32, 1.5e-4 / 16, 3e-7 / 32, 2.5e-4 / 16, 5e-7 / 32}, p, 1e-18);
    }

    @Test public void noiseFactorAndBinnedSitesScaleBothTerms() {
        // the worker's measured model correction (HYBRID NOISE CHECK "-> model x0.0625") and a DNG binned by 2 (4 sites a site)
        final double[] p = DngMetadata.noiseProfile(owner(), 0.0625, H, 6, 4);
        assertEquals(0.0625 * S / (H * 6 * 4), p[0], 1e-16);
        assertEquals(0.0625 * O / (H * H * 6 * 4), p[1], 1e-19);
    }

    @Test public void oneFrameAsItCameKeepsTheSensorModel() {
        assertArrayEquals(owner(), DngMetadata.noiseProfile(owner(), 1, 1, 1, 1), 0);
    }

    @Test public void implausibleInputsCountAsOne() {
        final double[] ref = DngMetadata.noiseProfile(owner(), 1, 1, 1, 1);
        assertArrayEquals(ref, DngMetadata.noiseProfile(owner(), Double.NaN, 0.5, Double.NaN, 0), 0);
        assertArrayEquals(ref, DngMetadata.noiseProfile(owner(), 10, Double.POSITIVE_INFINITY, -3, Double.NaN), 0);
        assertArrayEquals(ref, DngMetadata.noiseProfile(owner(), 0.01, 1, 1, 1), 0);
        try {
            DngMetadata.noiseProfile(new double[4], 1, 1, 1, 1);
            throw new AssertionError("a model without 3 pairs was accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    /**
     * The derivation, not only the arithmetic: N frames of one site, each with var = S x + O (normalised), averaged and written
     * over the headroom as the worker does (m = x_N / H): the measured variance of m at its level is what the profile says.
     */
    @Test public void profileMatchesTheVarianceOfASimulatedMergedSite() {
        final Random random = new Random(214);
        final int frames = 6, samples = 200_000;
        final double h = 3.0;
        final double[] p = DngMetadata.noiseProfile(owner(), 1, h, frames, 1);
        for (double x : new double[]{0.02, 0.2, 0.8}) {
            final double sigma = Math.sqrt(S * x + O);
            double sum = 0, sum2 = 0;
            for (int i = 0; i < samples; i++) {
                double mean = 0;
                for (int f = 0; f < frames; f++) mean += x + sigma * random.nextGaussian();
                final double m = mean / frames / h;
                sum += m;
                sum2 += m * m;
            }
            final double level = sum / samples, variance = sum2 / samples - level * level;
            final double predicted = p[0] * level + p[1];
            assertEquals(x / h, level, 1e-4);
            assertEquals("variance at x=" + x, predicted, variance, predicted * 0.02);
            // the one-frame model the tag held before overstates it H N times (at the merged level m)
            assertTrue(S * level + O > 15 * variance);
        }
    }

    @Test public void mergeFramesIsTheBasePlusEveryDonorsShare() {
        assertEquals(1 + 12 * 0.386570, DngMetadata.mergeFrames("HYBRID OUTPUT: Sabre 2x grid 8192x6144\n" + FACTORS
                + "\nHYBRID EFFECTIVE MAP: median frames=4.287422 code scale=14.927384\n"), 1e-9);
        // without the average: the tokens (2 decimals)
        assertEquals(1 + 0.32 + 0.30 + 0.5, DngMetadata.mergeFrames("HYBRID MERGE FACTORS: 1:0.32 2:0.30 7:0.50"), 1e-9);
        // shares are 0..1 (robustShare): anything else is clamped
        assertEquals(1 + 1 + 0, DngMetadata.mergeFrames("HYBRID MERGE FACTORS: 1:1.70 2:-0.20"), 1e-9);
        // one frame merged: no donors
        assertEquals(1, DngMetadata.mergeFrames("HYBRID MERGE FACTORS:\nSCAM CAPTURE OK\n"), 0);
    }

    @Test public void mergeFramesWithoutTheLineIsUnknown() {
        assertTrue(Double.isNaN(DngMetadata.mergeFrames(null)));
        assertTrue(Double.isNaN(DngMetadata.mergeFrames("")));
        // the effective-frame map's median is the Sabre kernel's weight sum, not frames: never read as N_eff
        assertTrue(Double.isNaN(DngMetadata.mergeFrames("HYBRID EFFECTIVE MAP: median frames=71.629272 code scale=0.893489\n")));
    }

    @Test public void mergeFramesReadsOnlyThisAttemptsLastMerge() {
        // a retried job keeps the failed first attempt above "RETRY:" (ScamNeuralClient): its merge is not this DNG's
        final String previous = "SCAMERA: neural inference job\nPREVIOUS ATTEMPT:\n" + FACTORS + "\nWORKER EXIT: signal 9\nRETRY:\n";
        assertTrue(Double.isNaN(DngMetadata.mergeFrames(previous + "HYBRID DNG: merged Bayer 8192x6144\n")));
        assertEquals(1 + 0.5, DngMetadata.mergeFrames(previous + "HYBRID MERGE FACTORS: 1:0.50  average=0.500000\n"), 1e-9);
        // two merges in one attempt: the last one wrote the DNG
        assertEquals(1 + 2 * 0.25, DngMetadata.mergeFrames(FACTORS + "\nHYBRID DNG: merged Bayer 4096x3072\n"
                + "HYBRID MERGE FACTORS: 1:0.20 2:0.30  average=0.250000\n"), 1e-9);
    }

    /**
     * The sub-frame split (hybridReconstructMosaic: mosaicPath 0, Tetra with mosaicTetra 0, the fallback of a failed native
     * merge) merges the b^2 sub-frames of each frame as frames of their own: its shares count sub-frames, / b^2 frames.
     */
    @Test public void subFrameSplitCountsFramesNotSubFrames() {
        // 2 Quad frames = 8 sub-frames: the base's other 3 sub-frames at 0.9, the donor's 4 at 0.5 -> (1 + 2.7 + 2) / 4 frames
        final String split = "HYBRID MOSAIC: binned alignment 210 ms; merging 8 sub-frames on their 2x grid -> 8192x6144\n"
                + "HYBRID OUTPUT: Sabre 2x grid 8192x6144 (kernel in sensor px)\n"
                + "HYBRID MERGE FACTORS: 1:0.90 2:0.90 3:0.90 4:0.50 5:0.50 6:0.50 7:0.50  average=0.671429\n";
        assertEquals((1 + 7 * 0.671429) / 4, DngMetadata.mergeFrames(split), 1e-9);
        // the fallback of a failed native merge: the native line before it is not this DNG's merge
        assertEquals((1 + 7 * 0.671429) / 4, DngMetadata.mergeFrames(FACTORS + "\nHYBRID MOSAIC NATIVE: failed (x); merging on the "
                + "sub-frame split (mosaicPath 0) instead\n" + split), 1e-9);
        // Tetra: 16 sub-frames a frame (2 frames, 31 donor sub-frames at 0.6)
        final StringBuilder tetra = new StringBuilder("HYBRID MERGE FACTORS:");
        for (int i = 1; i < 32; i++) tetra.append(' ').append(i).append(":0.60");
        assertEquals((1 + 31 * 0.6) / 16, DngMetadata.mergeFrames("HYBRID MOSAIC: binned alignment 300 ms; merging 32 sub-frames "
                + "on their 4x grid -> 16384x12288\n" + tetra + "  average=0.600000\n"), 1e-9);
        // a split line of an earlier merge (or of the failed first attempt) does not apply to a later plain merge
        assertEquals(1 + 0.4, DngMetadata.mergeFrames(split + "HYBRID MERGE FACTORS: 1:0.40  average=0.400000\n"), 1e-9);
        assertEquals(1 + 0.4, DngMetadata.mergeFrames("PREVIOUS ATTEMPT:\n" + split.replace("HYBRID MERGE FACTORS", "WORKER EXIT")
                + "\nRETRY:\nHYBRID MERGE FACTORS: 1:0.40  average=0.400000\n"), 1e-9);
        // never below one frame
        assertEquals(1, DngMetadata.mergeFrames("HYBRID MOSAIC: binned alignment 1 ms; merging 4 sub-frames on their 2x grid -> 8x8\n"
                + "HYBRID MERGE FACTORS: 1:0.00 2:0.00 3:0.00  average=0.000000\n"), 0);
    }

    @Test public void planesOfANoiseModelerModel() {
        @SuppressWarnings("unchecked")
        final Pair<Double, Double>[] model = new Pair[]{new Pair<>(2e-4, 4e-7), new Pair<>(1.5e-4, 3e-7), new Pair<>(2.5e-4, 5e-7)};
        assertArrayEquals(new double[]{2e-4, 4e-7, 1.5e-4, 3e-7, 2.5e-4, 5e-7}, DngMetadata.planes(model), 0);
        assertNull(DngMetadata.planes(null));
        @SuppressWarnings("unchecked")
        final Pair<Double, Double>[] incomplete = new Pair[]{new Pair<>(2e-4, 4e-7), null, new Pair<>(2.5e-4, 5e-7)};
        assertNull(DngMetadata.planes(incomplete));
    }

    /**
     * P320: a DCG shot with measured zones writes the one curve a DNG plane can hold on purpose: (S, O_hcg), exact in the
     * shadows, with the model's channel ratios and stacking and then the same frames / headroom handling; O_lcg never enters.
     */
    @Test public void dcgPlanesAreTheSlopeAndTheHighGainOffset() {
        final float[] zones = {2e-4f, 1e-6f, 4e-5f, 0.125f};
        final double s = zones[0], oh = zones[1];
        // the camera's one-frame model: R 2x the green slope and offset, B 1.5x the slope
        final double[] model = {4e-4, 2e-6, 2e-4, 1e-6, 3e-4, 1e-6};
        double[] p = DngMetadata.dcgPlanes(zones, model, 2e-4, 1e-6);
        final double[] expected = {s * 2, oh * 2, s, oh, s * 1.5, oh};
        for (int i = 0; i < 6; i++) assertEquals("plane term " + i, expected[i], p[i], 1e-9 * expected[i]);
        // the legacy stacked model (a quarter of the one frame) carries its scale over the same one-frame green
        final double[] stacked = new double[6];
        for (int i = 0; i < 6; i++) stacked[i] = model[i] / 4;
        p = DngMetadata.dcgPlanes(zones, stacked, 2e-4, 1e-6);
        for (int i = 0; i < 6; i++) assertEquals(expected[i] / 4, p[i], 1e-9 * expected[i]);
        // no camera model: every plane the measured green
        p = DngMetadata.dcgPlanes(zones, null, Double.NaN, Double.NaN);
        assertArrayEquals(new double[]{s, oh, s, oh, s, oh}, p, 0);
        assertArrayEquals(p, DngMetadata.dcgPlanes(zones, model, 0, -1), 0);
        // then the merged file's handling: S / (H N), O / (H^2 N)
        final double[] merged = DngMetadata.noiseProfile(DngMetadata.dcgPlanes(zones, model, 2e-4, 1e-6), 1, 2, 5, 1);
        assertEquals(s * 2 / 10, merged[0], 1e-9 * merged[0]);
        assertEquals(oh * 2 / 20, merged[1], 1e-9 * merged[1]);
        for (int c = 0; c < 3; c++) assertTrue("the low-gain offset is not written", merged[2 * c + 1] < zones[2] / 20);
        try {
            DngMetadata.dcgPlanes(null, model, 2e-4, 1e-6);
            throw new AssertionError("no zones");
        } catch (IllegalArgumentException expectedError) {
            // the caller checks NoiseModeler.dcgZones first
        }
    }

    @Test public void softwareNamesThisApk() {
        assertEquals("SCAMERA 0.98 (30966) cab4480a", DngMetadata.software("0.98", 30966, "cab4480a96a2f1e0", false));
        assertEquals("SCAMERA 0.98 (30966) cab4480a+dirty", DngMetadata.software("0.98", 30966, "cab4480a", true));
        assertEquals("SCAMERA 0.98 (30966) unknown", DngMetadata.software("0.98", 30966, null, false));
        // this build: the app's BuildConfig (circleimageview's had no version name and versionCode -1: "SCAMERA  (-1)")
        final String software = DngMetadata.software();
        assertTrue(software, software.startsWith("SCAMERA " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") "));
        assertTrue(BuildConfig.VERSION_CODE > 0);
        assertFalse(software, software.contains("(-1)"));
        assertFalse(software, software.contains("  "));
    }
}
