package com.particlesdevs.photoncamera.ui.camera.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Zoom ruler: a logarithmic scale that slides under a fixed pointer. Module ratios are the
 * labelled marks. It appears while the zoom changes (drag, pinch, lens strip) and fades out.
 */
public class ZoomDialView extends View {
    public interface Listener { void onZoom(float zoom); }

    private static final long HIDE_DELAY_MS = 1600;
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backdrop = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path triangle = new Path();
    private final List<Float> marks = new ArrayList<>();
    private final List<String> markLabels = new ArrayList<>();
    private float min = 1f, max = 4f, zoom = 1f;
    private float lastX;
    private boolean dragging;
    private Listener listener;
    private final Runnable hide = () -> { if (!dragging) animate().alpha(0f).setDuration(220).withEndAction(() -> setVisibility(INVISIBLE)).start(); };

    public ZoomDialView(Context context) { this(context, null); }

    public ZoomDialView(Context context, AttributeSet attrs) {
        super(context, attrs);
        tick.setColor(0xCCFFFFFF);
        tick.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        pointer.setColor(AccentPalette.camera(context));
        backdrop.setColor(0x66000000);
        setVisibility(INVISIBLE);
        setAlpha(0f);
    }

    public void setListener(Listener listener) { this.listener = listener; }

    /** Range and labelled marks (module ratios); called whenever the module list may have changed. */
    public void configure(float min, float max, List<Float> ratios, List<String> labels) {
        this.min = Math.max(0.1f, min);
        this.max = Math.max(this.min * 1.5f, max);
        marks.clear();
        markLabels.clear();
        marks.addAll(ratios);
        markLabels.addAll(labels);
        invalidate();
    }

    public void setZoom(float zoom) {
        this.zoom = Math.max(min, Math.min(max, zoom));
        invalidate();
    }

    /** Show the ruler and schedule fading it out. */
    public void poke() {
        removeCallbacks(hide);
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        animate().cancel();
        setAlpha(1f);
        postDelayed(hide, HIDE_DELAY_MS);
    }

    private float pxPerOctave() { return getWidth() * 0.36f; }

    /** Horizontal drag by dx pixels: the scale follows the finger. */
    public void dragBy(float dx) {
        float octaves = -dx / pxPerOctave();
        float next = (float) (zoom * Math.pow(2, octaves));
        next = Math.max(min, Math.min(max, next));
        if (next == zoom) return;
        zoom = next;
        invalidate();
        poke();
        if (listener != null) listener.onZoom(zoom);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getVisibility() != VISIBLE) return false;
                lastX = e.getX();
                dragging = true;
                removeCallbacks(hide);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                dragBy(e.getX() - lastX);
                lastX = e.getX();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                postDelayed(hide, HIDE_DELAY_MS);
                return true;
            default:
                return true;
        }
    }

    private static String format(float v) {
        return String.format(Locale.US, "%.1f", v).replace(".0", "");
    }

    @Override protected void onDraw(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        final float d = getResources().getDisplayMetrics().density;
        final float cx = w / 2f, baseline = h * 0.72f, ppo = pxPerOctave();
        canvas.drawRoundRect(0, h * 0.06f, w, h, h * 0.4f, h * 0.4f, backdrop);
        text.setTextSize(Math.max(11f * d, h * 0.20f));
        tick.setStrokeWidth(1.4f * d);
        final double logZoom = Math.log(zoom) / Math.log(2);
        // Minor ticks every 1/4 octave, drawn between the visible range limits.
        for (int q = (int) Math.floor((Math.log(min) / Math.log(2)) * 4); q <= (int) Math.ceil((Math.log(max) / Math.log(2)) * 4); q++) {
            float x = cx + (float) ((q / 4.0 - logZoom) * ppo);
            if (x < 0 || x > w) continue;
            boolean octave = q % 4 == 0;
            float len = (octave ? 0.16f : 0.08f) * h;
            tick.setAlpha(octave ? 200 : 110);
            canvas.drawLine(x, baseline - len, x, baseline, tick);
        }
        // Module marks with labels.
        for (int i = 0; i < marks.size(); i++) {
            float x = cx + (float) ((Math.log(marks.get(i)) / Math.log(2) - logZoom) * ppo);
            if (x < -d * 20 || x > w + d * 20) continue;
            tick.setAlpha(255);
            canvas.drawLine(x, baseline - 0.26f * h, x, baseline, tick);
            text.setAlpha(Math.abs(marks.get(i) - zoom) < 0.05f ? 255 : 200);
            canvas.drawText(markLabels.get(i), x, baseline - 0.29f * h, text);
        }
        // Fixed pointer and the numeric value.
        triangle.reset();
        triangle.moveTo(cx, baseline + 0.02f * h);
        triangle.lineTo(cx - 0.07f * h, baseline + 0.2f * h);
        triangle.lineTo(cx + 0.07f * h, baseline + 0.2f * h);
        triangle.close();
        canvas.drawPath(triangle, pointer);
        text.setAlpha(255);
        text.setColor(pointer.getColor());
        canvas.drawText(format(zoom) + "×", cx, h * 0.25f, text);
        text.setColor(0xFFFFFFFF);
    }
}
