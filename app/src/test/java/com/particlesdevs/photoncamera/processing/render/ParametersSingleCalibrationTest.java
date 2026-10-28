package com.particlesdevs.photoncamera.processing.render;

import static com.particlesdevs.photoncamera.processing.render.ParametersSceneCctTest.IDENTITY;
import static com.particlesdevs.photoncamera.processing.render.ParametersSceneCctTest.cst;
import static com.particlesdevs.photoncamera.processing.render.ParametersSceneCctTest.result;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.ColorSpaceTransform;

import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * P370 (A5): ReCalcColor never loses a shot. A HAL with one calibration (no *2 keys), an illuminant code Converter's table lacks,
 * a singular colour matrix or calibration, a transform whose row sums to about 0: the photo is rendered (the other calibration
 * stands in, the DNG SDK's kelvin, an unknown light, the white balance alone) and the reason is logged; the forward matrix 2
 * never becomes the identity.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ParametersSingleCalibrationTest {
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> app;
    private MockedStatic<PreferenceKeys> prefsKeys;
    private CameraCharacteristics previous;
    private com.particlesdevs.photoncamera.api.Settings settings;
    /** A real (non-placeholder) forward matrix, the PHY110 CMs. */
    private static final ColorSpaceTransform FM = cst(128, 70, 40, 14, 30, 90, 8, 4, 20, 82);

    @Before public void setUp() {
        settings = new com.particlesdevs.photoncamera.api.Settings();
        settings.colorMethod = 1;
        app = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        app.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(settings);
        prefsKeys = mockStatic(PreferenceKeys.class, invocation -> {
            final String name = invocation.getMethod().getName();
            if (name.equals("isScamEnabled")) return true;
            if (name.equals("routeInternalValue")) return 1f;
            return invocation.callRealMethod();
        });
        previous = CaptureController.mCameraCharacteristics;
        final CameraCharacteristics c = ParametersSceneCctTest.phy110();
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)).thenReturn(FM);
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)).thenReturn(cst(128, 72, 38, 14, 31, 89, 8, 5, 22, 80));
        CaptureController.mCameraCharacteristics = c;
    }

    @After public void tearDown() {
        CaptureController.mCameraCharacteristics = previous;
        prefsKeys.close();
        app.close();
    }

    private static Parameters shot(ColorSpaceTransform transform, float[] n) {
        final Parameters p = new Parameters();
        p.physicalID = 2;
        p.ReCalcColor(false, result(transform, n));
        return p;
    }

    private static float[] rowMajor(ColorSpaceTransform m) {
        final float[] out = new float[9];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) out[r * 3 + c] = m.getElement(c, r).floatValue();
        return out;
    }

    /** No *2 keys: the first calibration stands in; sensorToProPhoto = XYZ->ProPhoto . FM1 . D . CC1^-1, finite. */
    @Test public void oneCalibrationRendersWithIt() {
        final CameraCharacteristics c = CaptureController.mCameraCharacteristics;
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)).thenReturn(null);
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)).thenReturn(null);
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)).thenReturn(null);
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)).thenReturn(null);
        final Parameters p = shot(IDENTITY, ParametersSceneCctTest.neutralAt(6504));
        assertEquals("DNG_FM", p.colourSource);
        final float[] fm = rowMajor(FM);
        Converter.normalizeFM(fm);
        final float[] cc = rowMajor(cst(128, 127, 0, 0, 0, 128, 0, 0, 0, 130)), ccInv = new float[9];
        assertTrue(Converter.invert(cc, ccInv));
        final float[] ref = new float[3];
        for (int r = 0; r < 3; r++) ref[r] = ccInv[r * 3] * p.whitePoint[0] + ccInv[r * 3 + 1] * p.whitePoint[1] + ccInv[r * 3 + 2] * p.whitePoint[2];
        final float max = Math.max(ref[0], Math.max(ref[1], ref[2]));
        final float[] d = {max / ref[0], 0, 0, 0, max / ref[1], 0, 0, 0, max / ref[2]}, dcc = new float[9], c2x = new float[9], want = new float[9];
        Converter.multiply(d, ccInv, dcc);
        Converter.multiply(fm, dcc, c2x);
        Converter.multiply(Converter.sXYZtoProPhoto, c2x, want);
        for (int i = 0; i < 9; i++) {
            assertTrue(Float.isFinite(p.sensorToProPhoto[i]));
            assertEquals("element " + i, want[i], p.sensorToProPhoto[i], 1e-5f);
        }
        // the copied forward matrix 2 is FM1, never the identity
        for (int i = 0; i < 9; i++) assertEquals(p.ForwardTransform1[i], p.ForwardTransform2[i], 0f);
        assertTrue("one calibration: its own light", p.sceneCct > 0f);
    }

    /** Codes Converter's table lacks (3 tungsten, 10 cloudy, 24 studio tungsten) do not throw; 3 / 10 read as 17 / 21 do. */
    @Test public void unknownIlluminantCodesRender() {
        final float[] n = ParametersSceneCctTest.neutralAt(4200);
        final Parameters standard = shot(IDENTITY, n);
        final CameraCharacteristics c = CaptureController.mCameraCharacteristics;
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)).thenReturn(10);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)).thenReturn((byte) 3);
        final Parameters other = shot(IDENTITY, n);
        assertEquals(standard.sceneCct, other.sceneCct, 10f);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)).thenReturn((byte) 24);
        final Parameters studio = shot(IDENTITY, n);
        assertTrue(studio.sceneCct > 0f);
        // the former estimate threw for these codes; with dng_white_spec 0 it now takes the DNG SDK's kelvin too
        prefsKeys.close();
        prefsKeys = mockStatic(PreferenceKeys.class, invocation -> {
            final String name = invocation.getMethod().getName();
            if (name.equals("isScamEnabled")) return true;
            if (name.equals("routeInternalValue")) return 1f;
            if (name.equals("scamDevSwitch") && "dng_white_spec".equals(invocation.getArgument(0))) return false;
            return invocation.callRealMethod();
        });
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)).thenReturn((byte) 3);
        final Parameters legacy = shot(IDENTITY, n);
        assertTrue(legacy.sceneCct > 0f);
        assertEquals(6500, Converter.illuminantTemperature(10));
        assertEquals(2850, Converter.illuminantTemperature(3));
        assertEquals(2856, Converter.illuminantTemperature(17));
    }

    /** A singular colour matrix: the daylight calibration, an unknown light and a reason - not an exception (the shot was lost). */
    @Test public void aSingularColourMatrixIsAReasonNotACrash() {
        final CameraCharacteristics c = CaptureController.mCameraCharacteristics;
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(cst(1, 1, 1, 1, 1, 1, 1, 1, 1, 1));
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)).thenReturn(cst(1, 1, 1, 1, 1, 1, 1, 1, 1, 1));
        final Parameters p = shot(IDENTITY, new float[]{0.5f, 1f, 0.6f});
        assertEquals(0f, p.sceneCct, 0f);
        assertTrue(p.sceneCctSource, p.sceneCctSource.contains("white estimate failed"));
        for (float v : p.sensorToProPhoto) assertTrue(Float.isFinite(v));
        // no colour matrix at all
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(null);
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)).thenReturn(null);
        final Parameters none = shot(IDENTITY, new float[]{0.5f, 1f, 0.6f});
        assertTrue(none.sceneCctSource, none.sceneCctSource.contains("no colour matrix"));
        // a singular calibration: the chain fails, the white balance alone renders (the identity transform is no matrix)
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(cst(10000, 6178, -1002, -970, -4729, 14118, 345, -861, 2593, 4441));
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)).thenReturn(cst(1, 0, 0, 0, 0, 1, 0, 0, 0, 1));
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)).thenReturn(cst(1, 0, 0, 0, 0, 1, 0, 0, 0, 1));
        final Parameters singular = shot(IDENTITY, new float[]{0.5f, 1f, 0.6f});
        assertEquals("IDENTITY_FALLBACK", singular.colourSource);
        assertEquals(1f / singular.whitePoint[0], singular.sensorToProPhoto[0], 1e-6f);
        assertEquals(1f, singular.CCT.matrix[0], 0f);
        // ... and a full transform renders it instead
        final Parameters isp = shot(cst(128, 200, -62, -10, -17, 172, -27, 8, -70, 191), new float[]{0.5f, 1f, 0.6f});
        assertEquals("ISP_CST", isp.colourSource);
    }

    /** «Источник цвета» «Захват» (2) with a transform whose row sums to 0.01: refused (it gave inf), the DNG route renders. */
    @Test public void aTransformRowSummingToZeroIsRefused() {
        settings.colorMethod = 2;
        prefsKeys.close();
        prefsKeys = mockStatic(PreferenceKeys.class, invocation -> {
            final String name = invocation.getMethod().getName();
            if (name.equals("isScamEnabled")) return true;
            if (name.equals("routeInternalValue")) return 0f; // isp_ccm 0: only the legacy branch could take it
            return invocation.callRealMethod();
        });
        final ColorSpaceTransform bad = cst(100, 150, -60, -89, -20, 140, -20, 5, -60, 155); // the first row sums to 0.01
        final Parameters p = shot(bad, new float[]{0.5f, 1f, 0.6f});
        assertEquals("DNG_FM", p.colourSource);
        for (float v : p.CCT.matrix) assertTrue(Float.isFinite(v));
        for (float v : p.sensorToProPhoto) assertTrue(Float.isFinite(v));
        // a good transform is still taken by «Захват»
        final Parameters good = shot(cst(128, 200, -62, -10, -17, 172, -27, 8, -70, 191), new float[]{0.5f, 1f, 0.6f});
        assertEquals("LEGACY_CAPTURED", good.colourSource);
        assertNotEquals(1f, good.CCT.matrix[0], 0.1f);
        // the row normalisation itself keeps such a row instead of dividing by it
        final float[] m = {1.5f, -0.49f, -1.0f, 0.3f, 0.4f, 0.3f, 0f, 0f, 1f};
        assertFalse(Parameters.normalize(m));
        assertEquals(1.5f, m[0], 0f);
        assertEquals(0.3f, m[3], 1e-7f);
    }

    /** The neutral is read with green 1 (a HAL's scale of 2 renders as the same white point). */
    @Test public void theNeutralIsReadWithGreenOne() {
        final android.hardware.camera2.CaptureResult res = result(IDENTITY, new float[]{0.5f, 1f, 0.6f});
        when(res.get(android.hardware.camera2.CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)).thenReturn(new android.util.Rational[]{
                new android.util.Rational(1, 1), new android.util.Rational(2, 1), new android.util.Rational(6, 5)});
        final Parameters p = new Parameters();
        p.ReCalcColor(false, res);
        assertEquals(0.5f, p.whitePoint[0], 1e-6f);
        assertEquals(1f, p.whitePoint[1], 0f);
        assertEquals(0.6f, p.whitePoint[2], 1e-6f);
    }
}
