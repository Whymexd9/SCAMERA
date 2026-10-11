package com.particlesdevs.photoncamera.manual;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * P82 (owner, 2026-10-11): «если я выставлю условно 1/10, то все остальные параметры должны меняться автоматически,
 * подстраиваясь под то, что выставил я». One of ISO and shutter by hand, the other follows the scene.
 */
public class ExposurePriorityTest {
    private static final int ISO_MIN = 50, ISO_MAX = 6400;
    private static final long T_MIN = 50_000L, T_MAX = 200_000_000L;

    @Test
    public void exactlyOneHandSetParameterIsAPriority() {
        assertEquals(ExposurePriority.SHUTTER, ExposurePriority.mode(false, true));
        assertEquals(ExposurePriority.ISO, ExposurePriority.mode(true, false));
        assertEquals(ExposurePriority.NONE, ExposurePriority.mode(false, false));
        assertEquals(ExposurePriority.NONE, ExposurePriority.mode(true, true));
        // CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY 1, _SENSOR_EXPOSURE_TIME_PRIORITY 2
        assertEquals(1, ExposurePriority.halMode(ExposurePriority.ISO));
        assertEquals(2, ExposurePriority.halMode(ExposurePriority.SHUTTER));
        // the Xiaomi 17 Ultra lists [0 1 2]; a module without the key: the software loop
        assertTrue(ExposurePriority.halSupports(new int[]{0, 1, 2}, ExposurePriority.SHUTTER));
        assertFalse(ExposurePriority.halSupports(new int[]{0, 1}, ExposurePriority.SHUTTER));
        assertFalse(ExposurePriority.halSupports(null, ExposurePriority.ISO));
    }

    @Test
    public void handSetShutterKeepsTheAutoExposure() {
        // AE: ISO 400 at 1/100; the owner sets 1/10 -> ISO 40, clamped to the module's 50
        ExposurePriority.Loop loop = new ExposurePriority.Loop(ExposurePriority.SHUTTER, 400, 10_000_000L);
        loop.setFixed(0, 100_000_000L, ISO_MIN, ISO_MAX, T_MIN, T_MAX);
        assertEquals(100_000_000L, loop.shutterNs());
        assertEquals(ISO_MIN, loop.iso());
        // 1/1000 -> ISO 4000
        loop = new ExposurePriority.Loop(ExposurePriority.SHUTTER, 400, 10_000_000L);
        loop.setFixed(0, 1_000_000L, ISO_MIN, ISO_MAX, T_MIN, T_MAX);
        assertEquals(4000, loop.iso());
    }

    @Test
    public void handSetIsoKeepsTheAutoExposure() {
        ExposurePriority.Loop loop = new ExposurePriority.Loop(ExposurePriority.ISO, 400, 10_000_000L);
        loop.setFixed(100, 0, ISO_MIN, ISO_MAX, T_MIN, T_MAX);
        assertEquals(100, loop.iso());
        assertEquals(40_000_000L, loop.shutterNs());
        // the viewfinder's ceiling (1/5 s) bounds the shutter
        loop.setFixed(50, 0, ISO_MIN, ISO_MAX, T_MIN, T_MAX);
        assertEquals(80_000_000L, loop.shutterNs());
    }

    /** A scene of fixed light through a 2.2 gamma: luma of an exposure product. */
    private static double luma(double product, double sceneForProductOne) {
        return Math.min(1, Math.pow(Math.min(1, product * sceneForProductOne), 1 / 2.2));
    }

    @Test
    public void theFreeParameterFollowsTheScene() {
        // AE gave ISO 200 at 1/50 (product 4): luma 0.46 -> the scene constant
        final double k = Math.pow(0.46, 2.2) / 4.0;
        ExposurePriority.Loop loop = new ExposurePriority.Loop(ExposurePriority.SHUTTER, 200, 20_000_000L);
        loop.setFixed(0, 20_000_000L, ISO_MIN, ISO_MAX, T_MIN, T_MAX);
        assertFalse(loop.measured(luma(4, k), 0, ISO_MIN, ISO_MAX, T_MIN, T_MAX)); // learns the target
        assertEquals(0.46, loop.targetLuma(), 1e-9);
        // the light drops 3 stops: the ISO climbs back to ~1600 within a few measurements, the shutter stays
        final double dark = k / 8;
        for (int i = 0; i < 12; i++)
            loop.measured(luma(loop.iso() * loop.shutterNs() / 1e9, dark), 0, ISO_MIN, ISO_MAX, T_MIN, T_MAX);
        assertEquals(20_000_000L, loop.shutterNs());
        assertEquals(1600, loop.iso(), 1600 * 0.13); // inside the dead band (1/6 stop)
        // EV +1: twice the exposure
        for (int i = 0; i < 12; i++)
            loop.measured(luma(loop.iso() * loop.shutterNs() / 1e9, dark), 1, ISO_MIN, ISO_MAX, T_MIN, T_MAX);
        assertEquals(3200, loop.iso(), 3200 * 0.13);
    }

    @Test
    public void isoPriorityMovesTheShutterAndStopsAtItsEnds() {
        final double k = Math.pow(0.46, 2.2) / 4.0;
        ExposurePriority.Loop loop = new ExposurePriority.Loop(ExposurePriority.ISO, 200, 20_000_000L);
        loop.setFixed(200, 0, ISO_MIN, ISO_MAX, T_MIN, T_MAX);
        loop.measured(luma(4, k), 0, ISO_MIN, ISO_MAX, T_MIN, T_MAX);
        // 2 stops brighter: 1/200
        for (int i = 0; i < 12; i++)
            loop.measured(luma(loop.iso() * loop.shutterNs() / 1e9, k * 4), 0, ISO_MIN, ISO_MAX, T_MIN, T_MAX);
        assertEquals(200, loop.iso());
        assertEquals(5_000_000L, loop.shutterNs(), 5_000_000L * 0.13);
        // 10 stops darker: the viewfinder's 1/5 s, no further
        for (int i = 0; i < 20; i++)
            loop.measured(luma(loop.iso() * loop.shutterNs() / 1e9, k / 1024), 0, ISO_MIN, ISO_MAX, T_MIN, T_MAX);
        assertEquals(T_MAX, loop.shutterNs());
    }

    @Test
    public void aClippedFrameStepsDownAndTheDeadBandHolds() {
        assertTrue(ExposurePriority.nextProduct(4, 1.0, 0.46, 0) <= 2.0 + 1e-9);
        assertEquals(4, ExposurePriority.nextProduct(4, 0.47, 0.46, 0), 0); // a twentieth of a stop: no change
        // at most two stops a step
        assertEquals(16, ExposurePriority.nextProduct(4, 0.01, 0.46, 0), 1e-9);
    }

    /** P84: a shutter longer than 1/8 s is exposed after the shutter at the hand-set value; shutter priority moves the ISO. */
    @Test
    public void pslShutterKeepsTheViewfinderExposure() {
        // the owner's 1/8 s, at the viewfinder's 1/10 s ceiling (10 fps, owner 2026-10-11)
        assertEquals(100_000_000L, ParamController.PREVIEW_SHUTTER_MAX_NS);
        assertEquals(100_000_000L, ParamController.PSL_SHUTTER_NS);
        // viewfinder ISO 3200 at 1/5 s, the owner sets 2 s: ISO 320
        assertEquals(320, ParamController.pslIso(3200, 200_000_000L, 2_000_000_000L, ISO_MIN, ISO_MAX));
        // 30 s: ISO 21 -> the module's 50
        assertEquals(ISO_MIN, ParamController.pslIso(3200, 200_000_000L, 30_000_000_000L, ISO_MIN, ISO_MAX));
        assertEquals(ISO_MIN, ParamController.pslIso(0, 0, 2_000_000_000L, ISO_MIN, ISO_MAX));
    }

    @Test
    public void theOtherPriorityKeepsTheLearnedBrightness() {
        ExposurePriority.Loop shutter = new ExposurePriority.Loop(ExposurePriority.SHUTTER, 200, 20_000_000L);
        shutter.measured(0.4, 0, ISO_MIN, ISO_MAX, T_MIN, T_MAX);
        ExposurePriority.Loop iso = new ExposurePriority.Loop(ExposurePriority.ISO, shutter);
        assertEquals(0.4, iso.targetLuma(), 0);
        assertEquals(200, iso.iso());
    }
}
