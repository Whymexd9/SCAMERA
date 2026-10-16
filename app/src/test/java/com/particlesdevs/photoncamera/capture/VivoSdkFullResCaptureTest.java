package com.particlesdevs.photoncamera.capture;

import android.app.Application;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.ImageSaver;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class VivoSdkFullResCaptureTest {
    @Test public void nativeRawOnlyCapturesFourMatchedQuadFramesWithoutJpegOrVendorResultMode() throws Exception {
        verifyProfile("3", new VivoSdkFullRes.Profile(512, 512, 4, 2), 4, 2);
    }

    @Test public void nativeRawOnlyRejectsBayerPixelsInsteadOfCallingThemQuad() throws Exception {
        verifyProfile("3", new VivoSdkFullRes.Profile(512, 512, 4, 2), 1, 1);
    }

    @Test public void nativeProfilesUseFullSensorGeometryAndExplicitTeleModeZero() {
        assertEquals(8192, VivoSdkFullRes.nativeProfile("3").size.getWidth());
        assertEquals(4, VivoSdkFullRes.nativeProfile("4").sensorMode);
        assertEquals(16320, VivoSdkFullRes.nativeProfile("5").size.getWidth());
        assertEquals(12288, VivoSdkFullRes.nativeProfile("5").size.getHeight());
        assertEquals(0, VivoSdkFullRes.nativeProfile("5").sensorMode);
        assertEquals(4, VivoSdkFullRes.nativeProfile("5").nativeBlock);
        assertNull(VivoSdkFullRes.nativeProfile("1"));
    }

    /** P179: the tele's 50 MP button takes mode 1 (Quad 8160x6144), the 200 MP one mode 0 (Tetra); 200 MP until chosen. */
    @Test public void theTeleTakesTheChosenNativeSize() {
        try (org.mockito.MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> app =
                     mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class)) {
            app.when(com.particlesdevs.photoncamera.app.ScameraApp::getAppContext)
                    .thenReturn(org.robolectric.RuntimeEnvironment.getApplication());
            assertTrue(PixelPro10.tele200());
            PixelPro10.setTele200(false);
            VivoSdkFullRes.Profile fifty = VivoSdkFullRes.nativeProfile("5");
            assertEquals(8160, fifty.size.getWidth());
            assertEquals(6144, fifty.size.getHeight());
            assertEquals(1, fifty.sensorMode);
            assertEquals(2, fifty.nativeBlock);
            assertEquals("main and UW keep their one size", 8192, VivoSdkFullRes.nativeProfile("3").size.getWidth());
            PixelPro10.setTele200(true);
            assertEquals(16320, VivoSdkFullRes.nativeProfile("5").size.getWidth());
            assertEquals(0, VivoSdkFullRes.nativeProfile("5").sensorMode);
            assertFalse("the default package has no native sizes", VivoSdkFullRes.teleSizes("5"));
        }
    }
    private static final ByteBuffer RAW_PIXELS = ByteBuffer.allocateDirect(8192 * 6144 * 2);
    @Test public void usesFreshStillRequestsRawJpegOnlyAndOneMatchedFrameAtATime() throws Exception {
        verifyCamera("3", 8192, 6144, 13);
    }

    @Test public void ultraWideUsesItsOwnNativeModeWithThreeMatchedFullSizeFrames() throws Exception {
        verifyCamera("4", 8192, 6144, 11);
    }

    @Test public void teleUses8160WidthAndItsOwnNativeModeWithThreeMatchedFrames() throws Exception {
        verifyCamera("5", 8160, 6144, 18);
    }

    @Test public void completesTheFull44FrameSettingWithoutAThreeOrSixteenFrameCeiling() throws Exception {
        verifyCamera("3", 8192, 6144, 13, 44);
    }

    private void verifyCamera(String cameraId, int width, int height, int sensorMode) throws Exception {
        verifyCamera(cameraId, width, height, sensorMode, 3);
    }

    private void verifyCamera(String cameraId, int width, int height, int sensorMode, int count) throws Exception {
        VivoSdkFullRes.Profile profile = VivoSdkFullRes.profile(cameraId);
        verifyProfile(cameraId, profile, count, 0);
    }

    private void verifyProfile(String cameraId, VivoSdkFullRes.Profile profile, int count, int inputBlock) throws Exception {
        int width = profile.size.getWidth(), height = profile.size.getHeight(), sensorMode = profile.sensorMode;
        boolean nativeRaw = profile.nativeBlock > 1;
        Class<?> nativeMetadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        CaptureRequest.Builder previewBuilder = CaptureRequest.Builder.class
                .getConstructor(nativeMetadata, boolean.class, int.class, String.class, java.util.Set.class)
                .newInstance(nativeMetadata.getConstructor().newInstance(), false, -1, cameraId, null);
        android.util.Range<Integer> previewFps = new android.util.Range<>(30, 30);
        previewBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, previewFps);
        previewBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        CaptureRequest.Key<Integer> hdr = new CaptureRequest.Key<>(
                "org.codeaurora.qcamera3.sessionParameters.EnableSHDR", Integer.class);
        previewBuilder.set(hdr, 0);
        if (nativeRaw) previewBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 3);
        CaptureRequest preview = previewBuilder.build();
        CameraDevice device = mock(CameraDevice.class);
        CameraCaptureSession session = mock(CameraCaptureSession.class);
        Surface raw = mock(Surface.class), jpeg = mock(Surface.class);
        ImageSaver saver = mock(ImageSaver.class);
        List<CaptureRequest.Builder> builders = new ArrayList<>();
        List<CaptureRequest> requests = new ArrayList<>();
        List<CameraCaptureSession.CaptureCallback> callbacks = new ArrayList<>();
        ArrayList<ImageFrame> frames = new ArrayList<>();
        when(device.createCaptureRequest(anyInt())).thenAnswer(call -> {
            assertEquals(CameraDevice.TEMPLATE_STILL_CAPTURE, (int) call.getArgument(0));
            Class<?> metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
            CaptureRequest.Builder builder = CaptureRequest.Builder.class
                    .getConstructor(metadata, boolean.class, int.class, String.class, java.util.Set.class)
                    .newInstance(metadata.getConstructor().newInstance(), false, -1, cameraId, null);
            builders.add(builder);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new android.util.Range<>(15, 30));
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
            builder.set(hdr, 1);
            return builder;
        });
        when(session.capture(any(), any(), any())).thenAnswer(call -> {
            requests.add(call.getArgument(0));
            callbacks.add(call.getArgument(1));
            return requests.size();
        });
        doAnswer(call -> {
            Image image = call.getArgument(0);
            ImageFrame frame = mock(ImageFrame.class);
            frame.timestamp = image.getTimestamp();
            frames.add(frame);
            image.close();
            return null;
        }).when(saver).initProcess(any(Image.class));
        when(saver.snapshotFrames()).thenAnswer(call -> new ArrayList<>(frames));
        int[] completed = {0}, failed = {0};
        List<String> failures = new ArrayList<>();
        VivoSdkRawSession sdk = mock(VivoSdkRawSession.class);
        VivoSdkFullResCapture capture = new VivoSdkFullResCapture(device, session, sdk, raw, nativeRaw ? null : jpeg, saver,
                new Handler(Looper.getMainLooper()), () -> 10L, 90, 1, 10, count, profile, preview,
                java.util.Arrays.asList(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, hdr),
                new VivoSdkFullResCapture.Listener() {
                    public void started() { }
                    public void result(int index, TotalCaptureResult result) { }
                    public void complete(ScamCaptureSequence sequence, HashMap<Long, Double> exposures) {
                        assertEquals(count, sequence.boundFutureCount());
                        assertEquals(count, exposures.size());
                        completed[0]++;
                    }
                    public void failed(String reason) { failures.add(reason); failed[0]++; }
                });
        capture.start();
        ByteBuffer pixels = RAW_PIXELS.duplicate();
        pixels.limit(width * height * 2);
        pixels.putShort(0, (short) 64);
        if (nativeRaw) {
            pixels.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int[] levels = {130, 280, 280, 70};
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
                pixels.putShort((y * width + x) * 2, (short) (64 + levels[(y / inputBlock % 2) * 2 + x / inputBlock % 2]));
        }
        // P179: the native RAW-only series keeps up to NATIVE_IN_FLIGHT stills queued; the SDK series one at a time
        final int inFlight = nativeRaw ? VivoSdkFullResCapture.NATIVE_IN_FLIGHT : 1;
        for (int i = 0; i < count; i++) {
            assertEquals(failures.toString(), Math.min(count, i + inFlight), requests.size());
            CaptureRequest request = requests.get(i);
            assertEquals(previewFps, request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE));
            assertEquals(Integer.valueOf(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF),
                    request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE));
            assertEquals(Integer.valueOf(0), request.get(hdr));
            CameraCaptureSession.CaptureCallback callback = callbacks.get(i);
            long timestamp = 100L + i;
            callback.onCaptureStarted(session, request, timestamp, i);
            TotalCaptureResult result = mock(TotalCaptureResult.class);
            when(result.getRequest()).thenReturn(request);
            when(result.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(timestamp);
            when(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(16_666_667L);
            when(result.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(250);
            if (!nativeRaw) when(result.get(new CaptureResult.Key<>("com.qti.sensorbps.mode_index", int[].class)))
                    .thenReturn(new int[]{sensorMode});
            callback.onCaptureCompleted(session, request, result);
            callback.onCaptureSequenceCompleted(session, i + 1, i);
            if (!nativeRaw) capture.jpegDrained(timestamp);
            assertEquals("no new still before this one's RAW", Math.min(count, i + inFlight), requests.size());
            Image image = mock(Image.class);
            when(image.getTimestamp()).thenReturn(timestamp);
            when(image.getFormat()).thenReturn(ImageFormat.RAW_SENSOR);
            when(image.getWidth()).thenReturn(width);
            when(image.getHeight()).thenReturn(height);
            Image.Plane plane = mock(Image.Plane.class);
            when(plane.getBuffer()).thenReturn(pixels);
            when(plane.getRowStride()).thenReturn(width * 2);
            when(plane.getPixelStride()).thenReturn(2);
            when(image.getPlanes()).thenReturn(new Image.Plane[]{plane});
            capture.image(image);
            verify(image).close();
            if (nativeRaw && inputBlock != profile.nativeBlock) {
                assertEquals(1, failed[0]);
                assertEquals(0, completed[0]);
                assertTrue(failures.get(0).contains("mosaic"));
                return;
            }
        }
        assertEquals(1, completed[0]);
        assertEquals(0, failed[0]);
        assertEquals(count, builders.size());
        assertNotSame(requests.get(0), requests.get(1));
        assertNotSame(requests.get(1), requests.get(2));
        for (int i = 0; i < requests.size(); i++) {
            CaptureRequest request = requests.get(i);
            java.util.Collection<?> targets = (java.util.Collection<?>) CaptureRequest.class.getMethod("getTargets").invoke(request);
            assertEquals(nativeRaw ? 1 : 2, targets.size());
            assertTrue(targets.contains(raw));
            if (nativeRaw) {
                assertEquals(Integer.valueOf(3), request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION));
                assertFalse(targets.contains(jpeg));
                assertArrayEquals(new int[]{sensorMode}, request.get(new CaptureRequest.Key<>(VivoQcomIsz.KEY, int[].class)));
            } else {
            assertTrue(targets.contains(jpeg));
            assertEquals(Integer.valueOf(0), request.get(CaptureRequest.SENSOR_SENSITIVITY));
            assertEquals(Long.valueOf(0), request.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
            assertEquals(Long.valueOf(0), request.get(CaptureRequest.SENSOR_FRAME_DURATION));
            assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_ON), request.get(CaptureRequest.CONTROL_AE_MODE));
            verify(sdk).apply(builders.get(i), true, 10L);
            }
        }
        if (nativeRaw) verifyNoInteractions(sdk);
        verify(session, never()).captureBurst(anyList(), any(), any());
        verify(session, never()).setRepeatingRequest(any(), any(), any());
    }
}
