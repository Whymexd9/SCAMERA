package com.particlesdevs.photoncamera.gallery.editor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.gallery.editor.EditState.Param;
import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P351: the tool panels of the editor's tabs, built in code in the gallery's look (GalleryUi): slider rows bound to the
 * state's {@link Param}s, chips for sub-sections and choices, the curve editor. {@link #refresh} puts the state's values back
 * into every bound control after undo / redo / reset / a style.
 */
final class EditPanels {
    interface Host {
        EditState state();

        /** The state changed; {@code commit}: one undo step (a slider let go, a button). */
        void changed(boolean commit);

        EditSource source();

        /** Geometry buttons of the crop tab. */
        void rotate();

        void flip();

        void resetCrop();

        void aspect(float ratio);

        void straighten(float degrees, boolean commit);

        /** A style picked (-1: none) / its strength changed. */
        void style(int index, float amount, boolean commit);
    }

    private final Context c;
    private final Host host;
    private final List<Runnable> refreshers = new ArrayList<>();
    int hslBand = 0, gradeWheel = 0, curveChannel = EditState.CURVE_MASTER;
    int styleIndex = -1;
    float styleAmount = 1f;

    EditPanels(Context c, Host host) {
        this.c = c;
        this.host = host;
    }

    void refresh() {
        for (Runnable r : refreshers) r.run();
    }

    // ---------------------------------------------------------------- pieces

    static String label(Param p) {
        switch (p) {
            case EXPOSURE: return Lang.t("Экспозиция", "Exposure");
            case CONTRAST: return Lang.t("Контраст", "Contrast");
            case HIGHLIGHTS: return Lang.t("Света", "Highlights");
            case SHADOWS: return Lang.t("Тени", "Shadows");
            case WHITES: return Lang.t("Белые", "Whites");
            case BLACKS: return Lang.t("Чёрные", "Blacks");
            case TEMPERATURE: return Lang.t("Температура", "Temperature");
            case TINT: return Lang.t("Оттенок", "Tint");
            case VIBRANCE: return Lang.t("Сочность", "Vibrance");
            case SATURATION: return Lang.t("Насыщенность", "Saturation");
            case TEXTURE: return Lang.t("Текстура", "Texture");
            case CLARITY: return Lang.t("Чёткость", "Clarity");
            case DEHAZE: return Lang.t("Дымка", "Dehaze");
            case VIGNETTE: return Lang.t("Виньетка", "Vignette");
            case VIGNETTE_MID: return Lang.t("Середина виньетки", "Vignette midpoint");
            case GRAIN: return Lang.t("Зерно", "Grain");
            case GRAIN_SIZE: return Lang.t("Размер зерна", "Grain size");
            case SHARPEN: return Lang.t("Резкость", "Sharpening");
            case SHARPEN_RADIUS: return Lang.t("Радиус", "Radius");
            case LUMA_NR: return Lang.t("Шумоподавление яркости", "Luminance noise reduction");
            case COLOR_NR: return Lang.t("Шумоподавление цвета", "Colour noise reduction");
            case CURVE_SHADOWS: return Lang.t("Тени", "Shadows");
            case CURVE_DARKS: return Lang.t("Тёмные", "Darks");
            case CURVE_LIGHTS: return Lang.t("Светлые", "Lights");
            case CURVE_HIGHLIGHTS: return Lang.t("Блики", "Highlights");
            case CURVE_SPLIT_1: return Lang.t("Граница теней", "Shadows split");
            case CURVE_SPLIT_2: return Lang.t("Граница середины", "Midtones split");
            case CURVE_SPLIT_3: return Lang.t("Граница светов", "Highlights split");
            case GRADE_BLEND: return Lang.t("Смешивание", "Blending");
            case GRADE_BALANCE: return Lang.t("Баланс", "Balance");
            default: return p.key;
        }
    }

    static String bandName(int b) {
        switch (b) {
            case 0: return Lang.t("Красный", "Red");
            case 1: return Lang.t("Оранжевый", "Orange");
            case 2: return Lang.t("Жёлтый", "Yellow");
            case 3: return Lang.t("Зелёный", "Green");
            case 4: return Lang.t("Голубой", "Aqua");
            case 5: return Lang.t("Синий", "Blue");
            case 6: return Lang.t("Фиолетовый", "Purple");
            default: return Lang.t("Пурпурный", "Magenta");
        }
    }

    static final int[] BAND_COLOURS = {0xFFE5484D, 0xFFF08A24, 0xFFE8C531, 0xFF3FB950, 0xFF2EC4C4, 0xFF3B7BF6, 0xFF8E5BE8, 0xFFD94BC4};
    static final int[] HUE_GRADIENT = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};

    EditSlider slider(Param p, String label) {
        EditSlider s = new EditSlider(c).setup(label, p.min, p.max, p.def, p.step(), p.bipolar());
        s.set(host.state().get(p), false);
        s.listener(new EditSlider.Listener() {
            @Override
            public void changed(float value) {
                host.state().set(p, value);
                host.changed(false);
            }

            @Override
            public void committed() {
                host.state().set(p, s.value());
                host.changed(true);
            }
        });
        if (p == Param.EXPOSURE) s.format(v -> (v > 0 ? "+" : "") + GalleryUi.decimal(v, 2) + " EV");
        if (p == Param.TEMPERATURE || p == Param.TINT) s.format(v -> wbText(p, v));
        if (p == Param.GRADE_SH_HUE || p == Param.GRADE_MID_HUE || p == Param.GRADE_HI_HUE || p == Param.GRADE_ALL_HUE) {
            s.gradient(HUE_GRADIENT);
            s.format(v -> Math.round(v) + "°");
        }
        refreshers.add(() -> s.set(host.state().get(p), false));
        return s;
    }

    /** A RAW's white balance in K / tint from «Как снято»; a photo's relative numbers. */
    private String wbText(Param p, float v) {
        EditSource src = host.source();
        if (src != null && src.colour instanceof EditSource.RawColour) {
            double[] w = ((EditSource.RawColour) src.colour).white(host.state());
            if (w != null) {
                String asShot = host.state().get(Param.TEMPERATURE) == 0f && host.state().get(Param.TINT) == 0f
                        ? Lang.t("Как снято · ", "As shot · ") : "";
                if (p == Param.TEMPERATURE) return asShot + Math.round(w[0] / 50.0) * 50 + " K";
                return (v > 0 ? "+" : "") + Math.round(v);
            }
        }
        int i = Math.round(v);
        return i > 0 ? "+" + i : String.valueOf(i);
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    TextView chip(String text, boolean on, View.OnClickListener click) {
        TextView t = GalleryUi.text(c, text, 13.5f, on ? GalleryUi.INK : GalleryUi.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(GalleryUi.dp(c, 36));
        t.setPadding(GalleryUi.dp(c, 14), 0, GalleryUi.dp(c, 14), 0);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setBackground(GalleryUi.pressable(GalleryUi.round(c, on ? GalleryUi.accent(c) : GalleryUi.CARD, on ? GalleryUi.accent(c) : GalleryUi.LINE, 999)));
        t.setSelected(on);
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(click);
        return t;
    }

    /** A horizontally scrolling row of chips. */
    HorizontalScrollView chipRow(List<TextView> chips) {
        HorizontalScrollView sv = new HorizontalScrollView(c);
        sv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(GalleryUi.dp(c, 14), GalleryUi.dp(c, 6), GalleryUi.dp(c, 14), GalleryUi.dp(c, 6));
        for (TextView t : chips) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = GalleryUi.dp(c, 8);
            row.addView(t, lp);
        }
        sv.addView(row);
        return sv;
    }

    TextView section(String text) {
        TextView t = GalleryUi.sectionLabel(c, text);
        t.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 12), GalleryUi.dp(c, 18), GalleryUi.dp(c, 2));
        return t;
    }

    // ---------------------------------------------------------------- tabs

    View light() {
        LinearLayout l = column(c);
        for (Param p : new Param[]{Param.EXPOSURE, Param.CONTRAST, Param.HIGHLIGHTS, Param.SHADOWS, Param.WHITES, Param.BLACKS})
            l.addView(slider(p, label(p)));
        l.addView(section(Lang.t("Кривая", "Curve")));
        LinearLayout curveBox = column(c);
        l.addView(curveBox);
        buildCurve(curveBox);
        for (Param p : new Param[]{Param.CURVE_HIGHLIGHTS, Param.CURVE_LIGHTS, Param.CURVE_DARKS, Param.CURVE_SHADOWS,
                Param.CURVE_SPLIT_1, Param.CURVE_SPLIT_2, Param.CURVE_SPLIT_3})
            l.addView(slider(p, label(p)));
        return l;
    }

    private void buildCurve(LinearLayout box) {
        box.removeAllViews();
        String[] names = {"RGB", Lang.t("Красный", "Red"), Lang.t("Зелёный", "Green"), Lang.t("Синий", "Blue")};
        int[] colours = {0xFFFFFFFF, 0xFFFF5A5F, 0xFF4CD964, 0xFF4A8CFF};
        List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int ch = i;
            chips.add(chip(names[i], i == curveChannel, v -> {
                curveChannel = ch;
                buildCurve(box);
            }));
        }
        box.addView(chipRow(chips));
        CurveView cv = new CurveView(c);
        cv.set(host.state().curves[curveChannel], colours[curveChannel]);
        cv.setListener((pts, done) -> {
            host.state().curves[curveChannel] = pts;
            host.changed(done);
        });
        refreshers.add(() -> cv.set(host.state().curves[curveChannel], colours[curveChannel]));
        TextView hint = GalleryUi.text(c, Lang.t("Касание — новая точка, двойное касание по точке — убрать её", "Tap adds a point, double tap on a point removes it"),
                12, GalleryUi.MUTED);
        hint.setPadding(GalleryUi.dp(c, 18), 0, GalleryUi.dp(c, 18), GalleryUi.dp(c, 4));
        box.addView(cv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(hint);
    }

    int colourSection = 0;

    View colour() {
        LinearLayout l = column(c);
        LinearLayout body = column(c);
        String[] names = {Lang.t("Основное", "Basic"), Lang.t("Смешение", "Mixer"), Lang.t("Цветокоррекция", "Grading"), Lang.t("Ч/Б", "B&W")};
        LinearLayout tabs = column(c);
        l.addView(tabs);
        l.addView(body);
        buildColourSections(tabs, body, names);
        return l;
    }

    private void buildColourSections(LinearLayout tabs, LinearLayout body, String[] names) {
        tabs.removeAllViews();
        List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            chips.add(chip(names[i], i == colourSection, v -> {
                colourSection = k;
                buildColourSections(tabs, body, names);
            }));
        }
        tabs.addView(chipRow(chips));
        body.removeAllViews();
        switch (colourSection) {
            case 1: mixer(body); break;
            case 2: grading(body); break;
            case 3: mono(body); break;
            default:
                for (Param p : new Param[]{Param.TEMPERATURE, Param.TINT, Param.VIBRANCE, Param.SATURATION}) {
                    EditSlider s = slider(p, label(p));
                    if (p == Param.TEMPERATURE) s.gradient(new int[]{0xFF4A8CFF, 0xFFDDDDDD, 0xFFFFB13B});
                    if (p == Param.TINT) s.gradient(new int[]{0xFF4CD964, 0xFFDDDDDD, 0xFFE24BD8});
                    body.addView(s);
                }
        }
    }

    private void bandDots(LinearLayout body, int selected, java.util.function.IntConsumer pick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 8), GalleryUi.dp(c, 12), GalleryUi.dp(c, 4));
        for (int b = 0; b < 8; b++) {
            final int band = b;
            View dot = new View(c);
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            d.setColor(BAND_COLOURS[b]);
            if (b == selected) d.setStroke(GalleryUi.dp(c, 3), Color.WHITE);
            dot.setBackground(d);
            dot.setContentDescription(bandName(b));
            dot.setClickable(true);
            dot.setFocusable(true);
            dot.setOnClickListener(v -> pick.accept(band));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 30), GalleryUi.dp(c, 30));
            lp.leftMargin = lp.rightMargin = GalleryUi.dp(c, 5);
            row.addView(dot, lp);
        }
        body.addView(row);
    }

    private void mixer(LinearLayout body) {
        bandDots(body, hslBand, b -> {
            hslBand = b;
            body.removeAllViews();
            mixer(body);
        });
        String n = bandName(hslBand);
        body.addView(slider(Param.hue(hslBand), Lang.t("Тон", "Hue") + " · " + n));
        body.addView(slider(Param.sat(hslBand), Lang.t("Насыщенность", "Saturation") + " · " + n));
        body.addView(slider(Param.lum(hslBand), Lang.t("Яркость", "Luminance") + " · " + n));
    }

    private void grading(LinearLayout body) {
        String[] names = {Lang.t("Тени", "Shadows"), Lang.t("Средние", "Midtones"), Lang.t("Света", "Highlights"), Lang.t("Общий", "Global")};
        List<TextView> chips = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            final int k = w;
            chips.add(chip(names[w], w == gradeWheel, v -> {
                gradeWheel = k;
                body.removeAllViews();
                grading(body);
            }));
        }
        body.addView(chipRow(chips));
        body.addView(slider(Param.grade(gradeWheel, 0), Lang.t("Тон", "Hue")));
        body.addView(slider(Param.grade(gradeWheel, 1), Lang.t("Насыщенность", "Saturation")));
        body.addView(slider(Param.grade(gradeWheel, 2), Lang.t("Яркость", "Luminance")));
        body.addView(slider(Param.GRADE_BLEND, label(Param.GRADE_BLEND)));
        body.addView(slider(Param.GRADE_BALANCE, label(Param.GRADE_BALANCE)));
    }

    private void mono(LinearLayout body) {
        boolean on = host.state().mono();
        List<TextView> chips = new ArrayList<>();
        chips.add(chip(Lang.t("Цвет", "Colour"), !on, v -> {
            host.state().set(Param.MONO, 0f);
            host.changed(true);
            body.removeAllViews();
            mono(body);
        }));
        chips.add(chip(Lang.t("Ч/Б", "B&W"), on, v -> {
            host.state().set(Param.MONO, 1f);
            host.changed(true);
            body.removeAllViews();
            mono(body);
        }));
        body.addView(chipRow(chips));
        if (!on) return;
        for (int b = 0; b < 8; b++) {
            EditSlider s = slider(Param.bw(b), bandName(b));
            s.gradient(new int[]{0xFF202020, BAND_COLOURS[b], 0xFFF0F0F0});
            body.addView(s);
        }
    }

    View effects() {
        LinearLayout l = column(c);
        for (Param p : new Param[]{Param.TEXTURE, Param.CLARITY, Param.DEHAZE, Param.VIGNETTE, Param.VIGNETTE_MID, Param.GRAIN, Param.GRAIN_SIZE})
            l.addView(slider(p, label(p)));
        return l;
    }

    View detail() {
        LinearLayout l = column(c);
        for (Param p : new Param[]{Param.SHARPEN, Param.SHARPEN_RADIUS, Param.LUMA_NR, Param.COLOR_NR}) l.addView(slider(p, label(p)));
        TextView t = GalleryUi.text(c, Lang.t("Резкость и шумоподавление видны в полном размере; на экране — уменьшенный снимок",
                "Sharpening and noise reduction show at full size; the screen shows the photo scaled down"), 12, GalleryUi.MUTED);
        t.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 4), GalleryUi.dp(c, 18), GalleryUi.dp(c, 8));
        l.addView(t);
        return l;
    }

    View crop() {
        LinearLayout l = column(c);
        LinearLayout chipsBox = column(c);
        l.addView(chipsBox);
        buildAspect(chipsBox);
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(GalleryUi.dp(c, 8), 0, GalleryUi.dp(c, 8), 0);
        View rot = GalleryUi.actionButton(c, com.particlesdevs.photoncamera.R.drawable.ui_ic_rotate, Lang.t("Повернуть", "Rotate"), false, v -> host.rotate());
        View flip = GalleryUi.actionButton(c, com.particlesdevs.photoncamera.R.drawable.ui_ic_mirror, Lang.t("Отразить", "Flip"), false, v -> host.flip());
        View reset = GalleryUi.actionButton(c, com.particlesdevs.photoncamera.R.drawable.ui_ic_backup, Lang.t("Сбросить кадр", "Reset crop"), false, v -> host.resetCrop());
        for (View b : new View[]{rot, flip, reset}) buttons.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        l.addView(buttons);
        EditSlider s = new EditSlider(c).setup(Lang.t("Выпрямить", "Straighten"), -EditState.MAX_STRAIGHTEN, EditState.MAX_STRAIGHTEN, 0f, 0.1f, true);
        s.format(v -> (v > 0 ? "+" : "") + GalleryUi.decimal(v, 1) + "°");
        s.set(host.state().straighten, false);
        s.listener(new EditSlider.Listener() {
            @Override
            public void changed(float value) {
                host.straighten(value, false);
            }

            @Override
            public void committed() {
                host.straighten(s.value(), true);
            }
        });
        refreshers.add(() -> s.set(host.state().straighten, false));
        l.addView(s);
        refreshers.add(() -> buildAspect(chipsBox));
        return l;
    }

    private void buildAspect(LinearLayout box) {
        box.removeAllViews();
        List<TextView> chips = new ArrayList<>();
        int on = CropMath.presetIndex(host.state().aspect);
        for (int i = 0; i < CropMath.PRESETS.length; i++) {
            final float ratio = CropMath.PRESETS[i];
            String name = i == 0 ? Lang.t("Свободный", "Free") : i == 1 ? Lang.t("Оригинал", "Original") : CropMath.PRESET_RATIOS[i];
            chips.add(chip(name, i == on, v -> {
                host.aspect(ratio);
                buildAspect(box);
            }));
        }
        box.addView(chipRow(chips));
    }

    View styles() {
        LinearLayout l = column(c);
        LinearLayout chipsBox = column(c);
        l.addView(chipsBox);
        buildStyles(chipsBox);
        EditSlider s = new EditSlider(c).setup(Lang.t("Сила", "Amount"), 0, 100, 100, 1, false);
        s.format(v -> Math.round(v) + " %");
        s.set(styleAmount * 100f, false);
        s.listener(new EditSlider.Listener() {
            @Override
            public void changed(float value) {
                styleAmount = value / 100f;
                if (styleIndex >= 0) host.style(styleIndex, styleAmount, false);
            }

            @Override
            public void committed() {
                if (styleIndex >= 0) host.style(styleIndex, styleAmount, true);
            }
        });
        l.addView(s);
        TextView t = GalleryUi.text(c, Lang.t("Стиль ложится поверх ваших настроек; кадр не меняется",
                "A style goes on top of your settings; the crop stays"), 12, GalleryUi.MUTED);
        t.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 4), GalleryUi.dp(c, 18), GalleryUi.dp(c, 8));
        l.addView(t);
        return l;
    }

    private void buildStyles(LinearLayout box) {
        box.removeAllViews();
        List<TextView> chips = new ArrayList<>();
        chips.add(chip(Lang.t("Нет", "None"), styleIndex < 0, v -> {
            host.style(-1, 0f, true);
            styleIndex = -1;
            buildStyles(box);
        }));
        for (int i = 0; i < EditPresets.STYLES.length; i++) {
            final int k = i;
            chips.add(chip(EditPresets.name(i), i == styleIndex, v -> {
                styleIndex = k;
                host.style(k, styleAmount, true);
                buildStyles(box);
            }));
        }
        box.addView(chipRow(chips));
    }

    static String percent(float v) {
        return String.format(Locale.ROOT, "%d %%", Math.round(v * 100));
    }

    @Nullable
    View forTab(int tab) {
        refreshers.clear();
        switch (tab) {
            case 0: return crop();
            case 1: return styles();
            case 2: return light();
            case 3: return colour();
            case 4: return effects();
            case 5: return detail();
            default: return null;
        }
    }
}
