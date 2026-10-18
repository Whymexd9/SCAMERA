package com.particlesdevs.photoncamera.capture;

import android.app.Application;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** P27: manual exposure limits are learned only from repeated clamping and forgotten when the HAL honours the request. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class ExposureLimitsTest {
    @Before public void reset() { ExposureLimits.resetForTest(); }

    private static CaptureRequest manual(long ns, int iso) {
        CaptureRequest q = mock(CaptureRequest.class);
        when(q.get(CaptureRequest.CONTROL_AE_MODE)).thenReturn(CaptureRequest.CONTROL_AE_MODE_OFF);
        when(q.get(CaptureRequest.SENSOR_EXPOSURE_TIME)).thenReturn(ns);
        when(q.get(CaptureRequest.SENSOR_SENSITIVITY)).thenReturn(iso);
        return q;
    }
    private static CaptureResult result(long ns, int iso) {
        CaptureResult r = mock(CaptureResult.class);
        when(r.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(ns);
        when(r.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(iso);
        return r;
    }

    @Test public void oneClampedResultSetsNoCapTwoDo() {
        ExposureLimits l = ExposureLimits.of("2", 4096, 3072, false);
        assertFalse(l.observeManual(manual(10_000_000L, 640), result(10_000_000L, 320)));
        assertEquals(Integer.MAX_VALUE, l.isoCap());
        l.observeManual(manual(10_000_000L, 640), result(10_000_000L, 320));
        assertEquals(320, l.isoCap());
        // a request at the cap itself, honoured, keeps it (P124: the cap flapped 6400 <-> none on the OPPO Find X7 Ultra)
        assertTrue(l.observeManual(manual(10_000_000L, 320), result(10_000_000L, 320)));
        assertEquals(320, l.isoCap());
        // a later request the HAL honours above the cap clears it
        assertTrue(l.observeManual(manual(10_000_000L, 400), result(10_000_000L, 400)));
        assertEquals(Integer.MAX_VALUE, l.isoCap());
    }

    @Test public void shorterReturnedShutterTwiceSetsAShutterCap() {
        ExposureLimits l = ExposureLimits.of("3", 4096, 3072, false);
        l.observeManual(manual(125_000_000L, 1000), result(100_000_000L, 1000));
        assertEquals(Long.MAX_VALUE, l.shutterCap());
        l.observeManual(manual(125_000_000L, 1000), result(100_000_000L, 1000));
        assertEquals(100_000_000L, l.shutterCap());
    }

    @Test public void autoExposureResultsAndSensorRoundingTeachNothing() {
        ExposureLimits l = ExposureLimits.of("4", 4096, 3072, false);
        CaptureRequest auto = mock(CaptureRequest.class);
        when(auto.get(CaptureRequest.CONTROL_AE_MODE)).thenReturn(CaptureRequest.CONTROL_AE_MODE_ON);
        for (int i = 0; i < 3; i++) l.observeManual(auto, result(10_000_000L, 100));
        for (int i = 0; i < 3; i++) assertTrue(l.observeManual(manual(10_000_000L, 640), result(10_000_000L, 636)));
        // X100 Ultra log: ISO raised (not lowered) at a very short shutter is no gain cap
        for (int i = 0; i < 3; i++) l.observeManual(manual(73_808L, 50), result(73_808L, 60));
        assertEquals(Integer.MAX_VALUE, l.isoCap());
        assertSame(l, ExposureLimits.of("4", 4096, 3072, false));
        assertNotSame(l, ExposureLimits.of("4", 4096, 3072, true));
    }

    /**
     * P212a (log28 23:00:45.162, 'ISO cap -' with no ISO above 1600 delivered): a request just above the cap answered at the cap
     * within the rounding keeps the cap; only a result delivered above it clears it - also one that gives less than asked.
     */
    @Test public void onlyADeliveredIsoAboveTheCapClearsIt() {
        ExposureLimits l = ExposureLimits.of("2", 8192, 6144, false);
        l.observeManual(manual(40_000_000L, 6400), result(40_000_000L, 1600));
        l.observeManual(manual(40_000_000L, 6400), result(40_000_000L, 1600));
        assertEquals(1600, l.isoCap());
        for (int asked = 1601; asked <= 1624; asked++) {
            assertTrue(l.observeManual(manual(40_000_000L, asked), result(40_000_000L, 1600)));
            assertEquals("asked " + asked, 1600, l.isoCap());
        }
        // the once-a-session probe answered above the cap (a firmware that gives more): the cap goes
        assertFalse(l.observeManual(manual(40_000_000L, 2400), result(40_000_000L, 2000)));
        assertEquals(Integer.MAX_VALUE, l.isoCap());
    }

    private static android.content.SharedPreferences prefs() {
        return org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("exposure_limits_test", 0);
    }

    private static void learn1600(ExposureLimits l) {
        l.observeManual(manual(40_000_000L, 6400), result(40_000_000L, 1600));
        l.observeManual(manual(40_000_000L, 6400), result(40_000_000L, 1600));
        assertEquals(1600, l.isoCap());
    }

    /** P212a: a learned cap survives the process (the first shot of the next start plans against it), on the same firmware only. */
    @Test public void aLearnedCapSurvivesTheProcessOnTheSameFirmware() {
        final long[] now = {1_800_000_000_000L};
        final android.content.SharedPreferences p = prefs();
        ExposureLimits.useStoreForTest(p, "fw-1", () -> now[0]);
        learn1600(ExposureLimits.of("2", 8192, 6144, false));
        // a new process, the same firmware: the cap is there before any result
        ExposureLimits.resetForTest();
        ExposureLimits.useStoreForTest(p, "fw-1", () -> now[0]);
        assertEquals(1600, ExposureLimits.of("2", 8192, 6144, false).isoCap());
        assertEquals("another mode of the camera", Integer.MAX_VALUE, ExposureLimits.of("2", 4096, 3072, false).isoCap());
        // another firmware: forgotten, and the stored line with it
        ExposureLimits.resetForTest();
        ExposureLimits.useStoreForTest(p, "fw-2", () -> now[0]);
        assertEquals(Integer.MAX_VALUE, ExposureLimits.of("2", 8192, 6144, false).isoCap());
        ExposureLimits.resetForTest();
        ExposureLimits.useStoreForTest(p, "fw-1", () -> now[0]);
        assertEquals("dropped for good", Integer.MAX_VALUE, ExposureLimits.of("2", 8192, 6144, false).isoCap());
    }

    /** P212a: a stored cap not learned or confirmed for 14 days is forgotten; a cleared cap is cleared in the store too. */
    @Test public void aStoredCapExpiresAndAClearedOneIsGone() {
        final long[] now = {1_800_000_000_000L};
        final android.content.SharedPreferences p = prefs();
        ExposureLimits.useStoreForTest(p, "fw-1", () -> now[0]);
        learn1600(ExposureLimits.of("2", 8192, 6144, false));
        now[0] += 13L * 24 * 3600 * 1000;
        ExposureLimits.resetForTest();
        ExposureLimits.useStoreForTest(p, "fw-1", () -> now[0]);
        ExposureLimits kept = ExposureLimits.of("2", 8192, 6144, false);
        assertEquals("13 days", 1600, kept.isoCap());
        // clamped at the cap again (the probe): confirmed, the 14 days count from now
        kept.observeManual(manual(40_000_000L, 2400), result(40_000_000L, 1600));
        now[0] += 13L * 24 * 3600 * 1000;
        ExposureLimits.resetForTest();
        ExposureLimits.useStoreForTest(p, "fw-1", () -> now[0]);
        assertEquals("confirmed 13 days ago", 1600, ExposureLimits.of("2", 8192, 6144, false).isoCap());
        now[0] += 2L * 24 * 3600 * 1000;
        ExposureLimits.resetForTest();
        ExposureLimits.useStoreForTest(p, "fw-1", () -> now[0]);
        assertEquals("15 days", Integer.MAX_VALUE, ExposureLimits.of("2", 8192, 6144, false).isoCap());
        // learned again, then cleared by a delivered ISO above it: the next process has none
        ExposureLimits l = ExposureLimits.of("2", 8192, 6144, false);
        learn1600(l);
        l.observeManual(manual(40_000_000L, 3200), result(40_000_000L, 3200));
        assertEquals(Integer.MAX_VALUE, l.isoCap());
        ExposureLimits.resetForTest();
        ExposureLimits.useStoreForTest(p, "fw-1", () -> now[0]);
        assertEquals(Integer.MAX_VALUE, ExposureLimits.of("2", 8192, 6144, false).isoCap());
    }
}
