package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

/**
 * P314: the RAW viewfinder's per-pixel WHY fusion (why/combine.glsl POINT_FUSED 1, LiveWhyTone vf_why 1): the fused display luma
 * of why/gf.glsl as a function of the guide I alone - fusedAt(I) (every layer meters I x em, so a pixel's own weights and lumas
 * depend on I only; the guided filter gives the same on a flat area) with the P291 monotonic envelope (36 quarter-EV steps down),
 * tabulated over log2 I at PER_EV entries an EV. At the grid points the table is the shader's formula (a quarter EV is PER_EV / 4
 * entries); between them the shader's linear lookup errs below 1e-3 (a quarter of an 8-bit step) for monoU up to 0.1 (the default
 * 0.04; a steeper rise makes the envelope's own gate, fj >= 0.5, jump by more between two entries). Pure Java, no GL: LiveWhyTone
 * fills it after each viewfinder AE run (SIZE fusedAt evaluations) and uploads it as an R32F SIZE x 1 texture.
 */
final class FusedCurve {
    private FusedCurve() {}

    /** log2 I of the first and the last entry: below the first the curve is the toe's ~0, above the last the shoulder's 1. */
    static final float LOG2_MIN = -16f, LOG2_MAX = 6f;
    static final int PER_EV = 16;
    static final int SIZE = (int) ((LOG2_MAX - LOG2_MIN) * PER_EV) + 1;

    /** The kernel's ACES tone scale (why/gf.glsl acescg: pre-gain 1.5, a 2.51, d 0.59). */
    static double acescg(double v, double toe) {
        final double x = Math.max(v, 0.0) * 1.5;
        final double b = Math.max(toe * 0.75, 0.005), e = Math.max(0.14 - (toe - 0.04) * 0.8, 0.03);
        return (x * (2.51 * x + b)) / (x * (2.43 * x + 0.59) + e);
    }

    /**
     * why/gf.glsl fusedAt(I, sw = 0) with the shader's defaults for unset values (em: the four exposures, lw: the layer weights;
     * the viewfinder sets no saturated-light share).
     */
    static double fusedAt(double I, float[] em, float[] lw, float center, float smooth, float gammaInv, float toe) {
        final double c = center > 0f ? center : 0.6, s = smooth > 0f ? smooth : 0.25;
        final double gi = gammaInv > 0f ? gammaInv : 1.0 / 2.2, t = toe > 0f ? toe : 0.05;
        final double var = Math.max(s * s, 0.001);
        double sw = 0, swl = 0;
        for (int k = 0; k < 4; k++) {
            final double l = Math.pow(Math.max(acescg(I * em[k], t), 1e-6), gi);
            final double w = Math.exp(-0.5 * (l - c) * (l - c) / var) * lw[k] + 1e-6;
            sw += w;
            swl += w * l;
        }
        return swl / sw;
    }

    /**
     * The table into {@code out} (SIZE floats): entry i at log2 I = LOG2_MIN + i / PER_EV. {@code mono}: monoU of why/gf.glsl
     * (the envelope's rise per ln of the light; 0 = the kernel's curve as it is).
     */
    static void fill(float[] out, float[] em, float[] lw, float center, float smooth, float gammaInv, float toe, float mono) {
        final double[] f0 = new double[SIZE];
        for (int i = 0; i < SIZE; i++)
            f0[i] = fusedAt(Math.pow(2.0, LOG2_MIN + i / (double) PER_EV), em, lw, center, smooth, gammaInv, toe);
        final int step = PER_EV / 4;
        final double quarter = 0.25 * Math.log(2.0);
        for (int i = 0; i < SIZE; i++) {
            double env = f0[i];
            if (mono > 0f) {
                for (int j = 1; j <= 36; j++) {
                    final int k = i - j * step;
                    // below the table the curve is the toe's (< 0.5): the shader's step adds nothing there either
                    if (k < 0) break;
                    if (f0[k] >= 0.5) env = Math.max(env, f0[k] + mono * j * quarter);
                }
            }
            out[i] = (float) env;
        }
    }
}
