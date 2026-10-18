package com.particlesdevs.photoncamera.processing.color;

import java.util.Locale;

/**
 * P243 (the plan's «DNG colour = JPEG», defined from the code): the colour tags of our DNGs carry the white balance and the colour
 * matrix our JPEG was rendered with, so Lightroom / ACR (and the gallery's DngPreview) open the DNG in the JPEG's colour.
 * <p>
 * Before: the DNG got the camera's static dual-illuminant profile (SENSOR_COLOR_TRANSFORM1/2, FORWARD_MATRIX1/2,
 * CALIBRATION_TRANSFORM1/2) and the AsShotNeutral, while the JPEG used whatever Parameters chose - the ISP's colour transform of
 * the capture result (vivo: the DNG matrices are generic), the OPPO tuned forward matrix per CCT (OppoTunedColor), the plain-session
 * matrix of a mosaic shot (PlainColourMemory), a DCP profile (DcpProfiles) or the DNG interpolation itself. Only the last one
 * matched what a raw converter did with the tags.
 * <p>
 * The JPEG's linear render of a camera RAW value c (after the lens shading, the DNG's GainMap) is the linear sRGB
 * {@code intermediateToSRGB · sensorToProPhoto · c} (ark/low.glsl, combine.glsl: {@code inColor * neutral} is c); the «Цвет»
 * look's white matrix and the tone come on top of it and are no camera colour. The DNG's colour model with a ForwardMatrix is
 * XYZ_D50 = FM · diag(1 / AsShotNeutral) · c (no CameraCalibration, no AnalogBalance); sRGB here is our D50-adapted one
 * (Converter.sXYZtoSRGB, Bradford), so
 * <pre>
 *   FM  = sRGB_D50→XYZ · intermediateToSRGB · sensorToProPhoto · diag(n), scaled to (FM · 1).Y = 1;
 *   CM1 = diag(n) · FM⁻¹ · Bradford(scene white → FM · 1 ≈ D50)  (CM1 · XYZ_scene = n: the temperature readout follows the scene),
 * </pre>
 * one illuminant (no ColorMatrix2 / ForwardMatrix2 / CameraCalibration: nothing to interpolate, the render is the JPEG's
 * matrix whatever the converter's CCT estimate). n = Parameters.whitePoint (= AsShotNeutral). The ISP-matrix case gives
 * FM · 1 = D50 exactly; the DNG-interpolation case gives back the interpolated, normalised forward matrix.
 */
public final class DngColour {
    /** EXIF LightSource D65: the label of the single calibration (one matrix: no interpolation reads it). */
    public static final int ILLUMINANT_LABEL = 21;
    /** XYZ D50 -> linear sRGB (D50-adapted, Bradford): Converter.sXYZtoSRGB, the render's own (its inverse below). */
    static final double[] XYZ_TO_SRGB_D50 = {
            3.1338561, -1.6168667, -0.4906146,
            -0.9787684, 1.9161415, 0.0334540,
            0.0719453, -0.2289914, 1.4052427};
    static final double[] D50 = {0.9642, 1.0, 0.8249};
    private static final double[] BRADFORD = {
            0.8951, 0.2664, -0.1614,
            -0.7502, 1.7135, 0.0367,
            0.0389, -0.0685, 1.0296};

    /** The colour tags of one DNG: ColorMatrix1, ForwardMatrix1 (row-major), the calibration label. */
    public static final class Profile {
        public final float[] colorMatrix1, forwardMatrix1;
        public final int illuminant;
        /** CIE xy of FM · 1 (D50 = 0.3457 / 0.3585 when the JPEG's matrix keeps white white). */
        public final double whiteX, whiteY;

        Profile(float[] cm, float[] fm, int illuminant, double wx, double wy) {
            this.colorMatrix1 = cm;
            this.forwardMatrix1 = fm;
            this.illuminant = illuminant;
            this.whiteX = wx;
            this.whiteY = wy;
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "CM1 %s FM1 %s (FM white xy %.4f / %.4f), illuminant %d, single",
                    java.util.Arrays.toString(colorMatrix1), java.util.Arrays.toString(forwardMatrix1), whiteX, whiteY, illuminant);
        }
    }

    private DngColour() {}

    /**
     * The profile of the JPEG's colour: {@code sensorToProPhoto} and {@code intermediateToSRGB} as Parameters holds them for the
     * render, {@code neutral} its white point, {@code sceneCct} the scene light (K; outside 1667-25000 K: D50). Null when a matrix
     * is missing, not finite or not invertible (the camera's profile stays then).
     */
    public static Profile jpegProfile(float[] sensorToProPhoto, float[] intermediateToSRGB, float[] neutral, float sceneCct) {
        if (!finite(sensorToProPhoto, 9) || !finite(intermediateToSRGB, 9) || !finite(neutral, 3)) return null;
        for (float v : neutral) if (!(v > 0f)) return null;
        final double[] m = mul(toDouble(intermediateToSRGB), toDouble(sensorToProPhoto)); // camera -> linear sRGB (D50)
        final double[] c2x = mul(XYZ_TO_SRGB_D50_INV, m);                                    // camera -> XYZ D50
        double[] fm = mul(c2x, diag(neutral[0], neutral[1], neutral[2]));
        final double[] white = map(fm, new double[]{1, 1, 1});
        if (!(white[1] > 1e-9)) return null;
        for (int i = 0; i < 9; i++) fm[i] /= white[1];
        final double[] fmInv = invert(fm);
        if (fmInv == null) return null;
        final double[] scene = sceneCct >= 1667f && sceneCct <= 25000f ? xyzOfCct(sceneCct) : D50.clone();
        final double[] fmWhite = map(fm, new double[]{1, 1, 1}); // D50 up to the render matrix's rounding
        final double[] cm = mul(mul(diag(neutral[0], neutral[1], neutral[2]), fmInv), bradford(scene, fmWhite));
        final double sum = white[0] + white[1] + white[2];
        return new Profile(toFloat(cm), toFloat(fm), ILLUMINANT_LABEL, white[0] / sum, white[1] / sum);
    }

    /** XYZ (Y = 1) of a correlated colour temperature: the CIE daylight locus from 4000 K, Kang et al.'s Planckian fit below. */
    static double[] xyzOfCct(double t) {
        double x;
        if (t >= 4000) {
            x = t <= 7000 ? -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063
                    : -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.237040;
            final double y = -3.0 * x * x + 2.870 * x - 0.275;
            return new double[]{x / y, 1.0, (1 - x - y) / y};
        }
        x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910;
        final double y = t <= 2222 ? -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                : -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
        return new double[]{x / y, 1.0, (1 - x - y) / y};
    }

    /** Bradford adaptation from white {@code src} to white {@code dst} (XYZ). */
    static double[] bradford(double[] src, double[] dst) {
        final double[] s = map(BRADFORD, src), d = map(BRADFORD, dst);
        final double[] inv = invert(BRADFORD);
        return mul(inv, mul(diag(d[0] / s[0], d[1] / s[1], d[2] / s[2]), BRADFORD));
    }

    /** Linear sRGB (D50-adapted) -> XYZ D50. */
    static final double[] XYZ_TO_SRGB_D50_INV = invert(XYZ_TO_SRGB_D50);

    static boolean finite(float[] v, int n) {
        if (v == null || v.length < n) return false;
        for (int i = 0; i < n; i++) if (!Float.isFinite(v[i])) return false;
        return true;
    }

    static double[] diag(double a, double b, double c) { return new double[]{a, 0, 0, 0, b, 0, 0, 0, c}; }

    static double[] mul(double[] a, double[] b) {
        final double[] o = new double[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
        return o;
    }

    static double[] map(double[] m, double[] v) {
        return new double[]{m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    static double[] invert(double[] m) {
        final double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        final double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        final double det = a * A + b * B + c * C;
        if (!(Math.abs(det) > 1e-12) || !Double.isFinite(det)) return null;
        return new double[]{A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
                B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
                C / det, -(a * h - b * g) / det, (a * e - b * d) / det};
    }

    static double[] toDouble(float[] f) {
        final double[] d = new double[9];
        for (int i = 0; i < 9; i++) d[i] = f[i];
        return d;
    }

    static float[] toFloat(double[] d) {
        final float[] f = new float[9];
        for (int i = 0; i < 9; i++) f[i] = (float) d[i];
        return f;
    }
}
