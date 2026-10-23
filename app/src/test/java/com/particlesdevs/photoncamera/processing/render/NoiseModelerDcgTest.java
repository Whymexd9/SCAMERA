package com.particlesdevs.photoncamera.processing.render;

import android.util.Pair;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * P320: a DCG mode reports its ISO times its range scale (the X300 Ultra's DCG main: ISO 1336 for the gain of 167, x8), so a
 * chosen noise profile and the no-profile fallback, both keyed by ISO, are evaluated at ISO / dcgIsoScale; the HAL's own
 * profile is used as reported. Parameters.applyDcgIsoScale rebuilds the model once the scale is known (after
 * FillDynamicParameters), idempotently, and leaves every other shot's model as it was.
 */
@RunWith(RobolectricTestRunner.class)
public class NoiseModelerDcgTest {
    private static void assertSameModel(NoiseModeler expected, NoiseModeler actual) {
        for (int c = 0; c < 3; c++) {
            assertEquals(expected.baseModel[c].first, actual.baseModel[c].first, 0);
            assertEquals(expected.baseModel[c].second, actual.baseModel[c].second, 0);
            assertEquals(expected.computeModel[c].first, actual.computeModel[c].first, 0);
            assertEquals(expected.computeModel[c].second, actual.computeModel[c].second, 0);
        }
    }

    @Test public void modelIsoIsTheReportedIsoOverTheScale() {
        assertEquals(167, NoiseModeler.modelIso(1336, 8f));
        assertEquals(1336, NoiseModeler.modelIso(1336, 1f));
        assertEquals(1336, NoiseModeler.modelIso(1336, 0.5f));
        assertEquals(1336, NoiseModeler.modelIso(1336, Float.NaN));
        assertEquals(1336, NoiseModeler.modelIso(1336, Float.POSITIVE_INFINITY));
        assertEquals("at least ISO 1", 1, NoiseModeler.modelIso(3, 8f));
    }

    @Test public void fallbackIsEvaluatedAtTheScaledIso() {
        final NoiseModeler dcg = new NoiseModeler(null, 100, 1336, 0, 8f);
        final NoiseModeler at167 = new NoiseModeler(null, 100, 167, 0);
        assertSameModel(at167, dcg);
        assertEquals("the reported ISO is kept", 1336, dcg.SensivityISO);
        assertEquals(8f, dcg.isoScale, 0f);
        final NoiseModeler reported = new NoiseModeler(null, 100, 1336, 0);
        assertTrue("the reported ISO would model x8 the slope", reported.baseModel[1].first > 4 * dcg.baseModel[1].first);
        assertEquals(1f, reported.isoScale, 0f);
        // a scale of 1 (or none) is the plain model
        assertSameModel(reported, new NoiseModeler(null, 100, 1336, 0, 1f));
    }

    @Test @SuppressWarnings("unchecked") public void halProfileIsUsedAsReported() {
        final Pair<Double, Double>[] hal = new Pair[]{new Pair<>(2e-4, 4e-7), new Pair<>(1.5e-4, 3e-7), new Pair<>(1.7e-4, 3.4e-7),
                new Pair<>(2.5e-4, 5e-7)};
        final NoiseModeler plain = new NoiseModeler(hal, 100, 1336, 0), dcg = new NoiseModeler(hal, 100, 1336, 0, 8f);
        assertSameModel(plain, dcg);
        assertEquals(1.6e-4, dcg.baseModel[1].first, 1e-12);
    }

    @Test public void parametersRebuildTheModelOnlyForADcgShotWithAScale() {
        final NoiseModeler reported = new NoiseModeler(null, 100, 1336, 0);
        final NoiseModeler at167 = new NoiseModeler(null, 100, 167, 0);
        final Parameters p = new Parameters();
        p.applyDcgIsoScale();                                        // no model: nothing to do
        p.noiseModeler = reported;
        p.dcgIsoScale = 8f;
        p.applyDcgIsoScale();
        assertSame("a plain shot keeps its model", reported, p.noiseModeler);
        p.dcgShot = true;
        p.dcgIsoScale = 1f;
        p.applyDcgIsoScale();
        assertSame("a DCG shot of unknown scale keeps its model", reported, p.noiseModeler);
        p.dcgIsoScale = Float.NaN;
        p.applyDcgIsoScale();
        assertSame(reported, p.noiseModeler);
        p.dcgIsoScale = 8f;
        p.applyDcgIsoScale();
        assertNotSame(reported, p.noiseModeler);
        assertSameModel(at167, p.noiseModeler);
        // idempotent: a second call starts from the reported ISO again
        p.applyDcgIsoScale();
        assertSameModel(at167, p.noiseModeler);
        assertEquals(1336, p.noiseModeler.SensivityISO);
        assertEquals(100, p.noiseModeler.AnalogueISO);
    }
}
