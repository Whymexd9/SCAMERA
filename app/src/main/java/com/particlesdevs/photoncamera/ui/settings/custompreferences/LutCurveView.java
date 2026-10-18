package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.processing.color.LutLook;

/**
 * SIMPLE_UI §4c: the tone curve of «Что меняется» (the concept's lutCurve): greys 0..1 through the LUT at its strength - the
 * dashed diagonal is «no change», the red / green / blue lines the channels, the white line the luminance with the area
 * between it and the diagonal in the accent.
 */
public final class LutCurveView extends View {
    private static final int STEPS = 40;
    @Nullable private LutLook look;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public LutCurveView(Context c) {
        super(c);
        setTag("lut_curve");
    }

    public void setLook(@Nullable LutLook look) {
        this.look = look;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float w = getWidth(), h = getHeight(), r = UiTokens.dp(c, 10);
        rect.set(0, 0, w, h);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF111518);
        canvas.drawRoundRect(rect, r, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(0x22FFFFFF);
        for (int k = 1; k < 4; k++) {
            canvas.drawLine(w * k / 4, 0, w * k / 4, h, paint);
            canvas.drawLine(0, h * k / 4, w, h * k / 4, paint);
        }
        float d = UiTokens.dp(c, 1);
        paint.setPathEffect(new DashPathEffect(new float[]{4 * d, 4 * d}, 0));
        paint.setColor(0x55FFFFFF);
        canvas.drawLine(0, h, w, 0, paint);
        paint.setPathEffect(null);
        if (look == null) return;
        float[][] out = new float[STEPS + 1][];
        for (int k = 0; k <= STEPS; k++) {
            float v = k / (float) STEPS;
            out[k] = look.apply(v, v, v);
        }
        int[] colours = {0xAAFF6B6B, 0xAA6BDC8B, 0xAA6BA8FF};
        paint.setStrokeWidth(1.2f * d);
        for (int ch = 0; ch < 3; ch++) {
            paint.setColor(colours[ch]);
            Path p = new Path();
            for (int k = 0; k <= STEPS; k++) {
                float x = k / (float) STEPS * w, y = h - out[k][ch] * h;
                if (k == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            canvas.drawPath(p, paint);
        }
        Path area = new Path(), line = new Path();
        area.moveTo(0, h);
        for (int k = 0; k <= STEPS; k++) {
            float x = k / (float) STEPS * w, y = h - LutLook.luma(out[k]) * h;
            area.lineTo(x, y);
            if (k == 0) line.moveTo(x, y); else line.lineTo(x, y);
        }
        for (int k = STEPS; k >= 0; k--) area.lineTo(k / (float) STEPS * w, h - k / (float) STEPS * h);
        area.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(UiTokens.alpha(com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette.color(c), .22f));
        canvas.drawPath(area, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * d);
        paint.setColor(0xFFFFFFFF);
        canvas.drawPath(line, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
