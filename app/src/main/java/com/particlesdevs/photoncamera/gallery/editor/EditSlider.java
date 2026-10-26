package com.particlesdevs.photoncamera.gallery.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * P351: one slider row of the editor in the gallery's look - the label at the left, the value at the right (accent when it is
 * not the default), the track under them (a bipolar slider fills from its middle). A horizontal drag anywhere on the row moves
 * the value by the drag (the whole width = the whole range; a vertical move scrolls the panel instead), a double tap puts the
 * default back. {@link Listener#changed} while dragging, {@link Listener#committed} when let go (one undo step).
 */
public final class EditSlider extends View {
    public interface Listener {
        void changed(float value);

        void committed();
    }

    public interface Format {
        String of(float value);
    }

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG),
            fill = new Paint(Paint.ANTI_ALIAS_FLAG), thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector taps;
    private final int slop;
    private String label;
    private float min, max, def, step, value;
    private boolean bipolar;
    @Nullable private Listener listener;
    @Nullable private Format format;
    @Nullable private int[] gradient;
    private float downX, downY, downValue;
    private boolean dragging;

    public EditSlider(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        text.setTextSize(GalleryUi.dp(c, 13.5f));
        text.setTypeface(Typeface.DEFAULT);
        track.setColor(0xFF3A4048);
        track.setStrokeWidth(GalleryUi.dp(c, 3));
        track.setStrokeCap(Paint.Cap.ROUND);
        fill.setColor(GalleryUi.accent(c));
        fill.setStrokeWidth(GalleryUi.dp(c, 3));
        fill.setStrokeCap(Paint.Cap.ROUND);
        thumb.setColor(GalleryUi.TEXT);
        setFocusable(true);
        setClickable(true);
        taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                set(def, true);
                if (listener != null) listener.committed();
                return true;
            }
        });
    }

    public EditSlider setup(String label, float min, float max, float def, float step, boolean bipolar) {
        this.label = label;
        this.min = min;
        this.max = max;
        this.def = def;
        this.step = step;
        this.bipolar = bipolar;
        setContentDescription(label);
        invalidate();
        return this;
    }

    public EditSlider listener(@Nullable Listener l) {
        listener = l;
        return this;
    }

    public EditSlider format(@Nullable Format f) {
        format = f;
        invalidate();
        return this;
    }

    /** A colour track (a hue or temperature scale) instead of the plain one. */
    public EditSlider gradient(@Nullable int[] colours) {
        gradient = colours;
        invalidate();
        return this;
    }

    public float value() {
        return value;
    }

    /** Sets the value (snapped); {@code notify}: tells the listener. */
    public void set(float v, boolean notify) {
        float s = step > 0 ? Math.round(v / step) * step : v;
        s = Math.max(min, Math.min(max, s));
        if (s == value) return;
        value = s;
        invalidate();
        if (notify && listener != null) listener.changed(value);
    }

    String valueText() {
        if (format != null) return format.of(value);
        if (step >= 1f) {
            int i = Math.round(value);
            return bipolar && i > 0 ? "+" + i : String.valueOf(i);
        }
        String t = GalleryUi.decimal(value, step < 0.1f ? 2 : 1);
        return bipolar && value > 0 ? "+" + t : t;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(getDefaultSize(GalleryUi.dp(getContext(), 240), w), GalleryUi.dp(getContext(), 54));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Context c = getContext();
        int pad = GalleryUi.dp(c, 18);
        float w = getWidth(), top = GalleryUi.dp(c, 20);
        text.setColor(GalleryUi.TEXT);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(label != null ? label : "", pad, top, text);
        boolean changed = Math.abs(value - def) > 1e-6f;
        text.setColor(changed ? GalleryUi.accent(c) : GalleryUi.MUTED);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(valueText(), w - pad, top, text);
        float y = GalleryUi.dp(c, 38), x0 = pad, x1 = w - pad;
        if (gradient != null) {
            track.setShader(new LinearGradient(x0, y, x1, y, gradient, null, Shader.TileMode.CLAMP));
            track.setAlpha(255);
        } else {
            track.setShader(null);
        }
        canvas.drawLine(x0, y, x1, y, track);
        float tv = x0 + (x1 - x0) * (value - min) / Math.max(1e-6f, max - min);
        float from = x0 + (x1 - x0) * ((bipolar ? def : min) - min) / Math.max(1e-6f, max - min);
        if (gradient == null && changed) canvas.drawLine(Math.min(from, tv), y, Math.max(from, tv), y, fill);
        if (bipolar) canvas.drawCircle(from, y, GalleryUi.dp(c, 2), track);
        thumb.setColor(dragging ? GalleryUi.accent(c) : GalleryUi.TEXT);
        canvas.drawCircle(tv, y, GalleryUi.dp(c, dragging ? 9 : 7.5f), thumb);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        taps.onTouchEvent(e);
        float trackW = Math.max(1, getWidth() - 2 * GalleryUi.dp(getContext(), 18));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downValue = value;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (!dragging) {
                    if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                        return false;
                    }
                    if (Math.abs(dx) > slop) {
                        dragging = true;
                        downX = e.getX();
                        getParent().requestDisallowInterceptTouchEvent(true);
                        invalidate();
                    }
                    return true;
                }
                set(downValue + (e.getX() - downX) / trackW * (max - min), true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    invalidate();
                    if (listener != null) listener.committed();
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setText(label + ", " + valueText());
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, @Nullable android.os.Bundle args) {
        float s = Math.max(step, (max - min) / 20f);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            set(value + s, true);
            if (listener != null) listener.committed();
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            set(value - s, true);
            if (listener != null) listener.committed();
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    /** «Сброс двойным касанием» for the help text. */
    static String hint() {
        return Lang.t("Двойное касание — сброс", "Double tap resets");
    }
}
