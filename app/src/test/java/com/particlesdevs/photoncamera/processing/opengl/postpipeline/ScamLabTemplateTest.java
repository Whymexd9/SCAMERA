package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.SharedPreferences;

import com.particlesdevs.photoncamera.processing.color.PlainColourMemory;
import com.particlesdevs.photoncamera.processing.color.PlainColourMemoryAccess;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * P370 (A8): a series imported with a template's parameters renders at its own white. With the grey world's white point alone
 * the render's white-balance multiply put the template's white back; sensorToProPhoto now follows the new white (the ISP
 * route's diag(1 / n): a grey of the new light maps to (1, 1, 1); any 3x3: s2p' . n_new == s2p . n_template), the scene light
 * is the plain table's label at the new white (else unknown) and the table's matrix replaces an ISP route's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ScamLabTemplateTest {
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> app;
    private MockedStatic<PreferenceKeys> prefsKeys;
    private SharedPreferences prefs;

    private static final float[] TEMPLATE_WHITE = {0.54785156f, 1f, 0.41796875f}; // the X8U's daylight shot
    private static final float[] NEW_WHITE = {0.80f, 1f, 0.30f};                    // a warmer light
    private static final float[] ISP_CCT = {1.5625f, -0.484375f, -0.078125f, -0.1328125f, 1.34375f, -0.2109375f, 0.062015504f,
            -0.5426357f, 1.4806201f};

    @Before public void setUp() {
        app = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        app.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(new com.particlesdevs.photoncamera.api.Settings());
        prefsKeys = mockStatic(PreferenceKeys.class, invocation -> invocation.callRealMethod());
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("plain_colour_table_lab_template_test", 0);
        prefs.edit().clear().commit();
        PlainColourMemoryAccess.useStore(prefs, "OPPO/PKJ110/op5dd3l1:16/1");
    }

    @After public void tearDown() {
        PlainColourMemoryAccess.reset();
        prefsKeys.close();
        app.close();
    }

    private static JSONArray array(float[] v) throws Exception {
        final JSONArray a = new JSONArray();
        for (float x : v) a.put((double) x);
        return a;
    }

    private static JSONObject params(String route, float[] s2p, float[] cct) throws Exception {
        final JSONObject p = new JSONObject();
        p.put("whitePoint", array(TEMPLATE_WHITE));
        p.put("sensorToProPhoto", array(s2p));
        p.put("CCT", new JSONObject().put("matrix", array(cct)));
        p.put("colourSource", route);
        p.put("sceneCct", 5600.0);
        p.put("physicalID", 2);
        return p;
    }

    private static float[] times(float[] m, float[] v) {
        final float[] out = new float[3];
        for (int r = 0; r < 3; r++) out[r] = m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2];
        return out;
    }

    private static float[] diagInverse(float[] n) {
        return new float[]{1f / n[0], 0f, 0f, 0f, 1f / n[1], 0f, 0f, 0f, 1f / n[2]};
    }

    /** The ISP route: a grey of the burst's light maps to (1, 1, 1), the template's light no longer does; no table: unknown light. */
    @Test public void theIspRouteMapsTheNewGreyToWhite() throws Exception {
        final JSONObject in = params("ISP_CST", diagInverse(TEMPLATE_WHITE), ISP_CCT);
        ScamLab.retemplate(in, NEW_WHITE);
        final Parameters p = ScamLabParams.fromJson(in);
        final float[] grey = times(p.sensorToProPhoto, NEW_WHITE);
        for (int c = 0; c < 3; c++) assertEquals("channel " + c, 1f, grey[c], 1e-6f);
        assertEquals(NEW_WHITE[0], p.whitePoint[0], 0f);
        assertEquals(NEW_WHITE[2], p.whitePoint[2], 0f);
        // the former import: the new white point alone left diag(1 / n_template), a cast of n_new / n_template
        final float[] before = times(diagInverse(TEMPLATE_WHITE), NEW_WHITE);
        assertTrue("R/B before " + before[0] / before[2], before[0] / before[2] > 1.5f);
        assertEquals(0f, p.sceneCct, 0f);
        assertTrue(p.sceneCctSource, p.sceneCctSource.startsWith("unknown"));
        for (int i = 0; i < 9; i++) assertEquals("the template's matrix without a table", ISP_CCT[i], p.CCT.matrix[i], 0f);
        assertEquals("ISP_CST", p.colourSource);
    }

    /** Any 3x3 (the DNG route): the new grey lands where the template's grey did. */
    @Test public void aGeneralMatrixKeepsTheTemplatesWhite() throws Exception {
        final float[] s2p = {1.21f, -0.18f, 0.09f, -0.07f, 1.32f, -0.11f, 0.04f, -0.29f, 1.87f};
        final JSONObject in = params("DNG_FM", s2p, new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1});
        ScamLab.retemplate(in, NEW_WHITE);
        final Parameters p = ScamLabParams.fromJson(in);
        final float[] now = times(p.sensorToProPhoto, NEW_WHITE), then = times(s2p, TEMPLATE_WHITE);
        for (int c = 0; c < 3; c++) assertEquals("channel " + c, then[c], now[c], 1e-6f);
        assertEquals("DNG_FM", p.colourSource);
    }

    /** A plain table of the camera: its label is the scene light and its matrix at the new white replaces the ISP route's. */
    @Test public void thePlainTableGivesTheLightAndTheMatrix() throws Exception {
        final float[] plain = {1.70f, -0.55f, -0.15f, -0.20f, 1.45f, -0.25f, 0.05f, -0.60f, 1.55f};
        for (int i = 0; i < 4; i++) PlainColourMemory.offer("2", NEW_WHITE[0], NEW_WHITE[2], plain, 3300, 10_000L + 1_000L * i);
        final JSONObject in = params("ISP_CST", diagInverse(TEMPLATE_WHITE), ISP_CCT);
        ScamLab.retemplate(in, NEW_WHITE);
        final Parameters p = ScamLabParams.fromJson(in);
        assertEquals(3300f, p.sceneCct, 1f);
        assertEquals("PLAIN_TABLE", p.colourSource);
        for (int r = 0; r < 3; r++) {
            final float sum = plain[r * 3] + plain[r * 3 + 1] + plain[r * 3 + 2];
            for (int c = 0; c < 3; c++) assertEquals("row " + r, plain[r * 3 + c] / sum, p.CCT.matrix[r * 3 + c], 1e-5f);
        }
        // a DNG-route template keeps its matrix, takes the label
        final float[] s2p = {1.21f, -0.18f, 0.09f, -0.07f, 1.32f, -0.11f, 0.04f, -0.29f, 1.87f};
        final JSONObject dng = params("DNG_FM", s2p, new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1});
        ScamLab.retemplate(dng, NEW_WHITE);
        final Parameters q = ScamLabParams.fromJson(dng);
        assertEquals(3300f, q.sceneCct, 1f);
        assertEquals("DNG_FM", q.colourSource);
        assertEquals(1f, q.CCT.matrix[0], 0f);
    }
}
