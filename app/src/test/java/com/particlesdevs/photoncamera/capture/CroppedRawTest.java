package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** P100: the in-sensor zoom of a CROPPED_RAW stream (Galaxy S24 Ultra values, SCAM TEST 2026-05-18). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CroppedRawTest {
    @Test
    public void factorOfTheRawCrop() {
        Rect active = new Rect(0, 0, 4080, 3060);
        assertEquals(1f, CroppedRaw.factor(new Rect(32, 0, 4048, 3060), active), 0f);    // 1x: the HAL's trim, no zoom
        assertEquals(2f, CroppedRaw.factor(new Rect(1020, 765, 3060, 2295), active), 1e-4f); // centre at full resolution
        assertEquals(1f, CroppedRaw.factor(null, active), 0f);
    }

    @Test
    public void framesOfAnotherCropAreNotTheSame() {
        Rect wide = new Rect(32, 0, 4048, 3060), isz = new Rect(1020, 765, 3060, 2295);
        assertTrue(CroppedRaw.same(wide, new Rect(32, 0, 4048, 3060)));
        assertFalse(CroppedRaw.same(wide, isz));
        assertTrue(CroppedRaw.same(null, null));
        assertFalse(CroppedRaw.same(isz, null));
    }
}
