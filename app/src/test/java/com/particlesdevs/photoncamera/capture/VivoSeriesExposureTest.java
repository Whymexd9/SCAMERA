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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P212h (A7 = M14 phase 1, dev keys default off): a vivo native 200 MP series' exposure - the still-only route's ultrashort from the
 * first delivered N (count, order and mode unchanged) and the AE lock on its automatic N stills.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class VivoSeriesExposureTest {
    private static final long VF_US_NS = 1_250_000L, N_NS = 40_000_000L;
    private static final int VF_US_ISO = 100, N_ISO = 800;

    @Test
    public void theUltrashortIsTheFirstNsShutterOverTheFactorAtItsIso() {
        assertArrayEquals(new long[]{5_000_000L, 800}, VivoSdkFullResCapture.ultrashortFrom(40_000_000L, 800, 8.0, 50_000L, 50));
        // below the shortest shutter the ISO takes the rest
        assertArrayEquals(new long[]{50_000L, 400}, VivoSdkFullResCapture.ultrashortFrom(200_000L, 800, 8.0, 50_000L, 50));
        assertArrayEquals("not below the lowest ISO", new long[]{50_000L, 50}, VivoSdkFullResCapture.ultrashortFrom(100_000L, 100, 8.0, 50_000L, 50));
        assertNull(VivoSdkFullResCapture.ultrashortFrom(0L, 800, 8.0, 50_000L, 50));
        assertNull(VivoSdkFullResCapture.ultrashortFrom(40_000_000L, 0, 8.0, 50_000L, 50));
        assertNull(VivoSdkFullResCapture.ultrashortFrom(40_000_000L, 800, 1.0, 50_000L, 50));
        assertEquals(0.02, VivoSdkFullResCapture.exposureSpread(new double[]{100, 102, 101}, 3), 1e-12);
        assertTrue(Double.isNaN(VivoSdkFullResCapture.exposureSpread(new double[]{100, 0}, 2)));
    }

    /** P208's still-only route, 5 stills: the last one is built after the first completed and takes its exposure / 8. */
    @Test
    public void aStillOnlySeriesTakesItsUltrashortFromTheFirstDeliveredN() throws Exception {
        List<CaptureRequest> requests = run(5, true, false);
        CaptureRequest us = requests.get(4);
        assertEquals(ImageFrame.CaptureRole.EXTRA_SHORT, ((ImageFrame.ScamCaptureTag) us.getTag()).role);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_OFF), us.get(CaptureRequest.CONTROL_AE_MODE));
        assertEquals(Long.valueOf(N_NS / 8), us.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
        assertEquals(Integer.valueOf(N_ISO), us.get(CaptureRequest.SENSOR_SENSITIVITY));
        for (int i = 0; i < 4; i++) {
            assertEquals(ImageFrame.CaptureRole.NORMAL, ((ImageFrame.ScamCaptureTag) requests.get(i).getTag()).role);
            assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_ON), requests.get(i).get(CaptureRequest.CONTROL_AE_MODE));
            assertEquals("no lock without the key", Boolean.FALSE, requests.get(i).get(CaptureRequest.CONTROL_AE_LOCK));
        }
    }

    /** Fewer than 4 stills: the last one is queued before any N completed - the viewfinder's exposure stays; off: as before. */
    @Test
    public void aShortSeriesOrTheKeyOffKeepsTheViewfindersUltrashort() throws Exception {
        CaptureRequest three = run(3, true, false).get(2);
        assertEquals(Long.valueOf(VF_US_NS), three.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
        assertEquals(Integer.valueOf(VF_US_ISO), three.get(CaptureRequest.SENSOR_SENSITIVITY));
        CaptureRequest off = run(5, false, false).get(4);
        assertEquals(Long.valueOf(VF_US_NS), off.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
        assertEquals(Integer.valueOf(VF_US_ISO), off.get(CaptureRequest.SENSOR_SENSITIVITY));
    }

    /** vivo_series_ae_lock: the automatic N stills carry AE lock, the manual ultrashort does not need it. */
    @Test
    public void theAeLockGoesOnTheAutomaticNStillsOnly() throws Exception {
        List<CaptureRequest> requests = run(4, false, true);
        for (int i = 0; i < 3; i++) {
            assertEquals(Boolean.TRUE, requests.get(i).get(CaptureRequest.CONTROL_AE_LOCK));
            assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_ON), requests.get(i).get(CaptureRequest.CONTROL_AE_MODE));
        }
        CaptureRequest us = requests.get(3);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_OFF), us.get(CaptureRequest.CONTROL_AE_MODE));
        assertEquals(Boolean.FALSE, us.get(CaptureRequest.CONTROL_AE_LOCK));
    }

    private static CaptureRequest.Builder builder(String cameraId) throws Exception {
        Class<?> metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        return CaptureRequest.Builder.class.getConstructor(metadata, boolean.class, int.class, String.class, java.util.Set.class)
                .newInstance(metadata.getConstructor().newInstance(), false, -1, cameraId, null);
    }

    /** A still-only Quad series of {@code count} stills (the last the ultrashort), driven to its end; the requests in their order. */
    private List<CaptureRequest> run(int count, boolean fromN, boolean lock) throws Exception {
        final String cameraId = "5";
        final VivoSdkFullRes.Profile profile = new VivoSdkFullRes.Profile(512, 512, 0, 2, true);
        assertTrue(profile.stillOnly);
        CaptureRequest preview = builder(cameraId).build();
        CameraDevice device = mock(CameraDevice.class);
        CameraCaptureSession session = mock(CameraCaptureSession.class);
        Surface raw = mock(Surface.class);
        ImageSaver saver = mock(ImageSaver.class);
        List<CaptureRequest> requests = new ArrayList<>();
        List<CameraCaptureSession.CaptureCallback> callbacks = new ArrayList<>();
        ArrayList<ImageFrame> frames = new ArrayList<>();
        when(device.createCaptureRequest(anyInt())).thenAnswer(call -> builder(cameraId));
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
        int[] completed = {0};
        List<String> failures = new ArrayList<>();
        VivoSdkFullResCapture capture = new VivoSdkFullResCapture(device, session, mock(VivoSdkRawSession.class), raw, null, saver,
                new Handler(Looper.getMainLooper()), () -> 10L, 90, 1, 10, count, profile, preview, java.util.Collections.emptyList(),
                new VivoSdkFullResCapture.Listener() {
                    public void started() { }
                    public void result(int index, TotalCaptureResult result) { }
                    public void complete(ScamCaptureSequence sequence, HashMap<Long, Double> exposures) { completed[0]++; }
                    public void failed(String reason) { failures.add(reason); }
                });
        capture.ultrashortLast(VF_US_NS, VF_US_ISO);
        if (fromN) capture.ultrashortFromFirstN(8.0, 50_000L, 50);
        capture.lockNormals(lock);
        capture.start();
        // a Quad mosaic (2x2 blocks of one colour), so the still passes the native mosaic check
        ByteBuffer pixels = ByteBuffer.allocateDirect(512 * 512 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int[] levels = {130, 280, 280, 70};
        for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++)
            pixels.putShort((y * 512 + x) * 2, (short) (64 + levels[(y / 2 % 2) * 2 + x / 2 % 2]));
        for (int i = 0; i < count; i++) {
            assertEquals(failures.toString(), Math.min(count, i + VivoSdkFullResCapture.NATIVE_IN_FLIGHT), requests.size());
            CaptureRequest request = requests.get(i);
            long timestamp = 100L + i;
            callbacks.get(i).onCaptureStarted(session, request, timestamp, i);
            TotalCaptureResult result = mock(TotalCaptureResult.class);
            when(result.getRequest()).thenReturn(request);
            when(result.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(timestamp);
            final boolean us = i == count - 1;
            when(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(us ? request.get(CaptureRequest.SENSOR_EXPOSURE_TIME) : N_NS + i);
            when(result.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(us ? request.get(CaptureRequest.SENSOR_SENSITIVITY) : N_ISO);
            callbacks.get(i).onCaptureCompleted(session, request, result);
            callbacks.get(i).onCaptureSequenceCompleted(session, i + 1, i);
            Image image = mock(Image.class);
            when(image.getTimestamp()).thenReturn(timestamp);
            when(image.getFormat()).thenReturn(ImageFormat.RAW_SENSOR);
            when(image.getWidth()).thenReturn(512);
            when(image.getHeight()).thenReturn(512);
            Image.Plane plane = mock(Image.Plane.class);
            when(plane.getBuffer()).thenReturn(pixels.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN));
            when(plane.getRowStride()).thenReturn(1024);
            when(plane.getPixelStride()).thenReturn(2);
            when(image.getPlanes()).thenReturn(new Image.Plane[]{plane});
            capture.image(image);
        }
        assertEquals(failures.toString(), 1, completed[0]);
        assertEquals(count, requests.size());
        return requests;
    }
}
