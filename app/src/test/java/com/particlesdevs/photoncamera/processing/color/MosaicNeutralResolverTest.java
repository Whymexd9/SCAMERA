package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.hardware.camera2.CaptureResult;
import android.util.Rational;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * P212g (W6 = M6, the verifier's variant): a vivo native mosaic photo keeps the HAL's neutral when its AWB has converged and the
 * neutral lies on the camera's curve, else P121; P208's still-only route takes the median of its normal-mode viewfinder's converged
 * neutrals of the second before the press; the 12 MP ISZ modules and the OPPO route keep P121. Fixtures from the logged cases.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class MosaicNeutralResolverTest {
    @Before public void setUp() { MosaicNeutral.resetForTest(); }
    @After public void tearDown() { MosaicNeutral.resetForTest(); }

    private static final int CONVERGED = CaptureResult.CONTROL_AWB_STATE_CONVERGED, SEARCHING = CaptureResult.CONTROL_AWB_STATE_SEARCHING;

    /** The X300 Ultra tele's curve as its plain sessions would teach it: the plain 0.4805 / 0.5967 and lights along the camera's locus. */
    private static void teachX300Tele() {
        final double[][] pts = {{0.4805, 0.5967}, {0.43, 0.66}, {0.40, 0.71}, {0.55, 0.52}, {0.62, 0.45}, {0.70, 0.40}};
        for (double[] p : pts) for (int i = 0; i < 4; i++) MosaicNeutral.learn("x300", (float) p[0], (float) p[1]);
    }

    /** M6 VERIFY 1: X300 Ultra tele (SCAMERA-debug.log (7).txt:500): the converged HAL neutral 0.4746 / 0.5986 is kept. */
    @Test public void theX300TeleKeepsItsConvergedHalNeutral() {
        teachX300Tele();
        final float[] hal = {0.4746f, 1f, 0.5986f};
        final float d = MosaicNeutralResolver.curveDistance("x300", hal, null);
        assertTrue("on the curve: " + d, d < 0.03f);
        final MosaicNeutralResolver.Decision k = MosaicNeutralResolver.decide(MosaicNeutralResolver.Press.vivoNative(), hal, CONVERGED, d);
        assertNotNull(k);
        assertEquals(0.4746f, k.neutral[0], 1e-6f);
        assertEquals(0.5986f, k.neutral[2], 1e-6f);
        assertTrue(k.how, k.how.contains("HAL's neutral") && k.how.contains("vivo native"));
        // locked counts as converged
        assertNotNull(MosaicNeutralResolver.decide(MosaicNeutralResolver.Press.vivoNative(), hal, CaptureResult.CONTROL_AWB_STATE_LOCKED, d));
    }

    /** An AWB still searching, no state, a HAL far off the curve (a stably wrong Tetra neutral), no curve at all: P121. */
    @Test public void p121UnlessConvergedAndOnTheCurve() {
        teachX300Tele();
        final MosaicNeutralResolver.Press vivo = MosaicNeutralResolver.Press.vivoNative();
        final float[] hal = {0.4746f, 1f, 0.5986f};
        final float d = MosaicNeutralResolver.curveDistance("x300", hal, null);
        assertNull(MosaicNeutralResolver.decide(vivo, hal, SEARCHING, d));
        assertNull(MosaicNeutralResolver.decide(vivo, hal, null, d));
        assertTrue(MosaicNeutralResolver.whyNot(vivo, hal, SEARCHING, d).contains("not converged"));
        // 30839's night Tetra HAL neutral 0.568 / 0.643 against this curve (b ~0.5 at r 0.57): off it
        final float[] wrong = {0.568f, 1f, 0.643f};
        final float far = MosaicNeutralResolver.curveDistance("x300", wrong, null);
        assertTrue("off the curve: " + far, far > 0.1f);
        assertNull(MosaicNeutralResolver.decide(vivo, wrong, CONVERGED, far));
        assertTrue(MosaicNeutralResolver.whyNot(vivo, wrong, CONVERGED, far).contains("from the camera's curve"));
        // a camera without a curve (and no prior): nothing tells whether the HAL is right
        final float none = MosaicNeutralResolver.curveDistance("nocurve", hal, null);
        assertTrue(Float.isNaN(none));
        assertNull(MosaicNeutralResolver.decide(vivo, hal, CONVERGED, none));
        assertTrue(MosaicNeutralResolver.whyNot(vivo, hal, CONVERGED, none).contains("no curve"));
        // a NaN HAL neutral
        assertNull(MosaicNeutralResolver.decide(vivo, new float[]{Float.NaN, 1f, 0.5f}, CONVERGED, 0f));
    }

    /** No route (the 12 MP ISZ modules of any brand, the OPPO native route): P121 as before, whatever the HAL says. */
    @Test public void otherRoutesKeepP121() {
        assertNull(MosaicNeutralResolver.decide(null, new float[]{0.47f, 1f, 0.6f}, CONVERGED, 0f));
        assertNull(MosaicNeutralResolver.decide(new MosaicNeutralResolver.Press(MosaicNeutralResolver.ROUTE_NONE, null, 0, 0f),
                new float[]{0.47f, 1f, 0.6f}, CONVERGED, 0f));
        assertEquals("", MosaicNeutralResolver.whyNot(null, null, null, Float.NaN));
    }

    /**
     * M6 still-only 200 MP: the viewfinder's converged neutrals of the last second before the press, median in ln; older ones,
     * another camera's, unconverged ones and AWB off are left out; the still's own HAL neutral does not matter then.
     */
    @Test public void theStillOnlyRouteTakesTheViewfinderMedian() {
        final MosaicNeutralResolver.Ring ring = new MosaicNeutralResolver.Ring();
        final long press = 50_000L;
        ring.offer("5", press - 3_000, 0.70f, 0.40f, true); // older than a second
        ring.offer("5", press - 900, 0.480f, 0.600f, true);
        ring.offer("5", press - 600, 0.476f, 0.604f, true);
        ring.offer("5", press - 300, 0.484f, 0.596f, true);
        ring.offer("5", press - 200, 0.30f, 0.90f, false); // AWB still searching
        ring.offer("4", press - 100, 0.39f, 0.67f, true); // another camera
        final MosaicNeutralResolver.Press p = ring.stillOnly("5", press);
        assertEquals(MosaicNeutralResolver.ROUTE_STILL_ONLY, p.route);
        assertNotNull(p.viewfinder);
        assertEquals(3, p.results);
        assertEquals(0.480f, p.viewfinder[0], 1e-4f);
        assertEquals(0.600f, p.viewfinder[2], 1e-4f);
        assertTrue(p.spread < 0.01f);
        final MosaicNeutralResolver.Decision d = MosaicNeutralResolver.decide(p, new float[]{0.748f, 1f, 0.367f}, SEARCHING, Float.NaN);
        assertNotNull(d);
        assertEquals(0.480f, d.neutral[0], 1e-4f);
        assertTrue(d.how, d.how.contains("viewfinder"));
        // nothing converged in the window: the still's own HAL neutral by the vivo rule, else P121
        final MosaicNeutralResolver.Press empty = ring.stillOnly("5", press + 5_000);
        assertNull(empty.viewfinder);
        assertNull(MosaicNeutralResolver.decide(empty, new float[]{0.47f, 1f, 0.6f}, CONVERGED, Float.NaN));
        final MosaicNeutralResolver.Decision hal = MosaicNeutralResolver.decide(empty, new float[]{0.47f, 1f, 0.6f}, CONVERGED, 0.02f);
        assertNotNull(hal);
        assertTrue(hal.how, hal.how.contains("still-only route without a converged viewfinder result"));
        ring.clear();
        assertNull(ring.stillOnly("5", press).viewfinder);
    }

    /** The ring reads a result's neutral and AWB (AUTO and CONVERGED / LOCKED only count); it keeps the last 32. */
    @Test public void theRingReadsResults() {
        final MosaicNeutralResolver.Ring ring = new MosaicNeutralResolver.Ring();
        ring.offer("5", 1_000L, result(CaptureResult.CONTROL_AWB_MODE_AUTO, CONVERGED, 0.45f, 0.62f));
        ring.offer("5", 1_100L, result(CaptureResult.CONTROL_AWB_MODE_OFF, CONVERGED, 0.30f, 0.90f));
        ring.offer("5", 1_200L, result(CaptureResult.CONTROL_AWB_MODE_AUTO, SEARCHING, 0.30f, 0.90f));
        ring.offer("5", 1_300L, null);
        final MosaicNeutralResolver.Press p = ring.stillOnly("5", 1_500L);
        assertEquals(1, p.results);
        assertEquals(0.45f, p.viewfinder[0], 1e-3f);
        for (int i = 0; i < 40; i++) ring.offer("5", 2_000L + i, 0.5f, 0.5f, true);
        assertEquals(MosaicNeutralResolver.RING, ring.stillOnly("5", 2_100L).results);
    }

    /** The PD2454 tele without learned points: its DNG prior (anchored at the daylight neutral) is the curve the HAL is held to. */
    @Test public void aPriorIsACurveToo() {
        // a DNG locus through 0.4668 / 0.6904 (PD2454 camera 5's daylight neutral) and a warm 0.80 / 0.36
        final MosaicNeutral.Prior prior = new MosaicNeutral.Prior(Math.log(0.80), Math.log(0.36), Math.log(0.4668), Math.log(0.6904), false);
        final float d = MosaicNeutralResolver.curveDistance("prior", new float[]{0.4668f, 1f, 0.6904f}, prior);
        assertTrue("on the prior: " + d, d < 0.01f);
        assertTrue(MosaicNeutralResolver.curveDistance("prior", new float[]{0.4668f, 1f, 1.0f}, prior) > 0.1f);
    }

    /** The X8U camera 2's curve as logged (ln b = -0.5846 - 0.6602 (ln r + 0.7176), ln r -0.856 .. -0.515), its plain sessions' points. */
    private static void teachX8uMain() {
        for (int i = 0; i <= 6; i++) {
            final double lr = -0.856 + i * (0.856 - 0.515) / 6, lb = -0.5846 - 0.6602 * (lr + 0.7176);
            for (int k = 0; k < 4; k++) MosaicNeutral.learn("x8u", (float) Math.exp(lr), (float) Math.exp(lb));
        }
    }

    /**
     * P315 (owner's X8U ISZ shots, 2026-10-21: «обесцвеченные и с странным бб»): the OPPO sensor-mode route keeps the HAL's converged
     * neutral on the curve (0.446 / 0.589, as the plain mode's); a P121 estimate 0.745 off the curve (the LED glow on the white case)
     * gives way to the camera's neutral on any route; one nearer the curve, or a camera neutral off it, keeps P121.
     */
    @Test public void theX8uIszKeepsItsHalNeutral() {
        teachX8uMain();
        final float[] hal = {0.446f, 1f, 0.589f};
        final float d = MosaicNeutralResolver.curveDistance("x8u", hal, null);
        assertTrue("on the curve: " + d, d < 0.03f);
        final MosaicNeutralResolver.Decision k = MosaicNeutralResolver.decide(MosaicNeutralResolver.Press.sensorMode(), hal, CONVERGED, d);
        assertNotNull(k);
        assertEquals(0.446f, k.neutral[0], 1e-6f);
        assertTrue(k.how, k.how.contains("sensor-mode route"));
        assertNull(MosaicNeutralResolver.decide(MosaicNeutralResolver.Press.sensorMode(), hal, SEARCHING, d));
        final String keep = MosaicNeutralResolver.offLocus("x8u", 0.745f, hal, null);
        assertNotNull(keep);
        assertTrue(keep, keep.contains("camera's neutral 0.446 / 0.589 kept"));
        assertNull("an estimate near the curve stays", MosaicNeutralResolver.offLocus("x8u", 0.3f, hal, null));
        assertNull("a camera neutral off the curve is no better", MosaicNeutralResolver.offLocus("x8u", 0.745f, new float[]{1f, 1f, 1f}, null));
        assertNull("no curve to tell", MosaicNeutralResolver.offLocus("none", 0.745f, hal, null));
    }

    private static CaptureResult result(int awbMode, int awbState, float r, float b) {
        final CaptureResult c = mock(CaptureResult.class);
        when(c.get(CaptureResult.CONTROL_AWB_MODE)).thenReturn(awbMode);
        when(c.get(CaptureResult.CONTROL_AWB_STATE)).thenReturn(awbState);
        when(c.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)).thenReturn(new Rational[]{
                new Rational(Math.round(r * 1024), 1024), new Rational(1, 1), new Rational(Math.round(b * 1024), 1024)});
        return c;
    }
}
