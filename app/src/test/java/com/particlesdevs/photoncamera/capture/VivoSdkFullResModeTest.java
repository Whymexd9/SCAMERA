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
}
