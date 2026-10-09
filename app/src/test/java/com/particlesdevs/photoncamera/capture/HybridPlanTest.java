package com.particlesdevs.photoncamera.capture;

import android.app.Application;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Range;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** P27: the Hybrid's plan never throws and a frame that missed its plan keeps the role its measured exposure gives. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class HybridPlanTest {
    private MockedStatic<PreferenceKeys> prefs;
    private MockedStatic<com.particlesdevs.photoncamera.app.PhotonCamera> photon;

    @Before public void setup() {
        // PreferenceKeys' class initialisation reads strings through PhotonCamera (as in CameraResumeTest).
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        com.particlesdevs.photoncamera.settings.SettingsManager settings = new com.particlesdevs.photoncamera.settings.SettingsManager(context);
        photon = mockStatic(com.particlesdevs.photoncamera.app.PhotonCamera.class);
        photon.when(com.particlesdevs.photoncamera.app.PhotonCamera::getAppContext).thenReturn(context);
        photon.when(com.particlesdevs.photoncamera.app.PhotonCamera::getResourcesStatic).thenReturn(context.getResources());
        photon.when(com.particlesdevs.photoncamera.app.PhotonCamera::getSettingsManagerStatic).thenReturn(settings);
        photon.when(com.particlesdevs.photoncamera.app.PhotonCamera::getSettings).thenReturn(new com.particlesdevs.photoncamera.api.Settings());
        photon.when(() -> com.particlesdevs.photoncamera.app.PhotonCamera.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.getArgument(0)));
        prefs = mockStatic(PreferenceKeys.class);
        prefs.when(() -> PreferenceKeys.hybridSwitch(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        prefs.when(() -> PreferenceKeys.hybridValue(anyString(), anyFloat())).thenAnswer(i -> i.getArgument(1));
        prefs.when(PreferenceKeys::getAntibandingHz).thenReturn(0);
    }
    @After public void cleanup() { prefs.close(); photon.close(); }

    private static CameraCharacteristics camera(int isoMax) {
        CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)).thenReturn(new Range<>(10_000L, 500_000_000L));
        when(c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)).thenReturn(new Range<>(50, isoMax));
        return c;
    }
    private static CaptureRequest request(int index, ImageFrame.CaptureRole role) {
        CaptureRequest q = mock(CaptureRequest.class);
        when(q.getTag()).thenReturn(new ImageFrame.ScamCaptureTag(1, index, role));
        return q;
    }
    private static CaptureResult result(Long ns, Integer iso) {
        CaptureResult r = mock(CaptureResult.class);
        when(r.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(ns);
        when(r.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(iso);
        return r;
    }

    @Test public void ownersLongFramesCappedAtTheNGainAreNFrames() {
        // 'LONG: ISO 320/640, shutter 10000000/10000000, 1.00 EV': the HAL capped the gain, the frame is an N exposure.
        HybridPlan plan = HybridPlan.build(10_000_000L, 320, 0f, camera(12000));
        assertEquals(5, plan.requests.size());
        HybridPlan.Request longFrame = plan.requests.get(0);
        assertEquals(ImageFrame.CaptureRole.LONG, longFrame.role);
        assertEquals(640, longFrame.iso);
        CaptureRequest q = request(0, ImageFrame.CaptureRole.LONG);
        assertEquals(ImageFrame.CaptureRole.NORMAL, plan.classify(q, result(10_000_000L, 320)));
        assertEquals(ImageFrame.CaptureRole.LONG, plan.classify(q, result(10_000_000L, 640)));
        assertEquals(ImageFrame.CaptureRole.LONG, plan.classify(q, result(10_000_000L, 560)));   // sensor rounding: planned role
        assertNull(plan.classify(q, result(10_000_000L, 416)));                                     // x1.3: no role fits
        assertNull(plan.classify(q, result(10_000_000L, null)));                                    // no metadata
        assertNull(plan.classify(request(9, ImageFrame.CaptureRole.LONG), result(10_000_000L, 640))); // outside the plan
    }

    @Test public void ultrashortRoundingKeepsItsRoleAndAFarMissIsReclassified() {
        HybridPlan plan = HybridPlan.build(10_000_000L, 320, 0.3f, camera(12000));
        HybridPlan.Request us = plan.requests.get(0);
        assertEquals(ImageFrame.CaptureRole.EXTRA_SHORT, us.role);
        CaptureRequest q = request(0, ImageFrame.CaptureRole.EXTRA_SHORT);
        // X100 Ultra log 9127: the HAL raised the ISO 50 -> 60 at a short shutter (+0.26 EV).
        assertEquals(ImageFrame.CaptureRole.EXTRA_SHORT, plan.classify(q, result(us.shutterNs, us.iso * 6 / 5)));
        assertEquals(ImageFrame.CaptureRole.EXTRA_SHORT, plan.classify(q, result(us.shutterNs, us.iso * 2)));
        assertEquals(ImageFrame.CaptureRole.NORMAL, plan.classify(q, result(10_000_000L, 300)));     // came back at N, darker
        assertNull(plan.classify(q, result(10_000_000L, 360)));                                     // brighter than N: never an N donor
    }

    @Test public void plansThatWorkedAreUnchanged() {
        // X200 Pro log 3813: N 10 ms ISO 659 -> LONG 10 ms ISO 1318 x5.
        HybridPlan day = HybridPlan.build(10_000_000L, 659, 0f, camera(12000));
        assertEquals(5, day.requests.size());
        for (HybridPlan.Request r : day.requests) { assertEquals(10_000_000L, r.shutterNs); assertEquals(1318, r.iso); }
        // X200 Pro log 9489: N 80 ms ISO 12000 -> LONG 125 ms ISO 12000.
        HybridPlan night = HybridPlan.build(80_000_000L, 12000, 0f, camera(12000));
        assertEquals(125_000_000L, night.requests.get(0).shutterNs);
        assertEquals(12000, night.requests.get(0).iso);
    }

    @Test public void learnedGainCapMovesTheBracketToTheShutter() {
        ExposureLimits.resetForTest();
        ExposureLimits limits = ExposureLimits.of("2", 4096, 3072, false);
        CaptureRequest q = mock(CaptureRequest.class);
        when(q.get(CaptureRequest.CONTROL_AE_MODE)).thenReturn(CaptureRequest.CONTROL_AE_MODE_OFF);
        when(q.get(CaptureRequest.SENSOR_EXPOSURE_TIME)).thenReturn(10_000_000L);
        when(q.get(CaptureRequest.SENSOR_SENSITIVITY)).thenReturn(640);
        // without a learned cap: today's plan (gain first)
        assertEquals(640, HybridPlan.build(10_000_000L, 320, 0f, camera(12000), limits).requests.get(0).iso);
        limits.observeManual(q, result(10_000_000L, 320));
        limits.observeManual(q, result(10_000_000L, 320));
        HybridPlan.Request r = HybridPlan.build(10_000_000L, 320, 0f, camera(12000), limits).requests.get(0);
        assertEquals(ImageFrame.CaptureRole.LONG, r.role);
        assertEquals(320, r.iso);
        assertEquals(20_000_000L, r.shutterNs);
    }

    @Test public void normalBackTakesNAfterTheShutterThenTheExtras() {
        HybridPlan plan = HybridPlan.buildNormalBack(10_000_000L, 20000, 0f, camera(12000), 4);
        for (int i = 0; i < 4; i++) {
            HybridPlan.Request r = plan.requests.get(i);
            assertEquals(ImageFrame.CaptureRole.NORMAL, r.role);
            assertEquals(12000, r.iso);
            assertEquals(1.0, r.ratio, 0.01);   // the shutter carries the exposure above the ISO range
        }
        assertTrue(plan.requests.size() > 4);
        for (int i = 4; i < plan.requests.size(); i++) assertNotEquals(ImageFrame.CaptureRole.NORMAL, plan.requests.get(i).role);
        assertEquals(ImageFrame.CaptureRole.NORMAL, plan.classify(request(0, ImageFrame.CaptureRole.NORMAL), result(16_666_667L, 12000)));
    }

    @Test public void noKnownExposureLetsTheCameraExpose() {
        HybridPlan plan = HybridPlan.buildNormalBack(0, 0, 0f, camera(12000), 4);
        assertEquals(4, plan.requests.size());
        for (HybridPlan.Request r : plan.requests) assertTrue(r.autoExposure);
        assertEquals(ImageFrame.CaptureRole.NORMAL, plan.classify(request(2, ImageFrame.CaptureRole.NORMAL), result(5_000_000L, 1234)));
        assertEquals(1, HybridPlan.single(0, 0).requests.size());
        assertFalse(HybridPlan.single(10_000_000L, 100).requests.get(0).autoExposure);
    }
}
