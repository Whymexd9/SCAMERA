package com.particlesdevs.photoncamera.processing.color;

import java.util.Random;

/**
 * P289 (owner, 2026-10-20: the film settings' sliders «не работают нормально»; the reference app shows every effect in its
 * preview): the film's spatial effects on the settings' preview, by the photo's own formulas (FilmHalation, FilmGrain) - the
 * preview as a 1:1 crop of a photo {@link #PHOTO_LONG_PX} pixels long, so the grain, the halation and the film's softness look
 * as on the photo at full size.
 */
public final class FilmEffects {
    private FilmEffects() {}

    /** The photo the preview stands for: its long side (a 12 MP frame). */
    public static final int PHOTO_LONG_PX = 4000;
    /** The reference's in-emulsion scatter (HalationParams): core sigma, tail decay (um), the tail's weight. */
    static final float[] SCATTER_CORE = {2.2f, 2.0f, 1.6f}, SCATTER_TAIL = {9.3f, 9.7f, 9.1f}, SCATTER_W = {.78f, .65f, .67f};
    /** The exponential's three-Gaussian fit (amplitude, sigma / decay). */
    static final double[][] EXP_FIT = DiffusionGauss.FIT;

    /** The film's pixel size (um) of the preview, a crop of the photo at full size; 0 without a format. */
    public static float pixelUm(FilmSim.Settings set) {
        return set.pixelUm(PHOTO_LONG_PX);
    }

    static float decode(int v) {
        final float x = v / 255f;
        return x <= 0.04045f ? x / 12.92f : (float) Math.pow((x + 0.055f) / 1.055f, 2.4f);
    }

    static int encode(float y) {
        y = Math.max(0f, Math.min(1f, y));
        final float e = y <= 0.0031308f ? 12.92f * y : 1.055f * (float) Math.pow(y, 1 / 2.4) - 0.055f;
        return Math.round(e * 255f);
    }

    /** Scatter and halation (the light before the film's colour, the photo's FilmHalation) on sRGB pixels. */
    public static int[] light(int[] px, int w, int h, FilmSim.Settings set) {
        final float um = pixelUm(set);
        if (um <= 0f) return px;
        final boolean scatter = set.stock.spectral != null && set.softness > 0f;
        final float[] a = set.halationAmounts(); // x «Сила симуляции», as the photo
        final boolean halo = a[0] + a[1] + a[2] > 0f;
        if (!scatter && !halo) return px;
        float[] e = new float[px.length * 3];
        for (int i = 0; i < px.length; i++) {
            e[i * 3] = decode((px[i] >> 16) & 255);
            e[i * 3 + 1] = decode((px[i] >> 8) & 255);
            e[i * 3 + 2] = decode(px[i] & 255);
        }
        if (scatter) {
            // per channel (1 - w) G(core) + w Exp(tail), the scale the setting
            final float k = set.softnessApplied() / um;
            final float[] out = new float[e.length];
            for (int c = 0; c < 3; c++) {
                final float[] ch = channel(e, c);
                final float[] core = DiffusionGauss.blur1(ch, w, h, SCATTER_CORE[c] * k);
                final float[] tail = new float[ch.length];
                for (double[] f : EXP_FIT) {
                    final float[] g = DiffusionGauss.blur1(ch, w, h, (float) (f[1] * SCATTER_TAIL[c] * k));
                    for (int i = 0; i < ch.length; i++) tail[i] += (float) f[0] * g[i];
                }
                for (int i = 0; i < ch.length; i++) out[i * 3 + c] = (1 - SCATTER_W[c]) * core[i] + SCATTER_W[c] * tail[i];
            }
            e = out;
        }
        if (halo) {
            // three bounces G(sigma sqrt(k)) weighted 1, 1/2, 1/4, energy kept: (E + a H) / (1 + a)
            final float sigma = set.stock.halationUm / um;
            final float[] hsum = new float[e.length];
            final float[] wts = {1f / 1.75f, .5f / 1.75f, .25f / 1.75f};
            for (int b = 0; b < 3; b++) {
                final float[] g = DiffusionGauss.blurLarge(e, w, h, sigma * (float) Math.sqrt(b + 1));
                for (int i = 0; i < e.length; i++) hsum[i] += wts[b] * g[i];
            }
            for (int i = 0; i < e.length; i++) e[i] = (e[i] + a[i % 3] * hsum[i]) / (1 + a[i % 3]);
        }
        final int[] out = new int[px.length];
        for (int i = 0; i < px.length; i++)
            out[i] = 0xFF000000 | (encode(e[i * 3]) << 16) | (encode(e[i * 3 + 1]) << 8) | encode(e[i * 3 + 2]);
        return out;
    }

    private static float[] channel(float[] rgb, int c) {
        final float[] out = new float[rgb.length / 3];
        for (int i = 0; i < out.length; i++) out[i] = rgb[i * 3 + c];
        return out;
    }

    /**
     * The reference's grain (the photo's FilmGrain, film/grain_sf.glsl) on the finished sRGB pixels of a colour stock; P369
     * (viewfinder-bw-preview): a classic (black-and-white) stock's film sharpness and grain as the photo's FilmGrain draws them
     * ({@link #classic}).
     */
    public static int[] grain(int[] px, int w, int h, FilmSim.Settings set) {
        final float um = pixelUm(set);
        if (um > 0f && set.stock.spectral == null) return classic(px, w, h, set, um);
        if (um <= 0f || set.grain <= 0f || set.stock.spectral == null) return px;
        final FilmLut lut = FilmSim.grainLut(set, 17);
        if (lut == null) return px;
        final Random rnd = new Random(Math.abs(set.stock.id.hashCode()));
        final float[] n = new float[px.length * 3];
        for (int i = 0; i < n.length; i++) n[i] = (float) rnd.nextGaussian();
        final float[] nb = DiffusionGauss.blur(n, w, h, FilmSpectral.GRAIN_BLUR_UM / um);
        final float scale = set.grainApplied() / um;
        final int[] out = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            final float r = ((px[i] >> 16) & 255) / 255f, g = ((px[i] >> 8) & 255) / 255f, b = (px[i] & 255) / 255f;
            final float[] s = lut.sample(r, g, b);
            final int ro = Math.round(Math.max(0f, Math.min(1f, r + s[0] * scale * nb[i * 3])) * 255f);
            final int go = Math.round(Math.max(0f, Math.min(1f, g + s[1] * scale * nb[i * 3 + 1])) * 255f);
            final int bo = Math.round(Math.max(0f, Math.min(1f, b + s[2] * scale * nb[i * 3 + 2])) * 255f);
            out[i] = 0xFF000000 | (ro << 16) | (go << 8) | bo;
        }
        return out;
    }

    /**
     * P369 (viewfinder-bw-preview: the preview showed no grain and no softness for the black-and-white stocks, the photo applies
     * both): the photo's FilmGrain for a classic stock on the finished sRGB pixels - the film's sharpness, a Gaussian of the stock's
     * MTF sigma x «Мягкость плёнки» x the strength from a third of a pixel (FilmSim.classicBlurPx, film/blur.glsl on the encoded
     * picture), then film/grain.glsl: the light modulated as a density, y' = y 10^(-k n), k = FilmSim.classicGrainSigma (green; blue
     * x 1.25) x the tonal weight (a print: most in the mid tones; a reversal: in the shadows), n a unit field of grain clumps of the
     * grain's size (its own random lattice here, not the shader's hash: the same statistics, another pattern).
     */
    static int[] classic(int[] px, int w, int h, FilmSim.Settings set, float um) {
        final float blur = FilmSim.classicBlurPx(set, um);
        final boolean soft = blur >= FilmSim.CLASSIC_BLUR_MIN_PX, grainy = set.grain > 0f;
        if (!soft && !grainy) return px;
        float[] e = new float[px.length * 3];
        for (int i = 0; i < px.length; i++) {
            e[i * 3] = ((px[i] >> 16) & 255) / 255f;
            e[i * 3 + 1] = ((px[i] >> 8) & 255) / 255f;
            e[i * 3 + 2] = (px[i] & 255) / 255f;
        }
        if (soft) e = DiffusionGauss.blur(e, w, h, blur);
        final int[] out = new int[px.length];
        if (!grainy) {
            for (int i = 0; i < px.length; i++) out[i] = 0xFF000000 | (code(e[i * 3]) << 16) | (code(e[i * 3 + 1]) << 8) | code(e[i * 3 + 2]);
            return out;
        }
        final float s = FilmSim.classicGrainSigma(set, um), cell = FilmSim.classicGrainCell(set, um);
        final boolean slide = set.stock.reversal(), mono = set.stock.mono();
        final Random rnd = new Random(Math.abs(set.stock.id.hashCode()));
        // a monochrome stock: one silver layer (film/grain.glsl mono 1); a classic colour stock: the layers' fields partly shared
        final float[] together = clumps(w, h, cell, rnd), red = mono ? together : clumps(w, h, cell, rnd),
                green = mono ? together : clumps(w, h, cell, rnd), blue = mono ? together : clumps(w, h, cell * 1.4f, rnd);
        final float shared = (float) Math.sqrt(mono ? 1f : .55f), own = (float) Math.sqrt(mono ? 0f : .45f);
        final float[] sigma = {s, s, s * 1.25f};
        for (int i = 0; i < px.length; i++) {
            final float[] y = {decodeF(e[i * 3]), decodeF(e[i * 3 + 1]), decodeF(e[i * 3 + 2])};
            final float lum = Math.max(.2126f * y[0] + .7152f * y[1] + .0722f * y[2], 1e-4f);
            final double stops = Math.log(lum / .18) / Math.log(2);
            final double wt = slide ? .25 + .75 * Math.exp(-Math.pow(Math.log(lum / .04) / Math.log(2) / 2.5, 2))
                    : .35 + .65 * Math.exp(-Math.pow(stops / 2.2, 2));
            final float[] n = mono ? new float[]{together[i], together[i], together[i]}
                    : new float[]{shared * together[i] + own * red[i], shared * together[i] + own * green[i], shared * together[i] + own * blue[i]};
            int o = 0xFF000000;
            for (int c = 0; c < 3; c++) o |= encode((float) (y[c] * Math.pow(10, -sigma[c] * wt * n[c]))) << (16 - 8 * c);
            out[i] = o;
        }
        return out;
    }

    private static int code(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    private static float decodeF(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x <= 0.04045f ? x / 12.92f : (float) Math.pow((x + 0.055f) / 1.055f, 2.4f);
    }

    /**
     * film/grain.glsl's grain field of clumps {@code size} pixels: Gaussian values on a lattice of that size blended with a smooth
     * step (x 1.37: a blend of four unit values keeps about 0.53 of the variance), plus an octave of half the size at half the
     * weight, x 0.894: about unit variance.
     */
    static float[] clumps(int w, int h, float size, Random rnd) {
        final float[] out = new float[w * h];
        final float[] a = field(w, h, size, 0f, rnd), b = field(w, h, .5f * size, .5f * size, rnd);
        for (int i = 0; i < out.length; i++) out[i] = (a[i] + .5f * b[i]) * .894f;
        return out;
    }

    private static float[] field(int w, int h, float size, float offset, Random rnd) {
        final float cellPx = Math.max(size, 1f);
        final int lw = (int) Math.ceil((w + offset) / cellPx) + 2, lh = (int) Math.ceil((h + offset) / cellPx) + 2;
        final float[] lattice = new float[lw * lh];
        for (int i = 0; i < lattice.length; i++) lattice[i] = (float) rnd.nextGaussian();
        final float[] out = new float[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            final float qx = (x + .5f + offset) / cellPx, qy = (y + .5f + offset) / cellPx;
            final int ix = (int) Math.floor(qx), iy = (int) Math.floor(qy);
            float fx = qx - ix, fy = qy - iy;
            fx = fx * fx * (3 - 2 * fx);
            fy = fy * fy * (3 - 2 * fy);
            final float p00 = lattice[iy * lw + ix], p10 = lattice[iy * lw + ix + 1], p01 = lattice[(iy + 1) * lw + ix],
                    p11 = lattice[(iy + 1) * lw + ix + 1];
            out[y * w + x] = ((p00 + (p10 - p00) * fx) * (1 - fy) + (p01 + (p11 - p01) * fx) * fy) * 1.37f;
        }
        return out;
    }
}
