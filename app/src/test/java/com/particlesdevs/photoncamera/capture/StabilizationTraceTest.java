package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P38: the STAB_TRACE aggregates and the before / after reading of a shot (vivo X300 Ultra stabilisation lost after a shot). */
public class StabilizationTraceTest {
    private static StabilizationTrace.Frame frame(long ts, int ois, int vs, float gyroAngle, float oisRange, int cropL) {
        StabilizationTrace.Frame f = new StabilizationTrace.Frame();
        f.sensorTs = ts; f.resOis = ois; f.resVs = vs;
        f.gyroN = 4; f.gyroRms = gyroAngle * 30; f.gyroAngle = gyroAngle;
        f.oisCount = 5; f.oisRangeX = oisRange; f.oisRangeY = 0;
        f.cropL = cropL; f.cropT = 0; f.cropW = 4000; f.cropH = 3000;
        return f;
    }

    @Test
    public void oisThatStopsFollowingTheHandIsReportedEvenWhenItsKeyStaysOn() {
        StabilizationTrace.Stats before = new StabilizationTrace.Stats(), after = new StabilizationTrace.Stats();
        for (int i = 0; i < 30; i++) before.add(frame(i * 33_000_000L, 1, 1, 0.002f, 4f, i % 3));
        for (int i = 0; i < 30; i++) after.add(frame((40 + i) * 33_000_000L, 1, 1, 0.002f, 0.2f, i % 3));
        assertEquals(2000, before.compensation(), 1);
        assertEquals(100, after.compensation(), 1);
        String v = StabilizationTrace.Stats.verdict(before, after);
        assertTrue(v, v.contains("OIS stopped compensating"));
        assertFalse(v, v.contains("OIS key off"));
    }

    @Test
    public void keysSwitchedOffAndAFrozenCropAreReported() {
        StabilizationTrace.Stats before = new StabilizationTrace.Stats(), after = new StabilizationTrace.Stats();
        for (int i = 0; i < 30; i++) before.add(frame(i * 33_000_000L, 1, 1, 0.002f, 4f, i % 3));
        for (int i = 0; i < 30; i++) after.add(frame((40 + i) * 33_000_000L, 0, 0, 0.002f, 4f, 7));
        String v = StabilizationTrace.Stats.verdict(before, after);
        assertTrue(v, v.contains("OIS key off"));
        assertTrue(v, v.contains("EIS key off"));
        assertTrue(v, v.contains("EIS crop frozen"));
        assertEquals(29, before.cropMoves);
    }

    @Test
    public void aStillHandGivesNoCompensationAndAStableShotNoFinding() {
        StabilizationTrace.Stats before = new StabilizationTrace.Stats(), after = new StabilizationTrace.Stats();
        for (int i = 0; i < 20; i++) before.add(frame(i * 33_000_000L, 1, 0, 0f, 0f, 0));
        for (int i = 0; i < 20; i++) after.add(frame((25 + i) * 33_000_000L, 1, 0, 0f, 0f, 0));
        assertEquals(-1, before.compensation(), 0);
        assertEquals("no change seen in the keys / OIS data", StabilizationTrace.Stats.verdict(before, after));
    }

    @Test
    public void aPreviewGapAfterTheShotIsReported() {
        StabilizationTrace.Stats before = new StabilizationTrace.Stats(), after = new StabilizationTrace.Stats();
        for (int i = 0; i < 20; i++) before.add(frame(i * 33_000_000L, 1, 1, 0.001f, 2f, i % 2));
        after.add(frame(1_000_000_000L, 1, 1, 0.001f, 2f, 0));
        after.add(frame(1_350_000_000L, 1, 1, 0.001f, 2f, 1));
        assertEquals(350, after.maxGapMs, 0.01);
        assertTrue(StabilizationTrace.Stats.verdict(before, after).contains("preview gap 350 ms"));
    }

    @Test
    public void stabilisationKeyNamesAreFoundButNoiseKeysAreNot() {
        assertTrue(StabilizationTrace.isStabName("vivo.control.ois.mode"));
        assertTrue(StabilizationTrace.isStabName("com.vivo.enableOis"));
        assertTrue(StabilizationTrace.isStabName("org.quic.camera.EISLookAhead.EISLookAhead"));
        assertTrue(StabilizationTrace.isStabName("android.control.videoStabilizationMode"));
        assertTrue(StabilizationTrace.isStabName("vivo.gyro.data"));
        assertFalse(StabilizationTrace.isStabName("android.noiseReduction.mode"));
        assertFalse(StabilizationTrace.isStabName("vivo.NOISE_LEVEL"));
        assertFalse(StabilizationTrace.isStabName("vivo.control.relighting"));
    }
}
