package com.particlesdevs.photoncamera.processing.render;

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
import android.graphics.Point;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.RggbChannelVector;
import android.util.Rational;

import com.particlesdevs.photoncamera.capture.OppoPlainStateAccess;

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
 * P212e (W1 = M2): an OPPO native FULLRES frame without a lens shading map (a cold start straight into FULLRES) is developed with
 * the camera's stored plain map, in copyGainFactors order (so the crops and the DNG gain map take it as a reported one); a NaN
 * neutral never reaches the photo.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ParametersStoredShadingTest {
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> app;
    private SharedPreferences prefs;

    @Before public void setUp() {
        app = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        app.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(new com.particlesdevs.photoncamera.api.Settings());
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("oppo_plain_state_parameters_test", 0);
        prefs.edit().clear().commit();
        OppoPlainStateAccess.useStore(prefs, "fw");
        ShadowBuild.setManufacturer("OPPO");
    }

    @After public void tearDown() {
        OppoPlainStateAccess.reset();
        app.close();
    }

    private static float[] map(int cols, int rows) {
        final float[] g = new float[4 * cols * rows];
        for (int i = 0; i < cols * rows; i++) {
            final int x = i % cols, y = i / cols;
            final float r2 = ((x - 8f) * (x - 8f) / 64f + (y - 6f) * (y - 6f) / 36f) / 2f;
            g[4 * i] = 1f + 3.7f * r2;
            g[4 * i + 1] = 1f + 3.6f * r2;
            g[4 * i + 2] = 1f + 3.6f * r2;
            g[4 * i + 3] = 1f + 3.5f * r2;
        }
        return g;
    }

    private static Parameters parameters(int id, int w, int h) {
        final Parameters p = new Parameters();
        p.physicalID = id;
        p.rawSize = new Point(w, h);
        return p;
    }

    private static CaptureResult neutralResult(float r, float b) {
        final CaptureResult result = mock(CaptureResult.class);
        when(result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)).thenReturn(new Rational[]{
                new Rational(Math.round(r * 1000), 1000), new Rational(1, 1), new Rational(Math.round(b * 1000), 1000)});
        return result;
    }

    /** M2 VERIFY: the stored floats reach Parameters.gainMap when the result has no map. */
    @Test public void theStoredMapReachesTheGainMap() {
        final float[] g = map(17, 13);
        OppoPlainStateAccess.remember("2", 8192, 6144, 0.408f, 0.614f, g, 17, 13);
        final Parameters p = parameters(2, 8192, 6144);
        assertTrue(p.applyStoredShading(neutralResult(0.41f, 0.61f)));
        assertTrue(p.hasGainMap);
        assertEquals(new Point(17, 13), p.mapSize);
        assertArrayEquals(g, p.gainMap, 0f);
        p.gainMap[0] = 99f; // the photo's copy (the crops write into it): the store keeps its own
        final Parameters q = parameters(2, 8192, 6144);
        assertTrue("no neutral: the newest map", q.applyStoredShading(null));
        assertEquals(g[0], q.gainMap[0], 0f);
    }

    /** A plain 12 MP frame, another camera or a phone of another brand never takes it. */
    @Test public void onlyTheNativeFrameOfThatCamera() {
        OppoPlainStateAccess.remember("2", 8192, 6144, 0.408f, 0.614f, map(17, 13), 17, 13);
        assertFalse(parameters(2, 4096, 3072).applyStoredShading(null));
        assertFalse(parameters(4, 8192, 6144).applyStoredShading(null));
        ShadowBuild.setManufacturer("vivo");
        assertFalse(parameters(2, 8192, 6144).applyStoredShading(null));
    }

    /** M2 step 5: a NaN neutral is replaced by the inverse gains, else the stored plain neutral, else equal gains; a good one stays. */
    @Test public void aNanNeutralNeverReachesThePhoto() {
        final float[] nan = {Float.NaN, Float.NaN, Float.NaN};
        final RggbChannelVector gains = new RggbChannelVector(2.449f, 1f, 1f, 1.6287f);
        assertArrayEquals(new float[]{1 / 2.449f, 1f, 1 / 1.6287f}, Parameters.unusableNeutralFallback(nan, gains, null), 1e-5f);
        final RggbChannelVector nanGains = new RggbChannelVector(Float.NaN, 1f, 1f, Float.NaN);
        assertArrayEquals(new float[]{0.408f, 1f, 0.614f},
                Parameters.unusableNeutralFallback(nan, nanGains, new float[]{0.408f, 1f, 0.614f}), 1e-6f);
        assertArrayEquals(new float[]{1f, 1f, 1f}, Parameters.unusableNeutralFallback(null, null, null), 0f);
        assertArrayEquals(new float[]{1f, 1f, 1f}, Parameters.unusableNeutralFallback(new float[]{0f, 1f, 0.6f}, null, null), 0f);
        assertNull(Parameters.unusableNeutralFallback(new float[]{0.408f, 1f, 0.614f}, gains, null));
        for (float[] out : new float[][]{Parameters.unusableNeutralFallback(nan, gains, null),
                Parameters.unusableNeutralFallback(nan, nanGains, null)}) {
            assertNotNull(out);
            for (float v : out) assertTrue(v > 0f && !Float.isInfinite(v));
        }
    }
}
