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

    /** The reference's grain (the photo's FilmGrain, film/grain_sf.glsl) on the finished sRGB pixels of a colour stock. */
    public static int[] grain(int[] px, int w, int h, FilmSim.Settings set) {
        final float um = pixelUm(set);
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
}
