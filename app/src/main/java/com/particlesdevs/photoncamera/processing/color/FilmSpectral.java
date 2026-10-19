package com.particlesdevs.photoncamera.processing.color;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * P286 (owner, 2026-10-19: the film simulation as close to the reference app as possible): a port of spektrafilm's per-pixel
 * colour pipeline (Andrea Volpato, https://github.com/andreavolpato/spektrafilm, GPL-3.0 code; its film and paper profiles
 * CC BY-SA 4.0 - assets/film/LICENSE-spektrafilm.txt, assets/film/CHANGELOG.txt) on the arrays its own code exported
 * (research/filmsim/export.py): the colour through the film's spectral upsampling (Hanatos 2025, the film's raw LUT over the
 * chromaticity, Mitchell-Netravali cubic), the film's characteristic curves with the DIR couplers (the curves before the
 * couplers, the inhibition of the developed density), the enlarger (TH-KG3 lamp through the dichroic filters set to the
 * database's neutral values plus the user's shifts) through the negative's dye densities onto the paper's sensitivities,
 * normalised on the mid grey the film's exposure gives, the paper's curves, and the scan of the print (or of a slide) to
 * sRGB with spektrafilm's output gamut compression, baked into a 3D LUT over the print's (slide's) densities by its own code.
 * Matches spektrafilm's output (spatial and stochastic effects off) within 0.003 of the encoded sRGB on 13 film / paper /
 * exposure / filter cases (FilmSpectralTest).
 */
public final class FilmSpectral {
    private FilmSpectral() {}

    /** Where the arrays come from (the app's assets; a test's files). */
    public interface Source {
        InputStream open(String name) throws IOException;
    }

    private static volatile Source source = name -> com.particlesdevs.photoncamera.app.ScameraApp.getAppContext()
            .getAssets().open("film/" + name);

    static void source(Source s) {
        source = s;
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    // ───── the stocks (the order of the exported neutral filter table)

    static final String[] FILMS = {"fujifilm_c200", "fujifilm_pro_400h", "fujifilm_provia_100f", "fujifilm_velvia_100",
            "fujifilm_xtra_400", "kodak_ektachrome_100", "kodak_ektar_100", "kodak_gold_200", "kodak_kodachrome_64", "kodak_portra_160",
            "kodak_portra_400", "kodak_portra_800", "kodak_portra_800_push1", "kodak_portra_800_push2", "kodak_ultramax_400",
            "kodak_verita_200d", "kodak_vision3_200t", "kodak_vision3_250d", "kodak_vision3_500t", "kodak_vision3_50d"};
    static final String[] PAPERS = {"fujifilm_crystal_archive_typeii", "kodak_2383", "kodak_2393", "kodak_ektacolor_edge",
            "kodak_endura_premier", "kodak_portra_endura", "kodak_supra_endura", "kodak_ultra_endura"};

    // ───── the arrays

    static final class Arr {
        final int[] dims;
        final double[] v;

        Arr(int[] dims, double[] v) {
            this.dims = dims;
            this.v = v;
        }
    }

    private static final Map<String, Map<String, Arr>> CACHE = new HashMap<>();

    static Map<String, Arr> arrays(String file) throws IOException {
        synchronized (CACHE) {
            final Map<String, Arr> hit = CACHE.get(file);
            if (hit != null) return hit;
        }
        final byte[] bytes;
        try (InputStream in = source.open(file)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
            final byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            bytes = out.toByteArray();
        }
        final Map<String, Arr> m = parse(bytes);
        synchronized (CACHE) {
            CACHE.put(file, m);
        }
        return m;
    }

    /** 'SFD1', count, then per array: name, dtype (0 f32, 1 f16), dims, little-endian data. */
    static Map<String, Arr> parse(byte[] bytes) throws IOException {
        final ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 8 || b.get() != 'S' || b.get() != 'F' || b.get() != 'D' || b.get() != '1') throw new IOException("not SFD1");
        final int count = b.getInt();
        final Map<String, Arr> m = new HashMap<>();
        for (int a = 0; a < count; a++) {
            final byte[] nb = new byte[b.get() & 0xff];
            b.get(nb);
            final int dt = b.get(), nd = b.get();
            final int[] dims = new int[nd];
            int n = 1;
            for (int i = 0; i < nd; i++) n *= dims[i] = b.getInt();
            final double[] v = new double[n];
            for (int i = 0; i < n; i++) v[i] = dt == 1 ? half(b.getShort()) : b.getFloat();
            m.put(new String(nb, java.nio.charset.StandardCharsets.US_ASCII), new Arr(dims, v));
        }
        return m;
    }

    static float half(short h) {
        final int s = (h >> 15) & 1, e = (h >> 10) & 0x1f, f = h & 0x3ff;
        final float v;
        if (e == 0) v = f * (1f / (1 << 24));
        else if (e == 31) v = f == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        else v = Float.intBitsToFloat(((e + 112) << 23) | (f << 13));
        return s == 1 ? -v : v;
    }

    private static double[] get(Map<String, Arr> m, String k) throws IOException {
        final Arr a = m.get(k);
        if (a == null) throw new IOException("missing " + k);
        return a.v;
    }

    // ───── a film

    static final int N_LE = 256, N_WL = 81;

    static final class Film {
        final String id;
        final boolean positive;
        final double[] m, tc, le, dc, cd, bd, dir, dcl;
        final int tcSize;
        /** The curves above their minimum, before the couplers, their maxima. */
        final double[] dcN = new double[N_LE * 3], dc0 = new double[N_LE * 3], dmaxN = new double[3];
        @Nullable final Scan scan;

        Film(String id) throws IOException {
            this.id = id;
            final Map<String, Arr> a = arrays(id + ".bin");
            m = get(a, "M");
            tc = get(a, "tc");
            tcSize = a.get("tc").dims[0];
            le = get(a, "le");
            dc = get(a, "dc");
            cd = get(a, "cd");
            bd = get(a, "bd");
            dir = get(a, "dir");
            dcl = get(a, "dcl");
            scan = a.containsKey("scan") ? new Scan(a) : null;
            positive = scan != null;
            for (int c = 0; c < 3; c++) {
                double lo = Double.MAX_VALUE;
                for (int i = 0; i < N_LE; i++) lo = Math.min(lo, dc[i * 3 + c]);
                double hi = -Double.MAX_VALUE;
                for (int i = 0; i < N_LE; i++) hi = Math.max(hi, dcN[i * 3 + c] = dc[i * 3 + c] - lo);
                dmaxN[c] = hi;
            }
            // compute_density_curves_before_dir_couplers
            final double[] le0 = new double[N_LE], col = new double[N_LE];
            for (int c = 0; c < 3; c++) {
                for (int i = 0; i < N_LE; i++) {
                    double amount = 0;
                    for (int k = 0; k < 3; k++) amount += (positive ? dmaxN[k] - dcN[i * 3 + k] : dcN[i * 3 + k]) * dir[k * 3 + c];
                    le0[i] = le[i] - amount;
                    col[i] = positive ? -dcN[i * 3 + c] : dcN[i * 3 + c];
                }
                for (int i = 0; i < N_LE; i++) {
                    final double v = interp(le[i], le0, col, 1, 0);
                    dc0[i * 3 + c] = positive ? -v : v;
                }
            }
        }

        /** rgb_to_raw_hanatos2025 on linear sRGB: the raw exposure of the three layers. */
        void raw(double r, double g, double b, double[] out) {
            final double x = m[0] * r + m[1] * g + m[2] * b, y = m[3] * r + m[4] * g + m[5] * b, z = m[6] * r + m[7] * g + m[8] * b;
            double s = x + y + z;
            final double d = Math.max(s, 1e-10), tx = x / d, ty = y / d;
            final double qy = clamp01(ty / Math.max(1 - tx, 1e-10)), qx = clamp01((1 - tx) * (1 - tx));
            cubic2d(tc, tcSize, qx, qy, out);
            if (!Double.isFinite(s)) s = 0;
            for (int c = 0; c < 3; c++) out[c] *= s;
        }

        /** develop(): the curves (contrast {@code gamma}) and the DIR couplers. */
        void develop(double[] logRaw, double gamma, double[] out) {
            final double[] d = new double[3];
            for (int c = 0; c < 3; c++) d[c] = interp(logRaw[c] * gamma, le, dcN, 3, c);
            for (int c = 0; c < 3; c++) {
                double corr = 0;
                for (int k = 0; k < 3; k++) corr += (positive ? dmaxN[k] - d[k] : d[k]) * dir[k * 3 + c];
                out[c] = interp((logRaw[c] - corr) * gamma, le, dc0, 3, c);
            }
        }

        /**
         * The grain's density sigma per layer at developed densities {@code d} (above the base) for a 1 um pixel (it scales as
         * 1 / pixel size): the reference's particle model (apply_grain_to_density_layers, Poisson-binomial) - per sub-layer
         * (fast, mid, slow emulsion) of each colour layer, x = its density plus its share of the minimum density, p = x / its
         * maximum, n = the pixel's area over the particle area, od = maximum / n: variance x od (1 - p u). Checked against its
         * Monte Carlo (research/filmsim/grainchk.py) within 1 %.
         */
        void grainSigma(double[] d, double[] out) {
            for (int c = 0; c < 3; c++) {
                double tot = 0;
                final double[] dml = new double[3];
                for (int l = 0; l < 3; l++) {
                    double hi = -Double.MAX_VALUE;
                    for (int i = 0; i < N_LE; i++) hi = Math.max(hi, dcl[i * 9 + l * 3 + c]);
                    dml[l] = hi;
                    tot += hi;
                }
                double var = 0;
                for (int l = 0; l < 3; l++) {
                    final double frac = dml[l] / tot, dmin = frac * GRAIN_DMIN[c], dmax = dml[l] + dmin;
                    final double layer = positive ? interpDesc(d[c], dcN, c, dcl, l * 3 + c) : interp(d[c], dcN, c, dcl, l * 3 + c);
                    final double x = layer + dmin;
                    final double pr = Math.min(Math.max(x / dmax, 1e-6), 1 - 1e-6);
                    final double n = frac / (GRAIN_AREA * GRAIN_SCALE[c] * GRAIN_LAYER_SCALE[l]);
                    var += x * (dmax / n) * (1 - pr * GRAIN_UNIFORMITY[c] * (1 - 1e-6));
                }
                out[c] = Math.sqrt(Math.max(var, 0));
            }
        }

        /** The spectral density of a mid grey exposed {@code ev} stops (develop_simple: the raw curves, no couplers). */
        double[] midSpectral(double ev, double gamma) {
            final double[] raw = new double[3];
            final double v = 0.184 * Math.pow(2, ev);
            raw(v, v, v, raw);
            final double[] d = new double[3];
            for (int c = 0; c < 3; c++) d[c] = interp(Math.log10(raw[c] + 1e-10) * gamma, le, dc, 3, c);
            return spectral(cd, bd, d);
        }
    }

    /**
     * P289: the grain parameters of the reference app (its defaults: particle area 0.1 um2, per colour 1.2 / 1.0 / 2.5 and per
     * sub-layer 6 / 1 / 0.4 scales, minimum density 0.04 / 0.05 / 0.06, uniformity 0.99 / 0.97 / 0.98).
     */
    static final double GRAIN_AREA = 0.1;
    static final double[] GRAIN_SCALE = {1.2, 1.0, 2.5}, GRAIN_LAYER_SCALE = {6.0, 1.0, 0.4}, GRAIN_DMIN = {.04, .05, .06},
            GRAIN_UNIFORMITY = {.99, .97, .98};
    /** The grain's final blur (um of film, the reference app's grainFinalBlurUm) applied to the density noise. */
    public static final float GRAIN_BLUR_UM = 11.8f;

    /** interp of x over column {@code xc} of a stride-3 table to column {@code yc} of a stride-9 table (the density curve). */
    static double interp(double x, double[] xp3, int xc, double[] fp9, int yc) {
        final int n = N_LE;
        if (!(x > xp3[xc])) return fp9[yc];
        if (x >= xp3[(n - 1) * 3 + xc]) return fp9[(n - 1) * 9 + yc];
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            final int mid = (lo + hi) >>> 1;
            if (xp3[mid * 3 + xc] <= x) lo = mid; else hi = mid;
        }
        final double dx = xp3[hi * 3 + xc] - xp3[lo * 3 + xc];
        final double t = dx > 0 ? (x - xp3[lo * 3 + xc]) / dx : 0;
        return fp9[lo * 9 + yc] + (fp9[hi * 9 + yc] - fp9[lo * 9 + yc]) * t;
    }

    /** The same over a falling curve (a positive film): interp(-x, -curve, layer). */
    static double interpDesc(double x, double[] xp3, int xc, double[] fp9, int yc) {
        final double[] neg = new double[N_LE * 3]; // a positive's curve falls with exposure: its negative rises
        for (int i = 0; i < N_LE; i++) neg[i * 3 + xc] = -xp3[i * 3 + xc];
        return interp(-x, neg, xc, fp9, yc);
    }

    // ───── a paper (or a slide's scan)

    static final class Scan {
        final double[] lut, lo, hi;
        final int n;

        Scan(Map<String, Arr> a) throws IOException {
            lut = get(a, "scan");
            n = a.get("scan").dims[0];
            lo = get(a, "scanmin");
            hi = get(a, "scanmax");
        }

        /** The scan of densities to encoded sRGB (the LUT is [b][g][r], trilinear). */
        void apply(double[] d, double[] out) {
            final int[] i0 = new int[3];
            final double[] t = new double[3];
            for (int c = 0; c < 3; c++) {
                final double p = clamp01((d[c] - lo[c]) / (hi[c] - lo[c])) * (n - 1);
                i0[c] = Math.min((int) Math.floor(p), n - 2);
                t[c] = p - i0[c];
            }
            out[0] = out[1] = out[2] = 0;
            for (int dz = 0; dz < 2; dz++) for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
                final double w = (dx == 1 ? t[0] : 1 - t[0]) * (dy == 1 ? t[1] : 1 - t[1]) * (dz == 1 ? t[2] : 1 - t[2]);
                final int idx = (((i0[2] + dz) * n + (i0[1] + dy)) * n + (i0[0] + dx)) * 3;
                for (int c = 0; c < 3; c++) out[c] += w * lut[idx + c];
            }
        }
    }

    static final class Paper {
        final String id;
        final double[] sens, le, dcm, cd, bd;
        final Scan scan;

        Paper(String id) throws IOException {
            this.id = id;
            final Map<String, Arr> a = arrays(id + ".bin");
            sens = get(a, "sens");
            le = get(a, "le");
            dcm = get(a, "dcm");
            cd = get(a, "cd");
            bd = get(a, "bd");
            scan = new Scan(a);
        }
    }

    private static final Map<String, Object> STOCK_CACHE = new HashMap<>();

    static synchronized Film film(String id) throws IOException {
        Object f = STOCK_CACHE.get("f:" + id);
        if (f == null) STOCK_CACHE.put("f:" + id, f = new Film(id));
        return (Film) f;
    }

    static synchronized Paper paper(String id) throws IOException {
        Object p = STOCK_CACHE.get("p:" + id);
        if (p == null) STOCK_CACHE.put("p:" + id, p = new Paper(id));
        return (Paper) p;
    }

    // ───── the render

    /** One film (and paper) with the user's settings: {@link #render} per colour. */
    public static final class Engine {
        final Film film;
        @Nullable final Paper paper;
        final double ev, printExposure, gamma, factor;
        final double[] light;

        /**
         * @param ev film exposure (stops), @param printExposure the enlarger's exposure (linear), @param mShift / yShift the
         * magenta / yellow filters over the neutral (Kodak CC), @param gamma the film's contrast (density_curve_gamma).
         */
        Engine(Film film, @Nullable Paper paper, double ev, double printExposure, double mShift, double yShift, double gamma)
                throws IOException {
            this.film = film;
            this.paper = film.positive ? null : paper;
            this.ev = ev;
            this.printExposure = printExposure;
            this.gamma = gamma;
            if (this.paper == null) {
                light = null;
                factor = 1;
                return;
            }
            final Map<String, Arr> sh = arrays("shared.bin");
            final double[] lamp = get(sh, "thkg3"), dich = get(sh, "dichroic"), neutral = get(sh, "neutral");
            final int pi = indexOf(PAPERS, paper.id), fi = indexOf(FILMS, film.id);
            final double[] cc = {0, 65, 55}; // spektrafilm's defaults (c, m, y) without a database entry
            if (pi >= 0 && fi >= 0) for (int c = 0; c < 3; c++) cc[c] = neutral[(pi * FILMS.length + fi) * 3 + c];
            cc[1] += mShift;
            cc[2] += yShift;
            light = new double[N_WL];
            for (int w = 0; w < N_WL; w++) {
                double total = 1;
                for (int c = 0; c < 3; c++) total *= 1 - (1 - dich[w * 3 + c]) * (1 - Math.pow(10, -cc[c] / 100.0));
                light[w] = lamp[w] * total;
            }
            // P289: normalize_print_exposure without print_exposure_compensation - the print is timed for the film's mid grey at
            // its normal exposure, so «Экспозиция плёнки» shows (a denser negative prints lighter), as the enlarger's time fixed
            final double[] mid = expose(film.midSpectral(0, gamma));
            double lg = 0;
            for (int c = 0; c < 3; c++) lg += Math.log(Math.max(mid[c], 1e-10));
            factor = 1 / Math.exp(lg / 3);
        }

        /** The paper's raw exposure through spectral densities (density_to_light: no light where the density is undefined). */
        private double[] expose(double[] spectral) {
            final double[] raw = new double[3];
            for (int w = 0; w < N_WL; w++) {
                final double t = Math.pow(10, -spectral[w]) * light[w];
                if (!Double.isFinite(t)) continue;
                for (int c = 0; c < 3; c++) raw[c] += t * paper.sens[w * 3 + c];
            }
            return raw;
        }

        /** Linear sRGB (scene light, mid grey 0.184) to encoded sRGB. */
        public void render(double r, double g, double b, double[] out) {
            final double[] d = new double[3];
            densities(r, g, b, d);
            fromDensities(d, out);
        }

        /** The film's developed densities (above its base) of a linear sRGB colour. */
        public void densities(double r, double g, double b, double[] d) {
            final double[] raw = new double[3], lr = new double[3];
            film.raw(r, g, b, raw);
            final double k = Math.pow(2, ev);
            for (int c = 0; c < 3; c++) lr[c] = Math.log10(Math.max(raw[c] * k, 0) + 1e-10);
            film.develop(lr, gamma, d);
        }

        /** The grain's density sigma per layer for a 1 um pixel at densities {@code d}. */
        public void grainSigma(double[] d, double[] out) {
            film.grainSigma(d, out);
        }

        /** The print (or the slide's scan) of film densities: encoded sRGB. */
        public void fromDensities(double[] d, double[] out) {
            if (paper == null) {
                film.scan.apply(d, out);
                return;
            }
            final double[] rp = expose(spectral(film.cd, film.bd, d));
            final double[] dp = new double[3];
            for (int c = 0; c < 3; c++) {
                final double lrp = Math.log10(Math.max(rp[c] * factor, 0) + 1e-10);
                final double lrp2 = Math.log10(Math.max(Math.pow(10, lrp) * printExposure, 0) + 1e-10);
                dp[c] = interp(lrp2, paper.le, paper.dcm, 3, c);
            }
            paper.scan.apply(dp, out);
        }
    }

    /** An engine for a film id and a paper id (null paper: a slide is scanned; a negative without one: Portra Endura). */
    public static Engine engine(String filmId, @Nullable String paperId, double ev, double printEv, double mShift, double yShift,
                                double gamma) throws IOException {
        final Film f = film(filmId);
        final Paper p = f.positive ? null : paper(paperId != null && indexOf(PAPERS, paperId) >= 0 ? paperId : "kodak_portra_endura");
        return new Engine(f, p, ev, Math.pow(2, printEv), mShift, yShift, gamma);
    }

    // ───── helpers

    static double[] spectral(double[] cd, double[] bd, double[] d) {
        final double[] s = new double[N_WL];
        for (int w = 0; w < N_WL; w++) s[w] = cd[w * 3] * d[0] + cd[w * 3 + 1] * d[1] + cd[w * 3 + 2] * d[2] + bd[w];
        return s;
    }

    /** np.interp of x over (xp, column {@code col} of fp with {@code stride}): clamped ends. xp ascending. */
    static double interp(double x, double[] xp, double[] fp, int stride, int col) {
        final int n = xp.length;
        if (!(x > xp[0])) return fp[col];
        if (x >= xp[n - 1]) return fp[(n - 1) * stride + col];
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            final int mid = (lo + hi) >>> 1;
            if (xp[mid] <= x) lo = mid; else hi = mid;
        }
        final double dx = xp[hi] - xp[lo];
        final double t = dx > 0 ? (x - xp[lo]) / dx : 0;
        return fp[lo * stride + col] + (fp[hi * stride + col] - fp[lo * stride + col]) * t;
    }

    static double mitchell(double t) {
        final double b = 1 / 3.0, c = 1 / 3.0, x = Math.abs(t);
        if (x < 1) return ((12 - 9 * b - 6 * c) * x * x * x + (-18 + 12 * b + 6 * c) * x * x + (6 - 2 * b)) / 6;
        if (x < 2) return ((-b - 6 * c) * x * x * x + (6 * b + 30 * c) * x * x + (-12 * b - 48 * c) * x + (8 * b + 24 * c)) / 6;
        return 0;
    }

    private static int reflect(int i, int n) {
        return i < 0 ? -i : i >= n ? 2 * (n - 1) - i : i;
    }

    /** The cubic (Mitchell-Netravali) lookup of a [x][y][3] LUT at (x, y) in [0, 1], reflected edges, normalised weights. */
    static void cubic2d(double[] lut, int n, double x, double y, double[] out) {
        double cx = Math.min(Math.max(x * (n - 1), 0), n - 1), cy = Math.min(Math.max(y * (n - 1), 0), n - 1);
        int xb, yb;
        double xf, yf;
        if (cx >= n - 1) { xb = n - 2; xf = 1; } else { xb = (int) Math.floor(cx); xf = cx - xb; }
        if (cy >= n - 1) { yb = n - 2; yf = 1; } else { yb = (int) Math.floor(cy); yf = cy - yb; }
        final double[] wx = {mitchell(xf + 1), mitchell(xf), mitchell(xf - 1), mitchell(xf - 2)};
        final double[] wy = {mitchell(yf + 1), mitchell(yf), mitchell(yf - 1), mitchell(yf - 2)};
        out[0] = out[1] = out[2] = 0;
        double ws = 0;
        for (int i = 0; i < 4; i++) {
            final int xi = reflect(xb - 1 + i, n);
            for (int j = 0; j < 4; j++) {
                final int yj = reflect(yb - 1 + j, n);
                final double w = wx[i] * wy[j];
                ws += w;
                final int idx = (xi * n + yj) * 3;
                for (int c = 0; c < 3; c++) out[c] += w * lut[idx + c];
            }
        }
        if (ws != 0) for (int c = 0; c < 3; c++) out[c] /= ws;
    }

    static double clamp01(double v) {
        return v <= 0 ? 0 : v >= 1 ? 1 : v;
    }

    static int indexOf(String[] a, String s) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(s)) return i;
        return -1;
    }
}
