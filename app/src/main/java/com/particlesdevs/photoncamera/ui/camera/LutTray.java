package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SIMPLE_UI §1, the owner's requirement «менять LUT без меню и настроек»: the LUT pill in the viewfinder's top-left corner
 * (28dp, translucent dark, the film icon and the LUT's name - «LUT» with none; the name in the accent while a LUT is on,
 * the pill in the accent while the tray is open) and the LUT tray in the Pro panel's place above row 1 (Pro and the tray
 * close each other): a snap row of every LUT («Нет», the five film looks, every .cube of Download/SCAMERA/LUT), each a
 * 54 x 40dp thumbnail of the current viewfinder frame through that LUT (up to 30 times a second, off the main thread) with
 * its name; the strength slider; «Все LUT ›» to the settings' LUT row. The choice is the same state as the settings'
 * (pref_film_lut / pref_film_lut_strength): the live viewfinder (LiveRawRenderer) and the photo (ArkCombine) follow it.
 * P228: under the strength a second row of the lens filters («Нет», Black Pro-Mist ... Infrared), each a thumbnail of the same
 * frame through that filter at its own fine tune; a card is the settings' pref_lens_filter. The pill names the filter after the
 * LUT («Кино 500 · Pro-Mist»).
 */
public final class LutTray {
    /** The concept's tray: the sheet colour at 94 %. */
    static final int TRAY_FILL = 0xF015191C, PILL_FILL = 0x9E0D0F12;
    /**
     * Owner, 2026-10-18 («сделай у этих превью фпс повыше», twice): the thumbnails follow the frame up to 30 times a second (they
     * were redrawn twice a second): the next frame is taken when the last one is drawn, at most every THUMB_PERIOD_MS; only the
     * cards on screen are computed (each card once more when it has never been drawn), on several cores at once (background
     * priority, the viewfinder keeps its own), into two bitmaps a card that take turns (no new bitmaps a frame).
     */
    static final long THUMB_PERIOD_MS = 33;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2)), r -> {
                Thread t = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "lut-tray-thumb");
                t.setDaemon(true);
                return t;
            });
    private boolean rendering;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lut-tray");
        t.setDaemon(true);
        return t;
    });

    private final TextView pill;
    private final LinearLayout tray;
    private final LinearLayout row;
    private final SeekBar strength;
    private final TextView strengthValue;
    @Nullable private final SurfaceView preview;
    private final Runnable openAll;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> values = new ArrayList<>();
    private final List<ImageView> thumbs = new ArrayList<>();
    private final LinearLayout filterRow;
    private final List<ImageView> filterThumbs = new ArrayList<>();
    private final Map<String, FilmLut> luts = new HashMap<>();
    /** A card's two bitmaps: the one shown and the one the next frame is drawn into. */
    private final Map<ImageView, Bitmap[]> buffers = new java.util.WeakHashMap<>();
    private final android.graphics.Rect visible = new android.graphics.Rect();
    @Nullable private Runnable onOpen;
    private boolean open;
    private final Runnable refreshThumbs = this::grabFrame;

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
            main.post(refreshThumbs);
        } else {
            tray.setVisibility(View.GONE);
            main.removeCallbacks(refreshThumbs);
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
        boolean on = !value.isEmpty() && PreferenceKeys.filmLutStrength() > 0f || filterOn;
        int accent = ShadeStyle.accent(c);
        String label = value.isEmpty() ? "LUT" : name(c, value);
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

    /** Every LUT as a card: «Нет», our looks, the folder's .cube files. */
    private void rebuild() {
        Context c = tray.getContext();
        values.clear();
        values.add("");
        for (String v : FilmLooks.values()) values.add(v);
        try {
            for (String f : ScameraFolder.LUTS.list(c)) values.add(f);
        } catch (RuntimeException e) {
            Log.w("LutTray", "LUT folder: " + e);
        }
        row.removeAllViews();
        thumbs.clear();
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
            ImageView thumb = new ImageView(c);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(0xFF000000);
            float r = UiScale.dpF(c, 9);
            thumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            thumb.setClipToOutline(true);
            card.addView(thumb, new LinearLayout.LayoutParams(UiScale.dp(c, 54), UiScale.dp(c, 40)));
            thumbs.add(thumb);
            TextView name = text(c, name(c, value), 10, on ? accent : ShadeStyle.MUTED);
            name.setMaxLines(2);
            name.setGravity(Gravity.CENTER);
            name.setMinHeight(UiScale.dp(c, 23));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = UiScale.dp(c, 3);
            card.addView(name, nlp);
            card.setContentDescription(name(c, value));
            card.setOnClickListener(v -> choose(value));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(UiScale.dp(c, 62), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (row.getChildCount() > 0) lp.setMarginStart(UiScale.dp(c, 6));
            row.addView(card, lp);
            if (on) selected = card;
        }
        rebuildFilters();
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
        filterThumbs.clear();
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
            ImageView thumb = new ImageView(c);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(0xFF000000);
            float r = UiScale.dpF(c, 9);
            thumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            thumb.setClipToOutline(true);
            card.addView(thumb, new LinearLayout.LayoutParams(UiScale.dp(c, 54), UiScale.dp(c, 40)));
            filterThumbs.add(thumb);
            TextView name = text(c, id.isEmpty() ? Lang.t(c, "Без фильтра", "No filter") : LensFilters.name(c, id), 10,
                    on ? accent : ShadeStyle.MUTED);
            name.setMaxLines(2);
            name.setGravity(Gravity.CENTER);
            name.setMinHeight(UiScale.dp(c, 23));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = UiScale.dp(c, 3);
            card.addView(name, nlp);
            card.setContentDescription(Lang.t(c, "Фильтр: ", "Filter: ") + LensFilters.name(c, id));
            card.setTag("lut_tray_filter_" + id);
            card.setOnClickListener(v -> chooseFilter(id));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(UiScale.dp(c, 62), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (filterRow.getChildCount() > 0) lp.setMarginStart(UiScale.dp(c, 6));
            filterRow.addView(card, lp);
        }
    }

    static String[] filterIds() {
        String[] all = new String[LensFilters.IDS.length + 1];
        all[0] = "";
        System.arraycopy(LensFilters.IDS, 0, all, 1, LensFilters.IDS.length);
        return all;
    }

    /** A filter card tapped: that filter becomes the camera's (the settings' state). */
    void chooseFilter(String id) {
        settings().set(SettingsManager.SCOPE_GLOBAL, LensFilters.KEY, id);
        if (!id.isEmpty() && PreferenceKeys.lensFilterStrength() <= 0f)
            settings().set(SettingsManager.SCOPE_GLOBAL, LensFilters.STRENGTH_KEY, "50");
        rebuildFilters();
        bindPill();
        main.removeCallbacks(refreshThumbs);
        main.post(refreshThumbs);
    }

    /** A card tapped: that LUT becomes the camera's (the settings' state), the tray shows it chosen. */
    void choose(String value) {
        settings().set(SettingsManager.SCOPE_GLOBAL, FilmLut.KEY, value);
        if (!value.isEmpty() && PreferenceKeys.filmLutStrength() <= 0f)
            settings().set(SettingsManager.SCOPE_GLOBAL, FilmLut.STRENGTH_KEY, "100");
        rebuild();
        bindPill();
        main.removeCallbacks(refreshThumbs);
        main.post(refreshThumbs);
    }

    /** The next / previous LUT (a horizontal swipe on the viewfinder); returns its name for the message band. */
    public String step(int direction) {
        Context c = tray.getContext();
        if (!open) {
            values.clear();
            values.add("");
            for (String v : FilmLooks.values()) values.add(v);
            try {
                for (String f : ScameraFolder.LUTS.list(c)) values.add(f);
            } catch (RuntimeException ignored) {
            }
        }
        int i = values.indexOf(PreferenceKeys.filmLut());
        int n = values.size();
        String next = values.get(((i < 0 ? 0 : i) + direction % n + n) % n);
        choose(next);
        return Lang.t(c, "LUT: ", "LUT: ") + name(c, next);
    }

    // ---- thumbnails: the viewfinder frame through each LUT, at most every THUMB_PERIOD_MS

    private void grabFrame() {
        if (!open) return;
        if (rendering) {   // the last frame is still being drawn: no queue of frames behind it
            main.postDelayed(refreshThumbs, THUMB_PERIOD_MS / 3);
            return;
        }
        if (preview == null || preview.getHolder().getSurface() == null || !preview.getHolder().getSurface().isValid()
                || preview.getWidth() == 0) {
            main.postDelayed(refreshThumbs, THUMB_PERIOD_MS);
            return;
        }
        Context c = tray.getContext();
        int w = UiScale.dp(c, 54), h = UiScale.dp(c, 40);
        // the frame at the thumbnail's aspect, cropped from the centre by the image views
        float aspect = preview.getWidth() / (float) preview.getHeight();
        int bw = Math.max(w, Math.round(h * aspect)), bh = Math.max(h, Math.round(w / aspect));
        Bitmap frame = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        try {
            final long asked = android.os.SystemClock.uptimeMillis();
            PixelCopy.request(preview, frame, result -> {
                if (result == PixelCopy.SUCCESS) render(frame, asked);
                else main.postDelayed(refreshThumbs, THUMB_PERIOD_MS);
            }, main);
        } catch (RuntimeException e) {
            main.postDelayed(refreshThumbs, THUMB_PERIOD_MS);
        }
    }

    /** One card's thumbnail of a frame: a LUT's (filter null) or a filter's (lut null), drawn into {@code into}. */
    private static final class Thumb {
        final ImageView view;
        final Bitmap into;
        @Nullable final String lut, filter;

        Thumb(ImageView view, Bitmap into, @Nullable String lut, @Nullable String filter) {
            this.view = view;
            this.into = into;
            this.lut = lut;
            this.filter = filter;
        }
    }

    /** A card is drawn when it is on screen, or when it has never been drawn (the first frame draws every card). */
    private boolean wanted(ImageView v) {
        return !buffers.containsKey(v) || v.isShown() && v.getGlobalVisibleRect(visible);
    }

    /** The card's bitmap that is not shown, w x h (both made again when the frame's size changed). */
    private Bitmap spare(ImageView v, int w, int h) {
        Bitmap[] b = buffers.get(v);
        if (b == null || b[0].getWidth() != w || b[0].getHeight() != h) {
            b = new Bitmap[]{Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)};
            buffers.put(v, b);
            return b[0];
        }
        return v.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                && ((android.graphics.drawable.BitmapDrawable) v.getDrawable()).getBitmap() == b[0] ? b[1] : b[0];
    }

    private void render(Bitmap frame, long asked) {
        if (rendering) return;   // a second frame asked meanwhile (a card tapped): the chain of the first one goes on
        final int w = frame.getWidth(), h = frame.getHeight();
        Context app = tray.getContext().getApplicationContext();
        String[] ids = filterIds();
        List<Thumb> todo = new ArrayList<>();
        for (int i = 0; i < Math.min(values.size(), thumbs.size()); i++) {
            ImageView v = thumbs.get(i);
            if (wanted(v)) todo.add(new Thumb(v, spare(v, w, h), values.get(i), null));
        }
        for (int i = 0; i < Math.min(ids.length, filterThumbs.size()); i++) {
            ImageView v = filterThumbs.get(i);
            if (wanted(v)) todo.add(new Thumb(v, spare(v, w, h), null, ids[i]));
        }
        rendering = true;
        WORKER.execute(() -> {
            int[] src = new int[w * h];
            frame.getPixels(src, 0, w, 0, 0, w, h);
            float[] lin = null;
            for (Thumb t : todo)
                if (t.filter != null && !t.filter.isEmpty()) {
                    lin = com.particlesdevs.photoncamera.ui.settings.custompreferences.LensFilterPreference.linear(src);
                    break;
                }
            final float[] linear = lin;
            List<java.util.concurrent.Callable<Thumb>> jobs = new ArrayList<>();
            for (Thumb t : todo)
                jobs.add(() -> {
                    int[] px = t.lut != null ? through(app, t.lut, src)
                            : t.filter.isEmpty() ? src
                            : com.particlesdevs.photoncamera.ui.settings.custompreferences.LensFilterPreference.filterLinear(
                                    linear, w, h, t.filter, 0.8f, PreferenceKeys.lensFilterTune(t.filter));
                    t.into.setPixels(px, 0, w, 0, 0, w, h);
                    return t;
                });
            List<Thumb> done = new ArrayList<>();
            try {
                for (java.util.concurrent.Future<Thumb> f : POOL.invokeAll(jobs)) {
                    try {
                        done.add(f.get());
                    } catch (java.util.concurrent.ExecutionException e) {
                        Log.w("LutTray", "thumbnail: " + e.getCause());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            main.post(() -> {
                rendering = false;
                if (!open) return;
                for (Thumb t : done) if (buffers.containsKey(t.view)) t.view.setImageBitmap(t.into);
                long spent = android.os.SystemClock.uptimeMillis() - asked;
                main.removeCallbacks(refreshThumbs);
                main.postDelayed(refreshThumbs, Math.max(0L, THUMB_PERIOD_MS - spent));
            });
        });
    }

    /** {@code src} through the LUT of {@code value} at full strength (the original for «Нет» or an unreadable file). */
    private int[] through(Context c, String value, int[] src) {
        FilmLut lut = value.isEmpty() ? null : lut(c, value);
        if (lut == null) return src;
        int[] px = new int[src.length];
        float[] o = new float[3];
        for (int i = 0; i < px.length; i++) {
            int p = src[i];
            lut.sampleInto(((p >> 16) & 0xFF) / 255f, ((p >> 8) & 0xFF) / 255f, (p & 0xFF) / 255f, o);
            px[i] = 0xFF000000 | (clamp(o[0]) << 16) | (clamp(o[1]) << 8) | clamp(o[2]);
        }
        return px;
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255f)));
    }

    /** The LUT of a value, parsed once per tray (not through FilmLut.chosen: that one is the pipeline's cache). */
    @Nullable
    private synchronized FilmLut lut(Context c, String value) {
        if (luts.containsKey(value)) return luts.get(value);
        FilmLut lut = null;
        if (FilmLooks.isBuiltIn(value)) {
            lut = FilmLooks.lut(value);
        } else try (InputStream in = ScameraFolder.LUTS.open(c, value)) {
            if (in != null) lut = FilmLut.parse(in);
        } catch (Exception e) {
            Log.w("LutTray", "cannot read " + value + ": " + e);
        }
        luts.put(value, lut);
        return lut;
    }

    /** The camera screen goes. */
    public void release() {
        main.removeCallbacksAndMessages(null);
        open = false;
    }
}
