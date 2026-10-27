package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.ImageFormat;
import android.util.Size;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * P365 (owner, 2026-10-27: «В арк камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»): the streams
 * of the RAW viewfinder as the reference app's on the OPPO Find X7 Ultra - RAW10 where the lens's samples fit 10 bits, unpacked
 * into the ZSL ring slot on arrival, and the ISP stream of the 1440x1080 class under the RAW viewfinder.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class RawViewfinderStreamsTest {
    /** The X7 Ultra main camera's SurfaceTexture sizes of 4:3 and 16:9 (sensor-oriented), largest first as the HAL lists them. */
    private static final Size[] X7U = {new Size(4096, 3072), new Size(2560, 1920), new Size(1920, 1440), new Size(1920, 1080),
            new Size(1600, 1200), new Size(1440, 1080), new Size(1280, 960), new Size(1280, 720), new Size(640, 480)};

    @Test
    public void raw10OnlyWhereTheWhiteLevelFitsTenBits() {
        assertTrue("X7 Ultra camera 2: white 1023", CaptureController.raw10Fits(1023));
        assertTrue(CaptureController.raw10Fits(1000));
        assertFalse("a 12-bit lens keeps RAW_SENSOR", CaptureController.raw10Fits(4095));
        assertFalse(CaptureController.raw10Fits(1024));
        assertFalse("unknown: not honoured", CaptureController.raw10Fits(null));
        assertFalse(CaptureController.raw10Fits(0));
    }

    @Test
    public void theRingUnpacksWholePackedGroupsOnly() {
        assertTrue(ZslRing.unpacks(ImageFormat.RAW10, 4096));
        assertTrue(ZslRing.unpacks(ImageFormat.RAW12, 4080));
        assertFalse("16-bit frames are copied as they are", ZslRing.unpacks(ImageFormat.RAW_SENSOR, 4096));
        assertFalse(ZslRing.unpacks(ImageFormat.RAW10, 4094));
        assertFalse(ZslRing.unpacks(ImageFormat.RAW10, 0));
    }

    @Test
    public void theLightIspStreamIsTheSmallestOfTheAspectWithALongSideOf1440() {
        // 4:3 (getAspect: 3 x 4), the usual 1440 x 1920 (portrait) -> 1080 x 1440, the reference app's stream
        assertEquals(new Size(1080, 1440), CaptureController.lightPreviewSize(X7U, new Size(3, 4), new Size(1440, 1920)));
        // 16:9: no 16:9 size between 1440 and 1920 long: the current one stays
        assertEquals(new Size(1080, 1920), CaptureController.lightPreviewSize(X7U, new Size(9, 16), new Size(1080, 1920)));
        // never larger than the current choice, nothing listed: the current one
        assertEquals(new Size(960, 1280), CaptureController.lightPreviewSize(X7U, new Size(3, 4), new Size(960, 1280)));
        assertEquals(new Size(1440, 1920), CaptureController.lightPreviewSize(null, new Size(3, 4), new Size(1440, 1920)));
    }
}
