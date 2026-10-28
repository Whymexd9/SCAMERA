package com.particlesdevs.photoncamera.processing.render;

import static com.particlesdevs.photoncamera.processing.render.ParametersSceneCctTest.IDENTITY;
import static com.particlesdevs.photoncamera.processing.render.ParametersSceneCctTest.X8U_CST;
import static com.particlesdevs.photoncamera.processing.render.ParametersSceneCctTest.cst;
import static com.particlesdevs.photoncamera.processing.render.ParametersSceneCctTest.neutralAt;
import static com.particlesdevs.photoncamera.processing.render.ParametersSceneCctTest.result;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;

import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.processing.color.DcpProfiles;
import com.particlesdevs.photoncamera.processing.color.OplusCcm;
import com.particlesdevs.photoncamera.processing.color.PhotoMatrix;
import com.particlesdevs.photoncamera.processing.color.PlainColourMemory;
import com.particlesdevs.photoncamera.processing.color.PlainColourMemoryAccess;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * P370 (B5), documented at P368's clip guard (scamhdr/scamrgb.glsl ceilingGuard): the guard leaves a clipped core neutral in
 * white-balanced camera RGB, so every colour route must map the white to a white - the channel ratios of
 * CCT.matrix . sensorToProPhoto . diag(n) . (1, 1, 1) equal within 2e-3 (ratios: the computed route's white is max(n) . (1, 1, 1)).
 * The ISP route (the X8U transform), the computed DNG route (the PHY110 matrices, a warm neutral with R / G above 1 too), a DCP,
 * the plain-session table, the OPPO tuned matrix, the A3 / A5 fallbacks and the RAW viewfinder's resolver (PhotoMatrix: its
 * matrix on white-balanced RGB).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ParametersWhiteInvariantTest {
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> app;
    private MockedStatic<PreferenceKeys> prefsKeys;
    private CameraCharacteristics previous;
    private com.particlesdevs.photoncamera.api.Settings settings;
    private static float ispCcm = 1f;
    private static final float TOLERANCE = 2e-3f;
    private static final float[] X8U_N = {0.54785156f, 1f, 0.41796875f};
    // the OPPO Find X8 Ultra's com.oplus.custom.ccm (OplusCcmTest)
    private static final String OPLUS_HEX = "000000007501d23f9d371abfcdb41cbd468e9fbdf201aa3f302480be2669b93cd0c41bbfc5fcca3f000000000000000000000000";

    @Before public void setUp() {
        ispCcm = 1f;
        settings = new com.particlesdevs.photoncamera.api.Settings();
        settings.colorMethod = 1;
        app = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        app.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(settings);
        prefsKeys = mockStatic(PreferenceKeys.class, invocation -> {
            final String name = invocation.getMethod().getName();
            if (name.equals("isScamEnabled")) return true;
            if (name.equals("routeInternalValue") && "isp_ccm".equals(invocation.getArgument(0))) return ispCcm;
            if (name.equals("routeInternalValue")) return 1f;
            if (name.equals("hybridValue")) return invocation.getArgument(1);
            return invocation.callRealMethod();
        });
        previous = CaptureController.mCameraCharacteristics;
        final SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences("white_invariant_test", 0);
        prefs.edit().clear().commit();
        PlainColourMemoryAccess.useStore(prefs, "OPPO/PKJ110/op5dd3l1:16/1");
    }

    @After public void tearDown() {
        CaptureController.mCameraCharacteristics = previous;
        ShadowBuild.reset();
        PlainColourMemoryAccess.reset();
        prefsKeys.close();
        app.close();
    }

    /** The render's white of a shot: CCT.matrix . sensorToProPhoto . n (the shader: white-balanced input x n, then the matrices). */
    static float[] white(Parameters p) {
        final float[] m = p.cameraToSrgb(), w = new float[3];
        assertNotNull(p.colourSource, m);
        for (int r = 0; r < 3; r++) w[r] = m[r * 3] * p.whitePoint[0] + m[r * 3 + 1] * p.whitePoint[1] + m[r * 3 + 2] * p.whitePoint[2];
        return w;
    }

    static void assertWhite(String what, float[] w) {
        for (float v : w) assertTrue(what + ": " + java.util.Arrays.toString(w), Float.isFinite(v) && v > 0f);
        assertEquals(what + " R / G " + java.util.Arrays.toString(w), 1f, w[0] / w[1], TOLERANCE);
        assertEquals(what + " B / G " + java.util.Arrays.toString(w), 1f, w[2] / w[1], TOLERANCE);
    }

    private static Parameters shot(int camera, ColorSpaceTransform transform, float[] n) {
        final Parameters p = new Parameters();
        p.physicalID = camera;
        p.cameraID = String.valueOf(camera);
        p.ReCalcColor(false, result(transform, n));
        return p;
    }

    private static void assertRoute(String route, Parameters p) {
        assertEquals(p.colourSourceWhy, route, p.colourSource);
        assertWhite(route + " " + java.util.Arrays.toString(p.whitePoint), white(p));
    }

    /** The ISP route: diag(1 / n) and the X8U's row-normalised transform (and with the AWB label of B7). */
    @Test public void theIspRouteKeepsWhite() {
        CaptureController.mCameraCharacteristics = ParametersSceneCctTest.x8u();
        assertRoute("ISP_CST", shot(2, X8U_CST, X8U_N));
        final CaptureResult labelled = result(X8U_CST, X8U_N);
        when(labelled.get(Parameters.AWB_CCT_LABEL)).thenReturn(new int[]{5100});
        final Parameters p = new Parameters();
        p.physicalID = 2;
        p.ReCalcColor(false, labelled);
        assertRoute("ISP_CST", p);
    }

    /** The computed route: the PHY110 forward matrices at tungsten, 4000 K, daylight, a warm white with R / G above 1, a cool one. */
    @Test public void theComputedRouteKeepsWhite() {
        CaptureController.mCameraCharacteristics = ParametersSceneCctTest.phy110();
        final float[][] whites = {neutralAt(2856), neutralAt(4000), neutralAt(6504), {1.25f, 1f, 0.30f}, {0.38f, 1f, 0.95f}};
        assertTrue("a warm white with R / G above 1", whites[3][0] > 1f);
        for (float[] n : whites) assertRoute("DNG_FM", shot(2, IDENTITY, n));
    }

    /** A DCP profile (ColorMatrix1 and ForwardMatrix1, D65): XYZ -> ProPhoto of its camera -> XYZ, the white at D50. */
    @Test public void theDcpRouteKeepsWhite() throws Exception {
        CaptureController.mCameraCharacteristics = ParametersSceneCctTest.phy110();
        final Application context = RuntimeEnvironment.getApplication();
        final String id = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef.dcp";
        final File dir = new File(context.getFilesDir(), "dcp");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(dir, id))) {
            out.write(dcp(new double[]{0.6178, -0.1002, -0.0970, -0.4729, 1.4118, 0.0345, -0.0861, 0.2593, 0.4441},
                    new double[]{0.6200, 0.2500, 0.0942, 0.3000, 0.6600, 0.0400, 0.0300, 0.1000, 0.6949}));
        }
        final SharedPreferences prefs = context.getSharedPreferences("white_invariant_dcp", 0);
        prefs.edit().clear().putString(DcpProfiles.KEY, id).commit();
        final SettingsManager manager = mock(SettingsManager.class);
        when(manager.getContext()).thenReturn(context);
        when(manager.getDefaultPreferences()).thenReturn(prefs);
        app.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        for (float[] n : new float[][]{neutralAt(2856), neutralAt(6504), {1.25f, 1f, 0.30f}}) assertRoute("DCP", shot(2, IDENTITY, n));
    }

    /** A DCP of one illuminant: tags CalibrationIlluminant1 (D65), ColorMatrix1, ForwardMatrix1 as signed rationals. */
    private static byte[] dcp(double[] cm, double[] fm) {
        final ByteBuffer b = ByteBuffer.allocate(8 + 2 + 3 * 12 + 4 + 2 * 72).order(ByteOrder.LITTLE_ENDIAN);
        final int data = 8 + 2 + 3 * 12 + 4;
        b.put((byte) 'I').put((byte) 'I').putShort((short) 0x4352).putInt(8).putShort((short) 3);
        b.putShort((short) 50778).putShort((short) 3).putInt(1).putShort((short) 21).putShort((short) 0);
        b.putShort((short) 50721).putShort((short) 10).putInt(9).putInt(data);
        b.putShort((short) 50964).putShort((short) 10).putInt(9).putInt(data + 72);
        b.putInt(0);
        for (double v : cm) b.putInt((int) Math.round(v * 10000)).putInt(10000);
        for (double v : fm) b.putInt((int) Math.round(v * 10000)).putInt(10000);
        return b.array();
    }

    /** The plain-session table (a placeholder-FM camera, an identity transform): rows normalised, diag(1 / n). */
    @Test public void thePlainTableKeepsWhite() {
        CaptureController.mCameraCharacteristics = ParametersSceneCctTest.x8u();
        final float[] table = {1.65f, -0.51f, -0.11f, -0.19f, 1.39f, -0.17f, 0.04f, -0.57f, 1.56f}; // rows 1.03, 1.03, 1.03
        for (int i = 0; i < 4; i++) PlainColourMemory.offer("4", X8U_N[0], X8U_N[2], table, 5200, 10_000L + 1_000L * i);
        assertRoute("PLAIN_TABLE", shot(4, IDENTITY, X8U_N));
    }

    /** The OPPO Find X7 Ultra's tuned forward matrix (PHY110), daylight and a warm white. */
    @Test public void theOppoTunedRouteKeepsWhite() {
        ShadowBuild.setModel("PHY110");
        CaptureController.mCameraCharacteristics = ParametersSceneCctTest.phy110();
        for (float[] n : new float[][]{{0.52f, 1f, 0.66f}, neutralAt(3000)}) assertRoute("OPPO_TUNED", shot(2, IDENTITY, n));
    }

    private static byte[] hex(String s) {
        final byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** P370 (A3, A5): the fallbacks - the gated transform, the colour matrices alone, the placeholder, the OPPO ISP matrix, the
     * white balance alone, «Захват». */
    @Test public void theFallbacksKeepWhite() {
        final CameraCharacteristics x8u = ParametersSceneCctTest.x8u();
        CaptureController.mCameraCharacteristics = x8u;
        assertRoute("IDENTITY_PLACEHOLDER", shot(4, IDENTITY, X8U_N));
        final CaptureResult oplus = result(IDENTITY, X8U_N);
        when(oplus.get(new CaptureResult.Key<>(OplusCcm.TAG_NAME, byte[].class))).thenReturn(hex(OPLUS_HEX));
        final Parameters o = new Parameters();
        o.physicalID = 4;
        o.ReCalcColor(false, oplus);
        assertRoute("OPLUS_CCM", o);
        ispCcm = 0f;
        assertRoute("ISP_CST_PLACEHOLDER", shot(4, X8U_CST, X8U_N));
        ispCcm = 1f;
        when(x8u.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(cst(10000, 6178, -1002, -970, -4729, 14118, 345, -861, 2593, 4441));
        when(x8u.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)).thenReturn(cst(10000, 6892, 196, -3223, -7131, 18376, -1520, -884, 2258, 6427));
        for (float[] n : new float[][]{neutralAt(5000), neutralAt(2856)}) assertRoute("DNG_CM_ONLY", shot(4, IDENTITY, n));
        // A5: a singular calibration (the white balance alone) and «Захват» (colour method 2: the result's transform)
        final CameraCharacteristics phy = ParametersSceneCctTest.phy110();
        CaptureController.mCameraCharacteristics = phy;
        settings.colorMethod = 2;
        ispCcm = 0f; // with the ISP branch on, the same transform is the ISP route (above)
        assertRoute("LEGACY_CAPTURED", shot(2, X8U_CST, new float[]{0.5f, 1f, 0.6f}));
        ispCcm = 1f;
        settings.colorMethod = 1;
        when(phy.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)).thenReturn(cst(1, 0, 0, 0, 0, 1, 0, 0, 0, 1));
        when(phy.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)).thenReturn(cst(1, 0, 0, 0, 0, 1, 0, 0, 0, 1));
        assertRoute("IDENTITY_FALLBACK", shot(2, IDENTITY, new float[]{0.5f, 1f, 0.6f}));
    }

    /** P370 (A2): the RAW viewfinder's matrix (on white-balanced RGB) maps (1, 1, 1) to a white for every pick. */
    @Test public void theViewfinderResolverKeepsWhite() {
        ShadowBuild.setModel("PHY110");
        final CameraCharacteristics c = ParametersSceneCctTest.phy110();
        final float[] n = {0.52f, 1f, 0.66f}, identity = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        final float[] x8u = {200 / 128f, -62 / 128f, -10 / 128f, -17 / 128f, 172 / 128f, -27 / 128f, 8 / 128f, -70 / 128f, 191 / 128f};
        final float[] oplus = OplusCcm.parse(hex(OPLUS_HEX));
        final float[] table = {1.65f, -0.51f, -0.11f, -0.19f, 1.39f, -0.17f, 0.04f, -0.57f, 1.56f};
        for (int i = 0; i < 4; i++) PlainColourMemory.offer("2", n[0], n[2], table, 5000, 10_000L + 1_000L * i);
        final PlainColourMemory.Lookup bin = PlainColourMemory.lookup("2", n);
        assertNotNull(bin);
        final float[] tuned = OppoTunedColor.previewMatrix(c, n, "2");
        assertNotNull(tuned);
        final PhotoMatrix.Pick[] picks = {
                PhotoMatrix.viewfinder(x8u, null, Float.NaN, false, null, null),                                        // the result's transform
                PhotoMatrix.viewfinder(identity, tuned, OppoTunedColor.previewCct(c, n, "2"), false, null, oplus),       // tuned
                PhotoMatrix.viewfinder(identity, null, Float.NaN, false, bin, oplus),                                     // the OPPO matrix
                PhotoMatrix.viewfinder(identity, null, Float.NaN, true, bin, oplus),                                      // a mosaic: the table
        };
        for (PhotoMatrix.Pick pick : picks) {
            assertNotNull(pick.how, pick.rowMajor);
            final float[] m = pick.rowMajor, w = new float[3];
            for (int r = 0; r < 3; r++) w[r] = m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2];
            assertWhite("viewfinder " + pick.how, w);
        }
    }
}
