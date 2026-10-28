package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.color.FilmSim;
import com.particlesdevs.photoncamera.settings.PreferenceValue;
import com.particlesdevs.photoncamera.ui.settings.PageReset;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P281 «Плёночная симуляция» (FilmSim): the page in the LUT page's style - the before / after preview on the three samples, then
 * the tabs «Плёнка» (the stocks: «Нет» and every film as a card with its maker, name and kind), «Печать» (the papers and print
 * films a negative is printed on), «Формат» (the frame's size: the grain, the halation and the film's softness follow it) and
 * «Эффекты» (grain, halation, softness, the film's and the print's exposure, the print's tone, the strength). Every choice is
 * stored at once and redraws the preview; the photo and the viewfinder take it at the next frame (FilmChain).
 * P380 (owner, 2026-10-28: the pair with Unspektrawesome's SPEKTRA): «Эффекты» starts with the film's camera diffusion (Нет /
 * Glimmerglass / Black Pro-Mist / Pro-Mist / CineBloom) and its «Сила фильтра» (the grade 0 .. 2, stored x 100), and has
 * «Цветоразделение (DIR)» (the DIR couplers' amount, 0 .. 100 %) for the measured colour stocks; «Формат» lists 65 mm and IMAX 70.
 */
public class FilmSimPreference extends Preference implements SharedPreferences.OnSharedPreferenceChangeListener, PageReset.Resettable {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "film-sim-page");
        t.setDaemon(true);
        return t;
    });
    /** The tab shown (kept while the app runs). */
    static int tab;

    private final Handler main = new Handler(Looper.getMainLooper());
    /** P357: the sliders' resets of the shown tab («Сбросить всё»). */
    private final java.util.List<Runnable> sliderResets = new java.util.ArrayList<>();
    @Nullable private LutCompareView compare;
    @Nullable private LinearLayout samples, tabs, content;
    private int generation;

    public FilmSimPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(true);
        setLayoutResource(R.layout.preference_lut_page);
        setSelectable(false);
    }

    public FilmSimPreference(Context context) {
        this(context, null);
    }

    private String stored(String key, String fallback) {
        final SharedPreferences p = getSharedPreferences();
        if (p == null) return fallback;
        final Object v = PreferenceValue.get(p, key);
        return v == null ? fallback : v.toString();
    }

    private void store(String key, String value) {
        final SharedPreferences p = getSharedPreferences();
        if (p != null) p.edit().putString(key, value).apply();
    }

    /**
     * The page reset (audit 2026-10-27: it changed nothing here and said «Всё уже по умолчанию»): the film, the print, the format
     * and every effect's slider back to their defaults (their stored values go); true when one was stored.
     */
    @Override
    public boolean resetToDefault() {
        final SharedPreferences p = getSharedPreferences();
        if (p == null) return false;
        final boolean any = resetKeys(p);
        fill();
        redrawPreview();
        return any;
    }

    /**
     * The page reset's values (P382: the LUT tray's «Плёнка» tab double tap too): every key of the film - the stock, the print, the
     * format and every effect - loses its stored value; true when one was stored (other than «no film»).
     */
    public static boolean resetKeys(SharedPreferences p) {
        boolean stored = false, any = false;
        final java.util.Map<String, ?> all = p.getAll();
        final SharedPreferences.Editor e = p.edit();
        for (String k : FilmSim.KEYS) {
            if (!all.containsKey(k)) continue;
            e.remove(k);
            stored = true;
            // «no film» stored is the default already
            if (!FilmSim.STOCK_KEY.equals(k) || !String.valueOf(all.get(k)).isEmpty()) any = true;
        }
        if (stored) e.apply();
        return any;
    }

    @Override
    public CharSequence getSummary() {
        final FilmSim.Stock s = FilmSim.stock(stored(FilmSim.STOCK_KEY, ""));
        return s == null ? Lang.t(getContext(), "Выключена", "Off") : s.brand + " " + s.name;
    }

    @Override
    public void onAttached() {
        super.onAttached();
        final SharedPreferences prefs = getSharedPreferences();
        if (prefs != null) prefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDetached() {
        final SharedPreferences prefs = getSharedPreferences();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onDetached();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, @Nullable String key) {
        if (key != null && key.startsWith("pref_film_sim_") || FilmLut.KEY.equals(key) || FilmLut.STRENGTH_KEY.equals(key))
            main.post(this::redrawPreview);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        holder.setDividerAllowedAbove(false);
        holder.setDividerAllowedBelow(false);
        final FrameLayout host = (FrameLayout) holder.itemView;
        host.removeAllViews();
        host.addView(build(getContext()), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View build(Context c) {
        final LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        compare = new LutCompareView(c);
        page.addView(compare, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        samples = chips(c, new String[]{LutSamples.name(c, 0), LutSamples.name(c, 1), LutSamples.name(c, 2)}, "film_sample_", i -> {
            FilmLutPreference.sample = i;
            paintChips(samples, FilmLutPreference.sample);
            redrawPreview();
        });
        page.addView(samples, margin(c, 10));
        paintChips(samples, FilmLutPreference.sample);
        tabs = chips(c, new String[]{Lang.t(c, "Плёнка", "Film"), Lang.t(c, "Печать", "Print"), Lang.t(c, "Формат", "Format"),
                Lang.t(c, "Эффекты", "Effects")}, "film_tab_", i -> {
            tab = i;
            paintChips(tabs, tab);
            fill();
        });
        page.addView(tabs, margin(c, 16));
        paintChips(tabs, tab);
        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        page.addView(content, margin(c, 10));
        fill();
        compare.post(this::redrawPreview);
        return page;
    }

    private static LinearLayout.LayoutParams margin(Context c, int top) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiTokens.dp(c, top);
        return lp;
    }

    private LinearLayout chips(Context c, String[] names, String tag, java.util.function.IntConsumer onPick) {
        final HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < names.length; i++) {
            final TextView b = SettingsStyle.text(c, names[i], 12.5f, UiTokens.TEXT);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(UiTokens.dp(c, 34));
            b.setPadding(UiTokens.dp(c, 14), 0, UiTokens.dp(c, 14), 0);
            b.setTag(tag + i);
            final int index = i;
            b.setOnClickListener(v -> onPick.accept(index));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(UiTokens.dp(c, 6));
            row.addView(b, lp);
        }
        scroll.addView(row);
        final LinearLayout wrap = new LinearLayout(c);
        wrap.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.setTag(row);
        return wrap;
    }

    private void paintChips(@Nullable LinearLayout wrap, int on) {
        if (wrap == null) return;
        final Context c = getContext();
        final int accent = SettingsStyle.accent(c);
        final LinearLayout row = (LinearLayout) wrap.getTag();
        for (int i = 0; i < row.getChildCount(); i++) {
            final TextView b = (TextView) row.getChildAt(i);
            final boolean sel = i == on;
            b.setBackground(UiTokens.shape(c, sel ? accent : UiTokens.CARD, sel ? accent : UiTokens.LINE, 999));
            b.setTextColor(sel ? UiTokens.INK : UiTokens.TEXT);
            b.setSelected(sel);
        }
    }

    /** The tab's content. */
    private void fill() {
        if (content == null) return;
        final Context c = getContext();
        content.removeAllViews();
        sliderResets.clear();
        final String stockId = stored(FilmSim.STOCK_KEY, "");
        final FilmSim.Stock stock = FilmSim.stock(stockId);
        switch (tab) {
            case 0: {
                final String[] ids = new String[FilmSim.STOCKS.length + 1];
                final String[][] text = new String[ids.length][];
                ids[0] = "";
                text[0] = new String[]{"", Lang.t(c, "Нет", "None"), Lang.t(c, "без плёнки", "no film")};
                for (int i = 0; i < FilmSim.STOCKS.length; i++) {
                    final FilmSim.Stock s = FilmSim.STOCKS[i];
                    ids[i + 1] = s.id;
                    text[i + 1] = new String[]{s.brand, s.name, FilmSim.typeName(c, s)};
                }
                content.addView(strip(c, ids, text, stockId, id -> store(FilmSim.STOCK_KEY, id), "film_stock_"));
                note(c, stock == null ? Lang.t(c, "Выберите плёнку: снимок пройдёт через её слои, проявку и печать. Выбранный LUT накладывается после неё.",
                        "Choose a film: the photo goes through its layers, development and print. The chosen LUT is applied after it.")
                        : FilmSim.typeName(c, stock) + Lang.t(c, " · зерно RMS " + stock.rms, " · grain RMS " + stock.rms));
                break;
            }
            case 1: {
                final String[] ids = new String[FilmSim.PRINTS.length];
                final String[][] text = new String[ids.length][];
                for (int i = 0; i < ids.length; i++) {
                    final FilmSim.Print p = FilmSim.PRINTS[i];
                    ids[i] = p.id;
                    text[i] = new String[]{p.brand, p.name, p.film ? Lang.t(c, "кинопечать", "print film") : Lang.t(c, "бумага", "paper")};
                }
                content.addView(strip(c, ids, text, FilmSim.print(stored(FilmSim.PRINT_KEY, ""), stock).id,
                        id -> store(FilmSim.PRINT_KEY, id), "film_print_"));
                if (stock != null && stock.reversal())
                    note(c, Lang.t(c, "Обращаемая плёнка (слайд) не печатается: её показывают на просвет, печать не влияет.",
                            "A reversal film (slide) is not printed: it is projected, the print does not apply."));
                break;
            }
            case 2: {
                final String[] ids = new String[FilmSim.FORMATS.length];
                final String[][] text = new String[ids.length][];
                for (int i = 0; i < ids.length; i++) {
                    final FilmSim.Format f = FilmSim.FORMATS[i];
                    ids[i] = f.id;
                    text[i] = new String[]{f.longMm > 0 ? Lang.t(c, "Формат кадра", "Frame format") : "", FilmSim.formatName(c, f), FilmSim.formatHint(c, f)};
                }
                content.addView(strip(c, ids, text, FilmSim.format(stored(FilmSim.FORMAT_KEY, "135")).id, id -> store(FilmSim.FORMAT_KEY, id), "film_format_"));
                note(c, Lang.t(c, "Чем меньше кадр, тем крупнее зерно, шире ореол и мягче картинка: всё считается в микрометрах плёнки.",
                        "The smaller the frame, the larger the grain, the wider the halation and the softer the picture: all in micrometres of film."));
                break;
            }
            default: {
                // P380: the film's camera diffusion first (spektrafilm's order: the camera's filter, then the film)
                final String diffusion = storedDiffusion();
                final String[] ids = FilmSim.DIFFUSIONS;
                final String[] names = new String[ids.length];
                int on = 0;
                for (int i = 0; i < ids.length; i++) {
                    names[i] = FilmSim.diffusionName(c, ids[i]);
                    if (ids[i].equals(diffusion)) on = i;
                }
                final TextView label = SettingsStyle.text(c, Lang.t(c, "Диффузия", "Diffusion"), 14.5f, UiTokens.TEXT);
                label.setPadding(UiTokens.dp(c, 6), UiTokens.dp(c, 2), UiTokens.dp(c, 6), UiTokens.dp(c, 6));
                content.addView(label);
                final String help = com.particlesdevs.photoncamera.settings.ParamHelp.of(c, FilmSim.DIFFUSION_KEY);
                if (help != null) label.setOnLongClickListener(v -> { showHelp(c, label.getText(), help); return true; });
                final LinearLayout[] diffusionChips = new LinearLayout[1];
                diffusionChips[0] = chips(c, names, "film_diffusion_", i -> {
                    store(FilmSim.DIFFUSION_KEY, ids[i]);
                    paintChips(diffusionChips[0], i);
                    fill();
                });
                content.addView(diffusionChips[0]);
                paintChips(diffusionChips[0], on);
                if (!ids[on].isEmpty())
                    slider(c, FilmSim.DIFFUSION_STRENGTH_KEY, Lang.t(c, "Сила фильтра", "Filter strength"), 0, 2, .05f,
                            FilmSim.DIFFUSION_DEFAULT, "", 100);
                slider(c, FilmSim.GRAIN_KEY, Lang.t(c, "Зерно", "Grain"), 0, 200, 1, 100, "%");
                slider(c, FilmSim.HALATION_KEY, Lang.t(c, "Ореол", "Halation"), 0, 400, 1, 100, "%");
                slider(c, FilmSim.SOFTNESS_KEY, Lang.t(c, "Мягкость плёнки", "Film softness"), 0, 300, 1, 100, "%");
                slider(c, FilmSim.EXPOSURE_KEY, Lang.t(c, "Экспозиция плёнки", "Film exposure"), -2, 2, .1f, 0, " EV");
                slider(c, FilmSim.PRINT_EV_KEY, Lang.t(c, "Экспозиция печати", "Print exposure"), -1, 1, .05f, 0, " EV");
                slider(c, FilmSim.TONE_KEY, Lang.t(c, "Контраст плёнки", "Film contrast"), -100, 100, 1, 0, "");
                slider(c, FilmSim.PRINT_M_KEY, Lang.t(c, "Фильтр M увеличителя (пурпурнее … зеленее)", "Enlarger M filter (more magenta … greener)"),
                        -30, 30, 1, 0, " CC");
                slider(c, FilmSim.PRINT_Y_KEY, Lang.t(c, "Фильтр Y увеличителя (желтее … синее)", "Enlarger Y filter (more yellow … bluer)"),
                        -30, 30, 1, 0, " CC");
                // P380: the DIR couplers' amount (the measured colour stocks: our black-and-white model has none)
                if (stock == null || stock.spectral != null)
                    slider(c, FilmSim.DIR_KEY, Lang.t(c, "Цветоразделение (DIR)", "Colour separation (DIR)"), 0, 100, 1, 100, "%");
                slider(c, FilmSim.STRENGTH_KEY, Lang.t(c, "Сила симуляции", "Simulation strength"), 0, 100, 1, 100, "%");
                // P357: every slider of the tab back to its default
                final TextView all = SettingsStyle.text(c, Lang.t(c, "↺ Сбросить все ползунки", "↺ Reset all sliders"), 13.5f, SettingsStyle.accent(c));
                all.setTag("film_reset_all");
                all.setPadding(UiTokens.dp(c, 6), UiTokens.dp(c, 12), UiTokens.dp(c, 6), UiTokens.dp(c, 4));
                all.setOnClickListener(v -> {
                    for (Runnable r : new java.util.ArrayList<>(sliderResets)) r.run();
                    redrawPreview();
                });
                content.addView(all);
                // P369: approximate - the preview's film works on a finished sample picture, the photo's on the scene light
                note(c, Lang.t(c, "Превью показывает диффузию, зерно, ореол и мягкость примерно как на фото в полном размере (кусок кадра 1:1). В видоискателе — цвет, тон и диффузия плёнки.",
                        "The preview shows the diffusion, grain, halation and softness roughly as on the full-size photo (a 1:1 crop). The viewfinder shows the film's colour, tone and diffusion."));
            }
        }
    }

    private void note(Context c, String s) {
        final TextView t = SettingsStyle.text(c, s, 12, UiTokens.MUTED);
        t.setLineSpacing(UiTokens.dp(c, 3), 1);
        t.setPadding(UiTokens.dp(c, 6), UiTokens.dp(c, 10), UiTokens.dp(c, 6), 0);
        content.addView(t);
    }

    /** A strip of cards (maker, name, kind), the chosen one outlined; a tap stores it. */
    private View strip(Context c, String[] ids, String[][] text, String chosen, java.util.function.Consumer<String> onPick, String tag) {
        final int accent = SettingsStyle.accent(c);
        final HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(UiTokens.dp(c, 2), UiTokens.dp(c, 2), UiTokens.dp(c, 2), UiTokens.dp(c, 6));
        View selected = null;
        for (int i = 0; i < ids.length; i++) {
            final boolean on = ids[i].equals(chosen);
            final LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(UiTokens.dp(c, 12), UiTokens.dp(c, 10), UiTokens.dp(c, 12), UiTokens.dp(c, 10));
            final GradientDrawable bg = UiTokens.shape(c, UiTokens.CARD, 0, 14);
            bg.setStroke(UiTokens.dp(c, on ? 2 : 1), on ? accent : UiTokens.LINE);
            card.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), bg, null));
            final TextView brand = SettingsStyle.text(c, text[i][0].toUpperCase(Locale.ROOT), 10.5f, on ? accent : UiTokens.MUTED);
            brand.setLetterSpacing(.06f);
            card.addView(brand);
            final TextView name = SettingsStyle.text(c, text[i][1], 15, UiTokens.TEXT);
            name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            name.setPadding(0, UiTokens.dp(c, 4), 0, 0);
            name.setMaxLines(2);
            card.addView(name);
            final View gap = new View(c);
            card.addView(gap, new LinearLayout.LayoutParams(1, 0, 1));
            final TextView kind = SettingsStyle.text(c, text[i][2], 10.5f, UiTokens.MUTED);
            kind.setMaxLines(2);
            card.addView(kind);
            card.setMinimumHeight(UiTokens.dp(c, 108));
            card.setTag(tag + ids[i]);
            card.setContentDescription(text[i][0] + " " + text[i][1] + ". " + text[i][2]);
            card.setSelected(on);
            final String id = ids[i];
            card.setOnClickListener(v -> {
                onPick.accept(id);
                fill();
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(UiTokens.dp(c, 132), ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMarginEnd(UiTokens.dp(c, 8));
            row.addView(card, lp);
            if (on) selected = card;
        }
        scroll.addView(row);
        final View target = selected;
        if (target != null) row.post(() -> {
            final int l = target.getLeft(), r = target.getRight(), x = scroll.getScrollX(), w = scroll.getWidth();
            if (l < x) scroll.scrollTo(Math.max(0, l - UiTokens.dp(c, 4)), 0);
            else if (r > x + w) scroll.scrollTo(r - w + UiTokens.dp(c, 4), 0);
        });
        return scroll;
    }

    /** P380: the stored diffusion (none when no film is on to read it from). */
    private String storedDiffusion() {
        final String id = stored(FilmSim.DIFFUSION_KEY, "");
        return com.particlesdevs.photoncamera.processing.color.LensFilters.index(id) > 0 ? id : "";
    }

    /** A setting's slider row: the title, the value, the bar; stored when let go. */
    private void slider(Context c, String key, String title, float min, float max, float step, float def, String unit) {
        slider(c, key, title, min, max, step, def, unit, 1);
    }

    /**
     * A slider showing {@code min .. max} that stores the value x {@code scale} (P380: the diffusion's grade 0 .. 2 stored as
     * 0 .. 200; 1 the others, stored as shown).
     */
    private void slider(Context c, String key, String title, float min, float max, float step, float def, String unit, int scale) {
        final int accent = SettingsStyle.accent(c);
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(UiTokens.dp(c, 14), UiTokens.dp(c, 10), UiTokens.dp(c, 14), UiTokens.dp(c, 6));
        row.setBackground(UiTokens.shape(c, UiTokens.CARD, UiTokens.LINE, 14));
        final LinearLayout head = new LinearLayout(c);
        final TextView t = SettingsStyle.text(c, title, 14.5f, UiTokens.TEXT);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView value = SettingsStyle.text(c, "", 14, accent);
        head.addView(value);
        row.addView(head);
        float v;
        try { v = Float.parseFloat(stored(key, String.valueOf(def * scale)).replace(',', '.')) / scale; } catch (NumberFormatException e) { v = def; }
        // stored as the page has always stored it: two decimals below a step of 1, else a whole number (x scale)
        final java.util.function.Function<Float, String> storedText = x -> step * scale < 1
                ? String.format(Locale.ROOT, "%.2f", x * scale) : String.valueOf(Math.round(x * scale));
        final SeekBar bar = new SeekBar(c);
        bar.setTag("film_" + key);
        bar.setMax(Math.max(1, Math.round((max - min) / step)));
        bar.setProgress(Math.round((Math.max(min, Math.min(max, v)) - min) / step));
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        final java.util.function.IntFunction<String> text = p -> {
            final float x = min + p * step;
            // P380: a value of no sign (the diffusion's grade) without a plus
            return (step < 1 ? String.format(Locale.ROOT, step < .1f ? (min < 0 ? "%+.2f" : "%.2f") : (min < 0 ? "%+.1f" : "%.1f"), x)
                    : String.valueOf(Math.round(x))) + unit;
        };
        value.setText(text.apply(bar.getProgress()));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { value.setText(text.apply(p)); }
            @Override public void onStartTrackingTouch(SeekBar s) { s.getParent().requestDisallowInterceptTouchEvent(true); }
            @Override public void onStopTrackingTouch(SeekBar s) {
                final float x = min + s.getProgress() * step;
                store(key, storedText.apply(x));
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTokens.dp(c, 36)));
        // P357: a double tap puts the default back
        final Runnable reset = () -> {
            bar.setProgress(Math.round((def - min) / step));
            store(key, storedText.apply(def));
        };
        com.particlesdevs.photoncamera.ui.controls.DoubleTapReset.attach(bar, reset);
        com.particlesdevs.photoncamera.ui.controls.DoubleTapReset.attach(row, reset);
        sliderResets.add(reset);
        // owner, 2026-10-28 («добавь описание для параметров по длинному зажатию»): a long press tells what it does
        final String help = com.particlesdevs.photoncamera.settings.ParamHelp.of(c, key);
        if (help != null) {
            final View.OnLongClickListener explain = held -> { showHelp(c, title, help); return true; };
            row.setOnLongClickListener(explain);
            bar.setOnLongClickListener(explain);
        }
        final LinearLayout.LayoutParams lp = margin(c, 8);
        content.addView(row, lp);
    }

    /** A parameter's description in the settings' help sheet (SettingHelp's look). */
    static void showHelp(Context c, CharSequence title, String text) {
        final LinearLayout body = SettingsStyle.sheetBody(c, title, null);
        body.setTag("film_param_help");
        final TextView t = SettingsStyle.text(c, text, 15, UiTokens.TEXT);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(UiTokens.dp(c, 4), UiTokens.dp(c, 4), UiTokens.dp(c, 4), UiTokens.dp(c, 8));
        body.addView(t);
        final TextView tip = SettingsStyle.text(c, Lang.t(c, "Двойное касание ползунка возвращает значение по умолчанию.",
                "A double tap on the slider puts the default back."), 13, UiTokens.MUTED);
        tip.setPadding(UiTokens.dp(c, 4), 0, UiTokens.dp(c, 4), UiTokens.dp(c, 4));
        body.addView(tip);
        SettingsStyle.sheet(c, body).show();
    }

    /** The preview: the sample, and the sample through the simulation and the chosen LUT. */
    void redrawPreview() {
        final LutCompareView view = compare;
        if (view == null || view.getWidth() == 0) return;
        final Context c = getContext();
        final int w = Math.min(720, view.getWidth()), h = Math.round(w * .75f), s = FilmLutPreference.sample, gen = ++generation;
        WORKER.execute(() -> {
            if (gen != generation) return;
            final int[] base = FilmLutPreference.pixels(c, s, w, h, "");
            final com.particlesdevs.photoncamera.processing.color.FilmChain.State st = com.particlesdevs.photoncamera.processing.color.FilmChain.current();
            final FilmLut lut = st.key.isEmpty() || st.strength <= 0f ? null
                    : com.particlesdevs.photoncamera.processing.color.FilmChain.lut(c.getApplicationContext(), st.key);
            // P289: the film's softness, halation (the light) and grain (the finished picture) as on the photo at full size
            final FilmSim.Settings set = FilmSim.current();
            final int[] lit = set == null ? base : com.particlesdevs.photoncamera.processing.color.FilmEffects.light(base, w, h, set);
            int[] full = lut == null ? lit : LutSamples.apply(lit, lut);
            if (lut != null && st.strength < 1f) full = LutSamples.mix(lit, full, st.strength);
            if (set != null) full = com.particlesdevs.photoncamera.processing.color.FilmEffects.grain(full, w, h, set);
            final Bitmap before = FilmLutPreference.bitmap(base, w, h);
            final Bitmap after = FilmLutPreference.bitmap(full, w, h);
            main.post(() -> { if (gen == generation) view.setImages(before, after); });
        });
    }
}
