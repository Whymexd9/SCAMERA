package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * SIMPLE_UI §4c: the before / after preview of the «Плёночный LUT» page (the concept's .lutprev): 4:3, 18dp corners with the
 * line border, «До» on the left of a white 2dp cut, «После» on the right, a 30dp white knob with the compare icon. The cut
 * follows a horizontal drag (a vertical one still scrolls the page), a press on the knob or a tap; the tags fade near the
 * edges.
 */
public final class LutCompareView extends View {
    @Nullable private Bitmap before, after;
    private float cut = .5f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    @Nullable private final Drawable knobIcon;
    private final float touchSlop;
    private float downX, downY;
    private boolean dragging, decided;

    public LutCompareView(Context c) {
        super(c);
        knobIcon = c.getDrawable(R.drawable.ui_ic_compare);
        if (knobIcon != null) knobIcon.mutate().setTint(0xFF111111);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        tagPaint.setTextSize(UiTokens.dp(c, 11));
        tagPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        setFocusable(true);
        setContentDescription(Lang.t(c, "Сравнение до и после", "Before and after"));
        setTag("lut_compare");
    }

    public void setImages(@Nullable Bitmap before, @Nullable Bitmap after) {
        this.before = before;
        this.after = after;
        invalidate();
    }

    public float cut() { return cut; }

    public void setCut(float v) {
        cut = Math.max(0f, Math.min(1f, v));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, Math.round(w * .75f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Context c = getContext();
        int w = getWidth(), h = getHeight();
        float r = UiTokens.dp(c, 18);
        rect.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawColor(0xFF000000);
        if (before != null) canvas.drawBitmap(before, null, rect, paint);
        float x = cut * w;
        if (after != null) {
            canvas.save();
            canvas.clipRect(x, 0, w, h);
            canvas.drawBitmap(after, null, rect, paint);
            canvas.restore();
        }
        // the cut: white 2dp with a dark 1dp outline
        float lw = UiTokens.dp(c, 1);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66000000);
        canvas.drawRect(x - 2 * lw, 0, x + 2 * lw, h, paint);
        paint.setColor(0xFFFFFFFF);
        canvas.drawRect(x - lw, 0, x + lw, h, paint);
        // the tags
        tag(canvas, Lang.t(c, "До", "Before"), true, cut < .15f ? 0 : 255);
        tag(canvas, Lang.t(c, "После", "After"), false, cut > .85f ? 0 : 255);
        canvas.restore();
        // the knob (kept inside the frame)
        float k = Math.max(.03f, Math.min(.97f, cut)) * w, kr = UiTokens.dp(c, 15), cy = h / 2f;
        paint.setColor(0x80000000);
        canvas.drawCircle(k, cy + UiTokens.dp(c, 2), kr + lw, paint);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(k, cy, kr, paint);
        if (knobIcon != null) {
            int s = UiTokens.dp(c, 18);
            knobIcon.setBounds(Math.round(k - s / 2f), Math.round(cy - s / 2f), Math.round(k + s / 2f), Math.round(cy + s / 2f));
            knobIcon.draw(canvas);
        }
        // the border
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(lw);
        paint.setColor(UiTokens.LINE);
        rect.inset(lw / 2, lw / 2);
        canvas.drawRoundRect(rect, r, r, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void tag(Canvas canvas, String text, boolean left, int alpha) {
        if (alpha == 0) return;
        Context c = getContext();
        float pad = UiTokens.dp(c, 8), tw = tagPaint.measureText(text), th = UiTokens.dp(c, 18);
        float x0 = left ? pad : getWidth() - pad - tw - 2 * pad;
        RectF t = new RectF(x0, pad, x0 + tw + 2 * pad, pad + th);
        paint.setColor(0xAA000000);
        canvas.drawRoundRect(t, th / 2, th / 2, paint);
        tagPaint.setColor(0xFFFFFFFF);
        canvas.drawText(text, x0 + pad, t.centerY() - (tagPaint.descent() + tagPaint.ascent()) / 2, tagPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                float kx = Math.max(.03f, Math.min(.97f, cut)) * getWidth();
                dragging = Math.abs(e.getX() - kx) < UiTokens.dp(getContext(), 22);   // a press on the knob or the cut
                decided = dragging;
                if (dragging) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    setCut(e.getX() / getWidth());
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!decided) {
                    float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                    if (dx > touchSlop && dx > dy) {
                        decided = dragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (dy > touchSlop) {
                        decided = true;   // a vertical move: the page scrolls
                        return false;
                    }
                }
                if (dragging) setCut(e.getX() / getWidth());
                return true;
            case MotionEvent.ACTION_UP:
                if (!decided && Math.hypot(e.getX() - downX, e.getY() - downY) < touchSlop) {
                    setCut(e.getX() / getWidth());
                    performClick();
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
