package com.particlesdevs.photoncamera.ui.camera;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.ui.camera.views.ZoomReadoutView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * SIMPLE_UI §1, the owner's «свайп по полосе = плавный зум»: a drag sets the target on a log scale (one stop per 70dp), the
 * shown zoom eases to it in log (0.22 per frame), so the steps between frames stay small (≤ ~8 %) and it arrives; the
 * readout's value and its log scale.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w392dp-h871dp-xxhdpi")
public class SmoothZoomTest {
    @Test
    public void aDragSetsTheTargetOnALogScaleWithinTheRange() {
        float stop = 70f;
        assertEquals(2f, CameraFragment.nextZoomTarget(1f, 70f, stop, .4f, 10f), 1e-4f);
        assertEquals(.5f, CameraFragment.nextZoomTarget(1f, -70f, stop, .4f, 10f), 1e-4f);
        assertEquals(1.4142f, CameraFragment.nextZoomTarget(1f, 35f, stop, .4f, 10f), 1e-3f);
        assertEquals("never past the top", 10f, CameraFragment.nextZoomTarget(8f, 700f, stop, .4f, 10f), 0f);
        assertEquals("never under the bottom", .4f, CameraFragment.nextZoomTarget(1f, -700f, stop, .4f, 10f), 0f);
    }

    @Test
    public void theZoomEasesWithoutJumpsAndArrives() {
        float shown = 1f, target = 5f, worst = 0f;
        int frames = 0;
        // a jump of 2.3 stops in one drag: the first frames move most; a target a third of a stop away moves <= 8 %
        while (Math.abs(Math.log(shown) - Math.log(target)) > .0005 && frames < 200) {
            float next = CameraFragment.easeZoom(shown, target);
            worst = Math.max(worst, next / shown);
            shown = next;
            frames++;
        }
        assertEquals(target, shown, 1e-3f);
        assertTrue("arrives within half a second at 60 fps: " + frames, frames <= 30);
        float near = CameraFragment.easeZoom(1f, (float) Math.pow(2, 1 / 3.0));
        assertTrue("a third of a stop: one frame moves " + near, near <= 1.08f);
        assertTrue(worst > 1f);
    }

    @Test
    public void theReadoutShowsTheValueOnTheModulesScale() {
        Context c = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        ZoomReadoutView readout = new ZoomReadoutView(c);
        readout.configure(.4f, 10f, Arrays.asList(.4f, 1f, 2.4f, 5f, 10f));
        assertEquals(View.GONE, readout.getVisibility());
        readout.show(2.5f);
        assertEquals(View.VISIBLE, readout.getVisibility());
        assertEquals(2.5f, readout.getZoom(), 0f);
        readout.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        float d = c.getResources().getDisplayMetrics().density;
        assertTrue("22dp x k high", readout.getMeasuredHeight() >= Math.round(22 * .85f * d) && readout.getMeasuredHeight() <= Math.round(22 * 1.35f * d));
    }
}
