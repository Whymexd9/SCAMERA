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
        // a later request the HAL honours at or above the cap clears it
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
}
