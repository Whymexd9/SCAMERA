package com.particlesdevs.photoncamera.processing.render;

import static org.junit.Assert.assertEquals;
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
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * P370 (A1): the scene light (sceneCct: the DNG ColorMatrix1's As-Shot temperature, keep warm / cool) and the FM / CC weight from
 * the DNG SDK's white estimate. The ISP route's matrices are untouched (the factor only lerps equal forward matrices and a
 * diagonal calibration there); scam_dev "dng_white_spec 0" is the former estimate; a white far off the locus is unknown, and
 * takes the frame's AWB label when it has one (B7).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ParametersSceneCctTest {
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> app;
    private MockedStatic<PreferenceKeys> prefsKeys;
    private CameraCharacteristics previous;
    private com.particlesdevs.photoncamera.api.Settings settings;
    private static boolean whiteSpec = true;

    static ColorSpaceTransform cst(int den, int... v) {
        final Rational[] r = new Rational[9];
        for (int i = 0; i < 9; i++) r[i] = new Rational(v[i], den);
        return new ColorSpaceTransform(r);
    }

    static final ColorSpaceTransform IDENTITY = cst(1, 1, 0, 0, 0, 1, 0, 0, 0, 1);
    static final ColorSpaceTransform X8U_CST = cst(128, 200, -62, -10, -17, 172, -27, 8, -70, 191);
    // PHY110 camera 2 (SCAM TEST dump): CM1 at D65, CM2 at A, CC diag; forward matrices that differ (the DNG route)
    static final double[] CM1 = {0.6178, -0.1002, -0.0970, -0.4729, 1.4118, 0.0345, -0.0861, 0.2593, 0.4441};
    static final double[] CM2 = {0.6892, 0.0196, -0.3223, -0.7131, 1.8376, -0.1520, -0.0884, 0.2258, 0.6427};
    static final double[] CC1 = {127 / 128.0, 0, 0, 0, 1, 0, 0, 0, 130 / 128.0};
    static final double[] CC2 = {127 / 128.0, 0, 0, 0, 1, 0, 0, 0, 1};

    @Before public void setUp() {
        whiteSpec = true;
        settings = new com.particlesdevs.photoncamera.api.Settings();
        settings.colorMethod = 1; // the default (default_prefs.xml)
        app = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        app.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(settings);
        prefsKeys = mockStatic(PreferenceKeys.class, invocation -> {
            final String name = invocation.getMethod().getName();
            if (name.equals("isScamEnabled")) return true;
            if (name.equals("routeInternalValue")) return 1f;
            if (name.equals("scamDevSwitch") && "dng_white_spec".equals(invocation.getArgument(0))) return whiteSpec;
            return invocation.callRealMethod();
        });
        previous = CaptureController.mCameraCharacteristics;
        CaptureController.mCameraCharacteristics = phy110();
    }

    @After public void tearDown() {
        CaptureController.mCameraCharacteristics = previous;
        prefsKeys.close();
        app.close();
    }

    static CameraCharacteristics phy110() {
        final CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)).thenReturn(21);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)).thenReturn((byte) 17);
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)).thenReturn(cst(128, 127, 0, 0, 0, 128, 0, 0, 0, 130));
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)).thenReturn(cst(128, 127, 0, 0, 0, 128, 0, 0, 0, 128));
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(cst(10000, 6178, -1002, -970, -4729, 14118, 345, -861, 2593, 4441));
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)).thenReturn(cst(10000, 6892, 196, -3223, -7131, 18376, -1520, -884, 2258, 6427));
        // real forward matrices that differ (ParametersSingleCalibrationTest's; the review: the former 56 49 18 / 57 48 18 pair was
        // the sRGB -> XYZ placeholder and its second missed that test by 0.0001)
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)).thenReturn(cst(128, 70, 40, 14, 30, 90, 8, 4, 20, 82));
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)).thenReturn(cst(128, 72, 38, 14, 31, 89, 8, 5, 22, 80));
        when(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)).thenReturn(new float[]{8.67f});
        return c;
    }

    static CaptureResult result(ColorSpaceTransform transform, float[] n) {
        final CaptureResult res = mock(CaptureResult.class);
        when(res.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)).thenReturn(new Rational[]{
                new Rational(Math.round(n[0] * 65536), 65536), new Rational(1, 1), new Rational(Math.round(n[2] * 65536), 65536)});
        when(res.get(CaptureResult.CONTROL_AWB_MODE)).thenReturn(CaptureRequest.CONTROL_AWB_MODE_AUTO);
        when(res.get(CaptureResult.COLOR_CORRECTION_GAINS)).thenReturn(new RggbChannelVector(1f / n[0], 1f, 1f, 1f / n[2]));
        when(res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)).thenReturn(transform);
        return res;
    }

    /** The camera neutral of a Planckian light (Kim et al. xy) through the raw matrices interpolated in 1/T, green 1. */
    public static float[] neutralAt(double t) {
        final double x = t < 4000 ? -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
                : -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390;
        final double y = t < 2222 ? -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                : t < 4000 ? -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
                : 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483;
        final double[] xyz = {x / y, 1, (1 - x - y) / y};
        final double w = Math.max(0, Math.min(1, (1 / t - 1 / 6504.0) / (1 / 2856.0 - 1 / 6504.0)));
        final double[] n = new double[3];
        for (int r = 0; r < 3; r++)
            for (int k = 0; k < 3; k++)
                n[r] += ((1 - w) * CC1[r * 4] * CM1[r * 3 + k] + w * CC2[r * 4] * CM2[r * 3 + k]) * xyz[k];
        return new float[]{(float) (n[0] / n[1]), 1f, (float) (n[2] / n[1])};
    }

    private Parameters shot(ColorSpaceTransform transform, float[] n) {
        final Parameters p = new Parameters();
        p.physicalID = 2;
        p.cameraID = "2";
        p.ReCalcColor(false, result(transform, n));
        return p;
    }

    /** The DNG route reads the light: 2856 / 4000 / 6504 K within 150 K (the row-normalised estimate: 5068 / 6455 / 6503 K). */
    @Test public void theDngRouteReadsTheLight() {
        for (double t : new double[]{2856, 4000, 6504}) {
            final Parameters p = shot(IDENTITY, neutralAt(t));
            assertEquals("DNG_FM", p.colourSource);
            assertEquals("light " + t + " K", t, p.sceneCct, 150);
            assertEquals("the DNG estimate", p.sceneCctSource);
            assertTrue("Duv " + p.sceneDuv, Math.abs(p.sceneDuv) < 0.025f);
        }
        // the former estimate under tungsten (the row normalisation pulled it to the D65 calibration)
        whiteSpec = false;
        final Parameters before = shot(IDENTITY, neutralAt(2856));
        assertTrue("the former estimate " + before.sceneCct, before.sceneCct > 4500f);
    }

    /** scam_dev "dng_white_spec 0" gives the former estimate exactly: Converter's factor on row-normalised matrices, the mired lerp. */
    @Test public void theSwitchGivesTheFormerEstimate() {
        whiteSpec = false;
        final float[] n = neutralAt(3600);
        final Parameters p = shot(IDENTITY, n);
        final float[] cm1 = new float[9], cm2 = new float[9], cc1 = new float[9], cc2 = new float[9];
        for (int i = 0; i < 9; i++) {
            cm1[i] = p.ColorMatrix1[i];
            cm2[i] = p.ColorMatrix2[i];
            cc1[i] = p.calibrationTransform1[i];
            cc2[i] = p.calibrationTransform2[i];
        }
        Converter.normalizeFM(cm1);
        Converter.normalizeFM(cm2);
        final double f = Converter.findDngInterpolationFactor(21, 17, cc1, cc2, cm1, cm2, p.whitePoint);
        final float mired1 = 1e6f / OppoTunedColor.cct(21), mired2 = 1e6f / OppoTunedColor.cct(17);
        assertEquals(1e6f / (mired1 + (float) f * (mired2 - mired1)), p.sceneCct, 0f);
        assertTrue(p.sceneCctSource, p.sceneCctSource.contains("dng_white_spec 0"));
    }

    /** The ISP route keeps its matrices bit for bit (HEAD's golden arrays): diag(1 / n) and the row-normalised transform. */
    @Test public void theIspRouteIsUnchanged() {
        CaptureController.mCameraCharacteristics = x8u();
        final Parameters p = shot(X8U_CST, new float[]{0.54785156f, 1f, 0.41796875f});
        assertEquals("ISP_CST", p.colourSource);
        final float[] cct = {1.5625f, -0.484375f, -0.078125f, -0.1328125f, 1.34375f, -0.2109375f, 0.062015504f, -0.5426357f, 1.4806201f};
        final float[] s2p = {1.8253119f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 2.3925233f};
        for (int i = 0; i < 9; i++) {
            assertEquals("CCT " + i, cct[i], p.CCT.matrix[i], 0f);
            assertEquals("s2p " + i, s2p[i], p.sensorToProPhoto[i], 0f);
        }
        // the row-normalised matrices of the old log (x8u(): not the phone's raw ones, P370 C1) put the neutral off the locus:
        // the light is unknown, not the former 5373 K
        assertEquals(0f, p.sceneCct, 0f);
        assertTrue(p.sceneCctSource, p.sceneCctSource.startsWith("unknown"));
        assertTrue("Duv " + p.sceneDuv, Math.abs(p.sceneDuv) > 0.025f);
        // scam_dev dng_white_spec 0 (the former estimate) renders the same golden arrays (61fbd24d's, the review's probe)
        whiteSpec = false;
        final Parameters before = shot(X8U_CST, new float[]{0.54785156f, 1f, 0.41796875f});
        for (int i = 0; i < 9; i++) {
            assertEquals("CCT " + i, cct[i], before.CCT.matrix[i], 0f);
            assertEquals("s2p " + i, s2p[i], before.sensorToProPhoto[i], 0f);
        }
    }

    /**
     * P370 (B7): the light the estimate cannot tell (the X8U's row-normalised, off-locus matrices) takes the frame's AWB label; the matrices stay
     * bit for bit, the summary names the label; the HAL's initial 3745 K and a label outside 1500-15000 K are no light.
     */
    @Test public void anUnknownLightTakesTheAwbLabel() {
        CaptureController.mCameraCharacteristics = x8u();
        final CaptureResult res = result(X8U_CST, new float[]{0.54785156f, 1f, 0.41796875f});
        when(res.get(Parameters.AWB_CCT_LABEL)).thenReturn(new int[]{4870});
        final Parameters p = new Parameters();
        p.physicalID = 2;
        p.cameraID = "2";
        p.ReCalcColor(false, res);
        assertEquals("ISP_CST", p.colourSource);
        assertEquals(4870f, p.sceneCct, 0f);
        assertTrue(p.sceneCctSource, p.sceneCctSource.startsWith("the AWB label") && p.sceneCctSource.contains("unknown"));
        assertTrue(p.colourSummary(), p.colourSummary().contains("AWB label 4870 K"));
        final Parameters without = shot(X8U_CST, new float[]{0.54785156f, 1f, 0.41796875f});
        for (int i = 0; i < 9; i++) {
            assertEquals(without.CCT.matrix[i], p.CCT.matrix[i], 0f);
            assertEquals(without.sensorToProPhoto[i], p.sensorToProPhoto[i], 0f);
        }
        for (int bad : new int[]{3745, 1200, 20000}) {
            when(res.get(Parameters.AWB_CCT_LABEL)).thenReturn(new int[]{bad});
            final Parameters q = new Parameters();
            q.physicalID = 2;
            q.ReCalcColor(false, res);
            assertEquals("label " + bad, 0f, q.sceneCct, 0f);
        }
        // a light the estimate reads keeps the estimate, the label is only logged
        CaptureController.mCameraCharacteristics = phy110();
        final CaptureResult dng = result(IDENTITY, neutralAt(2856));
        when(dng.get(Parameters.AWB_CCT_LABEL)).thenReturn(new int[]{6000});
        final Parameters e = new Parameters();
        e.physicalID = 2;
        e.ReCalcColor(false, dng);
        assertEquals(2856f, e.sceneCct, 150f);
        assertEquals("the DNG estimate", e.sceneCctSource);
        assertTrue(e.colourSummary(), e.colourSummary().contains("AWB label 6000 K"));
    }

    /**
     * P370 (A1, review): the whole chain - a tungsten / daylight neutral through ReCalcColor to the DNG's profile (DngColour,
     * P243) read back the DNG SDK's way (DngColor, as Lightroom): the As-Shot temperature is the light, not the former row-normalised
     * estimate's (5068 K under tungsten).
     */
    @Test public void theDngAsShotIsTheEstimatedLight() {
        for (double t : new double[]{2856, 4000, 6504}) {
            final Parameters p = shot(IDENTITY, neutralAt(t));
            final com.particlesdevs.photoncamera.processing.color.DngColour.Profile profile =
                    com.particlesdevs.photoncamera.processing.color.DngColour.jpegProfile(p.sensorToProPhoto, p.CCT.matrix, p.whitePoint, p.sceneCct);
            org.junit.Assert.assertNotNull(profile);
            final double[] cm = new double[9];
            for (int i = 0; i < 9; i++) cm[i] = profile.colorMatrix1[i];
            final double[] n = {p.whitePoint[0], p.whitePoint[1], p.whitePoint[2]};
            final double asShot = com.particlesdevs.photoncamera.gallery.dng.DngColor.cctDuv(
                    com.particlesdevs.photoncamera.gallery.dng.DngColor.fromCamera(cm, n).xyForNeutral(n))[0];
            assertEquals("light " + t + " K", t, asShot, 150);
        }
    }

    /**
     * P370 (B7, review): a mosaic shot with the app's own neutral takes no AWB label - its ISP read the mosaic as Bayer or ran no 3A
     * (the X200U ISZ lease's label is the frozen one).
     */
    @Test public void aMosaicShotWithItsOwnNeutralTakesNoLabel() {
        CaptureController.mCameraCharacteristics = x8u();
        final CaptureResult res = result(X8U_CST, new float[]{0.54785156f, 1f, 0.41796875f});
        when(res.get(Parameters.AWB_CCT_LABEL)).thenReturn(new int[]{4870});
        final Parameters p = new Parameters();
        p.physicalID = 2;
        p.mosaicShot = true;
        p.ownNeutral = true;
        p.customNeutral = new float[]{0.54785156f, 1f, 0.41796875f};
        p.ReCalcColor(true, res);
        org.junit.Assert.assertNotEquals(4870f, p.sceneCct, 0f);
        assertTrue(p.colourSummary(), p.colourSummary().contains("AWB label 4870 K"));
    }

    /**
     * P370 (review): the Pixel 7's colour matrices are both placeholders (CM2 = XYZ -> sRGB, CM1 x XYZ(A) = 1, 1, 1): the spec
     * estimate reads no light off them, so the scene light is the former estimate's (keep warm / cool and the As-Shot as before),
     * said in its source.
     */
    @Test public void placeholderColourMatricesKeepTheFormerLight() {
        final CameraCharacteristics c = phy110();
        // CM1 at A: XYZ -> sRGB with its rows scaled so that XYZ(A) maps to 1, 1, 1; CM2 at D65: XYZ -> sRGB itself
        final double[] m = {3.2404542, -1.5371385, -0.4985314, -0.9692660, 1.8760108, 0.0415560, 0.0556434, -0.2040259, 1.0572252};
        final double[] a = {1.09850, 1.0, 0.35585};
        final int[] cm1 = new int[9], cm2 = new int[9];
        for (int r = 0; r < 3; r++) {
            final double s = m[r * 3] * a[0] + m[r * 3 + 1] * a[1] + m[r * 3 + 2] * a[2];
            for (int k = 0; k < 3; k++) {
                cm1[r * 3 + k] = (int) Math.round(m[r * 3 + k] / s * 10000);
                cm2[r * 3 + k] = (int) Math.round(m[r * 3 + k] * 10000);
            }
        }
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)).thenReturn(17);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)).thenReturn((byte) 21);
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(cst(10000, cm1));
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)).thenReturn(cst(10000, cm2));
        CaptureController.mCameraCharacteristics = c;
        for (float[] n : new float[][]{{0.47f, 1f, 0.62f}, {0.82f, 1f, 0.36f}}) {
            final Parameters p = shot(X8U_CST, n);
            assertTrue("light " + p.sceneCct, p.sceneCct > 1000f);
            assertTrue(p.sceneCctSource, p.sceneCctSource.contains("placeholders"));
            whiteSpec = false;
            final Parameters before = shot(X8U_CST, n);
            whiteSpec = true;
            assertEquals("the former estimate", before.sceneCct, p.sceneCct, 0f);
        }
    }

    /** A hand-set Kelvin round-trips: ManualWhiteBalance's gains at 3000 K on an AWB-off result read 3000 +- 150 K. */
    @Test public void aHandSetKelvinRoundTrips() {
        final RggbChannelVector g = com.particlesdevs.photoncamera.circularbarlib.camera.ManualWhiteBalance.gains(
                CaptureController.mCameraCharacteristics, 3000);
        final CaptureResult res = result(IDENTITY, new float[]{0.5f, 1f, 0.6f});
        when(res.get(CaptureResult.CONTROL_AWB_MODE)).thenReturn(CaptureRequest.CONTROL_AWB_MODE_OFF);
        when(res.get(CaptureResult.COLOR_CORRECTION_GAINS)).thenReturn(g);
        final Parameters p = new Parameters();
        p.physicalID = 2;
        p.ReCalcColor(false, res);
        assertEquals(3000f, p.sceneCct, 150f);
    }

    /**
     * The OPPO X8U camera 4 as the old log printed it: its colour matrices row-normalised (the row scales of the phone's raw
     * rationals are not known; the B1 "DNG colour tags" log line gives them, P370 C1), the placeholder forward matrices.
     */
    static CameraCharacteristics x8u() {
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
        return c;
    }
}
