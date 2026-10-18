package com.particlesdevs.photoncamera.ui.camera;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import org.robolectric.RuntimeEnvironment;

import com.particlesdevs.photoncamera.circularbarlib.ui.UiScale;
import com.particlesdevs.photoncamera.ui.camera.views.BottomChrome;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * SIMPLE_UI step 0: one scale factor for the camera chrome, k = clamp(short side / 392dp, 0.85, 1.35), from the window's
 * configuration; BottomChrome uses the same formula.
 */
@RunWith(RobolectricTestRunner.class)
public class UiScaleTest {
    @Test public void theConceptExamples() {
        assertEquals(0.918f, UiScale.k(360f), 1e-3f);
        assertEquals(1f, UiScale.k(392f), 0f);
        assertEquals(1.048f, UiScale.k(411f), 1e-3f);
        assertEquals(1.224f, UiScale.k(480f), 1e-3f);
        assertEquals("clamped below", 0.85f, UiScale.k(300f), 0f);
        assertEquals("clamped above", 1.35f, UiScale.k(700f), 0f);
        assertEquals("unknown", 1f, UiScale.k(0f), 0f);
        assertEquals("one formula", UiScale.k(411f), BottomChrome.scale(411f), 0f);
    }

    @Test @Config(qualifiers = "w411dp-h905dp-xxhdpi") public void theWindowsShortSide() {
        Context c = RuntimeEnvironment.getApplication();
        assertEquals(UiScale.k(411f), UiScale.k(c), 1e-4f);
        assertEquals(Math.round(54 * UiScale.k(411f) * c.getResources().getDisplayMetrics().density), UiScale.dp(c, 54));
    }

    @Test @Config(qualifiers = "w320dp-h690dp-mdpi") public void aSmallWindowIsClamped() {
        assertEquals(0.85f, UiScale.k(RuntimeEnvironment.getApplication()), 0f);
    }
}
