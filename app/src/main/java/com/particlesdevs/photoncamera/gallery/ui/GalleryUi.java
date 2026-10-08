package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.Calendar;
import java.util.Locale;

/**
 * P59b: the gallery in the SCAMERA card style (owner's GALLERY_TASK.md, concept "SCAMERA Gallery Concept"): the tokens of
 * the settings / shade / manual controls (UiTokens through SettingsStyle) and the pieces every gallery screen is built from.
 * Texts in ru / en (Lang). Built in code like the settings screens; the data and file logic stay where they were.
 */
public final class GalleryUi {
    private GalleryUi() {}

    public static final int BG = SettingsStyle.BG, CARD = SettingsStyle.CARD, LINE = SettingsStyle.LINE, TEXT = SettingsStyle.TEXT,
            MUTED = SettingsStyle.MUTED, SHEET = SettingsStyle.SHEET, WARN = SettingsStyle.WARN, INK = SettingsStyle.INK;
    /** Dark backing of the thumbnail badges and the floating pills over a photo. */
    public static final int SCRIM = 0xE60D0F12, PILL_BG = 0xCC0D0F12;

    public static int dp(Context c, float v) { return SettingsStyle.dp(c, v); }

    public static int accent(Context c) { return SettingsStyle.accent(c); }

    public static GradientDrawable card(Context c, int fill, float radius) {
        return SettingsStyle.shape(c, fill, LINE, radius);
    }

    public static GradientDrawable round(Context c, int fill, int stroke, float radius) {
        return SettingsStyle.shape(c, fill, stroke, radius);
    }

    /** A pressable background: the shape with a light ripple. */
    public static RippleDrawable pressable(GradientDrawable shape) {
        return new RippleDrawable(ColorStateList.valueOf(0x29FFFFFF), shape, null);
    }

    public static TextView text(Context c, CharSequence value, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(value);
        UiTokens.setTextSp(t, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** One line, no ellipsis allowed by the task: the text shrinks to fit instead (down to 70 %). */
    public static TextView fitText(Context c, CharSequence value, float sp, int color) {
        TextView t = text(c, value, sp, color);
        t.setSingleLine(true);
        t.setEllipsize(null);
        t.setHorizontallyScrolling(false);
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(t,
                Math.max(1, Math.round(sp * .7f)), Math.round(sp), 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        return t;
    }

    public static ImageView icon(Context c, @DrawableRes int res, int color, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(color));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** A 44dp square card button with an accent icon (header and viewer buttons). */
    public static FrameLayout squareButton(Context c, @DrawableRes int res, String description, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(c);
        b.setBackground(pressable(card(c, CARD, 16)));
        b.setContentDescription(description);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        b.setTooltipText(description);
        ImageView i = icon(c, res, accent(c), 22);
        b.addView(i, new FrameLayout.LayoutParams(dp(c, 22), dp(c, 22), Gravity.CENTER));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        return b;
    }

    /**
     * An action of the selection bar and of the viewer's button card: the accent icon above a short muted label, every
     * button the same height; {@code warn} draws the icon in the warning colour.
     */
    public static LinearLayout actionButton(Context c, @DrawableRes int res, String label, boolean warn, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(c, 8), 0, dp(c, 8));
        b.setMinimumHeight(dp(c, 52));
        b.setBackground(pressable(round(c, Color.TRANSPARENT, Color.TRANSPARENT, 14)));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(click);
        b.setContentDescription(label);
        ImageView i = icon(c, res, warn ? WARN : accent(c), 22);
        b.addView(i);
        TextView t = fitText(c, label, 11, MUTED);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 3);
        b.addView(t, lp);
        return b;
    }

    /** The disabled look of an action (the concept's opacity .35). */
    public static void setEnabledLook(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : .35f);
    }

    /** A folder chip «SCAMERA 128»: at least 40dp tall; the active one filled with the accent. */
    public static TextView chip(Context c, String name, int count, boolean active) {
        int accent = accent(c);
        TextView t = new TextView(c);
        UiTokens.setTextSp(t, 15);
        t.setSingleLine(true);
        t.setEllipsize(null);
        t.setMinHeight(dp(c, 40));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder(name);
        if (count >= 0) {
            int start = s.length();
            s.append("  ").append(String.valueOf(count));
            s.setSpan(new android.text.style.RelativeSizeSpan(.8f), start, s.length(), 0);
        }
        t.setText(s);
        t.setTextColor(active ? INK : MUTED);
        t.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setBackground(pressable(round(c, active ? accent : CARD, active ? accent : LINE, 999)));
        t.setSelected(active);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** A rounded pill over a photo (zoom «250 %», mini EXIF), dark backing. */
    public static TextView pill(Context c, String value, boolean accentText) {
        TextView t = text(c, value, 12.5f, accentText ? accent(c) : TEXT);
        t.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        t.setBackground(round(c, PILL_BG, LINE, 999));
        t.setGravity(Gravity.CENTER);
        if (accentText) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** The header of the gallery screens: «SCAMERA» eyebrow, the title, a 44dp card button on each side. */
    public static FrameLayout header(Context c, String title, View left, @Nullable View right) {
        FrameLayout h = new FrameLayout(c);
        h.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 10));
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView eyebrow = text(c, "SCAMERA", 12, MUTED);
        eyebrow.setLetterSpacing(.32f);
        titles.addView(eyebrow);
        TextView t = fitText(c, title, 26, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(c, 6);
        titles.addView(t, tp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        lp.leftMargin = lp.rightMargin = dp(c, 52);
        h.addView(titles, lp);
        h.addView(left, new FrameLayout.LayoutParams(dp(c, 44), dp(c, 44), Gravity.START | Gravity.CENTER_VERTICAL));
        if (right != null) h.addView(right, new FrameLayout.LayoutParams(dp(c, 44), dp(c, 44), Gravity.END | Gravity.CENTER_VERTICAL));
        return h;
    }

    /** The section label of the sheets («СЪЁМКА»): small, uppercase, letter-spaced accent. */
    public static TextView sectionLabel(Context c, String value) {
        TextView t = text(c, value.toUpperCase(Lang.ru() ? new Locale("ru") : Locale.ROOT), 12, accent(c));
        t.setLetterSpacing(.14f);
        t.setAlpha(.85f);
        t.setPadding(dp(c, 4), dp(c, 12), dp(c, 4), dp(c, 8));
        return t;
    }

    // ---------------------------------------------------------------- texts

    /** «1 снимок / 2 снимка / 5 снимков», "1 photo / 2 photos". */
    public static String shots(int n) {
        String en = n == 1 ? "photo" : "photos";
        switch (ruForm(n)) {
            case 0: return n + " " + Lang.t("снимок", en);
            case 1: return n + " " + Lang.t("снимка", en);
            default: return n + " " + Lang.t("снимков", en);
        }
    }

    /** The day section's count «N фото» / "N photos". */
    public static String dayCount(int n) {
        return Lang.t(n + " фото", n + (n == 1 ? " photo" : " photos"));
    }

    /** Russian plural form of {@code n}: 0 one (1, 21), 1 few (2..4, 22..24), 2 many (0, 5..20, 11..14). */
    public static int ruForm(int n) {
        int a = Math.abs(n) % 100, b = Math.abs(n) % 10;
        if (a > 10 && a < 20) return 2;
        if (b == 1) return 0;
        if (b >= 2 && b <= 4) return 1;
        return 2;
    }

    /** «Сегодня», «Вчера», «6 октября» (the year when it is not this one) / "Today", "Yesterday", "October 6". */
    public static String dayLabel(long millis, long nowMillis) {
        Calendar day = Calendar.getInstance(), now = Calendar.getInstance();
        day.setTimeInMillis(millis);
        now.setTimeInMillis(nowMillis);
        if (sameDay(day, now)) return Lang.t("Сегодня", "Today");
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(day, now)) return Lang.t("Вчера", "Yesterday");
        now.add(Calendar.DAY_OF_YEAR, 1);
        boolean ru = Lang.ru();
        int d = day.get(Calendar.DAY_OF_MONTH), y = day.get(Calendar.YEAR);
        String month = monthName(day.get(Calendar.MONTH));
        String s = ru ? d + " " + month : month + " " + d;
        if (y != now.get(Calendar.YEAR)) s += ru ? " " + y : ", " + y;
        return s;
    }

    /** «Сегодня, 11:17». */
    public static String dayTime(long millis, long nowMillis) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(millis);
        return dayLabel(millis, nowMillis) + ", " + String.format(Locale.ROOT, "%02d:%02d", day.get(Calendar.HOUR_OF_DAY), day.get(Calendar.MINUTE));
    }

    static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** The month in the genitive («октября») / "October". */
    static String monthName(int month) {
        switch (month) {
            case 0: return Lang.t("января", "January");
            case 1: return Lang.t("февраля", "February");
            case 2: return Lang.t("марта", "March");
            case 3: return Lang.t("апреля", "April");
            case 4: return Lang.t("мая", "May");
            case 5: return Lang.t("июня", "June");
            case 6: return Lang.t("июля", "July");
            case 7: return Lang.t("августа", "August");
            case 8: return Lang.t("сентября", "September");
            case 9: return Lang.t("октября", "October");
            case 10: return Lang.t("ноября", "November");
            default: return Lang.t("декабря", "December");
        }
    }

    /** A decimal with the locale's separator («1,85» in ru). */
    public static String decimal(double v, int digits) {
        String s = String.format(Locale.ROOT, "%." + digits + "f", v);
        if (digits > 0) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return Lang.ru() ? s.replace('.', ',') : s;
    }

    /** "1.2 MB" / «1,2 МБ». */
    public static String size(long bytes) {
        if (bytes >= 1L << 20) return decimal(bytes / (double) (1L << 20), 1) + Lang.t(" МБ", " MB");
        if (bytes >= 1L << 10) return Math.round(bytes / 1024.0) + Lang.t(" КБ", " KB");
        return bytes + Lang.t(" Б", " B");
    }

    static boolean empty(@Nullable CharSequence s) {
        return TextUtils.isEmpty(s);
    }
}
