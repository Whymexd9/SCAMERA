package com.particlesdevs.photoncamera.gallery.editor;

import com.particlesdevs.photoncamera.gallery.editor.EditState.Param;

/**
 * P351: the editor's per-pixel math in one place - the uniforms every shader pass gets ({@link Uniforms#of}), the tone curve
 * table, the radii in pixels and the tile overlap, and a CPU reference of develop.glsl / main.glsl / finish.glsl's per-pixel
 * part for the JVM tests ({@link #develop}, {@link #main}). Preview and export use the same uniforms, so they render the same
 * picture: every radius is a fraction of the photo's long side (pixels "at 4000 px", {@link #refScale}).
 *
 * <p>One line per tool (linear light = after develop):
 * <ul>
 *   <li>Экспозиция: linear x 2^EV (in develop, before every other tool).</li>
 *   <li>Температура / Оттенок: a photo - linear gains (R 2^(0.4t+0.15g), G 2^(-0.3g), B 2^(-0.4t+0.15g)) normalised to keep Y;
 *       a RAW - a new camera neutral from (CCT, Duv) through the DNG matrices (DngColor), shown in K.</li>
 *   <li>Контраст: an S-curve about mid grey (0.46 encoded) on the encoded luminance, slope up to 1.8x at +100.</li>
 *   <li>Света / Тени: +-1.5 EV gain from masks of the edge-aware (guided filter) large-scale log luminance, so detail stays.</li>
 *   <li>Белые / Чёрные: the top / toe of the encoded luminance curve (+-0.18 at white, +-0.12 at black); a RAW's white point
 *       of the filmic shoulder instead of Белые.</li>
 *   <li>Сочность / Насыщенность, Смешение, Ч/Б, Цветокоррекция: in OkLCh, ported from LightCraft (see {@link #colourStage}):
 *       vibrance C x (1 + 1.2 v low^2 skin), saturation C x (1 + s); 8 bands at the OkLCh hues of sRGB 0..300 degrees, raised-
 *       cosine weights, hue +-0.5 rad, C x (1 +- 1), L +- 0.18 sqrt(L), gated by C / 0.12; B&W L + 0.25 mix min(C / 0.2, 1),
 *       C = 0; grading a, b += 0.09 sat (cos h, sin h) and L += 0.12 lum, weighted by shadows / midtones / highlights of L.</li>
 *   <li>Кривая: parametric regions (LightCraft's raised-cosine bumps) in the tone table; point curves master, R, G, B as a
 *       monotone cubic on the encoded output.</li>
 *   <li>Текстура / Чёткость: log-luminance detail gain over a Gaussian (sigma 6 px @4000) / over the guided mid base (1 % of
 *       the side), clarity weighted to mid tones.</li>
 *   <li>Дымка: dark channel of the colour over the white point, blurred at 4 % of the side; J = A + (I - A) / max(1 - 0.95 k d,
 *       0.1); a negative amount mixes toward A.</li>
 *   <li>Виньетка: after the crop, elliptical in the crop, from 0.15 + 0.85 x mid, linear light; darkens to x(1 + v w), lightens
 *       toward white.</li>
 *   <li>Зерно: value noise on a lattice fixed in crop coordinates (cell 1..4 px @4000, seeded), amplitude scaled down when a
 *       cell is smaller than a pixel (the preview), stronger in mid tones.</li>
 *   <li>Резкость: log-luminance unsharp mask, sigma = radius px @4000.</li>
 *   <li>Шумоподавление: 5x5 bilateral of luma (step 0.6..2 px @4000) and luma-guided bilateral of chroma (1..4 px @4000) in an
 *       encoded YCbCr, before develop.</li>
 * </ul>
 */
public final class EditMath {
    private EditMath() {}

    public static final float[] LUMA = {0.2126f, 0.7152f, 0.0722f};
    /** Linear Display P3 -> linear sRGB (row-major). */
    public static final float[] P3_TO_SRGB = {1.2249401f, -0.2249404f, 0f, -0.0420569f, 1.0420571f, 0f, -0.0196376f, -0.0786361f, 1.0982735f};
    public static final float[] IDENTITY3 = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    public static final int LUT_SIZE = 1024;
    /** A photo's table: entry i is encoded p = i / DISPLAY_STEPS, so 1.0 is an exact entry. */
    public static final float DISPLAY_STEPS = 511f;
    /** Scene-referred curve domain: log2 Y in [SCENE_LO, SCENE_HI]. */
    public static final float SCENE_LO = -14f, SCENE_HI = 6f;
    /** The log-luminance offset of the guided filter textures (centred values keep half floats precise). */
    public static final float CENTRE = 4f;
    public static final float MID_GREY_LOG = -2.473931f; // log2(0.18)
    /** The radii are pixels of a photo 4000 px on its long side, scaled to the real one. */
    public static final float REF_SIDE = 4000f;

    /** Where the colour of a source comes from (EditSource): the primaries matrix, a RAW's white balance, the base exposure. */
    public interface SourceColour {
        /** True: the texture holds linear values (RAW, a linear F16 decode); false: sRGB-encoded. */
        boolean linear();

        /** True: a RAW: scene-referred tone (filmic shoulder), WB through the camera matrices. */
        boolean scene();

        /** Fills wbMul (camera multipliers, 1 for a photo) and toWork (3x3 row-major, source -> linear sRGB). */
        void colour(EditState s, float[] wbMul, float[] toWork);

        /** Base exposure in stops (a RAW's BaselineExposure and automatic lift), added to Экспозиция. */
        float baseStops();
    }

    /** A photo in sRGB (or Display P3 when {@code p3}). */
    public static SourceColour display(boolean p3, boolean linear) {
        return new SourceColour() {
            @Override public boolean linear() { return linear; }

            @Override public boolean scene() { return false; }

            @Override public void colour(EditState s, float[] wbMul, float[] toWork) {
                wbMul[0] = wbMul[1] = wbMul[2] = 1f;
                System.arraycopy(p3 ? P3_TO_SRGB : IDENTITY3, 0, toWork, 0, 9);
            }

            @Override public float baseStops() { return 0f; }
        };
    }

    // ---------------------------------------------------------------- transfer

    public static float srgbToLin(float e) {
        return e <= 0.04045f ? e / 12.92f : (float) Math.pow((e + 0.055f) / 1.055f, 2.4f);
    }

    public static float linToSrgb(float x) {
        return x <= 0.0031308f ? x * 12.92f : 1.055f * (float) Math.pow(x, 1f / 2.4f) - 0.055f;
    }

    // ---------------------------------------------------------------- white balance of a photo

    /** Display WB gains for temperature / tint in -1..1, normalised so a grey keeps its luminance. */
    public static float[] wbGains(float t, float g) {
        float r = (float) Math.pow(2, 0.40 * t + 0.15 * g), gr = (float) Math.pow(2, -0.30 * g), b = (float) Math.pow(2, -0.40 * t + 0.15 * g);
        float y = LUMA[0] * r + LUMA[1] * gr + LUMA[2] * b;
        return new float[]{r / y, gr / y, b / y};
    }

    // ---------------------------------------------------------------- tone curve

    /** The S-curve of Контраст on encoded p in 0..1 (pivot 0.46, slope gamma at the pivot); identity above 1. */
    static float sCurve(float p, float gamma) {
        if (p <= 0f) return p;
        if (p >= 1f) return p;
        final float pv = 0.46f;
        return p < pv ? pv * (float) Math.pow(p / pv, gamma) : 1f - (1f - pv) * (float) Math.pow((1f - p) / (1f - pv), gamma);
    }

    static float contrast(float p, float c) {
        if (c == 0f) return p;
        return c > 0 ? p + (sCurve(p, 1.8f) - p) * c : p + (sCurve(p, 1f / 1.8f) - p) * -c;
    }

    static float blacks(float p, float b) {
        if (b == 0f || p >= 1f) return p;
        float q = 1f - Math.max(0f, p);
        return Math.max(0f, p + b * 0.12f * q * q * q);
    }

    static float whites(float p, float w) {
        if (w == 0f) return p;
        float q = Math.min(Math.max(p, 0f), 1f);
        return p + w * 0.18f * q * q * q;
    }

    /** Soft shoulder above the knee k (k >= 1: a plain clip at 1). */
    static float shoulder(float p, float k) {
        if (k >= 1f) return Math.min(p, 1f);
        if (p <= k) return p;
        return k + (1f - k) * (1f - (float) Math.exp(-(p - k) / (1f - k)));
    }

    /** A RAW's filmic shoulder on scene-linear Y: y(1 + y/W^2)/(1 + y), y = 1.25 Y; 1.0 at y = W. */
    static float filmic(float yScene, float white) {
        float y = 1.25f * Math.max(yScene, 0f);
        return y * (1f + y / (white * white)) / (1f + y);
    }

    /** The white point of the filmic shoulder: 4 (2 stops over 1.0), Белые +-100 move it by -+1.5 stops. */
    static float filmicWhite(float whites) {
        return 4f * (float) Math.pow(2, -1.5 * whites);
    }

    /**
     * The tone table: entry i of {@link #LUT_SIZE}. A photo: encoded p = i / 511 in 0..2 (1.0 is entry 511) -> encoded p'. A RAW (scene):
     * log2 Y = SCENE_LO + (SCENE_HI - SCENE_LO) i / (N - 1) -> encoded p'. All sliders at 0 on a photo: the identity on 0..1.
     */
    public static float[] toneLut(EditState s, boolean scene) {
        float[] lut = new float[LUT_SIZE];
        float c = s.get(Param.CONTRAST) / 100f, b = s.get(Param.BLACKS) / 100f, w = s.get(Param.WHITES) / 100f;
        float ev = s.get(Param.EXPOSURE);
        float knee = ev > 0 ? 1f - 0.25f * Math.min(1f, ev / 2f) : 1f;
        float white = filmicWhite(w);
        float sceneContrast = Math.max(-1f, Math.min(1f, c + 0.15f));
        float[] regions = curveRegions(s);
        float prev = 0f;
        for (int i = 0; i < LUT_SIZE; i++) {
            float p;
            if (scene) {
                float x = SCENE_LO + (SCENE_HI - SCENE_LO) * i / (LUT_SIZE - 1f);
                float d = Math.min(1f, filmic((float) Math.pow(2, x), white));
                p = linToSrgb(d);
                p = blacks(p, b);
                p = contrast(p, sceneContrast);
                p = Math.min(1f, Math.max(0f, p));
            } else {
                p = i / DISPLAY_STEPS;
                p = blacks(p, b);
                p = whites(p, w);
                p = contrast(p, c);
                p = shoulder(Math.max(0f, p), knee);
            }
            if (regions != null && p <= 1f) p = parametric(Math.max(0f, p), regions);
            prev = i == 0 ? p : Math.max(prev, p);
            lut[i] = prev;
        }
        return lut;
    }

    /** {split1, split2, split3, amount0..3} of the curve's regions, null when all four are 0. */
    static float[] curveRegions(EditState s) {
        float a0 = s.get(Param.CURVE_SHADOWS), a1 = s.get(Param.CURVE_DARKS), a2 = s.get(Param.CURVE_LIGHTS), a3 = s.get(Param.CURVE_HIGHLIGHTS);
        if (a0 == 0f && a1 == 0f && a2 == 0f && a3 == 0f) return null;
        float s0 = s.get(Param.CURVE_SPLIT_1) / 100f;
        float s1 = Math.max(s0, s.get(Param.CURVE_SPLIT_2) / 100f);
        float s2 = Math.max(s1, s.get(Param.CURVE_SPLIT_3) / 100f);
        return new float[]{s0, s1, s2, a0, a1, a2, a3};
    }

    /**
     * Ported from LightCraft crates/pipeline/src/curve.rs (MIT OR Apache-2.0), commit bb08a697a41b2561a54f1b740245119503b24758:
     * the parametric curve - four regions split at s0 / s1 / s2, each a raised-cosine bump (centre the region's middle, half
     * width 0.75 x its width + 0.05) of amount / 100 x 0.22, applied as x + d 4x(1 - x) so 0 and 1 stay put (monotone after).
     */
    static float parametric(float x, float[] r) {
        float[] lo = {0f, r[0], r[1], r[2]}, hi = {r[0], r[1], r[2], 1f};
        float d = 0f;
        for (int k = 0; k < 4; k++) {
            float amt = r[3 + k];
            if (amt == 0f) continue;
            float ctr = (lo[k] + hi[k]) / 2f, half = (hi[k] - lo[k]) * 0.75f + 0.05f;
            float t = Math.max(-1f, Math.min(1f, (x - ctr) / half));
            d += amt / 100f * 0.22f * (0.5f + 0.5f * (float) Math.cos(t * Math.PI));
        }
        return Math.max(0f, Math.min(1f, x + d * 4f * x * (1f - x)));
    }

    // ---------------------------------------------------------------- point curves

    /**
     * The point curves as one table of 4 rows x {@link #LUT_SIZE} over encoded 0..1 (master, red, green, blue); a monotone
     * cubic through the points (Fritsch-Carlson tangents), flat outside the end points. Null when every curve is straight.
     */
    public static float[] pointLut(EditState s) {
        if (!s.anyCurve()) return null;
        float[] t = new float[4 * LUT_SIZE];
        for (int k = 0; k < 4; k++)
            for (int i = 0; i < LUT_SIZE; i++) t[k * LUT_SIZE + i] = curveAt(s.curves[k], i / (LUT_SIZE - 1f));
        return t;
    }

    /** The monotone cubic of {@code pts} (x0, y0, x1, y1... sorted by x) at x; the identity for null / fewer than 2 points. */
    public static float curveAt(float[] pts, float x) {
        if (pts == null || pts.length < 4) return x;
        int n = pts.length / 2;
        if (x <= pts[0]) return pts[1];
        if (x >= pts[2 * (n - 1)]) return pts[2 * n - 1];
        float[] d = new float[n - 1], m = new float[n];
        for (int i = 0; i < n - 1; i++) {
            float dx = Math.max(1e-6f, pts[2 * i + 2] - pts[2 * i]);
            d[i] = (pts[2 * i + 3] - pts[2 * i + 1]) / dx;
        }
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) m[i] = d[i - 1] * d[i] <= 0 ? 0f : (d[i - 1] + d[i]) / 2f;
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0f) {
                m[i] = 0f;
                m[i + 1] = 0f;
                continue;
            }
            float a = m[i] / d[i], b = m[i + 1] / d[i], h = a * a + b * b;
            if (h > 9f) {
                float tau = 3f / (float) Math.sqrt(h);
                m[i] = tau * a * d[i];
                m[i + 1] = tau * b * d[i];
            }
        }
        int k = 0;
        while (k < n - 2 && x > pts[2 * k + 2]) k++;
        float x0 = pts[2 * k], x1 = pts[2 * k + 2], y0 = pts[2 * k + 1], y1 = pts[2 * k + 3], h = Math.max(1e-6f, x1 - x0);
        float t = (x - x0) / h, t2 = t * t, t3 = t2 * t;
        float v = (2 * t3 - 3 * t2 + 1) * y0 + (t3 - 2 * t2 + t) * h * m[k] + (-2 * t3 + 3 * t2) * y1 + (t3 - t2) * h * m[k + 1];
        return Math.max(0f, Math.min(1f, v));
    }

    // ---------------------------------------------------------------- OkLab colour (ported)

    /** Linear sRGB -> OkLab (Bjorn Ottosson's matrices). */
    static float[] oklab(float[] c) {
        float l = 0.4122214708f * c[0] + 0.5363325363f * c[1] + 0.0514459929f * c[2];
        float m = 0.2119034982f * c[0] + 0.6806995451f * c[1] + 0.1073969566f * c[2];
        float s = 0.0883024619f * c[0] + 0.2817188376f * c[1] + 0.6299787005f * c[2];
        l = (float) Math.cbrt(l);
        m = (float) Math.cbrt(m);
        s = (float) Math.cbrt(s);
        return new float[]{0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
                1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
                0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s};
    }

    static float[] oklabToLinear(float[] lab) {
        float l = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2];
        float m = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2];
        float s = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2];
        l = l * l * l;
        m = m * m * m;
        s = s * s * s;
        return new float[]{4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
                -1.2684380046f * l + 2.6097574011f * m - 0.3413193414f * s,
                -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s};
    }

    /** The OkLCh hue (radians) of the fully saturated sRGB colour of HSV hue {@code deg} (LightCraft's oklch_hue_of_srgb_hue). */
    public static float oklchHueOfSrgbHue(float deg) {
        float[] e = hsvToRgb(new float[]{deg, 1f, 1f});
        float[] lab = oklab(new float[]{srgbToLin(e[0]), srgbToLin(e[1]), srgbToLin(e[2])});
        return (float) Math.atan2(lab[2], lab[1]);
    }

    /** The 8 bands as HSV hues of sRGB (red, orange, yellow, green, aqua, blue, purple, magenta) and 360 again. */
    public static final float[] BANDS = {0f, 30f, 60f, 120f, 180f, 240f, 270f, 300f, 360f};

    /** The mixer's band centres in OkLCh hue (radians, rising): red, orange, yellow, green, aqua, blue, purple, magenta. */
    public static final float[] BAND_HUES = new float[8];

    static {
        for (int i = 0; i < 8; i++) {
            float h = oklchHueOfSrgbHue(BANDS[i]);
            BAND_HUES[i] = h < 0 ? h + (float) (2 * Math.PI) : h;
        }
    }

    static float wrap(float a) {
        float p = (float) Math.PI;
        while (a > p) a -= 2 * p;
        while (a < -p) a += 2 * p;
        return a;
    }

    /**
     * Ported from LightCraft crates/pipeline/src/colorops.rs (band_weights), commit bb08a697: for hue h the pair of neighbouring
     * band centres whose arc holds it, split by a raised cosine 0.5 - 0.5 cos(pi t); the 8 weights sum to 1.
     */
    static float[] bandWeights(float h) {
        float[] w = new float[8];
        float tau = (float) (2 * Math.PI);
        h = ((h % tau) + tau) % tau;
        for (int i = 0; i < 8; i++) {
            float a = BAND_HUES[i], b = BAND_HUES[(i + 1) % 8];
            float span = ((b - a) % tau + tau) % tau, d = ((h - a) % tau + tau) % tau;
            if (span <= 0f || d > span) continue;
            float t = d / span, s = 0.5f - 0.5f * (float) Math.cos(t * Math.PI);
            w[i] += 1f - s;
            w[(i + 1) % 8] += s;
            break;
        }
        return w;
    }

    static float smoothRange(float a, float b, float x) {
        return b <= a ? (x >= b ? 1f : 0f) : smoothstep(a, b, x);
    }

    /**
     * The colour stage on linear light, ported from LightCraft crates/pipeline/src/colorops.rs (commit bb08a697; it works on
     * linear Rec.2020, here linear sRGB primaries): in OkLCh - vibrance (low-chroma boost, skin hues spared), saturation, the
     * 8-band mixer (hue +-0.5 rad, chroma x(1 +- 1), lightness +-0.18 sqrt(L), gated by C / 0.12), the B&W mix, then the
     * 3-way grading wheels (chroma offset 0.09 x sat, lightness 0.12 x lum, weights from L, pivot 0.5 - balance / 4, width
     * 0.15 + blending / 2).
     */
    static void colourStage(float[] c, Uniforms u) {
        if (!u.colourOn) return;
        float[] lab = oklab(new float[]{Math.max(c[0], 0f), Math.max(c[1], 0f), Math.max(c[2], 0f)});
        float L = lab[0], C = (float) Math.hypot(lab[1], lab[2]), h = (float) Math.atan2(lab[2], lab[1]);
        if (u.vib != 0f) {
            float low = 1f - clamp01(C / 0.22f);
            float skin = u.vib > 0 ? 1f - 0.6f * (float) Math.exp(-Math.pow(wrap(h - u.skinHue) / 0.35f, 2)) : 1f;
            C *= Math.max(0f, 1f + u.vib * low * low * skin * 1.2f);
        }
        C *= Math.max(0f, 1f + u.sat);
        if (u.mixerOn || u.mono) {
            float[] w = bandWeights(h);
            if (u.mixerOn) {
                float dh = 0, ds = 0, dl = 0;
                for (int i = 0; i < 8; i++) {
                    dh += w[i] * u.hsl[i * 3] * 0.5f;
                    ds += w[i] * u.hsl[i * 3 + 1];
                    dl += w[i] * u.hsl[i * 3 + 2] * 0.18f;
                }
                float cw = Math.min(C / 0.12f, 1f);
                h += dh * cw;
                C *= Math.max(0f, 1f + ds);
                L += dl * cw * (float) Math.sqrt(Math.max(L, 0.05f));
            }
            if (u.mono) {
                float mix = 0;
                for (int i = 0; i < 8; i++) mix += w[i] * u.bw[i];
                L = Math.max(0f, L + mix * Math.min(C / 0.2f, 1f) * 0.25f);
                C = 0f;
            }
        }
        float a = C * (float) Math.cos(h), b = C * (float) Math.sin(h);
        if (u.gradeOn) {
            float m = 0.5f - u.gradeBalance * 0.25f, width = 0.15f + u.gradeBlend * 0.5f;
            float ws = 1f - smoothRange(m - width, m + width * 0.25f, L);
            float wh = smoothRange(m - width * 0.25f, m + width, L);
            float wm = Math.max(0f, 1f - ws - wh);
            float[] wt = {ws, wm, wh, 1f};
            float dL = 0;
            for (int k = 0; k < 4; k++) {
                a += u.grade[k * 3] * wt[k];
                b += u.grade[k * 3 + 1] * wt[k];
                dL += u.grade[k * 3 + 2] * wt[k];
            }
            L += dL;
        }
        float[] out = oklabToLinear(new float[]{L, a, b});
        System.arraycopy(out, 0, c, 0, 3);
    }

    /** The table at encoded p (photo) or scene Y (RAW), linearly interpolated as main.glsl does. */
    static float lutAt(float[] lut, float y, boolean scene) {
        float x;
        if (scene) x = (float) ((Math.log(Math.max(y, 1e-9f)) / Math.log(2) - SCENE_LO) / (SCENE_HI - SCENE_LO) * (LUT_SIZE - 1));
        else x = linToSrgb(y) * DISPLAY_STEPS;
        x = Math.max(0f, Math.min(LUT_SIZE - 1f, x));
        int i0 = (int) Math.floor(x), i1 = Math.min(LUT_SIZE - 1, i0 + 1);
        float t = x - i0;
        return lut[i0] + (lut[i1] - lut[i0]) * t;
    }

    // ---------------------------------------------------------------- radii

    /** Pixels "at 4000 px" -> pixels of a photo {@code longSide} px long. */
    public static float refScale(float longSide) {
        return longSide / REF_SIDE;
    }

    public static float sharpenSigmaRef(EditState s) {
        return s.get(Param.SHARPEN_RADIUS);
    }

    public static final float TEXTURE_SIGMA_REF = 6f;

    public static float lumaStepRef(EditState s) {
        return 0.6f + 1.4f * s.get(Param.LUMA_NR) / 100f;
    }

    public static float chromaStepRef(EditState s) {
        return 1f + 3f * s.get(Param.COLOR_NR) / 100f;
    }

    /** {taps, stride} of a Gaussian of {@code sigma} px: up to 24 taps a side out to 3 sigma. */
    public static float[] gaussTaps(float sigma) {
        float reach = 3f * Math.max(sigma, 0.05f);
        if (reach <= 24f) return new float[]{Math.max(1, (int) Math.ceil(reach)), 1f};
        return new float[]{24, reach / 24f};
    }

    /**
     * The overlap a tile needs on every side (source pixels): the reach of the NR kernel (2 steps of up to 4 px @4000), then the
     * Gaussians (3 sigma of the 6 px texture band or a 3 px sharpening radius), the 5x5 demosaic and a bilinear margin.
     */
    public static int overlapPx(float longSide) {
        float ref = 2f * 4f + 3f * Math.max(TEXTURE_SIGMA_REF, 3f);
        return (int) Math.ceil(ref * refScale(longSide)) + 6;
    }

    // ---------------------------------------------------------------- uniforms

    /** Everything the passes need, from a state and its source (shared by the renderer and the tests). */
    public static final class Uniforms {
        public boolean linearIn, raw, scene;
        public final float[] wbMul = new float[3], toWork = new float[9], gain = new float[3], air = new float[3];
        public float sharpen, texture, clarity, shadows, highlights, dehaze, sat, vib;
        public boolean hslOn, mixerOn, mono, gradeOn, colourOn;
        public final float[] hsl = new float[24], bw = new float[8], grade = new float[12];
        public float gradeBlend, gradeBalance, skinHue = oklchHueOfSrgbHue(25f);
        public float[] lut, points;
        public float vignette, vignetteMid, grain, grainCellRef;
        public boolean nrLuma, nrChroma;
        public float sigL, sigC, stepLRef, stepCRef;
        public float sigmaSharpRef, sigmaTexRef;
        public boolean blurOn;

        public static Uniforms of(EditState s, SourceColour src) {
            Uniforms u = new Uniforms();
            u.linearIn = src.linear();
            u.scene = src.scene();
            src.colour(s, u.wbMul, u.toWork);
            u.raw = u.scene;
            float ev = (float) Math.pow(2, s.get(Param.EXPOSURE) + src.baseStops());
            float[] wb = u.scene ? new float[]{1, 1, 1} : wbGains(s.get(Param.TEMPERATURE) / 100f, s.get(Param.TINT) / 100f);
            for (int i = 0; i < 3; i++) {
                u.gain[i] = wb[i] * ev;
                u.air[i] = u.gain[i]; // the white point after develop
            }
            u.sharpen = s.get(Param.SHARPEN) / 100f;
            u.texture = s.get(Param.TEXTURE) / 100f * 0.7f;
            u.clarity = s.get(Param.CLARITY) / 100f * 0.8f;
            float sh = s.get(Param.SHADOWS) / 100f, hi = s.get(Param.HIGHLIGHTS) / 100f;
            u.shadows = sh * (sh > 0 ? 1.5f : 1.2f);
            u.highlights = hi * 1.5f;
            u.dehaze = s.get(Param.DEHAZE) / 100f;
            u.sat = s.get(Param.SATURATION) / 100f + (u.scene ? 0.08f : 0f);
            u.vib = s.get(Param.VIBRANCE) / 100f;
            u.hslOn = s.anyHsl();
            u.mixerOn = u.hslOn;
            for (int b = 0; b < 8; b++) {
                u.hsl[b * 3] = s.get(Param.hue(b)) / 100f;
                u.hsl[b * 3 + 1] = s.get(Param.sat(b)) / 100f;
                u.hsl[b * 3 + 2] = s.get(Param.lum(b)) / 100f;
                u.bw[b] = s.get(Param.bw(b)) / 100f;
            }
            u.mono = s.mono();
            u.gradeOn = s.anyGrade();
            for (int w = 0; w < 4; w++) {
                float hue = oklchHueOfSrgbHue(s.get(Param.grade(w, 0)));
                float sat = s.get(Param.grade(w, 1)) / 100f * 0.09f;
                u.grade[w * 3] = sat * (float) Math.cos(hue);
                u.grade[w * 3 + 1] = sat * (float) Math.sin(hue);
                u.grade[w * 3 + 2] = s.get(Param.grade(w, 2)) / 100f * 0.12f;
            }
            u.gradeBlend = s.get(Param.GRADE_BLEND) / 100f;
            u.gradeBalance = s.get(Param.GRADE_BALANCE) / 100f;
            u.colourOn = u.sat != 0f || u.vib != 0f || u.mixerOn || u.mono || u.gradeOn;
            u.lut = toneLut(s, u.scene);
            u.points = pointLut(s);
            u.vignette = s.get(Param.VIGNETTE) / 100f;
            u.vignetteMid = s.get(Param.VIGNETTE_MID) / 100f;
            u.grain = s.get(Param.GRAIN) / 100f * 0.10f;
            u.grainCellRef = 1f + 3f * s.get(Param.GRAIN_SIZE) / 100f;
            float nl = s.get(Param.LUMA_NR) / 100f, nc = s.get(Param.COLOR_NR) / 100f;
            u.nrLuma = nl > 0;
            u.nrChroma = nc > 0;
            u.sigL = 0.006f + 0.05f * nl;
            u.sigC = 0.01f + 0.12f * nc;
            u.stepLRef = lumaStepRef(s);
            u.stepCRef = chromaStepRef(s);
            u.sigmaSharpRef = sharpenSigmaRef(s);
            u.sigmaTexRef = TEXTURE_SIGMA_REF;
            u.blurOn = u.sharpen != 0f || u.texture != 0f;
            return u;
        }
    }

    // ---------------------------------------------------------------- CPU reference

    /** develop.glsl: a source texel -> {linear R, G, B, log2 Y} (the RAW clip handling included). */
    public static float[] develop(float[] src, Uniforms u) {
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) c[i] = u.linearIn ? src[i] : srgbToLin(src[i]);
        if (u.raw) {
            float m = Math.max(c[0], Math.max(c[1], c[2]));
            for (int i = 0; i < 3; i++) c[i] *= u.wbMul[i];
            float t = smoothstep(0.92f, 1.0f, m);
            float avg = (c[0] + c[1] + c[2]) / 3f;
            for (int i = 0; i < 3; i++) c[i] += (avg - c[i]) * t;
        }
        float[] w = new float[3];
        for (int r = 0; r < 3; r++) w[r] = (u.toWork[r * 3] * c[0] + u.toWork[r * 3 + 1] * c[1] + u.toWork[r * 3 + 2] * c[2]) * u.gain[r];
        float y = Math.max(LUMA[0] * w[0] + LUMA[1] * w[1] + LUMA[2] * w[2], 1e-6f);
        return new float[]{w[0], w[1], w[2], (float) (Math.log(y) / Math.log(2))};
    }

    /**
     * main.glsl for one pixel: {@code d} from {@link #develop}; {@code bm}, {@code bl} the mid / large guided bases (log2 Y),
     * {@code bSharp}, {@code bTex} the Gaussian blurs of log2 Y, {@code dark} the blurred dark channel. Returns encoded RGB.
     */
    public static float[] main(float[] d, float bm, float bl, float bSharp, float bTex, float dark, Uniforms u) {
        float[] c = {d[0], d[1], d[2]};
        float L = d[3];
        float dL = 0f;
        float toe = smoothstep(-11f, -7f, L);
        if (u.blurOn) dL += toe * (u.sharpen * (L - bSharp) + u.texture * (L - bTex));
        float relm = bm - MID_GREY_LOG, rel = bl - MID_GREY_LOG;
        dL += u.clarity * toe * (float) Math.exp(-relm * relm / 8f) * (L - bm);
        dL += u.shadows * (1f - smoothstep(-5f, 0f, rel)) + u.highlights * smoothstep(0f, 2.5f, rel);
        if (u.dehaze > 0f) {
            float t = Math.max(1f - 0.95f * u.dehaze * dark, 0.1f);
            for (int i = 0; i < 3; i++) c[i] = Math.max((c[i] - u.air[i]) / t + u.air[i], 0f);
        } else if (u.dehaze < 0f) {
            float k = -0.6f * u.dehaze * (0.4f + 0.6f * clamp01(dark));
            for (int i = 0; i < 3; i++) c[i] += (u.air[i] - c[i]) * k;
        }
        float g = (float) Math.pow(2, dL);
        for (int i = 0; i < 3; i++) c[i] *= g;
        float y = LUMA[0] * c[0] + LUMA[1] * c[1] + LUMA[2] * c[2];
        if (y > 1e-7f) {
            float y2 = srgbToLin(lutAt(u.lut, y, u.scene));
            for (int i = 0; i < 3; i++) c[i] *= y2 / y;
            y = y2;
        } else y = 0f;
        colourStage(c, u);
        y = Math.min(1f, Math.max(0f, LUMA[0] * c[0] + LUMA[1] * c[1] + LUMA[2] * c[2]));
        fitGamut(c, y);
        float[] e = {clamp01(linToSrgb(c[0])), clamp01(linToSrgb(c[1])), clamp01(linToSrgb(c[2]))};
        if (u.points != null)
            for (int i = 0; i < 3; i++) e[i] = pointAt(u.points, i + 1, pointAt(u.points, 0, e[i]));
        return e;
    }

    /** Row {@code row} of the point-curve table at encoded x, linearly interpolated (main.glsl's point()). */
    static float pointAt(float[] t, int row, float x) {
        float f = clamp01(x) * (LUT_SIZE - 1);
        int i0 = (int) Math.floor(f), i1 = Math.min(LUT_SIZE - 1, i0 + 1);
        float a = t[row * LUT_SIZE + i0], b = t[row * LUT_SIZE + i1];
        return a + (b - a) * (f - i0);
    }

    /** Pulls a colour into 0..1 along the line to its luminance (hue and Y kept). */
    static void fitGamut(float[] c, float y) {
        if (y >= 1f) {
            c[0] = c[1] = c[2] = 1f;
            return;
        }
        float mn = Math.min(c[0], Math.min(c[1], c[2]));
        if (mn < 0f) {
            float t = y / (y - mn);
            for (int i = 0; i < 3; i++) c[i] = y + (c[i] - y) * t;
        }
        float mx = Math.max(c[0], Math.max(c[1], c[2]));
        if (mx > 1f) {
            float t = (1f - y) / (mx - y);
            for (int i = 0; i < 3; i++) c[i] = y + (c[i] - y) * t;
        }
    }

    static float[] rgbToHsv(float[] c) {
        float mx = Math.max(c[0], Math.max(c[1], c[2])), mn = Math.min(c[0], Math.min(c[1], c[2])), d = mx - mn, h = 0f;
        if (d > 1e-6f) {
            if (mx == c[0]) h = (((c[1] - c[2]) / d) % 6f + 6f) % 6f;
            else if (mx == c[1]) h = (c[2] - c[0]) / d + 2f;
            else h = (c[0] - c[1]) / d + 4f;
        }
        return new float[]{h * 60f, mx > 1e-6f ? d / mx : 0f, mx};
    }

    static float[] hsvToRgb(float[] hsv) {
        float h = ((hsv[0] % 360f) + 360f) % 360f / 60f, c = hsv[2] * hsv[1];
        float x = c * (1f - Math.abs(h % 2f - 1f));
        float r, g, b;
        if (h < 1f) { r = c; g = x; b = 0; }
        else if (h < 2f) { r = x; g = c; b = 0; }
        else if (h < 3f) { r = 0; g = c; b = x; }
        else if (h < 4f) { r = 0; g = x; b = c; }
        else if (h < 5f) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        float m = hsv[2] - c;
        return new float[]{r + m, g + m, b + m};
    }

    static float smoothstep(float a, float b, float x) {
        float t = clamp01((x - a) / (b - a));
        return t * t * (3f - 2f * t);
    }

    static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
