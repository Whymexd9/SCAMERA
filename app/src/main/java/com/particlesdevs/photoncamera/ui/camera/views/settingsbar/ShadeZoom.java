package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.graphics.RectF;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * P176 (owner, 2026-10-16: «Шторка пускай вылезает из пилюли с такой же анимацией как открытие приложений в MacOS»; «По поводу
 * анимации шторки я написал только про 16:9»): with the preview running on under the controls (16:9) the HIDDEN handle is a
 * pill over the preview, and the shade grows out of it as a macOS window opens out of its Dock icon - one uniform scale
 * from the pill's width, its centre travelling from the pill's centre to its own, quickly visible, a long soft landing -
 * and shrinks back into it when it closes.
 * <p>
 * The frames are a function of t (0 = in the pill, 1 = in place) for a view scaled about its top left corner, so the
 * sheet's own transform (scale, translation, alpha) draws them.
 */
public final class ShadeZoom {
    static final long OPEN_MS = 420, CLOSE_MS = 300;
    /** Opening: fast out of the pill, a long deceleration into place (macOS-like); closing: an accelerating fall into it. */
    static final Interpolator OPEN = new PathInterpolator(0.16f, 1f, 0.3f, 1f), CLOSE = new PathInterpolator(0.5f, 0f, 0.9f, 0.6f);

    private ShadeZoom() {}

    /** The opening's t {@code ms} after it starts. */
    public static float at(long ms) {
        return OPEN.getInterpolation(Math.min(1f, ms / (float) OPEN_MS));
    }

    /**
     * {scale, translationX, translationY, alpha} at {@code t} for a sheet whose visible part is {@code sheet} (its top left
     * the view's top left) growing out of {@code pill} (both in the sheet parent's coordinates).
     */
    public static float[] frame(RectF pill, RectF sheet, float t) {
        t = Math.max(0f, Math.min(1f, t));
        float start = sheet.width() > 0 ? Math.min(1f, pill.width() / sheet.width()) : 1f;
        float scale = start + (1f - start) * t;
        float cx = pill.centerX() + (sheet.centerX() - pill.centerX()) * t;
        float cy = pill.centerY() + (sheet.centerY() - pill.centerY()) * t;
        float tx = cx - sheet.left - scale * sheet.width() / 2f;
        float ty = cy - sheet.top - scale * sheet.height() / 2f;
        float shown = Math.min(1f, t / 0.35f);
        float alpha = 0.2f + 0.8f * shown * shown * (3f - 2f * shown);
        return new float[]{scale, tx, ty, alpha};
    }
}
