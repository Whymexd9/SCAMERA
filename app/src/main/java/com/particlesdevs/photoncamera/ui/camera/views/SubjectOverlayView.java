package com.particlesdevs.photoncamera.ui.camera.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Face boxes and the tracking box over the viewfinder (P42), in ArkCamera v50's style (FaceIndicatorView): rounded
 * corner brackets, no text, boxes glide to their new place and fade in / out. Colours follow the app theme: the
 * primary face and the tracked subject in the accent colour, other faces in translucent white.
 * <p>
 * Inputs are normalized view rectangles (0..1 of this view, which covers the preview exactly). UI thread only;
 * {@link #onDraw} allocates nothing.
 */
public class SubjectOverlayView extends View {
    private static final int MAX_FACES = 10;
    private static final float GLIDE = 0.4f, FADE = 0.3f, MATCH = 0.25f;
    private static final int OTHER_FACE = 0x99FFFFFF;

    private static final class Box {
        final RectF current = new RectF(), target = new RectF();
        float alpha, targetAlpha;
        int missing;
        boolean used, primary;
    }

    private final Box[] faces = new Box[MAX_FACES];
    private final Box track = new Box();
    private final boolean[] matched = new boolean[MAX_FACES];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF px = new RectF();
    private final int accent;
    private final float stroke, trackStroke, corner, minBracket, maxBracket, minBox;

    public SubjectOverlayView(Context context) {
        this(context, null);
    }

    public SubjectOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        for (int i = 0; i < MAX_FACES; i++) faces[i] = new Box();
        float dp = getResources().getDisplayMetrics().density;
        stroke = 1.6f * dp;
        trackStroke = 2.2f * dp;
        corner = 4f * dp;
        minBracket = 10f * dp;
        maxBracket = 24f * dp;
        minBox = 16f * dp;
        accent = resolveAccent(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    /** Theme accent (the colour of the locked focus circle), else the material accent, else amber. */
    private static int resolveAccent(Context context) {
        int[] attrs = {android.R.attr.colorControlActivated, android.R.attr.colorAccent};
        for (int attr : attrs) {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(attr, value, true)) {
                if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT)
                    return value.data;
                if (value.resourceId != 0) {
                    try {
                        TypedArray a = context.obtainStyledAttributes(new int[]{attr});
                        int c = a.getColor(0, 0);
                        a.recycle();
                        if (c != 0) return c;
                    } catch (RuntimeException ignored) {
                        // fall through
                    }
                }
            }
        }
        return Color.rgb(255, 204, 0);
    }

    /**
     * New face set: {@code rects} holds {@code count} normalized rectangles (l, t, r, b), {@code primary} the index of
     * the face that drives AF / AE (-1: none).
     */
    public void setFaces(float[] rects, int count, int primary) {
        count = Math.min(count, MAX_FACES);
        for (int j = 0; j < count; j++) matched[j] = false;
        for (Box box : faces) {
            if (!box.used) continue;
            int best = -1;
            float bestDist = MATCH;
            for (int j = 0; j < count; j++) {
                if (matched[j]) continue;
                float d = (float) Math.hypot((rects[4 * j] + rects[4 * j + 2]) * 0.5f - box.target.centerX(),
                        (rects[4 * j + 1] + rects[4 * j + 3]) * 0.5f - box.target.centerY());
                if (d < bestDist) {
                    bestDist = d;
                    best = j;
                }
            }
            if (best >= 0) {
                matched[best] = true;
                box.target.set(rects[4 * best], rects[4 * best + 1], rects[4 * best + 2], rects[4 * best + 3]);
                box.targetAlpha = 1f;
                box.missing = 0;
                box.primary = best == primary;
            } else if (++box.missing >= 2) {
                box.targetAlpha = 0f;
            }
        }
        for (int j = 0; j < count; j++) {
            if (matched[j]) continue;
            Box free = null;
            for (Box box : faces) if (!box.used) { free = box; break; }
            if (free == null) break;
            free.used = true;
            free.target.set(rects[4 * j], rects[4 * j + 1], rects[4 * j + 2], rects[4 * j + 3]);
            free.current.set(free.target);
            free.alpha = 0f;
            free.targetAlpha = 1f;
            free.missing = 0;
            free.primary = j == primary;
        }
        postInvalidateOnAnimation();
    }

    /** Fades every face box out. */
    public void clearFaces() {
        for (Box box : faces) box.targetAlpha = 0f;
        postInvalidateOnAnimation();
    }

    /** Shows / moves the tracking box: centre (u, v), size (w, h), all normalized. */
    public void setTracking(float u, float v, float w, float h) {
        boolean wasHidden = !track.used || track.targetAlpha == 0f;
        track.target.set(u - w * 0.5f, v - h * 0.5f, u + w * 0.5f, v + h * 0.5f);
        if (wasHidden) {
            track.current.set(track.target);
            track.alpha = 0f;
        }
        track.used = true;
        track.targetAlpha = 1f;
        postInvalidateOnAnimation();
    }

    /** Fades the tracking box out (target lost or tracking stopped). */
    public void hideTracking() {
        track.targetAlpha = 0f;
        postInvalidateOnAnimation();
    }

    public void clearAll() {
        clearFaces();
        hideTracking();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        boolean animating = false;
        for (Box box : faces) {
            if (!box.used) continue;
            animating |= step(box);
            if (box.alpha <= 0.02f && box.targetAlpha == 0f) {
                box.used = false;
                continue;
            }
            draw(canvas, box, w, h, box.primary ? accent : OTHER_FACE, stroke);
        }
        if (track.used) {
            animating |= step(track);
            if (track.alpha <= 0.02f && track.targetAlpha == 0f) track.used = false;
            else draw(canvas, track, w, h, accent, trackStroke);
        }
        if (animating) postInvalidateOnAnimation();
    }

    /** One animation step toward the target; true while still moving. */
    private static boolean step(Box box) {
        boolean moving = false;
        float da = box.targetAlpha - box.alpha;
        if (Math.abs(da) > 0.01f) {
            box.alpha += da * FADE;
            moving = true;
        } else {
            box.alpha = box.targetAlpha;
        }
        RectF c = box.current, t = box.target;
        float dl = t.left - c.left, dt = t.top - c.top, dr = t.right - c.right, db = t.bottom - c.bottom;
        if (Math.abs(dl) > 0.001f || Math.abs(dt) > 0.001f || Math.abs(dr) > 0.001f || Math.abs(db) > 0.001f) {
            c.set(c.left + dl * GLIDE, c.top + dt * GLIDE, c.right + dr * GLIDE, c.bottom + db * GLIDE);
            moving = true;
        } else {
            c.set(t);
        }
        return moving;
    }

    private void draw(Canvas canvas, Box box, int w, int h, int color, float width) {
        px.set(box.current.left * w, box.current.top * h, box.current.right * w, box.current.bottom * h);
        if (px.width() < minBox || px.height() < minBox) return;
        paint.setColor(color);
        paint.setAlpha(Math.round(Color.alpha(color) * box.alpha));
        paint.setStrokeWidth(width);
        float len = Math.max(Math.min(Math.min(px.width(), px.height()) * 0.2f, maxBracket), minBracket);
        float r = corner;
        path.reset();
        path.moveTo(px.left, px.top + len);
        path.lineTo(px.left, px.top + r);
        path.quadTo(px.left, px.top, px.left + r, px.top);
        path.lineTo(px.left + len, px.top);
        path.moveTo(px.right - len, px.top);
        path.lineTo(px.right - r, px.top);
        path.quadTo(px.right, px.top, px.right, px.top + r);
        path.lineTo(px.right, px.top + len);
        path.moveTo(px.right, px.bottom - len);
        path.lineTo(px.right, px.bottom - r);
        path.quadTo(px.right, px.bottom, px.right - r, px.bottom);
        path.lineTo(px.right - len, px.bottom);
        path.moveTo(px.left + len, px.bottom);
        path.lineTo(px.left + r, px.bottom);
        path.quadTo(px.left, px.bottom, px.left, px.bottom - r);
        path.lineTo(px.left, px.bottom - len);
        canvas.drawPath(path, paint);
    }
}
