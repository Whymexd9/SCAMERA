package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Random;

/**
 * P212f (W5 = M13 phase 1): the mosaic white balance's illuminant locus - counted points merged instead of skipped and evicted, a
 * smooth Huber fit instead of the polyline, extrapolation instead of the clamp at its ends, the DNG prior of a camera without a
 * curve; old stored curves load (the M13 VERIFY cases).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class MosaicLocusTest {
    @Before public void setUp() { MosaicNeutral.resetForTest(); }
    @After public void tearDown() { MosaicNeutral.resetForTest(); }

    private static float[] pt(double r, double b, int count) {
        return new float[]{(float) Math.log(r), (float) Math.log(b), count, 0f};
    }

    /**
     * 48 daylight points of OPPO camera 2 as the log shows them (the curve's cool end 0.330 / 0.705): along a locus of slope -1.14
     * warmer from the end by 0.25 ln r, with ±0.006 of scatter and counts 1-64.
     */
    private static ArrayList<float[]> daylight() {
        final ArrayList<float[]> pts = new ArrayList<>();
        final Random rnd = new Random(5);
        final double lr0 = Math.log(0.330), lb0 = Math.log(0.705);
        for (int i = 0; i < 48; i++) {
            final double lr = lr0 + 0.25 * i / 47.0;
            final double lb = lb0 - 1.14 * (lr - lr0) + 0.006 * (rnd.nextDouble() * 2 - 1);
            pts.add(new float[]{(float) lr, (float) lb, 1 + rnd.nextInt(64), 0f});
        }
        return pts;
    }

    /** M13 VERIFY: 0.327 / 0.682 against the 48 daylight points keeps its B/G within 0.012 (the polyline pinned it at 0.705). */
    @Test public void aDaylightMeasurementIsNotPinnedAtTheEnd() {
        MosaicNeutral.curve("pin").addAll(daylight());
        final float[] old = MosaicNeutral.project(MosaicNeutral.curve("pin"), (float) Math.log(0.327), (float) Math.log(0.682));
        assertTrue("P121: the end point " + Math.exp(old[1]), Math.exp(old[1]) >= 0.70);
        for (float conf : new float[]{0f, 1f}) {
            final MosaicNeutral.Result r = MosaicNeutral.resolve("pin", 0.327f, 0.682f, 40_000, conf, null, "measured");
            assertEquals(r.how, 0.682, r.neutral[2], 0.012);
            assertEquals(r.how, 0.327, r.neutral[0], 0.012);
            assertTrue(r.how, r.how.contains("quadratic") || r.how.contains("line"));
        }
    }

    /** M13 VERIFY: a warm lamp beyond the curve's warm end (X200U 30840: 1.073 / 0.280) is extrapolated, not clamped to 0.785 / 0.329. */
    @Test public void aWarmLightIsExtrapolatedNotClamped() {
        final ArrayList<float[]> pts = MosaicNeutral.curve("warm");
        for (int i = 0; i < 10; i++) {
            final double t = i / 9.0, lr = Math.log(0.40) + t * (Math.log(0.785) - Math.log(0.40));
            final double lb = Math.log(0.70) + t * (Math.log(0.329) - Math.log(0.70));
            pts.add(new float[]{(float) lr, (float) lb, 8f, 0f});
        }
        final float[] old = MosaicNeutral.project(pts, (float) Math.log(1.073), (float) Math.log(0.280));
        assertEquals(0.785, Math.exp(old[0]), 0.005);
        final MosaicNeutral.Result r = MosaicNeutral.resolve("warm", 1.073f, 0.280f, 40_000, 0f, null, "measured");
        assertTrue(r.how, r.neutral[0] > 0.9f);
        assertTrue(r.how, r.extrapolated);
        assertTrue(r.how, r.how.contains("extrapolated"));
        // never further than EXTRAPOLATE beyond the end: a sodium-like 3.0 / 0.1 stops there
        final MosaicNeutral.Result far = MosaicNeutral.resolve("warm", 3.0f, 0.10f, 40_000, 0f, null, "measured");
        final double beyond = Math.hypot(Math.log(far.neutral[0] / 0.785), Math.log(far.neutral[2] / 0.329));
        assertTrue(far.how, beyond <= MosaicNeutral.EXTRAPOLATE + 0.01);
    }

    /** M13 VERIFY: a sweep of 0.005 ln steps moves the output by at most 0.01 ln a step (the polyline jumped by 0.02). */
    @Test public void aSweepGivesSmoothOutput() {
        MosaicNeutral.curve("sweep").addAll(daylight());
        double last = Double.NaN, lastB = Double.NaN, worst = 0;
        final double r0 = Math.log(0.30), b0 = Math.log(0.74), r1 = Math.log(0.45), b1 = Math.log(0.58);
        final int steps = (int) Math.ceil(Math.hypot(r1 - r0, b1 - b0) / 0.005);
        for (int i = 0; i <= steps; i++) {
            final double t = i / (double) steps;
            final MosaicNeutral.Result q = MosaicNeutral.resolve("sweep", (float) Math.exp(r0 + t * (r1 - r0)), (float) Math.exp(b0 + t * (b1 - b0)),
                    40_000, 0f, null, "measured");
            final double lr = Math.log(q.neutral[0]), lb = Math.log(q.neutral[2]);
            if (!Double.isNaN(last)) worst = Math.max(worst, Math.hypot(lr - last, lb - lastB));
            last = lr;
            lastB = lb;
        }
        assertTrue("largest step " + worst, worst <= 0.01);
    }

    /** M13 VERIFY: a tungsten point survives 200 daylight learns (the FIFO of 48 forgot it); the closest points merge above 48. */
    @Test public void aTungstenPointSurvivesDaylightLearns() {
        MosaicNeutral.learn("tung", 0.80f, 0.35f);
        final Random rnd = new Random(9);
        for (int i = 0; i < 200; i++)
            MosaicNeutral.learn("tung", 0.34f + 0.12f * rnd.nextFloat(), 0.62f + 0.12f * rnd.nextFloat());
        final ArrayList<float[]> pts = MosaicNeutral.curve("tung");
        assertTrue(pts.size() <= MosaicNeutral.MAX_POINTS);
        boolean tungsten = false;
        int total = 0;
        for (float[] p : pts) {
            tungsten |= Math.hypot(p[0] - Math.log(0.80), p[1] - Math.log(0.35)) < 0.03;
            total += (int) p[2];
        }
        assertTrue("the tungsten point is kept", tungsten);
        assertTrue("every sample counted (counts capped at " + MosaicNeutral.MAX_COUNT + "): " + total, total >= 150);
    }

    /** A sample within the spacing moves its point by the count-weighted mean; 49 distinct points merge the closest two. */
    @Test public void pointsAreCountedAndMerged() {
        MosaicNeutral.learn("count", 0.40f, 0.60f);
        MosaicNeutral.learn("count", 0.41f, 0.60f);
        ArrayList<float[]> pts = MosaicNeutral.curve("count");
        assertEquals(1, pts.size());
        assertEquals(2f, pts.get(0)[2], 0f);
        assertEquals(0.5 * (Math.log(0.40) + Math.log(0.41)), pts.get(0)[0], 1e-5);
        for (int i = 0; i < 49; i++) MosaicNeutral.learn("merge", (float) Math.exp(-1.5 + 0.05 * i), (float) Math.exp(-0.2 - 0.05 * i));
        pts = MosaicNeutral.curve("merge");
        assertEquals(MosaicNeutral.MAX_POINTS, pts.size());
        int total = 0;
        for (float[] p : pts) total += (int) p[2];
        assertEquals(49, total);
    }

    /** Old stored curves (P121: "ln r,ln b;...") load with count 1; the counted format round-trips through the store. */
    @Test public void oldAndNewCurvesLoad() {
        final ArrayList<float[]> old = MosaicNeutral.parse("-1.1090,-0.3500;-0.9000,-0.5000;bad;1,2,3");
        assertEquals(2, old.size());
        assertEquals(1f, old.get(0)[2], 0f);
        assertEquals(-1.109f, old.get(0)[0], 1e-6f);
        final ArrayList<float[]> counted = MosaicNeutral.parse("-1.1090,-0.3500,12,490000.50;-0.9,-0.5,999,1");
        assertEquals(12f, counted.get(0)[2], 0f);
        assertEquals(MosaicNeutral.MAX_COUNT, counted.get(1)[2], 0f);
        final Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("mosaic_awb_curve", 0).edit().clear().putString("9", "-1.1090,-0.3500;-0.4000,-1.0000").commit();
        app.getSharedPreferences("mosaic_awb_locus", 0).edit().clear().commit();
        MosaicNeutral.useContextForTest(app);
        final long[] now = {1_800_000_000_000L};
        MosaicNeutral.clock = () -> now[0];
        assertEquals("a P121 curve of an earlier build", 2, MosaicNeutral.curve("9").size());
        MosaicNeutral.learn("9", 0.40f, 0.60f); // a new point: stored at once
        now[0] += MosaicNeutral.SAVE_EVERY_MS;
        MosaicNeutral.learn("9", 0.40f, 0.60f); // a count: stored at most every 30 s
        MosaicNeutral.forgetForTest(); // a new process
        final ArrayList<float[]> back = MosaicNeutral.curve("9");
        assertEquals(3, back.size());
        boolean two = false;
        for (float[] p : back) two |= p[2] == 2f;
        assertTrue("the counts are stored", two);
        assertEquals("the P121 list is still written", 3,
                MosaicNeutral.parse(app.getSharedPreferences("mosaic_awb_curve", 0).getString("9", "")).size());
    }

    /** OPPO camera 2 (probe characteristics): ColorMatrix1 / 2 (D65 / StdA) and CalibrationTransform1 / 2, row-major. */
    private static final float[] CM1 = {0.6178f, -0.1002f, -0.0970f, -0.4729f, 1.4118f, 0.0345f, -0.0861f, 0.2593f, 0.4441f};
    private static final float[] CM2 = {0.6892f, 0.0196f, -0.3223f, -0.7131f, 1.8376f, -0.1520f, -0.0884f, 0.2258f, 0.6427f};
    private static final float[] CT1 = {127 / 128f, 0, 0, 0, 1, 0, 0, 0, 130 / 128f};
    private static final float[] CT2 = {127 / 128f, 0, 0, 0, 1, 0, 0, 0, 1};

    /**
     * M13 VERIFY: the DNG prior of OPPO camera 2 passes within 0.05 ln of the ISP's 0.408 / 0.614 (probe bin12) and the plain
     * photo's 0.3594 / 0.6709 (log13 L1725); a camera without a curve then lands on it instead of keeping the raw statistic.
     */
    @Test public void theDngPriorOfOppoCameraTwo() {
        final MosaicNeutral.Prior prior = MosaicNeutral.prior(CM1, CT1, 21, CM2, CT2, 17);
        assertNotNull(prior);
        assertEquals("D65", 0.378, Math.exp(prior.r2), 0.005);
        assertEquals("D65", 0.671, Math.exp(prior.b2), 0.005);
        assertEquals("StdA", 0.657, Math.exp(prior.r1), 0.005);
        assertEquals("StdA", 0.357, Math.exp(prior.b1), 0.005);
        final MosaicNeutral.Locus locus = MosaicNeutral.fit(new ArrayList<>(), prior);
        assertNotNull(locus);
        assertTrue(locus.project(Math.log(0.408), Math.log(0.614), 0).distance < 0.05);
        assertTrue(locus.project(Math.log(0.3594), Math.log(0.6709), 0).distance < 0.05);
        final MosaicNeutral.Result r = MosaicNeutral.resolve("fresh", 0.426f, 0.535f, 40_000, 0f, prior, "measured");
        assertTrue(r.how, r.how.contains("DNG prior"));
        assertTrue(r.how, Math.abs(r.distance - locus.project(Math.log(0.426), Math.log(0.535), 0).distance) < 1e-4);
        // without a curve and without a prior: the measurement as it is (as before)
        final MosaicNeutral.Result none = MosaicNeutral.resolve("fresh", 0.426f, 0.535f, 40_000, 0f, null, "measured");
        assertEquals(0.426f, none.neutral[0], 1e-6f);
        assertTrue(none.how, none.how.contains("no curve"));
        // too close a pair, a missing matrix or illuminant: none
        assertNull(MosaicNeutral.prior(CM1, CT1, 21, CM1, CT1, 21));
        assertNull(MosaicNeutral.prior(null, CT1, 21, CM2, CT2, 17));
        assertNull(MosaicNeutral.prior(CM1, CT1, 0, CM2, CT2, 17));
    }

    /** On vivo the DNG matrices give the shape only: anchored at the PD2454 daylight neutral, else it needs a learned point. */
    @Test public void aVivoPriorIsTheShapeOnly() {
        final MosaicNeutral.Prior dng = MosaicNeutral.prior(CM1, CT1, 21, CM2, CT2, 17);
        final MosaicNeutral.Prior shape = MosaicNeutral.anchored(dng, null);
        assertTrue(shape.shapeOnly);
        assertNull("no point, shape only: no locus", MosaicNeutral.fit(new ArrayList<>(), shape));
        final ArrayList<float[]> one = new ArrayList<>();
        one.add(pt(0.45, 0.62, 3));
        final MosaicNeutral.Locus through = MosaicNeutral.fit(one, shape);
        assertNotNull(through);
        assertEquals(dng.slope(), through.a1, 1e-9);
        assertEquals(0, through.project(Math.log(0.45), Math.log(0.62), 0).distance, 1e-6);
        final MosaicNeutral.Prior day = MosaicNeutral.anchored(dng, new float[]{0.3945f, 1f, 0.6396f});
        assertFalse(day.shapeOnly);
        assertEquals(0, MosaicNeutral.fit(new ArrayList<>(), day).project(Math.log(0.3945), Math.log(0.6396), 0).distance, 1e-6);
    }

    /** A confident measurement keeps its tint up to TINT off the locus; a not confident one goes onto it. */
    @Test public void theTintFollowsTheConfidence() {
        MosaicNeutral.curve("tint").addAll(daylight());
        // 0.03 ln above the locus at its middle
        final MosaicNeutral.Locus l = MosaicNeutral.fit(MosaicNeutral.curve("tint"), null);
        final double u = Math.log(0.37), on = l.f(u), s = l.df(u), n = Math.sqrt(1 + s * s);
        final float r = (float) Math.exp(u - s / n * 0.03), b = (float) Math.exp(on + 1 / n * 0.03);
        final MosaicNeutral.Result snapped = MosaicNeutral.resolve("tint", r, b, 40_000, 0f, null, "statistic");
        final MosaicNeutral.Result kept = MosaicNeutral.resolve("tint", r, b, 40_000, 1f, null, "statistic");
        assertEquals(0.03, snapped.distance, 0.002);
        assertEquals(r, kept.neutral[0], 1e-4f);
        assertEquals(b, kept.neutral[2], 1e-4f);
        assertEquals(Math.log(r) + s / n * 0.03, Math.log(snapped.neutral[0]), 0.002);
    }
}
