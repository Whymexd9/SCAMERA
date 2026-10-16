package com.particlesdevs.photoncamera.control;

import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.ui.camera.views.FocusCircleView;
import com.particlesdevs.photoncamera.ui.camera.views.viewfinder.GLPreview;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/**
 * P142 «Раздельные фокус и экспозиция»: a tap puts the AF and AE regions on the tapped point; the exposure square then moves
 * the AE regions alone (no AF trigger), the focus ring the AF regions alone; a tap on the square joins it to the ring. Off:
 * no square, the ring resets to auto as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class TouchFocusSplitTest {
    private static final int VIEW_W = 1000, VIEW_H = 1333;
    private final Rect active = new Rect(0, 0, 4000, 3000);
    private CaptureRequest.Builder builder;
    private CaptureController capture;
    private MockedStatic<PreferenceKeys> keys;
    private CameraCharacteristics previous;
    private FocusCircleView ring;
    private TouchFocus touch;
    private boolean split = true;
    /** The AF trigger of every request the camera got (one-shots and repeating updates). */
    private final List<Integer> triggers = new ArrayList<>();

    private static CaptureRequest.Builder newRequestBuilder() throws Exception {
        Class<?> metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        return CaptureRequest.Builder.class.getConstructor(metadata, boolean.class, int.class, String.class, java.util.Set.class)
                .newInstance(metadata.getConstructor().newInstance(), false, -1, "0", null);
    }

    @Before
    public void setUp() throws Exception {
        capture = mock(CaptureController.class);
        builder = newRequestBuilder();
        capture.mPreviewRequestBuilder = builder;
        capture.mSensorOrientation = 90;
        doAnswer(i -> { triggers.add(builder.get(CaptureRequest.CONTROL_AF_TRIGGER)); return null; }).when(capture).rebuildPreviewBuilder();
        doAnswer(i -> { triggers.add(builder.get(CaptureRequest.CONTROL_AF_TRIGGER)); return null; }).when(capture).rebuildPreviewBuilderOneShot();
        CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)).thenReturn(1);
        when(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)).thenReturn(1);
        when(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).thenReturn(active);
        when(c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)).thenReturn(10f);
        when(c.get(CameraCharacteristics.LENS_FACING)).thenReturn(CameraCharacteristics.LENS_FACING_BACK);
        when(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)).thenReturn(new int[]{0, 1, 3, 4});
        when(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)).thenReturn(new int[]{0, 1});
        previous = CaptureController.mCameraCharacteristics;
        CaptureController.mCameraCharacteristics = c;
        CaptureController.mPreviewCaptureRequest = null;
        CaptureController.burst = false;
        keys = mockStatic(PreferenceKeys.class);
        keys.when(PreferenceKeys::isSplitAfAe).thenAnswer(i -> split);
        keys.when(PreferenceKeys::getAfMode).thenReturn(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        keys.when(PreferenceKeys::getAeMode).thenReturn(CameraMetadata.CONTROL_AE_MODE_ON);
        android.app.Activity activity = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        Context context = activity;
        FrameLayout parent = new FrameLayout(context);
        ring = new FocusCircleView(context, null);
        parent.addView(ring, new FrameLayout.LayoutParams(120, 120));
        activity.setContentView(parent, new android.view.ViewGroup.LayoutParams(VIEW_W, VIEW_H)); // attached: View.post runs
        parent.measure(View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY));
        parent.layout(0, 0, VIEW_W, VIEW_H);
        GLPreview texture = mock(GLPreview.class);
        when(texture.getWidth()).thenReturn(VIEW_W);
        when(texture.getHeight()).thenReturn(VIEW_H);
        when(texture.getDisplay()).thenReturn(((android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay());
        touch = new TouchFocus(capture, ring, texture);
        idle();
        triggers.clear();
    }

    @After
    public void tearDown() {
        keys.close();
        CaptureController.mCameraCharacteristics = previous;
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private MeteringRectangle af() {
        MeteringRectangle[] r = builder.get(CaptureRequest.CONTROL_AF_REGIONS);
        return r == null ? null : r[0];
    }

    private MeteringRectangle ae() {
        MeteringRectangle[] r = builder.get(CaptureRequest.CONTROL_AE_REGIONS);
        return r == null ? null : r[0];
    }

    /** The region a tap at view point (x, y) maps to (the same geometry TouchFocus uses). */
    private MeteringRectangle region(float x, float y) {
        int[] r = TouchFocus.mapTapToCrop(x, y, VIEW_W, VIEW_H, active.left, active.top, active.width(), active.height(), 90, 90, false);
        return new MeteringRectangle(r[0], r[1], r[2], r[2], TouchFocus.REGION_WEIGHT);
    }

    private static boolean same(MeteringRectangle a, MeteringRectangle b) {
        return a != null && b != null && a.getX() == b.getX() && a.getY() == b.getY() && a.getWidth() == b.getWidth()
                && a.getHeight() == b.getHeight();
    }

    /** A finger on view v: down, moved by (dx, dy) in steps, up. */
    private void drag(View v, float dx, float dy) {
        float x0 = v.getX() + 10, y0 = v.getY() + 10;
        long t = SystemClock.uptimeMillis();
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x0, y0, 0));
        for (int i = 1; i <= 4; i++) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)); // the AE updates are throttled
            v.dispatchTouchEvent(MotionEvent.obtain(t, t + 100L * i, MotionEvent.ACTION_MOVE, x0 + dx * i / 4, y0 + dy * i / 4, 0));
        }
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 500, MotionEvent.ACTION_UP, x0 + dx, y0 + dy, 0));
        idle();
    }

    private void tap(View v) {
        float x0 = v.getX() + 10, y0 = v.getY() + 10;
        long t = SystemClock.uptimeMillis();
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x0, y0, 0));
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x0, y0, 0));
        idle();
    }

    @Test
    public void aTapPutsBothRegionsOnThePointAndShowsTheSquare() {
        touch.processTouchToFocus(500, 600);
        idle();
        assertTrue(same(region(500, 600), af()));
        assertTrue(same(af(), ae()));
        View square = touch.exposureSquare();
        assertNotNull(square);
        assertEquals(View.VISIBLE, square.getVisibility());
        assertEquals(View.VISIBLE, ring.getVisibility());
        assertEquals(500f, square.getX() + square.getLayoutParams().width / 2f, 1f);
        assertEquals(600f, square.getY() + square.getLayoutParams().width / 2f, 1f);
        assertTrue("the square is larger than the ring", square.getLayoutParams().width > ring.getLayoutParams().width);
    }

    @Test
    public void theSquareMovesOnlyTheExposureWithoutAFocusTrigger() {
        touch.processTouchToFocus(500, 600);
        idle();
        MeteringRectangle focus = af();
        View square = touch.exposureSquare();
        triggers.clear();
        drag(square, 260, -180);
        assertTrue("the AF regions stay on the ring", same(focus, af()));
        assertTrue("the AE regions follow the square", same(region(760, 420), ae()));
        assertNotEquals(af().getX(), ae().getX());
        assertTrue("repeating updates only, no AF start", !triggers.isEmpty());
        for (Integer t : triggers) assertTrue(t == null || t == CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
    }

    @Test
    public void theRingMovesOnlyTheFocusAndATapOnTheSquareJoinsItBack() {
        touch.processTouchToFocus(500, 600);
        idle();
        View square = touch.exposureSquare();
        drag(square, 260, -180);
        MeteringRectangle exposure = ae();
        triggers.clear();
        drag(ring, -300, 250);
        assertTrue("the AF regions follow the ring", same(region(200, 850), af()));
        assertTrue("the AE regions stay on the square", same(exposure, ae()));
        assertTrue("a focus sequence started (CANCEL first)", triggers.contains(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL));
        tap(square);
        assertTrue("a tap on the square brings the exposure back to the ring", same(af(), ae()));
        assertEquals(ring.getX() + ring.getLayoutParams().width / 2f, square.getX() + square.getLayoutParams().width / 2f, 1f);
    }

    @Test
    public void aTapOnTheRingStillResetsToAuto() {
        touch.processTouchToFocus(500, 600);
        idle();
        View square = touch.exposureSquare();
        tap(ring);
        assertEquals(View.GONE, square.getVisibility());
        assertEquals("the zero region of auto", 0, af().getWidth());
        assertEquals(0, ae().getWidth());
    }

    @Test
    public void withTheOptionOffThereIsNoSquare() {
        split = false;
        touch.processTouchToFocus(500, 600);
        idle();
        assertNull(touch.exposureSquare());
        assertTrue(same(af(), ae()));
        tap(ring);
        assertEquals(0, af().getWidth());
    }
}
