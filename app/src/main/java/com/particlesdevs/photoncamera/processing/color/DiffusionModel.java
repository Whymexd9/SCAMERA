package com.particlesdevs.photoncamera.processing.color;

import androidx.annotation.Nullable;

/**
 * Owner, 2026-10-18 («Посмотри как симуляция сделана ... и исправь у нас»): the diffusion filters as a physical model of light
 * scattered in the filter.
 * <ul>
 * <li>Energy kept, no threshold: a filter deflects a share p of all the light and spreads it with its point-spread function,
 *     out = (1 - p) E + p (K * E), on the scene-linear image. What glows is what is bright; the veil over the frame comes from
 *     the same light (no separate knee, lift or veil numbers).</li>
 * <li>K is a sum of three groups of 2D exponentials E(r; λ) = exp(-r / λ) / (2 π λ²): a tight core, a halo and a bloom whose
 *     sub-components follow a power law (a tail decaying as r^-α). Each group spans a geometric range of λ around its centre.</li>
 * <li>The halo is tinted without adding energy: warm light moves to its outer components, cool light to its inner ones (the warm
 *     rim / cool core seen around lights through mist filters).</li>
 * <li>The strength is the filter grade (1/8 .. 2); p comes from a table of grades times the family's gain.</li>
 * </ul>
 * Sizes are on a 36 mm wide frame (micrometres), so the look is the same share of the frame at any resolution. On the GPU and
 * the CPU the PSF is a weighted sum of our pyramid's levels: {@link #levelWeights} fits non-negative weights of the levels
 * (each a Gaussian of a known width; level 0 the image itself) to the PSF's encircled energy, per channel.
 */
public final class DiffusionModel {
    /** The frame the micrometres refer to (a 36 mm wide film frame). */
    static final double FRAME_UM = 36000.0;

    private DiffusionModel() {}

    /** One group of exponentials: its centre λ (µm), the range factor, the count, and the bloom's tail exponent. */
    static final class Group {
        final double lambdaUm, spread, alpha;
        final int n;

        Group(double lambdaUm, double spread, int n, double alpha) {
            this.lambdaUm = lambdaUm;
            this.spread = spread;
            this.n = n;
            this.alpha = alpha;
        }
    }

    /** A filter family: the three groups, their shares, the halo's warmth and the scatter gain. */
    static final class Family {
        final Group core, halo, bloom;
        final double wc, wh, wb, warmth, gain;

        Family(Group core, Group halo, Group bloom, double wc, double wh, double wb, double warmth, double gain) {
            this.core = core;
            this.halo = halo;
            this.bloom = bloom;
            this.wc = wc;
            this.wh = wh;
            this.wb = wb;
            this.warmth = warmth;
            this.gain = gain;
        }
    }

    /**
     * The families: Glimmerglass core-dominant (sharp, a faint long tail); Black Pro-Mist halo-dominant (a punchy tight halo, a
     * steep tail, deep blacks); Pearlescent a broad pastel halo with a balanced veil; CineBloom bloom-dominant (frame-wide reach,
     * the warmest rim); Fog a frame-wide veil with the shallowest tail; Double Fog keeps more of the core (detail) with a halo
     * between the two.
     */
    @Nullable
    static Family family(@Nullable String id) {
        switch (id == null ? "" : id) {
            case LensFilters.GLIMMERGLASS:
                return new Family(new Group(10, 1.5, 2, 0), new Group(50, 2, 3, 0), new Group(260, 2.5, 4, 3.2), 0.60, 0.30, 0.10, 0.0, 0.65);
            case LensFilters.BLACK_PRO_MIST:
                return new Family(new Group(16, 1.5, 2, 0), new Group(95, 2, 3, 0), new Group(380, 2.5, 4, 3.5), 0.40, 0.47, 0.13, 0.65, 0.75);
            case LensFilters.PEARLESCENT:
                return new Family(new Group(14, 1.5, 2, 0), new Group(150, 2, 3, 0), new Group(650, 2.5, 4, 2.9), 0.28, 0.42, 0.30, 0.40, 1.05);
            case LensFilters.CINEBLOOM:
                return new Family(new Group(20, 1.5, 2, 0), new Group(200, 2, 3, 0), new Group(1000, 2.5, 4, 2.5), 0.22, 0.30, 0.48, 0.85, 1.00);
            case LensFilters.FOG:
                return new Family(new Group(20, 1.5, 2, 0), new Group(300, 2, 3, 0), new Group(1500, 2.5, 4, 2.3), 0.20, 0.20, 0.60, 0.10, 1.10);
            case LensFilters.DOUBLE_FOG:
                return new Family(new Group(16, 1.5, 2, 0), new Group(220, 2, 3, 0), new Group(1200, 2.5, 4, 2.6), 0.35, 0.30, 0.35, 0.20, 0.95);
            default:
                return null;
        }
    }

    /** Whether the filter is a diffusion filter (this model). */
    public static boolean diffuses(@Nullable String id) {
        return family(id) != null;
    }

    private static final double[] GRADE = {0.125, 0.25, 0.5, 1.0, 2.0};
    private static final double[] SCATTER = {0.10, 0.20, 0.35, 0.55, 0.75};

    /** Our strength (0..1, 0.5 as the default) as the filter grade: 0 none, 0.5 = 1/2, 1 = grade 2 (log scale). */
    static double grade(float strength) {
        return strength <= 0f ? 0.0 : Math.pow(2.0, 4.0 * Math.min(1f, strength) - 3.0);
    }

    /** The deflected share p of the light at our strength (the grade table, log-interpolated, times the family gain). */
    public static double scatter(@Nullable String id, float strength) {
        Family f = family(id);
        double g = grade(strength);
        if (f == null || g <= 0) return 0.0;
        double lg = Math.log(g) / Math.log(2);
        double base;
        if (lg <= -3) base = SCATTER[0] * Math.pow(2.0, lg + 3);   // below 1/8: towards 0
        else if (lg >= 1) base = SCATTER[SCATTER.length - 1];
        else {
            int i = (int) Math.floor(lg + 3);
            double t = (lg + 3) - i;
            base = SCATTER[i] + (SCATTER[Math.min(i + 1, SCATTER.length - 1)] - SCATTER[i]) * t;
        }
        return Math.max(0.0, Math.min(0.99, base * f.gain));
    }

    /** A group's λs (geometric range) and weights (uniform; the bloom's ∝ λ^(2-α)), the weights summing to 1. */
    static double[][] expand(Group g, boolean bloom) {
        int n = Math.max(1, g.n);
        double[] l = new double[n], w = new double[n];
        double sum = 0;
        for (int k = 0; k < n; k++) {
            l[k] = n == 1 ? g.lambdaUm : Math.exp(Math.log(g.lambdaUm / g.spread) + (Math.log(g.lambdaUm * g.spread) - Math.log(g.lambdaUm / g.spread)) * k / (n - 1));
            w[k] = bloom ? Math.pow(l[k], 2.0 - g.alpha) : 1.0;
            sum += w[k];
        }
        for (int k = 0; k < n; k++) w[k] /= sum;
        return new double[][]{l, w};
    }

    /** The warmth axis per channel: warm light (R, a little G) to the outer halo, B to the inner. */
    private static final double[] WARM_AXIS = {1.30, 0.15, -1.45};

    /** The halo's weights of channel c with the warmth: moved along the inner→outer gradient, clipped, energy kept. */
    static double[] haloWeights(double[] w, double warmth, int c) {
        int n = w.length;
        if (n < 2) return w.clone();
        warmth = Math.max(-1.5, Math.min(1.5, warmth));
        double[] g = new double[n];
        double mean = 0, total = 0;
        for (int k = 0; k < n; k++) {
            g[k] = -1.0 + 2.0 * k / (n - 1);
            mean += w[k] * g[k];
            total += w[k];
        }
        mean /= total;
        double[] out = new double[n];
        double s = 0;
        for (int k = 0; k < n; k++) {
            out[k] = Math.max(0.0, w[k] * (1.0 + warmth * WARM_AXIS[c] * (g[k] - mean)));
            s += out[k];
        }
        if (s <= 0) return w.clone();
        for (int k = 0; k < n; k++) out[k] *= total / s;
        return out;
    }

    /** The share of a 2D exponential's energy within radius r (both in the same unit). */
    static double eeExp(double r, double lambda) {
        double x = r / Math.max(lambda, 1e-9);
        return 1.0 - (1.0 + x) * Math.exp(-x);
    }

    /** The share of a 2D Gaussian's energy within radius r. */
    static double eeGauss(double r, double sigma) {
        if (sigma <= 0) return r > 0 ? 1.0 : 0.0;
        return 1.0 - Math.exp(-r * r / (2 * sigma * sigma));
    }

    /** The filter's shape with the fine controls: the groups scaled, their shares and the warmth. */
    static final class Shape {
        final double[][] core, halo, bloom;   // {λ px..., weights...}
        final double wc, wh, wb, warmth;

        Shape(double[][] core, double[][] halo, double[][] bloom, double wc, double wh, double wb, double warmth) {
            this.core = core;
            this.halo = halo;
            this.bloom = bloom;
            this.wc = wc;
            this.wh = wh;
            this.wb = wb;
            this.warmth = warmth;
        }
    }

    /**
     * The shape in pixels of an image {@code longPx} long with the fine controls: «Радиус» the size ×0.5..×2, «Ореол» the halo's
     * share ×0.5..×2, «Дымка» the bloom's share ×0..×4 (the shares renormalised: the PSF keeps unit energy), «Тон свечения» the
     * warmth ±0.8 around the family's own.
     */
    static Shape shape(Family f, LensFilters.Tune t, double longPx) {
        double size = Math.pow(2.0, (t.radius - 0.5) * 2.0);
        double px = longPx / FRAME_UM * size;
        double hi = Math.pow(2.0, (t.knee - 0.5) * 2.0), bi = LensFilters.hazeGain(t.haze);
        double wc = f.wc, wh = f.wh * hi, wb = f.wb * bi, total = wc + wh + wb;
        double[][] core = expand(f.core, false), halo = expand(f.halo, false), bloom = expand(f.bloom, true);
        for (double[][] g : new double[][][]{core, halo, bloom}) for (int k = 0; k < g[0].length; k++) g[0][k] *= px;
        return new Shape(core, halo, bloom, wc / total, wh / total, wb / total, f.warmth + (t.warmth - 0.5) * 1.6);
    }

    /** The PSF's encircled energy at radius r (pixels), channel c. */
    static double ee(Shape s, int c, double r) {
        double e = 0;
        for (int k = 0; k < s.core[0].length; k++) e += s.wc * s.core[1][k] * eeExp(r, s.core[0][k]);
        double[] hw = haloWeights(s.halo[1], s.warmth, c);
        for (int k = 0; k < s.halo[0].length; k++) e += s.wh * hw[k] * eeExp(r, s.halo[0][k]);
        for (int k = 0; k < s.bloom[0].length; k++) e += s.wb * s.bloom[1][k] * eeExp(r, s.bloom[0][k]);
        return e;
    }

    /**
     * The photo pyramid's level widths in pixels of the full image: level k is the image Gaussian-downsampled k times (σ 1 texel
     * a step; {@code stepVariance} = that σ², 1 on the GPU's gaussdown, 0.75 for the CPU's [1 3 3 1]) and B-spline upsampled
     * back step by step (σ² 1/3 texel a step), σ_k² = (stepVariance + 4/3) (4^k - 1) / 3; level 0 the image itself.
     */
    public static double[] pyramidSigmas(int levels, double stepVariance) {
        double[] s = new double[levels + 1];
        for (int k = 1; k <= levels; k++) s[k] = Math.sqrt((stepVariance + 4.0 / 3.0) * (Math.pow(4, k) - 1) / 3.0);
        return s;
    }

    /**
     * The live viewfinder's widths in screen pixels: level k is the mip level k - 1 of the half-size frame (2x2 box steps from
     * the screen, σ² (2^i)²/3 each) sampled with a B-spline (σ² (2^k)²/3); level 0 the frame itself.
     */
    public static double[] mipSigmas(int levels) {
        double[] s = new double[levels + 1];
        for (int k = 1; k <= levels; k++) s[k] = Math.sqrt((Math.pow(4, k) - 1) / 9.0 + Math.pow(4, k) / 3.0);
        return s;
    }

    /**
     * The weights of the pyramid levels (index 0 = the image itself, then the levels of {@code sigmas}) per channel, [level][c],
     * so that Σ_k W_k,c L_k,c = (1 - p) E + p (K * E) of filter {@code id} at {@code strength} with its tune, on an image
     * {@code longPx} pixels long: non-negative weights fitted to the PSF's encircled energy at 48 radii (0.5 px .. the frame),
     * each channel summing to 1. Null for a filter that does not diffuse or at strength 0.
     */
    @Nullable
    public static float[][] levelWeights(@Nullable String id, float strength, LensFilters.Tune tune, double longPx, double[] sigmas) {
        Family f = family(id);
        double p = scatter(id, strength);
        if (f == null || p <= 0 || longPx <= 0) return null;
        Shape s = shape(f, tune, longPx);
        int n = sigmas.length, m = 48;
        double[] radii = new double[m];
        for (int i = 0; i < m; i++) radii[i] = 0.5 * Math.pow(longPx / 0.5, i / (double) (m - 1));
        double[][] basis = new double[n][m];
        for (int k = 0; k < n; k++) for (int i = 0; i < m; i++) basis[k][i] = eeGauss(radii[i], sigmas[k]);
        float[][] out = new float[n][3];
        for (int c = 0; c < 3; c++) {
            double[] target = new double[m];
            for (int i = 0; i < m; i++) target[i] = ee(s, c, radii[i]);
            double[] a = nnls(basis, target);
            double sum = 0;
            for (double v : a) sum += v;
            if (sum <= 0) { a = new double[n]; a[0] = 1; sum = 1; }
            for (int k = 0; k < n; k++) out[k][c] = (float) (p * a[k] / sum + (k == 0 ? 1.0 - p : 0.0));
        }
        return out;
    }

    /** Non-negative least squares by cyclic coordinate descent (a dozen unknowns): min |Σ a_k B_k - t|², a ≥ 0. */
    static double[] nnls(double[][] b, double[] t) {
        int n = b.length, m = t.length;
        double[][] h = new double[n][n];
        double[] g = new double[n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < m; i++) g[j] += b[j][i] * t[i];
            for (int k = 0; k < n; k++) {
                double v = 0;
                for (int i = 0; i < m; i++) v += b[j][i] * b[k][i];
                h[j][k] = v;
            }
        }
        double[] a = new double[n];
        for (int it = 0; it < 4000; it++) {
            double change = 0;
            for (int j = 0; j < n; j++) {
                if (h[j][j] <= 1e-12) continue;
                double r = g[j];
                for (int k = 0; k < n; k++) r -= h[j][k] * a[k];
                double next = Math.max(0.0, a[j] + r / h[j][j]);
                change = Math.max(change, Math.abs(next - a[j]));
                a[j] = next;
            }
            if (change < 1e-10) break;
        }
        return a;
    }
}
