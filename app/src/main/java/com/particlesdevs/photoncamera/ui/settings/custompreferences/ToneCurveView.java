package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.R;
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
 * <p>
 * P178 (owner, 2026-10-16: «Добавь возможность делать шаг действий назад и вперед при редактировании кривой»): every finished
 * edit is a step ({@link #record}: a gesture here, and whatever the editor stores - a slider let go, a typed value, a
 * shortcut, a template); {@link #undo} / {@link #redo} walk the steps and hand the curve to onCommit (stored as any edit),
 * from the buttons of {@link #historyButtons} on the graph's top left corner.
 */
public final class ToneCurveView extends View {
    private static final float ASPECT = 0.78f;
    /** The plot's inset from the view's edges (dp; 22 at the bottom for the EV labels). */
    private static final float PLOT_PAD = 14f;
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
    /** P178: the steps (serialized curves), the one shown, and who shows whether a step back / forth exists. */
    static final int MAX_STEPS = 100;
    private final java.util.ArrayList<String> history = new java.util.ArrayList<>();
    private int step = -1;
    @Nullable private Runnable onHistory;

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
        record();   // P178: the steps start at the curve shown first
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

    /**
     * Shows a curve (the stored one, or one changed elsewhere). P178: while nothing has been edited here it is where the
     * steps start; an edit made by the editor's own controls goes through {@link #edit}.
     */
    public void setCurve(ToneCurve curve) {
        if (history.size() <= 1) {
            history.clear();
            step = -1;
        }
        show(curve);
        if (history.isEmpty()) record();
    }

    /** P178: an edit from the editor's controls (a shortcut, a template, a loaded file): shown and made a step. */
    public void edit(ToneCurve curve) {
        show(curve);
        record();
    }

    private void show(ToneCurve curve) {
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
        float pad = dp(PLOT_PAD), bottom = dp(22);
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
        record();
        deliver();
    }

    private void deliver() {
        if (onCommit != null) onCommit.accept(curve);
    }

    // ───── P178: the steps

    /** The curve shown becomes a step (nothing when it is the step already); the steps after the shown one go. */
    public void record() {
        String now = curve.serialize();
        if (step >= 0 && step < history.size() && history.get(step).equals(now)) return;
        while (history.size() > step + 1) history.remove(history.size() - 1);
        history.add(now);
        if (history.size() > MAX_STEPS) history.remove(0);
        step = history.size() - 1;
        historyChanged();
    }

    public boolean canUndo() {
        return step > 0;
    }

    public boolean canRedo() {
        return step >= 0 && step < history.size() - 1;
    }

    /** One step back: the curve before the last edit, stored through onCommit. False when there is none. */
    public boolean undo() {
        return go(step - 1);
    }

    /** One step forth again after {@link #undo}. False when there is none. */
    public boolean redo() {
        return go(step + 1);
    }

    private boolean go(int to) {
        if (to < 0 || to >= history.size() || to == step) return false;
        step = to;
        int keep = selected;
        curve = ToneCurve.parse(history.get(to));
        active = -1;
        selected = keep >= 0 && keep < curve.size() ? keep : curve.size() / 2;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        invalidate();
        changed();
        deliver();
        historyChanged();
        return true;
    }

    /** Called when a step back or forth appears or goes (the buttons follow). */
    public void setOnHistory(@Nullable Runnable onHistory) {
        this.onHistory = onHistory;
        historyChanged();
    }

    private void historyChanged() {
        if (onHistory != null) onHistory.run();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        historyChanged();
    }

    /**
     * «Отменить» / «Повторить» as two round buttons in a pill, laid over the graph's top left corner (the corner a tone
     * curve never reaches: dark in, bright out); dimmed while there is no step that way.
     */
    public static LinearLayout historyButtons(Context c, ToneCurveView graph) {
        LinearLayout pill = new LinearLayout(c);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setTag("tone_curve_history");
        int pad = Math.round(graph.dp(3));
        pill.setPadding(pad, pad, pad, pad);
        pill.setBackground(SettingsStyle.shape(c, (SettingsStyle.CARD & 0x00FFFFFF) | 0xE6000000, SettingsStyle.LINE, 100));
        ImageButton undo = historyButton(c, R.drawable.ic_curve_undo, "tone_curve_undo", Lang.t(c, "Отменить", "Undo"));
        ImageButton redo = historyButton(c, R.drawable.ic_curve_redo, "tone_curve_redo", Lang.t(c, "Повторить", "Redo"));
        undo.setOnClickListener(v -> graph.undo());
        redo.setOnClickListener(v -> graph.redo());
        int size = Math.round(graph.dp(34));
        pill.addView(undo, new LinearLayout.LayoutParams(size, size));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(size, size);
        rp.setMarginStart(Math.round(graph.dp(2)));
        pill.addView(redo, rp);
        graph.setOnHistory(() -> {
            boolean on = graph.isEnabled();
            undo.setEnabled(on && graph.canUndo());
            redo.setEnabled(on && graph.canRedo());
            undo.setAlpha(undo.isEnabled() ? 1f : 0.35f);
            redo.setAlpha(redo.isEnabled() ? 1f : 0.35f);
        });
        return pill;
    }

    private static ImageButton historyButton(Context c, int icon, String tag, String name) {
        ImageButton b = new ImageButton(c);
        int accent = SettingsStyle.accent(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(accent));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf((accent & 0x00FFFFFF) | 0x33000000), null,
                SettingsStyle.shape(c, 0xFFFFFFFF, 0, 100)));
        b.setTag(tag);
        b.setContentDescription(name);
        return b;
    }

    /** The graph with its step buttons over its top left corner: the view to add where the graph alone went. */
    public static FrameLayout withHistory(Context c, ToneCurveView graph) {
        FrameLayout frame = new FrameLayout(c);
        frame.addView(graph, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addHistory(frame, graph);
        return frame;
    }

    /** Adds the step buttons to the frame the graph is in (once): inside the plot, clear of its frame and labels. */
    public static void addHistory(FrameLayout frame, ToneCurveView graph) {
        if (frame.findViewWithTag("tone_curve_history") != null) return;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        int inset = Math.round(graph.dp(PLOT_PAD + 6));
        lp.setMargins(inset, inset, 0, 0);
        frame.addView(historyButtons(frame.getContext(), graph), lp);
    }

    private void changed() {
        if (onChange != null) onChange.run();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
