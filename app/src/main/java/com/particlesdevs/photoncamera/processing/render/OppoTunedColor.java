package com.particlesdevs.photoncamera.processing.render;

import android.hardware.camera2.CameraCharacteristics;
import android.os.Build;

/**
 * OPPO Find X7 Ultra: the HAL derives SENSOR_FORWARD_MATRIX from ISP colour tuning whose CC14
 * matrices are zero/identity (com.qti.tuned.*.bin, fixed on rooted phones by a Magisk module), so the
 * reported matrix is just sRGB->XYZ and RAW renders washed out with a cyan cast. The calibrated
 * CC14 matrices of the fixed tuning (white-balanced sensor RGB -> linear sRGB) are embedded here per
 * sensor and colour temperature and turned into DNG forward matrices, so no root or module is needed.
 *
 * The tuning holds ~6 colour-temperature bands per lux-index group; the bright-scene group is used
 * (its lower groups only shrink the correction to hide low-light noise, which SCAMERA denoises itself).
 */
final class OppoTunedColor {
    private OppoTunedColor() {}

    // {focal length mm, nodes {CCT K, 3x3 CCM row-major}} per sensor; nodes ascend in CCT.
    private static final Object[][] TABLE = {
        // main (tuning lux group 250)
        {8.67f, new float[][]{
                {3000f, 1.606985f, -0.57837f, -0.028615f, -0.173947f, 1.210295f, -0.036348f, 0.045554f, -0.603968f, 1.558414f},
                {4050f, 1.596203f, -0.618557f, 0.022354f, -0.198034f, 1.351397f, -0.153363f, 0.03535f, -0.712416f, 1.677066f},
                {5200f, 1.59753f, -0.600736f, 0.003206f, -0.172655f, 1.380427f, -0.207772f, 0.038647f, -0.574189f, 1.535542f},
                {6300f, 1.643783f, -0.634866f, -0.008917f, -0.153337f, 1.369204f, -0.215867f, 0.040731f, -0.589429f, 1.548698f},
                {7500f, 1.643389f, -0.629449f, -0.01394f, -0.13487f, 1.350057f, -0.215187f, 0.04827f, -0.599342f, 1.551072f}}},
        // wide (tuning lux group 190)
        {2.59f, new float[][]{
                {3050f, 1.311452f, -0.575027f, 0.263575f, -0.061211f, 1.057326f, 0.003885f, 0.027159f, -0.844099f, 1.81694f},
                {4100f, 1.193814f, -0.261266f, 0.067452f, -0.11876f, 1.26905f, -0.15029f, 0.015646f, -0.902884f, 1.887238f},
                {5300f, 1.10746f, -0.178978f, 0.071518f, -0.129029f, 1.137246f, -0.008217f, -0.033333f, -0.780467f, 1.8138f},
                {6500f, 1.101037f, -0.145903f, 0.044866f, -0.090628f, 1.096854f, -0.006226f, -0.041309f, -0.793451f, 1.83476f},
                {7500f, 1.101037f, -0.145903f, 0.044866f, -0.090628f, 1.096854f, -0.006226f, -0.041309f, -0.793451f, 1.83476f}}},
        // tele3 (tuning lux group 240)
        {15.38f, new float[][]{
                {3025f, 1.35f, -0.33f, -0.02f, -0.28f, 1.19f, 0.09f, -0.05f, -0.59f, 1.64f},
                {4050f, 1.35f, -0.43f, 0.08f, -0.23f, 1.18f, 0.05f, 0.01f, -0.61f, 1.6f},
                {5000f, 1.52459f, -0.233887f, -0.290703f, -0.262018f, 1.610353f, -0.348335f, 0.020139f, -0.687166f, 1.667027f},
                {6100f, 1.52459f, -0.233887f, -0.290703f, -0.262018f, 1.610353f, -0.348335f, 0.020139f, -0.687166f, 1.667027f},
                {7500f, 1.52459f, -0.233887f, -0.290703f, -0.272018f, 1.600353f, -0.328335f, 0.020139f, -0.667166f, 1.647027f}}},
        // tele6 (tuning lux group 250)
        {22.36f, new float[][]{
                {2975f, 1.36f, -0.43f, 0.07f, -0.2f, 1.2f, 0.0f, -0.04f, -0.62f, 1.66f},
                {4065f, 1.4f, -0.5f, 0.1f, -0.15f, 1.24f, -0.09f, 0.04f, -0.68f, 1.64f},
                {5300f, 1.511401f, -0.186812f, -0.324589f, -0.140752f, 1.523603f, -0.382851f, -0.014582f, -0.592466f, 1.607048f},
                {6500f, 1.511401f, -0.186812f, -0.324589f, -0.140752f, 1.523603f, -0.382851f, -0.014582f, -0.592466f, 1.607048f},
                {7500f, 1.511401f, -0.186812f, -0.324589f, -0.140752f, 1.523603f, -0.382851f, -0.014582f, -0.592466f, 1.607048f}}},
        // front (tuning lux group 220)
        {3.23f, new float[][]{
                {3000f, 2.1141f, -0.9806f, -0.1335f, -0.3522f, 1.6273f, -0.2751f, -0.2545f, -0.5464f, 1.8009f},
                {3950f, 2.172f, -1.1314f, -0.0406f, -0.3614f, 1.6729f, -0.3115f, -0.2425f, -0.781f, 2.0235f},
                {5150f, 2.104f, -1.0285f, -0.0755f, -0.2898f, 1.8653f, -0.5755f, -0.152f, -0.7032f, 1.8552f},
                {6450f, 2.104f, -1.0285f, -0.0755f, -0.2898f, 1.8653f, -0.5755f, -0.152f, -0.7032f, 1.8552f},
                {8600f, 2.2168f, -0.9514f, -0.2654f, -0.144f, 1.556f, -0.412f, -0.1642f, -0.6449f, 1.8091f}}}
    };
    // sRGB (D65) -> XYZ (D50, Bradford), row-major.
    private static final float[] SRGB_TO_XYZ_D50 = {
            0.4360747f, 0.3850649f, 0.1430804f,
            0.2225045f, 0.7168786f, 0.0606169f,
            0.0139322f, 0.0971045f, 0.7141733f};

    static boolean applies() {
        return "PHY110".equals(Build.MODEL);
    }


    // Camera neutral ln(R/B) measured on a 6500 K white screen, per sensor focal length.
    private static final float[][] NEUTRAL_AT_6500 = {{8.67f, -0.696f}, {2.59f, -0.613f}, {15.38f, -0.551f}};
    // {ln(R/B) shift from the 6500 K neutral, mired}: the shift of a Planckian light seen by an sRGB-like sensor.
    private static final float[][] SHIFT_MIRED = {
            {-0.43f, 111f}, {-0.21f, 133f}, {0f, 154f}, {0.295f, 182f}, {0.729f, 222f}, {1.03f, 250f}, {1.41f, 286f}, {1.88f, 333f}};

    /** Colour temperature of the light from the as-shot neutral [R/G, 1, B/G] (the DNG estimate barely moves on this HAL). */
    static float estimateCct(CameraCharacteristics characteristics, float[] neutral) {
        float[] focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        return estimateCct(focal != null && focal.length > 0 ? focal[0] : 0f, neutral);
    }

    /** ln(R/B) of the neutral of a 6500 K light for the sensor of this focal length (-0.6 for one not measured). */
    static float anchor(float focal) {
        for (float[] row : NEUTRAL_AT_6500) if (Math.abs(row[0] - focal) < 0.05f) return row[1];
        return -0.6f;
    }

    static float estimateCct(float focal, float[] neutral) {
        float anchor = anchor(focal);
        float shift = (float) Math.log(neutral[0] / neutral[2]) - anchor;
        float[][] t = SHIFT_MIRED;
        float mired;
        if (shift <= t[0][0]) mired = t[0][1];
        else if (shift >= t[t.length - 1][0]) mired = t[t.length - 1][1];
        else {
            int i = 0;
            while (shift > t[i + 1][0]) i++;
            mired = t[i][1] + (t[i + 1][1] - t[i][1]) * (shift - t[i][0]) / (t[i + 1][0] - t[i][0]);
        }
        return 1e6f / mired;
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

    /** CCM at a colour temperature: piecewise linear in 1/CCT between the tuning's nodes, clamped at both ends. */
    private static float[] ccm(float[][] nodes, float cct) {
        if (cct <= nodes[0][0]) return java.util.Arrays.copyOfRange(nodes[0], 1, 10);
        int last = nodes.length - 1;
        if (cct >= nodes[last][0]) return java.util.Arrays.copyOfRange(nodes[last], 1, 10);
        for (int i = 0; i < last; i++) {
            if (cct <= nodes[i + 1][0]) {
                float lo = 1f / nodes[i][0], hi = 1f / nodes[i + 1][0];
                float t = (lo - 1f / cct) / (lo - hi);
                float[] out = new float[9];
                for (int k = 0; k < 9; k++) out[k] = nodes[i][k + 1] + (nodes[i + 1][k + 1] - nodes[i][k + 1]) * t;
                return out;
            }
        }
        return java.util.Arrays.copyOfRange(nodes[last], 1, 10);
    }

    /** Forward matrix (white-balanced sensor RGB -> XYZ D50, row-major) at a colour temperature, or null. */
    static float[] forwardAt(CameraCharacteristics characteristics, float cct) {
        float[] focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        return focal == null || focal.length == 0 ? null : forwardAt(focal[0], cct);
    }

    static float[] forwardAt(float focal, float cct) {
        Object[] row = null;
        for (Object[] r : TABLE) if (Math.abs(((Float) r[0]) - focal) < 0.05f) row = r;
        if (row == null) return null;
        float[][] nodes = nodesOf(row);
        float[] ccm = ccm(nodes, cct);
        float[] out = new float[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) {
                float v = 0f;
                for (int k = 0; k < 3; k++) v += SRGB_TO_XYZ_D50[r * 3 + k] * ccm[k * 3 + c];
                out[r * 3 + c] = v;
            }
        return out;
    }

    private static float[][] nodesOf(Object[] row) {
        return (float[][]) row[1];
    }
}
