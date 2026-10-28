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
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.processing.color.LensFilters;
import com.particlesdevs.photoncamera.settings.PreferenceNumber;
import com.particlesdevs.photoncamera.settings.PreferenceValue;
import com.particlesdevs.photoncamera.ui.settings.PageReset;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P228 «Фильтр на объектив» (owner: «нам нужна наглядная демонстрация прямо в настройках как у нас реализовано с LUT в новом
 * интерфейсе»): the page next to the LUT's - the before / after preview of a sample (night lights, a landscape, a sunset) through
 * the chosen filter (LensFilters.apply, the photo's model), the strip of filters with live thumbnails, «Сила», the filter's own
 * parameter and its fine controls (owner: «дай возможность настраивать эти фильтры»: threshold, radius, haze, glow tone; each
 * redraws the preview at once, «Сбросить» brings the filter back as designed) and what the filter does. Stores the id
 * (pref_lens_filter), the strength 0..100 and, per filter, its LensFilters.Tune.
 */
public class LensFilterPreference extends Preference implements PageReset.Resettable {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lens-filter-page");
        t.setDaemon(true);
        return t;
    });
    public static final int SAMPLES = 3;
    static final int THUMB_W = 160, THUMB_H = 120;
    static int sample;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private LutCompareView compare;
    @Nullable private LinearLayout strip, samples, controls;
    @Nullable private TextView description;
    private int generation, previewGeneration;

    public LensFilterPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(true);
        setLayoutResource(R.layout.preference_lut_page);
        setSelectable(false);
    }

    public LensFilterPreference(Context context) {
        this(context, null);
    }

    public String chosen() {
        String v = getPersistedString("");
        return LensFilters.known(v) ? v : "";
    }

    public void choose(String id) {
        persistString(id == null ? "" : id);
        SharedPreferences prefs = getSharedPreferences();
        if (prefs != null && id != null && !id.isEmpty() && strength(prefs) <= 0f)
            prefs.edit().putString(LensFilters.STRENGTH_KEY, "50").apply();
        notifyChanged();
    }

    /**
     * The page reset (audit 2026-10-27: the chosen filter stayed): no filter, the strength and every filter's fine controls as
     * designed (their stored values go); true when a filter was chosen or a value of its own was stored.
     */
    @Override
    public boolean resetToDefault() {
        final SharedPreferences prefs = getSharedPreferences();
        if (prefs == null) return false;
        boolean stored = false, any = !chosen().isEmpty();
        final SharedPreferences.Editor e = prefs.edit();
        for (String k : prefs.getAll().keySet()) {
            if (!LensFilters.KEY.equals(k) && !LensFilters.STRENGTH_KEY.equals(k) && !k.startsWith(LensFilters.TUNE_PREFIX)) continue;
            e.remove(k);
            stored = true;
            if (!LensFilters.KEY.equals(k)) any = true;
        }
        if (!stored) return false;
        e.apply();
        notifyChanged();
        return any;
    }

    @Override
    public CharSequence getSummary() {
        return LensFilters.name(getContext(), chosen());
    }

    static float strength(SharedPreferences prefs) {
        return number(prefs, LensFilters.STRENGTH_KEY, 50f);
    }

    /** The filter's own parameter and fine controls (per filter). */
    public static LensFilters.Tune tune(SharedPreferences prefs, String id) {
        try {
            Object v = PreferenceValue.get(prefs, LensFilters.tuneKey(id));
            return LensFilters.Tune.parse(id, v == null ? null : String.valueOf(v));
        } catch (RuntimeException e) {
            return LensFilters.Tune.defaults(id);
        }
    }

    static void storeTune(SharedPreferences prefs, String id, LensFilters.Tune t) {
        prefs.edit().putString(LensFilters.tuneKey(id), t.serialize()).apply();
    }

    private static float number(SharedPreferences prefs, String key, float fallback) {
        try {
            return (float) Math.max(0, Math.min(100, PreferenceNumber.read(PreferenceValue.get(prefs, key), fallback))) / 100f;
        } catch (RuntimeException e) {
            return fallback / 100f;
        }
    }

    public static String sampleName(Context c, int i) {
        switch (i) {
            case 0: return Lang.t(c, "Огни", "Lights");
            case 1: return Lang.t(c, "Пейзаж", "Landscape");
            default: return Lang.t(c, "Закат", "Sunset");
        }
    }

    /** The sample as linear RGB with the highlights' headroom (see {@link #linear}). */
    static float[] linearSample(int s, int w, int h) {
        Bitmap b = s == 0 ? LutSamples.lights(w, h) : LutSamples.draw(s == 1 ? 0 : 1, w, h);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        return linear(px);
    }

    /**
     * sRGB pixels as linear RGB with the highlights' headroom: the sun, a lamp's core - a source brighter than white by its sRGB
     * luma (up to 4 x) - so the glow has the energy it has in the photo (where the merge keeps the real highlights).
     */
    public static float[] linear(int[] px) {
        float[] rgb = new float[px.length * 3];
        final float[] lin = LINEAR;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            float r = lin[(p >> 16) & 255], g = lin[(p >> 8) & 255], bl = lin[p & 255];
            float y = (0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255)) / 255f;
            float t = Math.max(0f, Math.min(1f, (y - 0.82f) / 0.1f));
            float boost = 1f + 3f * t * t * (3f - 2f * t);
            rgb[i * 3] = r * boost;
            rgb[i * 3 + 1] = g * boost;
            rgb[i * 3 + 2] = bl * boost;
        }
        return rgb;
    }

    /** sRGB pixels through filter {@code id} (the viewfinder tray's thumbnails). */
    public static int[] filter(int[] px, int w, int h, String id, float strength, LensFilters.Tune tune) {
        return filterLinear(linear(px), w, h, id, strength, tune);
    }

    /** {@link #filter} of pixels already made {@link #linear} (one conversion for every filter of a frame); {@code lin} is kept. */
    public static int[] filterLinear(float[] lin, int w, int h, String id, float strength, LensFilters.Tune tune) {
        float[] rgb = lin.clone();
        LensFilters.apply(rgb, w, h, id, strength, tune);
        return pixels(rgb);
    }

    static int[] pixels(float[] rgb) {
        int[] px = new int[rgb.length / 3];
        for (int i = 0; i < px.length; i++)
            px[i] = 0xFF000000 | (toByte(rgb[i * 3]) << 16) | (toByte(rgb[i * 3 + 1]) << 8) | toByte(rgb[i * 3 + 2]);
        return px;
    }

    /** {@link #toLinear} of every 8-bit value (k / 255). */
    private static final float[] LINEAR = new float[256];

    static {
        for (int k = 0; k < 256; k++) LINEAR[k] = toLinear(k / 255f);
    }

    static float toLinear(float v) {
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4f);
    }

    static int toByte(float v) {
        v = Math.max(0f, v);
        // a soft shoulder above white (as a tone curve would roll the glow off), then the sRGB curve
        float t = v <= 0.8f ? v : 0.8f + 0.2f * (float) (1.0 - Math.exp(-(v - 0.8f) / 0.2f));
        float e = t <= 0.0031308f ? 12.92f * t : 1.055f * (float) Math.pow(t, 1 / 2.4) - 0.055f;
        return Math.round(Math.max(0f, Math.min(1f, e)) * 255f);
    }

    /** The sample at w x h through {@code id} at {@code strength} / {@code param}, as a bitmap. */
    public static Bitmap render(int s, int w, int h, String id, float strength, float param) {
        return render(s, w, h, id, strength, LensFilters.Tune.of(id, param));
    }

    /** The sample at w x h through {@code id} at {@code strength} with its fine controls, as a bitmap. */
    public static Bitmap render(int s, int w, int h, String id, float strength, LensFilters.Tune tune) {
        float[] rgb = linearSample(s, w, h);
        LensFilters.apply(rgb, w, h, id, strength, tune);
        return Bitmap.createBitmap(pixels(rgb), w, h, Bitmap.Config.ARGB_8888);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        FrameLayout host = (FrameLayout) holder.itemView;
        host.removeAllViews();
        host.addView(build(getContext()), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View build(Context c) {
        int accent = SettingsStyle.accent(c);
        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        compare = new LutCompareView(c);
        compare.setTag("lens_filter_compare");
        page.addView(compare, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        samples = new LinearLayout(c);
        for (int i = 0; i < SAMPLES; i++) {
            TextView b = SettingsStyle.text(c, sampleName(c, i), 12.5f, UiTokens.TEXT);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(UiTokens.dp(c, 34));
            b.setPadding(UiTokens.dp(c, 12), 0, UiTokens.dp(c, 12), 0);
            b.setTag("lens_filter_sample_" + i);
            final int index = i;
            b.setOnClickListener(v -> {
                sample = index;
                paintSamples();
                fillStrip();
                redraw();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(UiTokens.dp(c, 6));
            samples.addView(b, lp);
        }
        page.addView(samples, top(c, 10));
        paintSamples();
        page.addView(FilmLutPreference.groupTitle(c, Lang.t(c, "Фильтр", "Filter"), accent), top(c, 16));
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        strip = new LinearLayout(c);
        strip.setPadding(UiTokens.dp(c, 2), UiTokens.dp(c, 2), UiTokens.dp(c, 2), UiTokens.dp(c, 6));
        scroll.addView(strip, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, top(c, 6));
        controls = new LinearLayout(c);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setTag("lens_filter_controls");
        page.addView(controls, top(c, 12));
        LinearLayout note = (LinearLayout) LutChangesPreference.noteView(c, LensFilters.description(c, chosen()));
        description = (TextView) note.getChildAt(1);
        page.addView(note, top(c, 14));
        fillStrip();
        fillControls();
        compare.post(this::redraw);
        return page;
    }

    private static LinearLayout.LayoutParams top(Context c, int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiTokens.dp(c, dp);
        return lp;
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

    private void fillStrip() {
        if (strip == null) return;
        Context c = getContext();
        int accent = SettingsStyle.accent(c);
        String chosen = chosen();
        strip.removeAllViews();
        int gen = ++generation;
        final int s = sample;
        final SharedPreferences prefs = getSharedPreferences();
        // P279: the user's order (hold and drag a card), shared with the viewfinder's tray
        final java.util.List<String> all = com.particlesdevs.photoncamera.settings.ItemOrder.filters();
        for (String id : all) {
            boolean on = id.equals(chosen);
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(UiTokens.dp(c, 4), UiTokens.dp(c, 4), UiTokens.dp(c, 4), UiTokens.dp(c, 6));
            GradientDrawable bg = UiTokens.shape(c, UiTokens.CARD, 0, 14);
            bg.setStroke(UiTokens.dp(c, on ? 2 : 1), on ? accent : UiTokens.LINE);
            card.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), bg, null));
            ImageView thumb = new ImageView(c);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(0xFF000000);
            final float r = UiTokens.dp(c, 10);
            thumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            thumb.setClipToOutline(true);
            int cardW = UiTokens.dp(c, 92);
            card.addView(thumb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round((cardW - UiTokens.dp(c, 8)) * .75f)));
            TextView name = SettingsStyle.text(c, LensFilters.name(c, id), 12, UiTokens.TEXT);
            name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            name.setPadding(UiTokens.dp(c, 3), UiTokens.dp(c, 5), UiTokens.dp(c, 3), 0);
            name.setMaxLines(2);
            card.addView(name);
            TextView hint = SettingsStyle.text(c, LensFilters.hint(c, id), 10.5f, UiTokens.MUTED);
            hint.setPadding(UiTokens.dp(c, 3), UiTokens.dp(c, 3), UiTokens.dp(c, 3), 0);
            hint.setMaxLines(2);
            card.addView(hint);
            card.setTag("lens_filter_" + id);
            card.setSelected(on);
            card.setContentDescription(LensFilters.name(c, id) + ": " + LensFilters.hint(c, id));
            card.setOnClickListener(v -> choose(id));
            com.particlesdevs.photoncamera.ui.settings.StripReorder.attach(card, strip.getChildCount(), (from, to) -> {
                com.particlesdevs.photoncamera.settings.ItemOrder.store(com.particlesdevs.photoncamera.settings.ItemOrder.FILTER_KEY, all, from, to);
                fillStrip();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMarginEnd(UiTokens.dp(c, 8));
            strip.addView(card, lp);
            final String fid = id;
            WORKER.execute(() -> {
                Bitmap bm = render(s, THUMB_W, THUMB_H, fid, fid.isEmpty() ? 0f : 0.7f, prefs == null ? LensFilters.Tune.defaults(fid) : tune(prefs, fid));
                main.post(() -> { if (gen == generation) thumb.setImageBitmap(bm); });
            });
        }
    }

    /**
     * «Сила», the filter's own parameter (when it has one) and its fine controls under «Тонкая настройка» with «Сбросить»;
     * their changes redraw the preview at once.
     */
    private void fillControls() {
        LinearLayout box = controls;
        SharedPreferences prefs = getSharedPreferences();
        if (box == null || prefs == null) return;
        Context c = getContext();
        box.removeAllViews();
        String id = chosen();
        if (id.isEmpty()) {
            box.setVisibility(View.GONE);
        } else {
            box.setVisibility(View.VISIBLE);
            box.addView(slider(c, Lang.t(c, "Сила", "Strength"), null, "lens_filter_strength", strength(prefs), .5f,
                    v -> prefs.edit().putString(LensFilters.STRENGTH_KEY, String.valueOf(v)).apply()));
            String pn = LensFilters.paramName(c, id);
            if (pn != null)
                box.addView(slider(c, pn, null, "lens_filter_param", tune(prefs, id).param, LensFilters.Tune.defaults(id).param, v -> {
                    LensFilters.Tune t = tune(prefs, id);
                    t.param = v / 100f;
                    storeTune(prefs, id, t);
                }), top(c, 8));
            int fine = LensFilters.tunable(id);
            if (fine != 0) {
                LinearLayout head = new LinearLayout(c);
                head.setGravity(Gravity.CENTER_VERTICAL);
                head.addView(FilmLutPreference.groupTitle(c, Lang.t(c, "Тонкая настройка", "Fine tuning"), SettingsStyle.accent(c)),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView reset = SettingsStyle.text(c, Lang.t(c, "Сбросить", "Reset"), 12.5f, SettingsStyle.accent(c));
                reset.setPadding(UiTokens.dp(c, 12), UiTokens.dp(c, 6), UiTokens.dp(c, 6), UiTokens.dp(c, 6));
                reset.setTag("lens_filter_reset");
                reset.setEnabled(!tune(prefs, id).fineDefault());
                reset.setAlpha(reset.isEnabled() ? 1f : 0.4f);
                reset.setOnClickListener(v -> {
                    LensFilters.Tune t = LensFilters.Tune.defaults(id);
                    t.param = tune(prefs, id).param;
                    storeTune(prefs, id, t);
                    fillControls();
                    redraw();
                });
                head.addView(reset);
                box.addView(head, top(c, 14));
                for (int control : LensFilters.CONTROLS) {
                    if ((fine & control) == 0) continue;
                    box.addView(slider(c, LensFilters.controlName(c, id, control), LensFilters.controlHint(c, id, control),
                            "lens_filter_tune_" + control, tune(prefs, id).get(control), LensFilters.Tune.defaults(id).get(control), v -> {
                                LensFilters.Tune t = tune(prefs, id);
                                t.set(control, v / 100f);
                                storeTune(prefs, id, t);
                                View r = controls == null ? null : controls.findViewWithTag("lens_filter_reset");
                                if (r != null) {
                                    r.setEnabled(!t.fineDefault());
                                    r.setAlpha(r.isEnabled() ? 1f : 0.4f);
                                }
                            }), top(c, 8));
                }
            }
        }
        if (description != null) description.setText(LensFilters.description(c, id));
    }

    interface OnValue {
        void set(int value);
    }

    /** The concept's .strength line: the name (and what it does), the slider, the value; a double tap puts {@code def} back (P357). */
    private View slider(Context c, String label, @Nullable String hint, String tag, float value, float def, OnValue store) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiTokens.dp(c, 52));
        row.setPadding(UiTokens.dp(c, 14), UiTokens.dp(c, hint == null ? 0 : 8), UiTokens.dp(c, 10), UiTokens.dp(c, hint == null ? 0 : 8));
        row.setBackground(UiTokens.shape(c, UiTokens.CARD, UiTokens.LINE, 16));
        LinearLayout names = new LinearLayout(c);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = SettingsStyle.text(c, label, 14, UiTokens.TEXT);
        name.setMaxLines(2);
        names.addView(name);
        if (hint != null) {
            TextView h = SettingsStyle.text(c, hint, 11.5f, UiTokens.MUTED);
            h.setMaxLines(3);
            names.addView(h);
        }
        row.addView(names, new LinearLayout.LayoutParams(UiTokens.dp(c, 128), ViewGroup.LayoutParams.WRAP_CONTENT));
        SeekBar bar = new SeekBar(c);
        bar.setMax(100);
        bar.setProgress(Math.round(value * 100f));
        int accent = SettingsStyle.accent(c);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        bar.setTag(tag);
        bar.setContentDescription(label);
        row.addView(bar, new LinearLayout.LayoutParams(0, UiTokens.dp(c, 36), 1f));
        TextView shown = SettingsStyle.text(c, Math.round(value * 100f) + " %", 13.5f, accent);
        shown.setMinWidth(UiTokens.dp(c, 44));
        shown.setGravity(Gravity.END);
        row.addView(shown);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                shown.setText(p + " %");
                if (!user) return;
                store.set(p);
                redraw();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        final Runnable reset = () -> {
            final int p = Math.round(def * 100f);
            bar.setProgress(p);
            store.set(p);
            redraw();
        };
        com.particlesdevs.photoncamera.ui.controls.DoubleTapReset.attach(bar, reset);
        com.particlesdevs.photoncamera.ui.controls.DoubleTapReset.attach(row, reset);
        return row;
    }

    /** The preview at the current sample, filter, strength and parameter (off the main thread, the newest wins). */
    void redraw() {
        LutCompareView view = compare;
        SharedPreferences prefs = getSharedPreferences();
        if (view == null || view.getWidth() == 0 || prefs == null) return;
        final String id = chosen();
        final float st = strength(prefs);
        final LensFilters.Tune tu = tune(prefs, id);
        final int w = Math.min(560, view.getWidth()), h = Math.round(w * .75f), s = sample;
        final int gen = ++previewGeneration;
        WORKER.execute(() -> {
            if (gen != previewGeneration) return;
            Bitmap before = render(s, w, h, "", 0f, 0f);
            Bitmap after = id.isEmpty() ? before : render(s, w, h, id, st, tu);
            main.post(() -> { if (gen == previewGeneration) view.setImages(before, after); });
        });
    }

}
