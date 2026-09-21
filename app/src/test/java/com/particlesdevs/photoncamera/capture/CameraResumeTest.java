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
    @org.robolectric.annotation.Implements(value=com.particlesdevs.photoncamera.util.Allocator.class, isInAndroidSdk=false)
    public static class ShadowAllocator {
        @org.robolectric.annotation.Implementation
        protected static void __staticInitializer__() { /* Native memory is mocked in this selection test. */ }
    }
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
    private Image rawImage(long timestamp) {
        Image image=mock(Image.class);Image.Plane plane=mock(Image.Plane.class);
        when(image.getTimestamp()).thenReturn(timestamp);
        when(image.getFormat()).thenReturn(android.graphics.ImageFormat.RAW_SENSOR);
        when(image.getWidth()).thenReturn(64);when(image.getHeight()).thenReturn(64);
        when(image.getPlanes()).thenReturn(new Image.Plane[]{plane});
        when(plane.getRowStride()).thenReturn(128);when(plane.getPixelStride()).thenReturn(2);
        when(plane.getBuffer()).thenReturn(java.nio.ByteBuffer.allocate(64*64*2));
        return image;
    }
    private TotalCaptureResult exposure(long ns,int iso) {
        TotalCaptureResult result=mock(TotalCaptureResult.class);
        when(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(ns);
        when(result.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(iso);
        return result;
    }
    @Test
    @Config(shadows=ShadowAllocator.class, instrumentedPackages="com.particlesdevs.photoncamera.util")
    public void niceAcceptsArbitraryBayerSensorAndRequiresItsNoiseProfile() throws Exception {
        var p=new com.particlesdevs.photoncamera.processing.render.Parameters();
        p.rawSize=new android.graphics.Point(64,64);p.physicalID=77;p.cfaPattern=3;p.whiteLevel=1023;
        var frames=new java.util.ArrayList<com.particlesdevs.photoncamera.processing.ImageFrame>();
        for(int i=0;i<7;i++) {
            var f=mock(com.particlesdevs.photoncamera.processing.ImageFrame.class);
            frames.add(f);
            f.width=64;f.height=64;f.buffer=java.nio.ByteBuffer.allocate(64*64*2);
            f.timestamp=(i+1)*1_000_000_000L;
            f.measuredIso=25600;f.measuredExposure=i<4?1000000:i==4?2000000:i==5?250000:62500;
            var role=i<4?com.particlesdevs.photoncamera.processing.ImageFrame.CaptureRole.NORMAL
                    :i==4?com.particlesdevs.photoncamera.processing.ImageFrame.CaptureRole.LONG
                    :i==5?com.particlesdevs.photoncamera.processing.ImageFrame.CaptureRole.SHORT
                    :com.particlesdevs.photoncamera.processing.ImageFrame.CaptureRole.EXTRA_SHORT;
            when(f.getCaptureRole()).thenReturn(role);
            f.noiseSlope=.00015f;f.noiseOffset=.000002f;
            var metadata=exposure(f.measuredExposure,f.measuredIso);
            when(metadata.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(f.timestamp);
            when(f.getMatchedCaptureMetadata()).thenReturn(metadata);
            f.pair=mock(com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector.ExpoPair.class);
        }
        var normal=frames.get(0);
        var constructor=com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNiceBurst.class
                .getDeclaredConstructor(java.util.List.class,com.particlesdevs.photoncamera.processing.render.Parameters.class);
        constructor.setAccessible(true);
        try(var prefs=mockStatic(PreferenceKeys.class)) {
            assertNotNull(constructor.newInstance(frames,p));
            normal.noiseSlope=Float.NaN;
            var error=assertThrows(java.lang.reflect.InvocationTargetException.class,
                    ()->constructor.newInstance(frames,p));
            assertTrue(error.getCause().getMessage().contains("Camera2"));
            normal.noiseSlope=.00015f;p.quadCfa=true;
            error=assertThrows(java.lang.reflect.InvocationTargetException.class,
                    ()->constructor.newInstance(frames,p));
            assertTrue(error.getCause().getMessage().contains("Bayer"));
        }
    }
    @Test
    @Config(shadows=ShadowAllocator.class, instrumentedPackages="com.particlesdevs.photoncamera.util")
    public void niceZslSelectsOlderMatchedFrameWhenNewestResultIsLate() throws Exception {
        put(controller,"niceZslShutterTimestamp",9L);
        var ring=(ArrayDeque<Image>)get(controller,"mZslRingBuffer");
        var metadata=(java.util.Map<Long,TotalCaptureResult>)get(controller,"mHexZslResults");
        java.util.List<Image> raws=new java.util.ArrayList<>();
        for(long timestamp=1;timestamp<=9;timestamp++) {
            Image image=rawImage(timestamp);raws.add(image);ring.add(image);
            if(timestamp<9)metadata.put(timestamp,exposure(24_999_987L,10775));
        }
        var method=CaptureController.class.getDeclaredMethod("drainZslNormalFrames",int.class);
        method.setAccessible(true);
        // Native copying is unrelated to timestamp selection; retain the actual
        // controller method and inspect which metadata it attaches to each copy.
        try(var prefs=mockStatic(PreferenceKeys.class);
            var copies=mockConstruction(com.particlesdevs.photoncamera.processing.ImageFrame.class)) {
            prefs.when(PreferenceKeys::isVivoNiceEnabled).thenReturn(true);
            var results=new java.util.HashMap<>(metadata);
            var frames=(java.util.List<com.particlesdevs.photoncamera.processing.ImageFrame>)method.invoke(controller,8);
            assertEquals(8,frames.size());assertEquals(8,copies.constructed().size());
            for(int i=0;i<8;i++) {
                assertEquals(i+1,frames.get(i).timestamp);
                verify(frames.get(i)).setCaptureMetadata(results.get((long)i+1));
            }
            for(Image image:raws)verify(image,times(1)).close();
            assertTrue(ring.isEmpty());assertTrue(metadata.isEmpty());
        }
    }
    @Test
    @Config(shadows=ShadowAllocator.class, instrumentedPackages="com.particlesdevs.photoncamera.util")
    public void niceZslUsesShutterCutoffAndCurrentRawForBracketBase() throws Exception {
        var ring=(ArrayDeque<Image>)get(controller,"mZslRingBuffer");
        var metadata=(java.util.Map<Long,TotalCaptureResult>)get(controller,"mHexZslResults");
        // Out-of-order delivery, a post-press frame and an unrelated preview
        // exposure must not change this shot's selection or bracket base.
        for(long timestamp:new long[]{5,3,7,2,6,4}) {
            ring.add(rawImage(timestamp));metadata.put(timestamp,exposure(timestamp*1000000,100));
        }
        TotalCaptureResult expected=metadata.get(6L);
        put(controller,"niceZslShutterTimestamp",6L);
        put(controller,"mPreviewCaptureResult",exposure(99000000,800));
        var method=CaptureController.class.getDeclaredMethod("drainZslNormalFrames",int.class);
        method.setAccessible(true);
        try(var prefs=mockStatic(PreferenceKeys.class);
            var copies=mockConstruction(com.particlesdevs.photoncamera.processing.ImageFrame.class)) {
            prefs.when(PreferenceKeys::isVivoNiceEnabled).thenReturn(true);
            var frames=(java.util.List<com.particlesdevs.photoncamera.processing.ImageFrame>)method.invoke(controller,4);
            assertEquals(4,frames.size());
            for(int i=0;i<4;i++)assertEquals(i+3,frames.get(i).timestamp);
            assertSame(expected,get(controller,"mNativeZslBase"));
        }
    }
    @Test public void niceZslWithoutShutterTimestampDoesNotClaimBufferedFrames() throws Exception {
        var ring=(ArrayDeque<Image>)get(controller,"mZslRingBuffer");
        var metadata=(java.util.Map<Long,TotalCaptureResult>)get(controller,"mHexZslResults");
        Image image=rawImage(1);ring.add(image);metadata.put(1L,exposure(25000000,100));
        put(controller,"niceZslShutterTimestamp",0L);
        var method=CaptureController.class.getDeclaredMethod("drainZslNormalFrames",int.class);
        method.setAccessible(true);
        try(var prefs=mockStatic(PreferenceKeys.class);
            var copies=mockConstruction(com.particlesdevs.photoncamera.processing.ImageFrame.class)) {
            prefs.when(PreferenceKeys::isVivoNiceEnabled).thenReturn(true);
            assertTrue(((java.util.List<?>)method.invoke(controller,4)).isEmpty());
            assertTrue(copies.constructed().isEmpty());verify(image).close();
        }
    }
    @Test public void delayedPreviewCannotConsumeNiceBracketSlot() throws Exception {
        var saver=mock(com.particlesdevs.photoncamera.processing.ImageSaver.class);
        put(controller,"mImageSaver",saver);put(controller,"mZslCapturing",true);
        var router=(TimestampFrameRouter<Image>)get(controller,"mLiveRawRouter");
        Image preview=rawImage(10),shortRaw=rawImage(20);
        router.image(10,preview);router.request(20,true);
        router.image(20,shortRaw);router.request(10,false);
        verify(preview).close();verify(saver).initProcess(shortRaw);
        verify(saver,never()).initProcess(preview);
    }
    @Test public void niceZslWithoutMeasuredExposureReturnsEmptyForManualFallback() throws Exception {
        put(controller,"niceZslShutterTimestamp",3L);
        var ring=(ArrayDeque<Image>)get(controller,"mZslRingBuffer");
        var metadata=(java.util.Map<Long,TotalCaptureResult>)get(controller,"mHexZslResults");
        Image missing=rawImage(1),zeroTime=rawImage(2),zeroIso=rawImage(3);
        ring.add(missing);ring.add(zeroTime);ring.add(zeroIso);
        metadata.put(2L,exposure(0,100));metadata.put(3L,exposure(25_000_000,0));
        var method=CaptureController.class.getDeclaredMethod("drainZslNormalFrames",int.class);
        method.setAccessible(true);
        try(var prefs=mockStatic(PreferenceKeys.class);
            var copies=mockConstruction(com.particlesdevs.photoncamera.processing.ImageFrame.class)) {
            prefs.when(PreferenceKeys::isVivoNiceEnabled).thenReturn(true);
            assertTrue(((java.util.List<?>)method.invoke(controller,8)).isEmpty());
            assertTrue(copies.constructed().isEmpty());
            verify(missing).close();verify(zeroTime).close();verify(zeroIso).close();
        }
    }
    @Test public void oldPreviewCallbacksCannotEnterNewSession() throws Exception {
        CameraCaptureSession old=mock(CameraCaptureSession.class),current=mock(CameraCaptureSession.class);
        put(controller,"isCameraResumed",true);put(controller,"mCaptureSession",current);
        put(controller,"mConfiguredSessionGeneration",0);
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
        put(controller,"mConfiguredSessionGeneration",0);
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
    @Test public void busyShutterDeclinesWithoutStartingOrCancellingExistingWork() throws Exception {
        CaptureController.isProcessing=true;
        assertFalse(controller.takePicture());
        assertTrue(CaptureController.isProcessing);
        verifyNoInteractions(events);
        CaptureController.isProcessing=false;
        put(controller,"mZslCapturing",true);
        assertFalse(controller.takePicture());
        assertEquals(true,get(controller,"mZslCapturing"));
        verifyNoInteractions(events);
    }
    @Test public void rejectedAuxiliaryPreviewRequestReleasesPendingShot() throws Exception {
        controller.isDualSession=true;
        CameraCaptureSession session=mock(CameraCaptureSession.class);
        put(controller,"isCameraResumed",true);put(controller,"mCaptureSession",session);
        put(controller,"mConfiguredSessionGeneration",0);
        put(controller,"mCameraDevice",mock(CameraDevice.class));
        put(controller,"mCameraAfModes",new int[]{0});
        controller.mPreviewRequestBuilder=newRequestBuilder();
        CaptureController.mPreviewCaptureResult=mock(TotalCaptureResult.class);
        when(session.setRepeatingRequest(any(),any(),isNull()))
                .thenThrow(new IllegalStateException("session closed"));
        assertFalse(controller.takePicture());
        assertEquals(false,get(controller,"mShotInProgress"));
        verify(events).onProcessingError(contains("session closed"));
        // Retrying is accepted once the HAL is ready; no app restart required.
        doReturn(1).when(session).setRepeatingRequest(any(),any(),isNull());
        assertTrue(controller.takePicture());
    }
    @Test public void shutterRejectsMetadataUntilNewSessionIsConfigured() throws Exception {
        CameraCaptureSession session=mock(CameraCaptureSession.class);
        put(controller,"isCameraResumed",true);put(controller,"mCaptureSession",session);
        put(controller,"mConfiguredSessionGeneration",0);
        put(controller,"mCameraDevice",mock(CameraDevice.class));
        controller.mPreviewRequestBuilder=newRequestBuilder();
        CaptureController.mPreviewCaptureResult=mock(TotalCaptureResult.class);
        ((java.util.concurrent.atomic.AtomicInteger)get(controller,"mSessionGeneration")).incrementAndGet();
        assertFalse(controller.takePicture());
        verifyNoInteractions(session);
        verify(events).onProcessingError(any());
        assertEquals(false,get(controller,"mShotInProgress"));
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
