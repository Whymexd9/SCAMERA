package com.particlesdevs.photoncamera.gallery.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;

/**
 * P351: the editor's live histogram (R, G, B, square-root counts, added up like the gallery's Histogram view) of the edited
 * picture, read back from a small render of the crop at most every ~120 ms while sliders move. A plain view: no GL context of
 * its own (the gallery's Histogram makes one).
 */
public final class EditHistogramView extends android.view.View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    @Nullable private int[][] counts;

    public EditHistogramView(Context c) {
        super(c);
        bg.setColor(GalleryUi.PILL_BG);
        paint.setStyle(Paint.Style.FILL);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** counts[3][256] (R, G, B) from {@link #of}. */
    public void set(@Nullable int[][] counts) {
        this.counts = counts;
        invalidate();
    }

    /** The histogram of RGBA8 pixels (bytes R, G, B, A). */
    static int[][] of(byte[] rgba, int n) {
        int[][] h = new int[3][256];
        for (int i = 0; i < n; i++) {
            h[0][rgba[4 * i] & 0xFF]++;
            h[1][rgba[4 * i + 1] & 0xFF]++;
            h[2][rgba[4 * i + 2] & 0xFF]++;
        }
        return h;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = GalleryUi.dp(getContext(), 10);
        c.drawRoundRect(0, 0, w, h, r, r, bg);
        if (counts == null) return;
        double max = 1;
        for (int[] ch : counts)
            for (int i = 1; i < 255; i++) max = Math.max(max, Math.sqrt(ch[i]));
        int[] colours = {0xFFFF3B30, 0xFF34C759, 0xFF2F6BFF};
        float pad = GalleryUi.dp(getContext(), 4);
        for (int k = 0; k < 3; k++) {
            paint.setColor(colours[k]);
            paint.setAlpha(200);
            path.reset();
            path.moveTo(pad, h - pad);
            for (int i = 0; i < 256; i++) {
                float x = pad + (w - 2 * pad) * i / 255f;
                float y = (float) (h - pad - (h - 2 * pad) * Math.min(1, Math.sqrt(counts[k][i]) / max));
                path.lineTo(x, y);
            }
            path.lineTo(w - pad, h - pad);
            path.close();
            c.drawPath(path, paint);
        }
    }
}
