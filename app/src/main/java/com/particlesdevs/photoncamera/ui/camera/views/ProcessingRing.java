package com.particlesdevs.photoncamera.ui.camera.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;

/**
 * P158 (owner, 2026-10-16: «сделай процесс обработки фото как в ArkCam, прогресс постепенно должен идти вокруг иконки
 * галереи»): the processing of a shot as a line in the accent running clockwise round the gallery card's edge from the top
 * centre, over a faint track. It moves on by time (an ease-out over the last shot's processing time) and never lags behind
 * the pipeline's own milestones ({@link #setStage}: merged, processed, saved); it stops short of the end until the shot is
 * done, then closes the ring and fades. A shot queued behind the running one starts the ring again when the first is done.
 */
public class ProcessingRing extends View {
    /** Share of the ring the time estimate may reach before the shot is done. */
    static final float TIME_CAP = 0.94f;
    static final long MIN_ESTIMATE_MS = 800, MAX_ESTIMATE_MS = 20000;
    /** The time estimate of the next shot: the last shot's processing time (3.5 s before the first). */
    private static long estimateMs = 3500;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path(), part = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final RectF box = new RectF();
    private float shown, stage, fade = 1f;
    private long startedAt;
    private boolean running, finishing;
    private int jobs;

    public ProcessingRing(Context context) {
        this(context, null);
    }

    public ProcessingRing(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        int accent = ShadeStyle.accent(context);
        for (Paint p : new Paint[]{track, line}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        line.setColor(accent);
        track.setColor((accent & 0x00FFFFFF) | 0x38000000);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** A shot's processing starts. */
    public void start() {
        jobs++;
        if (running) return;   // closing the last shot's ring: it starts over for this one when it has faded
        restart();
    }

    /** The pipeline reached {@code fraction} of the work (0..1): the ring is at least there. */
    public void setStage(float fraction) {
        if (!running || finishing) return;
        stage = Math.max(stage, Math.max(0f, Math.min(TIME_CAP, fraction)));
        postInvalidateOnAnimation();
    }

    /** A shot's processing is done (or failed): the ring closes and fades, or starts over for the next queued shot. */
    public void finish() {
        if (jobs > 0) jobs--;
        if (!running || finishing) return;
        estimateMs = Math.max(MIN_ESTIMATE_MS, Math.min(MAX_ESTIMATE_MS, SystemClock.elapsedRealtime() - startedAt));
        finishing = true;
        postInvalidateOnAnimation();
    }

    /** Renders and tests: the ring of a running shot drawn at {@code fraction} at once. */
    @androidx.annotation.VisibleForTesting
    public void showAt(float fraction) {
        if (!running) restart();
        stage = shown = Math.max(0f, Math.min(TIME_CAP, fraction));
        invalidate();
    }

    public boolean isRunning() {
        return running;
    }

    /** The drawn share of the ring (0..1). */
    public float shown() {
        return shown;
    }

    private void restart() {
        running = true;
        finishing = false;
        shown = 0f;
        stage = 0f;
        fade = 1f;
        startedAt = SystemClock.elapsedRealtime();
        setVisibility(VISIBLE);
        postInvalidateOnAnimation();
    }

    /** The time estimate: an ease-out over the last shot's time (about 0.85 of the ring at that time), at most TIME_CAP. */
    static float byTime(long elapsedMs, long estimateMs) {
        double t = Math.max(0, elapsedMs) / (double) Math.max(1, estimateMs);
        return (float) (TIME_CAP * (1 - Math.exp(-2.2 * t)));
    }

    /** The outline from the top centre clockwise round a rounded rectangle. */
    static void outline(Path path, RectF r, float radius) {
        path.reset();
        float rad = Math.min(radius, Math.min(r.width(), r.height()) / 2f);
        path.moveTo(r.centerX(), r.top);
        path.lineTo(r.right - rad, r.top);
        path.arcTo(r.right - 2 * rad, r.top, r.right, r.top + 2 * rad, -90, 90, false);
        path.lineTo(r.right, r.bottom - rad);
        path.arcTo(r.right - 2 * rad, r.bottom - 2 * rad, r.right, r.bottom, 0, 90, false);
        path.lineTo(r.left + rad, r.bottom);
        path.arcTo(r.left, r.bottom - 2 * rad, r.left + 2 * rad, r.bottom, 90, 90, false);
        path.lineTo(r.left, r.top + rad);
        path.arcTo(r.left, r.top, r.left + 2 * rad, r.top + 2 * rad, 180, 90, false);
        path.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!running) return;
        final float f = BottomChrome.scale(getContext()), d = getResources().getDisplayMetrics().density * f;
        final float stroke = 2.6f * d;
        track.setStrokeWidth(stroke);
        line.setStrokeWidth(stroke);
        box.set(stroke / 2f, stroke / 2f, getWidth() - stroke / 2f, getHeight() - stroke / 2f);
        outline(outline, box, BottomChrome.SIDE_RADIUS_DP * d - stroke / 2f);
        long now = SystemClock.elapsedRealtime();
        float target = finishing ? 1f : Math.max(stage, byTime(now - startedAt, estimateMs));
        if (target > shown) shown += Math.max((target - shown) * 0.16f, finishing ? 0.02f : 0f);
        if (shown > target) shown = target;
        if (finishing && shown >= 0.999f) fade -= 0.08f;
        if (finishing && fade <= 0f) {
            running = false;
            if (jobs > 0) {
                restart();
                return;
            }
            setVisibility(INVISIBLE);
            return;
        }
        int alpha = Math.round(255 * Math.max(0f, fade));
        track.setAlpha(Math.round(0x38 * Math.max(0f, fade)));
        line.setAlpha(alpha);
        canvas.drawPath(outline, track);
        measure.setPath(outline, false);
        part.reset();
        measure.getSegment(0, measure.getLength() * shown, part, true);
        canvas.drawPath(part, line);
        postInvalidateOnAnimation();
    }
}
