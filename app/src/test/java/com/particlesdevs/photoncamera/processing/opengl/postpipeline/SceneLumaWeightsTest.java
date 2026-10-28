package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * P370 (B3, review): PostPipeline.sceneLumaWeights - the Ultra HDR scene plane's luminance weights of white-balanced camera RGB
 * (ultrahdr/sceneluma wU) are Rec.709 of the render's linear sRGB, LUMA^T . intermediateToSRGB . sensorToProPhoto . diag(n)
 * (tools/check_ultrahdr.py --scene-luma renders the shader with its own numpy copy of this product). A white keeps its value
 * on the ISP route (the weights sum to 1).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class SceneLumaWeightsTest {
    private static final double[] LUMA = {0.2126, 0.7152, 0.0722};

    /** The product in double precision, independent of Converter.multiply. */
    private static double[] expected(float[] i2s, float[] s2p, float[] n) {
        final double[] w = new double[3];
        for (int c = 0; c < 3; c++) {
            double s = 0;
            for (int r = 0; r < 3; r++) {
                double m = 0;
                for (int k = 0; k < 3; k++) m += (double) i2s[r * 3 + k] * s2p[k * 3 + c];
                s += LUMA[r] * m;
            }
            w[c] = s * n[c];
        }
        return w;
    }

    private static float[] rowsNormalised(int... v) {
        final float[] m = new float[9];
        for (int r = 0; r < 3; r++) {
            final float s = v[r * 3] + v[r * 3 + 1] + v[r * 3 + 2];
            for (int c = 0; c < 3; c++) m[r * 3 + c] = v[r * 3 + c] / s;
        }
        return m;
    }

    @Test public void theIspRouteWeightsAreTheRendersLuminance() {
        final float[] n = {0.54785156f, 1f, 0.41796875f};                                // the X8U's daylight shot
        final float[] cct = rowsNormalised(200, -62, -10, -17, 172, -27, 8, -70, 191);    // CCT.matrix: the transform's rows
        final float[] s2p = {1f / n[0], 0, 0, 0, 1f, 0, 0, 0, 1f / n[2]};               // the ISP route's diag(1 / n)
        final float[] w = PostPipeline.sceneLumaWeights(cct, s2p, n);
        final double[] want = expected(cct, s2p, n);
        for (int c = 0; c < 3; c++) assertEquals("weight " + c, want[c], w[c], 1e-6);
        assertEquals("a white keeps its value", 1.0, w[0] + w[1] + w[2], 1e-6);
        // not Rec.709 on camera RGB: the X8U matrix moves the blue weight
        org.junit.Assert.assertTrue("blue weight " + w[2], Math.abs(w[2] - 0.0722) > 0.01);
    }

    @Test public void aGeneralMatrixIsTheProduct() {
        final float[] i2s = {1.6604910f, -0.5876411f, -0.0728499f, -0.1245505f, 1.1328999f, -0.0083494f, -0.0181508f, -0.1005789f, 1.1187297f};
        final float[] s2p = {0.71f, 0.21f, 0.05f, 0.18f, 0.86f, -0.04f, 0.02f, -0.11f, 1.31f};
        final float[] n = {0.47f, 1f, 0.62f};
        final float[] w = PostPipeline.sceneLumaWeights(i2s, s2p, n);
        final double[] want = expected(i2s, s2p, n);
        for (int c = 0; c < 3; c++) assertEquals("weight " + c, want[c], w[c], 1e-6);
    }
}
