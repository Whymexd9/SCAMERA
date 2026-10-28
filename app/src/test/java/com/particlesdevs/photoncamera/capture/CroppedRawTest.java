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

    /** Owner's video 2026-10-28: the tele dialled from 3.5x to 14x (zoom ratio 4 on the ISP streams) - the RAW viewfinder too. */
    @Test
    public void theViewfinderCropFollowsTheZoomRatio() {
        Rect active = new Rect(0, 0, 4080, 3072);
        float[] none = CroppedRaw.viewCrop(active, new Rect(active), 1f, null);
        assertEquals(0f, none[0], 1e-6f);
        assertEquals(1f, none[2], 1e-6f);
        // the result's crop region is the whole array in the zoomed field: the frame's centre quarter is shown
        float[] four = CroppedRaw.viewCrop(active, new Rect(active), 4f, null);
        assertEquals(.375f, four[0], 1e-6f);
        assertEquals(.375f, four[1], 1e-6f);
        assertEquals(.25f, four[2], 1e-6f);
        assertEquals(.25f, four[3], 1e-6f);
        // without a ratio the crop region itself zooms (SCALER_CROP_REGION, before API 30)
        float[] region = CroppedRaw.viewCrop(active, new Rect(1530, 1152, 2550, 1920), 1f, null);
        assertEquals(.375f, region[0], 1e-6f);
        assertEquals(.25f, region[2], 1e-6f);
        // CROPPED_RAW: the HAL read the centre half at full resolution; at 2.5x the viewfinder shows 0.8 of that frame
        Rect half = new Rect(1020, 768, 3060, 2304);
        float[] isz = CroppedRaw.viewCrop(active, new Rect(active), 2.5f, half);
        assertEquals(.1f, isz[0], 1e-5f);
        assertEquals(.8f, isz[2], 1e-5f);
        assertEquals(.8f, isz[3], 1e-5f);
        // at 2x it is the whole cropped frame (no second crop); a 1x trim of the HAL is no frame of its own
        float[] two = CroppedRaw.viewCrop(active, new Rect(active), 2f, half);
        assertEquals(0f, two[0], 1e-5f);
        assertEquals(1f, two[2], 1e-5f);
        float[] trim = CroppedRaw.viewCrop(active, new Rect(active), 1f, new Rect(32, 0, 4048, 3072));
        assertEquals(1f, trim[2], 1e-6f);
    }
}
