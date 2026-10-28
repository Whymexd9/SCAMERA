package com.particlesdevs.photoncamera.processing.color;

import com.particlesdevs.photoncamera.processing.render.Converter;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * P243 («DNG colour = JPEG»): the DNG's ColorMatrix1 / ForwardMatrix1 from the JPEG's render matrices give, through the DNG
 * colour model (XYZ_D50 = FM · diag(1 / AsShotNeutral) · c, then the render's XYZ -> sRGB), the JPEG's own linear colour.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DngColourTest {
    private static final float[] NEUTRAL = {0.52f, 1f, 0.71f}; // a daylight-ish camera neutral (R/G, 1, B/G)

    /** The JPEG's linear sRGB of camera value c: intermediateToSRGB · sensorToProPhoto · c (why/low.glsl). */
    private static double[] jpeg(float[] s2p, float[] i2s, double[] c) {
        return DngColour.map(DngColour.mul(DngColour.toDouble(i2s), DngColour.toDouble(s2p)), c);
    }

    /** What a raw converter renders from the tags: sRGB(D50) · FM · diag(1 / n) · c. */
    private static double[] dng(DngColour.Profile p, float[] n, double[] c) {
        final double[] fm = DngColour.toDouble(p.forwardMatrix1);
        final double[] xyz = DngColour.map(DngColour.mul(fm, DngColour.diag(1 / n[0], 1 / n[1], 1 / n[2])), c);
        return DngColour.map(DngColour.XYZ_TO_SRGB_D50, xyz);
    }

    private static void assertSameColour(double[] a, double[] b, double tol) {
        // the converter normalises the brightness (FM scaled to Y = 1): the same colour up to one factor
        final double ka = a[0] + a[1] + a[2], kb = b[0] + b[1] + b[2];
        for (int i = 0; i < 3; i++) assertEquals(a[i] / ka, b[i] / kb, tol);
    }

    @Test public void theIspMatrixCaseRendersTheJpegsColour() {
        // vivo: sensorToProPhoto = diag(1 / n), intermediateToSRGB = the ISP's colour transform with rows summing to 1
        final float[] s2p = {1 / NEUTRAL[0], 0, 0, 0, 1 / NEUTRAL[1], 0, 0, 0, 1 / NEUTRAL[2]};
        final float[] i2s = {1.62f, -0.48f, -0.14f, -0.21f, 1.39f, -0.18f, 0.02f, -0.55f, 1.53f};
        final DngColour.Profile p = DngColour.jpegProfile(s2p, i2s, NEUTRAL, 5200f);
        assertNotNull(p);
        assertEquals("white stays D50", 0.3457, p.whiteX, 2e-3);
        assertEquals(0.3585, p.whiteY, 2e-3);
        for (double[] c : new double[][]{{0.2, 0.5, 0.1}, {0.05, 0.02, 0.3}, {0.52, 1, 0.71}, {0.9, 0.3, 0.2}})
            assertSameColour(jpeg(s2p, i2s, c), dng(p, NEUTRAL, c), 1e-5);
        assertEquals(DngColour.ILLUMINANT_LABEL, p.illuminant);
    }

    /**
     * P243 / P370: the profile's ColorMatrix1 read back the DNG SDK's way (the gallery's DngColor, as Lightroom / ACR) gives the
     * sceneCct it was made with as the As-Shot temperature (the profile's own step; DngColour is unchanged by P370). That sceneCct
     * is A1's estimate: ParametersSceneCctTest.theDngAsShotIsTheEstimatedLight runs the whole chain from a tungsten neutral.
     */
    @Test public void theAsShotTemperatureIsTheSceneLight() {
        final float[] s2p = {1 / NEUTRAL[0], 0, 0, 0, 1 / NEUTRAL[1], 0, 0, 0, 1 / NEUTRAL[2]};
        final float[] i2s = {1.62f, -0.48f, -0.14f, -0.21f, 1.39f, -0.18f, 0.02f, -0.55f, 1.53f};
        for (float cct : new float[]{2860f, 3600f, 5200f, 6504f}) {
            final DngColour.Profile p = DngColour.jpegProfile(s2p, i2s, NEUTRAL, cct);
            final double[] cm = DngColour.toDouble(p.colorMatrix1);
            final double[] n = {NEUTRAL[0], NEUTRAL[1], NEUTRAL[2]};
            final com.particlesdevs.photoncamera.gallery.dng.DngColor read =
                    com.particlesdevs.photoncamera.gallery.dng.DngColor.fromCamera(cm, n);
            final double asShot = com.particlesdevs.photoncamera.gallery.dng.DngColor.cctDuv(read.xyForNeutral(n))[0];
            assertEquals("scene " + cct + " K", cct, asShot, 20);
        }
    }

    @Test public void theDngInterpolationCaseGivesBackItsForwardMatrix() {
        // sensorToProPhoto = XYZ->ProPhoto · FM · D (Converter.calculateCameraToXYZD50Transform, no calibration), the standard
        // ProPhoto -> sRGB: the profile's forward matrix is that normalised FM
        final float[] fm = {0.6091f, 0.2427f, 0.1124f, 0.2313f, 0.8771f, -0.1084f, 0.0429f, -0.2001f, 0.9821f};
        Converter.normalizeFM(fm);
        final float max = Math.max(NEUTRAL[0], Math.max(NEUTRAL[1], NEUTRAL[2]));
        final float[] d = {max / NEUTRAL[0], 0, 0, 0, max / NEUTRAL[1], 0, 0, 0, max / NEUTRAL[2]};
        final float[] s2x = new float[9], s2p = new float[9], i2s = new float[9];
        Converter.multiply(fm, d, s2x);
        Converter.multiply(Converter.sXYZtoProPhoto, s2x, s2p);
        Converter.multiply(Converter.sXYZtoSRGB, Converter.sProPhotoToXYZ, i2s);
        final DngColour.Profile p = DngColour.jpegProfile(s2p, i2s, NEUTRAL, 6504f);
        assertNotNull(p);
        for (int i = 0; i < 9; i++) assertEquals("FM " + i, fm[i], p.forwardMatrix1[i], 2e-3);
        for (double[] c : new double[][]{{0.2, 0.5, 0.1}, {0.05, 0.02, 0.3}})
            assertSameColour(jpeg(s2p, i2s, c), dng(p, NEUTRAL, c), 1e-4);
    }

    @Test public void theColorMatrixPutsTheNeutralAtTheSceneLight() {
        final float[] s2p = {1 / NEUTRAL[0], 0, 0, 0, 1, 0, 0, 0, 1 / NEUTRAL[2]};
        final float[] i2s = {1.5f, -0.4f, -0.1f, -0.2f, 1.4f, -0.2f, 0f, -0.5f, 1.5f};
        for (float cct : new float[]{2856f, 4300f, 6504f}) {
            final DngColour.Profile p = DngColour.jpegProfile(s2p, i2s, NEUTRAL, cct);
            final double[] cam = DngColour.map(DngColour.toDouble(p.colorMatrix1), DngColour.xyzOfCct(cct));
            for (int i = 0; i < 3; i++) assertEquals("CM1 · XYZ(" + cct + " K) ∝ the neutral", NEUTRAL[i] / NEUTRAL[1], cam[i] / cam[1], 1e-4);
        }
    }

    @Test public void theLocusAndTheGuards() {
        final double[] d65 = DngColour.xyzOfCct(6504), a = DngColour.xyzOfCct(2856);
        assertEquals(0.3127, d65[0] / (d65[0] + d65[1] + d65[2]), 1e-3);
        assertEquals(0.3291, d65[1] / (d65[0] + d65[1] + d65[2]), 1e-3);
        assertEquals(0.4476, a[0] / (a[0] + a[1] + a[2]), 2e-3);
        assertEquals(0.4074, a[1] / (a[0] + a[1] + a[2]), 2e-3);
        // the render's XYZ -> sRGB is Converter's
        for (int i = 0; i < 9; i++) assertEquals(Converter.sXYZtoSRGB[i], DngColour.XYZ_TO_SRGB_D50[i], 1e-6);
        final float[] id = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        assertNull("no white balance", DngColour.jpegProfile(id, id, new float[]{0, 1, 1}, 5000f));
        assertNull(DngColour.jpegProfile(id, new float[]{Float.NaN, 0, 0, 0, 1, 0, 0, 0, 1}, NEUTRAL, 5000f));
        assertNull("singular", DngColour.jpegProfile(new float[9], id, NEUTRAL, 5000f));
        assertNotNull("an odd scene light: D50", DngColour.jpegProfile(id, id, NEUTRAL, 0f));
    }
}
