package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import static org.junit.Assert.*;

public class ScamLabOrientTest {
    @Test public void photoAndSensorCoordinatesAreInversesForEveryCode() {
        for (int code = 0; code < 8; code++) {
            final float[] xy = ScamLabOrient.toPhoto(code, .2f, .7f);
            final float[] uv = ScamLabOrient.toSensor(code, xy[0], xy[1]);
            assertEquals("code " + code, .2f, uv[0], 1e-6f);
            assertEquals("code " + code, .7f, uv[1], 1e-6f);
        }
    }

    @Test public void aQuarterTurnPutsTheSensorsTopLeftAtThePhotosTopRight() {
        final float[] xy = ScamLabOrient.toPhoto(1, 0, 0);
        assertEquals(1, xy[0], 0);
        assertEquals(0, xy[1], 0);
    }

    /** A scene without symmetry, as the sensor sees it, turned by each code: find() names the code. */
    @Test public void theOrientationIsFoundFromThePicturesForEveryCode() {
        final int sw = 64, sh = 48;
        final float[] sensor = new float[sw * sh];
        for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++)
            sensor[y * sw + x] = (float) (x * 0.7 + y * y * 0.05 + (x > 40 && y < 15 ? 30 : 0));
        for (int code = 0; code < 8; code++) {
            final boolean swap = ScamLabOrient.swaps(code);
            final int pw = swap ? 36 : 48, ph = swap ? 48 : 36;
            final float[] photo = new float[pw * ph];
            for (int y = 0; y < ph; y++) for (int x = 0; x < pw; x++) {
                final float[] uv = ScamLabOrient.toSensor(code, (x + .5f) / pw, (y + .5f) / ph);
                photo[y * pw + x] = (float) Math.log(1 + sensor[Math.min(sh - 1, (int) (uv[1] * sh)) * sw + Math.min(sw - 1, (int) (uv[0] * sw))]);
            }
            assertEquals(code, ScamLabOrient.find(sensor, sw, sh, photo, pw, ph));
        }
    }

    @Test public void aFragmentGoesToThePhotoAndBack() {
        final int[] r = {800, 640, 1344, 1024};
        for (int code = 0; code < 8; code++) {
            final float[] p = ScamLabOrient.photoRect(code, r, 4096, 3072);
            final int[] back = ScamLabOrient.sensorRect(code, p, 4096, 3072);
            assertArrayEquals("code " + code, r, back);
        }
    }
}
