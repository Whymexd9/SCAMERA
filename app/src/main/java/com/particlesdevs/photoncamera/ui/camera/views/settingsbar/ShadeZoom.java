package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.graphics.RectF;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * P176 (owner, 2026-10-16: «Шторка пускай вылезает из пилюли с такой же анимацией как открытие приложений в MacOS»; «По поводу
 * анимации шторки я написал только про 16:9»): with the preview running on under the controls (16:9) the HIDDEN handle is a
 * pill over the preview, and the shade comes out of it and goes back into it.
 * <p>
 * P185 (owner, 2026-10-17: «Исправь анимацию шторки» - a jump, not out of the pill, «При раскрытии шторки нижняя часть не
 * должна появляться и исчезать, её изначально не должно быть при раскрытии»): a container transform. The shade's shape is
 * the pill at the start - its very rectangle and radius - and grows to the shade's visible part (rounded at the bottom
 * too), nothing of the shade ever drawn outside that shape (its part below the visible one never shows). The content,
 * scaled uniformly to the shape's width (its top centre on the shape's top centre), fades in on the way.
 * <p>
 * The frames are a function of t (0 = the pill, 1 = in place) for a view scaled about its top left corner, so the sheet's own
 * transform (scale, translation) and its outline clip draw them.
 */
public final class ShadeZoom {
    static final long OPEN_MS = 380, CLOSE_MS = 300;
    /** Opening: quickly out of the pill, a long soft landing (Material emphasized decelerate); closing: an accelerating fall into it. */
    static final Interpolator OPEN = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f), CLOSE = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    /** Indices of a {@link #frame} result. */
    static final int LEFT = 0, TOP = 1, RIGHT = 2, BOTTOM = 3, RADIUS = 4, SCALE = 5, TX = 6, TY = 7, CONTENT = 8;

    private ShadeZoom() {}

    /** The opening's t {@code ms} after it starts. */
    public static float at(long ms) {
        return OPEN.getInterpolation(Math.min(1f, ms / (float) OPEN_MS));
    }

    /**
     * The frame at {@code t} for a sheet whose visible part is {@code sheet} (its top left the view's top left) coming out of
     * {@code pill} (both in the sheet parent's coordinates): {clip left, top, right, bottom (parent coordinates), clip corner
     * radius, content scale, translationX, translationY, content alpha}.
     */
    public static float[] frame(RectF pill, RectF sheet, float t, float pillRadius, float sheetRadius) {
        t = Math.max(0f, Math.min(1f, t));
        float l = lerp(pill.left, sheet.left, t), top = lerp(pill.top, sheet.top, t);
        float r = lerp(pill.right, sheet.right, t), b = lerp(pill.bottom, sheet.bottom, t);
        float radius = lerp(pillRadius, sheetRadius, t);
        float scale = sheet.width() > 0 ? Math.min(1f, (r - l) / sheet.width()) : 1f;
        float tx = (l + r) / 2f - sheet.left - scale * sheet.width() / 2f;
        float ty = top - sheet.top;
        // the content comes in once the shape has grown a little (the pill is the shade's colour, empty)
        float shown = Math.max(0f, Math.min(1f, (t - 0.18f) / 0.5f));
        float content = shown * shown * (3f - 2f * shown);
        return new float[]{l, top, r, b, radius, scale, tx, ty, content};
    }

    /** The clip of a frame in the view's own (unscaled) coordinates, for a view at {@code left, top} of its parent. */
    static RectF localClip(float[] f, float left, float top) {
        float s = f[SCALE] > 0f ? f[SCALE] : 1f;
        return new RectF((f[LEFT] - left - f[TX]) / s, (f[TOP] - top - f[TY]) / s, (f[RIGHT] - left - f[TX]) / s, (f[BOTTOM] - top - f[TY]) / s);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
