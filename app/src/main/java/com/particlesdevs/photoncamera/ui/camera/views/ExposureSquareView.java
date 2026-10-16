package com.particlesdevs.photoncamera.ui.camera.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * P142: the exposure square of «Раздельные фокус и экспозиция» - a rounded square with a small sun at its top right corner,
 * drawn around the focus ring after a tap and dragged on its own (the AE regions follow it).
 */
public class ExposureSquareView extends View {
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    public ExposureSquareView(Context context) {
        super(context);
        final float dp = context.getResources().getDisplayMetrics().density;
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(1.5f * dp);
        frame.setColor(Color.parseColor("#FFD54F"));
        sun.setStyle(Paint.Style.FILL_AND_STROKE);
        sun.setStrokeWidth(1.2f * dp);
        sun.setStrokeCap(Paint.Cap.ROUND);
        sun.setColor(Color.parseColor("#FFD54F"));
        setContentDescription(com.particlesdevs.photoncamera.util.Lang.t("Экспозиция", "Exposure"));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float dp = getResources().getDisplayMetrics().density;
        final float inset = 8f * dp, radius = 6f * dp;
        box.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawRoundRect(box, radius, radius, frame);
        // the sun: a disc and eight short rays just outside the top right corner
        final float cx = box.right, cy = box.top, r = 2.4f * dp;
        canvas.drawCircle(cx, cy, r, sun);
        for (int i = 0; i < 8; i++) {
            final double a = Math.PI / 4 * i;
            final float c = (float) Math.cos(a), s = (float) Math.sin(a);
            canvas.drawLine(cx + c * (r + 1.6f * dp), cy + s * (r + 1.6f * dp), cx + c * (r + 3.6f * dp), cy + s * (r + 3.6f * dp), sun);
        }
    }
}
