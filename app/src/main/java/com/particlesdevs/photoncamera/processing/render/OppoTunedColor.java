package com.particlesdevs.photoncamera.processing.render;

import android.hardware.camera2.CameraCharacteristics;
import android.os.Build;
import android.util.Rational;
import android.hardware.camera2.params.ColorSpaceTransform;

/**
 * OPPO Find X7 Ultra: the HAL derives SENSOR_FORWARD_MATRIX from ISP colour tuning whose CC14
 * matrices are zero/identity (com.qti.tuned.*.bin, fixed on rooted phones by a Magisk module), so the
 * reported matrix is just sRGB->XYZ and RAW renders washed out with a cyan cast. The calibrated
 * illuminant A / D65 matrices of the fixed tuning (ISP CCM: white-balanced sensor RGB -> linear sRGB)
 * are embedded here and turned into DNG forward matrices, so no root or module is needed.
 */
final class OppoTunedColor {
    private OppoTunedColor() {}

    // {focal length mm, CCM at illuminant A (2600-3400 K), CCM at D65}, per sensor.
    private static final Object[][] TABLE = {
        {8.67f, new float[]{1.606985f, -0.57837f, -0.028615f, -0.163947f, 1.200295f, -0.036348f, 0.060554f, -0.603968f, 1.543414f}, new float[]{1.643783f, -0.634866f, -0.008917f, -0.153337f, 1.369204f, -0.215867f, 0.040731f, -0.589429f, 1.548698f}}, // main
        {2.59f, new float[]{1.311452f, -0.575027f, 0.263575f, -0.061211f, 1.057326f, 0.003885f, 0.027159f, -0.844099f, 1.81694f}, new float[]{1.171037f, -0.145903f, -0.025134f, -0.160628f, 1.266854f, -0.106226f, -0.001309f, -0.803451f, 1.80476f}}, // wide
        {15.38f, new float[]{1.35f, -0.33f, -0.02f, -0.28f, 1.19f, 0.09f, -0.05f, -0.59f, 1.64f}, new float[]{1.52459f, -0.233887f, -0.290703f, -0.262018f, 1.610353f, -0.348335f, 0.020139f, -0.687166f, 1.667027f}}, // tele3
        {22.36f, new float[]{1.36f, -0.43f, 0.07f, -0.2f, 1.2f, 0.0f, -0.04f, -0.62f, 1.66f}, new float[]{1.511401f, -0.186812f, -0.324589f, -0.140752f, 1.523603f, -0.382851f, -0.014582f, -0.592466f, 1.607048f}}, // tele6
        {3.23f, new float[]{2.1141f, -0.9806f, -0.1335f, -0.3522f, 1.6273f, -0.2751f, -0.2545f, -0.5464f, 1.8009f}, new float[]{2.1708f, -0.9184f, -0.2524f, -0.1644f, 1.5752f, -0.4108f, -0.1522f, -0.6453f, 1.7975f}}, // front
    };
    // sRGB (D65) -> XYZ (D50, Bradford), row-major.
    private static final float[] SRGB_TO_XYZ_D50 = {
            0.4360747f, 0.3850649f, 0.1430804f,
            0.2225045f, 0.7168786f, 0.0606169f,
            0.0139322f, 0.0971045f, 0.7141733f};

    static boolean applies() {
        return "PHY110".equals(Build.MODEL);
    }

    /** DNG illuminant tag -> correlated colour temperature (K). */
    static float cct(int illuminant) {
        switch (illuminant) {
            case 17: case 3: return 2856f;
            case 24: return 3200f;
            case 15: return 3500f;
            case 2: case 14: return 4200f;
            case 18: return 4874f;
            case 13: return 5000f;
            case 23: return 5003f;
            case 1: case 4: case 9: case 20: return 5500f;
            case 10: case 12: case 21: return 6504f;
            case 19: return 6774f;
            case 11: case 22: return 7504f;
            default: return 6504f;
        }
    }

    private static Object[] sensor(CameraCharacteristics characteristics) {
        float[] focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (focal == null || focal.length == 0) return null;
        for (Object[] row : TABLE) if (Math.abs(((Float) row[0]) - focal[0]) < 0.05f) return row;
        return null;
    }

    /** Forward matrix (white-balanced sensor RGB -> XYZ D50) for the given DNG illuminant, or null. */
    static ColorSpaceTransform forwardMatrix(CameraCharacteristics characteristics, int illuminant) {
        Object[] row = sensor(characteristics);
        if (row == null) return null;
        float[] a = (float[]) row[1], d = (float[]) row[2];
        // Interpolate the CCM in 1/CCT between A (2856 K) and D65 (6504 K), clamped.
        float t = (1f / 2856f - 1f / cct(illuminant)) / (1f / 2856f - 1f / 6504f);
        t = Math.max(0f, Math.min(1f, t));
        float[] ccm = new float[9];
        for (int i = 0; i < 9; i++) ccm[i] = a[i] + (d[i] - a[i]) * t;
        Rational[] out = new Rational[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) {
                float v = 0f;
                for (int k = 0; k < 3; k++) v += SRGB_TO_XYZ_D50[r * 3 + k] * ccm[k * 3 + c];
                out[r * 3 + c] = new Rational(Math.round(v * 100000f), 100000);
            }
        return new ColorSpaceTransform(out);
    }
}
