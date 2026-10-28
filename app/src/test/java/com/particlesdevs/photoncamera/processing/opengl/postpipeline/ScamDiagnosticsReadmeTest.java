package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import android.util.Rational;

import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * P370 (B1): the diagnostic README names the colour chain that renders the photo - on the OPPO Find X8 Ultra's ISP route
 * sensorToProPhoto is the white balance alone, and README.txt read "no colour matrix" (the owner's SCAM-20261027-175141). Now it
 * prints the uploaded intermediateToSRGB, the camera matrix, their product with sensorToProPhoto, the route, the scene light and
 * the frame that supplied the colour (its timestamp, ISO, AWB label, transform and the camera's DNG tags as rationals).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ScamDiagnosticsReadmeTest {
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> app;
    private MockedStatic<PreferenceKeys> prefsKeys;
    private CameraCharacteristics previous;

    private static ColorSpaceTransform cst(int den, int... v) {
        final Rational[] r = new Rational[9];
        for (int i = 0; i < 9; i++) r[i] = new Rational(v[i], den);
        return new ColorSpaceTransform(r);
    }

    /** The X8U camera 4 transform of the shot (log of SCAM-20261027-175141), rows summing to 1, 1, 1.0078. */
    static final ColorSpaceTransform X8U_CST = cst(128, 200, -62, -10, -17, 172, -27, 8, -70, 191);

    @Before public void setUp() {
        app = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        app.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(new com.particlesdevs.photoncamera.api.Settings());
        prefsKeys = mockStatic(PreferenceKeys.class, invocation -> {
            final String name = invocation.getMethod().getName();
            if (name.equals("isScamEnabled")) return true;
            if (name.equals("routeInternalValue")) return 1f;
            return invocation.callRealMethod();
        });
        final CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)).thenReturn(21);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)).thenReturn((byte) 17);
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)).thenReturn(cst(128, 132, 0, 0, 0, 128, 0, 0, 0, 126));
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)).thenReturn(cst(128, 132, 0, 0, 0, 128, 0, 0, 0, 124));
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(cst(10000, 17027, -5047, -2338, -5533, 13832, 1700, -2337, 3591, 6995));
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)).thenReturn(cst(10000, 27817, -14754, -3421, -6597, 16746, -149, -1031, 2289, 6991));
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)).thenReturn(cst(128, 56, 49, 18, 28, 92, 8, 2, 12, 91));
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)).thenReturn(cst(128, 56, 49, 18, 28, 92, 8, 2, 12, 91));
        when(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)).thenReturn(new float[]{6.0f});
        previous = CaptureController.mCameraCharacteristics;
        CaptureController.mCameraCharacteristics = c;
    }

    @After public void tearDown() {
        CaptureController.mCameraCharacteristics = previous;
        prefsKeys.close();
        app.close();
    }

    static CaptureResult result(ColorSpaceTransform transform, float r, float b) {
        final CaptureResult res = mock(CaptureResult.class);
        when(res.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)).thenReturn(new Rational[]{
                new Rational(Math.round(r * 1024), 1024), new Rational(1, 1), new Rational(Math.round(b * 1024), 1024)});
        when(res.get(CaptureResult.CONTROL_AWB_MODE)).thenReturn(CaptureRequest.CONTROL_AWB_MODE_AUTO);
        when(res.get(CaptureResult.COLOR_CORRECTION_GAINS)).thenReturn(new RggbChannelVector(1f / r, 1f, 1f, 1f / b));
        when(res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)).thenReturn(transform);
        when(res.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(423792818000000L);
        when(res.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(597);
        return res;
    }

    private static float[] parse(String readme, String key) {
        for (String line : readme.split("\n")) {
            if (!line.startsWith(key + "=[")) continue;
            final String[] v = line.substring(key.length() + 2, line.indexOf(']')).split(",");
            final float[] out = new float[v.length];
            for (int i = 0; i < v.length; i++) out[i] = Float.parseFloat(v[i].trim());
            return out;
        }
        return null;
    }

    /** The X8U ISP route: the README's chain is N(CST) . diag(1 / n), the matrix of the plan's section 0 (M . n = 1). */
    @Test public void theIspRouteNamesTheMatrixThatRenders() {
        final Parameters p = new Parameters();
        p.physicalID = 4;
        p.cameraID = "4";
        p.ReCalcColor(false, result(X8U_CST, 0.54785156f, 0.41796875f));
        assertEquals("ISP_CST", p.colourSource);
        final String readme = ScamDiagnostics.readme(p, 597, 9999998L, 423793451406446L, 3.4935298E-4f, 5.384647E-7f, "scene", 13602550L,
                WhyTone.colourMatrixOf(p, null));
        final float[] chain = parse(readme, "cameraToLinearSRGB");
        assertNotNull(readme, chain);
        final float[] want = {2.852f, -0.484f, -0.187f, -0.242f, 1.344f, -0.505f, 0.113f, -0.543f, 3.542f};
        for (int i = 0; i < 9; i++) assertEquals("element " + i, want[i], chain[i], 2e-3f);
        final float[] n = p.whitePoint;
        for (int r = 0; r < 3; r++) assertEquals("row " + r + " maps the neutral to white", 1f,
                chain[r * 3] * n[0] + chain[r * 3 + 1] * n[1] + chain[r * 3 + 2] * n[2], 1e-4f);
        assertTrue(readme, readme.contains("\ncameraMatrix=["));
        assertTrue(readme, readme.contains("\ncolourSource=ISP_CST"));
        assertTrue(readme, readme.contains("colour frame: timestamp=423792818000000 ISO=597 AWBFrameControlCCT=none"));
        assertTrue(readme, readme.contains("colour frame CST=[1.5625, -0.484375, -0.078125"));
        assertTrue(readme, readme.contains("det="));
        assertTrue(readme, readme.contains("FM1 [7/16, 49/128, 9/64; 7/32, 23/32, 1/16; 1/64, 3/32, 91/128]"));
        assertTrue(readme, readme.contains("forward matrices: the sRGB -> XYZ placeholder"));
        assertTrue(readme, readme.contains("\nsensorToProPhoto=[1.8253"));
        assertTrue(readme, readme.contains("\nCamera2 noise slope=3.4935298E-4 offset=5.384647E-7"));
        // the accessor for new consumers is the same product before the look
        final float[] c2s = p.cameraToSrgb();
        for (int i = 0; i < 9; i++) assertEquals(chain[i], c2s[i], 1e-6f);
    }

    /** The uploaded intermediateToSRGB is the «Цвет» white matrix after CCT.matrix (WhyTone.colourMatrix). */
    @Test public void theUploadedMatrixCarriesTheLook() {
        final Parameters p = new Parameters();
        p.physicalID = 4;
        p.ReCalcColor(false, result(X8U_CST, 0.54785156f, 0.41796875f));
        final float[] white = {1.1f, -0.05f, -0.05f, 0f, 1f, 0f, -0.02f, -0.02f, 1.04f};
        final float[] up = WhyTone.colourMatrixOf(p, white);
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) {
                float s = 0f;
                for (int k = 0; k < 3; k++) s += white[r * 3 + k] * p.CCT.matrix[k * 3 + c];
                assertEquals(s, up[r * 3 + c], 1e-6f);
            }
        final String readme = ScamDiagnostics.readme(p, 597, 1L, 1L, 0f, 0f, "scene", null, up);
        final float[] printed = parse(readme, "intermediateToSRGB");
        for (int i = 0; i < 9; i++) assertEquals(up[i], printed[i], 1e-6f);
        assertTrue(readme, readme.contains("sceneCct=") && readme.contains(" source="));
        // without a matrix the README says so instead of failing
        assertTrue(ScamDiagnostics.readme(new Parameters(), 1, 1L, 1L, 0f, 0f, "s", null, null).contains("intermediateToSRGB=unknown"));
    }

    /** A camera DNG route names itself (identity transform, no table): DNG_FM, with the white of the forward matrices. */
    @Test public void theDngRouteNamesItself() {
        final Parameters p = new Parameters();
        p.physicalID = 4;
        when(CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2))
                .thenReturn(cst(128, 70, 40, 14, 30, 90, 8, 4, 20, 82));
        p.ReCalcColor(false, result(cst(1, 1, 0, 0, 0, 1, 0, 0, 0, 1), 0.54785156f, 0.41796875f));
        assertEquals("DNG_FM", p.colourSource);
        assertTrue(p.colourSummary(), p.colourSummary().startsWith("colour: DNG_FM"));
    }
}
