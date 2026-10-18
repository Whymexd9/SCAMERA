package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.particlesdevs.photoncamera.processing.color.MosaicNeutral;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * P212e (W3 = M7): the OPPO native session's white balance - reset and seeded at every session, measured only while the AE holds
 * on frames neither dark nor clipped, a Kalman step with outliers and scene jumps, anchored to the plain neutral it started from;
 * the photo fuses its own estimate with the state recorded at its reference frame.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class OppoNativeWbTest {
    private static final float[] PLAIN = {0.359f, 1f, 0.671f};
    /** Cells of a 50 MP estimate (MosaicNeutral samples ~49k). */
    private static final int CELLS = 49_000;

    @Before public void setUp() { OppoNativeWb.resetForTest(); }
    @After public void tearDown() { OppoNativeWb.resetForTest(); }

    private static float[] n(float r, float b) { return new float[]{r, 1f, b}; }

    /** {@code count} settled, ungated frames at the daylight median: one measurement every EVERY frames of {@code m}. */
    private static void measure(String cam, float[] m, int count, long[] now) {
        for (int i = 0; i < count; i++) {
            for (int f = 0; f < OppoNativeWb.EVERY; f++) {
                now[0] += 42;
                if (OppoNativeWb.wants(cam, false, 3, true, 0.12f, 0.8f, 0.01f)) OppoNativeWb.measure(cam, m, CELLS, true, now[0]);
            }
        }
    }

    /** A new session forgets the last one and starts at the fresh plain neutral (log31: a 2 s old session's average carried over). */
    @Test public void beginResetsAndSeedsFromTheFreshPlainNeutral() {
        final long[] now = {1_000L};
        OppoNativeWb.begin("2", now[0], null, -1, null, null);
        measure("2", n(0.773f, 0.286f), 5, now); // tungsten: the first measurement sets it
        assertEquals(0.773f, OppoNativeWb.neutral("2")[0], 0.01f);
        final String how = OppoNativeWb.begin("2", now[0], PLAIN, 3_000L, null, null);
        assertTrue(how, how.contains("plain neutral 3.0 s old"));
        assertArrayEquals(PLAIN, OppoNativeWb.neutral("2"), 1e-5f);
        // older than FRESH_MS: the stored one; neither: nothing until the first result (lateSeed)
        OppoNativeWb.begin("2", now[0], PLAIN, OppoNativeWb.FRESH_MS + 1, n(0.41f, 0.61f), null);
        assertArrayEquals(n(0.41f, 0.61f), OppoNativeWb.neutral("2"), 1e-5f);
        OppoNativeWb.begin("2", now[0], null, -1, null, n(0.45f, 0.60f));
        assertNull(OppoNativeWb.neutral("2"));
    }

    /**
     * The held AWBFrameControl gains seed only when they are not the 3745 K init signature (camera 2 2.225807 / 2.369458, camera 3
     * 2.0466313 / 2.565555); then the curve middle.
     */
    @Test public void theHeldAwbInitSignatureIsRefusedOnAnyCamera() {
        assertNull(OppoNativeWb.heldNeutral(new float[]{2.225807f, 1f, 2.369458f}, 3745));
        assertNull(OppoNativeWb.heldNeutral(new float[]{2.0466313f, 1f, 2.565555f}, 3745));
        assertNull("no CCT", OppoNativeWb.heldNeutral(new float[]{2.4490006f, 1f, 1.6287329f}, 0));
        assertNull(OppoNativeWb.heldNeutral(new float[]{Float.NaN, 1f, 1.6f}, 5546));
        // right after bin12 the mode-35 run holds bin12's last gains: neutral 0.408 / 0.614
        final float[] held = OppoNativeWb.heldNeutral(new float[]{2.4490006f, 1f, 1.6287329f}, 5546);
        assertArrayEquals(n(0.408f, 0.614f), held, 1e-3f);

        OppoNativeWb.begin("3", 0L, null, -1, null, n(0.45f, 0.60f));
        final String refused = OppoNativeWb.lateSeed("3", new float[]{2.0466313f, 1f, 2.565555f}, 3745, 10L);
        assertTrue(refused, refused.contains("curve middle"));
        assertArrayEquals(n(0.45f, 0.60f), OppoNativeWb.neutral("3"), 1e-5f);
        assertNull("once a session", OppoNativeWb.lateSeed("3", new float[]{2.449f, 1f, 1.6287f}, 5546, 20L));

        OppoNativeWb.begin("2", 0L, null, -1, null, null);
        assertNotNull(OppoNativeWb.lateSeed("2", new float[]{2.4490006f, 1f, 1.6287329f}, 5546, 10L));
        assertArrayEquals(n(0.408f, 0.614f), OppoNativeWb.neutral("2"), 1e-3f);
        OppoNativeWb.begin("4", 0L, null, -1, null, null);
        assertNull("neither: nothing (complete() leaves the HAL's value, the photo keeps its own)",
                OppoNativeWb.lateSeed("4", new float[]{2.225807f, 1f, 2.369458f}, 3745, 10L));
        assertNull(OppoNativeWb.neutral("4"));
    }

    /** M7 VERIFY 1: plain 0.359 / 0.671, then 20 measurements of 0.331 / 0.674 (the estimator's bias): the anchor holds the plain. */
    @Test public void theAnchorHoldsThePlainNeutralWhileTheSceneStays() {
        final long[] now = {1_000L};
        OppoNativeWb.begin("2", now[0], PLAIN, 2_000L, null, null);
        measure("2", n(0.331f, 0.674f), 20, now);
        final float[] out = OppoNativeWb.neutral("2");
        assertEquals(0.359f, out[0], 0.005f);
        assertEquals(0.671f, out[2], 0.005f);
        // without the anchor (the plain neutral 30 s old) the state follows the measurements
        OppoNativeWb.begin("2", now[0], PLAIN, 30_000L, null, null);
        measure("2", n(0.331f, 0.674f), 20, now);
        assertEquals(0.331f, OppoNativeWb.neutral("2")[0], 0.005f);
    }

    /** No anchor while the AE has not settled: the plain neutral holds (log31:200, the first measurement at unsettled AE). */
    @Test public void theAnchorWaitsForTheAe() {
        final long[] now = {1_000L};
        OppoNativeWb.begin("2", now[0], PLAIN, 1_000L, null, null);
        for (int i = 0; i < 10 * OppoNativeWb.EVERY; i++) {
            now[0] += 42;
            if (OppoNativeWb.wants("2", false, 3, false, 0.12f, 0.8f, 0.01f)) OppoNativeWb.measure("2", n(0.755f, 0.298f), CELLS, false, now[0]);
        }
        assertArrayEquals(PLAIN, OppoNativeWb.neutral("2"), 1e-5f);
    }

    /** The gates: AE moving, dark (night p50 0.001), clipped; a settled frame with a few clipped highlights still counts. */
    @Test public void gates() {
        assertEquals("AE", OppoNativeWb.gate(true, 3, false, 0.12f, 0.5f, 0f));
        assertEquals("AE", OppoNativeWb.gate(false, 2, true, 0.12f, 0.5f, 0f));
        assertEquals("dark", OppoNativeWb.gate(false, 3, true, 0.001f, 0.5f, 0f));
        assertEquals("clipped", OppoNativeWb.gate(false, 3, true, 0.12f, 1f, 0.06f));
        assertEquals("an overexposed start", "clipped", OppoNativeWb.gate(false, 3, false, 0.2f, 0.999f, 0.03f));
        assertNull(OppoNativeWb.gate(false, 3, true, 0.12f, 0.999f, 0.03f));
        assertNull("the P192 meter: no clip fraction", OppoNativeWb.gate(false, 3, true, 0.12f, 0.8f, -1f));
        assertNull(OppoNativeWb.gate(false, 3, false, 0.12f, 0.8f, 0.01f));
    }

    /** A gated measurement moves nothing; a gated frame does not use up the cadence (the next ungated frame is measured). */
    @Test public void aGatedFrameMovesNothing() {
        OppoNativeWb.begin("2", 0L, PLAIN, 30_000L, null, null);
        for (int i = 0; i < 40; i++) assertFalse(OppoNativeWb.wants("2", false, 3, true, 0.001f, 0.5f, 0f)); // night
        assertArrayEquals(PLAIN, OppoNativeWb.neutral("2"), 1e-6f);
        assertTrue("the first frame out of the dark", OppoNativeWb.wants("2", false, 3, true, 0.12f, 0.5f, 0f));
        OppoNativeWb.measure("2", n(0.3f, 0.7f), CELLS, true, 100L);
        assertFalse("then EVERY frames later", OppoNativeWb.wants("2", false, 3, true, 0.12f, 0.5f, 0f));
        // too few cells: no step
        OppoNativeWb.begin("2", 0L, PLAIN, 30_000L, null, null);
        assertTrue(OppoNativeWb.wants("2", false, 3, true, 0.12f, 0.5f, 0f));
        OppoNativeWb.measure("2", n(0.9f, 0.2f), 500, true, 100L);
        OppoNativeWb.measure("2", null, 0, true, 200L);
        assertArrayEquals(PLAIN, OppoNativeWb.neutral("2"), 1e-6f);
    }

    /** A tungsten step: three agreeing outliers are a new scene (within 3 updates); one night outlier moves nothing. */
    @Test public void aNewSceneJumpsAnOutlierDoesNot() {
        final long[] now = {1_000L};
        OppoNativeWb.begin("2", now[0], PLAIN, 30_000L, null, null);
        measure("2", PLAIN, 10, now);
        // one outlier (log-2026-10-17 20:46: 0.456 / 0.594 -> 0.343 / 0.755 -> back)
        OppoNativeWb.measure("2", n(0.75f, 0.30f), CELLS, true, now[0] += 400);
        assertArrayEquals(PLAIN, OppoNativeWb.neutral("2"), 0.003f);
        measure("2", PLAIN, 1, now);
        for (int i = 0; i < 3; i++) OppoNativeWb.measure("2", n(0.773f, 0.286f), CELLS, true, now[0] += 400);
        assertArrayEquals(n(0.773f, 0.286f), OppoNativeWb.neutral("2"), 0.01f);
    }

    /** The measurement noise: larger in the dark and with few cells, within its clamp. */
    @Test public void noiseGrowsInTheDarkAndWithFewCells() {
        assertEquals(OppoNativeWb.R_MIN, OppoNativeWb.noise(0.12f, CELLS), 1e-12);
        assertTrue(OppoNativeWb.noise(0.004f, 2_000) > OppoNativeWb.noise(0.04f, 2_000));
        assertTrue(OppoNativeWb.noise(0.01f, 2_000) > OppoNativeWb.noise(0.01f, 20_000));
        assertEquals("the floors of the median and the cells", OppoNativeWb.noise(0.002f, OppoNativeWb.MIN_CELLS),
                OppoNativeWb.noise(0.0001f, 10), 1e-12);
        assertTrue(OppoNativeWb.noise(0.0001f, 10) <= OppoNativeWb.R_MAX);
    }

    /**
     * The photo fuses its own estimate with the prior at its reference frame: between the two, near the established viewfinder
     * (log-2026-10-17 5701 / 6016: viewfinder 0.364 / 0.665, photo 0.330 / 0.705); a photo that joins the outliers the viewfinder
     * had begun to see (a new scene) keeps its own; a hand-set prior holds; no prior or a bad estimate: null (the caller keeps
     * its own).
     */
    @Test public void thePhotoFusesWithTheRecordedPrior() {
        final long[] now = {1_000L};
        OppoNativeWb.begin("2", now[0], n(0.364f, 0.665f), 30_000L, null, null);
        measure("2", n(0.364f, 0.665f), 12, now);
        OppoNativeWb.record("2", 386157783209218L, now[0]);
        final OppoNativeWb.Prior prior = OppoNativeWb.prior(386157783209218L);
        assertNotNull(prior);
        assertNull("another frame", OppoNativeWb.prior(386157783209219L));
        final OppoNativeWb.Fused f = OppoNativeWb.fuse(prior, n(0.330f, 0.705f), CELLS);
        assertNotNull(f);
        assertTrue(f.how, f.neutral[0] < 0.364f && f.neutral[0] > 0.330f && f.neutral[2] > 0.665f && f.neutral[2] < 0.705f);
        assertTrue("near the viewfinder: " + f.how, Math.abs(Math.log(f.neutral[0] / 0.364f)) < 0.3 * Math.abs(Math.log(0.330 / 0.364))
                && Math.abs(Math.log(f.neutral[2] / 0.665f)) < 0.3 * Math.abs(Math.log(0.705 / 0.665)));
        // the scene changed just before the frame: the viewfinder had seen one outlier of it, the photo joins it - its own
        OppoNativeWb.measure("2", n(0.773f, 0.286f), CELLS, true, now[0] += 400);
        OppoNativeWb.record("2", 386157783209300L, now[0]);
        final OppoNativeWb.Fused change = OppoNativeWb.fuse(OppoNativeWb.prior(386157783209300L), n(0.77f, 0.29f), CELLS);
        assertArrayEquals(change.how, n(0.77f, 0.29f), change.neutral, 1e-4f);
        assertTrue(change.how, change.how.contains("new scene"));
        assertNull(OppoNativeWb.fuse(prior, n(Float.NaN, 0.7f), CELLS));
        assertNull(OppoNativeWb.fuse(null, n(0.33f, 0.7f), CELLS));
        // a hand-set white balance is the photo's
        OppoNativeWb.hand("2", n(0.5f, 0.5f));
        assertArrayEquals(n(0.5f, 0.5f), OppoNativeWb.neutral("2"), 1e-6f);
        OppoNativeWb.record("2", 2L, now[0]);
        assertArrayEquals(n(0.5f, 0.5f), OppoNativeWb.fuse(OppoNativeWb.prior(2L), n(0.33f, 0.70f), CELLS).neutral, 1e-4f);
        OppoNativeWb.hand("2", null);
        assertEquals("automatic again: the filter's state", 0.364f, OppoNativeWb.neutral("2")[0], 0.003f);
    }

    /** Fusion never leaves the segment between the prior and the (mapped) estimate, and never gives NaN. */
    @Test public void fusionIsBounded() {
        final java.util.Random rnd = new java.util.Random(5);
        for (int i = 0; i < 200; i++) {
            final OppoNativeWb.Prior p = new OppoNativeWb.Prior("2", rnd.nextGaussian() * 0.5 - 0.9, rnd.nextGaussian() * 0.5 - 0.4,
                    Math.pow(10, -6 + 5 * rnd.nextDouble()), false, 0, 0, 0, 0, 0, (float) rnd.nextDouble() * 0.2f, false);
            final float[] own = n((float) Math.exp(rnd.nextGaussian() * 0.5 - 0.9), (float) Math.exp(rnd.nextGaussian() * 0.5 - 0.4));
            final OppoNativeWb.Fused f = OppoNativeWb.fuse(p, own, 1000 + rnd.nextInt(60_000));
            if (f == null) continue;
            final double fr = Math.log(f.neutral[0]), zr = Math.log(own[0]);
            assertFalse(Float.isNaN(f.neutral[0]) || Float.isNaN(f.neutral[2]));
            assertTrue(f.how, fr >= Math.min(p.lr, zr) - 1e-4 && fr <= Math.max(p.lr, zr) + 1e-4);
        }
    }

    /** The priors are kept for the last PRIORS frames only. */
    @Test public void priorsAreBounded() {
        OppoNativeWb.begin("2", 0L, PLAIN, 0L, null, null);
        for (long t = 0; t < OppoNativeWb.PRIORS + 10; t++) OppoNativeWb.record("2", t, 0L);
        assertNull(OppoNativeWb.prior(0L));
        assertNotNull(OppoNativeWb.prior(OppoNativeWb.PRIORS + 9));
        OppoNativeWb.record("9", 1_000_000L, 0L);
        assertNull("no session of that camera", OppoNativeWb.prior(1_000_000L));
    }

    /** The curve middle (the last prior): the median point by colour temperature; nothing before init (no empty curve cached). */
    @Test public void theCurveMiddle() {
        assertNull(MosaicNeutral.middle("wb-test-cam"));
        MosaicNeutral.init(RuntimeEnvironment.getApplication());
        MosaicNeutral.learn("wb-test-cam", 0.40f, 0.62f);
        MosaicNeutral.learn("wb-test-cam", 0.55f, 0.45f);
        MosaicNeutral.learn("wb-test-cam", 0.77f, 0.29f);
        assertArrayEquals(n(0.55f, 0.45f), MosaicNeutral.middle("wb-test-cam"), 1e-3f);
    }
}
