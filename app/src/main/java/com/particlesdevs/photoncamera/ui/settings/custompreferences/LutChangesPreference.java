package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.color.LutLook;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SIMPLE_UI §4c: «Что меняется» of the «Плёночный LUT» page (the concept's .changes) - what the chosen LUT imitates
 * («Слегка.» under 50 %), the analysis chips (LutLook: Контраст, Чёрный, Белый, Цвета, Тени, Света, Кожа, Зелень), the tone
 * curve with «тени / середина / света» and its reading, the six key colours before → after and «Как это определяется»;
 * then the note on importing. With no LUT or at 0 %: one line saying the photo stays as it is.
 */
public class LutChangesPreference extends Preference implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lut-changes");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private FrameLayout box;
    private int generation;
    private boolean howOpen;

    public LutChangesPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
        setLayoutResource(R.layout.preference_lut_page);
        setSelectable(false);
    }

    public LutChangesPreference(Context context) {
        this(context, null);
    }

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
        if (FilmLut.KEY.equals(key) || FilmLut.STRENGTH_KEY.equals(key)) main.post(this::refresh);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        Context c = getContext();
        FrameLayout host = (FrameLayout) holder.itemView;   // preference_lut_page: the host is the row itself
        host.removeAllViews();
        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(FilmLutPreference.groupTitle(c, Lang.t(c, "Что меняется", "What changes"), SettingsStyle.accent(c)));
        box = new FrameLayout(c);
        box.setTag("lut_changes");
        box.setBackground(UiTokens.shape(c, UiTokens.CARD, UiTokens.LINE, 18));
        int pad = UiTokens.dp(c, 12);
        box.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = UiTokens.dp(c, 6);
        page.addView(box, bp);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = UiTokens.dp(c, 14);
        page.addView(note(c), np);
        host.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refresh();
    }

    static View note(Context c) {
        return noteView(c, Lang.t(c,
                "Импортируйте свой .cube плиткой «Импорт .cube» — он сразу появится в ленте с превью до/после, кривой и разбором, что меняется. Файлы лежат в Download/SCAMERA/LUT.",
                "Import your own .cube with the «Import .cube» tile: it appears in the strip at once, with the before / after preview, the curve and what it changes. The files live in Download/SCAMERA/LUT."));
    }

    /** The concept's .note: the info icon in the accent, the text muted, on the second card colour. */
    public static View noteView(Context c, CharSequence text) {
        LinearLayout n = new LinearLayout(c);
        n.setOrientation(LinearLayout.HORIZONTAL);
        n.setBackground(UiTokens.shape(c, 0xFF232A2E, 0, 18));
        n.setPadding(UiTokens.dp(c, 14), UiTokens.dp(c, 12), UiTokens.dp(c, 14), UiTokens.dp(c, 12));
        ImageView i = new ImageView(c);
        i.setImageResource(R.drawable.ui_ic_info);
        i.setImageTintList(ColorStateList.valueOf(SettingsStyle.accent(c)));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(UiTokens.dp(c, 20), UiTokens.dp(c, 20));
        ip.setMarginEnd(UiTokens.dp(c, 10));
        ip.topMargin = UiTokens.dp(c, 1);
        n.addView(i, ip);
        TextView t = SettingsStyle.text(c, text, 13, UiTokens.MUTED);
        t.setLineSpacing(UiTokens.dp(c, 2), 1f);
        n.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return n;
    }

    /** The box for the current LUT and strength (the LUT is read off the main thread). */
    void refresh() {
        FrameLayout target = box;
        if (target == null) return;
        Context c = getContext();
        SharedPreferences prefs = getSharedPreferences();
        String value = prefs == null ? "" : prefs.getString(FilmLut.KEY, "");
        float k = prefs == null ? 1f : FilmLutPreference.strength(prefs);
        int gen = ++generation;
        if (value == null || value.isEmpty()) {
            show(target, line(c, Lang.t(c, "Плёночный образ выключен: снимок как есть.", "The film look is off: the photo stays as it is.")));
            return;
        }
        if (k <= 0f) {
            show(target, line(c, Lang.t(c, "Сила 0 %: снимок не меняется.", "Strength 0 %: the photo does not change.")));
            return;
        }
        WORKER.execute(() -> {
            FilmLut lut = FilmLutPreference.lut(c, value);
            main.post(() -> {
                if (gen != generation) return;
                if (lut == null) {
                    show(target, line(c, Lang.t(c, "Файл не читается: выберите другой LUT.", "The file cannot be read: choose another LUT.")));
                    return;
                }
                show(target, details(c, value, lut, k));
            });
        });
    }

    private static void show(FrameLayout box, View content) {
        box.removeAllViews();
        box.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static TextView line(Context c, String text) {
        return SettingsStyle.text(c, text, 13, UiTokens.MUTED);
    }

    private View details(Context c, String value, FilmLut lut, float k) {
        int accent = SettingsStyle.accent(c);
        LutLook look = new LutLook(lut::sampleInto, k);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int gap = UiTokens.dp(c, 10);
        // what it imitates
        String desc = (k < .5f ? Lang.t(c, "Слегка. ", "Slightly. ") : "") + FilmLutPreference.description(c, value, lut);
        TextView d = SettingsStyle.text(c, desc, 13, UiTokens.TEXT);
        d.setLineSpacing(UiTokens.dp(c, 2), 1f);
        col.addView(d);
        // the chips
        FlowLayout chips = new FlowLayout(c, UiTokens.dp(c, 6));
        chips.setTag("lut_chips");
        List<LutLook.Tag> tags = look.analyse();
        for (LutLook.Tag t : tags) {
            SpannableStringBuilder s = new SpannableStringBuilder(t.name(c) + ": ");
            int start = s.length();
            s.append(t.value(c));
            s.setSpan(new ForegroundColorSpan(accent), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView chip = SettingsStyle.text(c, s, 12, UiTokens.TEXT);
            chip.setPadding(UiTokens.dp(c, 9), UiTokens.dp(c, 4), UiTokens.dp(c, 9), UiTokens.dp(c, 4));
            chip.setBackground(UiTokens.shape(c, 0xFF232A2E, 0, 999));
            chips.addView(chip);
        }
        col.addView(chips, top(c, gap));
        // the curve and its reading
        LinearLayout curveBox = new LinearLayout(c);
        curveBox.setOrientation(LinearLayout.HORIZONTAL);
        curveBox.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout curveCol = new LinearLayout(c);
        curveCol.setOrientation(LinearLayout.VERTICAL);
        LutCurveView curve = new LutCurveView(c);
        curve.setLook(look);
        curve.setContentDescription(Lang.t(c, "Тоновая кривая", "Tone curve"));
        curveCol.addView(curve, new LinearLayout.LayoutParams(UiTokens.dp(c, 120), UiTokens.dp(c, 90)));
        FrameLayout axis = new FrameLayout(c);
        String[] names = {Lang.t(c, "тени", "shadows"), Lang.t(c, "середина", "middle"), Lang.t(c, "света", "lights")};
        int[] gravity = {Gravity.START, Gravity.CENTER_HORIZONTAL, Gravity.END};
        for (int i = 0; i < 3; i++) {
            TextView a = SettingsStyle.text(c, names[i], 10, UiTokens.MUTED);
            a.setMaxLines(1);
            axis.addView(a, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity[i]));
        }
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(UiTokens.dp(c, 120), ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = UiTokens.dp(c, 2);
        curveCol.addView(axis, ap);
        curveBox.addView(curveCol);
        TextView reading = SettingsStyle.text(c, Lang.t(c,
                "Линия выше пунктира — эта часть снимка станет светлее, ниже — темнее. Цветные линии показывают сдвиг оттенка.",
                "Above the dashed line that part of the photo gets lighter, below it darker. The coloured lines show the tint shift."),
                12.5f, UiTokens.MUTED);
        reading.setLineSpacing(UiTokens.dp(c, 2), 1f);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rp.setMarginStart(UiTokens.dp(c, 12));
        curveBox.addView(reading, rp);
        col.addView(curveBox, top(c, gap));
        // the key colours
        col.addView(SettingsStyle.text(c, Lang.t(c, "Ключевые цвета: слева — до, справа — после", "Key colours: before on the left, after on the right"),
                12, UiTokens.MUTED), top(c, gap));
        LinearLayout sw = new LinearLayout(c);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setTag("lut_swatches");
        String[] keyNames = {Lang.t(c, "Кожа", "Skin"), Lang.t(c, "Небо", "Sky"), Lang.t(c, "Зелень", "Greens"),
                Lang.t(c, "Красный", "Red"), Lang.t(c, "Тени", "Shadows"), Lang.t(c, "Света", "Lights")};
        for (int i = 0; i < LutLook.KEY_COLOURS.length; i++) {
            int[] k0 = LutLook.KEY_COLOURS[i];
            float[] a = look.apply(k0[0] / 255f, k0[1] / 255f, k0[2] / 255f);
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout pair = new LinearLayout(c);
            pair.setOrientation(LinearLayout.HORIZONTAL);
            pair.setClipToOutline(true);
            pair.setBackground(UiTokens.shape(c, 0, 0, 8));
            View before = new View(c), after = new View(c);
            before.setBackgroundColor(0xFF000000 | (k0[0] << 16) | (k0[1] << 8) | k0[2]);
            after.setBackgroundColor(0xFF000000 | (LutSamples.to8(a[0]) << 16) | (LutSamples.to8(a[1]) << 8) | LutSamples.to8(a[2]));
            pair.addView(before, new LinearLayout.LayoutParams(0, UiTokens.dp(c, 28), 1f));
            pair.addView(after, new LinearLayout.LayoutParams(0, UiTokens.dp(c, 28), 1f));
            cell.addView(pair, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTokens.dp(c, 28)));
            TextView n = SettingsStyle.text(c, keyNames[i], 10.5f, UiTokens.MUTED);
            n.setPadding(0, UiTokens.dp(c, 4), 0, 0);
            n.setMaxLines(1);
            cell.addView(n);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) cp.setMarginStart(UiTokens.dp(c, 6));
            sw.addView(cell, cp);
        }
        col.addView(sw, top(c, UiTokens.dp(c, 6)));
        // «Как это определяется»
        TextView how = SettingsStyle.text(c, "", 12.5f, accent);
        how.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        how.setMinHeight(UiTokens.dp(c, 32));
        how.setGravity(Gravity.CENTER_VERTICAL);
        how.setTag("lut_how");
        TextView howText = SettingsStyle.text(c, howText(c), 12, UiTokens.MUTED);
        howText.setLineSpacing(UiTokens.dp(c, 3), 1f);
        Runnable paint = () -> {
            how.setText((howOpen ? "▾ " : "▸ ") + Lang.t(c, "Как это определяется", "How it is worked out"));
            howText.setVisibility(howOpen ? View.VISIBLE : View.GONE);
        };
        how.setOnClickListener(v -> { howOpen = !howOpen; paint.run(); });
        paint.run();
        col.addView(how, top(c, UiTokens.dp(c, 4)));
        col.addView(howText);
        return col;
    }

    private static CharSequence howText(Context c) {
        return Lang.t(c,
                "Через LUT прогоняются эталонные цвета и серые тона, и сравнивается «до» и «после». Так же и для ваших файлов — описание из файла не нужно.\n"
                        + "• Контраст — разница яркости между тёмно-серым (10 %) и светло-серым (90 %): больше на 5 % — «сильнее», меньше на 3 % — «мягче». Если середина (30–70 %) растёт, а края нет — «ярче в середине, мягче по краям».\n"
                        + "• Чёрный / Белый — во что превращается чистый чёрный и чистый белый: чёрный светлее 1,5 % — «приподнят», белый темнее 98,5 % — «приглушён».\n"
                        + "• Цвета — насыщенность 18 цветов шкалы: больше на 4 % — «насыщеннее», меньше на 5 % — «спокойнее», почти ноль — «чёрно-белые».\n"
                        + "• Тени / Света — оттенок тёмно-серого (20 %) и светло-серого (80 %): красного больше синего — «теплее», наоборот — «холоднее».\n"
                        + "• Кожа — светлый тон кожи со шкалы стал насыщеннее. Зелень — цвет листвы ушёл к жёлтому (оливковая) или к синему (бирюзовая).",
                "Reference colours and greys go through the LUT and «before» is compared with «after» - the same for your own files, no description in the file is needed.\n"
                        + "• Contrast - the brightness spread of dark grey (10 %) and light grey (90 %): 5 % more is «stronger», 3 % less «softer». A middle (30-70 %) that grows while the ends do not: «punchier middle, softer ends».\n"
                        + "• Black / White - what pure black and pure white become: black above 1.5 % is «lifted», white below 98.5 % «muted».\n"
                        + "• Colours - the saturation of the chart's 18 colours: 4 % more is «richer», 5 % less «calmer», about none «black and white».\n"
                        + "• Shadows / Highlights - the tint of dark grey (20 %) and light grey (80 %): more red than blue is «warmer», the other way «cooler».\n"
                        + "• Skin - the chart's light skin got richer. Greens - foliage moved to yellow (olive) or to blue (teal).");
    }

    private static LinearLayout.LayoutParams top(Context c, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = margin;
        return lp;
    }

    /** Children left to right, wrapped onto new lines (the concept's flex-wrap chips). */
    static final class FlowLayout extends ViewGroup {
        private final int gap;

        FlowLayout(Context c, int gap) {
            super(c);
            this.gap = gap;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int max = MeasureSpec.getSize(widthSpec), x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                v.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + v.getMeasuredWidth() > max) {
                    x = 0;
                    y += line + gap;
                    line = 0;
                }
                x += v.getMeasuredWidth() + gap;
                line = Math.max(line, v.getMeasuredHeight());
            }
            setMeasuredDimension(max, y + line);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l, x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (x > 0 && x + v.getMeasuredWidth() > max) {
                    x = 0;
                    y += line + gap;
                    line = 0;
                }
                v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
                x += v.getMeasuredWidth() + gap;
                line = Math.max(line, v.getMeasuredHeight());
            }
        }
    }
}
