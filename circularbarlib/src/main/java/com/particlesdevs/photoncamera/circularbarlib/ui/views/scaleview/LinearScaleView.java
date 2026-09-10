package com.particlesdevs.photoncamera.circularbarlib.ui.views.scaleview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobItemInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal ticked scale for a manual parameter, in place of the rotary knob.
 *
 * <p>Why a strip and not a wheel: on a wheel only a few values are on screen at
 * once and the ends of the range are never visible, so there is no way to see
 * where the current value sits within what the sensor can do. A strip shows the
 * whole range at a glance - the marker's position is the answer - and a drag maps
 * to distance rather than to an angle, which is what a thumb on the bottom of a
 * phone actually does well.
 *
 * <p>Feeds on the same {@link KnobItemInfo} list the knob used, so the models
 * behind it are untouched.
 */
public class LinearScaleView extends View {

    /** Amber, and only here. Selection elsewhere is a light wash, so a coloured
     *  mark always means "this is the value", never "this is chosen". */
    private static final int MARKER_COLOR = 0xFFFFC400;
    private static final int TICK_COLOR = 0x66FFFFFF;
    private static final int LABEL_COLOR = 0x8CFFFFFF;

    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<KnobItemInfo> items = new ArrayList<>();
    private int selectedIndex = 0;

    /** Set while the finger is down, so the caller can hold auto-exposure off. */
    private boolean dragging = false;
    private float lastX;
    /** Leftover drag distance not yet worth a whole step. */
    private float dragAccum = 0f;

    public interface OnValueChangedListener {
        void onValueChanged(KnobItemInfo item, boolean fromUser);

        void onDragStateChanged(boolean dragging);
    }

    private OnValueChangedListener listener;

    public LinearScaleView(Context context) {
        this(context, null);
    }

    public LinearScaleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        tickPaint.setColor(TICK_COLOR);
        tickPaint.setStrokeWidth(density);
        markerPaint.setColor(MARKER_COLOR);
        markerPaint.setStrokeWidth(2.5f * density);
        textPaint.setColor(LABEL_COLOR);
        textPaint.setTextSize(10f * density);
        textPaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setColor(MARKER_COLOR);
        valuePaint.setTextSize(12f * density);
        valuePaint.setFakeBoldText(true);
        valuePaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(OnValueChangedListener l) {
        this.listener = l;
    }

    public void setItems(List<KnobItemInfo> newItems, int selected) {
        this.items = newItems == null ? new ArrayList<>() : newItems;
        this.selectedIndex = clampIndex(selected);
        invalidate();
    }

    public KnobItemInfo getSelected() {
        if (items.isEmpty()) return null;
        return items.get(clampIndex(selectedIndex));
    }

    private int clampIndex(int i) {
        if (items.isEmpty()) return 0;
        return Math.max(0, Math.min(items.size() - 1, i));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (items.isEmpty()) return;

        float density = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();
        float padding = 16f * density;
        float left = padding;
        float right = w - padding;
        float span = right - left;
        if (span <= 0) return;

        float ticksY = h * 0.62f;
        float shortTick = 5f * density;
        float longTick = 8f * density;

        // One tick per value up to a limit, then evenly spaced: some ranges have
        // hundreds of entries and drawing them all turns the strip into a solid
        // grey band that reads as nothing at all.
        int tickCount = Math.min(items.size(), 40);
        for (int i = 0; i < tickCount; i++) {
            float t = tickCount == 1 ? 0f : (float) i / (tickCount - 1);
            float x = left + t * span;
            float len = (i % 2 == 0) ? shortTick : longTick;
            canvas.drawLine(x, ticksY, x, ticksY + len, tickPaint);
        }

        // Range ends, so the strip says what the sensor can do, not just where
        // the value happens to be.
        canvas.drawText(items.get(0).text, left, ticksY - 8f * density, textPaint);
        canvas.drawText(items.get(items.size() - 1).text, right, ticksY - 8f * density, textPaint);

        int idx = clampIndex(selectedIndex);
        float pos = items.size() == 1 ? 0f : (float) idx / (items.size() - 1);
        float markerX = left + pos * span;
        canvas.drawLine(markerX, ticksY - 4f * density, markerX, ticksY + longTick + 2f * density, markerPaint);

        // Current value above its own marker rather than in a fixed corner: the
        // number and its position are the same fact, and splitting them makes the
        // strip harder to read at a glance.
        String label = items.get(idx).text;
        float labelX = Math.max(left + 12f * density, Math.min(right - 12f * density, markerX));
        canvas.drawText(label, labelX, ticksY - 22f * density, valuePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (items.isEmpty()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                lastX = event.getX();
                dragAccum = 0f;
                getParent().requestDisallowInterceptTouchEvent(true);
                if (listener != null) listener.onDragStateChanged(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - lastX;
                lastX = event.getX();
                float density = getResources().getDisplayMetrics().density;
                float span = Math.max(1f, getWidth() - 32f * density);
                // Distance per step, so a full sweep covers the whole range
                // whatever its length.
                float stepPx = span / Math.max(1, items.size() - 1);
                dragAccum += dx;
                int steps = (int) (dragAccum / stepPx);
                if (steps != 0) {
                    dragAccum -= steps * stepPx;
                    int next = clampIndex(selectedIndex + steps);
                    if (next != selectedIndex) {
                        selectedIndex = next;
                        invalidate();
                        if (listener != null) listener.onValueChanged(items.get(next), true);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (listener != null) listener.onDragStateChanged(false);
                return true;
        }
        return super.onTouchEvent(event);
    }

    public boolean isDragging() {
        return dragging;
    }
}
