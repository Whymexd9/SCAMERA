package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.processing.color.FilmLooks;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.settings.PreferenceNumber;
import com.particlesdevs.photoncamera.settings.PreferenceValue;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.ScameraFolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P134 / SIMPLE_UI §4c «Плёночный LUT»: the look the photo goes through (ArkCombine, FILM_LUT) - «Нет», our five film looks
 * (P170, FilmLooks) or a .cube of Download/SCAMERA/LUT. The top of the concept's LUT page: the before / after preview with a
 * draggable cut, three sample pictures («Пейзаж», «Закат», «Шкала цветов»), «Плёнка» - a strip of cards, each the sample
 * through that LUT with its name and hint, «Импорт .cube» (the picker; the file is copied into the folder) and, until the
 * folder is granted, «Открыть папку» (files dropped there by another app are listed then). The strength row and «Что
 * меняется» (LutChangesPreference) follow on the page; all of them redraw when the choice or the strength changes.
 */
public class FilmLutPreference extends Preference implements SharedPreferences.OnSharedPreferenceChangeListener {
    public interface Files {
        String[] list(Context context);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lut-page");
        t.setDaemon(true);
        return t;
    });
    /** Thumbnail size of the strip (the concept's 160 x 120 canvas). */
    static final int THUMB_W = 160, THUMB_H = 120;
    /** The sample shown (shared by the page's parts, kept while the app runs as the concept's lutSample). */
    static int sample;

    @Nullable private Runnable importer, folderOpener;
    private Files files = ScameraFolder.LUTS::list;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private LutCompareView compare;
    @Nullable private LinearLayout strip, samples;
    private final List<String> shown = new ArrayList<>();
    private int generation;

    public FilmLutPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(true);
        setLayoutResource(R.layout.preference_lut_page);
        setSelectable(false);
    }

    public FilmLutPreference(Context context) {
        this(context, null);
    }

    public void setImporter(@Nullable Runnable importer) { this.importer = importer; }
    public void setFolderOpener(@Nullable Runnable folderOpener) { this.folderOpener = folderOpener; }
    /** Tests: the folder's files. */
    public void setFiles(Files files) { this.files = files; }

    public String chosen() {
        return getPersistedString("");
    }

    /** Choose a LUT ("" = none); a LUT chosen at strength 0 comes back at 100 %. */
    public void choose(String name) {
        persistString(name == null ? "" : name);
        FilmLut.forget();
        SharedPreferences prefs = getSharedPreferences();
        if (prefs != null && name != null && !name.isEmpty() && strength(prefs) <= 0f)
            prefs.edit().putString(FilmLut.STRENGTH_KEY, "100").apply();
        notifyChanged();
    }

    @Override
    public CharSequence getSummary() {
        return displayName(getContext(), chosen());
    }

    /** The LUT's name in the UI: «Нет», a look's label or the file's name without .cube. */
    public static String displayName(Context c, @Nullable String value) {
        if (value == null || value.isEmpty()) return Lang.t(c, "Нет", "None");
        String look = FilmLooks.label(c, value);
        if (look != null) return look;
        return value.toLowerCase(java.util.Locale.ROOT).endsWith(".cube") ? value.substring(0, value.length() - 5) : value;
    }

    /** The hint under a card: a look's own, «свой · .cube» for a file. */
    static String hint(Context c, String value) {
        if (value.isEmpty()) return Lang.t(c, "без эффекта", "no effect");
        String h = FilmLooks.hint(c, value);
        return h != null ? h : Lang.t(c, "свой · .cube", "own · .cube");
    }

    /** What the LUT imitates (a look's description; for a file its name and size). */
    static String description(Context c, String value, @Nullable FilmLut lut) {
        String d = FilmLooks.description(c, value);
        if (d != null) return d;
        String size = lut == null ? "" : " · " + Lang.t(c, "3D, " + lut.size + " точек на сторону", "3D, " + lut.size + " points a side");
        String title = lut == null || lut.title == null || lut.title.isEmpty() ? "" : " («" + lut.title + "»)";
        return Lang.t(c, "Свой LUT из файла «" + value + "»", "Own LUT from «" + value + "»") + title + size;
    }

    /** P170: our looks first, then the folder's files. */
    public static String[] choices(String[] files) {
        String[] looks = FilmLooks.values();
        String[] out = new String[looks.length + files.length];
        System.arraycopy(looks, 0, out, 0, looks.length);
        System.arraycopy(files, 0, out, looks.length, files.length);
        return out;
    }

    /** The strength 0..1 as stored (a String of the slider, or a float of an old backup). */
    static float strength(SharedPreferences prefs) {
        try {
            return (float) Math.max(0, Math.min(100, PreferenceNumber.read(PreferenceValue.get(prefs, FilmLut.STRENGTH_KEY), 100))) / 100f;
        } catch (RuntimeException e) {
            return 1f;
        }
    }

    // ───── the LUTs and sample pictures, computed off the main thread and kept

    private static final Map<String, FilmLut> LUTS = new LinkedHashMap<String, FilmLut>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, FilmLut> e) { return size() > 12; }
    };
    private static final Map<String, int[]> PIXELS = new LinkedHashMap<String, int[]>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, int[]> e) { return size() > 48; }
    };

    /** A LUT by its value, parsed once (null: none or unreadable). */
    @Nullable
    static FilmLut lut(Context c, String value) {
        if (value == null || value.isEmpty()) return null;
        synchronized (LUTS) {
            if (LUTS.containsKey(value)) return LUTS.get(value);
        }
        FilmLut l = FilmLut.load(c, value);
        synchronized (LUTS) {
            LUTS.put(value, l);
        }
        return l;
    }

    /** A new import of the same name: parse it again. */
    public static void forgetFile(String value) {
        synchronized (LUTS) {
            LUTS.remove(value);
        }
        synchronized (PIXELS) {
            PIXELS.keySet().removeIf(k -> k.endsWith("|" + value));
        }
    }

    /** Sample {@code s} at w x h through {@code value} at full strength ("" = the sample itself). */
    static int[] pixels(Context c, int s, int w, int h, String value) {
        String key = s + "|" + w + "x" + h + "|" + value;
        synchronized (PIXELS) {
            int[] p = PIXELS.get(key);
            if (p != null) return p;
        }
        int[] out;
        if (value.isEmpty()) {
            Bitmap b = LutSamples.draw(s, w, h);
            out = new int[w * h];
            b.getPixels(out, 0, w, 0, 0, w, h);
            b.recycle();
        } else {
            out = LutSamples.apply(pixels(c, s, w, h, ""), lut(c, value));
        }
        synchronized (PIXELS) {
            PIXELS.put(key, out);
        }
        return out;
    }

    static Bitmap bitmap(int[] px, int w, int h) {
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    // ───── the page

    @Override
    public void onAttached() {
        super.onAttached();
        SharedPreferences prefs = getSharedPreferences();
        if (prefs != null) prefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDetached() {
        SharedPreferences prefs = getSharedPreferences();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onDetached();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, @Nullable String key) {
        if (FilmLut.STRENGTH_KEY.equals(key)) main.post(this::redrawPreview);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        holder.setDividerAllowedAbove(false);
        holder.setDividerAllowedBelow(false);
        FrameLayout host = (FrameLayout) holder.itemView;   // preference_lut_page: the host is the row itself
        host.removeAllViews();
        host.addView(build(getContext()), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View build(Context c) {
        int accent = SettingsStyle.accent(c);
        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        // before / after
        compare = new LutCompareView(c);
        page.addView(compare, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // the samples
        samples = new LinearLayout(c);
        samples.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < LutSamples.COUNT; i++) {
            TextView b = SettingsStyle.text(c, LutSamples.name(c, i), 12.5f, UiTokens.TEXT);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(UiTokens.dp(c, 34));
            b.setPadding(UiTokens.dp(c, 12), 0, UiTokens.dp(c, 12), 0);
            b.setTag("lut_sample_" + i);
            final int index = i;
            b.setOnClickListener(v -> {
                sample = index;
                paintSamples();
                fillStrip();
                redrawPreview();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(UiTokens.dp(c, 6));
            samples.addView(b, lp);
        }
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = UiTokens.dp(c, 10);
        page.addView(samples, sp);
        paintSamples();
        // «Плёнка»: the strip
        TextView title = groupTitle(c, Lang.t(c, "Плёнка", "Film"), accent);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = UiTokens.dp(c, 16);
        tp.bottomMargin = UiTokens.dp(c, 6);
        page.addView(title, tp);
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        strip = new LinearLayout(c);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(UiTokens.dp(c, 2), UiTokens.dp(c, 2), UiTokens.dp(c, 2), UiTokens.dp(c, 6));
        scroll.addView(strip, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fillStrip();
        compare.post(this::redrawPreview);
        return page;
    }

    /** The concept's .gt: 11.5sp, 600, upper case, the accent. */
    static TextView groupTitle(Context c, String text, int accent) {
        TextView t = SettingsStyle.text(c, text.toUpperCase(java.util.Locale.ROOT), 11.5f, accent);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setLetterSpacing(.09f);
        t.setPadding(UiTokens.dp(c, 6), UiTokens.dp(c, 4), UiTokens.dp(c, 6), 0);
        return t;
    }

    private void paintSamples() {
        if (samples == null) return;
        Context c = getContext();
        int accent = SettingsStyle.accent(c);
        for (int i = 0; i < samples.getChildCount(); i++) {
            TextView b = (TextView) samples.getChildAt(i);
            boolean on = i == sample;
            b.setBackground(UiTokens.shape(c, on ? accent : UiTokens.CARD, on ? accent : UiTokens.LINE, 999));
            b.setTextColor(on ? UiTokens.INK : UiTokens.TEXT);
            b.setSelected(on);
        }
    }

    /** Every LUT as a card, then «Импорт .cube» and, until the folder is open to the app, «Открыть папку». */
    private void fillStrip() {
        if (strip == null) return;
        Context c = getContext();
        int accent = SettingsStyle.accent(c);
        String chosen = chosen();
        shown.clear();
        shown.add("");
        String[] list;
        try {
            list = files.list(c);
        } catch (RuntimeException e) {
            list = new String[0];
        }
        for (String v : choices(list)) shown.add(v);
        strip.removeAllViews();
        int cardW = UiTokens.dp(c, 92);
        int gen = ++generation;
        final int s = sample;
        View selected = null;
        for (String value : shown) {
            boolean on = value.equals(chosen);
            LinearLayout card = card(c, on, accent);
            ImageView thumb = new ImageView(c);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(0xFF000000);
            float r = UiTokens.dp(c, 10);
            thumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            thumb.setClipToOutline(true);
            card.addView(thumb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round((cardW - UiTokens.dp(c, 8)) * .75f)));
            TextView name = SettingsStyle.text(c, displayName(c, value), 12, UiTokens.TEXT);
            name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            name.setPadding(UiTokens.dp(c, 3), UiTokens.dp(c, 5), UiTokens.dp(c, 3), 0);
            name.setMaxLines(2);
            card.addView(name);
            TextView hint = SettingsStyle.text(c, hint(c, value), 10.5f, UiTokens.MUTED);
            hint.setPadding(UiTokens.dp(c, 3), UiTokens.dp(c, 3), UiTokens.dp(c, 3), 0);
            hint.setMaxLines(2);
            card.addView(hint);
            card.setTag("lut_card_" + value);
            card.setContentDescription(displayName(c, value) + ": " + hint(c, value));
            card.setSelected(on);
            card.setOnClickListener(v -> select(value));
            strip.addView(card, stripParams(c, cardW));
            if (on) selected = card;
            WORKER.execute(() -> {
                int[] px = pixels(c, s, THUMB_W, THUMB_H, value);
                Bitmap b = bitmap(px, THUMB_W, THUMB_H);
                main.post(() -> { if (gen == generation) thumb.setImageBitmap(b); });
            });
        }
        strip.addView(actionTile(c, R.drawable.ui_ic_plus, Lang.t(c, "Импорт .cube", "Import .cube"), "lut_import", importer), stripParams(c, cardW));
        if (folderOpener != null && ScameraFolder.LUTS.grantedTree(c) == null)
            strip.addView(actionTile(c, R.drawable.ui_ic_folder, Lang.t(c, "Открыть папку", "Open the folder"), "lut_open_folder", folderOpener),
                    stripParams(c, cardW));
        View target = selected;
        if (target != null) strip.post(() -> {
            View scroll = (View) strip.getParent();
            if (scroll instanceof HorizontalScrollView) {
                int l = target.getLeft(), r = target.getRight(), x = scroll.getScrollX(), w = scroll.getWidth();
                if (l < x) scroll.scrollTo(Math.max(0, l - UiTokens.dp(c, 4)), 0);
                else if (r > x + w) scroll.scrollTo(r - w + UiTokens.dp(c, 4), 0);
            }
        });
    }

    private static LinearLayout.LayoutParams stripParams(Context c, int w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMarginEnd(UiTokens.dp(c, 8));
        return lp;
    }

    private static LinearLayout card(Context c, boolean on, int accent) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiTokens.dp(c, 4), UiTokens.dp(c, 4), UiTokens.dp(c, 4), UiTokens.dp(c, 6));
        GradientDrawable bg = UiTokens.shape(c, UiTokens.CARD, 0, 14);
        bg.setStroke(UiTokens.dp(c, on ? 2 : 1), on ? accent : UiTokens.LINE);
        card.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), bg, null));
        card.setFocusable(true);
        return card;
    }

    /** The concept's .lutc.imp: a dashed tile with an icon and a caption. */
    private static View actionTile(Context c, int icon, String text, String tag, @Nullable Runnable action) {
        LinearLayout tile = new LinearLayout(c);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setMinimumHeight(UiTokens.dp(c, 120));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiTokens.dp(c, 14));
        bg.setStroke(UiTokens.dp(c, 1), UiTokens.LINE, UiTokens.dp(c, 5), UiTokens.dp(c, 4));
        tile.setBackground(bg);
        ImageView i = new ImageView(c);
        i.setImageResource(icon);
        i.setImageTintList(ColorStateList.valueOf(UiTokens.MUTED));
        tile.addView(i, new LinearLayout.LayoutParams(UiTokens.dp(c, 24), UiTokens.dp(c, 24)));
        TextView t = SettingsStyle.text(c, text, 10.5f, UiTokens.MUTED);
        t.setGravity(Gravity.CENTER);
        t.setPadding(UiTokens.dp(c, 4), UiTokens.dp(c, 5), UiTokens.dp(c, 4), 0);
        tile.addView(t);
        tile.setTag(tag);
        tile.setContentDescription(text);
        tile.setEnabled(action != null);
        tile.setAlpha(action != null ? 1f : SettingsStyle.DIMMED);
        tile.setOnClickListener(v -> { if (action != null) action.run(); });
        return tile;
    }

    /** A card tapped: that LUT is the photo's (and the viewfinder's) from now on. */
    void select(String value) {
        choose(value);
    }

    /** The preview at the current sample, LUT and strength. */
    void redrawPreview() {
        LutCompareView view = compare;
        if (view == null || view.getWidth() == 0) return;
        Context c = getContext();
        SharedPreferences prefs = getSharedPreferences();
        float k = prefs == null ? 1f : strength(prefs);
        String value = chosen();
        int w = Math.min(720, view.getWidth()), h = Math.round(w * .75f);
        final int s = sample;
        WORKER.execute(() -> {
            int[] base = pixels(c, s, w, h, "");
            int[] full = value.isEmpty() ? base : pixels(c, s, w, h, value);
            Bitmap before = bitmap(base, w, h);
            Bitmap after = bitmap(value.isEmpty() || k <= 0f ? base : k >= 1f ? full : LutSamples.mix(base, full, k), w, h);
            main.post(() -> view.setImages(before, after));
        });
    }
}
