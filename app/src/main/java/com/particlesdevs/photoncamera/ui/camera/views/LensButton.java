package com.particlesdevs.photoncamera.ui.camera.views;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Layout;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;

import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;

/**
 * One lens of the strip ({@link AuxButtonsLayout}). P161 (owner, 2026-10-16: the P157 discs and capsule were «один в один
 * гугл камера», «Сделай стиль что-то между material you и нашим»): Material You's tonal colours of the camera accent on our
 * card shapes - every lens a rounded tile, wider than tall, with the corners of the gallery card in proportion, a tonal
 * fill and a faint tonal outline (our CARD + LINE), its short number («0,6», «2», «2,8»); the active lens a tile of the
 * same height filled with the accent, its full label in bold INK («1×», the live zoom «2,3×»), as an active tile of the
 * shade. No size jump, no circles. A press ripples in the accent. Sizes in proportion to the screen's width
 * ({@link BottomChrome#scale}).
 * <p>
 * P32 (owner, 2026-10-07): the button itself never rotates, so its background and the selected pill keep their portrait
 * shape inside the strip in every orientation. Only the label turns upright with the phone (0 / 90 / 180 / 270 degrees,
 * around the button's centre), and a turned label taller than the button («1× (2)» in landscape) shrinks to fit.
 */
@SuppressLint("AppCompatCustomView") // a plain Button as before: no AppCompat button style (insets, letter spacing)
public class LensButton extends Button {
    /** Room a turned label keeps from the button's edges, on each side. */
    static final float LABEL_INSET_DP = 4f;

    /** The label's angle in degrees, clockwise like {@link #setRotation}. */
    private float labelRotation;
    /** Where the running turn ends, in [0, 360). */
    private float turnTarget;
    private ValueAnimator turn;

    public LensButton(Context context) {
        super(context);
        unit = getResources().getDisplayMetrics().density * BottomChrome.scale(context);
        setMinHeight(0);
        setMinimumHeight(0);
        setGravity(Gravity.CENTER);
        setIncludeFontPadding(false);
        setMaxLines(1);
        setHorizontallyScrolling(false);
        int accent = AccentPalette.camera(context);
        // the other lenses in the accent's tone of the muted text (Material You's on-surface-variant)
        setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_selected}, {}},
                new int[]{SettingsStyle.INK, BottomChrome.tone(SettingsStyle.MUTED, accent, .22f)}));
        float radius = CORNER_DP * unit;
        GradientDrawable selected = new GradientDrawable();
        selected.setColor(accent);
        selected.setCornerRadius(radius);
        GradientDrawable tile = new GradientDrawable();
        tile.setColor(BottomChrome.tone(SettingsStyle.CARD, accent, .12f));
        tile.setStroke(Math.max(1, Math.round(getResources().getDisplayMetrics().density)),
                BottomChrome.tone(SettingsStyle.CARD, accent, .26f));
        tile.setCornerRadius(radius);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, selected);
        states.addState(new int[]{}, tile);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radius);
        setBackground(new RippleDrawable(ColorStateList.valueOf((accent & 0x00FFFFFF) | 0x33000000), states, mask));
        setStateListAnimator(null);
        setBackgroundTintList(null);
        setTransformationMethod(null);
        applyState();
    }

    /**
     * P161: every tile's height, the least width of a tile and of the active one, and the corner radius (dp before the
     * scale; the corner is the gallery card's 20 of 52dp in proportion).
     */
    static final float HEIGHT_DP = 38f, WIDTH_DP = 42f, SELECTED_WIDTH_DP = 56f, CORNER_DP = 14f;
    private final float unit;
    /**
     * P181 (owner, 2026-10-16: «у нас модули выходят за границы экрана»): the strip's fit, MIN_FIT..1 - the tile's least
     * width, padding and text shrink together when the lenses do not fit the screen's width ({@link AuxButtonsLayout}).
     */
    static final float MIN_FIT = 0.5f;
    private float fit = 1f;
    /** The text size, least width and side padding at fit 1 (px), as {@link #applyState} last set them. */
    private float baseTextPx, baseMinWidth, basePadding;

    /** Shrinks the tile to {@code f} of its own size (MIN_FIT..1). */
    void setFit(float f) {
        f = Math.max(MIN_FIT, Math.min(1f, f));
        if (Math.abs(f - fit) < 0.005f) return;
        fit = f;
        applyState();
    }

    float fit() {
        return fit;
    }

    /** The tile's width at fit 1: its label at the full text size between its padding, at least its least width. */
    float naturalWidth() {
        CharSequence text = getText();
        android.text.TextPaint paint = new android.text.TextPaint(getPaint());
        paint.setTextSize(baseTextPx);
        float label = text == null ? 0f : paint.measureText(text, 0, text.length());
        return Math.max(baseMinWidth, (float) Math.ceil(label) + 2 * basePadding);
    }
    /** The label as the strip set it; a tile not in use shows its short form ({@link #shortLabel}). */
    private CharSequence fullLabel;

    /** «2,8×» -> «2,8», «1x» -> «1»; anything else (a custom name, «1× (2)») stays as it is. */
    static CharSequence shortLabel(CharSequence label) {
        if (label == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d+(?:[.,]\\d+)?)\\s*[×xX]\\s*$").matcher(label);
        return m.matches() ? m.group(1) : label;
    }

    /** The label as the strip set it (the full one, also on a tile not in use). */
    public CharSequence fullLabel() {
        return fullLabel != null ? fullLabel : getText();
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        fullLabel = text;
        super.setText(isSelected() || text == null ? text : shortLabel(text), type);
    }

    @Override
    public void setSelected(boolean selected) {
        boolean changed = selected != isSelected();
        super.setSelected(selected);
        if (!changed) return;
        if (fullLabel != null) super.setText(selected ? fullLabel : shortLabel(fullLabel), BufferType.NORMAL);
        applyState();
        requestLayout();
    }

    /** The active tile: 14sp bold, at least 56dp wide; the others 13sp, 42dp; all 38dp high (times the scale). */
    private void applyState() {
        if (unit == 0f) return;   // the TextView constructor sets the text before this one runs
        boolean on = isSelected();
        com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.setTextSp(this, on ? 14 : 13);
        baseTextPx = getTextSize() * BottomChrome.scale(getContext());
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseTextPx * fit);
        setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        baseMinWidth = (on ? SELECTED_WIDTH_DP : WIDTH_DP) * unit;
        int width = Math.round(baseMinWidth * fit);
        setMinWidth(width);
        setMinimumWidth(width);
        basePadding = (on ? 10f : 6f) * unit;
        int padding = Math.round(basePadding * fit);
        setPadding(padding, 0, padding, 0);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Math.round(HEIGHT_DP * unit), MeasureSpec.EXACTLY));
    }

    /** Sets the label's angle at once (a new button takes the current orientation). */
    public void setLabelRotation(float degrees) {
        if (degrees == labelRotation) return;
        labelRotation = degrees;
        invalidate();
    }

    public float getLabelRotation() {
        return labelRotation;
    }

    /** Turns the label to {@code degrees} the short way round, as the strip turned the whole button before. */
    public void turnLabel(float degrees, long duration) {
        float target = normalize(degrees);
        // Every rebind of the camera screen sends the orientation again: a turn already on its way keeps going.
        if (turn != null && turn.isRunning() && target == turnTarget) return;
        if (turn != null) turn.cancel();
        turnTarget = target;
        float from = normalize(labelRotation), to = shortestTurn(from, target);
        setLabelRotation(from);
        if (duration <= 0 || from == to) {
            setLabelRotation(target);
            return;
        }
        turn = ValueAnimator.ofFloat(from, to);
        turn.setDuration(duration);
        turn.setInterpolator(new DecelerateInterpolator());
        turn.addUpdateListener(a -> setLabelRotation((float) a.getAnimatedValue()));
        turn.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        if (layout == null) {
            super.onDraw(canvas);
            return;
        }
        // The background (the pill) is drawn before onDraw, so only the text turns.
        float angle = normalize(labelRotation);
        float inset = 2 * LABEL_INSET_DP * getResources().getDisplayMetrics().density * BottomChrome.scale(getContext());
        float scale = labelScale(angle, labelWidth(layout), layout.getHeight(), getWidth() - inset, getHeight() - inset);
        if (angle == 0f && scale == 1f) {
            super.onDraw(canvas);
            return;
        }
        // The text is centred in the button (equal side padding, centre gravity): turn it around the button's centre.
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        int saved = canvas.save();
        canvas.rotate(angle, cx, cy);
        canvas.scale(scale, scale, cx, cy);
        super.onDraw(canvas);
        canvas.restoreToCount(saved);
    }

    private static float labelWidth(Layout layout) {
        float width = 0;
        for (int i = 0; i < layout.getLineCount(); i++) width = Math.max(width, layout.getLineWidth(i));
        return width;
    }

    /** {@code degrees} in [0, 360). */
    static float normalize(float degrees) {
        float d = degrees % 360f;
        return d < 0 ? d + 360f : d;
    }

    /** The angle equal to {@code to} (mod 360) nearest to {@code from}: a label turns at most half a turn. */
    static float shortestTurn(float from, float to) {
        float delta = (to - from) % 360f;
        if (delta > 180f) delta -= 360f;
        else if (delta <= -180f) delta += 360f;
        return from + delta;
    }

    /**
     * Scale that fits a label of {@code textWidth} x {@code textHeight}, turned by {@code degrees}, into a box of
     * {@code boxWidth} x {@code boxHeight} (the button minus the inset): 1 when it fits as it is, never above 1.
     */
    static float labelScale(float degrees, float textWidth, float textHeight, float boxWidth, float boxHeight) {
        if (textWidth <= 0 || textHeight <= 0 || boxWidth <= 0 || boxHeight <= 0) return 1f;
        double a = Math.toRadians(degrees);
        double cos = Math.abs(Math.cos(a)), sin = Math.abs(Math.sin(a));
        // The turned label's bounding box.
        double width = textWidth * cos + textHeight * sin, height = textWidth * sin + textHeight * cos;
        double scale = Math.min(1.0, Math.min(boxWidth / width, boxHeight / height));
        return (float) scale;
    }
}
