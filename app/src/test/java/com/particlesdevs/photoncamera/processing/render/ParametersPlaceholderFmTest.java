package com.particlesdevs.photoncamera.processing.render;

import static com.particlesdevs.photoncamera.processing.render.ParametersSceneCctTest.IDENTITY;
import static com.particlesdevs.photoncamera.processing.render.ParametersSceneCctTest.X8U_CST;
import static com.particlesdevs.photoncamera.processing.render.ParametersSceneCctTest.cst;
import static com.particlesdevs.photoncamera.processing.render.ParametersSceneCctTest.result;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.ColorSpaceTransform;

import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.processing.color.PlainColourMemory;
import com.particlesdevs.photoncamera.processing.color.PlainColourMemoryAccess;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * P370 (A3): the X8U camera 4 (and camera 2, Pixel 7) DNG forward matrices are the sRGB -> XYZ placeholder: a shot that ends on
 * them renders camera RGB as sRGB (0.50 - 0.71 of the chroma). Now on every shot (P315 guarded mosaic shots only) the route
 * falls back, in order, to the result's transform when its shape is a colour matrix's (the gates aside), the plain-session table,
 * the frame's OPPO ISP matrix, the colour matrices alone, else the placeholder with a warning; cameras with real forward matrices
 * and the default ISP route keep their matrices. The DNG camera profile drops placeholder forward matrices.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ParametersPlaceholderFmTest {
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> app;
    private MockedStatic<PreferenceKeys> prefsKeys;
    private CameraCharacteristics previous;
    private com.particlesdevs.photoncamera.api.Settings settings;
    private static boolean scam = true;
    private static float ispCcm = 1f;
    private static final float[] N = {0.54785156f, 1f, 0.41796875f};

    @Before public void setUp() {
        scam = true;
        ispCcm = 1f;
        settings = new com.particlesdevs.photoncamera.api.Settings();
        settings.colorMethod = 1;
        app = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        app.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(settings);
        prefsKeys = mockStatic(PreferenceKeys.class, invocation -> {
            final String name = invocation.getMethod().getName();
            if (name.equals("isScamEnabled")) return scam;
            if (name.equals("routeInternalValue") && "isp_ccm".equals(invocation.getArgument(0))) return ispCcm;
            return invocation.callRealMethod();
        });
        previous = CaptureController.mCameraCharacteristics;
        CaptureController.mCameraCharacteristics = ParametersSceneCctTest.x8u();
        final SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences("placeholder_fm_test", 0);
        prefs.edit().clear().commit();
        PlainColourMemoryAccess.useStore(prefs, "OPPO/PKJ110/op5dd3l1:16/1");
    }

    @After public void tearDown() {
        CaptureController.mCameraCharacteristics = previous;
        PlainColourMemoryAccess.reset();
        prefsKeys.close();
        app.close();
    }

    private static Parameters shot(ColorSpaceTransform transform) {
        final Parameters p = new Parameters();
        p.physicalID = 4;
        p.cameraID = "4";
        p.ReCalcColor(false, result(transform, N));
        return p;
    }

    /** Camera RGB (white-balanced) -> linear sRGB of the render: CCT.matrix . sensorToProPhoto . diag(n), rows scaled to 1. */
    private static float[] afterWhiteBalance(Parameters p) {
        final float[] m = p.cameraToSrgb(), out = new float[9];
        for (int r = 0; r < 3; r++) {
            float s = 0f;
            for (int c = 0; c < 3; c++) s += m[r * 3 + c] * p.whitePoint[c];
            for (int c = 0; c < 3; c++) out[r * 3 + c] = m[r * 3 + c] * p.whitePoint[c] / s;
        }
        return out;
    }

    private static float maxOffDiagonal(float[] m) {
        float max = 0f;
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) if (r != c) max = Math.max(max, Math.abs(m[r * 3 + c]));
        return max;
    }

    private static final float[] TABLE = {1.62f, -0.51f, -0.11f, -0.19f, 1.36f, -0.17f, 0.04f, -0.57f, 1.53f};

    private static void teach() {
        for (int i = 0; i < 4; i++) PlainColourMemory.offer("4", N[0], N[2], TABLE, 5200, 10_000L + 1_000L * i);
    }

    /** An identity transform (no OPPO matrix) and a table bin: the table's matrix, not the placeholder. */
    @Test public void anIdentityTransformTakesTheTable() {
        teach();
        final Parameters p = shot(IDENTITY);
        assertEquals("PLAIN_TABLE", p.colourSource);
        assertTrue(p.colourSourceWhy, p.colourSourceWhy.contains("placeholder"));
        for (int i = 0; i < 9; i++) assertEquals("element " + i, TABLE[i], p.CCT.matrix[i], 2e-3f);
        assertEquals(5200f, p.sceneCct, 1f);
    }

    /** A full transform with isp_ccm 0 or SCAM off: the transform renders (the placeholder had |off-diagonal| about 0.01). */
    @Test public void aFullTransformRendersWithTheGatesOff() {
        final Parameters isp = shot(X8U_CST);
        assertEquals("ISP_CST", isp.colourSource);
        ispCcm = 0f;
        final Parameters gateOff = shot(X8U_CST);
        assertEquals("ISP_CST_PLACEHOLDER", gateOff.colourSource);
        assertTrue("off-diagonal " + maxOffDiagonal(afterWhiteBalance(gateOff)), maxOffDiagonal(afterWhiteBalance(gateOff)) > 0.1f);
        for (int i = 0; i < 9; i++) assertEquals(isp.CCT.matrix[i], gateOff.CCT.matrix[i], 0f);
        ispCcm = 1f;
        scam = false;
        final Parameters scamOff = shot(X8U_CST);
        assertEquals("ISP_CST_PLACEHOLDER", scamOff.colourSource);
        assertTrue(maxOffDiagonal(afterWhiteBalance(scamOff)) > 0.1f);
        // what the placeholder forward matrices alone gave: camera RGB as sRGB
        final float[] placeholder = {0.43898535f, 0.38411218f, 0.14110243f, 0.21875f, 0.71875f, 0.0625f, 0.01571238f, 0.09427429f, 0.7149133f};
        final float[] srgb = new float[9];
        Converter.multiply(Converter.sXYZtoSRGB, placeholder, srgb);
        assertTrue("the placeholder is about the identity", maxOffDiagonal(srgb) < 0.02f);
    }

    /** An identity transform, no table, no OPPO matrix, colour matrices off the locus: the placeholder, flagged. */
    @Test public void nothingBetterIsFlagged() {
        final Parameters p = shot(IDENTITY);
        assertEquals("IDENTITY_PLACEHOLDER", p.colourSource);
        assertTrue(p.colourSourceWhy, p.colourSourceWhy.contains("camera RGB taken as sRGB"));
        assertTrue(p.colourSummary(), p.colourSummary().contains("IDENTITY_PLACEHOLDER"));
    }

    /** Real colour matrices on the locus (a camera with placeholder FMs but good CMs): the colour matrices alone. */
    @Test public void goodColourMatricesRenderAlone() {
        final CameraCharacteristics c = CaptureController.mCameraCharacteristics;
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(cst(10000, 6178, -1002, -970, -4729, 14118, 345, -861, 2593, 4441));
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)).thenReturn(cst(10000, 6892, 196, -3223, -7131, 18376, -1520, -884, 2258, 6427));
        final Parameters p = new Parameters();
        p.physicalID = 4;
        p.ReCalcColor(false, result(IDENTITY, ParametersSceneCctTest.neutralAt(5000)));
        assertEquals("DNG_CM_ONLY", p.colourSource);
        // white stays white (channel ratios of s2p . n equal) and the matrix is no identity
        final float[] w = new float[3];
        final float[] m = p.cameraToSrgb();
        for (int r = 0; r < 3; r++) w[r] = m[r * 3] * p.whitePoint[0] + m[r * 3 + 1] * p.whitePoint[1] + m[r * 3 + 2] * p.whitePoint[2];
        assertEquals(1f, w[0] / w[1], 2e-3f);
        assertEquals(1f, w[2] / w[1], 2e-3f);
        assertTrue(maxOffDiagonal(afterWhiteBalance(p)) > 0.1f);
    }

    /** A camera with real forward matrices keeps its DNG route: a table bin of its light changes nothing. */
    @Test public void realForwardMatricesKeepTheirRoute() {
        final CameraCharacteristics c = ParametersSceneCctTest.phy110();
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)).thenReturn(cst(128, 70, 40, 14, 30, 90, 8, 4, 20, 82));
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)).thenReturn(cst(128, 72, 38, 14, 31, 89, 8, 5, 22, 80));
        CaptureController.mCameraCharacteristics = c;
        final Parameters before = shot(IDENTITY);
        teach();
        final Parameters p = shot(IDENTITY);
        assertEquals("DNG_FM", p.colourSource);
        assertEquals("", p.colourSourceWhy);
        for (int i = 0; i < 9; i++) {
            assertEquals(before.sensorToProPhoto[i], p.sensorToProPhoto[i], 0f);
            assertEquals(before.CCT.matrix[i], p.CCT.matrix[i], 0f);
        }
        assertTrue(p.dngForwardMatrices());
    }

    /** The DNG camera profile (P243 off): no ForwardMatrix tags for the placeholder beside real colour matrices. */
    @Test public void theDngProfileDropsPlaceholderForwardMatrices() {
        final Parameters p = shot(X8U_CST);
        assertFalse(p.dngForwardMatrices());
        assertTrue(Parameters.placeholderColorMatrix(new float[]{3.2404542f, -1.5371385f, -0.4985314f, -0.9692660f, 1.8760108f,
                0.0415560f, 0.0556434f, -0.2040259f, 1.0572252f}, 21));
        assertFalse(Parameters.placeholderColorMatrix(p.ColorMatrix1, 21));
        assertTrue(Parameters.placeholderColorMatrix(new float[9], 21));
    }
}
