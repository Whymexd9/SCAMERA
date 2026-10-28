package com.particlesdevs.photoncamera.gallery.dng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * P370 (A1): the camera's white estimate the DNG SDK's way (DngColor.forCamera: each ColorMatrix normalised as one, the xy
 * iteration, CCT and Duv). Parameters normalised every ColorMatrix row by row, which pulled every white toward the D65
 * calibration: the PHY110 camera 2 matrices read a 2856 K light as 5068 K, 3200 K as 5508 K, 4000 K as 6455 K (the plan's cm.py).
 */
public class DngWhiteTest {
    /** PHY110 camera 2 (SCAM TEST dump): CM1 at D65 (21), CM2 at A (17), CC1 diag(127/128, 1, 130/128), CC2 diag(127/128, 1, 1). */
    static final double[] PHY_CM1 = {0.6178, -0.1002, -0.0970, -0.4729, 1.4118, 0.0345, -0.0861, 0.2593, 0.4441};
    static final double[] PHY_CM2 = {0.6892, 0.0196, -0.3223, -0.7131, 1.8376, -0.1520, -0.0884, 0.2258, 0.6427};
    static final double[] PHY_CC1 = {127 / 128.0, 0, 0, 0, 1, 0, 0, 0, 130 / 128.0};
    static final double[] PHY_CC2 = {127 / 128.0, 0, 0, 0, 1, 0, 0, 0, 1};
    /** vivo X200 Ultra camera 3 raw CMs (CM1 D65, CM2 A) and the CCs of the P343 log. */
    static final double[] X200U_CM1 = div(128, 124, -47, -18, -57, 178, 4, -7, 31, 52);
    static final double[] X200U_CM2 = div(128, 197, -115, -28, -72, 202, 15, -3, 15, 91);
    static final double[] X200U_CC1 = {1, 0, 0, 0, 1, 0, 0, 0, 0.9921875};
    static final double[] X200U_CC2 = {1, 0, 0, 0, 1, 0, 0, 0, 0.984375};
    /** OPPO Find X8 Ultra camera 4: the CMs as Parameters logged them (already row-normalised), its CCs. */
    static final double[] X8U_CM1 = {1.7026823, -0.5046948, -0.23378737, -0.5532736, 1.3832362, 0.17003755, -0.23374575, 0.3591421, 0.69950366};
    static final double[] X8U_CM2 = {2.781688, -1.4754171, -0.34207094, -0.659677, 1.674601, -0.014923964, -0.10308579, 0.22892523, 0.6990605};
    static final double[] X8U_CC1 = {1.03125, 0, 0, 0, 1, 0, 0, 0, 0.984375};
    static final double[] X8U_CC2 = {1.03125, 0, 0, 0, 1, 0, 0, 0, 0.96875};

    static double[] div(double d, double... v) {
        final double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] / d;
        return out;
    }

    /** Planckian xy (Kim et al. 2002), the plan's generator - independent of DngColor's Krystek locus. */
    static double[] planckXyz(double t) {
        final double x = t < 4000 ? -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
                : -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390;
        final double y = t < 2222 ? -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                : t < 4000 ? -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
                : 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483;
        return new double[]{x / y, 1, (1 - x - y) / y};
    }

    /** The camera neutral of a Planckian light at {@code t}: the raw matrices interpolated in 1/T between 6504 K (1) and 2856 K (2). */
    static double[] neutralAt(double t, double[] cm1, double[] cm2, double[] cc1, double[] cc2) {
        final double w = Math.max(0, Math.min(1, (1 / t - 1 / 6504.0) / (1 / 2856.0 - 1 / 6504.0)));
        final double[] a = DngPreview.mul(cc1, cm1), b = DngPreview.mul(cc2, cm2), m = new double[9];
        for (int i = 0; i < 9; i++) m[i] = (1 - w) * a[i] + w * b[i];
        final double[] n = DngColor.apply(m, planckXyz(t));
        return new double[]{n[0] / n[1], 1, n[2] / n[1]};
    }

    static double[] estimate(DngColor dc, double[] n) {
        return DngColor.cctDuv(dc.xyForNeutral(n));
    }

    @Test public void thePhy110MatricesReadTheLightTheyWereMadeFor() {
        final DngColor dc = DngColor.forCamera(PHY_CM1, PHY_CM2, PHY_CC1, PHY_CC2, 21, 17);
        assertTrue(dc.usable());
        for (double t : new double[]{2856, 3200, 4000, 5000, 6504}) {
            final double[] cd = estimate(dc, neutralAt(t, PHY_CM1, PHY_CM2, PHY_CC1, PHY_CC2));
            assertEquals("light " + t + " K read as " + cd[0], t, cd[0], t * 0.015);
            assertTrue("Duv " + cd[1], Math.abs(cd[1]) < 0.025);
        }
    }

    /** The interpolation weight does not depend on the scale of either matrix (the SDK normalises each as one). */
    @Test public void theWeightIgnoresTheMatricesScale() {
        final double[] n = neutralAt(3600, PHY_CM1, PHY_CM2, PHY_CC1, PHY_CC2);
        final DngColor a = DngColor.forCamera(PHY_CM1, PHY_CM2, PHY_CC1, PHY_CC2, 21, 17);
        final double[] big = PHY_CM1.clone(), small = PHY_CM2.clone();
        for (int i = 0; i < 9; i++) {
            big[i] *= 3;
            small[i] *= 0.5;
        }
        final DngColor b = DngColor.forCamera(big, small, PHY_CC1, PHY_CC2, 21, 17);
        final double ta = estimate(a, n)[0], tb = estimate(b, n)[0];
        assertEquals(a.weight2(ta), b.weight2(tb), 1e-9);
        assertEquals(ta, tb, 1e-6);
    }

    /** X200U: the shot's neutral and a tungsten light (the A calibration's own neutral) read as the plan measured them. */
    @Test public void theX200UltraMatricesReadDaylightAndTungsten() {
        final DngColor dc = DngColor.forCamera(X200U_CM1, X200U_CM2, X200U_CC1, X200U_CC2, 21, 17);
        final double[] shot = estimate(dc, new double[]{0.375, 1, 0.52246094});
        assertEquals("the P343 shot", 5477, shot[0], 60);
        final double[] a = DngColor.apply(DngPreview.mul(X200U_CC2, X200U_CM2), new double[]{1.09850, 1, 0.35585});
        final double[] tungsten = estimate(dc, new double[]{a[0] / a[1], 1, a[2] / a[1]});
        assertEquals("standard A", 2856, tungsten[0], 45);
        assertTrue("the A calibration's weight " + dc.weight2(tungsten[0]), dc.weight2(tungsten[0]) >= 0.98);
        assertTrue("Duv " + shot[1], Math.abs(shot[1]) < 0.025);
    }

    /**
     * The X8U's matrices as the old log printed them - row-normalised, the very artefact A1 removes - put its own neutral far off the
     * locus: no light can be read from them (unknown, not 5373 K). What the phone's raw matrices give is not known (their row scales
     * are not in the log): the B1 "DNG colour tags" line's rationals become this fixture once captured (P370 C1).
     */
    @Test public void rowNormalisedX8UltraMatricesGiveNoLight() {
        final DngColor dc = DngColor.forCamera(X8U_CM1, X8U_CM2, X8U_CC1, X8U_CC2, 21, 17);
        final double[] cd = estimate(dc, new double[]{0.54785156, 1, 0.41796875});
        assertTrue("Duv " + cd[1] + " at " + cd[0] + " K", Math.abs(cd[1]) > 0.025);
    }

    /** The SDK's normalisation: one scale, only outside 0.99 .. 1.01; a normal file's matrix is untouched. */
    @Test public void theSdkNormalisationIsOneScale() {
        final double[] n = DngColor.sdkNormalised(X200U_CM1);
        final double[] d50 = {0.3457 / 0.3585, 1, (1 - 0.3457 - 0.3585) / 0.3585};
        final double[] v = DngColor.apply(n, d50);
        assertEquals(1, Math.max(v[0], Math.max(v[1], v[2])), 1e-12);
        for (int i = 1; i < 9; i++) assertEquals(X200U_CM1[i] / X200U_CM1[0], n[i] / n[0], 1e-12);
        final double[] unit = DngColor.sdkNormalised(n);
        for (int i = 0; i < 9; i++) assertEquals(n[i], unit[i], 0);
        // a singular matrix gives no estimate (no silent D50)
        assertTrue(!DngColor.forCamera(new double[9], null, null, null, 21, 17).usable());
    }
}
