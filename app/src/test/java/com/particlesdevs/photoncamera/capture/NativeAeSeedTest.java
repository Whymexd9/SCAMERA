package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Range;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * P212b (M8): the OPPO native AE starts from this camera's own history - its last settled native exposure (stored, 10 min) or a
 * fresh plain RAW sample taken by the native law, whichever is newer; else its plain HAL exposure; never another camera's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class NativeAeSeedTest {
    private static final long WALL = 1_800_000_000_000L;
    private SharedPreferences prefs;

    @Before public void reset() {
        NativeAeSeed.resetForTest();
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("native_ae_seed_test", 0);
        NativeAeSeed.useStoreForTest(prefs);
    }

    private static NativeRawExposure ae() {
        return new NativeRawExposure(new Range<>(10_000L, 1_000_000_000L), new Range<>(100, 6400), CaptureController.NATIVE_FRAME_NS, 0L, 0);
    }

    /** A plain frame metered by the native meter seeds the native law: the AE starts within 0.2 EV of its setpoint. */
    @Test public void aFreshPlainSampleSeedsTheNativeLaw() {
        // plain frame: median 0.03 at 10 ms ISO 100 (1e7 ISO-100 ns), highlights low: the native law wants x4
        NativeAeSeed.plainSample("2", 0.03f, 0.2f, 1e7, 1_000L);
        NativeAeSeed.Seed s = NativeAeSeed.pick("2", 0f, 3_000L, WALL);
        assertEquals(4e7, s.total, 1e4);
        assertTrue(s.source, s.source.startsWith("plain RAW"));
        final NativeRawExposure a = ae();
        a.seed(s.total);
        final double p50 = 0.03 * a.total() / 1e7; // the same scene in the native mode (K = 1)
        assertEquals(0.0, Math.log(p50 / NativeRawExposure.SETPOINT) / Math.log(2), 0.2);
        // clipped highlights: the native law's highlight rule is in the seed too
        NativeAeSeed.plainSample("2", 0.03f, 0.95f, 1e7, 1_000L);
        assertEquals(1e7 * NativeRawExposure.multiplier(0.03f, 0.95f, 0f), NativeAeSeed.pick("2", 0f, 3_000L, WALL).total, 1e3);
        assertEquals("too old", 0.0, NativeAeSeed.pick("2", 0f, 1_000L + NativeAeSeed.PLAIN_MAX_AGE_MS + 1, WALL).total, 0.0);
        assertEquals("another camera's sample: never", 0.0, NativeAeSeed.pick("4", 0f, 3_000L, WALL).total, 0.0);
    }

    /** The last settled native exposure survives the process for ten minutes, moved by the change of the compensation. */
    @Test public void theLastSettledExposureSurvivesTheProcessForTenMinutes() {
        NativeAeSeed.storeSettled("2", 6.67e8, 0f, WALL);
        NativeAeSeed.resetForTest();
        NativeAeSeed.useStoreForTest(prefs); // a new process
        NativeAeSeed.Seed s = NativeAeSeed.pick("2", 0f, 100L, WALL + 60_000L);
        assertEquals(6.67e8, s.total, 1e3);
        assertTrue(s.source, s.source.startsWith("settled"));
        assertEquals("ev +1 since", 2 * 6.67e8, NativeAeSeed.pick("2", 1f, 100L, WALL + 60_000L).total, 1e4);
        assertEquals("11 min old", 0.0, NativeAeSeed.pick("2", 0f, 100L, WALL + 11 * 60_000L).total, 0.0);
        assertEquals("another camera", 0.0, NativeAeSeed.pick("3", 0f, 100L, WALL + 60_000L).total, 0.0);
    }

    /** Of a settled exposure and a plain sample, the newer one: the scene of the other may have changed since. */
    @Test public void theNewerOfTheTwoWins() {
        NativeAeSeed.storeSettled("2", 1e8, 0f, WALL);
        NativeAeSeed.plainSample("2", 0.03f, 0.2f, 1e7, 1_000L);
        // at WALL + 60 s the plain sample is 2 s old: newer than the settled one
        assertEquals(4e7, NativeAeSeed.pick("2", 0f, 3_000L, WALL + 60_000L).total, 1e4);
        // the native session settled after the plain sample (P200: back from the merge)
        NativeAeSeed.storeSettled("2", 1e8, 0f, WALL + 59_000L);
        assertEquals(1e8, NativeAeSeed.pick("2", 0f, 3_000L, WALL + 60_000L).total, 1e3);
    }

    /** Neither: the camera's own plain HAL exposure, else the constructor's default (0). */
    @Test public void withoutSamplesTheCamerasOwnHalExposureThenTheDefault() {
        NativeAeSeed.Seed none = NativeAeSeed.pick("2", 0f, 1_000L, WALL);
        assertEquals(0.0, none.total, 0.0);
        assertEquals("default", none.source);
        NativeAeSeed.plainHal("4", 10_000_000L, 200, 0L);
        assertEquals("another camera's HAL exposure: never", 0.0, NativeAeSeed.pick("2", 0f, 1_000L, WALL).total, 0.0);
        assertEquals(2e7, NativeAeSeed.pick("4", 0f, 1_000L, WALL).total, 1.0);
        final NativeRawExposure a = ae();
        a.seed(none.total);
        assertEquals("the default start stays", 30_000_000L, a.exposureNs());
    }
}
