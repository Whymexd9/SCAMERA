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

/** Manual controls with a fixed-marker photographic ruler and an independent Auto button. */
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
    private boolean autoPressed;
    private boolean temperatureMode;
    private boolean photographicMode;
    private String valuePrefix = "";
    public void setValuePrefix(String value) { valuePrefix = value; invalidate(); }
    public void setPhotographicMode(boolean value) { photographicMode = value; invalidate(); }
    private float lastX;
    /** Leftover drag distance not yet worth a whole step. */
    private float dragAccum = 0f;

    public interface OnValueChangedListener {
        void onValueChanged(KnobItemInfo item, boolean fromUser);

        void onDragStateChanged(boolean dragging);

        /**
         * The auto button was tapped. Handled by the caller rather than by picking
         * an item here: which entry means "auto" is the model's business, and
         * guessing at it from the view would break on any parameter that orders
         * its list differently.
         */
        void onAutoRequested();
    }

    private OnValueChangedListener listener;

    private float autoCx, autoCy, autoRadius;
    private final Paint autoFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint autoStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint autoTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean isInAutoButton(float x, float y) {
        return x >= 0 && x <= 52f * getResources().getDisplayMetrics().density && y >= 0 && y <= getHeight();
    }
    public void setTemperatureMode(boolean value){temperatureMode=value;invalidate();}
    public void setSelectedItem(KnobItemInfo item){int index=items.indexOf(item);if(index>=0){selectedIndex=index;invalidate();}}

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

        autoFillPaint.setColor(0x38FFFFFF);
        autoFillPaint.setStyle(Paint.Style.FILL);
        autoStrokePaint.setColor(0x4DFFFFFF);
        autoStrokePaint.setStyle(Paint.Style.STROKE);
        autoStrokePaint.setStrokeWidth(density);
        autoTextPaint.setColor(0xFFFFFFFF);
        autoTextPaint.setTextSize(12f * density);
        autoTextPaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setColor(MARKER_COLOR);
        valuePaint.setTextSize(12f * density);
        valuePaint.setFakeBoldText(true);
        valuePaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(OnValueChangedListener l) {
        this.listener = l;
    }

    public void setItems(List<KnobItemInfo> newItems, int selected) {
        KnobItemInfo chosen = newItems == null || newItems.isEmpty() ? null : newItems.get(Math.max(0,Math.min(selected,newItems.size()-1)));
        this.items = newItems == null ? new ArrayList<>() : new ArrayList<>(newItems);
        this.items.sort(java.util.Comparator.comparingDouble(item -> item.value));
        if(chosen!=null)selected=this.items.indexOf(chosen);
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
        if (photographicMode) { drawPhotographicRuler(canvas); return; }

        float density = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();
        float padding = 16f * density;
        // Room on the left for the auto button.
        float autoR = 13f * density;
        autoCx = padding + autoR;
        autoCy = getHeight() * 0.55f;
        autoRadius = autoR;
        float left = autoCx + autoR + 12f * density;
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

        if (temperatureMode) {
            Paint gradient = new Paint(Paint.ANTI_ALIAS_FLAG);
            gradient.setShader(new android.graphics.LinearGradient(left,0,right,0,0xFFD79A5F,0xFF739CD6,android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRoundRect(left,ticksY-5*density,right,ticksY-2*density,density,density,gradient);
        }
        // Range ends, so the strip says what the sensor can do, not just where
        // the value happens to be.
        int firstNumeric=items.get(0).value==0 && items.size()>1 ? 1 : 0;
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(items.get(firstNumeric).text, left, 19f*density, textPaint);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(items.get(items.size() - 1).text, right, 19f*density, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // Auto button: same light wash as every other selected state.
        canvas.drawCircle(autoCx, autoCy, autoRadius, autoFillPaint);
        canvas.drawCircle(autoCx, autoCy, autoRadius, autoStrokePaint);
        canvas.drawText("A", autoCx, autoCy + 4.5f * density, autoTextPaint);

        int idx = clampIndex(selectedIndex);
        float pos = items.size() == 1 ? 0f : (float) idx / (items.size() - 1);
        float markerX = left + pos * span;
        canvas.drawLine(markerX, ticksY - 4f * density, markerX, ticksY + longTick + 2f * density, markerPaint);

        // Current value above its own marker rather than in a fixed corner: the
        // number and its position are the same fact, and splitting them makes the
        // strip harder to read at a glance.
        String label = items.get(idx).text;
        // Keep the value clear of the range labels rather than only inside the
        // strip: with the marker at either end the two collided and printed over
        // each other, which is what the AF strip showed.
        float half = valuePaint.measureText(label) * 0.5f;
        float minEnd = left + textPaint.measureText(items.get(firstNumeric).text) + 8f * density + half;
        float maxEnd = right - textPaint.measureText(items.get(items.size() - 1).text) - 10f * density - half;
        float labelX = markerX;
        if (minEnd <= maxEnd) {
            labelX = Math.max(minEnd, Math.min(maxEnd, markerX));
        }
        canvas.drawText(label, labelX, 19f*density, valuePaint);
    }

    private void drawPhotographicRuler(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float y = getHeight() / 88f; // Fit the vertical layout without distorting text or the Auto button.
        float left = 58 * d, right = getWidth() - 10 * d;
        if (right <= left) return;
        float center = (left + right) / 2;
        float step = 24 * d;
        int first = items.get(0).value <= 0 ? 1 : 0;
        boolean auto = selectedIndex < first;
        int index = Math.max(first, selectedIndex);
        autoCx = 27 * d; autoCy = 53 * y; autoRadius = 16 * d;
        autoFillPaint.setColor(auto ? 0x66FFFFFF : 0x18FFFFFF);
        canvas.drawCircle(autoCx, autoCy, autoRadius, autoFillPaint);
        canvas.drawCircle(autoCx, autoCy, autoRadius, autoStrokePaint);
        canvas.drawText("A", autoCx, autoCy + 4.5f * d, autoTextPaint);
        canvas.save();
        canvas.clipRect(left, 25*y, right, getHeight());
        float lastLabelEnd = -Float.MAX_VALUE;
        for (int i = first; i < items.size(); i++) {
            float x = center + (i - index) * step;
            if (x < left - 50*d || x > right + 50*d) continue;
            KnobItemInfo item = items.get(i);
            canvas.drawLine(x, 53*y, x, (item.majorTick ? 72 : 62)*y, tickPaint);
            if (item.majorTick) {
                String label = item.text.replace(" s", "");
                float half = textPaint.measureText(label)/2;
                if (x-half >= left && x+half <= right && x-half > lastLabelEnd+8*d) {
                    canvas.drawText(label, x, 42*y, textPaint);
                    lastLabelEnd=x+half;
                }
            }
        }
        canvas.restore();
        canvas.drawLine(center, 51*y, center, 74*y, markerPaint);
        valuePaint.setTextSize(15*d);
        canvas.drawText(auto ? "A" : valuePrefix + items.get(selectedIndex).text, center, 21*y, valuePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || items.isEmpty()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                autoPressed = isInAutoButton(event.getX(), event.getY());
                if (autoPressed) {

                    return true;
                }
                dragging = true;
                lastX = event.getX();
                dragAccum = 0f;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (listener != null) listener.onDragStateChanged(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging || autoPressed) return true;
                float dx = event.getX() - lastX;
                lastX = event.getX();
                float density = getResources().getDisplayMetrics().density;
                float span = Math.max(1f, getWidth() - 32f * density);
                // Distance per step, so a full sweep covers the whole range
                // whatever its length.
                float stepPx = photographicMode ? 24f * density : span / Math.max(1, items.size() - 1);
                dragAccum += photographicMode ? -dx : dx;
                int steps = (int) (dragAccum / stepPx);
                if (steps != 0) {
                    dragAccum -= steps * stepPx;
                    int first = photographicMode && items.get(0).value <= 0 ? 1 : 0;
                    int next = Math.max(first, clampIndex(Math.max(first, selectedIndex) + steps));
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
                if (event.getActionMasked() == MotionEvent.ACTION_UP && autoPressed
                        && isInAutoButton(event.getX(), event.getY())) {
                    if (listener != null) listener.onAutoRequested();
                    performClick();
                }
                dragging = false;
                autoPressed = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (listener != null) listener.onDragStateChanged(false);
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override public boolean performClick(){super.performClick();return true;}

    public boolean isDragging() {
        return dragging;
    }
}
