package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.processing.color.ToneCurve;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.function.Consumer;

/**
 * P144: the own tone curve as a graph to edit with a finger - x the exposure -5..+5 EV around middle grey, y the display value.
 * A tap on an empty spot adds a point there (any number of points), a drag moves the point under the finger (the ends move
 * only up and down), a hold on a point deletes it (not the ends). The line is the curve the shader gets (the monotone cubic
 * through the points); the dashed diagonal is the straight line. The touched point stays selected (a filled dot in a halo):
 * the preference's sliders and typed values move it (moveSelected).
 */
public final class ToneCurveView extends View {
    private static final float ASPECT = 0.78f;
    private ToneCurve curve = ToneCurve.defaults();
    @Nullable private Consumer<ToneCurve> onCommit;
    @Nullable private Runnable onChange;
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            fill = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            guide = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG), back = new Paint(Paint.ANTI_ALIAS_FLAG),
            halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF plot = new RectF(), frame = new RectF();
    private final Path path = new Path(), area = new Path();
    private final int touchSlop;
    private int active = -1, selected = -1;
    private boolean moved, added;
    private float downX, downY;
    private final Runnable deleteOnHold = this::deleteActive;
    /** P177: curves drawn under the own one for comparison (y at evenly spaced x), their names and colours; null: none. */
    @Nullable private float[][] refSamples;
    @Nullable private String[] refNames;
    @Nullable private int[] refColours;
    private final Paint refLine = new Paint(Paint.ANTI_ALIAS_FLAG), refLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path refPath = new Path();

    public ToneCurveView(Context c) {
        super(c);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        int accent = SettingsStyle.accent(c);
        back.setColor(SettingsStyle.FIELD);
        grid.setColor(SettingsStyle.LINE);
        grid.setStrokeWidth(dp(1));
        guide.setColor((SettingsStyle.MUTED & 0x00FFFFFF) | 0x66000000);
        guide.setStrokeWidth(dp(1.2f));
        guide.setStyle(Paint.Style.STROKE);
        guide.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(4)}, 0));
        line.setColor(accent);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(2.6f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor((accent & 0x00FFFFFF) | 0x1F000000);
        dot.setColor(accent);
        halo.setColor((accent & 0x00FFFFFF) | 0x40000000);
        ring.setColor(accent);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(2.2f));
        label.setColor(SettingsStyle.MUTED);
        label.setTextSize(dp(10.5f));
        setContentDescription(Lang.t(c, "Тоновая кривая", "Tone curve"));
        setHapticFeedbackEnabled(true);
        refLine.setStyle(Paint.Style.STROKE);
        refLine.setStrokeWidth(dp(1.6f));
        refLine.setStrokeCap(Paint.Cap.ROUND);
        refLine.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0));
        refLabel.setTextSize(dp(10.5f));
        refLabel.setFakeBoldText(true);
    }

    /**
     * P177: the curves to compare with (each y sampled at evenly spaced x over 0..1), drawn dashed in their colours under the
     * own curve with their names; null or empty: none.
     */
    public void setReferences(@Nullable float[][] samples, @Nullable String[] names, @Nullable int[] colours) {
        refSamples = samples;
        refNames = names;
        refColours = colours;
        invalidate();
    }

    /** The references shown (tests). */
    public int references() {
        return refSamples == null ? 0 : refSamples.length;
    }

    public void setCurve(ToneCurve curve) {
        this.curve = curve;
        active = -1;
        if (selected >= curve.size()) selected = curve.size() / 2;
        invalidate();
        changed();
    }

    /** The selected point, -1 for none. */
    public int selected() {
        return selected;
    }

    public void select(int i) {
        selected = i >= 0 && i < curve.size() ? i : -1;
        invalidate();
        changed();
    }

    /** Moves the selected point (the sliders, a typed value); the caller stores the curve. False with none selected. */
    public boolean moveSelected(float x, float y) {
        if (selected < 0 || selected >= curve.size()) return false;
        curve.move(selected, x, y);
        invalidate();
        return true;
    }

    /** Deletes the selected inner point (not the ends) and selects the one before it; stored through onCommit. */
    public boolean deleteSelected() {
        if (selected < 0 || !curve.remove(selected)) return false;
        selected--;
        invalidate();
        changed();
        commit();
        return true;
    }

    public ToneCurve curve() {
        return curve;
    }

    /** Called when a gesture ends with a changed curve (the preference stores it). */
    public void setOnCommit(@Nullable Consumer<ToneCurve> onCommit) {
        this.onCommit = onCommit;
    }

    /** Called live when the selection changes or a finger moves the selected point (the sliders follow). */
    public void setOnChange(@Nullable Runnable onChange) {
        this.onChange = onChange;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, Math.round(w * ASPECT));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pad = dp(14), bottom = dp(22);
        frame.set(0, 0, w, h);
        plot.set(pad, pad, w - pad, h - bottom);
    }

    // ───── geometry

    float px(float x) { return plot.left + x * plot.width(); }
    float py(float y) { return plot.bottom - y * plot.height(); }
    float vx(float sx) { return (sx - plot.left) / plot.width(); }
    float vy(float sy) { return (plot.bottom - sy) / plot.height(); }

    /** The point within reach of a touch (28dp), -1 for none. */
    int pointNear(float sx, float sy) {
        int best = -1;
        float bestD = dp(28);
        for (int i = 0; i < curve.size(); i++) {
            float d = (float) Math.hypot(px(curve.get(i).x) - sx, py(curve.get(i).y) - sy);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = dp(14);
        canvas.drawRoundRect(frame, r, r, back);
        for (int i = 1; i < 4; i++) {
            canvas.drawLine(px(i / 4f), plot.top, px(i / 4f), plot.bottom, grid);
            canvas.drawLine(plot.left, py(i / 4f), plot.right, py(i / 4f), grid);
        }
        canvas.drawLine(plot.left, plot.bottom, plot.right, plot.top, guide);
        drawReferences(canvas);
        canvas.drawText(Lang.t(getContext(), "−5 EV", "−5 EV"), plot.left, frame.bottom - dp(7), label);
        String mid = Lang.t(getContext(), "0 (серый)", "0 (grey)");
        canvas.drawText(mid, px(0.5f) - label.measureText(mid) / 2f, frame.bottom - dp(7), label);
        String right = "+5 EV";
        canvas.drawText(right, plot.right - label.measureText(right), frame.bottom - dp(7), label);
        float[] s = curve.samples(128);
        path.reset();
        area.reset();
        area.moveTo(px(0), plot.bottom);
        for (int i = 0; i < s.length; i++) {
            float x = px(i / (float) (s.length - 1)), y = py(s[i]);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            area.lineTo(x, y);
        }
        area.lineTo(px(1), plot.bottom);
        area.close();
        canvas.drawPath(area, fill);
        canvas.drawPath(path, line);
        for (int i = 0; i < curve.size(); i++) {
            float x = px(curve.get(i).x), y = py(curve.get(i).y);
            if (i == active) {
                canvas.drawCircle(x, y, dp(9), dot);
            } else if (i == selected) {
                canvas.drawCircle(x, y, dp(13), halo);
                canvas.drawCircle(x, y, dp(7), dot);
            } else {
                canvas.drawCircle(x, y, dp(6.5f), back);
                canvas.drawCircle(x, y, dp(6.5f), ring);
            }
        }
    }

    /** P177: the comparison curves, dashed, each named near its upper end (labels kept apart). */
    private void drawReferences(Canvas canvas) {
        if (refSamples == null) return;
        float lastLabelY = Float.NaN;
        for (int k = 0; k < refSamples.length; k++) {
            float[] s = refSamples[k];
            if (s == null || s.length < 2) continue;
            int colour = refColours != null && k < refColours.length ? refColours[k] : SettingsStyle.MUTED;
            refLine.setColor((colour & 0x00FFFFFF) | 0xD9000000);
            refPath.reset();
            for (int i = 0; i < s.length; i++) {
                float x = px(i / (float) (s.length - 1)), y = py(s[i]);
                if (i == 0) refPath.moveTo(x, y); else refPath.lineTo(x, y);
            }
            canvas.drawPath(refPath, refLine);
            if (refNames == null || k >= refNames.length || refNames[k] == null) continue;
            int at = Math.round(0.18f * (s.length - 1));   // the dark end: the curves part there
            float ly = py(s[at]) - dp(6);
            if (!Float.isNaN(lastLabelY) && Math.abs(ly - lastLabelY) < dp(12)) ly = lastLabelY - dp(12);
            lastLabelY = ly;
            refLabel.setColor(colour);
            canvas.drawText(refNames[k], px(at / (float) (s.length - 1)) - dp(4), Math.max(plot.top + dp(10), ly), refLabel);
        }
    }

    // ───── touch

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true); // the list must not scroll away
                downX = e.getX();
                downY = e.getY();
                moved = false;
                added = false;
                active = pointNear(downX, downY);
                if (active < 0 && plot.contains(downX, downY)) {
                    active = curve.add(vx(downX), vy(downY));
                    added = active >= 0;
                    if (added) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                if (active >= 0 && !added && active > 0 && active < curve.size() - 1)
                    postDelayed(deleteOnHold, ViewConfiguration.getLongPressTimeout());
                if (active >= 0) selected = active;
                invalidate();
                changed();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (active < 0) return true;
                if (!moved && Math.hypot(e.getX() - downX, e.getY() - downY) > touchSlop) {
                    moved = true;
                    removeCallbacks(deleteOnHold);
                }
                if (moved || added) {
                    curve.move(active, vx(e.getX()), vy(e.getY()));
                    invalidate();
                    changed();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(deleteOnHold);
                boolean changed = active >= 0 && (moved || added);
                active = -1;
                invalidate();
                if (changed) commit();
                return true;
            default:
                return true;
        }
    }

    /** A hold on an inner point: it goes. */
    private void deleteActive() {
        if (active >= 0 && curve.remove(active)) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            selected = active - 1;
            active = -1;
            invalidate();
            changed();
            commit();
        }
    }

    private void commit() {
        if (onCommit != null) onCommit.accept(curve);
    }

    private void changed() {
        if (onChange != null) onChange.run();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
