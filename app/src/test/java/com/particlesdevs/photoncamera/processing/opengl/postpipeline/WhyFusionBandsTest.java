package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.app.Application;
import android.content.Context;

import com.particlesdevs.photoncamera.app.ScameraApp;
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

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * P391 (owner, 2026-10-28, approved: an edge-aware pyramid without halos, its depth, the shadow lift and the fine bands' gain bounded
 * by the frame's noise): the defaults and switches of WhyTone, WhyFusion's continuous depth and its noise model of the guide.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class WhyFusionBandsTest {
    private MockedStatic<ScameraApp> camera;

    @Before public void setUp() {
        final Context context = RuntimeEnvironment.getApplication();
        final SettingsManager manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        PreferenceKeys.initialise(manager);
    }

    @After public void tearDown() {
        PreferenceKeys.labDevOverlay(null);
        if (camera != null) camera.close();
    }

    private static void devLines(Object... kv) {
        final Map<String, Float> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).floatValue());
        PreferenceKeys.labDevOverlay(m);
    }

    /** The bands on by default; P349's guided share and P389's edge share off with them (the guided filter is not run). */
    @Test public void theBandsAreOnAndTheSharesOffByDefault() {
        final float[] b = WhyTone.bands();
        assertNotNull(b);
        // review of P391: the edge measure in EV of a light step (1.2 .. 1.8), reach 3 with a fall, the band floor 2^-9
        assertArrayEquals(new float[]{1.2f, 1.8f, 0.001953125f, 1f, 3f, 0.25f}, b, 0f);
        assertArrayEquals(new float[]{0f, 0f}, WhyTone.guidedLights(), 0f);
        final float[] e = WhyTone.edgeShare();
        assertTrue("the edge share off", !(e[1] > e[0]));
        assertTrue(WhyTone.depthSmooth());
        assertEquals(0.008f, WhyTone.deepNoise(), 0f);
        // c of the X200 Ultra's dump with the worker's k, by the calibration that recovers a known c (check_why_noise.py)
        assertEquals(1f, WhyTone.noiseScale(), 0f);
        assertArrayEquals(new float[]{2f, 4f, 3f}, WhyTone.fusionNoise(), 0f);
        assertEquals(2f, WhyTone.bandsNoise(), 0f);
    }

    /** An explicit scam_dev line brings a share back on top of the bands (the A/B); bands 0 gives the shares and the pyramid as before. */
    @Test public void theSharesComeBackByAnExplicitLineOrWithTheBandsOff() {
        devLines("hybrid_why_fusion_guided_lo", 0.8f, "hybrid_why_fusion_edge_hi", 1.0f);
        assertNotNull(WhyTone.bands());
        assertArrayEquals(new float[]{0.8f, 1.6f}, WhyTone.guidedLights(), 0f);
        assertArrayEquals(new float[]{0.3f, 1.0f, 0.0078125f}, WhyTone.edgeShare(), 0f);
        devLines("hybrid_why_fusion_bands", 0f);
        assertNull(WhyTone.bands());
        assertArrayEquals(new float[]{0.8f, 1.6f}, WhyTone.guidedLights(), 0f);
        assertArrayEquals(new float[]{0.3f, 1.0f, 0.0078125f}, WhyTone.edgeShare(), 0f);
    }

    /**
     * Continuous depth: levels = ceil(d), f = d - (levels - 1), d = log2(shorter side / coarse) - a 4096 x 3072 and a 4000 x 3000 sensor
     * (whyLow 1536 / 1500 rows at ef_coarse 48) 5 levels at f 1 and 0.966 (whole levels: 5 and 4); the total depth follows d without a
     * jump, f = 1 exactly at a power of two, whole levels where f is 1.
     */
    @Test public void theDepthIsContinuous() {
        assertArrayEquals(new float[]{5f, 1f}, WhyFusion.depth(2048, 1536, 48f), 0f);
        final float[] b = WhyFusion.depth(2000, 1500, 48f);
        assertEquals(5f, b[0], 0f);
        assertEquals(Math.log(1500 / 48.0) / Math.log(2) - 4, b[1], 1e-5);
        assertEquals(4, WhyFusion.pyramidLevels(2000, 1500, 48f));
        assertArrayEquals(new float[]{6f, 1f}, WhyFusion.depth(4096, 3072, 48f), 0f);
        assertArrayEquals(new float[]{1f, 1f}, WhyFusion.depth(64, 48, 48f), 0f);
        double last = -1;
        for (int side = 100; side <= 12288; side += 7) {
            final float[] d = WhyFusion.depth(side * 4 / 3, side, 48f);
            final double total = d[0] - 1 + d[1];
            assertTrue("f in (0, 1] at " + side, d[1] > 0f && d[1] <= 1f);
            if (side > 96) assertEquals("the depth's log at " + side, Math.log(side / 48.0) / Math.log(2), total, 1e-5);
            if (last >= 0) assertTrue("no jump at " + side, total >= last && total - last < 0.11);
            last = total;
        }
        for (int k = 1; k <= 7; k++) {
            final int side = 48 << k;
            assertArrayEquals(new float[]{k, 1f}, WhyFusion.depth(side * 4 / 3, side, 48f), 0f);
            assertEquals(k, WhyFusion.pyramidLevels(side * 4 / 3, side, 48f));
        }
    }

    /** The guide's noise: c x merged / 4 x the one-frame model in the AE's domain (variance S_I I + O_I); none without a model. */
    @Test public void theGuidesNoiseModel() {
        final float[] n = WhyFusion.guideNoise(2e-4f, 4e-7f, 0.1f, 2f, 1f, 1f);
        assertNotNull(n);
        assertEquals(1e-5f, n[0], 1e-11f);
        assertEquals(4e-8f, n[1], 1e-14f);
        final float[] c = WhyFusion.guideNoise(2e-4f, 4e-7f, 0.1f, 2f, 2f, 0.5f);
        assertEquals(1e-5f, c[0], 1e-11f);
        assertEquals(8e-8f, c[1], 1e-14f);
        assertNull(WhyFusion.guideNoise(2e-4f, 4e-7f, Float.NaN, 2f, 1f, 1f));
        assertNull(WhyFusion.guideNoise(0f, 0f, 0.1f, 2f, 1f, 1f));
        assertNull(WhyFusion.guideNoise(2e-4f, 4e-7f, 0.1f, 2f, 1f, 0f));
        assertEquals(Math.pow(0.55 * 20, -0.8), WhyTone.expectedMerged(), 1e-6);
    }

    /**
     * The edge ramp of a level: the step EV through the level's step response, halved (D of a step of S EV peaks near S / 2 x
     * EDGE_STEP), the fall a share of hi; the last response for the coarser levels; D's noise factor halving below its table.
     */
    @Test public void theEdgeRampFollowsTheLevelsStepResponse() {
        final float[] b = WhyTone.bands();
        assertArrayEquals(new float[]{0.5f * 1.2f * 0.95f, 0.5f * 1.8f * 0.95f, 0.25f * 0.5f * 1.8f * 0.95f}, WhyTone.edgeRamp(b, 0), 1e-7f);
        assertArrayEquals(new float[]{0.5f * 1.2f * 0.83f, 0.5f * 1.8f * 0.83f, 0.25f * 0.5f * 1.8f * 0.83f}, WhyTone.edgeRamp(b, 1), 1e-7f);
        assertArrayEquals(WhyTone.edgeRamp(b, 4), WhyTone.edgeRamp(b, 9), 0f);
        assertEquals(0.997f, WhyTone.edgeBeta(0), 0f);
        assertEquals(0.029f, WhyTone.edgeBeta(4), 0f);
        assertEquals(0.0145f, WhyTone.edgeBeta(5), 1e-7f);
        // a +-0.5 EV texture's measure (0.5 EV at level 0) under the ramp's start; a 2 EV step's (0.95) over its top
        assertTrue(0.5f < WhyTone.edgeRamp(b, 0)[0]);
        assertTrue(0.95f > WhyTone.edgeRamp(b, 0)[1]);
        devLines("hybrid_why_fusion_bands_fall", 0f, "hybrid_why_fusion_bands_reach", 9f);
        assertEquals(0f, WhyTone.bands()[5], 0f);
        assertEquals("the reach at most 4", 4f, WhyTone.bands()[4], 0f);
    }

    /** The bands' white-noise factor: the table on levels 0 .. 3, halving below. */
    @Test public void theBandNoiseFactorHalvesPerLevel() {
        assertEquals(0.9279f, WhyTone.bandBeta(0), 0f);
        assertEquals(0.0462f, WhyTone.bandBeta(3), 0f);
        assertEquals(0.0231f, WhyTone.bandBeta(4), 1e-7f);
        assertEquals(0.0462f / 64f, WhyTone.bandBeta(9), 1e-9f);
    }
}
