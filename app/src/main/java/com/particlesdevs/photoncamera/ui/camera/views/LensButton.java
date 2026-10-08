package com.particlesdevs.photoncamera.ui.camera.views;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Layout;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;

import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;

/**
 * One lens of the strip ({@link AuxButtonsLayout}): MUTED text on nothing, the active lens INK on a pill of the camera
 * accent.
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
        float density = getResources().getDisplayMetrics().density;
        setMinimumWidth(Math.round(48 * density));
        setMinWidth(Math.round(48 * density));
        setMinHeight(0);
        setMinimumHeight(0);
        int padding = Math.round(density * 9f);
        setPadding(padding, 0, padding, 0);
        setGravity(Gravity.CENTER);
        setIncludeFontPadding(false);
        setMaxLines(1);
        setHorizontallyScrolling(false);
        // 14sp, grown with the font size only up to UiTokens.FONT_SCALE_CAP (P43): the pill is a fixed 40dp high.
        com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.setTextSp(this, 14);
        setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_selected}, {}},
                new int[]{SettingsStyle.INK, SettingsStyle.MUTED}));
        GradientDrawable selected = new GradientDrawable();
        selected.setColor(AccentPalette.camera(context));
        selected.setCornerRadius(100 * density);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, selected);
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        setBackground(states);
        setStateListAnimator(null);
        setBackgroundTintList(null);
        setTransformationMethod(null);
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
        float inset = 2 * LABEL_INSET_DP * getResources().getDisplayMetrics().density;
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
