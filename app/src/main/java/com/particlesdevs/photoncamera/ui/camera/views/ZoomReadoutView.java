package com.particlesdevs.photoncamera.ui.camera.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.circularbarlib.ui.UiScale;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SIMPLE_UI §1, the concept's zoom readout (.zhud): while the zoom changes, a 22dp pill in the message band - the value in
 * the accent («2,5×», 12sp bold) and a 150dp mini log scale from the lowest to the highest zoom (third-stop ticks, taller
 * marks at the modules, the accent needle at the zoom). It replaces the 232 x 48dp zoom dial card over the viewfinder; it
 * fades out {@link #HIDE_MS} after the last change and takes the band's place from any message meanwhile.
 */
public class ZoomReadoutView extends View {
    public static final long HIDE_MS = 1300;
    static final float HEIGHT_DP = 22f, SCALE_DP = 150f, PAD_DP = 10f, GAP_DP = 8f, VALUE_DP = 30f;
    static final int FILL = 0xEB1A1F22, FAINT = 0xFF6C757C;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float min = 1f, max = 10f, zoom = 1f;
    private final List<Float> modules = new ArrayList<>();
    private final Runnable hide = () -> animate().alpha(0f).setDuration(200).withEndAction(() -> setVisibility(GONE)).start();

    public ZoomReadoutView(Context context) {
        this(context, null);
    }

    public ZoomReadoutView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        value.setTextSize(UiScale.spPx(context, 12));
        value.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        value.setTextAlign(Paint.Align.RIGHT);
        value.setFontFeatureSettings("tnum");
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The zoom range and the modules' marks. */
    public void configure(float min, float max, List<Float> moduleZooms) {
        if (max > min && min > 0f) {
            this.min = min;
            this.max = max;
        }
        modules.clear();
        modules.addAll(moduleZooms);
        invalidate();
    }

    /** Shows the readout at {@code zoom} and (re)starts its fade-out timer. */
    public void show(float zoom) {
        this.zoom = zoom;
        removeCallbacks(hide);
        animate().cancel();
        setAlpha(1f);
        setVisibility(VISIBLE);
        invalidate();
        postDelayed(hide, HIDE_MS);
    }

    public float getZoom() {
        return zoom;
    }

    /** «2,5×» (the decimal comma in Russian). */
    static String label(float zoom) {
        float rounded = Math.round(zoom * 10f) / 10f;
        String s = Math.abs(rounded - Math.round(rounded)) < 1e-3 ? String.valueOf(Math.round(rounded))
                : String.format(Locale.US, "%.1f", rounded);
        return (Lang.ru() ? s.replace('.', ',') : s) + "×";
    }

    /** Position of {@code z} on the scale, 0..1 (log). */
    float pos(float z) {
        double lo = Math.log(min), hi = Math.log(max);
        return (float) Math.max(0, Math.min(1, (Math.log(Math.max(min, Math.min(max, z))) - lo) / (hi - lo)));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Context c = getContext();
        int w = UiScale.dp(c, PAD_DP * 2 + VALUE_DP + GAP_DP + SCALE_DP);
        setMeasuredDimension(w, UiScale.dp(c, HEIGHT_DP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float h = getHeight(), w = getWidth(), d = UiScale.dpF(c, 1);
        int accent = ShadeStyle.accent(c);
        // the pill
        rect.set(d / 2, d / 2, w - d / 2, h - d / 2);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(FILL);
        canvas.drawRoundRect(rect, h / 2, h / 2, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(d);
        paint.setColor(ShadeStyle.LINE);
        canvas.drawRoundRect(rect, h / 2, h / 2, paint);
        // the value
        value.setColor(accent);
        Paint.FontMetrics fm = value.getFontMetrics();
        float left = UiScale.dpF(c, PAD_DP);
        canvas.drawText(label(zoom), left + UiScale.dpF(c, VALUE_DP), h / 2 - (fm.ascent + fm.descent) / 2, value);
        // the scale: third-stop ticks, module marks, the needle
        float x0 = left + UiScale.dpF(c, VALUE_DP + GAP_DP), sw = UiScale.dpF(c, SCALE_DP), cy = h / 2;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(FAINT);
        for (double z = min; z <= max * 1.0001; z *= Math.pow(2, 1 / 3.0)) {
            float x = x0 + pos((float) z) * sw;
            canvas.drawRect(x - d / 2, cy - 2 * d, x + d / 2, cy + 2 * d, paint);
        }
        paint.setColor(ShadeStyle.MUTED);
        for (float z : modules) {
            float x = x0 + pos(z) * sw;
            canvas.drawRect(x - d / 2, cy - 4 * d, x + d / 2, cy + 4 * d, paint);
        }
        paint.setColor(accent);
        float nx = x0 + pos(zoom) * sw;
        rect.set(nx - 1.5f * d, cy - 6 * d, nx + 1.5f * d, cy + 6 * d);
        canvas.drawRoundRect(rect, 2 * d, 2 * d, paint);
    }
}
