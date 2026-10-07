package com.particlesdevs.photoncamera.ui.camera.views;

import android.view.View;
import android.view.ViewGroup;

/**
 * Geometry of the camera screen's bottom chrome (P32, owner 2026-10-07).
 * <ul>
 * <li>The bottom controls (the lens strip, the shutter row and the zoom ruler's place above the strip) sit
 * {@link #RAISE_FRACTION} of the bottom panel's height higher, all by the same amount. The panel is the bottom bar, the
 * area under the 3:4 viewfinder.</li>
 * <li>The HIDDEN shade handle (the shade's dark top edge with its grip) starts at the viewfinder's bottom edge instead of
 * covering the viewfinder's bottom.</li>
 * </ul>
 * Neither may reach the other: the raise stops where the strip would come closer than {@link #CLEARANCE_DP} to the
 * handle, and the handle never comes closer than that to the strip. The zoom ruler only shows while the zoom changes and
 * is drawn over the handle then. The pure functions take pixels; {@link #attach} applies them on every layout.
 */
public final class BottomChrome {
    /** Share of the bottom panel's height the controls are raised by. */
    public static final float RAISE_FRACTION = 0.10f;
    /** Least gap between the HIDDEN handle and the lens strip under it. */
    static final float CLEARANCE_DP = 8f;

    private BottomChrome() {
    }

    /**
     * Pixels the bottom controls move up: {@link #RAISE_FRACTION} of the panel, but the strip stays {@code clearance}
     * under the handle at the panel's top; 0 when even the unraised controls leave no room for the handle.
     *
     * @param panel    the bottom panel's height
     * @param controls height of the unraised controls from the panel's bottom edge up to the strip's top
     * @param handle   the HIDDEN handle's height
     */
    public static int raise(int panel, int controls, int handle, int clearance) {
        if (panel <= 0) return 0;
        int wanted = Math.round(panel * RAISE_FRACTION);
        int room = panel - controls - handle - clearance;
        return Math.max(0, Math.min(wanted, room));
    }

    /**
     * Top of the HIDDEN handle relative to the panel's top: the viewfinder's bottom edge, or the panel's top when the
     * viewfinder ends above it, so the handle never covers the viewfinder. It never comes closer than {@code clearance}
     * to the strip, though: when a 16:9 preview runs on under the controls and the panel has no room left, the handle
     * sits just above the strip (over the preview, as the whole bottom bar does then).
     *
     * @param viewfinderBottom the viewfinder's bottom edge relative to the panel's top
     * @param stripTop         the (raised) strip's top relative to the panel's top
     */
    public static int handleTop(int viewfinderBottom, int stripTop, int handle, int clearance) {
        return Math.min(Math.max(0, viewfinderBottom), stripTop - clearance - handle);
    }

    /**
     * Keeps the views in step on every layout: the shutter row's bottom margin grows by {@link #raise} (the strip and the
     * ruler's place are chained above the row, so they follow it), and {@code handle} moves to {@link #handleTop} by its
     * translation.
     *
     * @param panel      the bottom bar
     * @param row        the shutter row, anchored at the panel's bottom by its bottom margin
     * @param strip      the lens strip, anchored on the row by its bottom margin
     * @param handle     the HIDDEN handle (its slot), laid out with its top at the panel's top
     * @param viewfinder the preview whose bottom edge the handle must not cover
     */
    public static void attach(View panel, View row, View strip, View handle, View viewfinder) {
        ViewGroup.MarginLayoutParams rowParams = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
        int baseMargin = rowParams.bottomMargin;
        int clearance = Math.round(CLEARANCE_DP * panel.getResources().getDisplayMetrics().density);
        View.OnLayoutChangeListener update = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int height = panel.getHeight();
            if (height <= 0) return;
            // The slot's height, also while the shade is open (the controls must not move with the shade).
            int handleHeight = handle.getLayoutParams().height > 0 ? handle.getLayoutParams().height : handle.getHeight();
            int controls = baseMargin + row.getHeight() + ((ViewGroup.MarginLayoutParams) strip.getLayoutParams()).bottomMargin
                    + strip.getHeight();
            int lift = raise(height, controls, handleHeight, clearance);
            if (rowParams.bottomMargin != baseMargin + lift) {
                rowParams.bottomMargin = baseMargin + lift;
                // Not inside this layout pass.
                row.post(() -> row.setLayoutParams(rowParams));
            }
            int panelY = windowY(panel);
            int handleTop = handleTop(windowY(viewfinder) + viewfinder.getHeight() - panelY, height - controls - lift,
                    handleHeight, clearance);
            float offset = panelY + handleTop - (windowY(handle) - handle.getTranslationY());
            if (handle.getTranslationY() != offset) handle.setTranslationY(offset);
        };
        panel.addOnLayoutChangeListener(update);
        // The preview changes its height on its own (aspect ratio of a new camera session).
        viewfinder.addOnLayoutChangeListener(update);
    }

    private static int windowY(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return at[1];
    }
}
