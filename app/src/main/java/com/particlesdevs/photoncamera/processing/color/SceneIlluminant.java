package com.particlesdevs.photoncamera.processing.color;

/**
 * The colour of the scene's light, from the white balance the camera chose for the shot.
 * <p>
 * The DNG matrices of the vivo X200 Ultra do not describe its sensors (the interpolation of the neutral
 * point lands on a reference illuminant for every scene), so the colour temperature comes from the
 * neutral point itself: the raw colour of a white under the scene's light, against the raw colour of a
 * white under daylight, through the camera's colour matrix, is the light's colour on the display; its
 * chromaticity gives the correlated colour temperature (McCamy).
 */
public final class SceneIlluminant {
    private SceneIlluminant() {}

    /** Daylight SENSOR_NEUTRAL_COLOR_POINT (overcast noon, 2026-10-01) of the vivo X200 Ultra (PD2454) cameras. */
    private static final String[] PD2454_CAMERAS = {"3", "4", "5"};
    private static final float[][] PD2454_DAYLIGHT = {
            {0.3945f, 1f, 0.6396f},   // 3: main (1x)
            {0.3896f, 1f, 0.6709f},   // 4: ultra wide (0.4x)
            {0.4668f, 1f, 0.6904f},   // 5: tele (2.4x, ISZ 6.7x / 10x)
    };

    /** The raw colour of a white under daylight for this camera, or null when it is not known. */
    public static float[] daylightNeutral(String cameraId) {
        if (cameraId == null || !"PD2454".equals(android.os.Build.DEVICE)) return null;
        for (int i = 0; i < PD2454_CAMERAS.length; i++)
            if (PD2454_CAMERAS[i].equals(cameraId)) return PD2454_DAYLIGHT[i].clone();
        return null;
    }

    /**
     * Linear sRGB colour of the scene's light (green = 1) seen through a daylight white balance, or null
     * when the camera has no daylight neutral. {@code cameraToSrgb} is the matrix the render applies after
     * the white balance division (the ISP colour transform on vivo).
     */
    public static float[] colour(String cameraId, float[] neutral, float[] cameraToSrgb) {
        float[] day = daylightNeutral(cameraId);
        if (day == null || neutral == null || neutral.length < 3 || cameraToSrgb == null || cameraToSrgb.length < 9) return null;
        for (int i = 0; i < 3; i++) if (!(neutral[i] > 0f) || !(day[i] > 0f)) return null;
        float[] white = {neutral[0] / day[0], neutral[1] / day[1], neutral[2] / day[2]};
        float[] rgb = new float[3];
        for (int r = 0; r < 3; r++)
            rgb[r] = cameraToSrgb[r * 3] * white[0] + cameraToSrgb[r * 3 + 1] * white[1] + cameraToSrgb[r * 3 + 2] * white[2];
        if (!(rgb[1] > 1e-4f)) return null;
        return new float[]{Math.max(rgb[0], 1e-3f) / rgb[1], 1f, Math.max(rgb[2], 1e-3f) / rgb[1]};
    }

    /** Correlated colour temperature (K) of a linear sRGB colour (McCamy), clamped to 1800..12000. */
    public static float cct(float[] rgb) {
        double X = 0.4124564 * rgb[0] + 0.3575761 * rgb[1] + 0.1804375 * rgb[2];
        double Y = 0.2126729 * rgb[0] + 0.7151522 * rgb[1] + 0.0721750 * rgb[2];
        double Z = 0.0193339 * rgb[0] + 0.1191920 * rgb[1] + 0.9503041 * rgb[2];
        double sum = X + Y + Z;
        if (!(sum > 1e-6)) return 6504f;
        double x = X / sum, y = Y / sum;
        double n = (x - 0.3320) / (0.1858 - y);
        double cct = 449.0 * n * n * n + 3525.0 * n * n + 6823.3 * n + 5520.33;
        if (Double.isNaN(cct)) return 6504f;
        return (float) Math.max(1800.0, Math.min(12000.0, cct));
    }

    /** The scene's colour temperature from the camera's white balance, or -1 when it cannot be known. */
    public static float cct(String cameraId, float[] neutral, float[] cameraToSrgb) {
        float[] rgb = colour(cameraId, neutral, cameraToSrgb);
        return rgb == null ? -1f : cct(rgb);
    }
}
