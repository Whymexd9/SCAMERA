package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.color.ToneCurve;
import com.particlesdevs.photoncamera.ui.controls.PrecisionEditor;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.Locale;

/**
 * P144: the own tone curve of «Кривая AgX и ACES» - its points ("x:y,..." in pref_scam_hybrid_ark_curve_points) edited on a
 * graph with a finger (ToneCurveView), plus «S-кривая» (ArkCam's default) and «Прямая» (the straight line). Under them the
 * selected point: ‹ › to step through the points, «Удалить», and two sliders - «Экспозиция» (x, -5..+5 EV around middle grey)
 * and «Яркость» (y, 0..100 %) - whose values tap to type them (PrecisionEditor). Stored when a gesture ends; the next shot
 * reads it (ArkTone.curveSamples).
 */
public class ToneCurvePreference extends Preference {
    private static final int STEPS = 1000;
    /** The selected point, kept over rebinds (-1: the middle one on the next bind). */
    private int selected = -1;

    public ToneCurvePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_tone_curve);
        setPersistent(true);
        setSelectable(false);
    }

    public ToneCurvePreference(Context context) {
        this(context, null);
    }

    @Override
    protected Object onGetDefaultValue(android.content.res.TypedArray a, int index) {
        String v = a.getString(index);
        return v != null ? v : ToneCurve.DEFAULT_POINTS;
    }

    /** The stored points (the default S when none). */
    public String points() {
        return getPersistedString(ToneCurve.DEFAULT_POINTS);
    }

    void store(ToneCurve curve) {
        String text = curve.serialize();
        if (text.equals(points()) || !callChangeListener(text)) return;
        // no notifyChanged: the row on screen already shows it, and a rebind would drop the finger's point
        persistString(text);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        Context c = getContext();
        FrameLayout host = (FrameLayout) holder.findViewById(R.id.tone_curve_host);
        ToneCurveView view = host.getChildCount() > 0 && host.getChildAt(0) instanceof ToneCurveView
                ? (ToneCurveView) host.getChildAt(0) : null;
        if (view == null) {
            view = new ToneCurveView(c);
            view.setTag("tone_curve_view");
            host.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        final ToneCurveView graph = view;
        graph.setOnChange(null);
        graph.setCurve(ToneCurve.parse(points()));
        graph.setOnCommit(this::store);
        graph.setEnabled(isEnabled());
        LinearLayout actions = (LinearLayout) holder.findViewById(R.id.tone_curve_actions);
        actions.removeAllViews();
        actions.addView(chip(c, Lang.t(c, "S-кривая", "S-curve"), "tone_curve_default", () -> {
            graph.setCurve(ToneCurve.defaults());
            store(graph.curve());
        }), weight());
        LinearLayout.LayoutParams second = weight();
        second.setMarginStart(SettingsStyle.dp(c, 8));
        actions.addView(chip(c, Lang.t(c, "Прямая", "Straight"), "tone_curve_identity", () -> {
            graph.setCurve(ToneCurve.identity());
            store(graph.curve());
        }), second);
        // the row dims as a whole when the curve is off (SettingsStyle.bind); the graph and its controls only stop reacting
        for (int i = 0; i < actions.getChildCount(); i++) actions.getChildAt(i).setEnabled(isEnabled());
        PointPanel panel = new PointPanel(c, graph, (LinearLayout) holder.findViewById(R.id.tone_curve_point), isEnabled());
        graph.setOnChange(() -> {
            selected = graph.selected();
            panel.refresh();
        });
        graph.select(selected >= 0 && selected < graph.curve().size() ? selected : graph.curve().size() / 2);
    }

    /** -5..+5 EV around middle grey on the graph's x. */
    static float ev(float x) {
        return (x - 0.5f) * 10f;
    }

    static String evText(float x) {
        float v = Math.round(ev(x) * 100f) / 100f;
        return (v > 0 ? "+" : v < 0 ? "−" : "") + String.format(Locale.ROOT, "%.2f EV", Math.abs(v));
    }

    static String percentText(float y) {
        return String.format(Locale.ROOT, "%.1f %%", y * 100f);
    }

    /** The selected point's controls. */
    private final class PointPanel {
        private final Context c;
        private final ToneCurveView graph;
        private final boolean on;
        private final TextView label, prev, next, delete, xValue, yValue;
        private final SeekBar xBar, yBar;

        PointPanel(Context c, ToneCurveView graph, LinearLayout panel, boolean on) {
            this.c = c;
            this.graph = graph;
            this.on = on;
            int accent = SettingsStyle.accent(c);
            panel.removeAllViews();
            panel.setBackground(SettingsStyle.shape(c, SettingsStyle.FIELD, 0, 14));
            panel.setClipChildren(false);
            panel.setClipToPadding(false);
            LinearLayout head = new LinearLayout(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            prev = arrow(c, "‹", "tone_curve_prev", Lang.t(c, "Предыдущая точка", "Previous point"));
            next = arrow(c, "›", "tone_curve_next", Lang.t(c, "Следующая точка", "Next point"));
            label = SettingsStyle.text(c, "", 14, SettingsStyle.TEXT);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setGravity(Gravity.CENTER);
            label.setTag("tone_curve_point_label");
            delete = SettingsStyle.text(c, Lang.t(c, "Удалить", "Delete"), 13.5f, SettingsStyle.WARN);
            delete.setTypeface(null, Typeface.BOLD);
            delete.setGravity(Gravity.CENTER);
            delete.setPadding(SettingsStyle.dp(c, 12), 0, SettingsStyle.dp(c, 12), 0);
            delete.setBackground(SettingsStyle.shape(c, 0x26FFB4A8, 0, 12));
            delete.setTag("tone_curve_delete");
            int h = SettingsStyle.dp(c, 34);
            head.addView(prev, new LinearLayout.LayoutParams(h, h));
            head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(next, new LinearLayout.LayoutParams(h, h));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h);
            dp.setMarginStart(SettingsStyle.dp(c, 10));
            head.addView(delete, dp);
            panel.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            xValue = pill(c, accent, "tone_curve_x_value");
            yValue = pill(c, accent, "tone_curve_y_value");
            xBar = bar(c, accent, "tone_curve_x");
            yBar = bar(c, accent, "tone_curve_y");
            addSlider(panel, Lang.t(c, "Экспозиция", "Exposure"), xValue, xBar);
            addSlider(panel, Lang.t(c, "Яркость", "Brightness"), yValue, yBar);
            prev.setOnClickListener(v -> graph.select(graph.selected() - 1));
            next.setOnClickListener(v -> graph.select(graph.selected() + 1));
            delete.setOnClickListener(v -> graph.deleteSelected());
            xBar.setOnSeekBarChangeListener(slide(true));
            yBar.setOnSeekBarChangeListener(slide(false));
            xValue.setOnClickListener(v -> typeX());
            yValue.setOnClickListener(v -> typeY());
        }

        void refresh() {
            ToneCurve curve = graph.curve();
            int i = graph.selected(), n = curve.size();
            boolean has = i >= 0 && i < n, end = has && (i == 0 || i == n - 1);
            if (has) {
                ToneCurve.Point p = curve.get(i);
                label.setText(Lang.t(c, "Точка " + (i + 1) + " из " + n, "Point " + (i + 1) + " of " + n));
                xValue.setText(evText(p.x));
                yValue.setText(percentText(p.y));
                xBar.setProgress(Math.round(p.x * STEPS));
                yBar.setProgress(Math.round(p.y * STEPS));
            } else {
                label.setText(Lang.t(c, "Нажмите на точку", "Tap a point"));
                xValue.setText("—");
                yValue.setText("—");
            }
            enable(prev, has && i > 0);
            enable(next, has && i < n - 1);
            enable(delete, has && !end);
            // the ends keep their exposure (-5 and +5 EV); only their brightness moves
            enable(xBar, has && !end);
            enable(xValue, has && !end);
            enable(yBar, has);
            enable(yValue, has);
        }

        private void enable(View v, boolean e) {
            v.setEnabled(on && e);
            v.setAlpha(on && e ? 1f : 0.4f);
        }

        private SeekBar.OnSeekBarChangeListener slide(boolean x) {
            return new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    int i = graph.selected();
                    if (!fromUser || i < 0) return;
                    ToneCurve.Point p = graph.curve().get(i);
                    float v = progress / (float) STEPS;
                    graph.moveSelected(x ? v : p.x, x ? p.y : v);
                    refresh(); // a point stops at its neighbours: the bar shows where it stayed
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    store(graph.curve());
                }
            };
        }

        /** The exposure typed in: within the neighbours' range (the ends keep theirs). */
        private void typeX() {
            ToneCurve curve = graph.curve();
            int i = graph.selected();
            if (i <= 0 || i >= curve.size() - 1) return;
            float lo = curve.get(i - 1).x + ToneCurve.MIN_GAP, hi = curve.get(i + 1).x - ToneCurve.MIN_GAP, now = curve.get(i).x;
            PrecisionEditor.show(c, Lang.t(c, "Экспозиция точки " + (i + 1) + ", EV", "Point " + (i + 1) + " exposure, EV"),
                    Math.ceil(ev(lo) * 100.0 - 1e-3) / 100.0, Math.floor(ev(hi) * 100.0 + 1e-3) / 100.0, round2(ev(now)), round2(ev(now)), true, v -> {
                        graph.moveSelected(0.5f + v / 10f, graph.curve().get(graph.selected()).y);
                        store(graph.curve());
                        refresh();
                    });
        }

        /** The brightness typed in, 0..100 %; «По умолчанию» puts the point on the straight line. */
        private void typeY() {
            ToneCurve curve = graph.curve();
            int i = graph.selected();
            if (i < 0 || i >= curve.size()) return;
            ToneCurve.Point p = curve.get(i);
            PrecisionEditor.show(c, Lang.t(c, "Яркость точки " + (i + 1) + ", %", "Point " + (i + 1) + " brightness, %"),
                    0, 100, round2(p.y * 100f), round2(p.x * 100f), true, v -> {
                        graph.moveSelected(graph.curve().get(graph.selected()).x, v / 100f);
                        store(graph.curve());
                        refresh();
                    });
        }

        private void addSlider(LinearLayout panel, CharSequence title, TextView value, SeekBar bar) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = SettingsStyle.text(c, title, 14.5f, SettingsStyle.TEXT);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = SettingsStyle.dp(c, 12);
            panel.addView(row, rp);
            // as the slider rows of the settings (preference_seekbar): the track lines up with the text
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SettingsStyle.dp(c, 36));
            bp.setMarginStart(-SettingsStyle.dp(c, 14));
            bp.setMarginEnd(-SettingsStyle.dp(c, 10));
            panel.addView(bar, bp);
        }
    }

    private static double round2(float v) {
        return Math.round(v * 100f) / 100.0;
    }

    private static TextView pill(Context c, int accent, String tag) {
        TextView t = SettingsStyle.text(c, "", 13.5f, accent);
        t.setBackgroundResource(R.drawable.settings_pill);
        t.setBackgroundTintList(ColorStateList.valueOf(SettingsStyle.tint(accent, .18f)));
        t.setFontFeatureSettings("tnum");
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(SettingsStyle.dp(c, 44));
        t.setPadding(SettingsStyle.dp(c, 11), SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 11), SettingsStyle.dp(c, 4));
        t.setTag(tag);
        t.setContentDescription(Lang.t(c, "Значение — нажмите для ввода", "Value, tap to type it"));
        return t;
    }

    private static SeekBar bar(Context c, int accent, String tag) {
        SeekBar s = new SeekBar(c);
        s.setMax(STEPS);
        s.setProgressTintList(ColorStateList.valueOf(accent));
        s.setThumbTintList(ColorStateList.valueOf(accent));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF4A5058));
        s.setTag(tag);
        return s;
    }

    private static TextView arrow(Context c, String glyph, String tag, CharSequence description) {
        int accent = SettingsStyle.accent(c);
        TextView t = SettingsStyle.text(c, glyph, 22, accent);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(SettingsStyle.shape(c, SettingsStyle.tint(accent, .12f), 0, 12));
        t.setTag(tag);
        t.setContentDescription(description);
        return t;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static TextView chip(Context c, CharSequence label, String tag, Runnable click) {
        int accent = SettingsStyle.accent(c);
        TextView t = SettingsStyle.text(c, label, 14, accent);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, SettingsStyle.dp(c, 10), 0, SettingsStyle.dp(c, 10));
        t.setBackground(SettingsStyle.shape(c, SettingsStyle.tint(accent, .12f), 0, 12));
        t.setTag(tag);
        t.setOnClickListener(v -> click.run());
        return t;
    }
}
