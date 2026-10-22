package com.particlesdevs.photoncamera.processing.color;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;

/**
 * SIMPLE_UI §4c «Что меняется» of the «Плёночный LUT» page (the concept's lutAnalyse): reference colours and greys go through
 * the LUT at the chosen strength and «before» is compared with «after», so imported .cube files get a description without
 * any text in the file.
 * <ul>
 *   <li>Контраст: the luminance spread of 10 % .. 90 % grey (+5 %: сильнее, -3 %: мягче); a middle (30 .. 70 %) that grows
 *       while the ends do not: «ярче в середине, мягче по краям».</li>
 *   <li>Чёрный / Белый: pure black above 1.5 % - приподнят; pure white below 98.5 % - приглушён.</li>
 *   <li>Цвета: the saturation of the chart's 18 colours (+4 %: насыщеннее, -5 %: спокойнее, about none: чёрно-белые).</li>
 *   <li>Тени / Света: the tint of 20 % / 80 % grey (red over blue: теплее, the other way: холоднее).</li>
 *   <li>Кожа: the light skin patch more saturated; Зелень: foliage towards yellow (olive) or blue (teal).</li>
 * </ul>
 */
public final class LutLook {
    /** A display colour 0..1 through the LUT (full strength), written into {@code out}. */
    public interface Fn {
        void apply(float r, float g, float b, float[] out);
    }

    /** The 24 patches of a colour chart (sRGB 0..255): 18 colours, then six greys. */
    public static final int[][] CHART = {{115, 82, 68}, {194, 150, 130}, {98, 122, 157}, {87, 108, 67}, {133, 128, 177},
            {103, 189, 170}, {214, 126, 44}, {80, 91, 166}, {193, 90, 99}, {94, 60, 108}, {157, 188, 64}, {224, 163, 46},
            {56, 61, 150}, {70, 148, 73}, {175, 54, 60}, {231, 199, 31}, {187, 86, 149}, {8, 133, 161}, {243, 243, 242},
            {200, 200, 200}, {160, 160, 160}, {122, 122, 121}, {85, 85, 85}, {52, 52, 52}};
    /** The six key colours shown before → after. */
    public static final int[][] KEY_COLOURS = {{194, 150, 130}, {98, 122, 157}, {87, 108, 67}, {175, 54, 60}, {45, 45, 48},
            {225, 222, 215}};

    private final Fn lut;
    private final float strength;

    /** {@code strength} 0..1: the mix in + (lut - in) x strength, as the photo (WhyCombine) and the preview mix it. */
    public LutLook(Fn lut, float strength) {
        this.lut = lut;
        this.strength = Math.max(0f, Math.min(1f, strength));
    }

    /** One display colour through the LUT at the strength. */
    public float[] apply(float r, float g, float b) {
        float[] o = new float[3];
        lut.apply(r, g, b, o);
        o[0] = clamp(r + (clamp(o[0]) - r) * strength);
        o[1] = clamp(g + (clamp(o[1]) - g) * strength);
        o[2] = clamp(b + (clamp(o[2]) - b) * strength);
        return o;
    }

    public static float luma(float[] c) {
        return .2126f * c[0] + .7152f * c[1] + .0722f * c[2];
    }

    private float grey(float v) {
        return luma(apply(v, v, v));
    }

    private static float spread(float[] c) {
        return Math.max(c[0], Math.max(c[1], c[2])) - Math.min(c[0], Math.min(c[1], c[2]));
    }

    /** The key names of the analysis, in the concept's order. */
    public enum Aspect { CONTRAST, BLACK, WHITE, COLOURS, SHADOWS, HIGHLIGHTS, SKIN, GREENS }

    /** One tag: the aspect and its verdict id. */
    public static final class Tag {
        public final Aspect aspect;
        public final String verdict;

        Tag(Aspect aspect, String verdict) {
            this.aspect = aspect;
            this.verdict = verdict;
        }

        public String name(@Nullable android.content.Context c) {
            switch (aspect) {
                case CONTRAST: return Lang.t(c, "Контраст", "Contrast");
                case BLACK: return Lang.t(c, "Чёрный", "Black");
                case WHITE: return Lang.t(c, "Белый", "White");
                case COLOURS: return Lang.t(c, "Цвета", "Colours");
                case SHADOWS: return Lang.t(c, "Тени", "Shadows");
                case HIGHLIGHTS: return Lang.t(c, "Света", "Highlights");
                case SKIN: return Lang.t(c, "Кожа", "Skin");
                default: return Lang.t(c, "Зелень", "Greens");
            }
        }

        public String value(@Nullable android.content.Context c) {
            switch (verdict) {
                case "midtones": return Lang.t(c, "ярче в середине, мягче по краям", "punchier middle, softer ends");
                case "stronger": return Lang.t(c, "сильнее", "stronger");
                case "softer": return Lang.t(c, "мягче", "softer");
                case "same": return Lang.t(c, "почти как был", "about the same");
                case "lifted": return Lang.t(c, "приподнят", "lifted");
                case "muted": return Lang.t(c, "приглушён", "muted");
                case "mono": return Lang.t(c, "чёрно-белые", "black and white");
                case "richer": return Lang.t(c, "насыщеннее", "richer");
                case "calmer": return Lang.t(c, "спокойнее", "calmer");
                case "kept": return Lang.t(c, "как были", "as they were");
                case "warmer": return Lang.t(c, "теплее", "warmer");
                case "cooler": return Lang.t(c, "холоднее", "cooler");
                case "olive": return Lang.t(c, "к оливковой", "towards olive");
                default: return Lang.t(c, "к бирюзовой", "towards teal");
            }
        }
    }

    /** The analysis (the concept's thresholds). */
    public List<Tag> analyse() {
        List<Tag> t = new ArrayList<>();
        float all = (grey(.9f) - grey(.1f)) / .8f, mid = (grey(.7f) - grey(.3f)) / .4f;
        if (mid > 1.05f && all <= 1.05f) t.add(new Tag(Aspect.CONTRAST, "midtones"));
        else t.add(new Tag(Aspect.CONTRAST, all > 1.05f ? "stronger" : all < .97f ? "softer" : "same"));
        if (luma(apply(0, 0, 0)) > .015f) t.add(new Tag(Aspect.BLACK, "lifted"));
        if (luma(apply(1, 1, 1)) < .985f) t.add(new Tag(Aspect.WHITE, "muted"));
        float before = 0, after = 0;
        for (int i = 0; i < 18; i++) {
            float r = CHART[i][0] / 255f, g = CHART[i][1] / 255f, b = CHART[i][2] / 255f;
            before += spread(new float[]{r, g, b});
            after += spread(apply(r, g, b));
        }
        float ratio = after / before;
        t.add(new Tag(Aspect.COLOURS, ratio < .08f ? "mono" : ratio > 1.04f ? "richer" : ratio < .95f ? "calmer" : "kept"));
        if (ratio >= .08f) {
            String sh = tone(apply(.2f, .2f, .2f)), hi = tone(apply(.8f, .8f, .8f));
            if (sh != null) t.add(new Tag(Aspect.SHADOWS, sh));
            if (hi != null) t.add(new Tag(Aspect.HIGHLIGHTS, hi));
            float[] skin0 = {194 / 255f, 150 / 255f, 130 / 255f};
            if (spread(apply(skin0[0], skin0[1], skin0[2])) > spread(skin0) * 1.04f) t.add(new Tag(Aspect.SKIN, "richer"));
            float[] gr = apply(87 / 255f, 108 / 255f, 67 / 255f);
            float dr = gr[0] - 87 / 255f, db = gr[2] - 67 / 255f;
            if (db > .015f || dr > .02f) t.add(new Tag(Aspect.GREENS, dr > db ? "olive" : "teal"));
        }
        return t;
    }

    @Nullable
    private static String tone(float[] c) {
        float w = c[0] - c[2];
        return w > .012f ? "warmer" : w < -.012f ? "cooler" : null;
    }

    static float clamp(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }
}
