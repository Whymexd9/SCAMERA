package com.particlesdevs.photoncamera.processing.color;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.Lang;

/**
 * P170 (owner, 2026-10-16: «добавь 5 своих лутов. Сделай их сам в плёночном стиле»): five film looks of our own, built here
 * as 3D LUTs of the display-encoded output (FilmLut, 33 points a side) and listed before the imported .cube files. Each is
 * a function of the display RGB: a film S per channel (toe lift, shoulder), split toning that keeps the luma, hue bands
 * (greens, blues, skin) moved and (de)saturated, and an overall saturation; the black-and-white one mixes the channels as a
 * panchromatic film. The chosen value is stored as {@link #PREFIX} + the id (no .cube file has that name).
 */
public final class FilmLooks {
    public static final String PREFIX = "scam:";
    /** Points a side of the built LUT (as the usual .cube looks). */
    public static final int SIZE = 33;

    interface Look {
        void apply(float[] rgb);
    }

    static final String[] IDS = {"warm400", "cool160", "slide100", "cine500", "mono400"};
    /** The LUT titles (English; the UI names are {@link #label}). */
    private static final String[] TITLES = {"Warm 400", "Cool 160", "Slide 100", "Cine 500", "B&W 400"};
    private static final Look[] LOOKS = {FilmLooks::warm400, FilmLooks::cool160, FilmLooks::slide100, FilmLooks::cine500,
            FilmLooks::mono400};

    private FilmLooks() {}

    /** The stored values of the five looks, in their order. */
    public static String[] values() {
        String[] out = new String[IDS.length];
        for (int i = 0; i < IDS.length; i++) out[i] = PREFIX + IDS[i];
        return out;
    }

    public static boolean isBuiltIn(@Nullable String value) {
        return index(value) >= 0;
    }

    static int index(@Nullable String value) {
        if (value == null || !value.startsWith(PREFIX)) return -1;
        String id = value.substring(PREFIX.length());
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return -1;
    }

    /** «Тёплая 400» / "Warm 400" in the UI language of {@code context}; null for anything else. */
    @Nullable
    public static String label(@Nullable android.content.Context context, @Nullable String value) {
        switch (index(value)) {
            case 0: return Lang.t(context, "Тёплая 400", TITLES[0]);
            case 1: return Lang.t(context, "Прохладная 160", TITLES[1]);
            case 2: return Lang.t(context, "Слайд 100", TITLES[2]);
            case 3: return Lang.t(context, "Кино 500", TITLES[3]);
            case 4: return Lang.t(context, "Ч/Б 400", TITLES[4]);
            default: return null;
        }
    }

    /** The LUT of a look, or null for anything else. */
    @Nullable
    public static FilmLut lut(@Nullable String value) {
        int i = index(value);
        if (i < 0) return null;
        Look look = LOOKS[i];
        int n = SIZE;
        float[] v = new float[n * n * n * 3];
        float[] c = new float[3];
        int k = 0;
        for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
            c[0] = r / (float) (n - 1);
            c[1] = g / (float) (n - 1);
            c[2] = b / (float) (n - 1);
            look.apply(c);
            for (int ch = 0; ch < 3; ch++) v[k++] = clamp(c[ch]);
        }
        return FilmLut.of(n, v, TITLES[i]);
    }

    /** The look applied to one display colour (tests, previews). */
    public static float[] apply(String value, float r, float g, float b) {
        float[] c = {r, g, b};
        LOOKS[index(value)].apply(c);
        for (int i = 0; i < 3; i++) c[i] = clamp(c[i]);
        return c;
    }

    // ───── the looks

    /** A portrait colour negative: soft contrast, lifted warm blacks, creamy highlights, olive greens, softer blues. */
    static void warm400(float[] c) {
        film(c, 4.0f, 0.42f, 0.55f, 0.030f, 0.975f);
        bands(c, new float[][]{
                {110, 50, -14, -0.25f, 0},   // greens toward olive, quieter
                {215, 40, -5, -0.15f, -0.05f}, // blues toward cyan, softer, not lighter
                {28, 25, 0, 0.06f, 0.01f},   // skin a little richer
        });
        split(c, 0.020f, 0.005f, -0.020f, 0.025f, 0.010f, -0.030f);
        saturate(c, 0.92f);
    }

    /** A fine-grain daylight negative: low contrast, pastel colour, cyan-green shadows, a touch of magenta in the whites. */
    static void cool160(float[] c) {
        film(c, 3.6f, 0.45f, 0.45f, 0.045f, 0.970f);
        bands(c, new float[][]{
                {110, 50, 10, -0.15f, 0},    // greens toward cyan
                {10, 30, -4, -0.05f, 0},     // reds and skin toward pink
                {215, 40, 4, -0.08f, 0.02f}, // blues lighter, pastel
        });
        split(c, -0.015f, 0.010f, 0.015f, 0.008f, -0.004f, 0.006f);
        saturate(c, 0.85f);
    }

    /** A reversal film: deep blacks, strong contrast, saturated colour, dense blues, a cool shadow and clean whites. */
    static void slide100(float[] c) {
        film(c, 5.5f, 0.47f, 0.80f, 0.008f, 0.985f);
        bands(c, new float[][]{
                {225, 45, 0, 0.10f, -0.06f}, // dense blue skies
                {0, 30, 0, 0.05f, 0},        // rich reds
                {120, 45, 4, 0.08f, 0},      // vivid greens, a little toward emerald
                {30, 22, 0, -0.10f, 0},      // skin and wood kept from turning orange
        });
        split(c, -0.010f, 0.0f, 0.015f, 0.005f, 0.004f, -0.006f);
        saturate(c, 1.08f);
    }

    /** A motion-picture print: filmic S, teal shadows, warm highlights, teal-leaning greens, rolled-off whites. */
    static void cine500(float[] c) {
        film(c, 5.0f, 0.45f, 0.75f, 0.025f, 0.965f);
        bands(c, new float[][]{
                {120, 55, 18, -0.30f, 0},    // greens toward teal, quieter
                {215, 40, -5, -0.10f, -0.05f}, // blues toward cyan, not lighter
                {25, 25, 0, 0.05f, 0},       // skin kept warm
        });
        split(c, -0.030f, 0.005f, 0.030f, 0.030f, 0.008f, -0.030f);
        saturate(c, 0.90f);
    }

    /** A panchromatic black-and-white film: a slightly red-sensitive mix, a firm S, a soft toe. */
    static void mono400(float[] c) {
        float y = 0.30f * c[0] + 0.59f * c[1] + 0.11f * c[2];
        c[0] = c[1] = c[2] = y;
        film(c, 5.5f, 0.45f, 0.85f, 0.025f, 0.985f);
    }

    // ───── the building blocks (display-encoded values)

    static float luma(float[] c) {
        return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    }

    /**
     * A film S per channel: a logistic of slope {@code k} round {@code pivot} normalised to 0..1, mixed in by {@code amount},
     * then the black lifted to {@code lift} and the white brought to {@code white}.
     */
    static void film(float[] c, float k, float pivot, float amount, float lift, float white) {
        float lo = logistic(0f, k, pivot), hi = logistic(1f, k, pivot);
        for (int i = 0; i < 3; i++) {
            float x = clamp(c[i]);
            float s = (logistic(x, k, pivot) - lo) / (hi - lo);
            float y = x + (s - x) * amount;
            c[i] = lift + (white - lift) * y;
        }
    }

    private static float logistic(float x, float k, float pivot) {
        return 1f / (1f + (float) Math.exp(-k * (x - pivot)));
    }

    /** Shadows toward (sr, sg, sb), highlights toward (hr, hg, hb), weighted by the luma; the luma itself kept. */
    static void split(float[] c, float sr, float sg, float sb, float hr, float hg, float hb) {
        float l = clamp(luma(c));
        float ws = (1f - l) * (1f - l), wh = l * l;
        float[] o = {sr * ws + hr * wh, sg * ws + hg * wh, sb * ws + hb * wh};
        float lo = luma(o);
        for (int i = 0; i < 3; i++) c[i] += o[i] - lo;
    }

    /** Colour away from the luma by {@code s}. */
    static void saturate(float[] c, float s) {
        float l = luma(c);
        for (int i = 0; i < 3; i++) c[i] = l + (c[i] - l) * s;
    }

    /**
     * Hue bands {centre degrees, half width degrees, hue shift degrees, saturation change, value change}: each moves the
     * colours near its centre by a smooth bump of the hue distance (HSV; the value change scales with the saturation, so
     * the greys stay where they are).
     */
    static void bands(float[] c, float[][] bands) {
        float r = clamp(c[0]), g = clamp(c[1]), b = clamp(c[2]);
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float v = max, d = max - min;
        if (d <= 1e-6f || max <= 1e-6f) return;   // a grey: no hue
        float s = d / max;
        float h;
        if (max == r) h = 60f * (((g - b) / d) % 6f);
        else if (max == g) h = 60f * ((b - r) / d + 2f);
        else h = 60f * ((r - g) / d + 4f);
        if (h < 0f) h += 360f;
        float dh = 0f, ds = 0f, dv = 0f;
        for (float[] band : bands) {
            float dist = Math.abs(h - band[0]);
            if (dist > 180f) dist = 360f - dist;
            float t = dist / band[1];
            if (t >= 1f) continue;
            float w = (1f - t * t) * (1f - t * t);
            dh += band[2] * w;
            ds += band[3] * w;
            dv += band[4] * w;
        }
        h = (h + dh + 360f) % 360f;
        s = clamp(s * (1f + ds));
        v = clamp(v * (1f + dv * s));
        float cc = v * s, x = cc * (1f - Math.abs((h / 60f) % 2f - 1f)), m = v - cc;
        float rr, gg, bb;
        int sector = (int) (h / 60f) % 6;
        switch (sector) {
            case 0: rr = cc; gg = x; bb = 0; break;
            case 1: rr = x; gg = cc; bb = 0; break;
            case 2: rr = 0; gg = cc; bb = x; break;
            case 3: rr = 0; gg = x; bb = cc; break;
            case 4: rr = x; gg = 0; bb = cc; break;
            default: rr = cc; gg = 0; bb = x; break;
        }
        c[0] = rr + m;
        c[1] = gg + m;
        c[2] = bb + m;
    }

    static float clamp(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }
}
