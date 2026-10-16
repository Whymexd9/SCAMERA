package com.particlesdevs.photoncamera.control;

import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.Looper;
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
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/**
 * P155 (owner, X300 Ultra: tap-to-focus failed without DCG 14 bit, worked with it): in a continuous AF mode a START on
 * PASSIVE_FOCUSED only locks the lens where it is. When the HAL shows no passive scan on the tapped region within the dwell,
 * the START goes in AF_MODE_AUTO (an active scan on the region); after an observed passive scan it stays the bare START of
 * the continuous mode, and the next tap starts in the continuous mode again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class TouchFocusActiveScanTest {
    private static final int VIEW_W = 1000, VIEW_H = 1333;
    private CaptureRequest.Builder builder;
    private MockedStatic<PreferenceKeys> keys;
    private CameraCharacteristics previous;
    private TouchFocus touch;
    private CaptureRequest request;
    private int[] afModes = {0, 1, 3, 4};
    /** {AF mode, AF trigger} of every request the camera got, and whether it was a one-shot. */
    private final List<int[]> sent = new ArrayList<>();

    private static CaptureRequest.Builder newRequestBuilder() throws Exception {
        Class<?> metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        return CaptureRequest.Builder.class.getConstructor(metadata, boolean.class, int.class, String.class, java.util.Set.class)
                .newInstance(metadata.getConstructor().newInstance(), false, -1, "0", null);
    }

    private void record(int oneShot) {
        Integer mode = builder.get(CaptureRequest.CONTROL_AF_MODE);
        Integer trigger = builder.get(CaptureRequest.CONTROL_AF_TRIGGER);
        sent.add(new int[]{mode == null ? -1 : mode, trigger == null ? -1 : trigger, oneShot});
    }

    @Before
    public void setUp() throws Exception {
        CaptureController capture = mock(CaptureController.class);
        builder = newRequestBuilder();
        capture.mPreviewRequestBuilder = builder;
        capture.mSensorOrientation = 90;
        doAnswer(i -> { record(0); return null; }).when(capture).rebuildPreviewBuilder();
        doAnswer(i -> { record(1); return null; }).when(capture).rebuildPreviewBuilderOneShot();
        CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)).thenReturn(1);
        when(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)).thenReturn(1);
        when(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).thenReturn(new Rect(0, 0, 4080, 3064));
        when(c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)).thenReturn(10f);
        when(c.get(CameraCharacteristics.LENS_FACING)).thenReturn(CameraCharacteristics.LENS_FACING_BACK);
        when(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)).thenAnswer(i -> afModes);
        when(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)).thenReturn(new int[]{0, 1});
        previous = CaptureController.mCameraCharacteristics;
        CaptureController.mCameraCharacteristics = c;
        CaptureController.mPreviewCaptureRequest = null;
        CaptureController.burst = false;
        keys = mockStatic(PreferenceKeys.class);
        keys.when(PreferenceKeys::isSplitAfAe).thenReturn(false);
        keys.when(PreferenceKeys::getAfMode).thenReturn(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        keys.when(PreferenceKeys::getAeMode).thenReturn(CameraMetadata.CONTROL_AE_MODE_ON);
        android.app.Activity activity = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        Context context = activity;
        FrameLayout parent = new FrameLayout(context);
        FocusCircleView ring = new FocusCircleView(context, null);
        parent.addView(ring, new FrameLayout.LayoutParams(120, 120));
        activity.setContentView(parent, new android.view.ViewGroup.LayoutParams(VIEW_W, VIEW_H));
        GLPreview texture = mock(GLPreview.class);
        when(texture.getWidth()).thenReturn(VIEW_W);
        when(texture.getHeight()).thenReturn(VIEW_H);
        when(texture.getDisplay()).thenReturn(((android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay());
        touch = new TouchFocus(capture, ring, texture);
        // results of the tap's requests: no tag, the region array present (the lenient gate of TouchFocus)
        request = mock(CaptureRequest.class);
        when(request.get(CaptureRequest.CONTROL_AF_REGIONS)).thenAnswer(i -> builder.get(CaptureRequest.CONTROL_AF_REGIONS));
        when(request.get(CaptureRequest.CONTROL_AE_REGIONS)).thenAnswer(i -> builder.get(CaptureRequest.CONTROL_AE_REGIONS));
        idle();
    }

    @After
    public void tearDown() {
        keys.close();
        CaptureController.mCameraCharacteristics = previous;
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void later(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private void frame(int afState) {
        CaptureResult r = mock(CaptureResult.class);
        when(r.get(CaptureResult.CONTROL_AF_STATE)).thenReturn(afState);
        when(r.getRequest()).thenReturn(request);
        touch.onCaptureResult(r);
    }

    private void tap() {
        sent.clear();
        touch.processTouchToFocus(500, 600);
        idle();
    }

    /** The submission that carried the AF START. */
    private int[] start() {
        for (int[] s : sent) if (s[1] == CameraMetadata.CONTROL_AF_TRIGGER_START) return s;
        return null;
    }

    @Test
    public void noPassiveScanOnTheRegionStartsAnActiveScanInAuto() {
        tap();
        assertEquals("the tap cancels in the continuous mode", CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE, sent.get(0)[0]);
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL, sent.get(0)[1]);
        frame(CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED);              // cancel acknowledged
        frame(CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED);
        assertEquals("no START inside the dwell", null, start());
        later(TouchFocus.PASSIVE_DWELL_MS + 50);
        frame(CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED);              // X300 Ultra without DCG: never a PASSIVE_SCAN
        int[] start = start();
        assertTrue("an AF START was sent", start != null);
        assertEquals("the START goes as a one-shot", 1, start[2]);
        assertEquals("the START switches to AF_MODE_AUTO", CameraMetadata.CONTROL_AF_MODE_AUTO, start[0]);
        int[] after = sent.get(sent.size() - 1);
        assertEquals("the repeating request after it stays in AUTO", 0, after[2]);
        assertEquals(CameraMetadata.CONTROL_AF_MODE_AUTO, after[0]);
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_IDLE, after[1]);
        frame(CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN);
        assertEquals(CameraMetadata.CONTROL_AF_MODE_AUTO, (int) builder.get(CaptureRequest.CONTROL_AF_MODE));

        // the next tap starts in the continuous mode again
        tap();
        assertEquals(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE, sent.get(0)[0]);
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL, sent.get(0)[1]);
    }

    @Test
    public void anObservedPassiveScanKeepsTheContinuousStart() {
        tap();
        frame(CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED);              // cancel acknowledged
        later(100);
        frame(CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN);                 // X300 Ultra with DCG: the HAL sweeps the region
        later(TouchFocus.PASSIVE_DWELL_MS);
        frame(CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN);
        assertEquals("never locked mid-scan", null, start());
        frame(CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED);
        int[] start = start();
        assertTrue("an AF START was sent", start != null);
        assertEquals("the START stays in the continuous mode", CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE, start[0]);
        assertEquals("no repeating update after the START", start, sent.get(sent.size() - 1));
    }

    @Test
    public void withoutAutoModeTheStartStaysContinuous() {
        afModes = new int[]{0, 4};
        tap();
        frame(CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED);
        later(TouchFocus.PASSIVE_DWELL_MS + 50);
        frame(CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED);
        int[] start = start();
        assertTrue(start != null);
        assertEquals(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE, start[0]);
    }

    @Test
    public void decision() {
        assertTrue(TouchFocus.startsActiveScan(false, false, true));
        assertFalse("a passive scan swept the region", TouchFocus.startsActiveScan(true, false, true));
        assertFalse("the shared budget is spent", TouchFocus.startsActiveScan(false, true, true));
        assertFalse("no AF_MODE_AUTO on this camera", TouchFocus.startsActiveScan(false, false, false));
    }
}
