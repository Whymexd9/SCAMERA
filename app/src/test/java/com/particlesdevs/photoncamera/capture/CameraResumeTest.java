package com.particlesdevs.photoncamera.capture;

import com.particlesdevs.photoncamera.processing.RingFrame;

import com.particlesdevs.photoncamera.api.CameraEventsListener;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import com.particlesdevs.photoncamera.api.CameraManager2;
import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.app.ScameraApp;
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
    private MockedStatic<ScameraApp> appStatic;
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
        appStatic=mockStatic(ScameraApp.class);
        appStatic.when(ScameraApp::getAppContext).thenReturn(context);
        appStatic.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        appStatic.when(ScameraApp::getSettingsManagerStatic).thenReturn(settings);
        appStatic.when(ScameraApp::getSettings).thenReturn(new Settings());
        ScameraApp app=mock(ScameraApp.class);when(app.getSettingsManager()).thenReturn(settings);
        appStatic.when(()->ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        appStatic.when(()->ScameraApp.getStringStatic(anyInt())).thenAnswer(i->context.getString(i.getArgument(0)));
        PreferenceKeys.initialise(settings);
        managers=mockConstruction(CameraManager2.class,(mock,c)->when(mock.getCameraIdList()).thenReturn(new String[]{"3"}));
        Activity activity=mock(Activity.class);CameraManager manager=mock(CameraManager.class);
        when(activity.getSystemService(Context.CAMERA_SERVICE)).thenReturn(manager);
        events=mock(CameraEventsListener.class);
        controller=new CaptureController(activity,mock(ExecutorService.class),events);
        CaptureController.isProcessing=false;
    }
    @After public void cleanup(){CaptureController.mPreviewCaptureResult=null;CaptureController.mPreviewCaptureRequest=null;managers.close();appStatic.close();}
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
    public void scamZslSelectsOlderMatchedFrameWhenNewestResultIsLate() throws Exception {
        put(controller,"scamZslShutterTimestamp",9L);
        var ring=(ArrayDeque<RingFrame>)get(controller,"mZslRingBuffer");
        var metadata=(java.util.Map<Long,TotalCaptureResult>)get(controller,"mHexZslResults");
        java.util.List<Image> raws=new java.util.ArrayList<>();
        for(long timestamp=1;timestamp<=9;timestamp++) {
            Image image=rawImage(timestamp);raws.add(image);ring.add(RingFrame.of(image));
            if(timestamp<9)metadata.put(timestamp,exposure(24_999_987L,10775));
        }
        var method=CaptureController.class.getDeclaredMethod("drainZslNormalFrames",int.class,ScamStockAe.Plan.class,boolean.class);
        method.setAccessible(true);
        // Native copying is unrelated to timestamp selection; retain the actual
        // controller method and inspect which metadata it attaches to each copy.
        try(var prefs=mockStatic(PreferenceKeys.class);
            var copies=mockConstruction(com.particlesdevs.photoncamera.processing.ImageFrame.class)) {
            prefs.when(PreferenceKeys::isScamEnabled).thenReturn(true);
            var results=new java.util.HashMap<>(metadata);
            var frames=(java.util.List<com.particlesdevs.photoncamera.processing.ImageFrame>)method.invoke(controller,8,null,false);
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
    public void scamZslUsesShutterCutoffAndCurrentRawForBracketBase() throws Exception {
        var ring=(ArrayDeque<RingFrame>)get(controller,"mZslRingBuffer");
        var metadata=(java.util.Map<Long,TotalCaptureResult>)get(controller,"mHexZslResults");
        // Out-of-order delivery, a post-press frame and an unrelated preview
        // exposure must not change this shot's selection or bracket base.
        for(long timestamp:new long[]{5,3,7,2,6,4}) {
            ring.add(RingFrame.of(rawImage(timestamp)));metadata.put(timestamp,exposure(timestamp*1000000,100));
        }
        TotalCaptureResult expected=metadata.get(6L);
        put(controller,"scamZslShutterTimestamp",6L);
        put(controller,"mPreviewCaptureResult",exposure(99000000,800));
        var method=CaptureController.class.getDeclaredMethod("drainZslNormalFrames",int.class,ScamStockAe.Plan.class,boolean.class);
        method.setAccessible(true);
        try(var prefs=mockStatic(PreferenceKeys.class);
            var copies=mockConstruction(com.particlesdevs.photoncamera.processing.ImageFrame.class)) {
            prefs.when(PreferenceKeys::isScamEnabled).thenReturn(true);
            var frames=(java.util.List<com.particlesdevs.photoncamera.processing.ImageFrame>)method.invoke(controller,4,null,false);
            assertEquals(4,frames.size());
            for(int i=0;i<4;i++)assertEquals(i+3,frames.get(i).timestamp);
            assertSame(expected,get(controller,"mNativeZslBase"));
        }
    }
    @Test public void scamZslWithoutShutterTimestampDoesNotClaimBufferedFrames() throws Exception {
        var ring=(ArrayDeque<RingFrame>)get(controller,"mZslRingBuffer");
        var metadata=(java.util.Map<Long,TotalCaptureResult>)get(controller,"mHexZslResults");
        Image image=rawImage(1);ring.add(RingFrame.of(image));metadata.put(1L,exposure(25000000,100));
        put(controller,"scamZslShutterTimestamp",0L);
        var method=CaptureController.class.getDeclaredMethod("drainZslNormalFrames",int.class,ScamStockAe.Plan.class,boolean.class);
        method.setAccessible(true);
        try(var prefs=mockStatic(PreferenceKeys.class);
            var copies=mockConstruction(com.particlesdevs.photoncamera.processing.ImageFrame.class)) {
            prefs.when(PreferenceKeys::isScamEnabled).thenReturn(true);
            assertTrue(((java.util.List<?>)method.invoke(controller,4,null,false)).isEmpty());
            assertTrue(copies.constructed().isEmpty());verify(image).close();
        }
    }
    @Test public void delayedPreviewCannotConsumeScamBracketSlot() throws Exception {
        var saver=mock(com.particlesdevs.photoncamera.processing.ImageSaver.class);
        put(controller,"mImageSaver",saver);put(controller,"mZslCapturing",true);
        var router=(TimestampFrameRouter<Image>)get(controller,"mLiveRawRouter");
        Image preview=rawImage(10),shortRaw=rawImage(20);
        router.image(10,preview);router.request(20,true);
        router.image(20,shortRaw);router.request(10,false);
        verify(preview).close();verify(saver).initProcess(shortRaw);
        verify(saver,never()).initProcess(preview);
    }
    @Test public void scamZslWithoutMeasuredExposureReturnsEmptyForManualFallback() throws Exception {
        put(controller,"scamZslShutterTimestamp",3L);
        var ring=(ArrayDeque<RingFrame>)get(controller,"mZslRingBuffer");
        var metadata=(java.util.Map<Long,TotalCaptureResult>)get(controller,"mHexZslResults");
        Image missing=rawImage(1),zeroTime=rawImage(2),zeroIso=rawImage(3);
        ring.add(RingFrame.of(missing));ring.add(RingFrame.of(zeroTime));ring.add(RingFrame.of(zeroIso));
        metadata.put(2L,exposure(0,100));metadata.put(3L,exposure(25_000_000,0));
        var method=CaptureController.class.getDeclaredMethod("drainZslNormalFrames",int.class,ScamStockAe.Plan.class,boolean.class);
        method.setAccessible(true);
        try(var prefs=mockStatic(PreferenceKeys.class);
            var copies=mockConstruction(com.particlesdevs.photoncamera.processing.ImageFrame.class)) {
            prefs.when(PreferenceKeys::isScamEnabled).thenReturn(true);
            assertTrue(((java.util.List<?>)method.invoke(controller,8,null,false)).isEmpty());
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
        ((ArrayDeque<RingFrame>)get(controller,"mZslRingBuffer")).add(RingFrame.of(image));
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
        SettingsManager manager=ScameraApp.getSettingsManagerStatic();
        assertEquals(50,CaptureController.zslRingCapacity());
        assertFalse(ModuleProfiles.isLocal("pref_zsl_buffer_count_key"));
        assertEquals(50,CaptureController.zslRingCapacity());
        manager.set("default_scope","pref_zsl_buffer_count_key","32");
        assertEquals(32,CaptureController.zslRingCapacity());
        SettingsMigration.migrateMultiFrame(manager.getDefaultPreferences());
        assertEquals(32,CaptureController.zslRingCapacity());
        assertNull(new SettingsAvailability(manager.getDefaultPreferences().getAll()).reason("pref_zsl_buffer_count_key"));
    }
    @Test public void stillModesUseRollingRaw() throws Exception {
        for(com.particlesdevs.photoncamera.api.CameraMode mode:new com.particlesdevs.photoncamera.api.CameraMode[]{
                com.particlesdevs.photoncamera.api.CameraMode.MOTION,com.particlesdevs.photoncamera.api.CameraMode.PHOTO,
                com.particlesdevs.photoncamera.api.CameraMode.NIGHT}) {
            ScameraApp.getSettings().selectedMode=mode;assertTrue(controller.isZslMode());
        }
        ScameraApp.getSettings().selectedMode=com.particlesdevs.photoncamera.api.CameraMode.RAWVIDEO;
        assertFalse(controller.isZslMode());
    }
    @Test public void shutterWaitsForFreshMetadataThenSubmitsWithoutRestart() throws Exception {
        ScameraApp.getSettings().selectedMode=com.particlesdevs.photoncamera.api.CameraMode.RAWVIDEO; // the shutter path without the ZSL ring
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
        ScameraApp.getSettings().selectedMode=com.particlesdevs.photoncamera.api.CameraMode.RAWVIDEO; // the shutter path without the ZSL ring
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

    @SuppressWarnings("unchecked")
    private java.util.List<com.particlesdevs.photoncamera.processing.ImageFrame> drainHybrid(long cutoff,long[] timestamps,int[] isos,int requested) throws Exception {
        put(controller,"scamZslShutterTimestamp",cutoff);
        var ring=(ArrayDeque<RingFrame>)get(controller,"mZslRingBuffer");
        var metadata=(java.util.Map<Long,TotalCaptureResult>)get(controller,"mHexZslResults");
        for(int i=0;i<timestamps.length;i++){ring.add(RingFrame.of(rawImage(timestamps[i])));metadata.put(timestamps[i],exposure(10_000_000L,isos[i]));}
        var method=CaptureController.class.getDeclaredMethod("drainZslNormalFrames",int.class,ScamStockAe.Plan.class,boolean.class,boolean.class);
        method.setAccessible(true);
        try(var prefs=mockStatic(PreferenceKeys.class);
            var copies=mockConstruction(com.particlesdevs.photoncamera.processing.ImageFrame.class,(frame,c)->{})) {
            prefs.when(PreferenceKeys::isScamEnabled).thenReturn(true);
            var frames=(java.util.List<com.particlesdevs.photoncamera.processing.ImageFrame>)method.invoke(controller,requested,null,false,true);
            return new java.util.ArrayList<>(frames);
        }
    }
    @Test
    @Config(shadows=ShadowAllocator.class, instrumentedPackages="com.particlesdevs.photoncamera.util")
    public void hybridRingKeepsTheFramesAtTheNewestExposureDuringAnAeRamp() throws Exception {
        // X300 Ultra 2026-10-06 (log 235-258): AE ramp ISO 468 -> 75 after a module switch, the newest three frames at ISO 72.
        // SCAM HDR's rule (four frames within 0.05 EV of the plan) emptied the ring and the shot was lost in a strict
        // normal-back; the Hybrid keeps the frames at the newest exposure and never takes a brighter one.
        int[] isos={468,468,468,316,212,154,111,100,95,91,88,85,83,81,79,78,76,75,72,72,72};
        long[] ts=new long[isos.length];for(int i=0;i<ts.length;i++)ts[i]=i+1;
        var frames=drainHybrid(ts.length,ts,isos,20);
        assertEquals(3,frames.size());
        var stamps=new java.util.TreeSet<Long>();for(var f:frames)stamps.add(f.timestamp);
        assertEquals(java.util.Set.of(19L,20L,21L),stamps);
    }
    @Test
    @Config(shadows=ShadowAllocator.class, instrumentedPackages="com.particlesdevs.photoncamera.util")
    public void hybridRingWidensOnlyToDarkerFramesAndOnlyToFillEight() throws Exception {
        // Newest exposure ISO 100 with five more at it, six at -0.32 EV (ISO 80), two at -1 EV (ISO 50): 6 exact + 2 darker.
        int[] isos={50,50,80,80,80,80,80,80,100,100,100,100,100,100};
        long[] ts=new long[isos.length];for(int i=0;i<ts.length;i++)ts[i]=i+1;
        var frames=drainHybrid(ts.length,ts,isos,20);
        assertEquals(8,frames.size());
        for(var f:frames)assertTrue(f.timestamp>=3);
        // Enough exact frames: nothing is widened, the newest requested ones are taken as before.
        cleanupRing();
        var exact=drainHybrid(ts.length,ts,isos,4);
        assertEquals(4,exact.size());
        for(var f:exact)assertTrue(f.timestamp>=11);
    }
    @Test
    @Config(shadows=ShadowAllocator.class, instrumentedPackages="com.particlesdevs.photoncamera.util")
    public void hybridRingWithOneFrameIsUsed() throws Exception {
        var frames=drainHybrid(5,new long[]{5},new int[]{400},20);
        assertEquals(1,frames.size());
        assertEquals(5,frames.get(0).timestamp);
    }
    @SuppressWarnings("unchecked")
    private void cleanupRing() throws Exception {
        ((ArrayDeque<RingFrame>)get(controller,"mZslRingBuffer")).clear();
        ((java.util.Map<Long,TotalCaptureResult>)get(controller,"mHexZslResults")).clear();
    }

    /** OPPO PHY110 2026-10-07: the provider died during startup, getCameraCharacteristics threw and the app crashed. */
    @Test @SuppressWarnings("unchecked")
    public void providerDeathWhileReadingCharacteristicsSchedulesRecoveryInsteadOfCrashing() throws Exception {
        Activity activity=mock(Activity.class);CameraManager manager=mock(CameraManager.class);
        when(activity.getSystemService(Context.CAMERA_SERVICE)).thenReturn(manager);
        when(manager.getCameraCharacteristics("3")).thenThrow(new IllegalArgumentException(
                "getCameraCharacteristics:1370: Unable to retrieve camera characteristics for unknown device 3: No such file or directory (-2)"));
        CaptureController dying=new CaptureController(activity,mock(ExecutorService.class),events);
        assertTrue((Boolean)get(dying,"mCharacteristicsIncomplete"));
        assertEquals(1,((ArrayDeque<Long>)get(dying,"mRecoveries")).size());
    }

    private static CameraCharacteristics facing(int lensFacing) {
        CameraCharacteristics c=mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.LENS_FACING)).thenReturn(lensFacing);
        return c;
    }

    /** OPPO PHY110 modules: back0 "2x" and back4 "1x" on the main camera 2, back3 "2.8x" on the hidden tele 4. */
    private Settings oppoModules() {
        Settings settings=new Settings();settings.mCameraID="4";
        appStatic.when(ScameraApp::getSettings).thenReturn(settings);
        android.content.SharedPreferences.Editor e=ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit();
        for(String[] r:new String[][]{{"back0","2","2\u00d7"},{"back3","4","2.8\u00d7"},{"back4","2","1\u00d7"},{"front0","1","1\u00d7"}})
            e.putString("module_auto_"+r[0],r[1]).putBoolean("module_visible_"+r[0],true).putString("module_label_"+r[0],r[2]);
        e.putString("module_active","back3").commit();
        PreferenceKeys.setCameraID("4");
        return settings;
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String,CameraCharacteristics> coldStartList(CameraCharacteristics main) throws Exception {
        java.util.Map<String,CameraCharacteristics> map=(java.util.Map<String,CameraCharacteristics>)get(controller,"mCameraCharacteristicsMap");
        map.clear();map.put("1",facing(CameraCharacteristics.LENS_FACING_FRONT));map.put("2",main);
        map.put("3",facing(CameraCharacteristics.LENS_FACING_BACK));
        return map;
    }

    /** Cold start with the 2.8x tele selected and the list [1, 2, 3]: the main back camera under its 1x module, not the front one. */
    @Test public void missingTeleOpensTheMainBackCameraUnderItsOwnModule() throws Exception {
        Settings settings=oppoModules();
        CameraManager manager=(CameraManager)get(controller,"mCameraManager");
        when(manager.getCameraCharacteristics("4")).thenThrow(new IllegalArgumentException("unknown device 4"));
        CameraCharacteristics main=facing(CameraCharacteristics.LENS_FACING_BACK);
        coldStartList(main);
        var method=CaptureController.class.getDeclaredMethod("requestedCharacteristics");method.setAccessible(true);
        assertSame(main,method.invoke(controller));
        assertEquals("2",get(controller,"physicalID"));assertEquals("2",get(controller,"logicalID"));
        assertEquals("2",settings.mCameraID);assertEquals("2",PreferenceKeys.getCameraID());
        assertEquals("back4",ModuleRegistry.active());
        // the requested module comes back when its camera appears
        verify(manager).registerAvailabilityCallback(any(CameraManager.AvailabilityCallback.class),any(android.os.Handler.class));
    }

    /**
     * P186 (X300 Ultra: a FULLRES mode killed the main camera, the provider restarted, the app went to the ultra-wide): just after
     * a device error the requested camera is waited for - the module stays, nothing opens in its place, a restart is posted.
     */
    @Test public void aCameraJustDeadIsWaitedForNotReplaced() throws Exception {
        Settings settings=oppoModules();
        CameraManager manager=(CameraManager)get(controller,"mCameraManager");
        when(manager.getCameraCharacteristics("4")).thenThrow(new IllegalArgumentException("unknown device 4"));
        coldStartList(facing(CameraCharacteristics.LENS_FACING_BACK));
        var error=CaptureController.class.getDeclaredField("mDeviceErrorMs");error.setAccessible(true);
        error.setLong(controller,android.os.SystemClock.elapsedRealtime());
        var method=CaptureController.class.getDeclaredMethod("requestedCharacteristics");method.setAccessible(true);
        assertNull("not opened in its place yet",method.invoke(controller));
        assertEquals("4",settings.mCameraID);
        assertEquals("the module stays","back3",ModuleRegistry.active());
        verify(manager,never()).registerAvailabilityCallback(any(CameraManager.AvailabilityCallback.class),any(android.os.Handler.class));
    }

    /** A camera the start-up list lacks but the camera service knows is opened as requested. */
    @Test public void unlistedCameraKnownToTheServiceOpensAsRequested() throws Exception {
        Settings settings=oppoModules();
        CameraManager manager=(CameraManager)get(controller,"mCameraManager");
        CameraCharacteristics tele=facing(CameraCharacteristics.LENS_FACING_BACK);
        when(manager.getCameraCharacteristics("4")).thenReturn(tele);
        var map=coldStartList(facing(CameraCharacteristics.LENS_FACING_BACK));
        var method=CaptureController.class.getDeclaredMethod("requestedCharacteristics");method.setAccessible(true);
        assertSame(tele,method.invoke(controller));
        assertSame(tele,map.get("4"));
        assertEquals("4",get(controller,"physicalID"));assertEquals("4",settings.mCameraID);
        assertEquals("back3",ModuleRegistry.active());
    }

    /**
     * P212z (review): the native AE's ISO probe is known by its request object, not by a tag set on the shared preview builder - a
     * repeating request built from the builder afterwards (a UI rebuild) carries the viewfinder's own tag and ISO and is no probe,
     * so its frames still reach the RAW viewfinder, the AE and the native ring.
     */
    @Test public void theNativeIsoProbeLeavesThePreviewBuilderAlone() throws Exception {
        CaptureRequest.Builder builder=newRequestBuilder();
        Object viewfinderTag=new Object();
        builder.setTag(viewfinderTag);
        builder.set(CaptureRequest.SENSOR_SENSITIVITY,1600);
        controller.mPreviewRequestBuilder=builder;
        CameraCaptureSession session=mock(CameraCaptureSession.class);
        put(controller,"mCaptureSession",session);
        boolean wasBurst=CaptureController.burst;CaptureController.burst=false;
        try {
            NativeRawExposure ae=new NativeRawExposure(new android.util.Range<>(10_000L,1_000_000_000L),new android.util.Range<>(100,6400),
                    CaptureController.NATIVE_FRAME_NS,40_000_000L,1600);
            var send=CaptureController.class.getDeclaredMethod("sendNativeIsoProbe",NativeRawExposure.class,int.class);send.setAccessible(true);
            send.invoke(controller,ae,3200);
            var sent=org.mockito.ArgumentCaptor.forClass(CaptureRequest.class);
            verify(session).capture(sent.capture(),any(),any());
            CaptureRequest probe=sent.getValue();
            assertEquals(Integer.valueOf(3200),probe.get(CaptureRequest.SENSOR_SENSITIVITY));
            assertSame("the builder keeps the viewfinder's tag",viewfinderTag,builder.build().getTag());
            assertEquals("and its ISO",Integer.valueOf(1600),builder.get(CaptureRequest.SENSOR_SENSITIVITY));
            var isProbe=CaptureController.class.getDeclaredMethod("isNativeIsoProbe",TotalCaptureResult.class);isProbe.setAccessible(true);
            TotalCaptureResult probed=mock(TotalCaptureResult.class);when(probed.getRequest()).thenReturn(probe);
            TotalCaptureResult rebuilt=mock(TotalCaptureResult.class);when(rebuilt.getRequest()).thenReturn(builder.build());
            assertTrue((Boolean)isProbe.invoke(controller,probed));
            assertFalse("a request built after the probe is the viewfinder's",(Boolean)isProbe.invoke(controller,rebuilt));
            assertFalse((Boolean)isProbe.invoke(controller,(Object)null));
        } finally {
            CaptureController.burst=wasBurst;
        }
    }

    /**
     * P212z (review): the native viewfinder's cached matrix (a null one too) is its session's: a cold start into FULLRES has no
     * plain table (the identity), a plain session in between teaches the table this light, and the next native session at the same
     * neutral takes it (it kept the identity while the neutral held within 0.01 ln).
     */
    @Test public void theNativeViewfinderMatrixIsLookedUpAgainInANewSession() throws Exception {
        android.content.SharedPreferences prefs=RuntimeEnvironment.getApplication().getSharedPreferences("vf_ccm_session_test",0);
        prefs.edit().clear().commit();
        com.particlesdevs.photoncamera.processing.color.PlainColourMemoryAccess.useStore(prefs,"vivo/V2562/V2562:16/1");
        try {
            put(controller,"physicalID","3");
            var matrix=CaptureController.class.getDeclaredMethod("nativeViewfinderMatrix",CameraCharacteristics.class,CaptureResult.class,float[].class);
            matrix.setAccessible(true);
            CameraCharacteristics c=mock(CameraCharacteristics.class);
            float[] gains={1f/0.48f,1f,1f/0.60f}; // the neutral 0.48 / 0.60
            assertNull("no plain table yet: the identity",matrix.invoke(controller,c,null,gains));
            float[] plain={1.30f,-0.29f,-0.01f,-0.15f,1.21f,-0.06f,0.02f,-0.46f,1.44f};
            for(int i=0;i<4;i++)com.particlesdevs.photoncamera.processing.color.PlainColourMemory.offer("3",0.48f,0.60f,plain,6200,10_000L+1_000L*i);
            assertNull("the same session keeps its lookup",matrix.invoke(controller,c,null,gains));
            var session=CaptureController.class.getDeclaredField("mVfCcmSession");session.setAccessible(true);
            session.setInt(controller,session.getInt(controller)+1); // onConfigured of the next session
            float[] next=(float[])matrix.invoke(controller,c,null,gains);
            assertNotNull("the next session takes the table",next);
            assertEquals("column-major",plain[3],next[1],2e-3f);
        } finally {
            com.particlesdevs.photoncamera.processing.color.PlainColourMemoryAccess.reset();
        }
    }

    /** The framework's shading map (its constructor is hidden in the SDK stubs: rows, then columns). */
    private static android.hardware.camera2.params.LensShadingMap lsm(float[] gains,int cols,int rows) throws Exception {
        var c=android.hardware.camera2.params.LensShadingMap.class.getDeclaredConstructor(float[].class,int.class,int.class);
        c.setAccessible(true);
        return c.newInstance(gains,rows,cols);
    }

    /**
     * P212z (review): the meter's shading gains stay the same array while a result's map has the same factors (a result builds a new
     * LensShadingMap on every get, so the map object never repeats) - NativeRawExposure.gainMedian's cache key - and the copy buffer
     * is reused; a changed map or size gives a new array and leaves the earlier one as it was.
     */
    @Test public void theMeterKeepsItsShadingGainsWhileTheMapIsTheSame() throws Exception {
        float[] g=new float[3*2*4];
        for(int i=0;i<g.length;i++)g[i]=1f+0.01f*i;
        float[][] scratch=new float[1][];
        float[] first=CaptureController.meterGains(lsm(g,3,2),null,0,0,scratch);
        assertArrayEquals(g,first,0f);
        float[] again=CaptureController.meterGains(lsm(g.clone(),3,2),first,3,2,scratch);
        assertSame("a new map of the same factors keeps the array",first,again);
        float[] buffer=scratch[0];
        assertNotNull(buffer);
        assertNotSame(first,buffer);
        assertSame(first,CaptureController.meterGains(lsm(g.clone(),3,2),again,3,2,scratch));
        assertSame("the copy buffer is reused",buffer,scratch[0]);
        float[] g2=g.clone();g2[5]+=0.1f;
        float[] changed=CaptureController.meterGains(lsm(g2,3,2),first,3,2,scratch);
        assertNotSame(first,changed);
        assertArrayEquals(g2,changed,0f);
        assertArrayEquals("the earlier gains as they were",g,first,0f);
        assertNotSame("another size",changed,CaptureController.meterGains(lsm(g2.clone(),2,3),changed,3,2,scratch));
        assertEquals(NativeRawExposure.gainMedian(first,3,2),NativeRawExposure.gainMedian(again,3,2),0f);
    }

}
