package com.particlesdevs.photoncamera.circularbarlib.ui.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.particlesdevs.photoncamera.circularbarlib.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;

/**
 * One parameter of the manual strip: three centred lines, no name (the owner's rule: icons only, the name is the content
 * description). The parameter icon (22dp), the value (14sp tabular figures, shrunk rather than ever ellipsized) and a
 * state line («авто», «ручн.», «выкл.»).
 * <ul>
 * <li>{@link #AUTO}: icon and value in MUTED (the camera's metered value);</li>
 * <li>{@link #MANUAL}: a chip fill (the accent over the card), icon and value in the accent;</li>
 * <li>{@link #LOCKED}: EV while ISO and shutter are manual: dimmed to 40 %, value «—», state «выкл.», disabled for
 * accessibility (it still takes taps, which explain why);</li>
 * <li>{@link #UNAVAILABLE}: the camera has no such control (fixed focus, no manual white balance): dimmed, «—».</li>
 * </ul>
 * The selected chip (its ruler is open) gets a 1.5dp accent outline.
 */
public class ManualChipView extends LinearLayout {
    public static final int AUTO = 0, MANUAL = 1, LOCKED = 2, UNAVAILABLE = 3;
    /** Value text size: at most this, shrunk to fit the chip down to {@link #VALUE_MIN_SP}. */
    public static final float VALUE_MAX_SP = 14f, VALUE_MIN_SP = 9f;
    public static final float STATE_SP = 9f, STATE_MIN_SP = 7f;
    /** Alpha of a locked or unavailable chip. */
    public static final float LOCKED_ALPHA = .4f;

    private final ImageView icon;
    private final TextView value, state;
    private final GradientDrawable background = new GradientDrawable();
    private int chipState = AUTO;
    private boolean chosen;
    private int accent;

    public ManualChipView(Context context) {
        this(context, null);
    }

    public ManualChipView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(UiTokens.dp(context, 2), UiTokens.dp(context, 7), UiTokens.dp(context, 2), UiTokens.dp(context, 6));
        setClickable(true);
        setLongClickable(true);
        setFocusable(true);
        setHapticFeedbackEnabled(true);
        accent = UiTokens.cameraAccent(context);
        icon = new ImageView(context);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = UiTokens.dp(context, 22);
        addView(icon, new LayoutParams(size, size));
        value = line(context, VALUE_MAX_SP);
        value.setFontFeatureSettings("tnum");
        // Each line takes LINE_HEIGHT x its nominal size, whatever the system font's own metrics (P43): the chip is
        // 7 + 22 + 3 + 16.8 + 3 + 10.8 + 6 dp as in the concept, the same for every chip (a shrunk value keeps the
        // line), and grows with the font size only up to UiTokens.FONT_SCALE_CAP.
        addView(value, lineParams(value, VALUE_MAX_SP, UiTokens.dp(context, 3)));
        state = line(context, STATE_SP);
        state.setAllCaps(true);
        state.setLetterSpacing(.06f);
        state.setTextColor(UiTokens.MUTED);
        addView(state, lineParams(state, STATE_SP, UiTokens.dp(context, 3)));
        background.setCornerRadius(UiTokens.dp(context, 16));
        setBackground(background);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ManualChipView);
            int res = a.getResourceId(R.styleable.ManualChipView_manualChipIcon, 0);
            a.recycle();
            if (res != 0) icon.setImageResource(res);
        }
        state.setText(R.string.manual_state_auto);
        apply();
    }

    /** Line height over the text size (the concept's «normal» line height of its font). */
    public static final float LINE_HEIGHT = 1.2f;

    /** A line's share of the chip: {@link #LINE_HEIGHT} x its nominal size, rounded. */
    public static int lineHeight(Context context, float sp) {
        return Math.round(UiTokens.spPx(context, sp) * LINE_HEIGHT);
    }

    /**
     * The view keeps its font's natural line height (nothing is clipped, also with a font whose metrics are taller than
     * Roboto's), and its margins take up the difference to {@link #lineHeight}, so every chip has the same height.
     */
    private static LayoutParams lineParams(TextView t, float sp, int gapAbove) {
        Paint.FontMetricsInt fm = t.getPaint().getFontMetricsInt();
        int natural = fm.descent - fm.ascent;
        int extra = lineHeight(t.getContext(), sp) - natural;
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, natural);
        lp.topMargin = gapAbove + Math.floorDiv(extra, 2);
        lp.bottomMargin = extra - Math.floorDiv(extra, 2);
        return lp;
    }

    private static TextView line(Context context, float sp) {
        TextView t = new TextView(context);
        UiTokens.setTextSp(t, sp);
        t.setIncludeFontPadding(false);
        // The line's height comes from the primary font (lineParams), not from a taller fallback font.
        t.setFallbackLineSpacing(false);
        // A shrunk value is centred in its line.
        t.setGravity(Gravity.CENTER);
        // One line without horizontal scrolling (wrap_content, centred by the chip): a single-line TextView lays its
        // text out on a very wide line, which a centred gravity would push out of sight.
        t.setMaxLines(1);
        t.setHorizontallyScrolling(false);
        t.setEllipsize(null);
        t.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    public void setIcon(int res) {
        icon.setImageResource(res);
        apply();
    }

    public ImageView getIcon() {
        return icon;
    }

    public TextView getValueView() {
        return value;
    }

    public TextView getStateView() {
        return state;
    }

    public void setAccent(int accent) {
        this.accent = accent;
        apply();
    }

    /** The chip's value and state ({@link #AUTO}, {@link #MANUAL}, {@link #LOCKED}, {@link #UNAVAILABLE}). */
    public void bind(CharSequence valueText, int newState) {
        chipState = newState;
        boolean off = newState == LOCKED || newState == UNAVAILABLE;
        value.setText(off ? "—" : valueText);
        state.setText(newState == MANUAL ? R.string.manual_state_manual : off ? R.string.manual_state_off : R.string.manual_state_auto);
        apply();
        requestLayout();
    }

    public int getChipState() {
        return chipState;
    }

    /** The chip whose ruler is open: a 1.5dp accent outline. */
    public void setChosen(boolean chosen) {
        this.chosen = chosen;
        setSelected(chosen);
        apply();
    }

    public boolean isChosen() {
        return chosen;
    }

    private void apply() {
        boolean manual = chipState == MANUAL;
        int color = manual ? accent : UiTokens.MUTED;
        icon.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        value.setTextColor(manual ? accent : UiTokens.MUTED);
        background.setColor(manual ? UiTokens.chipBg(accent) : 0);
        if (chosen) background.setStroke(Math.round(1.5f * getResources().getDisplayMetrics().density), accent);
        else background.setStroke(0, 0);
        setAlpha(chipState == LOCKED || chipState == UNAVAILABLE ? LOCKED_ALPHA : 1f);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Never an ellipsis: the value and the state line shrink to the chip's width instead.
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            int room = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
            valueSp = fit(value, room, VALUE_MAX_SP, VALUE_MIN_SP);
            fit(state, room, STATE_SP, STATE_MIN_SP);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private float valueSp = VALUE_MAX_SP;

    /**
     * The largest size from {@code maxSp} down to {@code minSp} (0.5sp steps) at which the text fits {@code room} px; sp
     * are scaled by the font size up to {@link UiTokens#FONT_SCALE_CAP} (P43), so a large system font cannot make the
     * strip twice as tall. Returns the size chosen, in sp.
     */
    static float fit(TextView view, int room, float maxSp, float minSp) {
        CharSequence text = view.getText();
        android.content.res.Resources res = view.getResources();
        float sp = maxSp;
        if (text != null && text.length() > 0 && room > 0) {
            TextPaint paint = new TextPaint(view.getPaint());
            String shown = view.isAllCaps() ? text.toString().toUpperCase(java.util.Locale.getDefault()) : text.toString();
            for (; sp > minSp; sp -= .5f) {
                paint.setTextSize(UiTokens.spPx(res, sp));
                if (paint.measureText(shown) <= room) break;
            }
            sp = Math.max(minSp, sp);
        }
        float px = UiTokens.spPx(res, sp);
        if (Math.abs(view.getTextSize() - px) > .01f) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        return sp;
    }

    /** The value's current size in sp, before the font scale (tests: «1/8000» must stay at 13sp or more at 360dp). */
    public float valueSp() {
        return valueSp;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        // A locked EV or a missing control is disabled for accessibility, though it still explains itself on a tap.
        if (chipState == LOCKED || chipState == UNAVAILABLE) info.setEnabled(false);
        info.setClassName(android.widget.Button.class.getName());
        info.setSelected(chosen);
    }

    /** True when no line is cut: every text is drawn whole within the chip (tests). */
    public boolean textFits() {
        for (TextView t : new TextView[]{value, state}) {
            if (t.getLayout() == null) continue;
            if (t.getLayout().getEllipsisCount(0) > 0 || t.getLayout().getLineCount() > 1) return false;
            if (t.getLayout().getWidth() > t.getWidth() + 1) return false; // laid out on a wider line than shown
            float w = t.getLayout().getLineWidth(0);
            if (w > getWidth() - getPaddingLeft() - getPaddingRight() + 0.5f) return false;
            if (t.getLeft() < 0 || t.getRight() > getWidth()) return false;
        }
        return true;
    }
}
