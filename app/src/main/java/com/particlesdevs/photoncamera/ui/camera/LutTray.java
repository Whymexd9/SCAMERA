package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiScale;
import com.particlesdevs.photoncamera.processing.color.FilmLooks;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.color.LensFilters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.util.ScameraFolder;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * SIMPLE_UI §1, the owner's requirement «менять LUT без меню и настроек»: the LUT pill in the viewfinder's top-left corner
 * (28dp, translucent dark, the film icon and the LUT's name - «LUT» with none; the name in the accent while a LUT is on,
 * the pill in the accent while the tray is open) and the LUT tray in the Pro panel's place above row 1 (Pro and the tray
 * close each other): a snap row of every LUT («Нет», the five film looks, every .cube of Download/SCAMERA/LUT) as cards
 * with their names (owner, 2026-10-19: no small live previews in the viewfinder); the strength slider; «Все LUT ›» to the settings' LUT row. The choice is the same state as the settings'
 * (pref_film_lut / pref_film_lut_strength): the live viewfinder (LiveRawRenderer) and the photo (WhyCombine) follow it.
 * P228 / P287: under the strength a second row of the lens filters («Нет» and the reference's four diffusion filters) by
 * name; a card is the settings' pref_lens_filter. The pill names the filter after the
 * LUT («Кино 500 · Pro-Mist»).
 */
public final class LutTray {
    /** The concept's tray: the sheet colour at 94 %. */
    static final int TRAY_FILL = 0xF015191C, PILL_FILL = 0x9E0D0F12;
    /** An unchosen chip: a faint glass fill with a faint edge. */
    static final int CHIP_FILL = 0x1AFFFFFF, CHIP_LINE = 0x14FFFFFF;
    private final TextView pill;
    private final LinearLayout tray;
    private final LinearLayout row;
    private final SeekBar strength;
    private final TextView strengthValue;
    @Nullable private final SurfaceView preview;
    private final Runnable openAll;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> values = new ArrayList<>();
    private final LinearLayout filterRow;
    /** P281: the film simulation's stocks (FilmSim), the tray's first row. */
    private final LinearLayout filmRow;
    /** The sections' current choices, after their titles. */
    private final TextView lutNow, filterNow, filmNow;
    /**
     * P319 (user report 2026-10-22: «Много лишнего, в том числе того, что должно быть только в видоискателе (фильтр на объектив,
     * тепло, и т.п.)»): «Тепло» and «Оттенок» of «Цвет» in the tray too, the same settings (pref_scam_hybrid_colour_warmth /
     * _tint, -100..100); the RAW viewfinder shows them at once, the photo always.
     */
    static final String WARMTH_KEY = "pref_scam_hybrid_colour_warmth", TINT_KEY = "pref_scam_hybrid_colour_tint";
    private final SeekBar warmth, tint;
    private final TextView warmthValue, tintValue;
    @Nullable private Runnable onOpen;
    private boolean open;

    public LutTray(TextView pill, LinearLayout tray, @Nullable SurfaceView preview, Runnable openAll) {
        this.pill = pill;
        this.tray = tray;
        this.preview = preview;
        this.openAll = openAll;
        Context c = tray.getContext();
        int accent = ShadeStyle.accent(c);
        // the pill
        pill.setOnClickListener(v -> setOpen(!open));
        pill.setCompoundDrawablePadding(UiScale.dp(c, 5));
        pill.setPaddingRelative(UiScale.dp(c, 8), 0, UiScale.dp(c, 10), 0);
        ViewGroup.LayoutParams plp = pill.getLayoutParams();
        plp.height = UiScale.dp(c, 28);
        pill.setLayoutParams(plp);
        UiScale.textFixed(pill, 12);
        // the tray (owner, 2026-10-20: «сделай красивый интерфейс»): three titled sections - LUT with its strength, the lens
        // filter, the film - each a title with the current choice and a row of chips, the chosen chip in the accent
        tray.setOrientation(LinearLayout.VERTICAL);
        tray.setPadding(UiScale.dp(c, 12), UiScale.dp(c, 10), UiScale.dp(c, 12), UiScale.dp(c, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(TRAY_FILL);
        bg.setStroke(Math.max(1, Math.round(c.getResources().getDisplayMetrics().density)), ShadeStyle.LINE);
        bg.setCornerRadius(UiScale.dpF(c, 22));
        tray.setBackground(bg);
        tray.setClickable(true);
        tray.removeAllViews();
        // LUT: the title with «Все LUT ›», the chips, the strength
        TextView all = text(c, Lang.t(c, "Все LUT ›", "All LUTs ›"), 12, accent);
        all.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        all.setPaddingRelative(UiScale.dp(c, 10), UiScale.dp(c, 4), UiScale.dp(c, 2), UiScale.dp(c, 4));
        all.setBackground(ShadeStyle.ripple(c, 10));
        all.setOnClickListener(v -> { setOpen(false); openAll.run(); });
        lutNow = section(c, "LUT", all, 0);
        row = chipRow(c, "lut_tray_luts");
        // the strength row: «Сила» · slider · value
        LinearLayout lr = new LinearLayout(c);
        lr.setOrientation(LinearLayout.HORIZONTAL);
        lr.setGravity(Gravity.CENTER_VERTICAL);
        lr.setTag("lut_tray_strength");
        TextView label = text(c, Lang.t(c, "Сила", "Strength"), 12, ShadeStyle.MUTED);
        lr.addView(label);
        strength = new SeekBar(c);
        strength.setMax(100);
        strength.setProgressTintList(ColorStateList.valueOf(accent));
        strength.setThumbTintList(ColorStateList.valueOf(accent));
        strength.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
        strength.setContentDescription(Lang.t(c, "Сила LUT", "LUT strength"));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lr.addView(strength, slp);
        strengthValue = text(c, "", 12, ShadeStyle.TEXT);
        strengthValue.setMinWidth(UiScale.dp(c, 40));
        strengthValue.setGravity(Gravity.END);
        strengthValue.setFontFeatureSettings("tnum");
        lr.addView(strengthValue);
        LinearLayout.LayoutParams lrp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lrp.topMargin = UiScale.dp(c, 2);
        tray.addView(lr, lrp);
        // P228: the lens filters
        filterNow = section(c, Lang.t(c, "Фильтр объектива", "Lens filter"), null, 6);
        filterRow = chipRow(c, "lut_tray_filters");
        // P281: the film simulation's stocks
        filmNow = section(c, Lang.t(c, "Плёнка", "Film"), null, 10);
        filmRow = chipRow(c, "lut_tray_films");
        // P319: the colour's warmth and tint
        section(c, Lang.t(c, "Цвет", "Colour"), null, 10);
        warmthValue = text(c, "", 12, ShadeStyle.TEXT);
        warmth = colourSlider(c, Lang.t(c, "Тепло", "Warmth"), WARMTH_KEY, warmthValue, "lut_tray_warmth", accent);
        tintValue = text(c, "", 12, ShadeStyle.TEXT);
        tint = colourSlider(c, Lang.t(c, "Оттенок", "Tint"), TINT_KEY, tintValue, "lut_tray_tint", accent);
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                strengthValue.setText(p + " %");
                if (user) settings().set(SettingsManager.SCOPE_GLOBAL, FilmLut.STRENGTH_KEY, String.valueOf(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { bindPill(); }
        });
        tray.setVisibility(View.GONE);
        bindPill();
    }

    /** P319: a «label · slider · value» row of a -100..100 colour setting, written as the settings page writes it. */
    private SeekBar colourSlider(Context c, String label, String key, TextView value, String tag, int accent) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setTag(tag);
        TextView l = text(c, label, 12, ShadeStyle.MUTED);
        l.setMinWidth(UiScale.dp(c, 64));
        r.addView(l);
        SeekBar bar = new SeekBar(c);
        bar.setMax(200);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
        bar.setContentDescription(label);
        r.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        value.setMinWidth(UiScale.dp(c, 40));
        value.setGravity(Gravity.END);
        value.setFontFeatureSettings("tnum");
        r.addView(value);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                value.setText(signed(p - 100));
                if (user) settings().set(SettingsManager.SCOPE_GLOBAL, key, String.valueOf(p - 100));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        tray.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }

    /** P319: a -100..100 colour setting's value now (a stored text, as UniversalSeekBarPreference keeps it). */
    static int colourValue(String key) {
        try {
            return Math.max(-100, Math.min(100, Math.round(PreferenceKeys.hybridValue(key.substring("pref_scam_hybrid_".length()), 0f))));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static SettingsManager settings() {
        return ScameraApp.getSettingsManagerStatic();
    }

    private static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setMaxLines(1);
        UiScale.textFixed(t, sp);
        return t;
    }

    /**
     * A section's title row: the title in small capitals, then the current choice in the accent (returned), {@code end}
     * at the row's end.
     */
    private TextView section(Context c, String title, @Nullable View end, int topDp) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(UiScale.dp(c, 24));
        TextView t = text(c, title.toUpperCase(java.util.Locale.getDefault()), 10.5f, ShadeStyle.MUTED);
        t.setLetterSpacing(.08f);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        r.addView(t);
        TextView now = text(c, "", 12, ShadeStyle.accent(c));
        now.setEllipsize(TextUtils.TruncateAt.END);
        now.setPaddingRelative(UiScale.dp(c, 8), 0, UiScale.dp(c, 4), 0);
        r.addView(now, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (end != null) r.addView(end);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiScale.dp(c, topDp);
        tray.addView(r, lp);
        return now;
    }

    /** A section's horizontal row of chips in its scroll, added to the tray. */
    private LinearLayout chipRow(Context c, String tag) {
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setTag(tag);
        scroll.addView(r, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiScale.dp(c, 4);
        tray.addView(scroll, lp);
        return r;
    }

    /** A chip's look: the accent fill when chosen, the glass fill and edge otherwise; a ripple bounded by its shape. */
    static android.graphics.drawable.Drawable chipBackground(Context c, boolean on, int accent, float radiusDp) {
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(UiScale.dpF(c, radiusDp));
        fill.setColor(on ? accent : CHIP_FILL);
        if (!on) fill.setStroke(Math.max(1, UiScale.dp(c, 1)), CHIP_LINE);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(UiScale.dpF(c, radiusDp));
        mask.setColor(0xFFFFFFFF);
        return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(on ? 0x33000000 : 0x29FFFFFF), fill, mask);
    }

    /** A one-line text chip (LUTs, filters), fully rounded; the chosen one in the accent with INK. */
    private static TextView textChip(Context c, String label, boolean on, int accent) {
        TextView t = text(c, label, 12.5f, on ? ShadeStyle.INK : ShadeStyle.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(UiScale.dp(c, 150));
        t.setPadding(UiScale.dp(c, 14), 0, UiScale.dp(c, 14), 0);
        if (on) t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setBackground(chipBackground(c, on, accent, 17));
        return t;
    }

    /** A chip's place in its row: {@code heightDp} high, 6dp after the previous. */
    private static LinearLayout.LayoutParams chipParams(Context c, ViewGroup row, int heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiScale.dp(c, heightDp));
        if (row.getChildCount() > 0) lp.setMarginStart(UiScale.dp(c, 6));
        return lp;
    }

    /** Scrolls a row so the chosen chip sits in its middle. */
    private static void centre(LinearLayout row, @Nullable View target) {
        if (target != null) row.post(() -> {
            View scroll = (View) row.getParent();
            if (scroll instanceof HorizontalScrollView)
                ((HorizontalScrollView) scroll).smoothScrollTo(Math.max(0, target.getLeft() + target.getWidth() / 2 - scroll.getWidth() / 2), 0);
        });
    }

    /** Pro opens: the tray closes (they are mutually exclusive). */
    public void setOnOpen(@Nullable Runnable onOpen) {
        this.onOpen = onOpen;
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean value) {
        if (value == open) return;
        open = value;
        if (open) {
            if (onOpen != null) onOpen.run();
            rebuild();
            tray.setVisibility(View.VISIBLE);
        } else {
            tray.setVisibility(View.GONE);
        }
        bindPill();
    }

    /** The current LUT's name, «LUT» with none. */
    static String name(Context c, String value) {
        if (value == null || value.isEmpty()) return Lang.t(c, "Нет", "None");
        String look = FilmLooks.label(c, value);
        if (look != null) return look;
        return value.toLowerCase(java.util.Locale.ROOT).endsWith(".cube") ? value.substring(0, value.length() - 5) : value;
    }

    /** The filter's short name for the pill («Pro-Mist», «Туман»). */
    static String filterShort(Context c, String id) {
        return LensFilters.BLACK_PRO_MIST.equals(id) ? "Pro-Mist" : LensFilters.name(c, id);
    }

    /** The pill's label and colours from the settings (also after the settings screen changed them). */
    public void bindPill() {
        Context c = pill.getContext();
        String value = PreferenceKeys.filmLut();
        String filter = PreferenceKeys.lensFilter();
        boolean filterOn = !filter.isEmpty() && PreferenceKeys.lensFilterStrength() > 0f;
        // P281: the film simulation's stock first on the pill
        final com.particlesdevs.photoncamera.processing.color.FilmSim.Settings film = com.particlesdevs.photoncamera.processing.color.FilmSim.current();
        boolean on = !value.isEmpty() && PreferenceKeys.filmLutStrength() > 0f || filterOn || film != null;
        int accent = ShadeStyle.accent(c);
        String label = value.isEmpty() ? (film != null ? film.stock.name : "LUT") : (film != null ? film.stock.name + " · " : "") + name(c, value);
        pill.setText(filterOn ? label + " · " + filterShort(c, filter) : label);
        pill.setEllipsize(TextUtils.TruncateAt.END);
        int fg = open ? ShadeStyle.INK : on ? accent : 0xFFFFFFFF;
        pill.setTextColor(fg);
        android.graphics.drawable.Drawable icon = c.getDrawable(R.drawable.ui_ic_film);
        if (icon != null) {
            icon = icon.mutate();
            int s = UiScale.dp(c, 16);
            icon.setBounds(0, 0, s, s);
            icon.setTint(fg);
        }
        pill.setCompoundDrawablesRelative(icon, null, null, null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(open ? accent : PILL_FILL);
        bg.setCornerRadius(UiScale.dpF(c, 14));
        pill.setBackground(bg);
        pill.setContentDescription(Lang.t(c, "Плёнка (LUT): ", "Film (LUT): ") + name(c, value)
                + (filterOn ? Lang.t(c, ", фильтр: ", ", filter: ") + LensFilters.name(c, filter) : ""));
        pill.setActivated(open);
    }

    /** Every LUT as a card: «Нет», our looks, the folder's .cube files - in the user's order (P279, ItemOrder). */
    private void rebuild() {
        Context c = tray.getContext();
        values.clear();
        values.addAll(com.particlesdevs.photoncamera.settings.ItemOrder.luts(c));
        row.removeAllViews();
        String chosen = PreferenceKeys.filmLut();
        int accent = ShadeStyle.accent(c);
        View selected = null;
        for (String value : values) {
            boolean on = value.equals(chosen);
            // owner, 2026-10-19 («Убираем маленькие живые превью в видоискателе»): the chips are the names
            TextView card = textChip(c, name(c, value), on, accent);
            card.setContentDescription(name(c, value));
            card.setOnClickListener(v -> choose(value));
            // P279: hold and drag a card to move it; the settings' strip shows the same order
            com.particlesdevs.photoncamera.ui.settings.StripReorder.attach(card, row.getChildCount(), (from, to) -> {
                com.particlesdevs.photoncamera.settings.ItemOrder.store(com.particlesdevs.photoncamera.settings.ItemOrder.LUT_KEY, new ArrayList<>(values), from, to);
                rebuild();
            });
            row.addView(card, chipParams(c, row, 34));
            if (on) selected = card;
        }
        rebuildFilters();
        rebuildFilms();
        // P319: the colour sliders follow the settings (also after the settings page changed them)
        warmth.setProgress(colourValue(WARMTH_KEY) + 100);
        tint.setProgress(colourValue(TINT_KEY) + 100);
        int s = Math.round(PreferenceKeys.filmLutStrength() * 100);
        strength.setProgress(s);
        strengthValue.setText(s + " %");
        // without a LUT the strength has nothing to act on: dimmed
        ((View) strength.getParent()).setAlpha(chosen.isEmpty() ? .4f : 1f);
        lutNow.setText(chosen.isEmpty() ? "" : name(c, chosen));
        // the chosen chip scrolled to the centre
        centre(row, selected);
    }

    /** P228: «Нет» and the 12 lens filters as cards, the chosen one in the accent. */
    private void rebuildFilters() {
        Context c = tray.getContext();
        filterRow.removeAllViews();
        String chosen = PreferenceKeys.lensFilter();
        int accent = ShadeStyle.accent(c);
        View selected = null;
        for (String id : filterIds()) {
            boolean on = id.equals(chosen);
            // owner, 2026-10-19: no live previews of the lens filters - the chips are their names
            TextView card = textChip(c, id.isEmpty() ? Lang.t(c, "Без фильтра", "No filter") : LensFilters.name(c, id), on, accent);
            card.setContentDescription(Lang.t(c, "Фильтр: ", "Filter: ") + LensFilters.name(c, id));
            card.setTag("lut_tray_filter_" + id);
            card.setOnClickListener(v -> chooseFilter(id));
            // P279: the filters' order, shared with the settings' strip
            com.particlesdevs.photoncamera.ui.settings.StripReorder.attach(card, filterRow.getChildCount(), (from, to) -> {
                com.particlesdevs.photoncamera.settings.ItemOrder.store(com.particlesdevs.photoncamera.settings.ItemOrder.FILTER_KEY, java.util.Arrays.asList(filterIds()), from, to);
                rebuildFilters();
            });
            filterRow.addView(card, chipParams(c, filterRow, 34));
            if (on) selected = card;
        }
        filterNow.setText(chosen.isEmpty() ? "" : LensFilters.name(c, chosen));
        centre(filterRow, selected);
    }

    /** «Без фильтра» and every filter, in the user's order (P279). */
    static String[] filterIds() {
        return com.particlesdevs.photoncamera.settings.ItemOrder.filters().toArray(new String[0]);
    }

    /** P281: «Без плёнки» and every film stock as a small text card (maker, name); a tap makes it the camera's. */
    private void rebuildFilms() {
        Context c = tray.getContext();
        filmRow.removeAllViews();
        final String chosen = settings().getString(SettingsManager.SCOPE_GLOBAL, com.particlesdevs.photoncamera.processing.color.FilmSim.STOCK_KEY, "");
        final int accent = ShadeStyle.accent(c);
        final int count = com.particlesdevs.photoncamera.processing.color.FilmSim.STOCKS.length + 1;
        View selected = null;
        for (int i = 0; i < count; i++) {
            final com.particlesdevs.photoncamera.processing.color.FilmSim.Stock st = i == 0 ? null : com.particlesdevs.photoncamera.processing.color.FilmSim.STOCKS[i - 1];
            final String id = st == null ? "" : st.id;
            final boolean on = id.equals(chosen);
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(UiScale.dp(c, 12), 0, UiScale.dp(c, 12), 0);
            card.setBackground(chipBackground(c, on, accent, 14));
            TextView brand = text(c, st == null ? Lang.t(c, "Плёнка", "Film") : st.brand.toUpperCase(java.util.Locale.ROOT), 8.5f,
                    on ? (ShadeStyle.INK & 0x00FFFFFF) | 0xB3000000 : ShadeStyle.MUTED);
            brand.setLetterSpacing(.06f);
            card.addView(brand);
            TextView name = text(c, st == null ? Lang.t(c, "Нет", "None") : st.name, 12, on ? ShadeStyle.INK : ShadeStyle.TEXT);
            if (on) name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            card.addView(name);
            card.setContentDescription(st == null ? Lang.t(c, "Без плёнки", "No film") : st.brand + " " + st.name);
            card.setTag("lut_tray_film_" + id);
            card.setOnClickListener(v -> {
                settings().set(SettingsManager.SCOPE_GLOBAL, com.particlesdevs.photoncamera.processing.color.FilmSim.STOCK_KEY, id);
                rebuildFilms();
                bindPill();
            });
            filmRow.addView(card, chipParams(c, filmRow, 40));
            if (on) selected = card;
        }
        com.particlesdevs.photoncamera.processing.color.FilmSim.Stock now = com.particlesdevs.photoncamera.processing.color.FilmSim.stock(chosen);
        filmNow.setText(chosen.isEmpty() || now == null ? "" : now.name);
        centre(filmRow, selected);
    }

    /** A filter card tapped: that filter becomes the camera's (the settings' state). */
    void chooseFilter(String id) {
        settings().set(SettingsManager.SCOPE_GLOBAL, LensFilters.KEY, id);
        if (!id.isEmpty() && PreferenceKeys.lensFilterStrength() <= 0f)
            settings().set(SettingsManager.SCOPE_GLOBAL, LensFilters.STRENGTH_KEY, "50");
        rebuildFilters();
        bindPill();
    }

    /** A card tapped: that LUT becomes the camera's (the settings' state), the tray shows it chosen. */
    void choose(String value) {
        settings().set(SettingsManager.SCOPE_GLOBAL, FilmLut.KEY, value);
        if (!value.isEmpty() && PreferenceKeys.filmLutStrength() <= 0f)
            settings().set(SettingsManager.SCOPE_GLOBAL, FilmLut.STRENGTH_KEY, "100");
        rebuild();
        bindPill();
    }

    /** The next / previous LUT (a horizontal swipe on the viewfinder); returns its name for the message band. */
    public String step(int direction) {
        Context c = tray.getContext();
        if (!open) {
            values.clear();
            values.addAll(com.particlesdevs.photoncamera.settings.ItemOrder.luts(c));
        }
        int i = values.indexOf(PreferenceKeys.filmLut());
        int n = values.size();
        String next = values.get(((i < 0 ? 0 : i) + direction % n + n) % n);
        choose(next);
        return Lang.t(c, "LUT: ", "LUT: ") + name(c, next);
    }

    /** The camera screen goes. */
    public void release() {
        main.removeCallbacksAndMessages(null);
        open = false;
    }
}
