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
 * (pref_film_lut / pref_film_lut_strength): the live viewfinder (LiveRawRenderer) and the photo (ArkCombine) follow it.
 * P228 / P287: under the strength a second row of the lens filters («Нет» and the reference's four diffusion filters) by
 * name; a card is the settings' pref_lens_filter. The pill names the filter after the
 * LUT («Кино 500 · Pro-Mist»).
 */
public final class LutTray {
    /** The concept's tray: the sheet colour at 94 %. */
    static final int TRAY_FILL = 0xF015191C, PILL_FILL = 0x9E0D0F12;
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
        // the tray
        tray.setOrientation(LinearLayout.VERTICAL);
        int pad = UiScale.dp(c, 8);
        tray.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(TRAY_FILL);
        bg.setStroke(Math.max(1, Math.round(c.getResources().getDisplayMetrics().density)), ShadeStyle.LINE);
        bg.setCornerRadius(UiScale.dpF(c, 18));
        tray.setBackground(bg);
        tray.setClickable(true);
        tray.removeAllViews();
        // P281: the film stocks' row (the film simulation), built here, added after the filters
        HorizontalScrollView filmScroll = new HorizontalScrollView(c);
        filmScroll.setHorizontalScrollBarEnabled(false);
        filmScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        filmRow = new LinearLayout(c);
        filmRow.setOrientation(LinearLayout.HORIZONTAL);
        filmRow.setTag("lut_tray_films");
        filmScroll.addView(filmRow, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tray.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // the strength row: «Сила» · slider · value · «Все LUT ›»
        LinearLayout lr = new LinearLayout(c);
        lr.setOrientation(LinearLayout.HORIZONTAL);
        lr.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(c, Lang.t(c, "Сила", "Strength"), 12, ShadeStyle.MUTED);
        lr.addView(label);
        strength = new SeekBar(c);
        strength.setMax(100);
        strength.setProgressTintList(ColorStateList.valueOf(accent));
        strength.setThumbTintList(ColorStateList.valueOf(accent));
        strength.setContentDescription(Lang.t(c, "Сила LUT", "LUT strength"));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lr.addView(strength, slp);
        strengthValue = text(c, "", 12, ShadeStyle.TEXT);
        strengthValue.setMinWidth(UiScale.dp(c, 38));
        strengthValue.setFontFeatureSettings("tnum");
        lr.addView(strengthValue);
        TextView all = text(c, Lang.t(c, "Все LUT ›", "All LUTs ›"), 12, accent);
        all.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        all.setPaddingRelative(UiScale.dp(c, 8), UiScale.dp(c, 6), UiScale.dp(c, 2), UiScale.dp(c, 6));
        all.setOnClickListener(v -> { setOpen(false); openAll.run(); });
        lr.addView(all);
        LinearLayout.LayoutParams lrp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lrp.topMargin = UiScale.dp(c, 6);
        tray.addView(lr, lrp);
        // P228: the lens filters' row
        HorizontalScrollView fscroll = new HorizontalScrollView(c);
        fscroll.setHorizontalScrollBarEnabled(false);
        fscroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        filterRow = new LinearLayout(c);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setTag("lut_tray_filters");
        fscroll.addView(filterRow, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = UiScale.dp(c, 6);
        tray.addView(fscroll, fp);
        // P281: the film stocks' row (the film simulation), after the filters
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = UiScale.dp(c, 6);
        tray.addView(filmScroll, flp);
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
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = UiScale.dp(c, 3);
            card.setPadding(p, p, p, p);
            GradientDrawable cbg = new GradientDrawable();
            cbg.setCornerRadius(UiScale.dpF(c, 12));
            cbg.setColor(on ? (accent & 0x00FFFFFF) | 0x1F000000 : 0);
            cbg.setStroke(Math.max(1, UiScale.dp(c, 1)), on ? accent : 0);
            card.setBackground(cbg);
            // owner, 2026-10-19 («Убираем маленькие живые превью в видоискателе»): the cards are the names
            TextView name = text(c, name(c, value), 10, on ? accent : ShadeStyle.MUTED);
            name.setMaxLines(2);
            name.setGravity(Gravity.CENTER);
            name.setMinHeight(UiScale.dp(c, 23));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            card.addView(name, nlp);
            card.setContentDescription(name(c, value));
            card.setOnClickListener(v -> choose(value));
            // P279: hold and drag a card to move it; the settings' strip shows the same order
            com.particlesdevs.photoncamera.ui.settings.StripReorder.attach(card, row.getChildCount(), (from, to) -> {
                com.particlesdevs.photoncamera.settings.ItemOrder.store(com.particlesdevs.photoncamera.settings.ItemOrder.LUT_KEY, new ArrayList<>(values), from, to);
                rebuild();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(UiScale.dp(c, 62), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (row.getChildCount() > 0) lp.setMarginStart(UiScale.dp(c, 6));
            row.addView(card, lp);
            if (on) selected = card;
        }
        rebuildFilters();
        rebuildFilms();
        int s = Math.round(PreferenceKeys.filmLutStrength() * 100);
        strength.setProgress(s);
        strengthValue.setText(s + " %");
        // the chosen card scrolled to the centre
        View target = selected;
        if (target != null) row.post(() -> {
            View scroll = (View) row.getParent();
            if (scroll instanceof HorizontalScrollView)
                ((HorizontalScrollView) scroll).smoothScrollTo(Math.max(0, target.getLeft() + target.getWidth() / 2 - scroll.getWidth() / 2), 0);
        });
    }

    /** P228: «Нет» and the 12 lens filters as cards, the chosen one in the accent. */
    private void rebuildFilters() {
        Context c = tray.getContext();
        filterRow.removeAllViews();
        String chosen = PreferenceKeys.lensFilter();
        int accent = ShadeStyle.accent(c);
        for (String id : filterIds()) {
            boolean on = id.equals(chosen);
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = UiScale.dp(c, 3);
            card.setPadding(p, p, p, p);
            GradientDrawable cbg = new GradientDrawable();
            cbg.setCornerRadius(UiScale.dpF(c, 12));
            cbg.setColor(on ? (accent & 0x00FFFFFF) | 0x1F000000 : 0);
            cbg.setStroke(Math.max(1, UiScale.dp(c, 1)), on ? accent : 0);
            card.setBackground(cbg);
            // owner, 2026-10-19: no live previews of the lens filters - the cards are their names
            TextView name = text(c, id.isEmpty() ? Lang.t(c, "Без фильтра", "No filter") : LensFilters.name(c, id), 10,
                    on ? accent : ShadeStyle.MUTED);
            name.setMaxLines(2);
            name.setGravity(Gravity.CENTER);
            name.setMinHeight(UiScale.dp(c, 23));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            card.addView(name, nlp);
            card.setContentDescription(Lang.t(c, "Фильтр: ", "Filter: ") + LensFilters.name(c, id));
            card.setTag("lut_tray_filter_" + id);
            card.setOnClickListener(v -> chooseFilter(id));
            // P279: the filters' order, shared with the settings' strip
            com.particlesdevs.photoncamera.ui.settings.StripReorder.attach(card, filterRow.getChildCount(), (from, to) -> {
                com.particlesdevs.photoncamera.settings.ItemOrder.store(com.particlesdevs.photoncamera.settings.ItemOrder.FILTER_KEY, java.util.Arrays.asList(filterIds()), from, to);
                rebuildFilters();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(UiScale.dp(c, 62), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (filterRow.getChildCount() > 0) lp.setMarginStart(UiScale.dp(c, 6));
            filterRow.addView(card, lp);
        }
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
            card.setPadding(UiScale.dp(c, 8), UiScale.dp(c, 5), UiScale.dp(c, 8), UiScale.dp(c, 5));
            GradientDrawable cbg = new GradientDrawable();
            cbg.setCornerRadius(UiScale.dpF(c, 12));
            cbg.setColor(on ? (accent & 0x00FFFFFF) | 0x1F000000 : 0x14FFFFFF);
            cbg.setStroke(Math.max(1, UiScale.dp(c, 1)), on ? accent : 0);
            card.setBackground(cbg);
            TextView brand = text(c, st == null ? Lang.t(c, "Плёнка", "Film") : st.brand.toUpperCase(java.util.Locale.ROOT), 8.5f, on ? accent : ShadeStyle.MUTED);
            card.addView(brand);
            TextView name = text(c, st == null ? Lang.t(c, "Нет", "None") : st.name, 11, on ? accent : ShadeStyle.TEXT);
            name.setMaxLines(1);
            card.addView(name);
            card.setContentDescription(st == null ? Lang.t(c, "Без плёнки", "No film") : st.brand + " " + st.name);
            card.setTag("lut_tray_film_" + id);
            card.setOnClickListener(v -> {
                settings().set(SettingsManager.SCOPE_GLOBAL, com.particlesdevs.photoncamera.processing.color.FilmSim.STOCK_KEY, id);
                rebuildFilms();
                bindPill();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiScale.dp(c, 40));
            if (filmRow.getChildCount() > 0) lp.setMarginStart(UiScale.dp(c, 6));
            filmRow.addView(card, lp);
            if (on) selected = card;
        }
        final View target = selected;
        if (target != null) filmRow.post(() -> {
            View sc = (View) filmRow.getParent();
            if (sc instanceof HorizontalScrollView)
                ((HorizontalScrollView) sc).smoothScrollTo(Math.max(0, target.getLeft() + target.getWidth() / 2 - sc.getWidth() / 2), 0);
        });
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
