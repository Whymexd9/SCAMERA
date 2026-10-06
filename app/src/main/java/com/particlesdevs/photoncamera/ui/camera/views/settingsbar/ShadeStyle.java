package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.widget.TextView;

import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;

/**
 * The card language of the settings screens (SettingsStyle, P6b) on the camera screen (P25): CARD tiles with a LINE
 * stroke, the camera accent (AccentPalette.camera, owner's answer 5) with INK on accent fills, MUTED secondary text.
 */
public final class ShadeStyle {
    public static final int BG = SettingsStyle.BG, CARD = SettingsStyle.CARD, LINE = SettingsStyle.LINE, TEXT = SettingsStyle.TEXT,
            MUTED = SettingsStyle.MUTED, INK = SettingsStyle.INK, FIELD = SettingsStyle.FIELD;
    public static final float DIMMED = SettingsStyle.DIMMED;
    private static final int RIPPLE = 0x29FFFFFF;

    private ShadeStyle() {}

    public static int dp(Context c, float v) {
        return SettingsStyle.dp(c, v);
    }

    /** The camera accent: the user's accent, or the camera yellow with the default theme. */
    public static int accent(Context c) {
        return AccentPalette.camera(c);
    }

    /** The accent at a given alpha over the card colour (chips, pills). */
    public static int tint(int accent, float alpha) {
        return SettingsStyle.tint(accent, alpha);
    }

    /** A rounded card: fill, optional 1dp stroke. */
    public static GradientDrawable card(Context c, int fill, int stroke, float radiusDp) {
        return SettingsStyle.shape(c, fill, stroke, radiusDp);
    }

    /** A card with a ripple bounded by its own shape. */
    public static Drawable pressable(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable mask = card(c, 0xFFFFFFFF, 0, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), card(c, fill, stroke, radiusDp), mask);
    }

    /** The sheet: BG with a LINE stroke and 26dp top corners; the stroke has no bottom edge. */
    public static Drawable sheet(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(BG);
        float r = dp(c, 26);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        d.setStroke(dp(c, 1), LINE);
        return new InsetDrawable(d, 0, 0, 0, -dp(c, 2));
    }

    /** Small uppercase accent label of a section («ЗАКРЕПЛЕНО», «HYBRID»). */
    public static void label(TextView t, int accent) {
        t.setAllCaps(true);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setLetterSpacing(.14f);
        t.setTextColor((accent & 0x00FFFFFF) | 0xD9000000);
        t.setIncludeFontPadding(false);
    }

    /**
     * Never an ellipsis (SHADE_TASK.md §2): one line, shrinking from {@code maxSp} down to {@code oneLineMinSp} (0.5sp
     * steps); when that does not fit, the largest size down to {@code minSp} at which the text takes at most
     * {@code maxLines} lines of {@code widthPx} without splitting a word.
     */
    public static void fit(TextView view, int widthPx, float maxSp, float oneLineMinSp, float minSp, int maxLines) {
        CharSequence text = view.getText();
        view.setMaxLines(maxLines);
        view.setEllipsize(null);
        if (text == null || text.length() == 0 || widthPx <= 0) {
            setSp(view, maxSp);
            return;
        }
        TextPaint paint = new TextPaint(view.getPaint());
        float scaled = view.getResources().getDisplayMetrics().scaledDensity;
        for (float sp = maxSp; sp >= oneLineMinSp - 1e-3f; sp -= .5f) {
            paint.setTextSize(sp * scaled);
            if (fits(text, paint, widthPx, 1)) {
                setSp(view, sp);
                return;
            }
        }
        float sp = maxSp;
        for (; sp > minSp; sp -= .5f) {
            paint.setTextSize(sp * scaled);
            if (fits(text, paint, widthPx, maxLines)) break;
        }
        setSp(view, Math.max(minSp, sp));
    }

    private static void setSp(TextView view, float sp) {
        float px = sp * view.getResources().getDisplayMetrics().scaledDensity;
        if (Math.abs(view.getTextSize() - px) > .01f) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
    }

    static boolean fits(CharSequence text, TextPaint paint, int width, int maxLines) {
        for (String word : text.toString().split("[\\s­]+"))
            if (paint.measureText(word) > width) return false;
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build();
        return layout.getLineCount() <= maxLines;
    }
}
