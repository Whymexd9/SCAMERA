package com.particlesdevs.photoncamera.ui.controls;

import android.app.Application;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

/**
 * P172 (owner, 2026-10-16: «Сделай так чтобы при выборе шага с таким шагом параметр и менялся»): with «Шаг 10» the ruler moves
 * the value by whole steps of 10 from where it is (53: 63, 73), a part of a tick moves nothing, the range ends hold.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class PrecisionRulerTest {
    private PrecisionRuler ruler(double value) {
        PrecisionRuler r = new PrecisionRuler(RuntimeEnvironment.getApplication(), 1, 100, value, 1);
        new android.widget.FrameLayout(RuntimeEnvironment.getApplication()).addView(r);   // the drag asks its parent not to intercept
        r.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY));
        r.layout(0, 0, 1080, 200);
        return r;
    }

    private static float tick() {
        return 16 * RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density;
    }

    /** A drag of {@code ticks} ticks to the left (larger values), in small moves, held still before it is let go. */
    private static void drag(PrecisionRuler r, float ticks) {
        long t = SystemClock.uptimeMillis();
        float x = 800, end = x - ticks * tick();
        r.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, 100, 0));
        int moves = 12;
        for (int i = 1; i <= moves; i++)
            r.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 16, MotionEvent.ACTION_MOVE, x + (end - x) * i / moves, 100, 0));
        r.dispatchTouchEvent(MotionEvent.obtain(t, t + 600, MotionEvent.ACTION_MOVE, end, 100, 0));
        r.dispatchTouchEvent(MotionEvent.obtain(t, t + 1200, MotionEvent.ACTION_UP, end, 100, 0));
        r.computeScroll();
    }

    @Test
    public void stepTenMovesTheValueByTens() {
        PrecisionRuler r = ruler(50);
        r.setStep(10);
        drag(r, 1.5f);
        assertEquals("one whole tick, half a tick moves nothing", 60, r.getValue(), 1e-9);
        drag(r, 2.2f);
        assertEquals(80, r.getValue(), 1e-9);
        drag(r, -3f);
        assertEquals(50, r.getValue(), 1e-9);
    }

    @Test
    public void theStepIsRelativeToTheValueAndTheRangeHolds() {
        PrecisionRuler r = ruler(53);
        r.setStep(10);
        drag(r, 2f);
        assertEquals("53 + 2 steps of 10", 73, r.getValue(), 1e-9);
        drag(r, 9f);
        assertEquals("the range's end", 100, r.getValue(), 1e-9);
        r.setStep(1);
        drag(r, -3f);
        assertEquals(97, r.getValue(), 1e-9);
    }
}
