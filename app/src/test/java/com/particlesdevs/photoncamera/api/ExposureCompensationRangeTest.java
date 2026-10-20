package com.particlesdevs.photoncamera.api;

import static org.junit.Assert.assertEquals;

import android.util.Range;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** P296: the widened EV range keeps a fine-stepped camera's own range (OPPO Find X8 Ultra: 1/330 EV, +-990 steps). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ExposureCompensationRangeTest {
    @Test
    public void neverNarrowerThanDeclared() {
        assertEquals(new Range<>(-990, 990), Camera2ApiAutoFix.exposureCompensationRange(new Range<>(-990, 990)));
        assertEquals(new Range<>(-24, 24), Camera2ApiAutoFix.exposureCompensationRange(new Range<>(-12, 12)));
        assertEquals(new Range<>(-24, 24), Camera2ApiAutoFix.exposureCompensationRange(null));
    }
}
