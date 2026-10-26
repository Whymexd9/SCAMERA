package com.particlesdevs.photoncamera.gallery.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;

/**
 * P351: the crop frame over the editor's picture in the «Кадр» tab: the frame, corner handles, thirds while dragging. A corner
 * or an edge resizes (keeping a chosen ratio), a drag inside moves the frame, two fingers scale it about its centre; every move
 * is stopped at the edge of the straightened photo (CropMath.constrain). The picture under it is drawn by the GL view with the
 * same canvas rect: the whole turned frame (base uv 0..1) fitted into the view.
 */
public final class CropOverlayView extends View {
    public interface Listener {
        /** The new crop (l, t, r, b in base uv); {@code done} when the finger is lifted. */
        void crop(float[] rect, boolean done);
    }

    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG), handle = new Paint(Paint.ANTI_ALIAS_FLAG),
            grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF canvasRect = new RectF();
    private float[] rect = {0, 0, 1, 1};
    private double wb = 1, hb = 1, degrees;
    /** Pixel ratio w / h to keep, 0 for free. */
    private double ratio;
    @Nullable private Listener listener;
    private int target = -1; // 0..3 corners (lt, rt, rb, lb), 4..7 edges (l, t, r, b), 8 move
    private float lastX, lastY, pinchDist;
    private float[] start;
    private boolean pinching, active;

    public CropOverlayView(Context c) {
        super(c);
        frame.setStyle(Paint.Style.STROKE);
        frame.setColor(0xFFFFFFFF);
        frame.setStrokeWidth(GalleryUi.dp(c, 1.5f));
        handle.setStyle(Paint.Style.STROKE);
        handle.setColor(0xFFFFFFFF);
        handle.setStrokeWidth(GalleryUi.dp(c, 3.5f));
        handle.setStrokeCap(Paint.Cap.ROUND);
        grid.setStyle(Paint.Style.STROKE);
        grid.setColor(0x99FFFFFF);
        grid.setStrokeWidth(GalleryUi.dp(c, 0.8f));
        setContentDescription("crop");
    }

    public void setListener(@Nullable Listener l) {
        listener = l;
    }

    /** The canvas (view px of base uv 0..1), the turned frame's pixel size, the straighten angle, the ratio to keep. */
    public void bind(RectF canvas, double wb, double hb, double degrees, double ratio, float[] rect) {
        canvasRect.set(canvas);
        this.wb = wb;
        this.hb = hb;
        this.degrees = degrees;
        this.ratio = ratio;
        if (!active) this.rect = rect.clone();
        invalidate();
    }

    private float px(float u) { return canvasRect.left + u * canvasRect.width(); }

    private float py(float v) { return canvasRect.top + v * canvasRect.height(); }

    @Override
    protected void onDraw(Canvas c) {
        if (canvasRect.width() <= 0) return;
        float l = px(rect[0]), t = py(rect[1]), r = px(rect[2]), b = py(rect[3]);
        c.drawRect(l, t, r, b, frame);
        if (active) {
            for (int i = 1; i < 3; i++) {
                float x = l + (r - l) * i / 3f, y = t + (b - t) * i / 3f;
                c.drawLine(x, t, x, b, grid);
                c.drawLine(l, y, r, y, grid);
            }
        }
        float k = GalleryUi.dp(getContext(), 18);
        float[][] corners = {{l, t, 1, 1}, {r, t, -1, 1}, {r, b, -1, -1}, {l, b, 1, -1}};
        for (float[] p : corners) {
            c.drawLine(p[0], p[1], p[0] + k * p[2], p[1], handle);
            c.drawLine(p[0], p[1], p[0], p[1] + k * p[3], handle);
        }
    }

    private int hit(float x, float y) {
        float l = px(rect[0]), t = py(rect[1]), r = px(rect[2]), b = py(rect[3]);
        float rc = GalleryUi.dp(getContext(), 30), re = GalleryUi.dp(getContext(), 22);
        float[][] corners = {{l, t}, {r, t}, {r, b}, {l, b}};
        for (int i = 0; i < 4; i++) if (Math.hypot(x - corners[i][0], y - corners[i][1]) < rc) return i;
        if (y > t && y < b) {
            if (Math.abs(x - l) < re) return 4;
            if (Math.abs(x - r) < re) return 6;
        }
        if (x > l && x < r) {
            if (Math.abs(y - t) < re) return 5;
            if (Math.abs(y - b) < re) return 7;
        }
        return 8;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                target = hit(e.getX(), e.getY());
                lastX = e.getX();
                lastY = e.getY();
                start = rect.clone();
                active = true;
                pinching = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) {
                    pinching = true;
                    pinchDist = dist(e);
                    start = rect.clone();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pinching && e.getPointerCount() >= 2) {
                    float d = dist(e);
                    if (pinchDist > 0 && d > 0) apply(scaled(start, pinchDist / d));
                    return true;
                }
                if (pinching) return true;
                float du = (e.getX() - lastX) / Math.max(1f, canvasRect.width()), dv = (e.getY() - lastY) / Math.max(1f, canvasRect.height());
                apply(moved(start, target, du, dv));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                active = false;
                pinching = false;
                invalidate();
                if (listener != null) listener.crop(rect.clone(), true);
                return true;
            default:
                return true;
        }
    }

    private static float dist(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private void apply(float[] candidate) {
        float[] c = CropMath.constrain(rect, candidate, wb, hb, degrees);
        rect = c;
        invalidate();
        if (listener != null) listener.crop(c.clone(), false);
    }

    /** The crop scaled by {@code k} about its centre (fingers apart: k < 1, a tighter crop). */
    float[] scaled(float[] r, float k) {
        float cx = (r[0] + r[2]) / 2, cy = (r[1] + r[3]) / 2, hw = (r[2] - r[0]) / 2 * k, hh = (r[3] - r[1]) / 2 * k;
        float min = minSize();
        hw = Math.max(hw, min / 2);
        hh = Math.max(hh, min / 2);
        return new float[]{cx - hw, cy - hh, cx + hw, cy + hh};
    }

    private float minSize() {
        return 0.08f;
    }

    /** The crop after dragging {@code target} by (du, dv) from {@code r}; a kept ratio holds the opposite corner / the centre line. */
    float[] moved(float[] r, int target, float du, float dv) {
        float l = r[0], t = r[1], rr = r[2], b = r[3];
        float min = minSize();
        if (target == 8) return new float[]{l + du, t + dv, rr + du, b + dv};
        boolean left = target == 0 || target == 3 || target == 4, right = target == 1 || target == 2 || target == 6;
        boolean top = target == 0 || target == 1 || target == 5, bottom = target == 2 || target == 3 || target == 7;
        if (left) l = Math.min(l + du, rr - min);
        if (right) rr = Math.max(rr + du, l + min);
        if (top) t = Math.min(t + dv, b - min);
        if (bottom) b = Math.max(b + dv, t + min);
        if (ratio > 0) {
            // keep w_px / h_px = ratio: heights follow widths (an edge drag keeps the other axis centred)
            double wpx = (rr - l) * wb, hpx = (b - t) * hb;
            if (target >= 4) {
                if (left || right) {
                    float nh = (float) (wpx / ratio / hb), cy = (r[1] + r[3]) / 2;
                    t = cy - nh / 2;
                    b = cy + nh / 2;
                } else {
                    float nw = (float) (hpx * ratio / wb), cx = (r[0] + r[2]) / 2;
                    l = cx - nw / 2;
                    rr = cx + nw / 2;
                }
            } else {
                float nh = (float) (wpx / ratio / hb);
                if (top) t = b - nh;
                else b = t + nh;
            }
        }
        return new float[]{l, t, rr, b};
    }
}
