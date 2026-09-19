package com.particlesdevs.photoncamera.capture;

import com.particlesdevs.photoncamera.api.CameraEventsListener;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import com.particlesdevs.photoncamera.api.CameraManager2;
import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class CameraResumeTest {
    private CaptureController controller;
    private MockedStatic<PhotonCamera> photon;
    private MockedConstruction<CameraManager2> managers;
    private CameraEventsListener events;
    private static Object get(Object o,String name) throws Exception {Field f=CaptureController.class.getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void put(Object o,String name,Object value) throws Exception {Field f=CaptureController.class.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static CaptureRequest.Builder newRequestBuilder() throws Exception {
        Class<?> metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        return CaptureRequest.Builder.class.getConstructor(metadata, boolean.class, int.class, String.class, java.util.Set.class)
                .newInstance(metadata.getConstructor().newInstance(), false, -1, "3", null);
    }
    @Before public void setup() {
        Context context=RuntimeEnvironment.getApplication();SettingsManager settings=new SettingsManager(context);
        photon=mockStatic(PhotonCamera.class);
        photon.when(PhotonCamera::getAppContext).thenReturn(context);
        photon.when(PhotonCamera::getResourcesStatic).thenReturn(context.getResources());
        photon.when(PhotonCamera::getSettingsManagerStatic).thenReturn(settings);
        photon.when(PhotonCamera::getSettings).thenReturn(new Settings());
        PhotonCamera app=mock(PhotonCamera.class);when(app.getSettingsManager()).thenReturn(settings);
        photon.when(()->PhotonCamera.getInstance(any(Context.class))).thenReturn(app);
        photon.when(()->PhotonCamera.getStringStatic(anyInt())).thenAnswer(i->context.getString(i.getArgument(0)));
        PreferenceKeys.initialise(settings);
        managers=mockConstruction(CameraManager2.class,(mock,c)->when(mock.getCameraIdList()).thenReturn(new String[]{"3"}));
        Activity activity=mock(Activity.class);CameraManager manager=mock(CameraManager.class);
        when(activity.getSystemService(Context.CAMERA_SERVICE)).thenReturn(manager);
        events=mock(CameraEventsListener.class);
        controller=new CaptureController(activity,mock(ExecutorService.class),events);
        CaptureController.isProcessing=false;
    }
    @After public void cleanup(){CaptureController.mPreviewCaptureResult=null;CaptureController.mPreviewCaptureRequest=null;managers.close();photon.close();}
    @Test public void oldPreviewCallbacksCannotEnterNewSession() throws Exception {
        CameraCaptureSession old=mock(CameraCaptureSession.class),current=mock(CameraCaptureSession.class);
        put(controller,"isCameraResumed",true);put(controller,"mCaptureSession",current);
        controller.mPreviewRequestBuilder=newRequestBuilder();
        var callback=(CameraCaptureSession.CaptureCallback)get(controller,"mCaptureCallback");
        CaptureRequest request=mock(CaptureRequest.class);TotalCaptureResult result=mock(TotalCaptureResult.class);
        callback.onCaptureStarted(old,request,17,1);
        callback.onCaptureProgressed(old,request,result);
        callback.onCaptureCompleted(old,request,result);
        verifyNoInteractions(result,events);
        assertNull(CaptureController.mPreviewCaptureResult);
    }
    @Test public void closeDrainsZslBeforeClosingReaderAndDropsLaterCallbacks() throws Exception {
        Image image=mock(Image.class);ImageReader raw=mock(ImageReader.class);
        ((ArrayDeque<Image>)get(controller,"mZslRingBuffer")).add(image);
        controller.mImageReaderRaw=raw; // Preview reader may independently be null.
        controller.mPreviewRequestBuilder=newRequestBuilder();
        CaptureController.mPreviewCaptureResult=mock(TotalCaptureResult.class);
        CaptureController.mPreviewCaptureRequest=mock(CaptureRequest.class);
        CameraCaptureSession session=mock(CameraCaptureSession.class);put(controller,"mCaptureSession",session);
        put(controller,"isCameraResumed",true);
        controller.closeCamera();
        var order=inOrder(image,raw);order.verify(image).close();order.verify(raw).close();
        assertTrue(((ArrayDeque<?>)get(controller,"mZslRingBuffer")).isEmpty());
        assertNull(controller.mImageReaderRaw);assertNull(controller.mPreviewRequestBuilder);
        assertNull(CaptureController.mPreviewCaptureResult);assertNull(CaptureController.mPreviewCaptureRequest);
        TotalCaptureResult late=mock(TotalCaptureResult.class);
        ((CameraCaptureSession.CaptureCallback)get(controller,"mCaptureCallback")).onCaptureCompleted(session,mock(CaptureRequest.class),late);
        verifyNoInteractions(late,events);
    }
    @Test public void zslCapacityIsGlobalAndIndependentOfFrameCount() {
        SettingsManager manager=PhotonCamera.getSettingsManagerStatic();
        assertEquals(50,CaptureController.zslRingCapacity());
        assertFalse(ModuleProfiles.isLocal("pref_zsl_buffer_count_key"));
        manager.set("default_scope","pref_raw_mfsr_enabled_key",true);
        manager.set("default_scope","pref_mfsr_frames_key","3");
        assertEquals(50,CaptureController.zslRingCapacity());
        manager.set("default_scope","pref_zsl_buffer_count_key","32");
        assertEquals(32,CaptureController.zslRingCapacity());
        SettingsMigration.migrateMultiFrame(manager.getDefaultPreferences());
        assertEquals(32,CaptureController.zslRingCapacity());
        assertNull(new SettingsAvailability(manager.getDefaultPreferences().getAll()).reason("pref_zsl_buffer_count_key"));
    }
    @Test public void nativeMfsrUsesRollingRawAcrossStillModes() throws Exception {
        PhotonCamera.getSettingsManagerStatic().set("default_scope","pref_raw_mfsr_enabled_key",true);
        for(com.particlesdevs.photoncamera.api.CameraMode mode:new com.particlesdevs.photoncamera.api.CameraMode[]{
                com.particlesdevs.photoncamera.api.CameraMode.MOTION,com.particlesdevs.photoncamera.api.CameraMode.PHOTO,
                com.particlesdevs.photoncamera.api.CameraMode.NIGHT}) {
            PhotonCamera.getSettings().selectedMode=mode;assertTrue(controller.isZslMode());
        }
        PhotonCamera.getSettings().selectedMode=com.particlesdevs.photoncamera.api.CameraMode.RAWVIDEO;
        assertFalse(controller.isZslMode());
    }
    @Test public void shutterWaitsForFreshMetadataThenSubmitsWithoutRestart() throws Exception {
        controller.isDualSession=true;
        CameraCaptureSession session=mock(CameraCaptureSession.class);
        put(controller,"isCameraResumed",true);put(controller,"mCaptureSession",session);
        put(controller,"mCameraDevice",mock(CameraDevice.class));
        put(controller,"mCameraAfModes",new int[]{0});
        controller.mPreviewRequestBuilder=newRequestBuilder();

        controller.takePicture();
        verify(events).onProcessingError(any());verifyNoInteractions(session);
        assertEquals(false,get(controller,"mShotInProgress"));
        CaptureController.mPreviewCaptureResult=mock(TotalCaptureResult.class);
        controller.takePicture();
        verify(session).setRepeatingRequest(any(CaptureRequest.class),any(),isNull());
        verify(events,never()).onCameraRestarted();
        assertEquals(true,get(controller,"mShotInProgress"));
    }
    @Test public void restartUsesTheNormalPreparedResumePath() throws Exception {
        var spy=spy(controller);
        doNothing().when(spy).startBackgroundThread();
        doNothing().when(spy).resumeCamera();
        spy.restartCamera();
        var order=inOrder(spy,events);
        order.verify(spy).closeCamera();
        order.verify(events).onCameraRestarted();
        order.verify(spy).startBackgroundThread();
        order.verify(spy).resumeCamera();
    }

}
