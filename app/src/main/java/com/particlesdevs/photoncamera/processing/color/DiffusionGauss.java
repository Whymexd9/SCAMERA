package com.particlesdevs.photoncamera.processing.color;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * P289 (owner, 2026-10-20: «посмотри как это реализовано в [приложении-образце] и исправь у нас» - the FFT filter left the photo
 * unchanged): the diffusion filters as the reference app runs them - out = (1 - p) E + p (K * E) with K the filter's sum of 2D
 * exponentials (DiffusionModel), each exponential as the three-Gaussian fit the reference model uses for exponential blurs
 * (amplitudes 0.1633, 0.6496, 0.1870 at 0.536, 1.5236, 2.7684 x the decay), every Gaussian a separable blur on the level of a box
 * pyramid where it is 3..6 pixels wide (its residual width after the box filtering), the levels summed from the coarsest up,
 * each bilinearly enlarged. The GPU (LensFilter, lensgauss/*.glsl) and {@link #apply} (the settings' preview, the tests) run
 * this same plan.
 */
public final class DiffusionGauss {
    private DiffusionGauss() {}

    /** The three-Gaussian fit of a 2D exponential exp(-r / l) / (2 pi l^2): amplitude, sigma / l. */
    static final double[][] FIT = {{0.1633, 0.5360}, {0.6496, 1.5236}, {0.1870, 2.7684}};
    /** The smallest level side. */
    static final int MIN_LEVEL = 8;

    /** One Gaussian of the plan: its level, its sigma on that level (pixels of the level), its weight per channel. */
    public static final class Blur {
        public final int level;
        public final float sigma;
        public final float[] weight;

        Blur(int level, float sigma, float[] weight) {
            this.level = level;
            this.sigma = sigma;
            this.weight = weight;
        }
    }

    /** The plan: the scattered share p, the Gaussians, the coarsest level used. */
    public static final class Plan {
        public final double p;
        public final List<Blur> blurs;
        public final int top;

        Plan(double p, List<Blur> blurs, int top) {
            this.p = p;
            this.blurs = blurs;
            this.top = top;
        }
    }

    public static int levelSize(int size, int level) {
        return Math.max(1, size >> level);
    }

    /** The variance (full-size pixels squared) the box pyramid's first {@code level} halvings add. */
    static double boxVariance(int level) {
        return (Math.pow(4, level) - 1) / 12.0;
    }

    /**
     * The plan of filter {@code id} at {@code strength} with {@code tune} on a {@code w} x {@code h} image whose frame is
     * {@code longPx} long (the PSF's size: a 36 mm frame). Null: no diffusion filter.
     */
    @Nullable
    public static Plan plan(@Nullable String id, float strength, LensFilters.Tune tune, int w, int h, double longPx) {
        final DiffusionModel.Family f = DiffusionModel.family(id);
        final double p = DiffusionModel.scatter(id, strength);
        if (f == null || p <= 0 || w < 2 || h < 2) return null;
        final DiffusionModel.Shape s = DiffusionModel.shape(f, tune, longPx);
        final List<double[]> comps = new ArrayList<>(); // {lambda, w_r, w_g, w_b}
        for (int k = 0; k < s.core[0].length; k++) comps.add(new double[]{s.core[0][k], s.wc * s.core[1][k], s.wc * s.core[1][k], s.wc * s.core[1][k]});
        final double[][] halo = new double[3][];
        for (int c = 0; c < 3; c++) halo[c] = DiffusionModel.haloWeights(s.halo[1], s.warmth, c);
        for (int k = 0; k < s.halo[0].length; k++) comps.add(new double[]{s.halo[0][k], s.wh * halo[0][k], s.wh * halo[1][k], s.wh * halo[2][k]});
        for (int k = 0; k < s.bloom[0].length; k++) comps.add(new double[]{s.bloom[0][k], s.wb * s.bloom[1][k], s.wb * s.bloom[1][k], s.wb * s.bloom[1][k]});
        int lmax = 0;
        while (levelSize(w, lmax + 1) >= MIN_LEVEL && levelSize(h, lmax + 1) >= MIN_LEVEL) lmax++;
        final List<Blur> blurs = new ArrayList<>();
        int top = 0;
        for (double[] c : comps) for (double[] fit : FIT) {
            final double sigma = Math.max(fit[1] * c[0], 1e-3);
            int l = sigma < 6 ? 0 : (int) Math.floor(Math.log(sigma / 3) / Math.log(2));
            l = Math.max(0, Math.min(l, lmax));
            final double rest = Math.sqrt(Math.max(sigma * sigma - boxVariance(l), 0.0)) / (1 << l);
            final float[] wt = {(float) (fit[0] * c[1]), (float) (fit[0] * c[2]), (float) (fit[0] * c[3])};
            blurs.add(new Blur(l, (float) rest, wt));
            top = Math.max(top, l);
        }
        return new Plan(p, blurs, top);
    }

    // ───── the CPU run (the GPU's mirror)

    /** The box pyramid's next level (2 x 2 means, the edge clamped). */
    static float[] down(float[] src, int w, int h, int nw, int nh) {
        final float[] out = new float[nw * nh * 3];
        for (int y = 0; y < nh; y++) for (int x = 0; x < nw; x++) for (int c = 0; c < 3; c++) {
            float s = 0;
            for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++)
                s += src[(Math.min(h - 1, 2 * y + j) * w + Math.min(w - 1, 2 * x + i)) * 3 + c];
            out[(y * nw + x) * 3 + c] = s * .25f;
        }
        return out;
    }

    /** The taps of a Gaussian of {@code sigma} pixels (radius 3 sigma, at least the pixel itself), normalised. */
    static float[] taps(float sigma) {
        final int r = Math.min((int) Math.ceil(3 * sigma), 24); // lensgauss/blur.glsl's loop
        final float[] t = new float[2 * r + 1];
        float s = 0;
        for (int i = -r; i <= r; i++) s += t[i + r] = sigma < 1e-3f ? (i == 0 ? 1f : 0f) : (float) Math.exp(-0.5 * i * i / (sigma * sigma));
        for (int i = 0; i < t.length; i++) t[i] /= s;
        return t;
    }

    /** numpy's 'reflect' index (no edge repeat): the reference pads its image so. */
    static int mirror(int i, int n) {
        if (n == 1) return 0;
        final int period = 2 * (n - 1);
        i = Math.floorMod(i, period);
        return i < n ? i : period - i;
    }

    /** A separable Gaussian blur of an RGB image (the edge mirrored), along x then y. */
    static float[] blur(float[] src, int w, int h, float sigma) {
        final float[] t = taps(sigma);
        final int r = t.length / 2;
        final float[] a = new float[src.length], b = new float[src.length];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 3; c++) {
            float s = 0;
            for (int i = -r; i <= r; i++) s += t[i + r] * src[(y * w + mirror(x + i, w)) * 3 + c];
            a[(y * w + x) * 3 + c] = s;
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 3; c++) {
            float s = 0;
            for (int i = -r; i <= r; i++) s += t[i + r] * a[(mirror(y + i, h) * w + x) * 3 + c];
            b[(y * w + x) * 3 + c] = s;
        }
        return b;
    }

    /** {@link #blur} of one channel. */
    static float[] blur1(float[] src, int w, int h, float sigma) {
        final float[] rgb = new float[src.length * 3];
        for (int i = 0; i < src.length; i++) rgb[i * 3] = src[i];
        final float[] b = blur(rgb, w, h, sigma);
        final float[] out = new float[src.length];
        for (int i = 0; i < out.length; i++) out[i] = b[i * 3];
        return out;
    }

    /** A Gaussian of any width: on the box-pyramid level where it is under 8 pixels, enlarged back bilinearly. */
    static float[] blurLarge(float[] src, int w, int h, float sigma) {
        int l = 0;
        while (sigma / (1 << l) > 8 && levelSize(w, l + 1) >= MIN_LEVEL && levelSize(h, l + 1) >= MIN_LEVEL) l++;
        if (l == 0) return blur(src, w, h, sigma);
        float[] img = src;
        for (int k = 1; k <= l; k++) img = down(img, levelSize(w, k - 1), levelSize(h, k - 1), levelSize(w, k), levelSize(h, k));
        final int lw = levelSize(w, l), lh = levelSize(h, l);
        final float[] g = blur(img, lw, lh, (float) (Math.sqrt(Math.max(sigma * sigma - boxVariance(l), 0.25)) / (1 << l)));
        final float[] out = new float[src.length];
        final float[] s = new float[3];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            bilinear(g, lw, lh, w, h, x, y, s);
            System.arraycopy(s, 0, out, (y * w + x) * 3, 3);
        }
        return out;
    }

    /** A GL_LINEAR, clamp-to-edge sample of a coarse w x h RGB image at the centre of fine pixel (x, y) of an fw x fh image. */
    static void bilinear(float[] img, int w, int h, int fw, int fh, int x, int y, float[] out) {
        final double u = (x + .5) * w / fw - .5, v = (y + .5) * h / fh - .5;
        final int x0 = (int) Math.floor(u), y0 = (int) Math.floor(v);
        final double ax = u - x0, ay = v - y0;
        final int xa = Math.max(0, Math.min(w - 1, x0)), xb = Math.max(0, Math.min(w - 1, x0 + 1));
        final int ya = Math.max(0, Math.min(h - 1, y0)), yb = Math.max(0, Math.min(h - 1, y0 + 1));
        for (int c = 0; c < 3; c++) {
            final double a = img[(ya * w + xa) * 3 + c], b = img[(ya * w + xb) * 3 + c];
            final double d = img[(yb * w + xa) * 3 + c], e = img[(yb * w + xb) * 3 + c];
            out[c] = (float) ((a + (b - a) * ax) * (1 - ay) + (d + (e - d) * ax) * ay);
        }
    }

    /** The plan on an RGB float image (w x h x 3, linear light), in place. */
    public static void apply(float[] rgb, int w, int h, Plan plan) {
        final int top = plan.top;
        final float[][] lv = new float[top + 1][];
        lv[0] = rgb.clone();
        for (int l = 1; l <= top; l++)
            lv[l] = down(lv[l - 1], levelSize(w, l - 1), levelSize(h, l - 1), levelSize(w, l), levelSize(h, l));
        float[] acc = null;
        for (int l = top; l >= 0; l--) {
            final int lw = levelSize(w, l), lh = levelSize(h, l);
            final float[] sum = new float[lw * lh * 3];
            for (Blur b : plan.blurs) {
                if (b.level != l) continue;
                final float[] g = blur(lv[l], lw, lh, b.sigma);
                for (int i = 0; i < sum.length; i++) sum[i] += b.weight[i % 3] * g[i];
            }
            if (acc != null) {
                final int cw = levelSize(w, l + 1), ch = levelSize(h, l + 1);
                final float[] s = new float[3];
                for (int y = 0; y < lh; y++) for (int x = 0; x < lw; x++) {
                    bilinear(acc, cw, ch, lw, lh, x, y, s);
                    for (int c = 0; c < 3; c++) sum[(y * lw + x) * 3 + c] += s[c];
                }
            }
            acc = sum;
        }
        final float p = (float) plan.p;
        for (int i = 0; i < rgb.length; i++) rgb[i] = (1 - p) * rgb[i] + p * acc[i];
    }
}
