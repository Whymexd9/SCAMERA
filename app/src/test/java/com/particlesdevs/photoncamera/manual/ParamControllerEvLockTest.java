package com.particlesdevs.photoncamera.manual;

import android.app.Application;
import android.hardware.camera2.CaptureRequest;

import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * The owner's EV rule on the request side (MANUAL_TASK.md §2): with ISO and shutter both manual the preview request gets
 * no exposure compensation; the stored EV is kept and applies again as soon as ISO or shutter returns to auto.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ParamControllerEvLockTest {
    private CaptureRequest.Builder builder;
    private ManualParamModel model;
    private ParamController controller;
    /** The compensation in the builder at each preview rebuild (what the camera actually got). */
    private final List<Integer> rebuilt = new ArrayList<>();

    private static CaptureRequest.Builder newRequestBuilder() throws Exception {
        Class<?> metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        return CaptureRequest.Builder.class.getConstructor(metadata, boolean.class, int.class, String.class, java.util.Set.class)
                .newInstance(metadata.getConstructor().newInstance(), false, -1, "0", null);
    }

    private Integer ev() {
        return builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
    }

    @Before
    public void setUp() throws Exception {
        CaptureController capture = mock(CaptureController.class);
        builder = newRequestBuilder();
        capture.mPreviewRequestBuilder = builder;
        capture.mPreviewIso = 100;
        capture.mPreviewExposureTime = 10_000_000L;
        doAnswer(i -> { rebuilt.add(ev()); return null; }).when(capture).rebuildPreviewBuilder();
        controller = new ParamController(capture);
        model = new ManualParamModel();
        model.reset();
        model.addObserver(controller);
    }

    @Test
    public void evIsAppliedWhileIsoOrShutterIsAuto() {
        assertNull(ev());
        model.setCurrentEvValue(3);
        assertEquals(Integer.valueOf(3), ev());
        assertEquals(3, controller.effectiveEv());
        model.setCurrentISOValue(400); // ISO manual, shutter auto: EV still applies
        assertEquals(Integer.valueOf(3), ev());
        model.setCurrentISOValue(ManualParamModel.ISO_AUTO);
        model.setCurrentExposureValue(1_000_000_000L / 125); // shutter manual, ISO auto: EV still applies
        assertEquals(Integer.valueOf(3), ev());
        assertEquals(3, controller.effectiveEv());
    }

    @Test
    public void evIsNotAppliedWhileLockedAndComesBack() {
        model.setCurrentEvValue(-2);
        model.setCurrentISOValue(800);
        assertEquals(Integer.valueOf(-2), ev());
        // shutter manual too: locked, the request gets 0 and the stored value stays
        model.setCurrentExposureValue(1_000_000_000L / 125);
        assertEquals(Integer.valueOf(0), ev());
        assertEquals(0, controller.effectiveEv());
        assertEquals(-2, controller.EV);
        assertEquals(-2, model.getCurrentEvValue(), 0);
        // a new EV while locked is stored, not applied
        model.setCurrentEvValue(-1);
        assertEquals(Integer.valueOf(0), ev());
        assertEquals(-1, controller.EV);
        // ISO back to auto: the stored EV applies again
        model.setCurrentISOValue(ManualParamModel.ISO_AUTO);
        assertEquals(Integer.valueOf(-1), ev());
        assertEquals(-1, controller.effectiveEv());
    }

    @Test
    public void lockAndUnlockReachTheBuilderBeforeTheRebuild() {
        model.setCurrentISOValue(200);
        model.setCurrentEvValue(2);
        rebuilt.clear();
        model.setCurrentExposureValue(1_000_000_000L / 60); // locks: the rebuild already carries 0
        assertEquals(Integer.valueOf(0), rebuilt.get(0));
        rebuilt.clear();
        model.setCurrentExposureValue(ManualParamModel.EXPOSURE_AUTO); // unlocks: the rebuild carries the stored 2
        assertEquals(Integer.valueOf(2), rebuilt.get(0));
    }

    @Test
    public void setupPreviewKeepsTheLock() {
        model.setCurrentEvValue(3);
        model.setCurrentISOValue(400);
        model.setCurrentExposureValue(1_000_000_000L / 250);
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 3); // a stale value, as after a session rebuild
        controller.setupPreview();
        assertEquals(Integer.valueOf(0), ev());
        model.setCurrentExposureValue(ManualParamModel.EXPOSURE_AUTO);
        controller.setupPreview();
        assertEquals(Integer.valueOf(3), ev());
    }
}
