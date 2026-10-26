package com.particlesdevs.photoncamera.gallery.editor;

import com.particlesdevs.photoncamera.gallery.editor.EditState.Param;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * P351: «Стили» - a few looks as settings (written for this editor, not taken from anywhere) and their strength: a style moves
 * each of its sliders from the photo's own value by amount x (style value - default); 100 % is the style as written on top of
 * the photo's edits, 0 % the photo as it was before the style was picked. Geometry is never touched.
 */
public final class EditPresets {
    private EditPresets() {}

    /** {param, value} pairs per style. */
    static final Object[][][] STYLES = {
            {{Param.CONTRAST, 25f}, {Param.VIBRANCE, 30f}, {Param.CLARITY, 15f}, {Param.SHADOWS, 15f}, {Param.HIGHLIGHTS, -20f}},
            {{Param.CONTRAST, -20f}, {Param.HIGHLIGHTS, -25f}, {Param.SHADOWS, 25f}, {Param.CLARITY, -15f}, {Param.SATURATION, -10f}},
            {{Param.TEMPERATURE, 18f}, {Param.TINT, 4f}, {Param.VIBRANCE, 12f}, {Param.GRADE_HI_HUE, 45f}, {Param.GRADE_HI_SAT, 20f}},
            {{Param.TEMPERATURE, -18f}, {Param.VIBRANCE, 8f}, {Param.GRADE_SH_HUE, 220f}, {Param.GRADE_SH_SAT, 20f}},
            {{Param.CONTRAST, -10f}, {Param.BLACKS, 25f}, {Param.SATURATION, -15f}, {Param.GRAIN, 25f}, {Param.GRAIN_SIZE, 35f},
                    {Param.GRADE_SH_HUE, 200f}, {Param.GRADE_SH_SAT, 18f}, {Param.GRADE_HI_HUE, 40f}, {Param.GRADE_HI_SAT, 15f},
                    {Param.CURVE_SHADOWS, 20f}},
            {{Param.MONO, 1f}, {Param.CONTRAST, 35f}, {Param.CLARITY, 20f}, {Param.BW_0, 20f}, {Param.BW_5, -30f}},
            {{Param.MONO, 1f}, {Param.CONTRAST, -10f}, {Param.SHADOWS, 20f}, {Param.GRAIN, 20f}},
            {{Param.DEHAZE, 20f}, {Param.VIBRANCE, 20f}, {Param.TEXTURE, 15f}, {Param.HIGHLIGHTS, -30f}},
    };

    static String name(int i) {
        switch (i) {
            case 0: return Lang.t("Яркий", "Vivid");
            case 1: return Lang.t("Мягкий", "Soft");
            case 2: return Lang.t("Тёплый", "Warm");
            case 3: return Lang.t("Холодный", "Cool");
            case 4: return Lang.t("Плёнка", "Film");
            case 5: return Lang.t("Ч/Б контраст", "B&W contrast");
            case 6: return Lang.t("Ч/Б мягкий", "B&W soft");
            default: return Lang.t("Пейзаж", "Landscape");
        }
    }

    /** {@code base} with style {@code i} at {@code amount} 0..1 (a monochrome style turns on at more than half). */
    static EditState apply(EditState base, int i, float amount) {
        EditState s = base.copy();
        for (Object[] pv : STYLES[i]) {
            Param p = (Param) pv[0];
            float v = (Float) pv[1];
            if (p == Param.MONO) {
                s.set(p, amount > 0.5f ? 1f : base.get(p));
                continue;
            }
            s.set(p, base.get(p) + (v - p.def) * amount);
        }
        return s;
    }
}
