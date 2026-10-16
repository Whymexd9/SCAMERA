package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.app.Application;
import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** P176: the frames of the shade growing out of its pill - on the pill at the start, in place at the end, never larger. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ShadeZoomTest {
    @Test
    public void startsOnThePillAndEndsInPlace() {
        RectF pill = new RectF(142, 1300, 218, 1328), sheet = new RectF(0, 900, 360, 1400);
        float[] start = ShadeZoom.frame(pill, sheet, 0f);
        assertEquals("as wide as the pill", pill.width() / sheet.width(), start[0], 1e-5f);
        // the scaled sheet's centre (scaled about the view's top left) sits on the pill's centre
        assertEquals(pill.centerX(), sheet.left + start[1] + start[0] * sheet.width() / 2f, 1e-3f);
        assertEquals(pill.centerY(), sheet.top + start[2] + start[0] * sheet.height() / 2f, 1e-3f);
        assertTrue("faint at first", start[3] < 0.3f);
        float[] end = ShadeZoom.frame(pill, sheet, 1f);
        assertEquals(1f, end[0], 0f);
        assertEquals(0f, end[1], 1e-4f);
        assertEquals(0f, end[2], 1e-4f);
        assertEquals(1f, end[3], 0f);
        float last = 0f;
        for (int i = 0; i <= 20; i++) {
            float[] f = ShadeZoom.frame(pill, sheet, ShadeZoom.OPEN.getInterpolation(i / 20f));
            assertTrue("grows steadily", f[0] >= last - 1e-6f && f[0] <= 1f + 1e-6f);
            last = f[0];
        }
        assertTrue("quickly visible: half way in a quarter of the time",
                ShadeZoom.OPEN.getInterpolation(0.25f) > 0.5f);
    }
}
