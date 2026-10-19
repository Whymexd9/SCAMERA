package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * P277: the lab's before / after - side A left of a draggable border, side B right of it, over one picture space. The border
 * belongs to the screen (owner, 2026-10-19: «полоска сравнения должна всегда оставаться на экране»): zooming and panning move
 * the picture under it, never the border off the view.
 * Double tap zooms in 3x at the tap (again: back to fit), two fingers zoom and pan, a drag away from the border pans a zoomed
 * picture, a long press holds a 3x magnifier over the finger (both sides, the border too). The layers are any pictures of
 * the same scene (the photos, the frame maps, the difference), each drawn over the photo's rectangle.
 */
public class LabCompareView extends View {
    private Bitmap left, right, single;
    private String leftLabel = "", rightLabel = "";
    private float split = .5f;
    private final Matrix view = new Matrix();
    private boolean fitted;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            label = new Paint(Paint.ANTI_ALIAS_FLAG), chip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scale;
    private final GestureDetector gestures;
    private boolean dragSplit, loupe;
    private float lastX, lastY, loupeX, loupeY;
    private final float density;
    private int accent = 0xFFB4A0FF;

    public LabCompareView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        line.setStrokeWidth(2 * density);
        line.setColor(0xFFFFFFFF);
        label.setColor(0xFFFFFFFF);
        label.setTextSize(12 * density);
        chip.setColor(0x99000000);
        scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                view.postScale(d.getScaleFactor(), d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                clamp();
                invalidate();
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (zoom() > 1.05f) fit(); else { view.postScale(3, 3, e.getX(), e.getY()); clamp(); }
                invalidate();
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (dragSplit) return;
                loupe = true;
                loupeX = e.getX();
                loupeY = e.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
            }
        });
        setContentDescription("compare");
    }

    public void setAccent(int accent) {
        this.accent = accent;
        invalidate();
    }

    /** Side by side: {@code a} left of the border, {@code b} right of it. */
    public void setPair(Bitmap a, Bitmap b, String aLabel, String bLabel) {
        left = a;
        right = b;
        single = null;
        leftLabel = aLabel;
        rightLabel = bLabel;
        if (!fitted) fit();
        invalidate();
    }

    /** One picture over the whole width (the difference). */
    public void setSingle(Bitmap picture, String text) {
        single = picture;
        left = right = null;
        leftLabel = text;
        rightLabel = "";
        if (!fitted) fit();
        invalidate();
    }

    private Bitmap base() {
        return single != null ? single : left != null ? left : right;
    }

    /** The picture space: the first photo's size (all layers are stretched over it). */
    private int iw = 1, ih = 1;

    public void setSpace(int w, int h) {
        iw = Math.max(1, w);
        ih = Math.max(1, h);
        fitted = false;
        fit();
    }

    private void fit() {
        if (getWidth() == 0 || getHeight() == 0) return;
        final float s = Math.min((float) getWidth() / iw, (float) getHeight() / ih);
        view.setScale(s, s);
        view.postTranslate((getWidth() - iw * s) / 2, (getHeight() - ih * s) / 2);
        fitted = true;
    }

    private float fitScale() {
        return Math.min((float) getWidth() / iw, (float) getHeight() / ih);
    }

    private float zoom() {
        final float[] v = new float[9];
        view.getValues(v);
        return v[Matrix.MSCALE_X] / Math.max(1e-6f, fitScale());
    }

    /** Never smaller than fit, at most 16x; the picture stays over the view. */
    private void clamp() {
        final float z = zoom();
        if (z < 1) { fit(); return; }
        if (z > 16) view.postScale(16 / z, 16 / z, getWidth() / 2f, getHeight() / 2f);
        final RectF r = new RectF(0, 0, iw, ih);
        view.mapRect(r);
        float dx = 0, dy = 0;
        if (r.width() <= getWidth()) dx = getWidth() / 2f - r.centerX();
        else if (r.left > 0) dx = -r.left; else if (r.right < getWidth()) dx = getWidth() - r.right;
        if (r.height() <= getHeight()) dy = getHeight() / 2f - r.centerY();
        else if (r.top > 0) dy = -r.top; else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
        view.postTranslate(dx, dy);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    /** The border's x on the view (a share of the view's width, whatever the zoom). */
    private float splitX() {
        return split * getWidth();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scale.onTouchEvent(e);
        gestures.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragSplit = single == null && Math.abs(e.getX() - splitX()) < 32 * density;
                lastX = e.getX();
                lastY = e.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (scale.isInProgress() || e.getPointerCount() > 1) { lastX = e.getX(); lastY = e.getY(); return true; }
                if (loupe) { loupeX = e.getX(); loupeY = e.getY(); }
                else if (dragSplit) split = Math.max(.02f, Math.min(.98f, e.getX() / Math.max(1, getWidth()))); else if (zoom() > 1.01f) {
                    view.postTranslate(e.getX() - lastX, e.getY() - lastY);
                    clamp();
                }
                lastX = e.getX();
                lastY = e.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                loupe = false;
                dragSplit = false;
                invalidate();
                return true;
        }
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (base() == null) return;
        if (!fitted) fit();
        canvas.drawColor(0xFF000000);
        layers(canvas, view, splitX());
        if (single == null) {
            final float x = splitX();
            canvas.drawLine(x, 0, x, getHeight(), line);
            final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
            knob.setColor(accent);
            canvas.drawCircle(x, getHeight() / 2f, 14 * density, knob);
            knob.setColor(0xFF15131D);
            knob.setTextSize(13 * density);
            knob.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("‹›", x, getHeight() / 2f + 4.5f * density, knob);
        }
        tag(canvas, leftLabel, 8 * density, false);
        if (!rightLabel.isEmpty()) tag(canvas, rightLabel, getWidth() - 8 * density, true);
        if (loupe) drawLoupe(canvas);
    }

    /** The layers through {@code m}; A left of {@code cut} (an x of the canvas), B right of it. */
    private void layers(Canvas canvas, Matrix m, float cut) {
        final RectF all = new RectF(0, 0, iw, ih);
        if (single != null) {
            canvas.save();
            canvas.concat(m);
            canvas.drawBitmap(single, null, all, paint);
            canvas.restore();
            return;
        }
        canvas.save();
        canvas.clipRect(-1e5f, -1e5f, cut, 1e5f);
        canvas.concat(m);
        if (left != null) canvas.drawBitmap(left, null, all, paint);
        canvas.restore();
        canvas.save();
        canvas.clipRect(cut, -1e5f, 1e5f, 1e5f);
        canvas.concat(m);
        if (right != null) canvas.drawBitmap(right, null, all, paint);
        canvas.restore();
    }

    private void tag(Canvas canvas, String text, float x, boolean alignRight) {
        if (text == null || text.isEmpty()) return;
        final float w = label.measureText(text), pad = 6 * density, top = 8 * density, h = 22 * density;
        final float l = alignRight ? x - w - 2 * pad : x;
        canvas.drawRoundRect(new RectF(l, top, l + w + 2 * pad, top + h), 8 * density, 8 * density, chip);
        canvas.drawText(text, l + pad, top + h - 7 * density, label);
    }

    private void drawLoupe(Canvas canvas) {
        final float r = 72 * density;
        final float cx = loupeX, cy = Math.max(r + 4 * density, loupeY - r - 40 * density);
        final Matrix m = new Matrix(view);
        m.postScale(3, 3, loupeX, loupeY);
        m.postTranslate(cx - loupeX, cy - loupeY);
        final Path circle = new Path();
        circle.addCircle(cx, cy, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(circle);
        canvas.drawColor(0xFF000000);
        // the screen's border as the magnifier shows it: x -> cx + 3 (x - loupeX)
        final float cut = cx + 3 * (splitX() - loupeX);
        layers(canvas, m, cut);
        if (single == null) canvas.drawLine(cut, cy - r, cut, cy + r, line);
        canvas.restore();
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * density);
        ring.setColor(accent);
        canvas.drawCircle(cx, cy, r, ring);
    }
}
