package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import com.particlesdevs.photoncamera.processing.color.ColourLook;
import com.particlesdevs.photoncamera.processing.color.PhotoMatrix;
import com.particlesdevs.photoncamera.processing.color.PlainColourMemory;
import com.particlesdevs.photoncamera.processing.color.PlainColourMemoryAccess;
import com.particlesdevs.photoncamera.processing.render.OppoTunedColor;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;

/**
 * P370 (A2, B4): the RAW viewfinder renders with the photo's matrix (PhotoMatrix: the rules Parameters renders a frame with). The
 * OPPO Find X7 Ultra's identity transform showed no colour matrix on every module while the photo took the tuned one; an X200
 * Ultra ISZ lease showed the identity while the photo took the plain-session table. The look's scene light (keep warm / cool) and
 * the tuned matrix's AgX saturation follow the frame's matrix as the photo's do.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ViewfinderColourTest {
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> app;
    private MockedStatic<PreferenceKeys> prefsKeys;
    private CameraCharacteristics previous;

    private static ColorSpaceTransform cst(int den, int... v) {
        final Rational[] r = new Rational[9];
        for (int i = 0; i < 9; i++) r[i] = new Rational(v[i], den);
        return new ColorSpaceTransform(r);
    }

    private static final float[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    private static final float[] OPLUS = {1.71f, -0.58f, -0.10f, -0.22f, 1.43f, -0.18f, 0.03f, -0.61f, 1.62f};
    private static final float[] TABLE = {1.62f, -0.51f, -0.11f, -0.19f, 1.36f, -0.17f, 0.04f, -0.57f, 1.53f};

    @Before public void setUp() {
        final com.particlesdevs.photoncamera.api.Settings settings = new com.particlesdevs.photoncamera.api.Settings();
        settings.colorMethod = 1;
        app = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        app.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(settings);
        prefsKeys = mockStatic(PreferenceKeys.class, invocation -> {
            final String name = invocation.getMethod().getName();
            if (name.equals("isScamEnabled")) return true;
            if (name.equals("routeInternalValue")) return 1f;
            if (name.equals("hybridValue")) return invocation.getArgument(1);
            return invocation.callRealMethod();
        });
        previous = CaptureController.mCameraCharacteristics;
        final SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences("viewfinder_colour_test", 0);
        prefs.edit().clear().commit();
        PlainColourMemoryAccess.useStore(prefs, "OPPO/PHY110/OP5D0DL1:16/1");
    }

    @After public void tearDown() {
        CaptureController.mCameraCharacteristics = previous;
        ShadowBuild.reset();
        PlainColourMemoryAccess.reset();
        prefsKeys.close();
        app.close();
    }

    private static CameraCharacteristics phy110Main() {
        final CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)).thenReturn(21);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)).thenReturn((byte) 17);
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)).thenReturn(cst(128, 127, 0, 0, 0, 128, 0, 0, 0, 130));
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)).thenReturn(cst(128, 127, 0, 0, 0, 128, 0, 0, 0, 128));
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(cst(10000, 6178, -1002, -970, -4729, 14118, 345, -861, 2593, 4441));
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)).thenReturn(cst(10000, 6892, 196, -3223, -7131, 18376, -1520, -884, 2258, 6427));
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)).thenReturn(cst(128, 56, 49, 18, 28, 92, 8, 2, 12, 91));
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)).thenReturn(cst(128, 57, 48, 18, 28, 92, 8, 2, 12, 91));
        when(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)).thenReturn(new float[]{8.67f});
        return c;
    }

    private static CaptureResult result(ColorSpaceTransform transform, float[] n) {
        final CaptureResult res = mock(CaptureResult.class);
        when(res.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)).thenReturn(new Rational[]{
                new Rational(Math.round(n[0] * 65536), 65536), new Rational(1, 1), new Rational(Math.round(n[2] * 65536), 65536)});
        when(res.get(CaptureResult.CONTROL_AWB_MODE)).thenReturn(CaptureRequest.CONTROL_AWB_MODE_AUTO);
        when(res.get(CaptureResult.COLOR_CORRECTION_GAINS)).thenReturn(new RggbChannelVector(1f / n[0], 1f, 1f, 1f / n[2]));
        when(res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)).thenReturn(transform);
        return res;
    }

    /** The X7 Ultra (PHY110) main camera, identity transform: the tuned matrix, = the photo's CCT . s2p . diag(n) up to its scale. */
    @Test public void theX7UltraShowsTheTunedMatrix() {
        ShadowBuild.setModel("PHY110");
        final CameraCharacteristics c = phy110Main();
        CaptureController.mCameraCharacteristics = c;
        final float[] n = {0.52f, 1f, 0.66f};
        final float[] tuned = OppoTunedColor.previewMatrix(c, n, "2");
        assertNotNull(tuned);
        final PhotoMatrix.Pick pick = PhotoMatrix.viewfinder(IDENTITY, tuned, OppoTunedColor.previewCct(c, n, "2"), false, null, OPLUS);
        assertTrue(pick.tuned);
        assertArrayEquals(tuned, pick.rowMajor, 0f);
        final Parameters p = new Parameters();
        p.physicalID = 2;
        p.ReCalcColor(false, result(cst(1, 1, 0, 0, 0, 1, 0, 0, 0, 1), n));
        assertEquals("OPPO_TUNED", p.colourSource);
        final float[] photo = p.cameraToSrgb();
        final float[] wb = new float[9];
        float k = 0f;
        for (int r = 0; r < 3; r++) for (int col = 0; col < 3; col++) wb[r * 3 + col] = photo[r * 3 + col] * p.whitePoint[col];
        for (int col = 0; col < 3; col++) k += wb[3 + col];
        for (int i = 0; i < 9; i++) assertEquals("element " + i, pick.rowMajor[i], wb[i] / k, 1e-4f);
        // the look reads its light and the photo's AgX saturation (1.1)
        assertEquals(p.sceneCct, PhotoMatrix.sceneCct(pick, "2", n, null, 0), 1f);
        assertEquals(1.1f, WhyTone.ccmSatComp(pick.tuned), 0f);
        assertEquals(1f, WhyTone.ccmSatComp(false), 0f);
    }

    /** A plain Bayer stream with an OPPO ISP matrix: that matrix (the photo's OPLUS_CCM), even with a table bin of the light. */
    @Test public void aPlainStreamTakesTheOppoMatrix() {
        for (int i = 0; i < 4; i++) PlainColourMemory.offer("2", 0.52f, 0.66f, TABLE, 5000, 10_000L + 1_000L * i);
        final PlainColourMemory.Lookup table = PlainColourMemory.lookup("2", new float[]{0.52f, 1f, 0.66f});
        assertNotNull(table);
        final PhotoMatrix.Pick plain = PhotoMatrix.viewfinder(IDENTITY, null, Float.NaN, false, table, OPLUS);
        assertArrayEquals(PhotoMatrix.rowsNormalised(OPLUS), plain.rowMajor, 0f);
        // a mosaic stream (ISZ lease, picked mode, FULLRES): the table, its label the light
        final PhotoMatrix.Pick mosaic = PhotoMatrix.viewfinder(IDENTITY, null, Float.NaN, true, table, OPLUS);
        assertArrayEquals(table.ccm, mosaic.rowMajor, 0f);
        assertEquals(5000f, PhotoMatrix.sceneCct(mosaic, "2", new float[]{0.52f, 1f, 0.66f}, null, 0), 1f);
        // a mosaic stream without a bin: the OPPO matrix; neither: the identity (null)
        assertArrayEquals(PhotoMatrix.rowsNormalised(OPLUS), PhotoMatrix.viewfinder(IDENTITY, null, Float.NaN, true, null, OPLUS).rowMajor, 0f);
        assertNull(PhotoMatrix.viewfinder(IDENTITY, null, Float.NaN, true, null, null).rowMajor);
        assertNull(PhotoMatrix.viewfinder(IDENTITY, null, Float.NaN, false, table, null).rowMajor);
    }

    /** A result transform whose rows sum to 1.008 (the X8U's): the viewfinder rows sum to 1, as the photo's CCT.matrix. */
    @Test public void theResultsTransformHasThePhotosRows() {
        final float[] x8u = {200 / 128f, -62 / 128f, -10 / 128f, -17 / 128f, 172 / 128f, -27 / 128f, 8 / 128f, -70 / 128f, 191 / 128f};
        final PhotoMatrix.Pick pick = PhotoMatrix.viewfinder(x8u, null, Float.NaN, false, null, OPLUS);
        assertTrue(pick.reported);
        for (int r = 0; r < 3; r++) assertEquals(1f, pick.rowMajor[r * 3] + pick.rowMajor[r * 3 + 1] + pick.rowMajor[r * 3 + 2], 1e-6f);
        // apart from the row sums nothing changes (SCAM off, any stream): the same matrix up to each row's scale
        for (int r = 0; r < 3; r++) {
            final float s = x8u[r * 3] + x8u[r * 3 + 1] + x8u[r * 3 + 2];
            for (int col = 0; col < 3; col++) assertEquals(x8u[r * 3 + col] / s, pick.rowMajor[r * 3 + col], 1e-7f);
        }
        assertEquals(1f, pick.rowMajor[0] / x8u[0] * (200 + -62 + -10) / 128f, 1e-6f);
        // the photo's normalisation is the same rule (Parameters.normalize = PhotoMatrix.normaliseRows)
        final float[] m = {1.5f, -0.49f, -1.0f, 0.3f, 0.4f, 0.3f, 0f, 0f, 1f};
        assertFalse(PhotoMatrix.normaliseRows(m));
        assertEquals(1.5f, m[0], 0f);
    }

    /** B4: keep warm at 50 and a 2900 K frame: the look's white matrix is whiteMatrix(that light), not whiteMatrix(0). */
    @Test public void theLookKeepsTheFramesLight() {
        final CameraCharacteristics c = phy110Main();
        final com.particlesdevs.photoncamera.gallery.dng.DngColor dng = PhotoMatrix.dngWhite(c);
        assertNotNull(dng);
        final float[] n = com.particlesdevs.photoncamera.processing.render.ParametersSceneCctTest.neutralAt(2900);
        final PhotoMatrix.Pick pick = PhotoMatrix.viewfinder(IDENTITY, null, Float.NaN, false, null, OPLUS);
        final float cct = PhotoMatrix.sceneCct(pick, "2", n, dng, 0);
        assertEquals(2900f, cct, 150f);
        final ColourLook look = new ColourLook();
        look.keepWarm = 50f;
        PhotoMatrix.viewfinderSceneCct = cct;
        final float[] shown = look.whiteMatrix(PhotoMatrix.viewfinderSceneCct), want = look.whiteMatrix(cct);
        assertNotNull(want);
        assertArrayEquals(want, shown, 1e-5f);
        assertNull("the former viewfinder: no scene light, no matrix", look.whiteMatrix(0f));
        // an unknown light and no AWB label: 0; a label stands in
        final float[] far = {0.9f, 1f, 0.2f};
        assertEquals(0f, PhotoMatrix.sceneCct(PhotoMatrix.viewfinder(IDENTITY, null, Float.NaN, false, null, null), "2", far, null, 0), 0f);
        assertEquals(4100f, PhotoMatrix.sceneCct(PhotoMatrix.viewfinder(IDENTITY, null, Float.NaN, false, null, null), "2", far, null, 4100), 0f);
        PhotoMatrix.viewfinderSceneCct = 0f;
    }
}
