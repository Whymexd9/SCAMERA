package com.particlesdevs.photoncamera.control;

/** Rotational image-motion extent during the measured exposure, in RAW pixels.
 * Uses path extrema, so return motion is not mistaken for zero blur. This is
 * an uncompensated pinhole estimate, not an OIS or rolling-shutter model. */
public final class GyroBlurEstimate {
    private GyroBlurEstimate() {}
    public static double pixels(GyroBurst gyro, double fx, double fy, int width, int height) {
        if (gyro == null || gyro.samples <= 0 || !(fx > 0) || !(fy > 0)
                || !Double.isFinite(fx + fy)) return Double.NaN;
        double[] p = new double[3], lo = new double[3], hi = new double[3];
        for (int i = 0; i < gyro.samples; i++) for (int a = 0; a < 3; a++) {
            double d = gyro.movementss[a][i];
            if (!Double.isFinite(d)) return Double.NaN;
            p[a] += d; lo[a] = Math.min(lo[a], p[a]); hi[a] = Math.max(hi[a], p[a]);
        }
        return Math.hypot((hi[1] - lo[1]) * fx, (hi[0] - lo[0]) * fy)
                + (hi[2] - lo[2]) * Math.hypot(width, height) * .5;
    }
}
