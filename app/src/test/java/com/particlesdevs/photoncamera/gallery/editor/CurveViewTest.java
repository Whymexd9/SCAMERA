package com.particlesdevs.photoncamera.gallery.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** «Кривая»: a double tap on a point removes it (the hint says so); the same touch did not put a new point back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CurveViewTest {
    private CurveView cv;
    private final List<float[]> commits = new ArrayList<>();
    private float side, left, top;

    @Before
    public void setUp() {
        Application app = RuntimeEnvironment.getApplication();
        cv = new CurveView(app);
        new FrameLayout(app).addView(cv);
        cv.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        cv.layout(0, 0, 1000, 600);
        cv.setListener((pts, done) -> {
            if (!done) return;
            commits.add(pts);
            cv.set(pts, 0xFFFFFFFF); // as EditPanels' refresh does on a commit
        });
        float d = app.getResources().getDisplayMetrics().density;
        side = Math.min(1000, 600) - 12 * d;
        left = (1000 - side) / 2f;
        top = (600 - side) / 2f;
    }

    private float x(float v) { return left + v * side; }

    private float y(float v) { return top + (1 - v) * side; }

    private void touch(long down, long up, float x, float y) {
        cv.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0));
        cv.dispatchTouchEvent(MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, x, y, 0));
    }

    @Test
    public void doubleTapRemovesAPoint() {
        cv.set(new float[]{0, 0, 0.5f, 0.7f, 1, 1}, 0xFFFFFFFF);
        long t0 = SystemClock.uptimeMillis();
        touch(t0, t0 + 60, x(0.5f), y(0.7f));
        touch(t0 + 160, t0 + 220, x(0.5f), y(0.7f));
        assertNull("the double-tapped point is gone (a straight curve): " + Arrays.toString(cv.points()), cv.points());
        assertNull("the last commit is the straight curve", commits.get(commits.size() - 1));
    }

    @Test
    public void aTapOnTheCurveStillAddsAPoint() {
        long t0 = SystemClock.uptimeMillis();
        touch(t0, t0 + 60, x(0.25f), y(0.25f));
        float[] p = cv.points();
        assertNotNull("a point was added on the straight curve", p);
        assertEquals(6, p.length);
        assertEquals(0.25f, p[2], 0.01f);
    }
}
