package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.app.Application;
import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P176 / P185: the frames of the shade coming out of its pill - a container transform: the shape IS the pill at the start
 * (its rectangle and radius), the shade's visible part at the end, growing steadily, the content fitted to its width.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ShadeZoomTest {
    private static final RectF PILL = new RectF(142, 1300, 218, 1328), SHEET = new RectF(0, 900, 360, 1340);
    private static final float PILL_R = 14, SHEET_R = 26;

    @Test
    public void startsAsThePillItself() {
        float[] f = ShadeZoom.frame(PILL, SHEET, 0f, PILL_R, SHEET_R);
        assertEquals(PILL.left, f[ShadeZoom.LEFT], 1e-4f);
        assertEquals(PILL.top, f[ShadeZoom.TOP], 1e-4f);
        assertEquals(PILL.right, f[ShadeZoom.RIGHT], 1e-4f);
        assertEquals(PILL.bottom, f[ShadeZoom.BOTTOM], 1e-4f);
        assertEquals("the pill's radius", PILL_R, f[ShadeZoom.RADIUS], 1e-4f);
        assertEquals("the content fitted to the pill's width", PILL.width() / SHEET.width(), f[ShadeZoom.SCALE], 1e-5f);
        assertEquals("an empty pill: no content yet", 0f, f[ShadeZoom.CONTENT], 0f);
    }

    @Test
    public void endsInPlace() {
        float[] f = ShadeZoom.frame(PILL, SHEET, 1f, PILL_R, SHEET_R);
        assertEquals(SHEET.left, f[ShadeZoom.LEFT], 1e-4f);
        assertEquals(SHEET.top, f[ShadeZoom.TOP], 1e-4f);
        assertEquals(SHEET.right, f[ShadeZoom.RIGHT], 1e-4f);
        assertEquals(SHEET.bottom, f[ShadeZoom.BOTTOM], 1e-4f);
        assertEquals(SHEET_R, f[ShadeZoom.RADIUS], 1e-4f);
        assertEquals(1f, f[ShadeZoom.SCALE], 0f);
        assertEquals(0f, f[ShadeZoom.TX], 1e-4f);
        assertEquals(0f, f[ShadeZoom.TY], 1e-4f);
        assertEquals(1f, f[ShadeZoom.CONTENT], 0f);
        RectF local = ShadeZoom.localClip(f, SHEET.left, SHEET.top);
        assertEquals(new RectF(0, 0, SHEET.width(), SHEET.height()), local);
    }

    @Test
    public void growsSteadilyWithTheContentOnTheShapesTopCentre() {
        float width = 0f, height = 0f;
        for (int i = 0; i <= 20; i++) {
            float[] f = ShadeZoom.frame(PILL, SHEET, ShadeZoom.OPEN.getInterpolation(i / 20f), PILL_R, SHEET_R);
            float w = f[ShadeZoom.RIGHT] - f[ShadeZoom.LEFT], h = f[ShadeZoom.BOTTOM] - f[ShadeZoom.TOP];
            assertTrue("grows steadily", w >= width - 1e-4f && h >= height - 1e-4f);
            width = w;
            height = h;
            // never beyond where the shade ends (its part below the visible one never shows)
            assertTrue(f[ShadeZoom.BOTTOM] <= Math.max(PILL.bottom, SHEET.bottom) + 1e-4f);
            float s = f[ShadeZoom.SCALE];
            assertEquals("the content's top centre on the shape's", (f[ShadeZoom.LEFT] + f[ShadeZoom.RIGHT]) / 2f,
                    SHEET.left + f[ShadeZoom.TX] + s * SHEET.width() / 2f, 1e-3f);
            assertEquals(f[ShadeZoom.TOP], SHEET.top + f[ShadeZoom.TY], 1e-3f);
            // the local clip maps back onto the shape
            RectF local = ShadeZoom.localClip(f, SHEET.left, SHEET.top);
            assertEquals(f[ShadeZoom.LEFT], SHEET.left + f[ShadeZoom.TX] + s * local.left, 1e-3f);
            assertEquals(f[ShadeZoom.BOTTOM], SHEET.top + f[ShadeZoom.TY] + s * local.bottom, 1e-3f);
        }
        assertTrue("quickly visible: half way in a quarter of the time", ShadeZoom.OPEN.getInterpolation(0.25f) > 0.5f);
    }
}
