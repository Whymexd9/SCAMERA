package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertEquals;
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

/**
 * P212f (W5 = M13 phase 2, log only): the ISP teacher - plain-session pairs (our statistic m, the ISP's neutral n) kept as ln n -
 * ln m in a sparse grid over ln m, predicted with a Gaussian kernel, stored per camera.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class MosaicTeacherTest {
    @Before public void setUp() { MosaicTeacher.resetForTest(); }
    @After public void tearDown() { MosaicTeacher.resetForTest(); }

    /** The OPPO daylight offset of the statistic against the ISP (about -8 % R/G, +5 % B/G): ln n - ln m. */
    private static final double DR = Math.log(0.92), DB = Math.log(1.05);

    /** Pairs over daylight statistics m (0.30-0.40 / 0.64-0.74), all with the constant residual (DR, DB). */
    private static void teach(String id, int pairs) {
        for (int i = 0; i < pairs; i++) {
            final float mr = 0.30f + 0.10f * ((i * 7) % 23) / 22f, mb = 0.64f + 0.10f * ((i * 11) % 19) / 18f;
            MosaicTeacher.observe(id, mr, mb, (float) (mr * Math.exp(DR)), (float) (mb * Math.exp(DB)));
        }
    }

    /** M13 VERIFY: a constant synthetic residual is applied exactly; the support and the confidence grow with the pairs. */
    @Test public void aConstantResidualIsAppliedExactly() {
        teach("2", 200);
        final MosaicTeacher.Prediction p = MosaicTeacher.predict("2", 0.35f, 0.69f);
        assertNotNull(p);
        assertEquals(0.35 * Math.exp(DR), p.r, 1e-4);
        assertEquals(0.69 * Math.exp(DB), p.b, 1e-4);
        assertTrue(p.toString(), p.support >= MosaicTeacher.FULL_SUPPORT);
        assertEquals(1.0, p.confidence, 0);
        assertNull("nothing learned near a tungsten statistic", MosaicTeacher.predict("2", 0.80f, 0.33f));
        assertNull("another camera", MosaicTeacher.predict("3", 0.35f, 0.69f));
    }

    /** One pair is support 1: a prediction without confidence; observe returns the prediction made before the pair. */
    @Test public void aFewPairsHaveNoConfidence() {
        assertNull(MosaicTeacher.observe("4", 0.35f, 0.69f, 0.33f, 0.72f));
        final MosaicTeacher.Prediction before = MosaicTeacher.observe("4", 0.35f, 0.69f, 0.33f, 0.72f);
        assertNotNull(before);
        assertEquals(0.33f, before.r, 1e-4f);
        assertEquals(0.0, before.confidence, 0);
        assertNull("not a neutral", MosaicTeacher.observe("4", Float.NaN, 0.69f, 0.33f, 0.72f));
    }

    /** The grid holds at most MAX_CELLS cells a camera and a cell's mean follows its last COUNT_CAP pairs. */
    @Test public void theGridIsBounded() {
        for (int i = 0; i < MosaicTeacher.MAX_CELLS + 100; i++) {
            final float mr = (float) Math.exp(-2 + MosaicTeacher.CELL * (i % 60)), mb = (float) Math.exp(-2 + MosaicTeacher.CELL * (i / 60));
            MosaicTeacher.observe("5", mr, mb, mr, mb);
        }
        assertEquals(MosaicTeacher.MAX_CELLS, MosaicTeacher.cells("5"));
        for (int i = 0; i < 500; i++) MosaicTeacher.observe("6", 0.35f, 0.69f, 0.35f, 0.69f);
        for (int i = 0; i < MosaicTeacher.COUNT_CAP; i++) MosaicTeacher.observe("6", 0.35f, 0.69f, (float) (0.35 * Math.exp(0.1)), 0.69f);
        final MosaicTeacher.Prediction p = MosaicTeacher.predict("6", 0.35f, 0.69f);
        assertTrue("the new residual dominates: " + p, Math.log(p.r / 0.35) > 0.05);
    }

    /** The grid is stored per camera and a new process predicts the same. */
    @Test public void theGridIsStored() {
        final Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("mosaic_awb_teacher", 0).edit().clear().commit();
        MosaicTeacher.useContextForTest(app);
        final long[] now = {1_800_000_000_000L};
        MosaicTeacher.clock = () -> now[0];
        teach("2", 40);
        now[0] += MosaicTeacher.SAVE_EVERY_MS;
        teach("2", 1);
        final MosaicTeacher.Prediction before = MosaicTeacher.predict("2", 0.35f, 0.69f);
        MosaicTeacher.forgetForTest();
        final MosaicTeacher.Prediction after = MosaicTeacher.predict("2", 0.35f, 0.69f);
        assertNotNull(after);
        assertEquals(before.r, after.r, 1e-4f);
        assertEquals(before.b, after.b, 1e-4f);
        assertEquals(before.support, after.support, 1e-6);
    }
}
