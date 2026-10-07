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
 * <p>
 * P25 look: a small CARD with a LINE stroke above the lens strip in the bottom bar (never over the shade's tiles), the
 * zoom value in the camera accent with a decimal comma, MUTED ticks.
 */
public class ZoomDialView extends View {
    public interface Listener { void onZoom(float zoom); }

    /** Told when the ruler appears and when it has faded out (P32: the shade handle it may reach steps aside meanwhile). */
    public interface ShownListener { void onShown(boolean shown); }

    private static final long HIDE_DELAY_MS = 1600;
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backdrop = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path triangle = new Path();
    private final Path clip = new Path();
    private final android.graphics.RectF card = new android.graphics.RectF();
    private final List<Float> marks = new ArrayList<>();
    private final List<String> markLabels = new ArrayList<>();
    private float min = 1f, max = 4f, zoom = 1f;
    private float lastX;
    private boolean dragging;
    private Listener listener;
    private ShownListener shownListener;
    private final Runnable hide = () -> { if (!dragging) animate().alpha(0f).setDuration(220).withEndAction(() -> {
        setVisibility(INVISIBLE);
        if (shownListener != null) shownListener.onShown(false);
    }).start(); };

    public ZoomDialView(Context context) { this(context, null); }

    public ZoomDialView(Context context, AttributeSet attrs) {
        super(context, attrs);
        tick.setColor(com.particlesdevs.photoncamera.ui.settings.SettingsStyle.MUTED);
        tick.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(com.particlesdevs.photoncamera.ui.settings.SettingsStyle.MUTED);
        text.setTextAlign(Paint.Align.CENTER);
        pointer.setColor(AccentPalette.camera(context));
        backdrop.setColor(com.particlesdevs.photoncamera.ui.settings.SettingsStyle.CARD);
        outline.setStyle(Paint.Style.STROKE);
        outline.setColor(com.particlesdevs.photoncamera.ui.settings.SettingsStyle.LINE);
        setVisibility(INVISIBLE);
        setAlpha(0f);
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setShownListener(ShownListener listener) { this.shownListener = listener; }

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
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            if (shownListener != null) shownListener.onShown(true);
        }
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

    /** «2,5», «1»: the decimal comma in the Russian UI (owner's answer 11), the point in English. */
    static String format(float v) {
        final String text = String.format(Locale.US, "%.1f", v).replace(".0", "");
        return com.particlesdevs.photoncamera.util.Lang.ru() ? text.replace('.', ',') : text;
    }

    @Override protected void onDraw(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        final float d = getResources().getDisplayMetrics().density;
        final float cx = w / 2f, ppo = pxPerOctave(), radius = 16 * d;
        // The card.
        card.set(d / 2f, d / 2f, w - d / 2f, h - d / 2f);
        canvas.drawRoundRect(card, radius, radius, backdrop);
        outline.setStrokeWidth(d);
        canvas.drawRoundRect(card, radius, radius, outline);
        canvas.save();
        clip.reset();
        clip.addRoundRect(card, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        // The value, in the accent.
        text.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        text.setFakeBoldText(true);
        text.setColor(pointer.getColor());
        text.setAlpha(255);
        canvas.drawText(format(zoom) + "×", cx, 18 * d, text);
        text.setFakeBoldText(false);
        final float baseline = h - 9 * d;
        final double logZoom = Math.log(zoom) / Math.log(2);
        tick.setStrokeWidth(1.2f * d);
        // Minor ticks every 1/4 octave, drawn between the visible range limits.
        for (int q = (int) Math.floor((Math.log(min) / Math.log(2)) * 4); q <= (int) Math.ceil((Math.log(max) / Math.log(2)) * 4); q++) {
            float x = cx + (float) ((q / 4.0 - logZoom) * ppo);
            if (x < 0 || x > w) continue;
            boolean octave = q % 4 == 0;
            float len = (octave ? 7f : 4f) * d;
            tick.setColor(com.particlesdevs.photoncamera.ui.settings.SettingsStyle.MUTED);
            tick.setAlpha(octave ? 200 : 110);
            canvas.drawLine(x, baseline - len, x, baseline, tick);
        }
        // Module marks with labels.
        text.setTextSize(9 * getResources().getDisplayMetrics().scaledDensity);
        for (int i = 0; i < marks.size(); i++) {
            float x = cx + (float) ((Math.log(marks.get(i)) / Math.log(2) - logZoom) * ppo);
            if (x < -d * 20 || x > w + d * 20) continue;
            boolean current = Math.abs(marks.get(i) - zoom) < 0.05f;
            tick.setColor(com.particlesdevs.photoncamera.ui.settings.SettingsStyle.TEXT);
            tick.setAlpha(255);
            canvas.drawLine(x, baseline - 9 * d, x, baseline, tick);
            text.setColor(current ? com.particlesdevs.photoncamera.ui.settings.SettingsStyle.TEXT : com.particlesdevs.photoncamera.ui.settings.SettingsStyle.MUTED);
            canvas.drawText(AuxButtonsLayout.display(markLabels.get(i)), x, baseline - 11 * d, text);
        }
        // Fixed pointer under the scale.
        triangle.reset();
        triangle.moveTo(cx, baseline + d);
        triangle.lineTo(cx - 4 * d, baseline + 6 * d);
        triangle.lineTo(cx + 4 * d, baseline + 6 * d);
        triangle.close();
        canvas.drawPath(triangle, pointer);
        canvas.restore();
    }
}
