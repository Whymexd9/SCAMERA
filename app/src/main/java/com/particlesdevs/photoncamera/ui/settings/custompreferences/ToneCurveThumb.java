package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import com.particlesdevs.photoncamera.processing.color.ToneCurve;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;

/** P144: a saved curve's preview in «Загрузить кривую» - the curve over the straight line on a small rounded field. */
final class ToneCurveThumb extends View {
    private final float[] samples;
    private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG), diagonal = new Paint(Paint.ANTI_ALIAS_FLAG),
            line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private final Path path = new Path();

    ToneCurveThumb(Context c, ToneCurve curve) {
        super(c);
        samples = curve.samples(48);
        int accent = SettingsStyle.accent(c);
        float d = getResources().getDisplayMetrics().density;
        back.setColor(SettingsStyle.FIELD);
        diagonal.setColor(SettingsStyle.LINE);
        diagonal.setStrokeWidth(d);
        line.setColor(accent);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f * d);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density, pad = 6 * d;
        frame.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(frame, 10 * d, 10 * d, back);
        float l = pad, t = pad, w = getWidth() - 2 * pad, h = getHeight() - 2 * pad;
        canvas.drawLine(l, t + h, l + w, t, diagonal);
        path.reset();
        for (int i = 0; i < samples.length; i++) {
            float x = l + w * i / (samples.length - 1f), y = t + h * (1f - samples[i]);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, line);
    }
}
