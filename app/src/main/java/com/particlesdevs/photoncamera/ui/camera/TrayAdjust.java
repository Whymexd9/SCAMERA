package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.circularbarlib.ui.UiScale;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The LUT tray's adjustments of the chosen filter / film (owner, 2026-10-28: «дай возможность настраивать луты, фильтры и
 * пленки прямо в этом подменю. Делай так чтобы оно не получалось слишком массивным»): a row of small parameter chips and
 * one slider for the chosen parameter, as a photo editor's adjust strip - two short lines however many parameters there are.
 * Every parameter is the setting the settings page writes (the same key, range, step and default); a double tap on the
 * slider puts the default back.
 */
final class TrayAdjust {
    /** One parameter: its setting read and written as the settings page does, its range, step, default, unit and description. */
    static final class Param {
        final String id, title, unit;
        /** What it does (ParamHelp), shown on a long press; null: none. */
        @Nullable final String help;
        final float min, max, step, def;
        /** Written while the slider moves (the viewfinder follows at once); else when it is let go. */
        final boolean live;
        final java.util.function.Supplier<Float> read;
        final java.util.function.Consumer<Float> write;

        Param(String id, String title, @Nullable String help, float min, float max, float step, float def, String unit, boolean live,
              java.util.function.Supplier<Float> read, java.util.function.Consumer<Float> write) {
            this.id = id;
            this.title = title;
            this.help = help;
            this.min = min;
            this.max = max;
            this.step = step;
            this.def = def;
            this.unit = unit;
            this.live = live;
            this.read = read;
            this.write = write;
        }

        int steps() {
            return Math.max(1, Math.round((max - min) / step));
        }

        int progress(float v) {
            return Math.round((Math.max(min, Math.min(max, v)) - min) / step);
        }

        float value(int progress) {
            return min + progress * step;
        }

        /** The value as the settings page shows it: «+0.4 EV», «120 %», «-15». */
        String text(int progress) {
            final float x = value(progress);
            if (step < 1f) return String.format(Locale.ROOT, step < .1f ? "%+.2f" : "%+.1f", x) + unit;
            final int i = Math.round(x);
            return (min < 0 && i > 0 ? "+" : "") + i + unit;
        }
    }

    final LinearLayout view;
    private final TrayMotion.ChipRow chips;
    private final SeekBar bar;
    private final TextView value;
    private final String tag;
    private final List<Param> params = new ArrayList<>();
    @Nullable private Param now;
    @Nullable private Runnable onChange;
    /** A long press on a parameter's chip or on the slider: its title and description. */
    @Nullable private java.util.function.BiConsumer<String, String> onHelp;
    /** The parameter chosen last (kept when the list is built again for another stock or filter). */
    @Nullable private String chosenId;

    TrayAdjust(Context c, String tag) {
        this.tag = tag;
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setTag(tag);
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setHorizontalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(UiScale.dp(c, 14));
        chips = new TrayMotion.ChipRow(c, 0x2EFFFFFF, UiScale.dpF(c, 12));
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setTag(tag + "_params");
        scroll.addView(chips, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = UiScale.dp(c, 6);
        view.addView(scroll, slp);
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        bar = new SeekBar(c);
        bar.setTag(tag + "_seek");
        LutTray.styleSlider(c, bar, ShadeStyle.accent(c), null);
        r.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        value = new TextView(c);
        value.setTag(tag + "_value");
        value.setTextColor(ShadeStyle.TEXT);
        value.setMaxLines(1);
        UiScale.textFixed(value, 12);
        value.setMinWidth(UiScale.dp(c, 54));
        value.setGravity(Gravity.END);
        value.setFontFeatureSettings("tnum");
        r.addView(value);
        view.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                final Param q = now;
                if (q == null) return;
                value.setText(q.text(p));
                if (!user) return;
                if (p == q.progress(q.def)) TrayMotion.tick(s); // the default is felt
                if (q.live) q.write.accept(q.value(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                s.getParent().requestDisallowInterceptTouchEvent(true);
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                final Param q = now;
                if (q == null) return;
                q.write.accept(q.value(s.getProgress()));
                if (onChange != null) onChange.run();
            }
        });
        bar.setOnLongClickListener(v -> {
            final Param q = now;
            if (q == null || q.help == null || onHelp == null) return false;
            onHelp.accept(q.title, q.help);
            return true;
        });
        com.particlesdevs.photoncamera.ui.controls.DoubleTapReset.attach(bar, () -> {
            final Param q = now;
            if (q == null) return;
            bar.setProgress(q.progress(q.def), true);
            value.setText(q.text(q.progress(q.def)));
            q.write.accept(q.def);
            if (onChange != null) onChange.run();
        });
    }

    /** Runs after a value was written (let go, or put back by a double tap): the pill follows. */
    void setOnChange(@Nullable Runnable r) {
        onChange = r;
    }

    /** Runs on a long press of a parameter (its chip or the slider) with its title and description. */
    void setOnHelp(@Nullable java.util.function.BiConsumer<String, String> r) {
        onHelp = r;
    }

    /** The parameters of the chosen filter / film; the parameter chosen before stays chosen when it is among them. */
    void setParams(List<Param> list) {
        final Context c = view.getContext();
        params.clear();
        params.addAll(list);
        chips.removeAllViews();
        for (Param p : params) {
            TextView t = new TextView(c);
            t.setText(p.title);
            t.setTag(tag + "_param_" + p.id);
            t.setMaxLines(1);
            t.setGravity(Gravity.CENTER);
            UiScale.textFixed(t, 11.5f);
            t.setTextColor(ShadeStyle.MUTED);
            t.setPadding(UiScale.dp(c, 10), 0, UiScale.dp(c, 10), 0);
            t.setOnClickListener(v -> { TrayMotion.tick(v); show(p, true); });
            t.setOnLongClickListener(v -> {
                if (p.help == null || onHelp == null) return false;
                show(p, true);
                onHelp.accept(p.title, p.help);
                return true;
            });
            TrayMotion.pressable(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiScale.dp(c, 24));
            if (chips.getChildCount() > 0) lp.setMarginStart(UiScale.dp(c, 2));
            chips.addView(t, lp);
        }
        Param first = params.isEmpty() ? null : params.get(0);
        for (Param p : params) if (p.id.equals(chosenId)) first = p;
        if (first != null) show(first, false);
    }

    /** The values again from the settings (the settings page or a double tap elsewhere may have changed them). */
    void refresh() {
        if (now != null) show(now, false);
    }

    /** {@code p} becomes the slider's parameter: its chip on the pill (gliding when {@code animate}), the slider at its value. */
    private void show(Param p, boolean animate) {
        now = p;
        chosenId = p.id;
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView t = (TextView) chips.getChildAt(i);
            boolean on = params.get(i) == p;
            t.setTextColor(on ? ShadeStyle.TEXT : ShadeStyle.MUTED);
            t.setTypeface(android.graphics.Typeface.create(on ? "sans-serif-medium" : "sans-serif", android.graphics.Typeface.NORMAL));
            if (on) chips.moveTo(t, animate);
        }
        final int pr = p.progress(p.read.get());
        bar.setMax(p.steps());
        bar.setProgress(pr, animate && view.isAttachedToWindow());
        value.setText(p.text(pr));
        bar.setContentDescription(p.title);
    }
}
