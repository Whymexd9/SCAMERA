package com.particlesdevs.photoncamera.ui.camera.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

/**
 * P319 (owner, 2026-10-22: «у нас по свайпу по экрану переключаются луты, сделай для этого анимацию чтобы было нагляднее»): a
 * wipe between two LUTs. Before a swipe changes the LUT, the viewfinder's current picture (the old look) is copied from the
 * preview surface (PixelCopy) and drawn over it; the LUT then changes underneath and the old picture is cut away from the side
 * the finger came from, a thin bright edge on the border - the new look slides in with the swipe. Without a copy (no surface
 * yet, PixelCopy failed) the LUT just changes as before. Never takes a touch.
 */
public final class LutWipeView extends View {
    /** The wipe's length and the pause for the preview to show its first frame with the new LUT. */
    static final long DURATION_MS = 320, SETTLE_MS = 50;
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private Bitmap frame;
    @Nullable private ValueAnimator animator;
    /** Share of the old picture already cut away (0..1) and the side it goes to: +1 next LUT (cut from the right), -1 previous. */
    private float progress;
    private int direction;
    private boolean showing;
    private int request;

    public LutWipeView(Context context) { this(context, null); }

    public LutWipeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        edge.setColor(0xE6FFFFFF);
        edge.setStrokeWidth(Math.max(1f, 2f * getResources().getDisplayMetrics().density));
        edge.setShadowLayer(6f * getResources().getDisplayMetrics().density, 0, 0, 0x99000000);
        setLayerType(LAYER_TYPE_SOFTWARE, edge); // the edge's shadow
    }

    /**
     * Copies {@code preview}'s picture, runs {@code change} (the LUT step) and wipes from the old picture to the new one;
     * {@code direction} +1 for the next LUT (a swipe to the left), -1 for the previous one.
     */
    public void wipe(@Nullable SurfaceView preview, int direction, Runnable change) {
        final int ticket = ++request;
        stop();
        if (preview == null || preview.getWidth() <= 0 || preview.getHeight() <= 0 || preview.getHolder().getSurface() == null
                || !preview.getHolder().getSurface().isValid()) {
            change.run();
            return;
        }
        final int w = preview.getWidth(), h = preview.getHeight();
        if (frame == null || frame.getWidth() != w || frame.getHeight() != h) {
            if (frame != null) frame.recycle();
            try {
                frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                frame = null;
                change.run();
                return;
            }
        }
        final Bitmap target = frame;
        try {
            PixelCopy.request(preview, target, result -> {
                change.run(); // every swipe steps once; only the newest one animates
                if (ticket != request || result != PixelCopy.SUCCESS) return;
                start(direction);
            }, main);
        } catch (RuntimeException e) {
            change.run();
        }
    }

    private void start(int direction) {
        this.direction = direction >= 0 ? 1 : -1;
        progress = 0f;
        showing = true;
        invalidate();
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(DURATION_MS);
        a.setStartDelay(SETTLE_MS);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(v -> {
            progress = (float) v.getAnimatedValue();
            invalidate();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (!cancelled) { showing = false; invalidate(); }
            }
        });
        animator = a;
        a.start();
    }

    private void stop() {
        if (animator != null) animator.cancel();
        animator = null;
        showing = false;
        invalidate();
    }

    /** The camera screen goes: the copy's memory back. */
    public void release() {
        request++;
        stop();
        // not recycled: a PixelCopy still in flight may write into it; the GC takes it
        frame = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!showing || frame == null || frame.isRecycled()) return;
        final int w = getWidth(), h = getHeight();
        // the old picture keeps the part the new look has not reached yet
        final float cut = w * progress;
        final float left = direction > 0 ? 0f : cut, right = direction > 0 ? w - cut : w;
        if (right <= left) return;
        canvas.save();
        canvas.clipRect(left, 0, right, h);
        canvas.drawBitmap(frame, null, new android.graphics.RectF(0, 0, w, h), image);
        canvas.restore();
        final float x = direction > 0 ? right : left;
        canvas.drawLine(x, 0, x, h, edge);
    }

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }
}
