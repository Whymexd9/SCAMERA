package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.particlesdevs.photoncamera.processing.color.ToneCurve;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.WhyTone;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * P177 (owner, 2026-10-16: «для кастомных кривых добавь возможность визуализации кривых AGX/ACES и тд для сравнения чтобы можно
 * было на графике по ним ориентироваться. Ну и дай возможность их подгружать чтобы потом редактировать»): the other tone
 * operators next to the own curve - chips «AgX» / «ACES» / «Uchimura» that draw each one (with today's settings) dashed on the
 * graph, remembered for every editor of the curve (the settings page and the shade), and the templates (S-curve, straight,
 * AgX, ACES, Uchimura) to load one as an own curve of nine points and edit it.
 */
public final class ToneCurveCompare {
    /** Which operators are drawn (a per-viewer convenience, not a setting: its own preferences file). */
    static final String PREFS = "scam_ui", KEY = "tone_curve_compare";
    static final int[] OPS = {ToneCurve.AGX, ToneCurve.ACES, ToneCurve.UCHIMURA};
    static final String[] NAMES = {"AgX", "ACES", "Uchimura"};
    static final int[] COLOURS = {0xFF8FD3FF, 0xFFFFB86B, 0xFFC3A6FF};
    /** Points of a loaded operator: enough to follow it, few enough to edit. */
    static final int TEMPLATE_POINTS = 9;
    private static final int SAMPLES = 96;

    private ToneCurveCompare() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean shown(Context c, int i) {
        return prefs(c).getString(KEY, "").contains(NAMES[i]);
    }

    static void setShown(Context c, int i, boolean on) {
        List<String> names = new ArrayList<>();
        for (int k = 0; k < NAMES.length; k++) if (k == i ? on : shown(c, k)) names.add(NAMES[k]);
        prefs(c).edit().putString(KEY, String.join(",", names)).apply();
    }

    /** The operator's curve as samples of the own curve's domain. */
    static float[] samples(int op) {
        java.util.function.DoubleUnaryOperator f = WhyTone.reference(op);
        float[] s = new float[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) s[i] = (float) f.applyAsDouble(i / (double) (SAMPLES - 1));
        return s;
    }

    /** Draws the remembered operators on {@code graph}. */
    public static void apply(Context c, ToneCurveView graph) {
        List<float[]> samples = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        for (int i = 0; i < OPS.length; i++) {
            if (!shown(c, i)) continue;
            try {
                samples.add(samples(OPS[i]));
            } catch (RuntimeException e) {
                continue;   // settings not ready: nothing to compare with
            }
            names.add(NAMES[i]);
            colours.add(COLOURS[i]);
        }
        int[] cols = new int[colours.size()];
        for (int i = 0; i < cols.length; i++) cols[i] = colours.get(i);
        graph.setReferences(samples.toArray(new float[0][]), names.toArray(new String[0]), cols);
    }

    /** «Сравнить:» and a chip per operator: a tap draws it on the graph or takes it off. */
    public static LinearLayout chips(Context c, ToneCurveView graph) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag("tone_curve_compare");
        TextView title = SettingsStyle.text(c, Lang.t(c, "Сравнить:", "Compare:"), 13, SettingsStyle.MUTED);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMarginEnd(SettingsStyle.dp(c, 6));
        row.addView(title, tp);
        for (int i = 0; i < OPS.length; i++) {
            final int k = i;
            TextView chip = SettingsStyle.text(c, NAMES[i], 13, COLOURS[i]);
            chip.setGravity(Gravity.CENTER);
            chip.setTag("tone_curve_compare_" + NAMES[i]);
            paint(c, chip, k);
            chip.setOnClickListener(v -> {
                setShown(c, k, !shown(c, k));
                paint(c, chip, k);
                apply(c, graph);
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, SettingsStyle.dp(c, 34), 1f);
            cp.setMarginStart(SettingsStyle.dp(c, 4));
            row.addView(chip, cp);
        }
        apply(c, graph);
        return row;
    }

    private static void paint(Context c, TextView chip, int i) {
        boolean on = shown(c, i);
        chip.setSelected(on);
        chip.setBackground(SettingsStyle.shape(c, on ? (COLOURS[i] & 0x00FFFFFF) | 0x33000000 : SettingsStyle.FIELD,
                on ? COLOURS[i] : SettingsStyle.LINE, 100));
    }

    /** Names of the templates: the S-curve, the straight line and the operators. */
    public static String[] templateNames(Context c) {
        return new String[]{Lang.t(c, "S-кривая", "S-curve"), Lang.t(c, "Прямая", "Straight"),
                "AgX", "ACES", Lang.t(c, "Uchimura (Gran Turismo)", "Uchimura (Gran Turismo)")};
    }

    /** The i-th template as an own curve to edit. */
    public static ToneCurve template(int i) {
        if (i == 0) return ToneCurve.defaults();
        if (i == 1) return ToneCurve.identity();
        return ToneCurve.following(WhyTone.reference(OPS[i - 2]), TEMPLATE_POINTS);
    }

    /** A sheet of the templates; the picked one is handed over (to be shown and stored). */
    public static void pickTemplate(Context c, int accent, Consumer<ToneCurve> onPick) {
        String[] names = templateNames(c);
        CharSequence[] tags = new CharSequence[names.length];
        for (int i = 0; i < tags.length; i++) tags[i] = "template_" + i;
        SettingsStyle.optionSheet(c, Lang.t(c, "Загрузить шаблон", "Load a template"), names, tags, -1, accent,
                i -> onPick.accept(template(i)));
    }
}
