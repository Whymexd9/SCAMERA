package com.particlesdevs.photoncamera.processing.color;

import androidx.annotation.Nullable;

/**
 * P288 (owner, 2026-10-19: «Сделай точную FFT-свёртку, если это возможно»): the diffusion filters (DiffusionModel, the
 * reference model's families) applied as the reference does - out = (1 - p) E + p (K * E), K the filter's per-channel sum of
 * 2D exponentials sampled on the pixel grid, the image reflect-padded - by FFT convolution instead of the pyramid fit.
 * <p>
 * A full-resolution FFT of the whole PSF does not fit a phone (the bloom reaches across the frame: a 50 MP frame padded by its
 * radius is several 8192 x 8192 complex planes per channel), so each exponential is convolved on the level of a box pyramid
 * where its decay is 2..4 pixels (level 0, the image itself, for the narrower ones): there it is sampled as finely as needed,
 * its kernel cut at 8 decays like the reference's (and at half the level's size), normalised to its share of the PSF. A level
 * whose padded size fits {@link #GRID} is one FFT; a larger one is cut into overlapping tiles (overlap-save: each tile's border
 * the kernel's radius, only its middle kept). The levels are then summed from the coarsest up, each bilinearly enlarged (as a
 * GL texture samples) and added to the next finer one's convolution. The GPU (LensFilter, lensfft/*.glsl) runs exactly this
 * plan; {@link #apply} runs it on the CPU (the settings' previews, the tray's thumbnails, the tests), {@link #reference} the
 * reference's single full-size convolution (tests).
 */
public final class DiffusionFft {
    private DiffusionFft() {}

    /** The FFT grid's largest side (a level's tile). */
    public static final int GRID = 1024;
    /** The smallest level side. */
    static final int MIN_LEVEL = 16;
    /** A component is convolved on the coarsest level where its decay is at least this many pixels. */
    static double SAMPLES = 4;

    /** One level of the plan: its size, its components' kernel radius and FFT grid / tiles. */
    public static final class Tier {
        public final int level, w, h;
        /** The components on this level: decay (pixels of this level) and per-channel weight. */
        public final double[] lambda;
        public final double[][] weight;
        public final int radius, grid, useful, tilesX, tilesY;
        /** The whole PSF's sampled sum per channel (the reference normalises the PSF as one): this level's kernel / it. */
        final double[] norm = {1, 1, 1};

        /** {@code refRadius}: the reference's cut of the whole PSF (full-size pixels). */
        Tier(int level, int w, int h, double[] lambda, double[][] weight, int refRadius) {
            this.level = level;
            this.w = w;
            this.h = h;
            this.lambda = lambda;
            this.weight = weight;
            double lmax = 0;
            for (double l : lambda) lmax = Math.max(lmax, l);
            // the reference's cut on this level; a narrow component's kernel stops at 12 decays (its tail beyond: < 1e-4)
            int r = (int) Math.ceil(Math.min(refRadius / (double) (1 << level), Math.max(12.0 * lmax, 2.0)));
            r = Math.max(1, Math.min(r, Math.min(w, h) / 2 - 1));
            radius = lambda.length == 0 ? 0 : r;
            if (Math.max(w, h) + 2 * radius <= GRID) {
                int g = 8;
                while (g < Math.max(w, h) + 2 * radius) g <<= 1;
                grid = g;
                useful = g - 2 * radius;
                tilesX = tilesY = 1;
            } else {
                grid = GRID;
                useful = GRID - 2 * radius;
                tilesX = (w + useful - 1) / useful;
                tilesY = (h + useful - 1) / useful;
            }
        }

        public boolean empty() {
            return lambda.length == 0;
        }

        /**
         * The level's kernel on its FFT grid for channel c ([y][x] row-major, the centre at (0, 0) wrapped): its exponentials
         * sampled on this level (a level pixel stands for 4^level full-size ones: the decays in level pixels keep the energy)
         * over the whole PSF's sum ({@link #norm}).
         */
        public float[] kernel(int c) {
            final int g = grid, r = radius;
            final float[] k = new float[g * g];
            for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++)
                k[((dy + g) % g) * g + (dx + g) % g] = (float) (sampled(c, Math.sqrt(dx * dx + dy * dy), dx, dy) / norm[c]);
            return k;
        }

        double sampled(int c, double d) {
            return sampled(c, d, 0, 0);
        }

        /**
         * The kernel at offset (dx, dy) (distance d): the reference's point sample; a component narrower than half a pixel (only
         * on a small image: a preview, a thumbnail) as its mean over the pixel instead - a point sample of it is all centre and
         * the normalisation would leave no glow, where the photo (its decays a pixel or more) shows one.
         */
        double sampled(int c, double d, int dx, int dy) {
            double s = 0;
            for (int i = 0; i < lambda.length; i++) {
                final double l = lambda[i];
                if (l >= 0.5 || Math.abs(dx) > 3 || Math.abs(dy) > 3) {
                    s += weight[c][i] * e(d, l);
                    continue;
                }
                double m = 0;
                for (int j = 0; j < 8; j++) for (int k = 0; k < 8; k++) {
                    final double x = dx + (k + .5) / 8 - .5, y = dy + (j + .5) / 8 - .5;
                    m += e(Math.sqrt(x * x + y * y), l);
                }
                s += weight[c][i] * m / 64;
            }
            return s;
        }

        /** The level kernel's sum of channel c before the normalisation. */
        double sum(int c) {
            double s = 0;
            for (int dy = -radius; dy <= radius; dy++) for (int dx = -radius; dx <= radius; dx++) s += sampled(c, Math.sqrt(dx * dx + dy * dy), dx, dy);
            return s;
        }
    }

    /** The plan of a filter on an image: the scatter share p and the levels (index = level; some may be empty). */
    public static final class Plan {
        public final double p;
        public final Tier[] tiers;

        Plan(double p, Tier[] tiers) {
            this.p = p;
            this.tiers = tiers;
        }

        /** The coarsest level with components (-1: none). */
        public int top() {
            for (int l = tiers.length - 1; l >= 0; l--) if (!tiers[l].empty()) return l;
            return -1;
        }
    }

    /** The level sizes of a box pyramid (each half, at least 1). */
    static int levelSize(int size, int level) {
        return Math.max(1, size >> level);
    }

    /**
     * The plan for filter {@code id} at {@code strength} with {@code tune} on a {@code w} x {@code h} image whose frame is
     * {@code longPx} long (the PSF's size: a 36 mm frame; a lab fragment passes its whole frame's). Null: no diffusion filter.
     */
    @Nullable
    public static Plan plan(@Nullable String id, float strength, LensFilters.Tune tune, int w, int h, double longPx) {
        return plan(id, DiffusionModel.scatter(id, strength), tune, w, h, longPx, DiffusionModel.FRAME_UM);
    }

    /** P380: the plan of filter {@code id} deflecting {@code p}, its PSF on a frame {@code frameUm} long (the film's diffusion). */
    @Nullable
    public static Plan plan(@Nullable String id, double p, LensFilters.Tune tune, int w, int h, double longPx, double frameUm) {
        final DiffusionModel.Family f = DiffusionModel.family(id);
        if (f == null || !(p > 0) || w < 2 || h < 2) return null;
        final DiffusionModel.Shape s = DiffusionModel.shape(f, tune, longPx, frameUm);
        // the components and their per-channel weights (the PSF sums to 1 per channel)
        final java.util.List<double[]> comps = new java.util.ArrayList<>(); // {lambda, w_r, w_g, w_b}
        for (int k = 0; k < s.core[0].length; k++) comps.add(new double[]{s.core[0][k], s.wc * s.core[1][k], s.wc * s.core[1][k], s.wc * s.core[1][k]});
        final double[][] halo = new double[3][];
        for (int c = 0; c < 3; c++) halo[c] = DiffusionModel.haloWeights(s.halo[1], s.warmth, c);
        for (int k = 0; k < s.halo[0].length; k++) comps.add(new double[]{s.halo[0][k], s.wh * halo[0][k], s.wh * halo[1][k], s.wh * halo[2][k]});
        for (int k = 0; k < s.bloom[0].length; k++) comps.add(new double[]{s.bloom[0][k], s.wb * s.bloom[1][k], s.wb * s.bloom[1][k], s.wb * s.bloom[1][k]});
        double bloomMax = 0;
        for (double l : s.bloom[0]) bloomMax = Math.max(bloomMax, l);
        final int refRadius = Math.min((int) Math.ceil(Math.max(8 * bloomMax, 5)), Math.max(Math.min(w, h) / 2 - 1, 1));
        int lmax = 0;
        while (levelSize(w, lmax + 1) >= MIN_LEVEL && levelSize(h, lmax + 1) >= MIN_LEVEL) lmax++;
        final java.util.List<java.util.List<double[]>> byLevel = new java.util.ArrayList<>();
        for (int l = 0; l <= lmax; l++) byLevel.add(new java.util.ArrayList<>());
        for (double[] c : comps) {
            final double lambda = Math.max(c[0], 1e-6);
            int l = lambda < 2 * SAMPLES ? 0 : (int) Math.floor(Math.log(lambda / SAMPLES) / Math.log(2));
            byLevel.get(Math.max(0, Math.min(l, lmax))).add(c);
        }
        final Tier[] tiers = new Tier[lmax + 1];
        for (int l = 0; l <= lmax; l++) {
            final java.util.List<double[]> cs = byLevel.get(l);
            final double[] lam = new double[cs.size()];
            final double[][] wt = new double[3][cs.size()];
            for (int i = 0; i < cs.size(); i++) {
                lam[i] = cs.get(i)[0] / (1 << l);
                for (int c = 0; c < 3; c++) wt[c][i] = cs.get(i)[1 + c];
            }
            tiers[l] = new Tier(l, levelSize(w, l), levelSize(h, l), lam, wt, refRadius);
        }
        for (int c = 0; c < 3; c++) {
            double total = 0;
            for (Tier t : tiers) if (!t.empty()) total += t.sum(c);
            for (Tier t : tiers) t.norm[c] = total > 0 ? total : 1;
        }
        return new Plan(p, tiers);
    }

    // ───── the FFT (Stockham radix-2, natural order; lensfft/fft.glsl runs the same passes)

    /** In-place FFT of n = 2^k complex values (re, im) along a line: element i at off + i * step. sign -1 forward, +1 inverse. */
    static void fft(double[] re, double[] im, int off, int step, int n, int sign, double[] tr, double[] ti, double[] ur, double[] ui) {
        for (int i = 0; i < n; i++) {
            tr[i] = re[off + i * step];
            ti[i] = im[off + i * step];
        }
        double[] xr = tr, xi = ti, yr = ur, yi = ui;
        for (int ns = 1; ns < n; ns <<= 1) {
            for (int j = 0; j < n / 2; j++) {
                final int k = j % ns;
                final double ang = sign * 2 * Math.PI * k / (2.0 * ns), c = Math.cos(ang), s = Math.sin(ang);
                final double ar = xr[j], ai = xi[j], br0 = xr[j + n / 2], bi0 = xi[j + n / 2];
                final double br = br0 * c - bi0 * s, bi = br0 * s + bi0 * c;
                final int d = (j / ns) * ns * 2 + k;
                yr[d] = ar + br;
                yi[d] = ai + bi;
                yr[d + ns] = ar - br;
                yi[d + ns] = ai - bi;
            }
            double[] t = xr; xr = yr; yr = t;
            t = xi; xi = yi; yi = t;
        }
        for (int i = 0; i < n; i++) {
            re[off + i * step] = xr[i];
            im[off + i * step] = xi[i];
        }
    }

    /** 2D FFT of a g x g grid (rows, then columns). */
    static void fft2(double[] re, double[] im, int g, int sign) {
        final double[] tr = new double[g], ti = new double[g], ur = new double[g], ui = new double[g];
        for (int y = 0; y < g; y++) fft(re, im, y * g, 1, g, sign, tr, ti, ur, ui);
        for (int x = 0; x < g; x++) fft(re, im, x, g, g, sign, tr, ti, ur, ui);
    }

    // ───── the CPU run of the plan (the GPU's mirror)

    /** numpy's 'reflect' index (no edge repeat). */
    static int reflect(int i, int n) {
        if (n == 1) return 0;
        final int period = 2 * (n - 1);
        i = Math.floorMod(i, period);
        return i < n ? i : period - i;
    }

    /** The box pyramid's next level of an RGB image (2 x 2 means, the edge clamped). */
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

    /** One level's convolution (all channels) of {@code img} (the level image): tiles, FFT, kernel, inverse, middles kept. */
    static float[] convolve(Tier t, float[] img) {
        final int g = t.grid, r = t.radius, u = t.useful;
        final float[] out = new float[t.w * t.h * 3];
        final double[][] kr = new double[3][], ki = new double[3][];
        for (int c = 0; c < 3; c++) {
            final float[] k = t.kernel(c);
            kr[c] = new double[g * g];
            ki[c] = new double[g * g];
            for (int i = 0; i < k.length; i++) kr[c][i] = k[i];
            fft2(kr[c], ki[c], g, -1);
        }
        final double[] re = new double[g * g], im = new double[g * g];
        for (int ty = 0; ty < t.tilesY; ty++) for (int tx = 0; tx < t.tilesX; tx++) {
            final int ox = tx * u, oy = ty * u;
            for (int c = 0; c < 3; c++) {
                java.util.Arrays.fill(re, 0);
                java.util.Arrays.fill(im, 0);
                for (int gy = 0; gy < Math.min(g, u + 2 * r); gy++) for (int gx = 0; gx < Math.min(g, u + 2 * r); gx++) {
                    final int lx = reflect(ox - r + gx, t.w), ly = reflect(oy - r + gy, t.h);
                    re[gy * g + gx] = img[(ly * t.w + lx) * 3 + c];
                }
                fft2(re, im, g, -1);
                for (int i = 0; i < g * g; i++) {
                    final double a = re[i], b = im[i];
                    re[i] = a * kr[c][i] - b * ki[c][i];
                    im[i] = a * ki[c][i] + b * kr[c][i];
                }
                fft2(re, im, g, 1);
                final double scale = 1.0 / ((double) g * g);
                for (int ly = oy; ly < Math.min(t.h, oy + u); ly++) for (int lx = ox; lx < Math.min(t.w, ox + u); lx++)
                    out[(ly * t.w + lx) * 3 + c] = (float) (re[(ly - oy + r) * g + (lx - ox + r)] * scale);
            }
        }
        return out;
    }

    /** The plan on an RGB float image (w x h x 3, linear light), in place. */
    public static void apply(float[] rgb, int w, int h, Plan plan) {
        final int top = plan.top();
        if (top < 0) return;
        final float[][] lv = new float[top + 1][];
        lv[0] = rgb.clone();
        for (int l = 1; l <= top; l++)
            lv[l] = down(lv[l - 1], levelSize(w, l - 1), levelSize(h, l - 1), levelSize(w, l), levelSize(h, l));
        float[] acc = null;
        for (int l = top; l >= 0; l--) {
            final Tier t = plan.tiers[l];
            final float[] conv = t.empty() ? new float[t.w * t.h * 3] : convolve(t, lv[l]);
            if (acc != null) {
                final Tier c = plan.tiers[l + 1];
                final float[] s = new float[3];
                for (int y = 0; y < t.h; y++) for (int x = 0; x < t.w; x++) {
                    bilinear(acc, c.w, c.h, t.w, t.h, x, y, s);
                    for (int ch = 0; ch < 3; ch++) conv[(y * t.w + x) * 3 + ch] += s[ch];
                }
            }
            acc = conv;
        }
        final float p = (float) plan.p;
        for (int i = 0; i < rgb.length; i++) rgb[i] = (1 - p) * rgb[i] + p * acc[i];
    }

    /**
     * The reference's own convolution (tests): the whole PSF sampled at full size, cut at 8 x the largest decay (at most half the
     * image), normalised per channel, the image reflect-padded, one FFT; out = (1 - p) E + p (K * E).
     */
    public static void reference(float[] rgb, int w, int h, @Nullable String id, float strength, LensFilters.Tune tune, double longPx) {
        reference(rgb, w, h, id, DiffusionModel.scatter(id, strength), tune, longPx, DiffusionModel.FRAME_UM);
    }

    /** P380: the reference's convolution of filter {@code id} deflecting {@code p}, its PSF on a frame {@code frameUm} long. */
    public static void reference(float[] rgb, int w, int h, @Nullable String id, double p, LensFilters.Tune tune, double longPx,
                                 double frameUm) {
        final DiffusionModel.Family f = DiffusionModel.family(id);
        if (f == null || !(p > 0)) return;
        final DiffusionModel.Shape s = DiffusionModel.shape(f, tune, longPx, frameUm);
        double lmax = 0;
        for (double l : s.bloom[0]) lmax = Math.max(lmax, l);
        int r = (int) Math.ceil(Math.max(8 * lmax, 5));
        r = Math.min(r, Math.max(Math.min(w, h) / 2 - 1, 1));
        int g = 8;
        while (g < Math.max(w, h) + 2 * r) g <<= 1;
        final double[] re = new double[g * g], im = new double[g * g];
        final float[] src = rgb.clone();
        for (int c = 0; c < 3; c++) {
            final double[] halo = DiffusionModel.haloWeights(s.halo[1], s.warmth, c);
            final double[] kre = new double[g * g], kim = new double[g * g];
            double sum = 0;
            for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
                final double d = Math.sqrt(dx * dx + dy * dy);
                double v = 0;
                for (int k = 0; k < s.core[0].length; k++) v += s.wc * s.core[1][k] * e(d, s.core[0][k]);
                for (int k = 0; k < s.halo[0].length; k++) v += s.wh * halo[k] * e(d, s.halo[0][k]);
                for (int k = 0; k < s.bloom[0].length; k++) v += s.wb * s.bloom[1][k] * e(d, s.bloom[0][k]);
                kre[((dy + g) % g) * g + (dx + g) % g] = v;
                sum += v;
            }
            for (int i = 0; i < kre.length; i++) kre[i] /= sum;
            fft2(kre, kim, g, -1);
            java.util.Arrays.fill(re, 0);
            java.util.Arrays.fill(im, 0);
            for (int gy = 0; gy < h + 2 * r; gy++) for (int gx = 0; gx < w + 2 * r; gx++)
                re[gy * g + gx] = src[(reflect(gy - r, h) * w + reflect(gx - r, w)) * 3 + c];
            fft2(re, im, g, -1);
            for (int i = 0; i < g * g; i++) {
                final double a = re[i], b = im[i];
                re[i] = a * kre[i] - b * kim[i];
                im[i] = a * kim[i] + b * kre[i];
            }
            fft2(re, im, g, 1);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                final int i = (y * w + x) * 3 + c;
                rgb[i] = (float) ((1 - p) * src[i] + p * re[(y + r) * g + x + r] / ((double) g * g));
            }
        }
    }

    private static double e(double d, double lambda) {
        final double l = Math.max(lambda, 1e-6);
        return Math.exp(-d / l) / (2 * Math.PI * l * l);
    }
}
