package com.particlesdevs.photoncamera.processing.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import android.util.Rational;

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
 * P212g (W7 = M12): a mosaic shot whose result has no usable ISP matrix for its neutral (the vivo mosaic results' identity, or
 * P121's own neutral) is rendered with the camera's plain-session matrix at that neutral, its scene light from the table's AWB
 * labels; any other shot, a result whose ISP matrix fits the camera's neutral, and an empty table keep what they had. P212z
 * (review): a FULLRES shot only with scam_dev.txt "mosaic_ccm_table 1"; P366: every mosaic shot (the 12 MP ISZ modules too) by
 * default ("mosaic_ccm_table 2" - the owner's green X200U ISZ photos with the DNG matrices were the A/B P212z waited for).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ParametersPlainTableTest {
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> app;
    private MockedStatic<PreferenceKeys> prefsKeys;
    private CameraCharacteristics previous;
    private SharedPreferences prefs;
    /** scam_dev.txt "mosaic_ccm_table" of a test (null: no line, the default). */
    private static Float ccmTable;

    private static ColorSpaceTransform cst(int den, int... v) {
        final Rational[] r = new Rational[9];
        for (int i = 0; i < 9; i++) r[i] = new Rational(v[i], den);
        return new ColorSpaceTransform(r);
    }

    private static final ColorSpaceTransform IDENTITY = cst(1, 1, 0, 0, 0, 1, 0, 0, 0, 1);
    /** The X300 Ultra's plain ISP matrix (SCAMERA-debug.log (7).txt:1157). */
    private static final ColorSpaceTransform PLAIN = cst(128, 167, -37, -2, -19, 155, -7, 2, -59, 184);

    @Before public void setUp() {
        app = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        app.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(new com.particlesdevs.photoncamera.api.Settings());
        // SCAM on, the ISP branch's internal switch at its default; everything else as it is
        prefsKeys = mockStatic(PreferenceKeys.class, invocation -> {
            final String name = invocation.getMethod().getName();
            if (name.equals("isScamEnabled")) return true;
            if (name.equals("routeInternalValue")) return 1f;
            if (name.equals("scamDevNumber") && "mosaic_ccm_table".equals(invocation.getArgument(0)) && ccmTable != null) return ccmTable;
            return invocation.callRealMethod();
        });
        final CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)).thenReturn(21);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)).thenReturn((byte) 17);
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)).thenReturn(IDENTITY);
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)).thenReturn(IDENTITY);
        // OPPO camera 2's DNG matrices (SCAM TEST PHY110 dump), forward matrices that differ (no "wrong calibration" by default)
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(cst(10000, 6178, -1002, -970, -4729, 14118, 345, -861, 2593, 4441));
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)).thenReturn(cst(10000, 6892, 196, -3223, -7131, 18376, -1520, -884, 2258, 6427));
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)).thenReturn(cst(128, 56, 49, 18, 28, 92, 8, 2, 12, 91));
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)).thenReturn(cst(128, 57, 48, 18, 28, 92, 8, 2, 12, 91));
        when(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)).thenReturn(new float[]{8.67f});
        previous = CaptureController.mCameraCharacteristics;
        CaptureController.mCameraCharacteristics = c;
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("plain_colour_table_render_test", 0);
        prefs.edit().clear().commit();
        PlainColourMemoryAccess.useStore(prefs, "vivo/V2562/V2562:16/1");
    }

    @After public void tearDown() {
        ccmTable = null;
        CaptureController.mCameraCharacteristics = previous;
        PlainColourMemoryAccess.reset();
        prefsKeys.close();
        app.close();
    }

    private static CaptureResult result(ColorSpaceTransform transform, float r, float b) {
        final CaptureResult res = mock(CaptureResult.class);
        when(res.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)).thenReturn(new Rational[]{
                new Rational(Math.round(r * 1024), 1024), new Rational(1, 1), new Rational(Math.round(b * 1024), 1024)});
        when(res.get(CaptureResult.CONTROL_AWB_MODE)).thenReturn(CaptureRequest.CONTROL_AWB_MODE_AUTO);
        when(res.get(CaptureResult.COLOR_CORRECTION_GAINS)).thenReturn(new RggbChannelVector(1f / r, 1f, 1f, 1f / b));
        when(res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)).thenReturn(transform);
        return res;
    }

    /** The plain matrix with its rows summing to 1 (row-major). */
    private static float[] plainRows() {
        final Rational[] r = new Rational[9];
        PLAIN.copyElements(r, 0);
        final float[] m = new float[9];
        for (int row = 0; row < 3; row++) {
            final float sum = r[row * 3].floatValue() + r[row * 3 + 1].floatValue() + r[row * 3 + 2].floatValue();
            for (int c = 0; c < 3; c++) m[row * 3 + c] = r[row * 3 + c].floatValue() / sum;
        }
        return m;
    }

    private static void teach() {
        final float[] m = new float[9];
        final Rational[] r = new Rational[9];
        PLAIN.copyElements(r, 0);
        for (int i = 0; i < 9; i++) m[i] = r[i].floatValue();
        for (int i = 0; i < 4; i++) PlainColourMemory.offer("5", 0.4805f, 0.5967f, m, 6200, 10_000L + 1_000L * i);
    }

    /** A FULLRES mosaic shot (P212z: the table's default scope; ImageSaver.fullResRequested of a native series). */
    private static Parameters mosaic(boolean ownNeutral) {
        final Parameters p = iszMosaic(ownNeutral);
        p.fullResRequested = 8;
        return p;
    }

    /** P212z: a 12 MP ISZ module's mosaic shot (no FULLRES series). */
    private static Parameters iszMosaic(boolean ownNeutral) {
        final Parameters p = new Parameters();
        p.physicalID = 5;
        p.cameraID = "5";
        p.mosaicShot = true;
        p.ownNeutral = ownNeutral;
        return p;
    }

    /** M12: a vivo mosaic result (identity transform), the HAL's neutral kept: the plain-session matrix and its label's 6200 K. */
    @Test public void aMosaicShotTakesThePlainMatrix() {
        teach();
        final Parameters p = mosaic(false);
        p.ReCalcColor(false, result(IDENTITY, 0.4746f, 0.5986f));
        final float[] want = plainRows();
        for (int i = 0; i < 9; i++) assertEquals("element " + i, want[i], p.CCT.matrix[i], 2e-3f);
        assertTrue(p.mosaicMatrixSource, p.mosaicMatrixSource.contains("plain-session matrix of camera 5"));
        assertEquals(6200f, p.sceneCct, 1f);
        // the white balance is the division by the neutral (the ISP branch)
        assertEquals(1f / p.whitePoint[0], p.sensorToProPhoto[0], 1e-6f);
        assertEquals(0f, p.sensorToProPhoto[1], 0f);
    }

    /** P121's own neutral on a result whose ISP matrix was picked at the camera's neutral: the table's matrix at our neutral. */
    @Test public void ownNeutralTakesThePlainMatrixOverTheResults() {
        teach();
        final Parameters p = mosaic(true);
        p.customNeutral = new float[]{0.47f, 1f, 0.61f};
        p.ReCalcColor(true, result(cst(128, 185, -59, 2, -17, 173, -28, 2, -69, 194), 0.40f, 0.70f));
        assertEquals(plainRows()[0], p.CCT.matrix[0], 2e-3f);
        assertTrue(p.mosaicMatrixSource, p.mosaicMatrixSource.contains("plain-session"));
    }

    /** The HAL's neutral with a usable ISP matrix of its own (the X100 Ultra's mosaic keeps one): that matrix, not the table. */
    @Test public void theResultsOwnMatrixFitsTheHalsNeutral() {
        teach();
        final ColorSpaceTransform own = cst(128, 168, -38, -2, -20, 160, -12, 2, -60, 186);
        final Parameters p = mosaic(false);
        p.ReCalcColor(false, result(own, 0.4746f, 0.5986f));
        assertEquals(168f / 128f, p.CCT.matrix[0], 1e-2f);
        assertTrue(p.mosaicMatrixSource, p.mosaicMatrixSource.contains("ISP transform of the result"));
    }

    /** Not a mosaic shot, or an empty table: the DNG matrices as before (the same render with and without the table). */
    @Test public void otherShotsAndAnEmptyTableKeepTheDngMatrices() {
        final Parameters before = mosaic(false);
        before.ReCalcColor(false, result(IDENTITY, 0.4746f, 0.5986f));
        assertTrue(before.mosaicMatrixSource, before.mosaicMatrixSource.contains("DNG matrices") && before.mosaicMatrixSource.contains("no bin"));
        teach();
        final Parameters plain = new Parameters();
        plain.physicalID = 5;
        plain.cameraID = "5";
        plain.ReCalcColor(false, result(IDENTITY, 0.4746f, 0.5986f));
        assertEquals("", plain.mosaicMatrixSource);
        for (int i = 0; i < 9; i++) assertEquals(before.CCT.matrix[i], plain.CCT.matrix[i], 1e-6f);
        assertNotEquals(plainRows()[0], plain.CCT.matrix[0], 1e-3f);
    }

    /**
     * P366 (owner, 2026-10-27: «вот такие фото получаю с исз, исправь»): a 12 MP ISZ mosaic shot takes the table by default - P212z
     * kept the DNG matrices there until an owner A/B, and the owner's green X200U camera 4 ISZ photos (log-2026-10-27 L13851: "the DNG
     * matrices (not a FULLRES shot ...)") are that A/B, so the old default's expectation here was the bug; "mosaic_ccm_table 1" keeps
     * P212z's FULLRES-only scope (the same render as before the table), 0 takes it from FULLRES too.
     */
    @Test public void aTwelveMegapixelMosaicShotTakesTheTableByDefault() {
        final Parameters before = iszMosaic(true);
        before.customNeutral = new float[]{0.47f, 1f, 0.61f};
        before.ReCalcColor(true, result(IDENTITY, 0.40f, 0.70f));
        teach();
        assertTrue(PlainColourMemory.wanted(true));
        assertTrue("P366: the 12 MP ISZ modules by default", PlainColourMemory.wanted(false));
        final Parameters isz = iszMosaic(true);
        isz.customNeutral = new float[]{0.47f, 1f, 0.61f};
        isz.ReCalcColor(true, result(IDENTITY, 0.40f, 0.70f));
        assertEquals(plainRows()[0], isz.CCT.matrix[0], 2e-3f);
        assertTrue(isz.mosaicMatrixSource, isz.mosaicMatrixSource.contains("plain-session"));
        ccmTable = 1f;
        assertTrue(PlainColourMemory.wanted(true));
        assertFalse(PlainColourMemory.wanted(false));
        final Parameters fullResOnly = iszMosaic(true);
        fullResOnly.customNeutral = new float[]{0.47f, 1f, 0.61f};
        fullResOnly.ReCalcColor(true, result(IDENTITY, 0.40f, 0.70f));
        assertTrue(fullResOnly.mosaicMatrixSource, fullResOnly.mosaicMatrixSource.contains("DNG matrices")
                && fullResOnly.mosaicMatrixSource.contains("not a FULLRES shot"));
        for (int i = 0; i < 9; i++) assertEquals("element " + i, before.CCT.matrix[i], fullResOnly.CCT.matrix[i], 1e-6f);
        ccmTable = 0f;
        final Parameters off = mosaic(true);
        off.customNeutral = new float[]{0.47f, 1f, 0.61f};
        off.ReCalcColor(true, result(IDENTITY, 0.40f, 0.70f));
        assertTrue(off.mosaicMatrixSource, off.mosaicMatrixSource.contains("mosaic_ccm_table 0"));
        for (int i = 0; i < 9; i++) assertEquals("element " + i, before.CCT.matrix[i], off.CCT.matrix[i], 1e-6f);
    }

    /** The rule itself: a mosaic shot, no tuned matrix, and the result's matrix does not fit (own neutral, or not usable). */
    @Test public void whenTheTableApplies() {
        assertTrue(Parameters.plainTableApplies(true, false, true, true));
        assertTrue(Parameters.plainTableApplies(true, false, false, false));
        assertFalse(Parameters.plainTableApplies(true, false, false, true));
        assertFalse("the OPPO tuned matrix wins", Parameters.plainTableApplies(true, true, true, false));
        assertFalse("not a mosaic shot", Parameters.plainTableApplies(false, false, true, false));
    }
}
