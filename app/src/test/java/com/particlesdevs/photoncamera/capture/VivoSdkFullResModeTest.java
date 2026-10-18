package com.particlesdevs.photoncamera.capture;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;

import com.particlesdevs.photoncamera.api.CameraEventsListener;
import com.particlesdevs.photoncamera.api.CameraManager2;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class VivoSdkFullResModeTest {
    private MockedStatic<ScameraApp> app;
    private MockedStatic<PreferenceKeys> preferences;
    private MockedStatic<PixelPro10> pixel;
    private MockedStatic<VivoSdkFullRes> fullRes;
    private MockedStatic<VivoSdkRawSession> sdk;
    private MockedConstruction<CameraManager2> managers;
    private CaptureController controller;
    private Settings settings;
    private final VivoSdkRawSessionTest.Provider provider = new VivoSdkRawSessionTest.Provider();

    @Before public void setup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SettingsManager manager = new SettingsManager(context);
        app = mockStatic(ScameraApp.class);
        app.when(ScameraApp::getAppContext).thenReturn(context);
        app.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        app.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(call -> context.getString(call.getArgument(0)));
        ScameraApp instance = mock(ScameraApp.class);
        when(instance.getSettingsManager()).thenReturn(manager);
        app.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(instance);
        PreferenceKeys.initialise(manager);
        settings = new Settings();
        settings.mCameraID = "3";
        app.when(ScameraApp::getSettings).thenReturn(settings);
        preferences = mockStatic(PreferenceKeys.class);
        preferences.when(PreferenceKeys::isScamEnabled).thenReturn(true);
        pixel = mockStatic(PixelPro10.class);
        pixel.when(PixelPro10::fullResOn).thenReturn(true);
        fullRes = mockStatic(VivoSdkFullRes.class, CALLS_REAL_METHODS);
        fullRes.when(() -> VivoSdkFullRes.available("3")).thenReturn(true);
        sdk = mockStatic(VivoSdkRawSession.class);
        sdk.when(() -> VivoSdkRawSession.open(provider)).thenCallRealMethod();
        sdk.when(VivoSdkRawSession::open).thenAnswer(call -> VivoSdkRawSession.open(provider));
        sdk.when(() -> VivoSdkRawSession.applyAutomaticExposure(any())).thenCallRealMethod();
        managers = mockConstruction(CameraManager2.class,
                (mock, call) -> when(mock.getCameraIdList()).thenReturn(new String[]{"3"}));
        Activity activity = mock(Activity.class);
        when(activity.getSystemService(Context.CAMERA_SERVICE)).thenReturn(mock(CameraManager.class));
        controller = new CaptureController(activity, mock(ExecutorService.class), mock(CameraEventsListener.class));
    }

    @After public void cleanup() throws Exception {
        if (controller != null) {
            Method close = CaptureController.class.getDeclaredMethod("closeVivoSdkRawSession", CameraDevice.class);
            close.setAccessible(true);
            close.invoke(controller, new Object[]{null});
        }
        managers.close();
        sdk.close();
        fullRes.close();
        pixel.close();
        preferences.close();
        app.close();
    }

    private void prepare() throws Exception {
        Method prepare = CaptureController.class.getDeclaredMethod("prepareVivoSdkRawSession");
        prepare.setAccessible(true);
        prepare.invoke(controller);
    }

    private Object session() throws Exception {
        Field field = CaptureController.class.getDeclaredField("mVivoSdkRawSession");
        field.setAccessible(true);
        return field.get(controller);
    }

    @Test public void thePhotoModeShownByTheUiActuallyOpensSdkAndUsesFutureRaw() throws Exception {
        settings.selectedMode = CameraMode.userModeAt(0);
        assertEquals(CameraMode.MOTION, settings.selectedMode);
        prepare();
        sdk.verify(VivoSdkRawSession::open);
        assertNotNull(session());
        assertEquals(Integer.valueOf(1), provider.values.get(40));
        assertEquals(Integer.valueOf(2), provider.values.get(33));
        assertFalse(controller.isZslMode());
    }

    @Test public void videoAndRetiredUnlimitedModesNeverOpenTheSdkPhotoGraph() throws Exception {
        for (CameraMode mode : new CameraMode[]{CameraMode.VIDEO, CameraMode.RAWVIDEO, CameraMode.UNLIMITED}) {
            settings.selectedMode = mode;
            prepare();
            assertNull(session());
        }
        sdk.verify(VivoSdkRawSession::open, never());
        assertEquals(Integer.valueOf(0), provider.values.get(40));
        assertEquals(Integer.valueOf(1), provider.values.get(33));
    }

    @Test public void sdkPreviewKeepsVariableFpsInsteadOfTheMotionRingRate() throws Exception {
        settings.selectedMode = CameraMode.userModeAt(0);
        prepare();
        android.util.Range<Integer> auto = new android.util.Range<>(15, 30);
        Field fps = CaptureController.class.getDeclaredField("FpsRangeAuto");
        fps.setAccessible(true);
        fps.set(controller, auto);
        Class<?> metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        android.hardware.camera2.CaptureRequest.Builder builder = android.hardware.camera2.CaptureRequest.Builder.class
                .getConstructor(metadata, boolean.class, int.class, String.class, java.util.Set.class)
                .newInstance(metadata.getConstructor().newInstance(), false, -1, "4", null);
        builder.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                new android.util.Range<>(30, 30));
        builder.set(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, 250);
        builder.set(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, 16_666_667L);
        Method apply = CaptureController.class.getDeclaredMethod("applyVivoSdkPreview",
                android.hardware.camera2.CaptureRequest.Builder.class);
        apply.setAccessible(true);
        apply.invoke(controller, builder);
        assertEquals(auto, builder.get(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE));
        assertEquals(Integer.valueOf(0), builder.get(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY));
        assertEquals(Long.valueOf(0), builder.get(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME));
        Method motion = CaptureController.class.getDeclaredMethod("motionFpsRange");
        motion.setAccessible(true);
        try (MockedStatic<com.particlesdevs.photoncamera.settings.MotionMode> mode =
                     mockStatic(com.particlesdevs.photoncamera.settings.MotionMode.class)) {
            mode.when(com.particlesdevs.photoncamera.settings.MotionMode::on).thenReturn(true);
            assertNull(motion.invoke(controller));
            mode.verify(() -> com.particlesdevs.photoncamera.settings.MotionMode.fpsRange(any(), anyInt(), any()), never());
        }
    }

    @Test public void refusedSdkClearsTheRequestAndOnlyAnExplicitRetryReopensSdkAfterRestore() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean requested = new java.util.concurrent.atomic.AtomicBoolean(true);
        pixel.when(PixelPro10::fullResOn).thenAnswer(call -> requested.get());
        pixel.when(() -> PixelPro10.setFullRes(anyBoolean())).thenAnswer(call -> {
            requested.set(call.getArgument(0));
            return null;
        });
        settings.selectedMode = CameraMode.userModeAt(0);
        prepare();
        assertNotNull(session());
        Method refuse = CaptureController.class.getDeclaredMethod("retryWithoutVivoSdkFullRes",
                android.hardware.camera2.CameraCaptureSession.class, String.class);
        refuse.setAccessible(true);
        assertEquals(Boolean.TRUE, refuse.invoke(controller, null, "RAW graph refused"));
        assertFalse(requested.get());
        assertEquals(Boolean.FALSE, refuse.invoke(controller, null, "late duplicate failure"));
        Method close = CaptureController.class.getDeclaredMethod("closeVivoSdkRawSession", CameraDevice.class);
        close.setAccessible(true);
        close.invoke(controller, new Object[]{null});
        assertNull(session());
        assertEquals(Integer.valueOf(0), provider.values.get(40));
        assertEquals(Integer.valueOf(1), provider.values.get(33));
        prepare();
        assertNull(session());
        sdk.verify(VivoSdkRawSession::open, times(1));
        PixelPro10.setFullRes(true);
        prepare();
        assertNotNull(session());
        sdk.verify(VivoSdkRawSession::open, times(2));
    }

    private static void profile(VivoSdkFullRes.Profile p, String what, int w, int h, int mode, int block) {
        assertNotNull(what, p);
        assertEquals(what, w, p.size.getWidth());
        assertEquals(what, h, p.size.getHeight());
        assertEquals(what, mode, p.sensorMode);
        assertEquals(what, block, p.nativeBlock);
    }

    /**
     * P194 (owner, 2026-10-17: «FULLRES: аппаратный ремозаик» on the X100 / X300 Ultra): with the switch on, the native build's
     * 50 MP shot of a camera with a known Bayer row takes it by the native route (nativeBlock 1); a camera without one keeps its
     * own mosaic; 200 MP never changes; the switch off is the P186 table as before; no SDK profile (an X200 Ultra mode) on them.
     */
    @Test public void theHardwareRemosaicTakesTheBayerRowsOfTheX100AndX300UltraAt50MpOnly() {
        preferences.when(() -> PreferenceKeys.scamDevNumber(anyString(), anyFloat())).thenAnswer(call -> call.getArgument(1));
        final VivoNativeFullRes.Phone x300 = VivoNativeFullRes.Phone.X300_ULTRA, x100 = VivoNativeFullRes.Phone.X100_ULTRA;
        profile(VivoSdkFullRes.choose(x300, "3", true, true, false), "X300 main 50 MP, on", 8160, 6128, 2, 1);
        profile(VivoSdkFullRes.choose(x300, "3", true, false, false), "X300 main 50 MP, off (P200: row 3 first)", 8160, 6128, 3, 2);
        for (boolean on : new boolean[]{true, false}) {
            profile(VivoSdkFullRes.choose(x300, "3", true, on, true), "X300 main 200 MP " + on, 16320, 12256, 0, 4);
            profile(VivoSdkFullRes.choose(x300, "4", true, on, true), "X300 ultra-wide " + on, 8192, 6144, 0, 2);
            profile(VivoSdkFullRes.choose(x300, "5", true, on, false), "X300 tele 50 MP " + on + " (P229: row 14, Bayer)", 8160, 6144, on ? 14 : 1, on ? 1 : 2);
            profile(VivoSdkFullRes.choose(x300, "5", true, on, true), "X300 tele 200 MP " + on, 16320, 12288, 0, 4);
            profile(VivoSdkFullRes.choose(x100, "3", true, on, true), "X100 ultra-wide " + on, 8192, 6144, on ? 0 : 9, on ? 1 : 2);
            profile(VivoSdkFullRes.choose(x100, "4", true, on, false), "X100 tele 50 MP " + on, 8160, 6144, 1, 2);
            profile(VivoSdkFullRes.choose(x100, "4", true, on, true), "X100 tele 200 MP " + on, 16320, 12288, 0, 4);
            for (VivoNativeFullRes.Phone phone : new VivoNativeFullRes.Phone[]{x100, x300})
                for (String camera : new String[]{"0", "2", "3", "4", "5"})
                    for (boolean large : new boolean[]{true, false}) {
                        VivoSdkFullRes.Profile p = VivoSdkFullRes.choose(phone, camera, true, on, large);
                        if (p != null) assertTrue(phone + " " + camera + ": never the SDK route", p.nativeRoute());
                        if (p != null && p.size.getWidth() > 10000) assertEquals("200 MP: the own Tetra", 4, p.nativeBlock);
                    }
        }
        for (String camera : new String[]{"2", "0"}) {
            profile(VivoSdkFullRes.choose(x100, camera, true, true, true), "X100 main " + camera + ", on", 8192, 6144, 0, 1);
            profile(VivoSdkFullRes.choose(x100, camera, true, false, true), "X100 main " + camera + ", off", 8192, 6144, 11, 2);
        }
        // the X200 Ultra (and any phone outside the tables) keeps the P182 SDK route
        profile(VivoSdkFullRes.choose(null, "3", true, true, true), "X200 main, on", 8192, 6144, 13, 0);
        profile(VivoSdkFullRes.choose(VivoNativeFullRes.Phone.X200_ULTRA, "5", true, true, false), "X200 tele 50, on", 8160, 6144, 18, 0);
        assertSame(VivoSdkFullRes.TELE_200, VivoSdkFullRes.choose(VivoNativeFullRes.Phone.X200_ULTRA, "5", true, true, true));
        // a hardware row that failed on the phone gives way to the camera's own mosaic
        ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit().putBoolean("module_fullres_rowfail_3_hw", true).commit();
        try {
            profile(VivoSdkFullRes.choose(x300, "3", true, true, false), "X300 main 50 MP, row 2 failed", 8160, 6128, 3, 2);
            assertNull(VivoNativeFullRes.hardwareProfile(x300, "3", true));
            assertNotNull("the table itself keeps it", VivoNativeFullRes.hardwareProfile(x300, "3", false));
        } finally {
            ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit().remove("module_fullres_rowfail_3_hw").commit();
        }
        // the X300 main camera's own 50 MP rows all failed (P186 / P188): its 50 MP button stays while the hardware row can run
        ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit().putInt("module_fullres_rowfail_3_50", 3).commit();
        try {
            preferences.when(PreferenceKeys::isFullResHwRemosaic).thenReturn(false);
            assertFalse("switch off: 200 MP only", VivoNativeFullRes.twoSizes(x300, "3", true));
            profile(VivoSdkFullRes.choose(x300, "3", true, false, false), "X300 main, no own 50 MP row", 16320, 12256, 0, 4);
            try (MockedStatic<VivoNativeFullRes> phone = mockStatic(VivoNativeFullRes.class, CALLS_REAL_METHODS)) {
                phone.when(VivoNativeFullRes::phone).thenReturn(x300);
                preferences.when(PreferenceKeys::isFullResHwRemosaic).thenReturn(true);
                assertTrue("switch on: the hardware row is a 50 MP size", VivoNativeFullRes.twoSizes(x300, "3", true));
                profile(VivoSdkFullRes.choose(x300, "3", true, true, false), "X300 main 50 MP by the hardware row", 8160, 6128, 2, 1);
                profile(VivoSdkFullRes.choose(x300, "3", true, true, true), "X300 main 200 MP", 16320, 12256, 0, 4);
            }
        } finally {
            ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit().remove("module_fullres_rowfail_3_50").commit();
        }
    }

    /** P194: the switch is offered in the native build of the X200 / X100 / X300 Ultra, never on the OPPO Find X7 Ultra. */
    @Test public void theHardwareRemosaicIsOfferedOnTheNativeVivoBuildsOnly() {
        fullRes.when(VivoSdkFullRes::nativeRaw).thenReturn(false);
        assertFalse(VivoSdkFullRes.hardwareRemosaicOffered());
        fullRes.when(VivoSdkFullRes::nativeRaw).thenReturn(true);
        assertTrue(VivoSdkFullRes.hardwareRemosaicOffered());
        try (MockedStatic<OppoNativeFullRes> oppo = mockStatic(OppoNativeFullRes.class)) {
            oppo.when(OppoNativeFullRes::device).thenReturn(true);
            assertFalse(VivoSdkFullRes.hardwareRemosaicOffered());
            preferences.when(PreferenceKeys::isFullResHwRemosaic).thenReturn(true);
            assertFalse(VivoSdkFullRes.hardwareRemosaicOn());
        }
    }

    @Test public void frameCountUsesTheSettingAndAvailableRawMemory() {
        long raw = 8192L * 6144 * 2;
        assertEquals(20, VivoSdkFullRes.frameCount(20, raw, raw * 50));
        assertEquals(44, VivoSdkFullRes.frameCount(44, raw, raw * 100));
        assertEquals(8, VivoSdkFullRes.frameCount(20, raw, raw * 16));
        assertEquals(0, VivoSdkFullRes.frameCount(20, raw, raw));
    }

    /**
     * P190: an explicit FULLRES series is sized by the merge's memory beyond its legacy count (200 MP: 4, the camera closed for the
     * merge; 50 MP: 2); up to 4 frames at 200 MP the count is exactly the P179 one.
     */
    @Test public void seriesFitSizesAnExplicitSeriesByTheMergesMemory() {
        final long mib = 1L << 20, gib = 1L << 30;
        final long px200 = 16320L * 12288, raw200 = px200 * 2;
        assertEquals(0, VivoSdkFullRes.seriesFit(px200, 16320, raw200, 4, 1, true, 3060 * mib));
        assertEquals(7, VivoSdkFullRes.seriesFit(px200, 16320, raw200, 4, 1, true, 6200 * mib));
        assertEquals(11, VivoSdkFullRes.seriesFit(px200, 16320, raw200, 4, 1, true, 8000 * mib));
        assertEquals("the legacy 4 whatever the fit", 4, VivoSdkFullRes.seriesCount(64, px200, 16320, raw200, 4, 1, true, 3060 * mib));
        assertEquals(7, VivoSdkFullRes.seriesCount(64, px200, 16320, raw200, 4, 1, true, 6200 * mib));
        assertEquals("the RAW guard still binds", 10, VivoSdkFullRes.seriesCount(64, px200, 16320, raw200, 4, 1, true, 8000 * mib));
        assertEquals(8, VivoSdkFullRes.seriesCount(8, px200, 16320, raw200, 4, 1, true, 12000 * mib));
        for (long avail : new long[]{-1, 1000 * mib, 3060 * mib, 6200 * mib, 8000 * mib, 16 * gib})
            for (int requested = 1; requested <= 4; requested++)
                assertEquals("200 MP up to 4 frames: as before P190", VivoSdkFullRes.frameCount(requested, raw200, avail),
                        VivoSdkFullRes.seriesCount(requested, px200, 16320, raw200, 4, 1, true, avail));
        final long px50 = 8160L * 6144, raw50 = px50 * 2;
        assertEquals(14, VivoSdkFullRes.seriesCount(64, px50, 8160, raw50, 2, 1, false, 4 * gib));
        assertEquals(27, VivoSdkFullRes.seriesCount(64, px50, 8160, raw50, 2, 1, false, 6 * gib));
        assertEquals(41, VivoSdkFullRes.seriesCount(64, px50, 8160, raw50, 2, 1, false, 8 * gib));
        assertEquals(20, VivoSdkFullRes.seriesCount(20, px50, 8160, raw50, 2, 1, false, 8 * gib));
        assertEquals("the 2x output: the floor of 2", 2, VivoSdkFullRes.seriesCount(20, px50, 8160, raw50, 2, 2, false, 6 * gib));
        assertEquals(1, VivoSdkFullRes.seriesCount(1, px50, 8160, raw50, 2, 1, false, 8 * gib));
        assertEquals("unknown memory: as asked", 30, VivoSdkFullRes.seriesCount(30, px50, 8160, raw50, 2, 1, false, -1));
        assertEquals("no RAW room: none", 0, VivoSdkFullRes.seriesCount(30, px50, 8160, raw50, 2, 1, false, raw50));
    }
}
