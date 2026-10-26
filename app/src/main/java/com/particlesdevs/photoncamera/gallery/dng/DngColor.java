package com.particlesdevs.photoncamera.gallery.dng;

/**
 * P351 (owner, 2026-10-26: «Не забудь сделать чтобы наша галерея полноценно принимала dng и тд»): the colour of a DNG as the DNG
 * specification defines it, for the editor's RAW source: the camera neutral of a white balance given as correlated colour
 * temperature and Duv (tint), ColorMatrix / ForwardMatrix / CameraCalibration interpolated by 1/CCT between the two calibration
 * illuminants, CameraCalibration and AnalogBalance applied, camera -> XYZ D50 (ForwardMatrix, else the inverse ColorMatrix with
 * a Bradford adaptation) -> linear sRGB D65. Pure Java (JVM tests). Matrices are row-major 3x3.
 */
public final class DngColor {
    /** XYZ (D50) -> linear sRGB (D65 through Bradford), as DngPreview. */
    static final double[] XYZ50_TO_SRGB = {3.1338561, -1.6168667, -0.4906146, -0.9787684, 1.9161415, 0.0334540, 0.0719453, -0.2289914, 1.4052427};
    static final double[] D50 = {0.3457, 0.3585};
    private static final double[] BRADFORD = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};

    final double[] cm1, cm2, fm1, fm2, cc1, cc2, ab;
    final double t1, t2;
    /** The as-shot camera neutral (green 1). */
    public final double[] asShotNeutral;

    DngColor(double[] cm1, double[] cm2, double[] fm1, double[] fm2, double[] cc1, double[] cc2, double[] ab,
             int illuminant1, int illuminant2, double[] asShotNeutral, double[] asShotWhiteXY) {
        this.cm1 = m3(cm1);
        this.cm2 = m3(cm2);
        this.fm1 = m3(fm1);
        this.fm2 = m3(fm2);
        this.cc1 = m3(cc1);
        this.cc2 = m3(cc2);
        this.ab = ab != null && ab.length >= 3 ? new double[]{ab[0], 0, 0, 0, ab[1], 0, 0, 0, ab[2]} : null;
        this.t1 = illuminantTemperature(illuminant1, 2850);
        this.t2 = illuminantTemperature(illuminant2, 6500);
        double[] n = null;
        if (asShotNeutral != null && asShotNeutral.length >= 3 && asShotNeutral[1] > 0) n = normalise(asShotNeutral.clone());
        else if (asShotWhiteXY != null && asShotWhiteXY.length >= 2 && hasMatrices()) n = neutralForXy(asShotWhiteXY);
        this.asShotNeutral = n != null ? n : new double[]{1, 1, 1};
    }

    /**
     * P352: the colour of a camera RAW read by LibRaw: its XYZ (D65) -> camera matrix (cam_xyz, Adobe's coefficients) as the one
     * calibration, illuminant D65, and the camera's as-shot neutral (1 / cam_mul).
     */
    public static DngColor fromCamera(double[] xyzToCamera, double[] neutral) {
        boolean any = false;
        if (xyzToCamera != null) for (double v : xyzToCamera) any |= v != 0;
        return new DngColor(any ? xyzToCamera : null, null, null, null, null, null, null, 21, 0, neutral, null);
    }

    static DngColor of(DngPreview p) {
        return new DngColor(p.colorMatrix1, p.colorMatrix2, p.forwardMatrix1, p.forwardMatrix2, p.cameraCalibration1,
                p.cameraCalibration2, p.analogBalance, p.illuminant1, p.illuminant2, p.asShotNeutral, p.asShotWhiteXY);
    }

    /** Whether the file carries a ColorMatrix (else the editor balances the camera RGB with plain gains). */
    public boolean hasMatrices() {
        return cm1 != null;
    }

    private static double[] m3(double[] m) {
        return m != null && m.length >= 9 ? new double[]{m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8]} : null;
    }

    /** EXIF LightSource -> kelvin (the DNG SDK's table); 0 / unknown -> {@code fallback}. */
    static double illuminantTemperature(int code, double fallback) {
        switch (code) {
            case 1: case 4: case 9: case 20: return 5500;
            case 2: case 14: return 4150;
            case 3: case 17: return 2850;
            case 10: return 6500;
            case 11: return 7500;
            case 12: return 6430;
            case 13: return 5000;
            case 15: return 3450;
            case 16: return 2940;
            case 18: return 4875;
            case 19: return 6775;
            case 21: return 6504;
            case 22: return 7504;
            case 23: return 5003;
            case 24: return 3200;
            default: return fallback;
        }
    }

    /** The weight of calibration 1 at {@code cct} (1/T interpolation, clamped). */
    double weight1(double cct) {
        if (cm2 == null || t1 == t2) return 1;
        double lo = Math.min(t1, t2), hi = Math.max(t1, t2);
        double t = Math.max(lo, Math.min(hi, cct));
        double g = (1 / t - 1 / hi) / (1 / lo - 1 / hi); // weight of the low-temperature calibration
        return t1 <= t2 ? g : 1 - g;
    }

    static double[] mix(double[] a, double[] b, double wa) {
        if (b == null) return a;
        if (a == null) return b;
        double[] r = new double[9];
        for (int i = 0; i < 9; i++) r[i] = a[i] * wa + b[i] * (1 - wa);
        return r;
    }

    /** AB x CC (both interpolated): the camera-specific part, identity when absent. */
    double[] abcc(double cct) {
        double w = weight1(cct);
        double[] cc = mix(cc1, cc2, w);
        double[] m = cc != null ? cc : identity();
        return ab != null ? DngPreview.mul(ab, m) : m;
    }

    /** XYZ -> camera at {@code cct}. */
    double[] xyzToCamera(double cct) {
        return DngPreview.mul(abcc(cct), mix(cm1, cm2, weight1(cct)));
    }

    /** The camera neutral of a white of chromaticity xy (green 1). */
    public double[] neutralForXy(double[] xy) {
        double[] xyz = xyToXyz(xy);
        return normalise(apply(xyzToCamera(cctDuv(xy)[0]), xyz));
    }

    /** The chromaticity whose camera neutral is {@code neutral} (the DNG SDK's iteration). */
    public double[] xyForNeutral(double[] neutral) {
        double[] xy = D50.clone();
        for (int i = 0; i < 40; i++) {
            double[] m = xyzToCamera(cctDuv(xy)[0]);
            if (Math.abs(det(m)) < 1e-12) return D50.clone();
            double[] xyz = apply(DngPreview.inverse(m), neutral);
            double s = xyz[0] + xyz[1] + xyz[2];
            if (s <= 1e-12) return D50.clone();
            double[] next = {xyz[0] / s, xyz[1] / s};
            if (Math.abs(next[0] - xy[0]) + Math.abs(next[1] - xy[1]) < 1e-9) return next;
            xy = next;
        }
        return xy;
    }

    /**
     * Camera RGB -> linear sRGB for the camera neutral {@code n}: ForwardMatrix route when present, else the inverse colour
     * matrix adapted from the white to D50; rows scaled so the neutral comes out as (1, 1, 1).
     */
    public double[] cameraToSrgb(double[] n) {
        if (!hasMatrices()) {
            return new double[]{1 / n[0], 0, 0, 0, 1 / n[1], 0, 0, 0, 1 / n[2]};
        }
        double[] xy = xyForNeutral(n);
        double cct = cctDuv(xy)[0];
        double w = weight1(cct);
        double[] fm = mix(fm1, fm2, w);
        double[] abcc = abcc(cct);
        double[] camToXyz50;
        if (fm != null) {
            double[] inv = DngPreview.inverse(abcc);
            double[] ref = apply(inv, n);
            double[] d = {1 / Math.max(ref[0], 1e-9), 0, 0, 0, 1 / Math.max(ref[1], 1e-9), 0, 0, 0, 1 / Math.max(ref[2], 1e-9)};
            camToXyz50 = DngPreview.mul(fm, DngPreview.mul(d, inv));
        } else {
            double[] camToXyz = DngPreview.inverse(xyzToCamera(cct));
            camToXyz50 = DngPreview.mul(adaptation(xyToXyz(xy), xyToXyz(D50)), camToXyz);
        }
        double[] m = DngPreview.mul(XYZ50_TO_SRGB, camToXyz50);
        double[] out = apply(m, n);
        for (int r = 0; r < 3; r++) {
            double s = out[r] > 1e-9 ? 1 / out[r] : 1;
            for (int c = 0; c < 3; c++) m[r * 3 + c] *= s;
        }
        return m;
    }

    // ---------------------------------------------------------------- CCT / Duv

    /** Krystek's (1985) Planckian locus in CIE 1960 uv, 1000..15000 K. */
    static double[] planckUv(double t) {
        double u = (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t) / (1 + 8.42420235e-4 * t + 7.08145163e-7 * t * t);
        double v = (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t) / (1 - 2.89741816e-5 * t + 1.61456053e-7 * t * t);
        return new double[]{u, v};
    }

    static double[] xyToUv(double[] xy) {
        double d = -2 * xy[0] + 12 * xy[1] + 3;
        return new double[]{4 * xy[0] / d, 6 * xy[1] / d};
    }

    static double[] uvToXy(double[] uv) {
        double d = 2 * uv[0] - 8 * uv[1] + 4;
        return new double[]{3 * uv[0] / d, 2 * uv[1] / d};
    }

    /** {CCT in K, Duv}: the nearest point of the locus (golden-section search on mired 40..700); Duv > 0 above (green). */
    public static double[] cctDuv(double[] xy) {
        double[] uv = xyToUv(xy);
        double lo = 1e6 / 25000, hi = 1e6 / 1430;
        final double g = (Math.sqrt(5) - 1) / 2;
        double a = hi - g * (hi - lo), b = lo + g * (hi - lo);
        double fa = dist2(uv, 1e6 / a), fb = dist2(uv, 1e6 / b);
        for (int i = 0; i < 80; i++) {
            if (fa < fb) {
                hi = b;
                b = a;
                fb = fa;
                a = hi - g * (hi - lo);
                fa = dist2(uv, 1e6 / a);
            } else {
                lo = a;
                a = b;
                fa = fb;
                b = lo + g * (hi - lo);
                fb = dist2(uv, 1e6 / b);
            }
        }
        double t = 1e6 / ((lo + hi) / 2);
        double[] p = planckUv(t);
        double d = Math.sqrt(dist2(uv, t));
        return new double[]{t, uv[1] >= p[1] ? d : -d};
    }

    private static double dist2(double[] uv, double t) {
        double[] p = planckUv(t);
        return (uv[0] - p[0]) * (uv[0] - p[0]) + (uv[1] - p[1]) * (uv[1] - p[1]);
    }

    /** The chromaticity at {@code cct} K, {@code duv} off the locus along its normal (toward +v for Duv > 0). */
    public static double[] xyFromCctDuv(double cct, double duv) {
        double[] p = planckUv(cct), q = planckUv(cct * 1.001);
        double tu = q[0] - p[0], tv = q[1] - p[1], len = Math.hypot(tu, tv);
        double nu = -tv / len, nv = tu / len;
        if (nv < 0) {
            nu = -nu;
            nv = -nv;
        }
        return uvToXy(new double[]{p[0] + duv * nu, p[1] + duv * nv});
    }

    // ---------------------------------------------------------------- helpers

    static double[] xyToXyz(double[] xy) {
        return new double[]{xy[0] / xy[1], 1, (1 - xy[0] - xy[1]) / xy[1]};
    }

    /** Bradford adaptation from white {@code src} to white {@code dst} (XYZ). */
    static double[] adaptation(double[] src, double[] dst) {
        double[] s = apply(BRADFORD, src), d = apply(BRADFORD, dst);
        double[] scale = {d[0] / s[0], 0, 0, 0, d[1] / s[1], 0, 0, 0, d[2] / s[2]};
        return DngPreview.mul(DngPreview.inverse(BRADFORD), DngPreview.mul(scale, BRADFORD));
    }

    static double[] apply(double[] m, double[] v) {
        return new double[]{m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    static double[] normalise(double[] n) {
        double g = n[1] > 1e-12 ? n[1] : Math.max(n[0], Math.max(n[1], n[2]));
        if (g <= 1e-12) return new double[]{1, 1, 1};
        return new double[]{n[0] / g, n[1] / g, n[2] / g};
    }

    static double[] identity() {
        return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
    }

    static double det(double[] m) {
        return m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6]);
    }
}
