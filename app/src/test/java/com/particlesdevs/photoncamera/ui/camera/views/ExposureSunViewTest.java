package com.particlesdevs.photoncamera.ui.camera.views;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiScale;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * SIMPLE_UI §1: the brightness sun beside the focus ring - 136dp x k, 0 EV in the middle, a drag up brightens in thirds of
 * a stop within the camera's range, it sits right of the ring (left where the right has no room).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w392dp-h871dp-xxhdpi")
public class ExposureSunViewTest {
    private final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);

    private ExposureSunView sun() {
        ExposureSunView sun = new ExposureSunView(context);
        sun.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        sun.layout(0, 0, sun.getMeasuredWidth(), sun.getMeasuredHeight());
        return sun;
    }

    private static MotionEvent ev(int action, long t, float x, float y) {
        return MotionEvent.obtain(0, t, action, x, y, 0);
    }

    @Test
    public void aDragUpBrightensInThirdsWithinTheRange() {
        ExposureSunView sun = sun();
        assertEquals(UiScale.dp(context, ExposureSunView.HEIGHT_DP), sun.getHeight());
        sun.setRange(-2f, 2f);
        sun.setEv(0f);
        assertEquals(sun.getHeight() / 2f, sun.knobY(), .5f);
        List<Float> evs = new ArrayList<>();
        sun.setListener(evs::add);
        float x = UiScale.dpF(context, 14), y = sun.knobY(), perEv = sun.pxPerEv();
        assertTrue(sun.onTouchEvent(ev(MotionEvent.ACTION_DOWN, 0, x, y)));
        sun.onTouchEvent(ev(MotionEvent.ACTION_MOVE, 16, x, y - perEv));
        assertEquals(1f, sun.getEv(), 1e-4f);
        assertEquals(1f, evs.get(evs.size() - 1), 1e-4f);
        sun.onTouchEvent(ev(MotionEvent.ACTION_MOVE, 32, x, y - perEv * 0.7f));
        assertEquals(.6667f, sun.getEv(), 1e-3f);   // thirds of a stop
        sun.onTouchEvent(ev(MotionEvent.ACTION_MOVE, 48, x, y - perEv * 10));
        assertEquals("never past the range", 2f, sun.getEv(), 1e-4f);
        sun.onTouchEvent(ev(MotionEvent.ACTION_UP, 64, x, y - perEv * 10));
        assertFalse(sun.isDragging());
        assertTrue("the knob moved up", sun.knobY() < sun.getHeight() / 2f);
        // a touch on the label's room is not taken
        assertFalse(sun.onTouchEvent(ev(MotionEvent.ACTION_DOWN, 80, UiScale.dpF(context, 60), y)));
    }

    @Test
    public void itSitsRightOfTheRingOrLeftAtTheEdge() {
        ExposureSunView sun = sun();
        float r = UiScale.dpF(context, 38), gap = UiScale.dpF(context, ExposureSunView.GAP_DP);
        int w = Math.round(UiScale.dpF(context, 392)), h = Math.round(UiScale.dpF(context, 520));
        sun.placeBeside(300, 400, r, w, h);
        assertEquals(300 + r + gap, sun.getX(), 1f);
        assertEquals(400 - sun.getHeight() / 2f, sun.getY(), 1f);
        sun.placeBeside(w - 40, 400, r, w, h);
        assertTrue("left of the ring at the right edge", sun.getX() + UiScale.dpF(context, ExposureSunView.WIDTH_DP) <= w - 40 - r);
        sun.placeBeside(300, 10, r, w, h);
        assertEquals("kept inside the frame", 0f, sun.getY(), 0f);
    }
}
