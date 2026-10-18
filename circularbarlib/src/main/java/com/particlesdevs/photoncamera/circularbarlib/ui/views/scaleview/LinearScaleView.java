package com.particlesdevs.photoncamera.circularbarlib.ui.views.scaleview;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import com.particlesdevs.photoncamera.circularbarlib.ui.ManualFormat;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobItemInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * The ruler of the Pro panel (SIMPLE_UI §3, the concept's one-row ruler): on the left the «Авто» button (a 13dp-radius
 * outline card, filled with the accent while the parameter is in auto), on the right the scale on a darker inset. The
 * marker is fixed in the centre in the accent, the current value labelled above it; the scale moves under it.
 * <ul>
 * <li>a drag moves the scale; each crossed stop is committed at once (live preview), the model gives the light haptic
 * tick;</li>
 * <li>a release with velocity flings (decaying, stopping at the ends), then the scale snaps to the stop;</li>
 * <li>the first touch on a parameter in auto starts from its metered value;</li>
 * <li>the arrow keys step by one stop.</li>
 * </ul>
 * Geometry (dp, from the scale's bottom h): baseline h-8, major ticks 14 (MUTED), minor 8 (faint), labels of the major
 * stops 23dp over the bottom (10sp), the marker a 2dp line h-25..h-5, the value label 3dp from the top (12sp bold), stops
 * 16dp apart, the ends fade out over 40dp.
 */
public class LinearScaleView extends View {
    /** What the stops are: decides the major stops and their labels. */
    public static final int MODE_GENERIC = -1, MODE_ISO = 0, MODE_SHUTTER = 1, MODE_EV = 2, MODE_FOCUS = 3, MODE_WB = 4;
    /** Distance between two stops. */
    public static final float STOP_DP = 16f;
    /** A release counts as a fling only when the finger moved within this many ms before it. */
    static final long FLING_WINDOW_MS = 80;
    static final float FLING_DECAY = .92f, FLING_STOP_DP = .4f, FRAME_MS = 16f;
    static final long SNAP_MS = 140;

    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint autoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint autoTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint insetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint();
    private final RectF rect = new RectF();

    /** Every item, sorted by value. */
    private List<KnobItemInfo> items = new ArrayList<>();
    /** The indices of {@link #items} that are stops of the scale (the auto item is not, except for EV, where it is «0»). */
    private final List<Integer> stops = new ArrayList<>();
    private int autoIndex = -1;
    private int selectedIndex = 0;
    /** The stop under the marker and the scale's sub-stop shift in px (positive: moved left, towards higher values). */
    private int idx = 0;
    private float offset = 0f;
    private double metered = Double.NaN;
    private int mode = MODE_GENERIC;
    private ManualFormat.Units units = ManualFormat.EN;
    private int accent;
    private String autoLabel = "Auto";

    /** Set while the finger is down, so the caller can hold auto-exposure off. */
    private boolean dragging = false;
    private boolean autoPressed;
    private boolean moved;
    private float lastX, downX;
    private long lastMoveTime;
    private VelocityTracker velocity;
    private float flingVelocity;
    private ValueAnimator snap;
    /** One fling step per display frame (the Choreographer, so it also runs before the view is attached). */
    private final android.view.Choreographer.FrameCallback flingFrame = frameTimeNanos -> flingStep();
    private boolean flinging;

    public interface OnValueChangedListener {
        void onValueChanged(KnobItemInfo item, boolean fromUser);

        void onDragStateChanged(boolean dragging);

        /**
         * The auto button was tapped. Handled by the caller rather than by picking an item here: which entry means "auto"
         * is the model's business.
         */
        void onAutoRequested();
    }

    private OnValueChangedListener listener;

    public LinearScaleView(Context context) {
        this(context, null);
    }

    public LinearScaleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        accent = UiTokens.cameraAccent(context);
        tickPaint.setColor(UiTokens.MUTED);
        tickPaint.setStrokeWidth(d);
        markerPaint.setStrokeWidth(2 * d);
        markerPaint.setStrokeCap(Paint.Cap.ROUND);
        labelPaint.setColor(UiTokens.MUTED);
        // Text in sp, its growth with the font size capped (P43): the scale is a fixed 54dp box.
        labelPaint.setTextSize(UiTokens.spPx(context, 10f));
        valuePaint.setTextSize(UiTokens.spPx(context, 12f));
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTypeface(Typeface.DEFAULT_BOLD);
        valuePaint.setFontFeatureSettings("tnum");
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFontFeatureSettings("tnum");
        autoTextPaint.setTextSize(UiTokens.spPx(context, 13f));
        autoTextPaint.setTextAlign(Paint.Align.CENTER);
        insetPaint.setColor(UiTokens.SCALE_INSET);
        setFocusable(true);
    }

    public void setListener(OnValueChangedListener l) {
        this.listener = l;
    }

    public void setAccent(int accent) {
        this.accent = accent;
        invalidate();
    }

    /** The «Авто» button's text. */
    public void setAutoLabel(String label) {
        autoLabel = label;
        invalidate();
    }

    public static int modeFor(int param) {
        return param >= MODE_ISO && param <= MODE_WB ? param : MODE_GENERIC;
    }

    /** The parameter the stops belong to and the units of its labels. */
    public void setMode(int mode, ManualFormat.Units units) {
        this.mode = mode;
        if (units != null) this.units = units;
        invalidate();
    }

    /** White balance: stops in kelvin. */
    public void setTemperatureMode(boolean value) {
        if (value) mode = MODE_WB;
        else if (mode == MODE_WB) mode = MODE_GENERIC;
        invalidate();
    }

    /**
     * The items with the selected one; the first item is the auto item when its value is 0 or less (ISO, shutter, focus
     * and white balance lists start with it).
     */
    public void setItems(List<KnobItemInfo> newItems, int selected) {
        KnobItemInfo chosen = newItems == null || newItems.isEmpty() ? null : newItems.get(Math.max(0, Math.min(selected, newItems.size() - 1)));
        KnobItemInfo auto = newItems != null && newItems.size() > 1 && newItems.get(0).value <= 0 ? newItems.get(0) : null;
        setItems(newItems, chosen, auto);
    }

    /** The items, the selected one and the parameter's auto item (null: none). */
    public void setItems(List<KnobItemInfo> newItems, KnobItemInfo selected, KnobItemInfo auto) {
        stopMotion();
        items = newItems == null ? new ArrayList<>() : new ArrayList<>(newItems);
        items.sort(java.util.Comparator.comparingDouble(item -> item.value));
        autoIndex = auto == null ? -1 : items.indexOf(auto);
        stops.clear();
        for (int i = 0; i < items.size(); i++) if (i != autoIndex || autoIsStop()) stops.add(i);
        int at = selected == null ? -1 : items.indexOf(selected);
        selectedIndex = at >= 0 ? at : autoIndex >= 0 ? autoIndex : 0;
        offset = 0;
        placeMarker();
        invalidate();
    }

    /** EV: auto is compensation 0, a stop of its own in the middle of the scale. */
    private boolean autoIsStop() {
        return mode == MODE_EV;
    }

    public void setSelectedItem(KnobItemInfo item) {
        int index = items.indexOf(item);
        if (index < 0 || (index == selectedIndex && !isAuto())) return;
        selectedIndex = index;
        if (!dragging && !flinging) placeMarker();
        invalidate();
    }

    /** The camera's metered value of the parameter (NaN: unknown): where the scale stands, and starts, while in auto. */
    public void setMeteredValue(double value) {
        metered = value;
        if (isAuto() && !dragging && !flinging) {
            placeMarker();
            invalidate();
        }
    }

    /** Whether the parameter is in auto (the selected item is the auto item). */
    public boolean isAuto() {
        return autoIndex >= 0 && selectedIndex == autoIndex;
    }

    private void placeMarker() {
        if (stops.isEmpty()) { idx = 0; return; }
        int stop = stops.indexOf(selectedIndex);
        if (stop < 0 || (isAuto() && !autoIsStop())) stop = meteredStop();
        idx = Math.max(0, Math.min(stops.size() - 1, stop));
    }

    /** The stop nearest to the metered value (by ratio for ISO and shutter), else the first one. */
    int meteredStop() {
        if (stops.isEmpty()) return 0;
        if (Double.isNaN(metered)) return autoIsStop() && autoIndex >= 0 ? Math.max(0, stops.indexOf(autoIndex)) : 0;
        boolean ratio = (mode == MODE_ISO || mode == MODE_SHUTTER) && metered > 0;
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int j = 0; j < stops.size(); j++) {
            double v = items.get(stops.get(j)).value;
            double dist = ratio ? (v > 0 ? Math.abs(Math.log(v / metered)) : Double.MAX_VALUE) : Math.abs(v - metered);
            if (dist < bestD) { bestD = dist; best = j; }
        }
        return best;
    }

    public KnobItemInfo getSelected() {
        if (items.isEmpty()) return null;
        return items.get(Math.max(0, Math.min(items.size() - 1, selectedIndex)));
    }

    /** The item under the marker (the metered stop while in auto). */
    public KnobItemInfo getMarkerItem() {
        return stops.isEmpty() ? null : items.get(stops.get(idx));
    }

    /** The scale's stops in order (the auto item left out, except EV's «0»): exactly the model's values. */
    public List<KnobItemInfo> stopItems() {
        List<KnobItemInfo> out = new ArrayList<>();
        for (int i : stops) out.add(items.get(i));
        return out;
    }

    /** The labels of the major stops in order (before the drawing skips labels that would collide). */
    public List<String> majorLabels() {
        List<String> out = new ArrayList<>();
        for (int j = 0; j < stops.size(); j++) {
            KnobItemInfo item = items.get(stops.get(j));
            if (isMajor(item, j)) out.add(label(item));
        }
        return out;
    }

    public float getOffset() {
        return offset;
    }

    public boolean isFlinging() {
        return flinging;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** Width of the «Авто» button: its text plus 11dp on each side. */
    public float autoWidth() {
        return autoTextPaint.measureText(autoLabel) + dp(22);
    }

    private boolean isInAutoButton(float x, float y) {
        return x >= 0 && x <= autoWidth() && y >= 0 && y <= getHeight();
    }

    // ───── drawing

    /** The concept's faint minor tick (--faint). */
    static final int FAINT = 0xFF6C757C;
    /** Width of the fades at the scale's ends (the concept's 40px). */
    static final float FADE_DP = 40f;

    /**
     * SIMPLE_UI §3, the concept's ruler row: the «Авто» button (an outline card with the text, the accent with INK while the
     * parameter is in auto) and the scale on its inset (14dp corners): 16dp stops, minor ticks 1.5 x 8dp faint, major ones
     * 14dp muted with a 10sp label above, all on a line 8dp over the bottom; the accent marker 2 x 22dp from 4dp over the
     * bottom; the current value as a small label at the top centre (12sp bold, the accent; the text colour in auto); 40dp
     * fades at both ends.
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float autoW = autoWidth();
        boolean auto = isAuto();
        rect.set(dp(.5f), dp(.5f), autoW - dp(.5f), h - dp(.5f));
        if (auto) {
            autoPaint.setStyle(Paint.Style.FILL);
            autoPaint.setColor(accent);
        } else {
            autoPaint.setStyle(Paint.Style.STROKE);
            autoPaint.setStrokeWidth(dp(1));
            autoPaint.setColor(UiTokens.LINE);
        }
        canvas.drawRoundRect(rect, dp(13), dp(13), autoPaint);
        autoTextPaint.setColor(auto ? UiTokens.ACCENT_INK : UiTokens.TEXT);
        autoTextPaint.setTypeface(MEDIUM);
        Paint.FontMetrics fm = autoTextPaint.getFontMetrics();
        canvas.drawText(autoLabel, autoW / 2, h / 2 - (fm.ascent + fm.descent) / 2, autoTextPaint);

        // The scale on its inset.
        float left = autoW + dp(8), right = w;
        if (right - left < dp(24)) return;
        rect.set(left, 0, right, h);
        canvas.drawRoundRect(rect, dp(14), dp(14), insetPaint);
        if (stops.isEmpty()) return;
        canvas.save();
        canvas.clipRect(left, 0, right, h);
        float cx = (left + right) / 2, step = dp(STOP_DP), base = h - dp(8);
        float labelBase = h - dp(23) - labelPaint.getFontMetrics().descent;
        float lastLabelEnd = -Float.MAX_VALUE;
        int from = Math.max(0, idx - (int) Math.ceil((cx - left) / step) - 2);
        int to = Math.min(stops.size() - 1, idx + (int) Math.ceil((right - cx) / step) + 2);
        tickPaint.setStrokeWidth(dp(1.5f));
        for (int j = from; j <= to; j++) {
            float x = cx + (j - idx) * step - offset;
            KnobItemInfo item = items.get(stops.get(j));
            boolean major = isMajor(item, j);
            tickPaint.setColor(major ? UiTokens.MUTED : FAINT);
            canvas.drawLine(x, base, x, base - dp(major ? 14 : 8), tickPaint);
            if (major) {
                String label = label(item);
                float half = labelPaint.measureText(label) / 2;
                if (x - half >= left + dp(2) && x + half <= right - dp(2) && x - half > lastLabelEnd + dp(6)) {
                    canvas.drawText(label, x, labelBase, labelPaint);
                    lastLabelEnd = x + half;
                }
            }
        }
        // Edges fade into the inset.
        float fade = Math.min(dp(FADE_DP), (right - left) / 3);
        fadePaint.setShader(new LinearGradient(left, 0, left + fade, 0, UiTokens.SCALE_INSET, UiTokens.SCALE_INSET & 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(left, 0, left + fade, h, fadePaint);
        fadePaint.setShader(new LinearGradient(right - fade, 0, right, 0, UiTokens.SCALE_INSET & 0x00FFFFFF, UiTokens.SCALE_INSET, Shader.TileMode.CLAMP));
        canvas.drawRect(right - fade, 0, right, h, fadePaint);
        canvas.restore();
        // The fixed marker in the accent: a 2dp line, 22dp high, 4dp over the bottom.
        markerPaint.setColor(accent);
        canvas.drawLine(cx, h - dp(5), cx, h - dp(25), markerPaint);
        // The current value at the top centre, on the inset's colour.
        KnobItemInfo current = currentItem();
        if (current != null) {
            String value = label(current);
            valuePaint.setColor(auto ? UiTokens.TEXT : accent);
            float vw = valuePaint.measureText(value);
            Paint.FontMetrics vm = valuePaint.getFontMetrics();
            float top = dp(3), textH = vm.descent - vm.ascent;
            rect.set(cx - vw / 2 - dp(5), top, cx + vw / 2 + dp(5), top + textH);
            canvas.drawRoundRect(rect, dp(6), dp(6), insetPaint);
            canvas.drawText(value, cx, top - vm.ascent, valuePaint);
        }
    }

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** The item the value label shows: the selected one, or the metered stop while in auto. */
    private KnobItemInfo currentItem() {
        if (stops.isEmpty()) return null;
        int i = Math.max(0, Math.min(stops.size() - 1, idx));
        return items.get(stops.get(i));
    }

    /**
     * Major (labelled) stops, SIMPLE_UI §3 «no two labels may touch at 360dp»: ISO octaves and whole shutter stops with the
     * module's own end stops (the models mark them; 48dp apart, and the drawing skips a label that would touch its
     * neighbour), whole EV and every 3rd focus stop, every 1000 K for white balance.
     */
    boolean isMajor(KnobItemInfo item, int stop) {
        switch (mode) {
            case MODE_EV: return Math.abs(item.value - Math.rint(item.value)) < 1e-3;
            case MODE_FOCUS: return stop % 3 == 0;
            case MODE_WB: return Math.round(item.value) % 1000 == 0;
            default: return item.majorTick;
        }
    }

    String label(KnobItemInfo item) {
        switch (mode) {
            case MODE_ISO: return ManualFormat.iso(item.value);
            case MODE_SHUTTER: return ManualFormat.shutterShort((long) item.value, units);
            case MODE_EV: return ManualFormat.ev(item.value);
            case MODE_FOCUS: return ManualFormat.focus(item.value, units);
            case MODE_WB: return ManualFormat.wb((int) Math.round(item.value));
            default: return item.text == null ? "" : item.text.replace(" s", "");
        }
    }

    // ───── interaction

    /** The stop under the marker becomes the value: the listener applies it (live preview) and the model ticks. */
    private void commit() {
        int index = stops.get(idx);
        if (index == selectedIndex) return;
        selectedIndex = index;
        if (listener != null) listener.onValueChanged(items.get(index), true);
    }

    /** Commits every stop the offset crossed by half a stop; clamps at the ends. Returns false at an end. */
    private boolean cross() {
        float step = dp(STOP_DP);
        boolean free = true;
        while (offset > step / 2) {
            if (idx >= stops.size() - 1) break;
            idx++;
            offset -= step;
            commit();
        }
        while (offset < -step / 2) {
            if (idx <= 0) break;
            idx--;
            offset += step;
            commit();
        }
        if (idx >= stops.size() - 1 && offset > 0) { offset = 0; free = false; }
        if (idx <= 0 && offset < 0) { offset = 0; free = false; }
        return free;
    }

    private void stopMotion() {
        flinging = false;
        android.view.Choreographer.getInstance().removeFrameCallback(flingFrame);
        if (snap != null) snap.cancel();
    }

    private void flingStep() {
        if (!flinging) return;
        offset += flingVelocity;
        boolean free = cross();
        flingVelocity *= FLING_DECAY;
        invalidate();
        if (!free || Math.abs(flingVelocity) < dp(FLING_STOP_DP)) {
            flinging = false;
            snapToStop();
        } else {
            android.view.Choreographer.getInstance().postFrameCallback(flingFrame);
        }
    }

    /** Eases the scale back onto the stop under the marker. */
    private void snapToStop() {
        if (snap != null) snap.cancel();
        if (Math.abs(offset) < .5f) { offset = 0; invalidate(); return; }
        snap = ValueAnimator.ofFloat(offset, 0f);
        snap.setDuration(SNAP_MS);
        snap.setInterpolator(new DecelerateInterpolator());
        snap.addUpdateListener(a -> { offset = (float) a.getAnimatedValue(); invalidate(); });
        snap.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || items.isEmpty()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopMotion();
                autoPressed = isInAutoButton(event.getX(), event.getY());
                if (autoPressed) return true;
                dragging = true;
                moved = false;
                lastX = downX = event.getX();
                lastMoveTime = event.getEventTime();
                if (velocity != null) velocity.recycle();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(event);
                // A drag on the ruler is the ruler's: never the shade's, never the lens strip's.
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                // The first touch on a parameter in auto starts from its metered value.
                if (isAuto() && !autoIsStop()) idx = meteredStop();
                if (listener != null) listener.onDragStateChanged(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging || autoPressed) return true;
                if (velocity != null) velocity.addMovement(event);
                float dx = event.getX() - lastX;
                lastX = event.getX();
                if (Math.abs(event.getX() - downX) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) moved = true;
                if (dx != 0) lastMoveTime = event.getEventTime();
                offset -= dx;
                cross();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean up = event.getActionMasked() == MotionEvent.ACTION_UP;
                if (up && autoPressed && isInAutoButton(event.getX(), event.getY())) {
                    if (listener != null) listener.onAutoRequested();
                    performClick();
                }
                if (dragging) {
                    // A drag that did not cross a stop still means "this value": the metered start becomes manual.
                    if (up && moved && isAuto() && !autoIsStop()) commit();
                    float v = 0;
                    if (velocity != null) {
                        velocity.addMovement(event);
                        velocity.computeCurrentVelocity(1);
                        v = velocity.getXVelocity();
                    }
                    if (up && event.getEventTime() - lastMoveTime <= FLING_WINDOW_MS && Math.abs(v * FRAME_MS) >= dp(FLING_STOP_DP)) {
                        flingVelocity = -v * FRAME_MS;
                        flinging = true;
                        android.view.Choreographer.getInstance().postFrameCallback(flingFrame);
                    } else {
                        snapToStop();
                    }
                }
                if (velocity != null) { velocity.recycle(); velocity = null; }
                dragging = false;
                autoPressed = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (listener != null) listener.onDragStateChanged(false);
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!items.isEmpty() && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            stopMotion();
            if (isAuto() && !autoIsStop()) idx = meteredStop();
            int next = Math.max(0, Math.min(stops.size() - 1, idx + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
            boolean fromAuto = isAuto() && !autoIsStop();
            if (next != idx || fromAuto) {
                idx = next;
                offset = 0;
                commit();
                invalidate();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    public boolean isDragging() {
        return dragging;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopMotion();
        super.onDetachedFromWindow();
    }
}
