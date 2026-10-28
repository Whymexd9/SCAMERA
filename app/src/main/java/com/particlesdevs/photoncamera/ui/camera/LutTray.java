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
import com.particlesdevs.photoncamera.settings.ParamHelp;
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
 * Owner, 2026-10-28: the sections as tabs of one segmented control («LUT · Фильтр · Плёнка · Цвет», the last one kept), the
 * chosen filter's and film's adjustments in their pages (TrayAdjust), a long press on a parameter shows what it does (ParamHelp),
 * «Все ›» opens the shown section's settings page.
 */
public final class LutTray {
    /**
     * The tray as a pane of dark glass (owner, 2026-10-27: «более красиво ... аля iOS / Mac OS»): a vertical gradient of the sheet
     * colour at 95 %, a hairline edge lit a little more at the top, 24dp corners and a soft shadow.
     */
    static final int TRAY_FILL = 0xF214171B, TRAY_TOP = 0xF21E2228, TRAY_EDGE = 0x24FFFFFF, PILL_FILL = 0x9E0D0F12;
    /** An unchosen chip: a faint glass fill with a faint edge. */
    static final int CHIP_FILL = 0x17FFFFFF, CHIP_LINE = 0x12FFFFFF;
    /** The warmth / tint tracks: cool -> warm, green -> magenta (as a photo editor shows them). */
    static final int[] WARMTH_TRACK = {0xFF5AA0FF, 0xFFB9C2CC, 0xFFFFB04A}, TINT_TRACK = {0xFF57D07A, 0xFFB9C2CC, 0xFFE46AD6};
    /**
     * Owner, 2026-10-28 («дай возможность настраивать луты, фильтры и пленки прямо в этом подменю. Делай так чтобы оно не
     * получалось слишком массивным»): the tray's four sections as tabs of one segmented control - only the chosen section's page
     * shows, with the chosen filter's / film's adjustments (TrayAdjust: parameter chips and one slider) under its chips.
     */
    static final int TAB_LUT = 0, TAB_FILTER = 1, TAB_FILM = 2, TAB_COLOUR = 3;
    static final String[] TAB_IDS = {"lut", "filter", "film", "colour"};
    /** The tab shown last (kept while the app runs). */
    static int lastTab = TAB_LUT;
    private String[] tabTitles;
    private final TextView pill;
    private final LinearLayout tray;
    private final LinearLayout row;
    private final SeekBar strength;
    private final TextView strengthValue;
    @Nullable private final SurfaceView preview;
    private final Runnable openAll;
    /** Opens a settings page (its key, the row to show) for «Все ›» of the filter and film tabs; null: the LUT page. */
    @Nullable private java.util.function.BiConsumer<String, String> pageOpener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> values = new ArrayList<>();
    private final LinearLayout filterRow;
    /** P281: the film simulation's stocks (FilmSim). */
    private final LinearLayout filmRow;
    private final TrayMotion.ChipRow tabs;
    private final LinearLayout pages;
    private final TextView all, hint;
    private final TrayAdjust filterAdjust, filmAdjust;
    private int tab = -1;
    /** The tray's height animation (a tab, an adjust strip or the description changed what it holds). */
    @Nullable private android.animation.ValueAnimator resizing;
    private final Runnable hideHint = this::hideHint;
    /**
     * P319 (user report 2026-10-22: «Много лишнего, в том числе того, что должно быть только в видоискателе (фильтр на объектив,
     * тепло, и т.п.)»): «Тепло» and «Оттенок» of «Цвет» in the tray too, the same settings (pref_scam_hybrid_colour_warmth /
     * _tint, -100..100); the RAW viewfinder shows them at once, the photo always.
     */
    static final String WARMTH_KEY = ParamHelp.WARMTH_KEY, TINT_KEY = ParamHelp.TINT_KEY;
    private final SeekBar warmth, tint;
    private final TextView warmthValue, tintValue;
    @Nullable private Runnable onOpen;
    private boolean open;
    /** The values the filter and film chips stand for (the chips are updated in place, the row's pill glides). */
    private final List<String> filterValues = new ArrayList<>(), filmValues = new ArrayList<>();
    /** The pill's colours as last drawn (its change animates). */
    @Nullable private GradientDrawable pillBg;
    private int pillFill, pillInk;

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
        tray.setOrientation(LinearLayout.VERTICAL);
        tray.setPadding(UiScale.dp(c, 12), UiScale.dp(c, 12), UiScale.dp(c, 12), UiScale.dp(c, 12));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{TRAY_TOP, TRAY_FILL});
        bg.setStroke(Math.max(1, Math.round(c.getResources().getDisplayMetrics().density)), TRAY_EDGE);
        bg.setCornerRadius(UiScale.dpF(c, 24));
        tray.setBackground(bg);
        tray.setElevation(UiScale.dpF(c, 18));
        tray.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        tray.setClickable(true);
        tray.removeAllViews();
        // the head: the segmented control of the sections and «Все ›» to the chosen section's settings page
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setTag("lut_tray_head");
        tabs = new TrayMotion.ChipRow(c, accent, UiScale.dpF(c, 13));
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setTag("lut_tray_tabs");
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(UiScale.dpF(c, 15));
        track.setColor(CHIP_FILL);
        track.setStroke(Math.max(1, UiScale.dp(c, 1)), CHIP_LINE);
        tabs.setBackground(track);
        tabs.setPadding(UiScale.dp(c, 2), UiScale.dp(c, 2), UiScale.dp(c, 2), UiScale.dp(c, 2));
        final String[] titles = tabTitles = new String[]{"LUT", Lang.t(c, "Фильтр", "Filter"), Lang.t(c, "Плёнка", "Film"), Lang.t(c, "Цвет", "Colour")};
        for (int i = 0; i < titles.length; i++) {
            final int t = i;
            TextView chip = text(c, titles[i], 12.5f, ShadeStyle.TEXT);
            chip.setGravity(Gravity.CENTER);
            chip.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            chip.setTag("lut_tray_tab_" + TAB_IDS[i]);
            chip.setContentDescription(titles[i]);
            chip.setOnClickListener(v -> { if (tab != t) TrayMotion.tick(v); showTab(t, true); });
            TrayMotion.pressable(chip);
            tabs.addView(chip, new LinearLayout.LayoutParams(0, UiScale.dp(c, 28), 1f));
        }
        head.addView(tabs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        all = text(c, Lang.t(c, "Все ›", "All ›"), 12, accent);
        all.setTag("lut_tray_all");
        all.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        all.setPaddingRelative(UiScale.dp(c, 10), UiScale.dp(c, 6), UiScale.dp(c, 4), UiScale.dp(c, 6));
        all.setBackground(ShadeStyle.ripple(c, 10));
        all.setOnClickListener(v -> openPage());
        head.addView(all);
        tray.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // the pages, one shown
        pages = new LinearLayout(c);
        pages.setOrientation(LinearLayout.VERTICAL);
        pages.setTag("lut_tray_pages");
        LinearLayout.LayoutParams pgp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pgp.topMargin = UiScale.dp(c, 6);
        tray.addView(pages, pgp);
        // LUT: the chips and the strength
        LinearLayout lutPage = page(c, TAB_LUT);
        row = chipRow(c, lutPage, "lut_tray_luts", 17);
        LinearLayout lr = new LinearLayout(c);
        lr.setOrientation(LinearLayout.HORIZONTAL);
        lr.setGravity(Gravity.CENTER_VERTICAL);
        lr.setTag("lut_tray_strength");
        TextView label = text(c, Lang.t(c, "Сила", "Strength"), 12, ShadeStyle.MUTED);
        label.setMinWidth(UiScale.dp(c, 64));
        helpOnHold(label, label.getText().toString(), FilmLut.STRENGTH_KEY);
        lr.addView(label);
        strength = new SeekBar(c);
        strength.setMax(100);
        styleSlider(c, strength, accent, null);
        strength.setContentDescription(Lang.t(c, "Сила LUT", "LUT strength"));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lr.addView(strength, slp);
        strengthValue = text(c, "", 12, ShadeStyle.TEXT);
        strengthValue.setMinWidth(UiScale.dp(c, 54));
        strengthValue.setGravity(Gravity.END);
        strengthValue.setFontFeatureSettings("tnum");
        lr.addView(strengthValue);
        LinearLayout.LayoutParams lrp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lrp.topMargin = UiScale.dp(c, 4);
        lutPage.addView(lr, lrp);
        // P228: the lens filters, with the chosen filter's strength, own parameter and fine controls
        LinearLayout filterPage = page(c, TAB_FILTER);
        filterRow = chipRow(c, filterPage, "lut_tray_filters", 17);
        filterAdjust = new TrayAdjust(c, "lut_tray_filter_adjust");
        filterPage.addView(filterAdjust.view);
        // P281: the film simulation's stocks, with the chosen film's strength, grain, halation, softness, exposures, contrast
        LinearLayout filmPage = page(c, TAB_FILM);
        filmRow = chipRow(c, filmPage, "lut_tray_films", 14);
        filmAdjust = new TrayAdjust(c, "lut_tray_film_adjust");
        filmPage.addView(filmAdjust.view);
        for (TrayAdjust a : new TrayAdjust[]{filterAdjust, filmAdjust}) {
            a.setOnChange(() -> { bindPill(); bindTabs(); });
            a.setOnHelp(this::showHint);
        }
        // P319: the colour's warmth and tint
        LinearLayout colourPage = page(c, TAB_COLOUR);
        warmthValue = text(c, "", 12, ShadeStyle.TEXT);
        warmth = colourSlider(c, colourPage, Lang.t(c, "Тепло", "Warmth"), WARMTH_KEY, warmthValue, "lut_tray_warmth", WARMTH_TRACK);
        tintValue = text(c, "", 12, ShadeStyle.TEXT);
        tint = colourSlider(c, colourPage, Lang.t(c, "Оттенок", "Tint"), TINT_KEY, tintValue, "lut_tray_tint", TINT_TRACK);
        // the description of a parameter held (owner, 2026-10-28: «добавь описание для параметров по длинному зажатию»)
        hint = text(c, "", 12.5f, ShadeStyle.TEXT);
        hint.setTag("lut_tray_hint");
        hint.setMaxLines(6);
        hint.setLineSpacing(UiScale.dp(c, 2), 1f);
        hint.setPadding(UiScale.dp(c, 12), UiScale.dp(c, 9), UiScale.dp(c, 12), UiScale.dp(c, 10));
        GradientDrawable hb = new GradientDrawable();
        hb.setCornerRadius(UiScale.dpF(c, 14));
        hb.setColor(CHIP_FILL);
        hint.setBackground(hb);
        hint.setVisibility(View.GONE);
        hint.setOnClickListener(v -> hideHint());
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = UiScale.dp(c, 8);
        tray.addView(hint, hlp);
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                strengthValue.setText(p + " %");
                if (user) settings().set(SettingsManager.SCOPE_GLOBAL, FilmLut.STRENGTH_KEY, String.valueOf(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { bindPill(); bindTabs(); }
        });
        // P357: a double tap puts the default back (the settings' 100 %)
        com.particlesdevs.photoncamera.ui.controls.DoubleTapReset.attach(strength, () -> {
            strength.setProgress(100);
            settings().set(SettingsManager.SCOPE_GLOBAL, FilmLut.STRENGTH_KEY, "100");
            bindPill();
            bindTabs();
        });
        tray.setVisibility(View.GONE);
        bindPill();
    }

    /** A section's page in the pages' box (shown by its tab). */
    private LinearLayout page(Context c, int t) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setTag("lut_tray_page_" + TAB_IDS[t]);
        p.setVisibility(View.GONE);
        pages.addView(p, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return p;
    }

    /** «Все ›» opens the settings page of the shown section (the LUT's through the camera's own route). */
    public void setPageOpener(@Nullable java.util.function.BiConsumer<String, String> opener) {
        pageOpener = opener;
    }

    private void openPage() {
        setOpen(false);
        if (tab == TAB_FILTER && pageOpener != null) pageOpener.accept("lens_filter_screen", LensFilters.KEY);
        else if (tab == TAB_FILM && pageOpener != null)
            pageOpener.accept("film_sim_screen", com.particlesdevs.photoncamera.processing.color.FilmSim.STOCK_KEY);
        else openAll.run();
    }

    /** A long press on {@code v} shows {@code key}'s description (ParamHelp) under the page. */
    private void helpOnHold(View v, String title, String key) {
        v.setOnLongClickListener(x -> {
            String text = ParamHelp.of(x.getContext(), key);
            if (text == null) return false;
            showHint(title, text);
            return true;
        });
    }

    /** The description line: the parameter's name, then what it does; it stays 8 s, a tap on it closes it. */
    void showHint(String title, String text) {
        Context c = tray.getContext();
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(title);
        b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length(), 0);
        b.append("  ");
        int at = b.length();
        b.append(text);
        b.setSpan(new android.text.style.ForegroundColorSpan(ShadeStyle.MUTED), at, b.length(), 0);
        main.removeCallbacks(hideHint);
        if (hint.getVisibility() == View.VISIBLE) {
            hint.setText(b);
        } else {
            resize(() -> { hint.setText(b); hint.setVisibility(View.VISIBLE); });
            hint.setAlpha(0f);
            hint.animate().alpha(1f).setStartDelay(60).setDuration(200).setInterpolator(TrayMotion.EASE_OUT).start();
        }
        hint.setContentDescription(title + ". " + text);
        main.postDelayed(hideHint, 8000);
    }

    private void hideHint() {
        main.removeCallbacks(hideHint);
        if (hint.getVisibility() != View.VISIBLE) return;
        hint.animate().cancel();
        resize(() -> hint.setVisibility(View.GONE));
    }

    /**
     * Runs {@code change} (what the tray holds) and lets the tray's height follow on the glide spring instead of jumping
     * (the tray is anchored at its bottom: it grows and shrinks at its top edge).
     */
    private void resize(Runnable change) {
        final int h0 = tray.getHeight();
        if (resizing != null) resizing.cancel();
        change.run();
        if (!open || h0 == 0 || !tray.isAttachedToWindow() || !android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        tray.measure(View.MeasureSpec.makeMeasureSpec(tray.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int h1 = tray.getMeasuredHeight();
        if (h1 == h0) return;
        final ViewGroup.LayoutParams lp = tray.getLayoutParams();
        lp.height = h0;
        tray.setLayoutParams(lp);
        final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(TrayMotion.GLIDE.durationMs);
        a.setInterpolator(TrayMotion.GLIDE);
        a.addUpdateListener(v -> {
            lp.height = Math.round(h0 + (h1 - h0) * (float) v.getAnimatedValue());
            tray.setLayoutParams(lp);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                tray.setLayoutParams(lp);
                if (resizing == a) resizing = null;
            }
        });
        resizing = a;
        a.start();
    }

    /** Shows tab {@code t}'s page: the pill glides to its tab, the page slides in from the side it lies on, the height follows. */
    private void showTab(int t, boolean animate) {
        final int from = tab;
        tab = t;
        lastTab = t;
        if (from != t) hideHintNow();
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView chip = (TextView) tabs.getChildAt(i);
            chip.setActivated(i == t);
            fade(chip, i == t ? ShadeStyle.INK : ShadeStyle.TEXT, animate && from >= 0);
            if (i == t) tabs.moveTo(chip, animate && from >= 0);
        }
        bindTabs();
        all.setVisibility(t == TAB_COLOUR ? View.INVISIBLE : View.VISIBLE);
        final View in = pages.getChildAt(t);
        final Runnable change = () -> { for (int i = 0; i < pages.getChildCount(); i++) pages.getChildAt(i).setVisibility(i == t ? View.VISIBLE : View.GONE); };
        in.animate().cancel();
        if (!animate || from < 0 || from == t) {
            change.run();
            in.setAlpha(1f);
            in.setTranslationX(0f);
            return;
        }
        resize(change);
        in.setAlpha(0f);
        in.setTranslationX(UiScale.dpF(tray.getContext(), 18) * (t > from ? 1 : -1));
        in.animate().alpha(1f).translationX(0f).setStartDelay(30).setDuration(260).setInterpolator(TrayMotion.EASE_OUT).start();
    }

    private void hideHintNow() {
        main.removeCallbacks(hideHint);
        hint.animate().cancel();
        hint.setVisibility(View.GONE);
    }

    /** A tab whose section is on (a LUT, a filter, a film, a colour shift) carries a small dot, INK on the pill, accent off it. */
    private void bindTabs() {
        Context c = tray.getContext();
        final boolean[] on = {
                !PreferenceKeys.filmLut().isEmpty() && PreferenceKeys.filmLutStrength() > 0f,
                !PreferenceKeys.lensFilter().isEmpty() && PreferenceKeys.lensFilterStrength() > 0f,
                com.particlesdevs.photoncamera.processing.color.FilmSim.current() != null,
                colourValue(WARMTH_KEY) != 0 || colourValue(TINT_KEY) != 0};
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView chip = (TextView) tabs.getChildAt(i);
            if (!on[i]) {
                chip.setText(tabTitles[i]);
                continue;
            }
            // the dot right after the title (a compound drawable sits at the tab's edge, half off the selected pill)
            android.text.SpannableString t = new android.text.SpannableString(tabTitles[i] + " ");
            t.setSpan(new DotSpan(i == tab ? ShadeStyle.INK : ShadeStyle.accent(c), UiScale.dpF(c, 2.5f), UiScale.dpF(c, 4)),
                    t.length() - 1, t.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            chip.setText(t);
        }
    }

    /** A tab's «something is on here» dot: a small circle {@code gap} after the title, at the middle of its letters. */
    private static final class DotSpan extends android.text.style.ReplacementSpan {
        private final int color;
        private final float r, gap;

        DotSpan(int color, float r, float gap) {
            this.color = color;
            this.r = r;
            this.gap = gap;
        }

        @Override
        public int getSize(android.graphics.Paint p, CharSequence text, int start, int end, @Nullable android.graphics.Paint.FontMetricsInt fm) {
            return Math.round(gap + 2 * r);
        }

        @Override
        public void draw(android.graphics.Canvas cv, CharSequence text, int start, int end, float x, int top, int y, int bottom, android.graphics.Paint p) {
            final int old = p.getColor();
            p.setColor(color);
            cv.drawCircle(x + gap + r, y + p.ascent() * .36f, r, p);
            p.setColor(old);
        }
    }

    /** P319: a «label · slider · value» row of a -100..100 colour setting, written as the settings page writes it. */
    private SeekBar colourSlider(Context c, LinearLayout parent, String label, String key, TextView value, String tag, int[] track) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setTag(tag);
        TextView l = text(c, label, 12, ShadeStyle.MUTED);
        l.setMinWidth(UiScale.dp(c, 64));
        helpOnHold(l, label, key);
        r.addView(l);
        SeekBar bar = new SeekBar(c);
        bar.setMax(200);
        styleSlider(c, bar, 0, track);
        bar.setContentDescription(label);
        r.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        value.setMinWidth(UiScale.dp(c, 54));
        value.setGravity(Gravity.END);
        value.setFontFeatureSettings("tnum");
        r.addView(value);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                value.setText(signed(p - 100));
                if (user) {
                    settings().set(SettingsManager.SCOPE_GLOBAL, key, String.valueOf(p - 100));
                    if (p == 100) TrayMotion.tick(s); // the neutral middle is felt
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { bindTabs(); }
        });
        // P357: a double tap puts the default (0) back
        com.particlesdevs.photoncamera.ui.controls.DoubleTapReset.attach(bar, () -> {
            bar.setProgress(100);
            settings().set(SettingsManager.SCOPE_GLOBAL, key, "0");
            bindTabs();
        });
        // a long press on the slider: what it does
        bar.setOnLongClickListener(x -> {
            String text = ParamHelp.of(x.getContext(), key);
            if (text == null) return false;
            showHint(label, text);
            return true;
        });
        parent.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
     * A section's horizontal row of chips in its scroll, added to the tray: the row draws its selection pill under the chips
     * (TrayMotion.ChipRow), which glides to the chosen chip; the row's ends fade into the tray.
     */
    private LinearLayout chipRow(Context c, LinearLayout parent, String tag, float radiusDp) {
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setHorizontalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(UiScale.dp(c, 18));
        TrayMotion.ChipRow r = new TrayMotion.ChipRow(c, ShadeStyle.accent(c), UiScale.dpF(c, radiusDp));
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setTag(tag);
        scroll.addView(r, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiScale.dp(c, 4);
        parent.addView(scroll, lp);
        return r;
    }

    /**
     * A chip's look: clear when chosen (the row's selection pill in the accent lies under it), the glass fill and edge otherwise;
     * a ripple bounded by its shape.
     */
    static android.graphics.drawable.Drawable chipBackground(Context c, boolean on, int accent, float radiusDp) {
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(UiScale.dpF(c, radiusDp));
        fill.setColor(on ? 0x00000000 : CHIP_FILL);
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
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setBackground(chipBackground(c, on, accent, 17));
        t.setActivated(on);
        TrayMotion.pressable(t);
        return t;
    }

    /**
     * A row's chips show {@code chosen} (an index of the row, -1 none): the chosen chip clear over the selection pill, its texts in
     * INK, the others on glass - the colours cross-fade and the pill glides when {@code animate}, else they are set at once.
     */
    private void select(LinearLayout row, int chosen, float radiusDp, boolean animate) {
        Context c = row.getContext();
        int accent = ShadeStyle.accent(c);
        View target = null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View chip = row.getChildAt(i);
            boolean on = i == chosen;
            if (on) target = chip;
            if (chip.isActivated() == on && chip.getBackground() != null && animate) continue;
            chip.setActivated(on);
            chip.setBackground(chipBackground(c, on, accent, radiusDp));
            recolour(chip, on, animate);
        }
        if (!(row instanceof TrayMotion.ChipRow)) return;
        TrayMotion.ChipRow chips = (TrayMotion.ChipRow) row;
        if (target == null) chips.hide();
        else chips.moveTo(target, animate);
    }

    /** A chip's texts: INK on the pill, TEXT (and the brand line MUTED) on glass; cross-faded over 180 ms when {@code animate}. */
    private static void recolour(View chip, boolean on, boolean animate) {
        if (chip instanceof TextView) {
            fade((TextView) chip, on ? ShadeStyle.INK : ShadeStyle.TEXT, animate);
        } else if (chip instanceof ViewGroup && ((ViewGroup) chip).getChildCount() == 2) {
            ViewGroup g = (ViewGroup) chip;
            fade((TextView) g.getChildAt(0), on ? (ShadeStyle.INK & 0x00FFFFFF) | 0xB3000000 : ShadeStyle.MUTED, animate);
            fade((TextView) g.getChildAt(1), on ? ShadeStyle.INK : ShadeStyle.TEXT, animate);
        }
    }

    private static void fade(TextView t, int to, boolean animate) {
        final int from = t.getCurrentTextColor();
        t.setTextColor(to); // the state at once; the animator's first frame (before the next draw) starts from the old colour
        if (!animate || from == to) return;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(180);
        a.setInterpolator(TrayMotion.EASE_OUT);
        a.addUpdateListener(v -> t.setTextColor(TrayMotion.mix(from, to, (float) v.getAnimatedValue())));
        a.start();
    }

    /**
     * An iOS-like slider: a 4dp rounded track (the accent over a faint line, or {@code track}'s colours across the whole length for
     * the warmth / tint, whose value is the thumb's place) and a white 20dp thumb with a hairline edge.
     */
    static void styleSlider(Context c, SeekBar bar, int accent, @Nullable int[] track) {
        float r = UiScale.dpF(c, 2);
        GradientDrawable back = track != null ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, track) : new GradientDrawable();
        if (track == null) back.setColor(0x33FFFFFF);
        back.setCornerRadius(r);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(track != null ? 0x00000000 : accent);
        fill.setCornerRadius(r);
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{back, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        int h = UiScale.dp(c, 4);
        layers.setLayerHeight(0, h);
        layers.setLayerHeight(1, h);
        layers.setLayerGravity(0, Gravity.CENTER_VERTICAL);
        layers.setLayerGravity(1, Gravity.CENTER_VERTICAL);
        bar.setProgressDrawable(layers);
        bar.setProgressTintList(null);
        bar.setProgressBackgroundTintList(null);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(0xFFFFFFFF);
        thumb.setStroke(Math.max(1, UiScale.dp(c, 1) / 2), 0x33000000);
        int t = UiScale.dp(c, 20);
        thumb.setSize(t, t);
        bar.setThumb(thumb);
        bar.setThumbTintList(null);
        bar.setThumbOffset(t / 2);
        bar.setSplitTrack(false);
        bar.setMinimumHeight(UiScale.dp(c, 32));
        bar.setPadding(t / 2 + UiScale.dp(c, 2), 0, t / 2 + UiScale.dp(c, 2), 0);
    }

    /** A chip's place in its row: {@code heightDp} high, 6dp after the previous. */
    private static LinearLayout.LayoutParams chipParams(Context c, ViewGroup row, int heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiScale.dp(c, heightDp));
        if (row.getChildCount() > 0) lp.setMarginStart(UiScale.dp(c, 8));
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
        if (resizing != null) resizing.cancel(); // its end puts the height back to wrap the content
        if (open) {
            if (onOpen != null) onOpen.run();
            tray.animate().setListener(null);
            hideHintNow();
            rebuild();
            // visible at once (the state), rising into place on a spring (the motion)
            TrayMotion.appear(tray);
        } else {
            main.removeCallbacks(hideHint);
            TrayMotion.disappear(tray, () -> {
                if (open) return;
                tray.setVisibility(View.GONE);
                TrayMotion.rest(tray);
                hideHintNow();
            });
        }
        bindPill(true);
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
        bindPill(false);
    }

    /** As {@link #bindPill()}; {@code animate}: the pill's colours cross-fade and it gives a little under the change (the tray opening). */
    private void bindPill(boolean animate) {
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
        final int fill = open ? accent : PILL_FILL;
        final int fromFg = pillInk, fromFill = pillFill;
        final boolean fadeIn = animate && pillBg != null && (fromFg != fg || fromFill != fill);
        pillInk = fg;
        pillFill = fill;
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
        bg.setColor(fill);
        bg.setCornerRadius(UiScale.dpF(c, 14));
        pill.setBackground(bg);
        pillBg = bg;
        if (fadeIn) {
            // the final colours are set above; the animator's first frame (before the next draw) starts from the old ones
            final android.graphics.drawable.Drawable[] icons = pill.getCompoundDrawablesRelative();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(200);
            a.setInterpolator(TrayMotion.EASE_OUT);
            a.addUpdateListener(v -> {
                float k = (float) v.getAnimatedValue();
                if (pillBg != bg) return; // a newer bind took over
                bg.setColor(TrayMotion.mix(fromFill, fill, k));
                int ink = TrayMotion.mix(fromFg, fg, k);
                pill.setTextColor(ink);
                if (icons[0] != null) icons[0].setTint(ink);
            });
            a.start();
            // the pill gives a little and springs back
            pill.animate().cancel();
            pill.setScaleX(.92f);
            pill.setScaleY(.92f);
            pill.animate().scaleX(1f).scaleY(1f).setDuration(TrayMotion.RELEASE.durationMs).setStartDelay(0).setInterpolator(TrayMotion.RELEASE).start();
        }
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
            card.setOnClickListener(v -> { TrayMotion.tick(v); choose(value); });
            // P279: hold and drag a card to move it; the settings' strip shows the same order
            com.particlesdevs.photoncamera.ui.settings.StripReorder.attach(card, row.getChildCount(), (from, to) -> {
                com.particlesdevs.photoncamera.settings.ItemOrder.store(com.particlesdevs.photoncamera.settings.ItemOrder.LUT_KEY, new ArrayList<>(values), from, to);
                rebuild();
            });
            row.addView(card, chipParams(c, row, 34));
            if (on) selected = card;
        }
        select(row, values.indexOf(chosen), 17, false);
        rebuildFilters();
        rebuildFilms();
        // P319: the colour sliders follow the settings (also after the settings page changed them)
        warmth.setProgress(colourValue(WARMTH_KEY) + 100);
        tint.setProgress(colourValue(TINT_KEY) + 100);
        bindLutState(chosen, false);
        // the chosen chip scrolled to the centre
        centre(row, selected);
        tab = -1;
        showTab(lastTab, false);
    }

    /** The strength row for the LUT {@code chosen}. */
    private void bindLutState(String chosen, boolean animate) {
        int s = Math.round(PreferenceKeys.filmLutStrength() * 100);
        strength.setProgress(s);
        strengthValue.setText(s + " %");
        // without a LUT the strength has nothing to act on: dimmed
        View strengthRow = (View) strength.getParent();
        float alpha = chosen.isEmpty() ? .4f : 1f;
        if (animate) strengthRow.animate().alpha(alpha).setDuration(200).setStartDelay(0).setInterpolator(TrayMotion.EASE_OUT).start();
        else strengthRow.setAlpha(alpha);
    }

    /** P228: «Нет» and the 12 lens filters as cards, the chosen one in the accent, and the chosen filter's adjustments. */
    private void rebuildFilters() {
        Context c = tray.getContext();
        filterRow.removeAllViews();
        String chosen = PreferenceKeys.lensFilter();
        int accent = ShadeStyle.accent(c);
        View selected = null;
        filterValues.clear();
        java.util.Collections.addAll(filterValues, filterIds());
        for (String id : filterValues) {
            boolean on = id.equals(chosen);
            // owner, 2026-10-19: no live previews of the lens filters - the chips are their names
            TextView card = textChip(c, id.isEmpty() ? Lang.t(c, "Без фильтра", "No filter") : LensFilters.name(c, id), on, accent);
            card.setContentDescription(Lang.t(c, "Фильтр: ", "Filter: ") + LensFilters.name(c, id));
            card.setTag("lut_tray_filter_" + id);
            card.setOnClickListener(v -> { TrayMotion.tick(v); chooseFilter(id); });
            // P279: the filters' order, shared with the settings' strip
            com.particlesdevs.photoncamera.ui.settings.StripReorder.attach(card, filterRow.getChildCount(), (from, to) -> {
                com.particlesdevs.photoncamera.settings.ItemOrder.store(com.particlesdevs.photoncamera.settings.ItemOrder.FILTER_KEY, java.util.Arrays.asList(filterIds()), from, to);
                rebuildFilters();
            });
            filterRow.addView(card, chipParams(c, filterRow, 34));
            if (on) selected = card;
        }
        select(filterRow, filterValues.indexOf(chosen), 17, false);
        bindFilterAdjust(chosen);
        centre(filterRow, selected);
    }

    /** The chosen filter's adjustments (none without a filter): its strength, own parameter and fine controls. */
    private void bindFilterAdjust(String id) {
        if (id.isEmpty()) {
            filterAdjust.view.setVisibility(View.GONE);
            return;
        }
        filterAdjust.setParams(filterParams(tray.getContext(), id));
        filterAdjust.view.setVisibility(View.VISIBLE);
    }

    /**
     * A filter's parameters as its settings page has them (LensFilterPreference): the strength (0..100, 50), its own parameter
     * and its fine controls (0..100, the middle as designed: LensFilters.Tune) - written as that page writes them.
     */
    static List<TrayAdjust.Param> filterParams(Context c, String id) {
        final List<TrayAdjust.Param> out = new ArrayList<>();
        out.add(new TrayAdjust.Param("strength", Lang.t(c, "Сила", "Strength"), ParamHelp.of(c, LensFilters.STRENGTH_KEY),
                0, 100, 1, 50, " %", true,
                () -> PreferenceKeys.lensFilterStrength() * 100f,
                v -> settings().set(SettingsManager.SCOPE_GLOBAL, LensFilters.STRENGTH_KEY, String.valueOf(Math.round(v)))));
        final String pn = LensFilters.paramName(c, id);
        if (pn != null) {
            final int colon = pn.indexOf(':');
            out.add(new TrayAdjust.Param("param", colon > 0 ? pn.substring(0, colon) : pn, ParamHelp.filterParam(c, id),
                    0, 100, 1, Math.round(LensFilters.defaultParam(id) * 100f), "", true,
                    () -> PreferenceKeys.lensFilterTune(id).param * 100f,
                    v -> {
                        LensFilters.Tune t = PreferenceKeys.lensFilterTune(id);
                        t.param = Math.round(v) / 100f;
                        settings().set(SettingsManager.SCOPE_GLOBAL, LensFilters.tuneKey(id), t.serialize());
                    }));
        }
        final int fine = LensFilters.tunable(id);
        for (int control : LensFilters.CONTROLS) {
            if ((fine & control) == 0) continue;
            out.add(new TrayAdjust.Param("tune_" + control, LensFilters.controlName(c, id, control), ParamHelp.filterControl(c, id, control),
                    0, 100, 1, 50, "", true,
                    () -> PreferenceKeys.lensFilterTune(id).get(control) * 100f,
                    v -> {
                        LensFilters.Tune t = PreferenceKeys.lensFilterTune(id);
                        t.set(control, Math.round(v) / 100f);
                        settings().set(SettingsManager.SCOPE_GLOBAL, LensFilters.tuneKey(id), t.serialize());
                    }));
        }
        return out;
    }

    /**
     * A film's parameters as the film page has them (FilmSimPreference, the same keys, ranges, steps and defaults): the
     * simulation's strength (written as it moves: the viewfinder mixes it), grain, halation, softness, the film's exposure, its
     * contrast (the measured colour stocks: our black-and-white model has none) and the print's exposure (not for a slide or a
     * reversal film, which are not printed) - the rest stays on the page («Все ›»).
     */
    static List<TrayAdjust.Param> filmParams(Context c, @Nullable com.particlesdevs.photoncamera.processing.color.FilmSim.Stock st) {
        final List<TrayAdjust.Param> out = new ArrayList<>();
        out.add(filmParam(c, "strength", Lang.t(c, "Сила", "Strength"), com.particlesdevs.photoncamera.processing.color.FilmSim.STRENGTH_KEY, 0, 100, 1, 100, " %", true));
        out.add(filmParam(c, "grain", Lang.t(c, "Зерно", "Grain"), com.particlesdevs.photoncamera.processing.color.FilmSim.GRAIN_KEY, 0, 200, 1, 100, " %", false));
        out.add(filmParam(c, "halation", Lang.t(c, "Ореол", "Halation"), com.particlesdevs.photoncamera.processing.color.FilmSim.HALATION_KEY, 0, 400, 1, 100, " %", false));
        out.add(filmParam(c, "softness", Lang.t(c, "Мягкость", "Softness"), com.particlesdevs.photoncamera.processing.color.FilmSim.SOFTNESS_KEY, 0, 300, 1, 100, " %", false));
        out.add(filmParam(c, "exposure", Lang.t(c, "Экспозиция", "Exposure"), com.particlesdevs.photoncamera.processing.color.FilmSim.EXPOSURE_KEY, -2, 2, .1f, 0, " EV", false));
        // only what acts on this stock: the curve's steepness on the measured (colour) stocks, the print on what is printed
        if (st == null || st.spectral != null)
            out.add(filmParam(c, "contrast", Lang.t(c, "Контраст", "Contrast"), com.particlesdevs.photoncamera.processing.color.FilmSim.TONE_KEY, -100, 100, 1, 0, "", false));
        if (st == null || st.type != com.particlesdevs.photoncamera.processing.color.FilmSim.SLIDE && st.type != com.particlesdevs.photoncamera.processing.color.FilmSim.BW_REVERSAL)
            out.add(filmParam(c, "print", Lang.t(c, "Печать", "Print"), com.particlesdevs.photoncamera.processing.color.FilmSim.PRINT_EV_KEY, -1, 1, .05f, 0, " EV", false));
        return out;
    }

    private static TrayAdjust.Param filmParam(Context c, String id, String title, String key, float min, float max, float step, float def,
                                              String unit, boolean live) {
        return new TrayAdjust.Param(id, title, ParamHelp.of(c, key), min, max, step, def, unit, live,
                () -> {
                    try {
                        return Float.parseFloat(settings().getString(SettingsManager.SCOPE_GLOBAL, key, String.valueOf(def)).replace(',', '.'));
                    } catch (RuntimeException e) {
                        return def;
                    }
                },
                // as the film page stores it (FilmSimPreference.slider)
                v -> settings().set(SettingsManager.SCOPE_GLOBAL, key, step < 1 ? String.format(java.util.Locale.ROOT, "%.2f", v) : String.valueOf(Math.round(v))));
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
        filmValues.clear();
        for (int i = 0; i < count; i++) {
            final com.particlesdevs.photoncamera.processing.color.FilmSim.Stock st = i == 0 ? null : com.particlesdevs.photoncamera.processing.color.FilmSim.STOCKS[i - 1];
            final String id = st == null ? "" : st.id;
            filmValues.add(id);
            final boolean on = id.equals(chosen);
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(UiScale.dp(c, 12), 0, UiScale.dp(c, 12), 0);
            card.setBackground(chipBackground(c, on, accent, 14));
            card.setActivated(on);
            TextView brand = text(c, st == null ? Lang.t(c, "Плёнка", "Film") : st.brand.toUpperCase(java.util.Locale.ROOT), 8.5f,
                    on ? (ShadeStyle.INK & 0x00FFFFFF) | 0xB3000000 : ShadeStyle.MUTED);
            brand.setLetterSpacing(.06f);
            card.addView(brand);
            TextView name = text(c, st == null ? Lang.t(c, "Нет", "None") : st.name, 12, on ? ShadeStyle.INK : ShadeStyle.TEXT);
            name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            card.addView(name);
            card.setContentDescription(st == null ? Lang.t(c, "Без плёнки", "No film") : st.brand + " " + st.name);
            card.setTag("lut_tray_film_" + id);
            card.setOnClickListener(v -> {
                TrayMotion.tick(v);
                settings().set(SettingsManager.SCOPE_GLOBAL, com.particlesdevs.photoncamera.processing.color.FilmSim.STOCK_KEY, id);
                // in place: the pill glides to the stock, the row is not built again; its adjustments follow (the height too)
                select(filmRow, filmValues.indexOf(id), 14, open);
                resize(() -> bindFilmAdjust(id));
                centre(filmRow, v);
                bindPill();
                bindTabs();
            });
            TrayMotion.pressable(card);
            filmRow.addView(card, chipParams(c, filmRow, 42));
            if (on) selected = card;
        }
        select(filmRow, filmValues.indexOf(chosen), 14, false);
        bindFilmAdjust(chosen);
        centre(filmRow, selected);
    }

    /** The chosen film's adjustments (none without a film). */
    private void bindFilmAdjust(String id) {
        final com.particlesdevs.photoncamera.processing.color.FilmSim.Stock st = com.particlesdevs.photoncamera.processing.color.FilmSim.stock(id);
        if (id.isEmpty() || st == null) {
            filmAdjust.view.setVisibility(View.GONE);
            return;
        }
        filmAdjust.setParams(filmParams(tray.getContext(), st));
        filmAdjust.view.setVisibility(View.VISIBLE);
    }

    /** A filter card tapped: that filter becomes the camera's (the settings' state). */
    void chooseFilter(String id) {
        settings().set(SettingsManager.SCOPE_GLOBAL, LensFilters.KEY, id);
        if (!id.isEmpty() && PreferenceKeys.lensFilterStrength() <= 0f)
            settings().set(SettingsManager.SCOPE_GLOBAL, LensFilters.STRENGTH_KEY, "50");
        int i = filterValues.indexOf(id);
        if (open && i >= 0 && filterRow.getChildCount() == filterValues.size() && filterValues.equals(java.util.Arrays.asList(filterIds()))) {
            // in place: the pill glides to the filter, its adjustments follow (the height too)
            select(filterRow, i, 17, true);
            resize(() -> bindFilterAdjust(id));
            centre(filterRow, filterRow.getChildAt(i));
        } else rebuildFilters();
        bindPill();
        bindTabs();
    }

    /** A card tapped: that LUT becomes the camera's (the settings' state), the tray shows it chosen. */
    void choose(String value) {
        settings().set(SettingsManager.SCOPE_GLOBAL, FilmLut.KEY, value);
        if (!value.isEmpty() && PreferenceKeys.filmLutStrength() <= 0f)
            settings().set(SettingsManager.SCOPE_GLOBAL, FilmLut.STRENGTH_KEY, "100");
        int i = values.indexOf(value);
        if (open && i >= 0 && row.getChildCount() == values.size()) {
            // in place: the pill glides to the LUT, the strength row follows
            select(row, i, 17, true);
            bindLutState(value, true);
            centre(row, row.getChildAt(i));
        } else rebuild();
        bindPill();
        bindTabs();
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
        if (resizing != null) resizing.cancel();
        tray.animate().cancel();
        TrayMotion.rest(tray);
    }
}
