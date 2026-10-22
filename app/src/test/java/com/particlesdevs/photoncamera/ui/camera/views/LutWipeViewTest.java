package com.particlesdevs.photoncamera.ui.camera.views;

import android.app.Application;
import android.content.Context;
import android.view.SurfaceView;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * P319 (owner, 2026-10-22: «у нас по свайпу по экрану переключаются луты, сделай для этого анимацию»): the wipe never costs a
 * LUT step - without a preview picture to copy (no surface, a laid-out view of size 0) the step runs at once, once per swipe;
 * the view never takes a touch and lets its copy go with the screen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class LutWipeViewTest {
    @Test public void withoutAPictureTheLutStillStepsOncePerSwipe() {
        Context c = RuntimeEnvironment.getApplication();
        LutWipeView wipe = new LutWipeView(c);
        AtomicInteger steps = new AtomicInteger();
        wipe.wipe(null, 1, steps::incrementAndGet);
        assertEquals(1, steps.get());
        SurfaceView unlaid = new SurfaceView(c); // width 0: nothing to copy
        wipe.wipe(unlaid, -1, steps::incrementAndGet);
        wipe.wipe(unlaid, 1, steps::incrementAndGet);
        assertEquals(3, steps.get());
        wipe.release();
        wipe.wipe(null, 1, steps::incrementAndGet);
        assertEquals(4, steps.get());
    }

    @Test public void itNeverTakesATouch() {
        LutWipeView wipe = new LutWipeView(RuntimeEnvironment.getApplication());
        assertFalse(wipe.isClickable());
        assertFalse(wipe.isFocusable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, wipe.getImportantForAccessibility());
    }
}
