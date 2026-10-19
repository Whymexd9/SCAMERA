package com.particlesdevs.photoncamera.processing.color;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.Lang;

/**
 * P228 (owner, 2026-10-18: «Реализовать симуляцию Black pro mist, glimmer glass и других подобных фильтров ... с возможностью
 * настройки»): the lens filters - Black Pro-Mist, CineBloom, Glimmerglass, Pearlescent, Fog, Double Fog, GND, Light Pollution,
 * Streak, Prism, Kaleidoscope, Infrared - as models on the linear scene-referred RGB before the ARK tone (LensFilter node on the
 * GPU, {@link #apply} here on the CPU for the settings' preview and the LUT tray's thumbnails; both follow the same formulas).
 * <p>
 * The diffusion family (Black Pro-Mist, CineBloom, Glimmerglass, Pearlescent, Fog, Double Fog) is {@link DiffusionModel}: a share
 * of all the light spread with the filter's point-spread function, energy kept (owner, 2026-10-18: «посмотри как симуляция сделана
 * ... исправь у нас»). Glimmerglass adds its short star, Pearlescent its pastel. Each filter has a strength (0..1) and at most one parameter of its own
 * (0..1; its meaning per filter in {@link #paramName}) and the fine controls of {@link Tune} (owner: «дай возможность настраивать
 * эти фильтры»): the threshold, the radius, the haze and the glow's tone, each 0.5 = the filter as designed. Stored as the
 * filter's id, the strength 0..100 and, per filter, its {@link Tune} ({@link #tuneKey}).
 */
public final class LensFilters {
    public static final String KEY = "pref_lens_filter", STRENGTH_KEY = "pref_lens_filter_strength", TUNE_PREFIX = "pref_lens_filter_tune_";
    public static final String NONE = "", BLACK_PRO_MIST = "bpm", CINEBLOOM = "cinebloom", GLIMMERGLASS = "glimmer",
            PEARLESCENT = "pearl", FOG = "fog", DOUBLE_FOG = "dfog", GND = "gnd", LIGHT_POLLUTION = "lightpol", STREAK = "streak",
            PRISM = "prism", KALEIDOSCOPE = "kaleido", INFRARED = "ir";
    /**
     * P287 (owner, 2026-10-19: «Наши фильтры объективов замени на те что в спектра»): the filters offered are the reference
     * model's diffusion families only, in its order - Glimmerglass, Black Pro-Mist, Pro-Mist (stored as "pearl"), CineBloom
     * (DiffusionModel: its PSF groups, shares, strength table and halo warmth). The others are no longer offered: a stored one
     * reads as none.
     */
    public static final String[] IDS = {GLIMMERGLASS, BLACK_PRO_MIST, PEARLESCENT, CINEBLOOM};
    /** The GPU and CPU numbering of the filters (lensfilter/apply.glsl FILTER), fixed. */
    private static final String[] NUMBERING = {BLACK_PRO_MIST, CINEBLOOM, GLIMMERGLASS, PEARLESCENT, FOG, DOUBLE_FOG, GND,
            LIGHT_POLLUTION, STREAK, PRISM, KALEIDOSCOPE, INFRARED};
    /** Pyramid levels of the glow (each twice as wide as the one before; level 1 = half the image size). */
    public static final int LEVELS = 6;

    /** The fine controls (bits of {@link #tunable}). */
    public static final int T_KNEE = 1, T_RADIUS = 2, T_HAZE = 4, T_WARMTH = 8;
    public static final int[] CONTROLS = {T_KNEE, T_RADIUS, T_HAZE, T_WARMTH};

    private LensFilters() {}

    /**
     * A filter's own settings: its parameter and the fine controls, each 0..1 (0.5 = as designed). Stored per filter as
     * "param,knee,radius,haze,warmth" in 0..100.
     */
    public static final class Tune {
        public float param, knee = 0.5f, radius = 0.5f, haze = 0.5f, warmth = 0.5f;

        public static Tune defaults(@Nullable String id) {
            Tune t = new Tune();
            t.param = defaultParam(id);
            return t;
        }

        public static Tune of(@Nullable String id, float param) {
            Tune t = defaults(id);
            t.param = clamp01(param);
            return t;
        }

        /** The stored text; anything unreadable keeps that value's default. */
        public static Tune parse(@Nullable String id, @Nullable String text) {
            Tune t = defaults(id);
            if (text == null || text.isEmpty()) return t;
            String[] parts = text.split(",");
            float[] v = {t.param, t.knee, t.radius, t.haze, t.warmth};
            for (int i = 0; i < Math.min(parts.length, v.length); i++) {
                try {
                    float f = Float.parseFloat(parts[i].trim()) / 100f;
                    if (!Float.isNaN(f)) v[i] = clamp01(f);
                } catch (NumberFormatException ignored) {
                }
            }
            t.param = v[0];
            t.knee = v[1];
            t.radius = v[2];
            t.haze = v[3];
            t.warmth = v[4];
            return t;
        }

        public String serialize() {
            return Math.round(param * 100f) + "," + Math.round(knee * 100f) + "," + Math.round(radius * 100f) + ","
                    + Math.round(haze * 100f) + "," + Math.round(warmth * 100f);
        }

        public float get(int control) {
            switch (control) {
                case T_KNEE: return knee;
                case T_RADIUS: return radius;
                case T_HAZE: return haze;
                case T_WARMTH: return warmth;
                default: return param;
            }
        }

        public void set(int control, float value) {
            value = clamp01(value);
            switch (control) {
                case T_KNEE: knee = value; break;
                case T_RADIUS: radius = value; break;
                case T_HAZE: haze = value; break;
                case T_WARMTH: warmth = value; break;
                default: param = value;
            }
        }

        /** The fine controls as designed (the parameter is left). */
        public boolean fineDefault() {
            return knee == 0.5f && radius == 0.5f && haze == 0.5f && warmth == 0.5f;
        }
    }

    static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** The key of a filter's {@link Tune}. */
    public static String tuneKey(String id) {
        return TUNE_PREFIX + id;
    }

    /** Which fine controls the filter has (T_* bits): the diffusion family all four, the star / streak fewer, the rest none. */
    public static int tunable(@Nullable String id) {
        switch (id == null ? "" : id) {
            case BLACK_PRO_MIST: case CINEBLOOM: case PEARLESCENT: case FOG: case DOUBLE_FOG: case GLIMMERGLASS:
                return T_KNEE | T_RADIUS | T_HAZE | T_WARMTH;
            case STREAK: return T_KNEE | T_WARMTH;
            case PRISM: return T_HAZE;
            default: return 0;
        }
    }

    /** A fine control's name. */
    public static String controlName(@Nullable android.content.Context c, @Nullable String id, int control) {
        switch (control) {
            case T_KNEE: return DiffusionModel.diffuses(id) ? Lang.t(c, "Ореол", "Halo") : Lang.t(c, "Порог свечения", "Glow threshold");
            case T_RADIUS: return Lang.t(c, "Радиус", "Radius");
            case T_HAZE: return PRISM.equals(id) ? Lang.t(c, "Яркость двойника", "Ghost brightness") : Lang.t(c, "Дымка", "Haze");
            default: return Lang.t(c, "Тон свечения", "Glow tone");
        }
    }

    /** What a fine control does, under its slider. */
    public static String controlHint(@Nullable android.content.Context c, @Nullable String id, int control) {
        switch (control) {
            case T_KNEE: return DiffusionModel.diffuses(id) ? Lang.t(c, "левее слабее ореол вокруг огней, правее сильнее", "left a fainter halo round lights, right a stronger one")
                    : Lang.t(c, "левее - светятся и более тёмные места", "lower: dimmer areas glow too");
            case T_RADIUS: return Lang.t(c, "уже или шире ореол", "a tighter or wider halo");
            case T_HAZE: return PRISM.equals(id) ? Lang.t(c, "насколько заметен цветной двойник", "how visible the tinted ghost is")
                    : Lang.t(c, "шире дымка по кадру: светлее тени, ниже контраст", "a wider haze over the frame: lifted shadows, lower contrast");
            default: return Lang.t(c, "левее холоднее, правее теплее", "left cooler, right warmer");
        }
    }

    /** The filter's number for the shader (0: none, 1.. in {@link #IDS} order), -1 for an unknown id. */
    public static int index(@Nullable String id) {
        if (id == null || id.isEmpty()) return 0;
        boolean offered = false;
        for (String o : IDS) offered |= o.equals(id);
        if (!offered) return -1;
        for (int i = 0; i < NUMBERING.length; i++) if (NUMBERING[i].equals(id)) return i + 1;
        return -1;
    }

    public static boolean known(@Nullable String id) {
        return index(id) >= 0;
    }

    public static String name(@Nullable android.content.Context c, @Nullable String id) {
        switch (id == null ? "" : id) {
            case BLACK_PRO_MIST: return "Black Pro-Mist";
            case CINEBLOOM: return "CineBloom";
            case GLIMMERGLASS: return "Glimmerglass";
            case PEARLESCENT: return "Pro-Mist";
            case FOG: return Lang.t(c, "Туман", "Fog");
            case DOUBLE_FOG: return Lang.t(c, "Двойной туман", "Double Fog");
            case GND: return Lang.t(c, "Градиент ND", "Graduated ND");
            case LIGHT_POLLUTION: return Lang.t(c, "Против засветки", "Light Pollution");
            case STREAK: return Lang.t(c, "Лучи (Streak)", "Streak");
            case PRISM: return Lang.t(c, "Призма", "Prism");
            case KALEIDOSCOPE: return Lang.t(c, "Калейдоскоп", "Kaleidoscope");
            case INFRARED: return Lang.t(c, "Инфракрасный", "Infrared");
            default: return Lang.t(c, "Нет", "None");
        }
    }

    /** The short hint under a card. */
    public static String hint(@Nullable android.content.Context c, @Nullable String id) {
        switch (id == null ? "" : id) {
            case BLACK_PRO_MIST: return Lang.t(c, "плотный ореол у светов, чёрный глубокий", "punchy halo, deep black");
            case CINEBLOOM: return Lang.t(c, "свечение на весь кадр, тёплый ободок", "frame-wide bloom, warm rim");
            case GLIMMERGLASS: return Lang.t(c, "чуть мягче, почти без дымки", "subtle, almost no haze");
            case PEARLESCENT: return Lang.t(c, "пастельная дымка, широкий ореол", "pastel haze, broad halo");
            case FOG: return Lang.t(c, "дымка, мягкий контраст", "haze, soft contrast");
            case DOUBLE_FOG: return Lang.t(c, "свечение, детали целы", "glow, detail kept");
            case GND: return Lang.t(c, "темнее небо", "darker sky");
            case LIGHT_POLLUTION: return Lang.t(c, "убирает жёлтые фонари", "cuts sodium light");
            case STREAK: return Lang.t(c, "лучи от огней", "streaks from lights");
            case PRISM: return Lang.t(c, "цветной двойник", "tinted ghost");
            case KALEIDOSCOPE: return Lang.t(c, "зеркальные сектора", "mirrored segments");
            case INFRARED: return Lang.t(c, "листва белая, небо тёмное", "white foliage, dark sky");
            default: return Lang.t(c, "без фильтра", "no filter");
        }
    }

    /** What the filter does, one line. */
    public static String description(@Nullable android.content.Context c, @Nullable String id) {
        switch (id == null ? "" : id) {
            case BLACK_PRO_MIST: return Lang.t(c, "Плотный яркий ореол рядом со светом, быстро спадает; чёрный остаётся глубоким",
                    "A concentrated punchy halo close to the light with a fast fall-off; the blacks stay deep");
            case CINEBLOOM: return Lang.t(c, "Свечение на весь кадр с медленным хвостом и ретро-вуалью, самый тёплый ободок",
                    "A frame-wide bloom with a slow tail and a retro veil, the warmest rim");
            case GLIMMERGLASS: return Lang.t(c, "Тонкое смягчение микроконтраста, почти без вуали, слабый длинный хвост вокруг ярких огней",
                    "Subtle micro-contrast smoothing, almost no veil, a faint long tail around bright lights");
            case PEARLESCENT: return Lang.t(c, "Атмосферная пастельная дымка: широкий ореол и ровная вуаль",
                    "An atmospheric pastel haze: a broad halo and a balanced veil");
            case FOG: return Lang.t(c, "Дымка на весь кадр: светлее тени, ниже контраст, свечение огней",
                    "A haze over the frame: lifted shadows, lower contrast, glowing lights");
            case DOUBLE_FOG: return Lang.t(c, "Свечение как у тумана, но детали и контраст сохраняются лучше",
                    "A glow like fog, but detail and contrast stay better");
            case GND: return Lang.t(c, "Верх кадра темнее на несколько ступеней: небо не пересвечивается",
                    "The top of the frame darker by a few stops: the sky keeps its detail");
            case LIGHT_POLLUTION: return Lang.t(c, "Убирает оранжево-жёлтый свет фонарей, ночное небо холоднее",
                    "Cuts the orange-yellow street-light band, a cooler night sky");
            case STREAK: return Lang.t(c, "Горизонтальные анаморфотные лучи от огней", "Horizontal anamorphic streaks from lights");
            case PRISM: return Lang.t(c, "Смещённый цветной мягкий двойник части кадра", "A shifted, tinted, soft ghost of part of the frame");
            case KALEIDOSCOPE: return Lang.t(c, "Кадр, повторённый зеркальными секторами вокруг центра", "The frame repeated in mirrored segments around the centre");
            case INFRARED: return Lang.t(c, "Ложный инфракрасный цвет: листва светлая, небо тёмное", "False-colour infrared: bright foliage, dark sky");
            default: return Lang.t(c, "Снимок без фильтра", "The photo without a filter");
        }
    }

    /** The filter's own parameter's name, null when it has none. */
    @Nullable
    public static String paramName(@Nullable android.content.Context c, @Nullable String id) {
        switch (id == null ? "" : id) {
            case GND: return Lang.t(c, "Положение перехода", "Transition position");
            case STREAK: return Lang.t(c, "Длина лучей", "Streak length");
            case PRISM: return Lang.t(c, "Смещение", "Offset");
            case KALEIDOSCOPE: return Lang.t(c, "Число секторов", "Segments");
            case INFRARED: return Lang.t(c, "Вариант: ч/б · цветной · обмен каналов", "Variant: mono · colour · channel swap");
            default: return null;
        }
    }

    /** The parameter's default (0..1). */
    public static float defaultParam(@Nullable String id) {
        switch (id == null ? "" : id) {
            case GND: return 0.45f;
            case STREAK: return 0.5f;
            case PRISM: return 0.4f;
            case KALEIDOSCOPE: return 0.33f;
            case INFRARED: return 0.5f;
            default: return 0f;
        }
    }

    /** Kaleidoscope segments from the parameter: 3..12. */
    public static int segments(float param) {
        return 3 + Math.round(Math.max(0f, Math.min(1f, param)) * 9f);
    }

    // ───── the model: per-filter constants (shared with lensfilter/apply.glsl through LensFilter)

    /** {knee, glow amount, lift, veil} at strength 1 with the fine controls: the threshold x0.25..x4, the haze x0..x4. */
    public static float[] shape(@Nullable String id, Tune t) {
        float[] s = shape(id);
        s[0] *= (float) Math.pow(2.0, (t.knee - 0.5f) * 4f);
        float haze = hazeGain(t.haze);
        s[2] *= haze;
        s[3] = Math.min(0.9f, s[3] * haze);
        return s;
    }

    /** The haze control's gain: 0 at 0, 1 at 0.5 (as designed), 4 at 1. */
    public static float hazeGain(float haze) {
        return haze <= 0.5f ? 2f * haze : 1f + 6f * (haze - 0.5f);
    }

    /** The PSF with the radius: the weights tilted towards the narrow (0) or the wide (1) levels, still summing to 1. */
    public static float[] psf(@Nullable String id, Tune t) {
        float[] w = psf(id);
        float r = (t.radius - 0.5f) * 2f, sum = 0f;
        for (int k = 0; k < w.length; k++) {
            w[k] *= (float) Math.pow(2.0, 2.0 * r * (k - 2.5));
            sum += w[k];
        }
        if (sum > 0f) for (int k = 0; k < w.length; k++) w[k] /= sum;
        return w;
    }

    /** The glow's colour with the tone: warmer (more red, less blue) or cooler. */
    public static float[] tint(@Nullable String id, Tune t) {
        float[] c = tint(id);
        float k = (t.warmth - 0.5f) * 2f;
        c[0] *= 1f + 0.25f * k;
        c[1] *= 1f + 0.04f * k;
        c[2] *= 1f - 0.25f * k;
        return c;
    }

    /** {knee, glow amount, lift, veil} at strength 1. */
    public static float[] shape(@Nullable String id) {
        switch (id == null ? "" : id) {
            case BLACK_PRO_MIST: return new float[]{0.45f, 0.55f, 0f, 0.06f};
            case CINEBLOOM: return new float[]{0.5f, 0.6f, 0.01f, 0.1f};
            case GLIMMERGLASS: return new float[]{0.6f, 0.3f, 0f, 0f};
            case PEARLESCENT: return new float[]{0.45f, 0.5f, 0.015f, 0.08f};
            case FOG: return new float[]{0.5f, 0.35f, 0.025f, 0.35f};
            case DOUBLE_FOG: return new float[]{0.45f, 0.5f, 0.012f, 0.18f};
            case STREAK: return new float[]{0.7f, 0f, 0f, 0f};
            default: return new float[]{1f, 0f, 0f, 0f};
        }
    }

    /** The PSF: the weights of the pyramid levels 1..LEVELS (they sum to 1). */
    public static float[] psf(@Nullable String id) {
        switch (id == null ? "" : id) {
            case BLACK_PRO_MIST: return new float[]{0.10f, 0.15f, 0.20f, 0.22f, 0.18f, 0.15f};
            case CINEBLOOM: return new float[]{0.05f, 0.10f, 0.15f, 0.20f, 0.25f, 0.25f};
            case GLIMMERGLASS: return new float[]{0.40f, 0.30f, 0.18f, 0.08f, 0.04f, 0f};
            case PEARLESCENT: return new float[]{0.15f, 0.20f, 0.25f, 0.20f, 0.12f, 0.08f};
            case FOG: return new float[]{0.04f, 0.08f, 0.14f, 0.20f, 0.26f, 0.28f};
            case DOUBLE_FOG: return new float[]{0.08f, 0.12f, 0.18f, 0.22f, 0.22f, 0.18f};
            default: return new float[]{0f, 0f, 0f, 0f, 0f, 0f};
        }
    }

    /** The glow's colour (linear RGB gains). */
    public static float[] tint(@Nullable String id) {
        switch (id == null ? "" : id) {
            case CINEBLOOM: return new float[]{1.0f, 0.95f, 0.88f};
            case PEARLESCENT: return new float[]{1.0f, 0.93f, 1.06f};
            case STREAK: return new float[]{0.55f, 0.8f, 1.25f};
            default: return new float[]{1f, 1f, 1f};
        }
    }

    public static boolean glows(@Nullable String id) {
        float[] s = shape(id);
        return s[1] > 0f;
    }

    public static boolean veils(@Nullable String id) {
        return shape(id)[3] > 0f || PRISM.equals(id);
    }

    public static boolean streaks(@Nullable String id) {
        return STREAK.equals(id);
    }

    /** The streak's half length as a fraction of the image width: Glimmerglass a short star, Streak 3..20 % by the parameter. */
    public static float streakLength(@Nullable String id, float param) {
        return GLIMMERGLASS.equals(id) ? 0.04f : 0.03f + 0.17f * param;
    }

    /** The streak's length with the fine controls (Glimmerglass: the radius x0.5..x2). */
    public static float streakLength(@Nullable String id, Tune t) {
        float len = streakLength(id, t.param);
        return GLIMMERGLASS.equals(id) ? len * (float) Math.pow(2.0, (t.radius - 0.5f) * 2f) : len;
    }

    /** The streak plane's gain (the taps sum to 1 per direction). */
    public static float streakGain(@Nullable String id) {
        return GLIMMERGLASS.equals(id) ? 1.6f : 4f;
    }

    static float luma(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    // ───── the CPU model (the settings' sample and the tray's thumbnails)

    /**
     * {@code rgb}: linear RGB, 3 floats a pixel, w x h, changed in place by filter {@code id} at {@code strength} (0..1) and
     * {@code param} (0..1). Values above 1 are highlights with headroom (a source brighter than white blooms by its energy).
     */
    public static void apply(float[] rgb, int w, int h, @Nullable String id, float strength, float param) {
        apply(rgb, w, h, id, strength, Tune.of(id, param));
    }

    /** {@link #apply(float[], int, int, String, float, float)} with the filter's fine controls. */
    public static void apply(float[] rgb, int w, int h, @Nullable String id, float strength, Tune tune) {
        final float param = tune.param;
        final int f = index(id);
        if (f <= 0 || strength <= 0f || w < 2 || h < 2) return;
        final float s = Math.min(1f, strength);
        if (DiffusionModel.diffuses(id)) {
            diffuse(rgb, w, h, id, s, tune); // P287: the reference's diffusion alone (no star, no pastel)
            return;
        }
        if (KALEIDOSCOPE.equals(id)) { kaleidoscope(rgb, w, h, s, param); return; }
        if (GND.equals(id)) { gnd(rgb, w, h, s, param); return; }
        if (LIGHT_POLLUTION.equals(id)) { lightPollution(rgb, s); return; }
        if (INFRARED.equals(id)) { infrared(rgb, s, param); return; }
        final float[] shape = shape(id, tune);
        final float knee = shape[0];
        final float[] src = rgb.clone();
        // the highlight part (the light above the knee, its colour kept)
        float[] hi = new float[src.length];
        for (int i = 0; i < src.length; i += 3) {
            float y = luma(src[i], src[i + 1], src[i + 2]);
            float k = y > knee ? (y - knee) / Math.max(y, 1e-6f) : 0f;
            hi[i] = src[i] * k;
            hi[i + 1] = src[i + 1] * k;
            hi[i + 2] = src[i + 2] * k;
        }
        float[] glow = null;
        if (shape[1] > 0f) glow = pyramidSum(hi, w, h, psf(id, tune));
        float[] streak = streaks(id) ? streak(hi, w, h, id, streakLength(id, tune)) : null;
        float[] veil = veils(id) ? pyramidSum(src, w, h, new float[]{0f, 0f, 0f, 0.2f, 0.4f, 0.4f}) : null;
        final float[] tint = tint(id, tune);
        final float a = shape[1] * s, lift = shape[2] * s, veilA = shape[3] * s;
        final boolean prism = PRISM.equals(id);
        for (int y = 0, i = 0; y < h; y++) {
            for (int x = 0; x < w; x++, i += 3) {
                for (int ch = 0; ch < 3; ch++) {
                    float v = src[i + ch];
                    if (glow != null) v += a * (glow[i + ch] * tint[ch] - hi[i + ch]);
                    if (veil != null && !prism) v += veilA * (veil[i + ch] - v);
                    if (streak != null) v += s * streak[i + ch] * tint[ch];
                    v += lift;
                    src[i + ch] = v;
                }
                if (prism) prismGhost(src, i, x, y, w, h, veil, s * hazeGain(tune.haze), param);
            }
        }
        System.arraycopy(src, 0, rgb, 0, rgb.length);
    }

    /** The light above {@code knee} (its colour kept): the star's source. */
    static float[] highlights(float[] src, float knee) {
        float[] hi = new float[src.length];
        for (int i = 0; i < src.length; i += 3) {
            float y = luma(src[i], src[i + 1], src[i + 2]);
            float k = y > knee ? (y - knee) / Math.max(y, 1e-6f) : 0f;
            hi[i] = src[i] * k;
            hi[i + 1] = src[i + 1] * k;
            hi[i + 2] = src[i + 2] * k;
        }
        return hi;
    }

    /** Pyramid levels of a w x h image down to about 2 pixels (at most 12). */
    public static int diffusionLevels(int w, int h) {
        int k = 0;
        for (int m = Math.min(w, h); m >= 4 && k < 12; m /= 2) k++;
        return k;
    }

    /**
     * {@link DiffusionModel} on the CPU, in place: the image's pyramid (the [1 3 3 1] steps), its levels weighted per channel and
     * summed from the coarse end with B-spline steps, plus the image itself times its own weight.
     */
    static void diffuse(float[] rgb, int w, int h, String id, float s, Tune tune) {
        // P288: the photo's FFT convolution (DiffusionFft), the frame as long as this image
        final DiffusionFft.Plan plan = DiffusionFft.plan(id, s, tune, w, h, Math.max(w, h));
        if (plan != null) DiffusionFft.apply(rgb, w, h, plan);
    }

    /**
     * Σ weight_k x (the image blurred to pyramid level k, upsampled): as the photo (lensfilter/upsum.glsl) - from the coarsest
     * level up, each step the level times its weight plus the coarser sum B-spline upsampled, the last step to full size
     * (owner, 2026-10-18: a bilinear stretch of the coarse levels drew square highlights).
     */
    static float[] pyramidSum(float[] img, int w, int h, float[] weights) {
        int n = weights.length;
        float[][] levels = new float[n][];
        int[] lw = new int[n], lh = new int[n];
        float[] level = img;
        int cw = w, ch = h;
        for (int k = 0; k < n; k++) {
            int nw = Math.max(1, cw / 2), nh = Math.max(1, ch / 2);
            level = down(level, cw, ch, nw, nh);
            levels[k] = level;
            lw[k] = cw = nw;
            lh[k] = ch = nh;
        }
        float[] acc = null;
        int aw = 0, ah = 0;
        for (int k = n - 1; k >= 0; k--) {
            float[] next = acc == null ? new float[levels[k].length] : bsplineUp(acc, aw, ah, lw[k], lh[k]);
            if (weights[k] != 0f)
                for (int i = 0; i < next.length; i++) next[i] += weights[k] * levels[k][i];
            acc = next;
            aw = lw[k];
            ah = lh[k];
        }
        return bsplineUp(acc, aw, ah, w, h);
    }

    /** {@code src} (sw x sh, 3 floats a pixel) upsampled to w x h with a cubic B-spline (C2, a light's glow round). */
    static float[] bsplineUp(float[] src, int sw, int sh, int w, int h) {
        float[] out = new float[w * h * 3];
        final int[] xi = new int[w * 4];
        final float[] xw = new float[w * 4];
        for (int x = 0; x < w; x++) taps((x + 0.5f) * sw / w - 0.5f, sw, xi, xw, x * 4);
        final int[] yi = new int[4];
        final float[] yw = new float[4];
        for (int y = 0; y < h; y++) {
            taps((y + 0.5f) * sh / h - 0.5f, sh, yi, yw, 0);
            for (int x = 0; x < w; x++) {
                float r = 0f, g = 0f, b = 0f;
                for (int ty = 0; ty < 4; ty++) {
                    int row = yi[ty] * sw;
                    float wy = yw[ty];
                    for (int tx = 0; tx < 4; tx++) {
                        int j = (row + xi[x * 4 + tx]) * 3;
                        float wgt = wy * xw[x * 4 + tx];
                        r += wgt * src[j];
                        g += wgt * src[j + 1];
                        b += wgt * src[j + 2];
                    }
                }
                int o = (y * w + x) * 3;
                out[o] = r;
                out[o + 1] = g;
                out[o + 2] = b;
            }
        }
        return out;
    }

    /** The four cubic B-spline taps (clamped indices, weights summing to 1) around position q of a row of n samples. */
    static void taps(float q, int n, int[] idx, float[] wgt, int at) {
        int i = (int) Math.floor(q);
        float f = q - i, f2 = f * f, f3 = f2 * f;
        wgt[at] = (1f - 3f * f + 3f * f2 - f3) / 6f;
        wgt[at + 1] = (4f - 6f * f2 + 3f * f3) / 6f;
        wgt[at + 2] = (1f + 3f * f + 3f * f2 - 3f * f3) / 6f;
        wgt[at + 3] = f3 / 6f;
        for (int t = 0; t < 4; t++) idx[at + t] = Math.max(0, Math.min(n - 1, i - 1 + t));
    }

    /** A 2:1 down-sample with a [1 2 1] x [1 2 1] filter (the Gaussian pyramid step). */
    static float[] down(float[] img, int w, int h, int nw, int nh) {
        float[] out = new float[nw * nh * 3];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                for (int ch = 0; ch < 3; ch++) {
                    float sum = 0f, wsum = 0f;
                    for (int dy = -1; dy <= 2; dy++) {
                        int sy = Math.min(h - 1, Math.max(0, 2 * y + dy));
                        float wy = dy == -1 || dy == 2 ? 1f : 3f;
                        for (int dx = -1; dx <= 2; dx++) {
                            int sx = Math.min(w - 1, Math.max(0, 2 * x + dx));
                            float wx = dx == -1 || dx == 2 ? 1f : 3f;
                            sum += wx * wy * img[(sy * w + sx) * 3 + ch];
                            wsum += wx * wy;
                        }
                    }
                    out[(y * nw + x) * 3 + ch] = sum / wsum;
                }
            }
        }
        return out;
    }

    /** {@link #bilinear} of the three channels at once (the same numbers, one address computation). */
    static void bilinear3(float[] img, int w, int h, float fx, float fy, float[] out) {
        fx = Math.max(0f, Math.min(w - 1f, fx));
        fy = Math.max(0f, Math.min(h - 1f, fy));
        int x0 = (int) fx, y0 = (int) fy, x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        float ax = fx - x0, ay = fy - y0;
        int i00 = (y0 * w + x0) * 3, i01 = (y0 * w + x1) * 3, i10 = (y1 * w + x0) * 3, i11 = (y1 * w + x1) * 3;
        for (int ch = 0; ch < 3; ch++) {
            float a = img[i00 + ch], b = img[i01 + ch];
            float c = img[i10 + ch], d = img[i11 + ch];
            out[ch] = (a + (b - a) * ax) + ((c + (d - c) * ax) - (a + (b - a) * ax)) * ay;
        }
    }

    static float bilinear(float[] img, int w, int h, float fx, float fy, int ch) {
        fx = Math.max(0f, Math.min(w - 1f, fx));
        fy = Math.max(0f, Math.min(h - 1f, fy));
        int x0 = (int) fx, y0 = (int) fy, x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        float ax = fx - x0, ay = fy - y0;
        float a = img[(y0 * w + x0) * 3 + ch], b = img[(y0 * w + x1) * 3 + ch];
        float c = img[(y1 * w + x0) * 3 + ch], d = img[(y1 * w + x1) * 3 + ch];
        return (a + (b - a) * ax) + ((c + (d - c) * ax) - (a + (b - a) * ax)) * ay;
    }

    /** Streak: horizontal taps with an exponential fall-off; Glimmerglass: a short four-point star. */
    static float[] streak(float[] hi, int w, int h, String id, float length) {
        final boolean star = GLIMMERGLASS.equals(id);
        final float len = length * w;
        final int taps = 24;
        final int[][] dirs = star ? new int[][]{{1, 1}, {1, -1}} : new int[][]{{1, 0}};
        float[] out = new float[hi.length];
        float norm = 0f;
        for (int t = 1; t <= taps; t++) norm += 2f * (float) Math.exp(-3.0 * t / taps);
        norm *= dirs.length;
        // the taps' weights and offsets once (they were computed per pixel)
        final float[] wgt = new float[taps + 1], off = new float[taps + 1];
        for (int t = 1; t <= taps; t++) {
            wgt[t] = (float) Math.exp(-3.0 * t / taps) / norm;
            off[t] = len * t / taps;
        }
        final float gain = streakGain(id);
        final float[] px = new float[3];
        for (int y = 0, i = 0; y < h; y++) {
            for (int x = 0; x < w; x++, i += 3) {
                float r = 0f, g = 0f, b = 0f;
                for (int[] d : dirs) {
                    for (int t = 1; t <= taps; t++) {
                        for (int sgn = -1; sgn <= 1; sgn += 2) {
                            float fx = x + sgn * off[t] * d[0], fy = y + sgn * off[t] * d[1];
                            bilinear3(hi, w, h, fx, fy, px);
                            r += wgt[t] * px[0];
                            g += wgt[t] * px[1];
                            b += wgt[t] * px[2];
                        }
                    }
                }
                out[i] = r * gain;
                out[i + 1] = g * gain;
                out[i + 2] = b * gain;
            }
        }
        return out;
    }

    /** Prism: a shifted (up-right by the parameter), tinted copy of the soft image added on the frame's left part. */
    static void prismGhost(float[] out, int i, int x, int y, int w, int h, float[] soft, float s, float param) {
        float off = (0.05f + 0.2f * param) * w;
        float fx = x + off, fy = y - off * 0.35f;
        float mask = 1f - smooth(0.35f * w, 0.75f * w, x);
        float a = 0.35f * s * mask;
        float[] tint = {1.15f, 0.9f, 1.2f};
        for (int ch = 0; ch < 3; ch++) out[i + ch] += a * tint[ch] * bilinear(soft, w, h, fx + (ch - 1) * 0.004f * w, fy, ch);
    }

    static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    static void pastel(float[] rgb, float k) {
        for (int i = 0; i < rgb.length; i += 3) {
            float y = luma(rgb[i], rgb[i + 1], rgb[i + 2]);
            for (int ch = 0; ch < 3; ch++) rgb[i + ch] += k * (y - rgb[i + ch]);
        }
    }

    /** GND: the top darker by up to 3 stops x strength, the transition at {@code param} (0 top .. 1 bottom), soft. */
    static void gnd(float[] rgb, int w, int h, float s, float param) {
        for (int y = 0; y < h; y++) {
            float t = (y + 0.5f) / h;
            float d = 1f - smooth(param - 0.15f, param + 0.15f, t);
            float gain = (float) Math.pow(2.0, -3.0 * s * d);
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 3;
                rgb[i] *= gain;
                rgb[i + 1] *= gain;
                rgb[i + 2] *= gain;
            }
        }
    }

    /** Light pollution: the orange-yellow band (R >= G > B) loses its chroma, the frame a little cooler. */
    static void lightPollution(float[] rgb, float s) {
        for (int i = 0; i < rgb.length; i += 3) {
            float r = rgb[i], g = rgb[i + 1], b = rgb[i + 2];
            float y = luma(r, g, b);
            float yellow = Math.max(0f, (r + g) * 0.5f - b) / Math.max(1e-4f, Math.max(r, Math.max(g, b)));
            float band = smooth(0.15f, 0.55f, yellow) * (r >= g ? 1f : smooth(0.6f, 1f, r / Math.max(1e-4f, g)));
            float k = 0.8f * s * band;
            r = r + k * (y - r);
            g = g + k * (y - g);
            b = b + k * (y - b);
            rgb[i] = r * (1f - 0.06f * s);
            rgb[i + 1] = g;
            rgb[i + 2] = b * (1f + 0.08f * s);
        }
    }

    /** Infrared: 0..1/3 monochrome IR, ..2/3 false colour (foliage pink-white), above that the R / B swap. */
    static void infrared(float[] rgb, float s, float param) {
        for (int i = 0; i < rgb.length; i += 3) {
            float r = rgb[i], g = rgb[i + 1], b = rgb[i + 2];
            float ir = Math.max(0f, 0.25f * r + 1.2f * g - 0.45f * b);
            float or, og, ob;
            if (param < 1f / 3f) { or = og = ob = ir; }
            else if (param < 2f / 3f) { or = 0.6f * ir + 0.6f * r; og = 0.75f * ir + 0.1f * r; ob = 0.55f * b + 0.25f * ir; }
            else { or = b; og = g; ob = r; }
            rgb[i] = r + s * (or - r);
            rgb[i + 1] = g + s * (og - g);
            rgb[i + 2] = b + s * (ob - b);
        }
    }

    /** Kaleidoscope: mirrored segments around the centre (count from the parameter), mixed by the strength. */
    static void kaleidoscope(float[] rgb, int w, int h, float s, float param) {
        final float[] src = rgb.clone();
        final int n = segments(param);
        final double seg = 2 * Math.PI / n;
        final float cx = w * 0.5f, cy = h * 0.5f;
        for (int y = 0, i = 0; y < h; y++) {
            for (int x = 0; x < w; x++, i += 3) {
                double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
                double r = Math.hypot(dx, dy), a = Math.atan2(dy, dx);
                double m = ((a % seg) + seg) % seg;
                double folded = Math.abs(m - seg / 2);
                float fx = (float) (cx + r * Math.cos(folded)) - 0.5f, fy = (float) (cy + r * Math.sin(folded)) - 0.5f;
                for (int ch = 0; ch < 3; ch++) rgb[i + ch] = src[i + ch] + s * (bilinear(src, w, h, fx, fy, ch) - src[i + ch]);
            }
        }
    }
}
