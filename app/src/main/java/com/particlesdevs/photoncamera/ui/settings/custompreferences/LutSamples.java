package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.color.LutLook;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * SIMPLE_UI §4c: the three sample pictures of the «Плёночный LUT» page (the concept's drawSample: «Пейзаж», «Закат», «Шкала
 * цветов») drawn here, so the page needs no bundled photo, and the LUT applied to them per pixel (trilinear, as the photo).
 */
public final class LutSamples {
    public static final int COUNT = 3;
    /** The concept's scene palettes: sky top, sky middle, horizon, far ridge, near ridge, sun. */
    private static final String[][] PALS = {
            {"#203c55", "#b9826b", "#f3c39b", "#3d4660", "#262c3a", "#ffd9a0"},
            {"#1d4a63", "#6fa3b8", "#d8e6e3", "#3b5d63", "#22363b", "#fff6d8"},
            {"#2b2347", "#9d5f7a", "#f0a07e", "#4a3b5a", "#271f33", "#ffc28a"}};

    private LutSamples() {}

    public static String name(Context c, int i) {
        switch (i) {
            case 0: return Lang.t(c, "Пейзаж", "Landscape");
            case 1: return Lang.t(c, "Закат", "Sunset");
            default: return Lang.t(c, "Шкала цветов", "Colour chart");
        }
    }

    /** Sample {@code i} at w x h. */
    public static Bitmap draw(int i, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (i == 0) scene(c, w, h, 0, 1.5f);
        else if (i == 1) scene(c, w, h, 2, 1.15f);
        else chart(c, w, h);
        return b;
    }

    /** The concept's rnd(seed): a small LCG, so the scenes match it. */
    private static final class Rnd {
        long s;

        Rnd(int seed) { s = seed * 9301L + 49297L; }

        float next() {
            s = (s * 9301L + 49297L) % 233280L;
            return s / 233280f;
        }
    }

    private static int col(String hex) { return Color.parseColor(hex); }

    private static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    static void scene(Canvas ctx, int w, int h, int seed, float zoom) {
        String[] p = PALS[seed % PALS.length];
        Rnd r = new Rnd(seed + 3);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ctx.save();
        float cx = w * .62f, cy = h * .47f;
        ctx.translate(cx, cy);
        ctx.scale(zoom, zoom);
        ctx.translate(-cx, -cy);
        paint.setShader(new LinearGradient(0, 0, 0, h * .62f, new int[]{col(p[0]), col(p[1]), col(p[2])}, new float[]{0f, .6f, 1f},
                Shader.TileMode.CLAMP));
        ctx.drawRect(-w, -h, w * 2, h * 2, paint);
        float sx = w * (.55f + r.next() * .2f), sy = h * (.38f + r.next() * .08f), sr = w * .055f;
        paint.setShader(new RadialGradient(sx, sy, sr * 6, alpha(col(p[5]), 0xCC), alpha(col(p[5]), 0), Shader.TileMode.CLAMP));
        ctx.drawRect(sx - sr * 6, sy - sr * 6, sx + sr * 6, sy + sr * 6, paint);
        paint.setShader(null);
        paint.setColor(col(p[5]));
        ctx.drawCircle(sx, sy, sr, paint);
        ridge(ctx, paint, w, h, seed, .6f, .16f, col(p[3]), 3.1f);
        ridge(ctx, paint, w, h, seed, .66f, .09f, col(p[4]), 5.3f);
        paint.setShader(new LinearGradient(0, h * .66f, 0, h, col(p[1]), col(p[4]), Shader.TileMode.CLAMP));
        ctx.drawRect(-w, h * .66f, w * 2, h * 3, paint);
        paint.setShader(null);
        paint.setColor(alpha(col(p[5]), 0x88));
        float px = Math.max(1f, w / 360f);
        for (int i = 0; i < 14; i++) {
            float yy = h * .68f + i * h * .022f, x = sx - sr * (1.4f - i * .06f) - r.next() * 6 * px;
            ctx.drawRect(x, yy, x + sr * (2.8f - i * .1f), yy + 2 * px, paint);
        }
        paint.setColor(0xFF0D1116);
        Path tree = new Path();
        for (int i = 0; i < 9; i++) {
            float tx = w * (.02f + i * .05f + r.next() * .03f), th = h * (.16f + r.next() * .14f), tw = th * .32f, by = h * .7f;
            tree.reset();
            tree.moveTo(tx, by - th);
            tree.lineTo(tx - tw, by);
            tree.lineTo(tx + tw, by);
            tree.close();
            ctx.drawPath(tree, paint);
        }
        ctx.drawRect(-w, h * .7f, w * .5f, h * .712f, paint);
        ctx.restore();
    }

    private static void ridge(Canvas ctx, Paint paint, int w, int h, int seed, float base, float amp, int color, float f) {
        paint.setShader(null);
        paint.setColor(color);
        Path path = new Path();
        path.moveTo(-w, h * 2);
        for (float x = -w; x <= w * 2; x += w / 60f) {
            float y = (float) (h * base - Math.abs(Math.sin(x / w * f + seed)) * h * amp - Math.sin(x / w * f * 2.7 + seed * 2) * h * amp * .35);
            path.lineTo(x, y);
        }
        path.lineTo(w * 2, h * 2);
        path.close();
        ctx.drawPath(path, paint);
    }

    static void chart(Canvas ctx, int w, int h) {
        ctx.drawColor(0xFF202326);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float pw = w / 6f, ph = h * .78f / 4f, m = Math.max(1f, 3f * w / 360f);
        for (int k = 0; k < LutLook.CHART.length; k++) {
            int[] c = LutLook.CHART[k];
            paint.setColor(Color.rgb(c[0], c[1], c[2]));
            float x = (k % 6) * pw, y = (k / 6) * ph;
            ctx.drawRect(x + m, y + m, x + pw - m, y + ph - m, paint);
        }
        paint.setShader(new LinearGradient(0, 0, w, 0, 0xFF000000, 0xFFFFFFFF, Shader.TileMode.CLAMP));
        ctx.drawRect(m, h * .8f, w - m, h - m, paint);
    }

    /** {@code src} through {@code lut} at full strength (null: a copy). */
    public static int[] apply(int[] src, FilmLut lut) {
        int[] out = new int[src.length];
        if (lut == null) {
            System.arraycopy(src, 0, out, 0, src.length);
            return out;
        }
        float[] o = new float[3];
        for (int i = 0; i < src.length; i++) {
            int p = src[i];
            lut.sampleInto(((p >> 16) & 255) / 255f, ((p >> 8) & 255) / 255f, (p & 255) / 255f, o);
            out[i] = 0xFF000000 | (to8(o[0]) << 16) | (to8(o[1]) << 8) | to8(o[2]);
        }
        return out;
    }

    /** The linear mix before + (after - before) x strength (strength 0..1), as the photo mixes the LUT. */
    public static int[] mix(int[] before, int[] after, float strength) {
        int[] out = new int[before.length];
        float k = Math.max(0f, Math.min(1f, strength));
        for (int i = 0; i < before.length; i++) {
            int a = before[i], b = after[i];
            int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * k);
            int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * k);
            int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * k);
            out[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
        }
        return out;
    }

    static int to8(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }
}
