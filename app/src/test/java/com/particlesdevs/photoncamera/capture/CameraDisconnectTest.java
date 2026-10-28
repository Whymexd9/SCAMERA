package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;

import com.particlesdevs.photoncamera.api.CameraEventsListener;
import com.particlesdevs.photoncamera.api.CameraManager2;
import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Bug capture-disconnect-no-reopen: another client took the camera (split screen, a pop-up window) while the camera screen stayed
 * resumed - no onResume follows, so nothing reopened it and the viewfinder stayed frozen. It reopens once the camera is available
 * again or the access priorities change while this window has the focus.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CameraDisconnectTest {
    private CaptureController controller;
    private MockedStatic<ScameraApp> appStatic;
    private MockedConstruction<CameraManager2> managers;
    private CameraEventsListener events;
    private CameraManager manager;
    private Activity activity;

    private static Object get(Object o, String name) throws Exception {
        Field f = CaptureController.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void put(Object o, String name, Object value) throws Exception {
        Field f = CaptureController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    @Before
    public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        SettingsManager settings = new SettingsManager(context);
        appStatic = mockStatic(ScameraApp.class);
        appStatic.when(ScameraApp::getAppContext).thenReturn(context);
        appStatic.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        appStatic.when(ScameraApp::getSettingsManagerStatic).thenReturn(settings);
        appStatic.when(ScameraApp::getSettings).thenReturn(new Settings());
        ScameraApp app = mock(ScameraApp.class);
        when(app.getSettingsManager()).thenReturn(settings);
        appStatic.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        appStatic.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.getArgument(0)));
        PreferenceKeys.initialise(settings);
        managers = mockConstruction(CameraManager2.class, (mock, c) -> when(mock.getCameraIdList()).thenReturn(new String[]{"3"}));
        activity = mock(Activity.class);
        manager = mock(CameraManager.class);
        when(activity.getSystemService(Context.CAMERA_SERVICE)).thenReturn(manager);
        events = mock(CameraEventsListener.class);
        controller = new CaptureController(activity, mock(ExecutorService.class), events);
        CaptureController.isProcessing = false;
    }

    @After
    public void cleanup() {
        CaptureController.mPreviewCaptureResult = null;
        CaptureController.mPreviewCaptureRequest = null;
        managers.close();
        appStatic.close();
    }

    private CameraManager.AvailabilityCallback disconnect(String id) throws Exception {
        return disconnect(id, 1);
    }

    /** The camera disconnected while resumed: the wait it registered (the {@code nth} registration). */
    private CameraManager.AvailabilityCallback disconnect(String id, int nth) throws Exception {
        final CameraDevice device = mock(CameraDevice.class);
        when(device.getId()).thenReturn(id);
        put(controller, "isCameraResumed", true);
        put(controller, "mCameraDevice", device);
        ((CameraDevice.StateCallback) get(controller, "mStateCallback")).onDisconnected(device);
        assertNull(get(controller, "mCameraDevice"));
        final ArgumentCaptor<CameraManager.AvailabilityCallback> registered = ArgumentCaptor.forClass(CameraManager.AvailabilityCallback.class);
        verify(manager, times(nth)).registerAvailabilityCallback(registered.capture(), any(Handler.class));
        final List<CameraManager.AvailabilityCallback> all = registered.getAllValues();
        return all.get(all.size() - 1);
    }

    private static void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    @Test
    public void disconnectWhileResumedReopensWhenTheCameraIsBack() throws Exception {
        final CameraManager.AvailabilityCallback callback = disconnect("3");
        callback.onCameraAvailable("5");
        verify(events, never()).onCameraRestarted();
        callback.onCameraAvailable("3");
        verify(events, times(1)).onCameraRestarted();
        verify(manager).unregisterAvailabilityCallback(callback);
        callback.onCameraAvailable("3");
        verify(events, times(1)).onCameraRestarted();
    }

    @Test
    public void prioritiesReopenOnlyTheFocusedWindow() throws Exception {
        final CameraManager.AvailabilityCallback callback = disconnect("3");
        when(activity.hasWindowFocus()).thenReturn(false);
        callback.onCameraAccessPrioritiesChanged();
        verify(events, never()).onCameraRestarted();
        when(activity.hasWindowFocus()).thenReturn(true);
        callback.onCameraAccessPrioritiesChanged();
        verify(events, times(1)).onCameraRestarted();
    }

    /**
     * Review: the window focus can reach the main thread after the priorities change (in split screen the other app keeps the
     * camera when it loses the focus: no onCameraAvailable follows); the focus is looked at again shortly.
     */
    @Test
    public void prioritiesBeforeTheFocusReopenOnceTheFocusArrives() throws Exception {
        final CameraManager.AvailabilityCallback callback = disconnect("3");
        when(activity.hasWindowFocus()).thenReturn(false);
        callback.onCameraAccessPrioritiesChanged();
        verify(events, never()).onCameraRestarted();
        when(activity.hasWindowFocus()).thenReturn(true);
        idle(CaptureController.CAMERA_BACK_FOCUS_MS);
        verify(events, times(1)).onCameraRestarted();
        verify(manager).unregisterAvailabilityCallback(callback);
    }

    /** No focus at the second look either: nothing opens, the wait stays for the next change. */
    @Test
    public void prioritiesWithoutTheFocusKeepWaiting() throws Exception {
        final CameraManager.AvailabilityCallback callback = disconnect("3");
        when(activity.hasWindowFocus()).thenReturn(false);
        callback.onCameraAccessPrioritiesChanged();
        idle(CaptureController.CAMERA_BACK_FOCUS_MS * 2);
        verify(events, never()).onCameraRestarted();
        assertSame(callback, get(controller, "mCameraBackCallback"));
        callback.onCameraAvailable("3");
        verify(events, times(1)).onCameraRestarted();
    }

    /**
     * Review: a second disconnect soon after a reopen (the user tapping into the other split-screen app within 3 s) was not
     * waited for at all - the viewfinder froze again. It is waited for; the reopen is held back to one per CAMERA_BACK_MIN_MS.
     */
    @Test
    public void aDisconnectRightAfterAReopenIsWaitedFor() throws Exception {
        disconnect("3").onCameraAvailable("3");
        verify(events, times(1)).onCameraRestarted();
        final CameraManager.AvailabilityCallback again = disconnect("3", 2);
        assertSame(again, get(controller, "mCameraBackCallback"));
        again.onCameraAvailable("3");
        again.onCameraAvailable("3");
        verify(events, times(1)).onCameraRestarted();
        idle(CaptureController.CAMERA_BACK_MIN_MS / 2);
        verify(events, times(1)).onCameraRestarted();
        idle(CaptureController.CAMERA_BACK_MIN_MS);
        verify(events, times(2)).onCameraRestarted();
        verify(manager).unregisterAvailabilityCallback(again);
    }

    /** A held-back reopen whose camera was taken again meanwhile (and no focus) waits for the next change instead of failing. */
    @Test
    public void aHeldReopenNoLongerDueKeepsWaiting() throws Exception {
        when(activity.hasWindowFocus()).thenReturn(false);
        disconnect("3").onCameraAvailable("3");
        final CameraManager.AvailabilityCallback again = disconnect("3", 2);
        again.onCameraAvailable("3");
        again.onCameraUnavailable("3");
        idle(CaptureController.CAMERA_BACK_MIN_MS * 2);
        verify(events, times(1)).onCameraRestarted();
        assertSame(again, get(controller, "mCameraBackCallback"));
        again.onCameraAvailable("3");
        verify(events, times(2)).onCameraRestarted();
    }

    /** Closing the camera while a reopen is held back cancels it. */
    @Test
    public void closingTheCameraCancelsAHeldReopen() throws Exception {
        disconnect("3").onCameraAvailable("3");
        disconnect("3", 2).onCameraAvailable("3");
        controller.closeCamera();
        idle(CaptureController.CAMERA_BACK_MIN_MS * 2);
        verify(events, times(1)).onCameraRestarted();
    }

    @Test
    public void closingTheCameraEndsTheWait() throws Exception {
        final CameraManager.AvailabilityCallback callback = disconnect("3");
        assertSame(callback, get(controller, "mCameraBackCallback"));
        controller.closeCamera();
        verify(manager).unregisterAvailabilityCallback(callback);
        assertNull(get(controller, "mCameraBackCallback"));
        callback.onCameraAvailable("3");
        verify(events, never()).onCameraRestarted();
    }

    @Test
    public void aPausedScreenWaitsForNothing() throws Exception {
        final CameraDevice device = mock(CameraDevice.class);
        when(device.getId()).thenReturn("3");
        put(controller, "isCameraResumed", false);
        ((CameraDevice.StateCallback) get(controller, "mStateCallback")).onDisconnected(device);
        verify(manager, never()).registerAvailabilityCallback(any(CameraManager.AvailabilityCallback.class), any(Handler.class));
    }
}
