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
    @Test public void usesFreshStillRequestsRawJpegOnlyAndOneMatchedFrameAtATime() throws Exception {
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
                    .newInstance(metadata.getConstructor().newInstance(), false, -1, "3", null);
            builders.add(builder);
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
        VivoSdkFullResCapture capture = new VivoSdkFullResCapture(device, session, sdk, raw, jpeg, saver,
                new Handler(Looper.getMainLooper()), () -> 10L, 90, 1, 10, 3,
                new VivoSdkFullResCapture.Listener() {
                    public void started() { }
                    public void result(int index, TotalCaptureResult result) { }
                    public void complete(ScamCaptureSequence sequence, HashMap<Long, Double> exposures) {
                        assertEquals(3, sequence.boundFutureCount());
                        assertEquals(3, exposures.size());
                        completed[0]++;
                    }
                    public void failed(String reason) { failures.add(reason); failed[0]++; }
                });
        capture.start();
        ByteBuffer pixels = ByteBuffer.allocate(8192 * 6144 * 2);
        pixels.putShort(0, (short) 64);
        for (int i = 0; i < 3; i++) {
            assertEquals(failures.toString(), i + 1, requests.size());
            CaptureRequest request = requests.get(i);
            CameraCaptureSession.CaptureCallback callback = callbacks.get(i);
            long timestamp = 100L + i;
            callback.onCaptureStarted(session, request, timestamp, i);
            TotalCaptureResult result = mock(TotalCaptureResult.class);
            when(result.getRequest()).thenReturn(request);
            when(result.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(timestamp);
            when(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(16_666_667L);
            when(result.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(250);
            when(result.get(new CaptureResult.Key<>("com.qti.sensorbps.mode_index", int[].class))).thenReturn(new int[]{13});
            callback.onCaptureCompleted(session, request, result);
            callback.onCaptureSequenceCompleted(session, i + 1, i);
            capture.jpegDrained(timestamp);
            assertEquals(i + 1, requests.size());
            Image image = mock(Image.class);
            when(image.getTimestamp()).thenReturn(timestamp);
            when(image.getFormat()).thenReturn(ImageFormat.RAW_SENSOR);
            when(image.getWidth()).thenReturn(8192);
            when(image.getHeight()).thenReturn(6144);
            Image.Plane plane = mock(Image.Plane.class);
            when(plane.getBuffer()).thenReturn(pixels);
            when(plane.getRowStride()).thenReturn(16384);
            when(plane.getPixelStride()).thenReturn(2);
            when(image.getPlanes()).thenReturn(new Image.Plane[]{plane});
            capture.image(image);
            verify(image).close();
        }
        assertEquals(1, completed[0]);
        assertEquals(0, failed[0]);
        assertEquals(3, builders.size());
        assertNotSame(requests.get(0), requests.get(1));
        assertNotSame(requests.get(1), requests.get(2));
        for (int i = 0; i < requests.size(); i++) {
            CaptureRequest request = requests.get(i);
            java.util.Collection<?> targets = (java.util.Collection<?>) CaptureRequest.class.getMethod("getTargets").invoke(request);
            assertEquals(2, targets.size());
            assertTrue(targets.contains(raw));
            assertTrue(targets.contains(jpeg));
            assertNull(request.get(CaptureRequest.SENSOR_SENSITIVITY));
            assertNull(request.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
            verify(sdk).apply(builders.get(i), true, 10L);
        }
        verify(session, never()).captureBurst(anyList(), any(), any());
        verify(session, never()).setRepeatingRequest(any(), any(), any());
    }
}
