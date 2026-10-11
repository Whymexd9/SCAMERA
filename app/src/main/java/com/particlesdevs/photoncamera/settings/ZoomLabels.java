package com.particlesdevs.photoncamera.settings;

import java.util.Locale;

/**
 * P100c Module zoom labels as the phone's own camera names them (owner, 2026-10-12, Galaxy S24 Ultra: the strip showed
 * «0,6× 1× 2,7× 5,1×», the stock camera «0,6× 1× 3× 5×»). The automatic ratio is the lens's field of view against the main
 * camera's (35 mm equivalents from the metadata: main 22.3 mm, tele 61.0 mm = 2.73×, periscope 112.7 mm = 5.05×, UW 13.6 mm =
 * 0.61×). On Samsung the label and the module's zoom snap to the stock steps; the true field of view stays the module's native
 * ratio, so at 3× the tele is cropped by 3 / 2.73 and the frame does not widen when the zoom moves from the main to the tele.
 * Other brands keep the measured ratio (vivo's 3.7× tele, OPPO's 2.8× / 5.9× are their own labels).
 */
public final class ZoomLabels {
    /** Samsung's stock zoom steps. */
    static final float[] SAMSUNG_STEPS = {0.6f, 1f, 2f, 3f, 5f, 10f};
    /** How far (relative) a measured ratio may be from a step to take its name. */
    static final float TOLERANCE = 0.12f;

    private ZoomLabels() {}

    /** Whether this brand's labels snap to its stock steps. */
    public static boolean snaps(String brand) {
        return brand != null && brand.equalsIgnoreCase("samsung");
    }

    /** The stock step of a measured ratio (that ratio itself when no step is close enough, or the brand does not snap). */
    public static float nominal(float ratio, String brand) {
        if (!snaps(brand) || !(ratio > 0f)) return ratio;
        for (float step : SAMSUNG_STEPS) if (Math.abs(ratio - step) <= TOLERANCE * step) return step;
        return ratio;
    }

    /** «3×», «0.6×», «2.7×»: the automatic label of a measured ratio. */
    public static String label(float ratio, String brand) {
        return String.format(Locale.US, "%.1f×", nominal(ratio, brand)).replace(".0×", "×");
    }
}
