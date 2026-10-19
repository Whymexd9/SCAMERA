package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * P277: the lab shot's photo with the fragment the lab merges (a normalised rectangle of the photo, null: the whole frame);
 * a tap or a drag moves the fragment's centre there.
 */
public class LabPickView extends View {
    public interface OnPick { void at(float x, float y); }

    private Bitmap photo;
    @Nullable private float[] rect;
    private OnPick onPick;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG), dim = new Paint(), frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shown = new RectF();

    public LabPickView(Context c) {
        super(c);
        dim.setColor(0x99000000);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(2 * c.getResources().getDisplayMetrics().density);
    }

    public void set(Bitmap photo, @Nullable float[] rect, int accent, OnPick onPick) {
        this.photo = photo;
        this.rect = rect;
        this.onPick = onPick;
        frame.setColor(accent);
        requestLayout();
        invalidate();
    }

    public void setRect(@Nullable float[] rect) {
        this.rect = rect;
        invalidate();
    }

    @Override protected void onMeasure(int ws, int hs) {
        final int w = MeasureSpec.getSize(ws);
        final int h = photo == null ? w * 3 / 4 : Math.round(w * (float) photo.getHeight() / photo.getWidth());
        setMeasuredDimension(w, Math.min(h, Math.round(getResources().getDisplayMetrics().heightPixels * .55f)));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (photo == null) return;
        final float s = Math.min((float) getWidth() / photo.getWidth(), (float) getHeight() / photo.getHeight());
        final float w = photo.getWidth() * s, h = photo.getHeight() * s;
        shown.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
        canvas.drawBitmap(photo, null, shown, paint);
        if (rect == null) return;
        final RectF r = new RectF(shown.left + rect[0] * w, shown.top + rect[1] * h, shown.left + rect[2] * w, shown.top + rect[3] * h);
        canvas.drawRect(shown.left, shown.top, shown.right, r.top, dim);
        canvas.drawRect(shown.left, r.bottom, shown.right, shown.bottom, dim);
        canvas.drawRect(shown.left, r.top, r.left, r.bottom, dim);
        canvas.drawRect(r.right, r.top, shown.right, r.bottom, dim);
        canvas.drawRect(r, frame);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (photo == null || rect == null || onPick == null || shown.width() <= 0) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            onPick.at(Math.max(0, Math.min(1, (e.getX() - shown.left) / shown.width())),
                    Math.max(0, Math.min(1, (e.getY() - shown.top) / shown.height())));
        }
        return true;
    }
}
