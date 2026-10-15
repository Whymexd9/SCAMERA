package com.particlesdevs.photoncamera.processing;

import android.app.Application;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.media.Image;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.processing.processor.ScamProcessor;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.util.Allocator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = IszAspectCaptureTest.CopyAllocator.class)
public class IszAspectCaptureTest {
    @Implements(value = Allocator.class, isInAndroidSdk = false)
    public static class CopyAllocator {
        @Implementation protected static void __staticInitializer__() { }
        @Implementation protected static ByteBuffer allocateAndCopy(int capacity, ByteBuffer source, int offset) {
            ByteBuffer copy = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
            ByteBuffer region = source.duplicate();
            region.position(offset);
            region.limit(offset + capacity);
            copy.put(region).rewind();
            return copy;
        }
        @Implementation protected static void free(ByteBuffer buffer) { }
    }

    private MockedStatic<ScameraApp> app;
    private MockedStatic<ImagePath> paths;
    private MockedStatic<ParseExif> exif;
    private MockedConstruction<ScamProcessor> processors;
    private Settings settings;
    private CameraCharacteristics previousCharacteristics;

    @Before public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        SettingsManager manager = new SettingsManager(context);
        app = mockStatic(ScameraApp.class);
        app.when(ScameraApp::getAppContext).thenReturn(context);
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        app.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        PreferenceKeys.initialise(manager);
        settings = new Settings();
        settings.aspect169 = true;
        settings.binning = false;
        app.when(ScameraApp::getSettings).thenReturn(settings);
        processors = mockConstruction(ScamProcessor.class);
        paths = mockStatic(ImagePath.class);
        paths.when(ImagePath::newBaseName).thenReturn("isz-test");
        paths.when(() -> ImagePath.dngFile(anyString())).thenReturn(Paths.get("isz-test.dng"));
        paths.when(() -> ImagePath.imageFile(anyString())).thenReturn(Paths.get("isz-test.jpg"));
        exif = mockStatic(ParseExif.class);
        previousCharacteristics = CaptureController.mCameraCharacteristics;
        CameraCharacteristics characteristics = mock(CameraCharacteristics.class);
        when(characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)).thenReturn(1023);
        CaptureController.mCameraCharacteristics = characteristics;
        SaverImplementation.IMAGE_BUFFER.clear();
    }

    @After public void cleanup() {
        for (ImageFrame frame : SaverImplementation.IMAGE_BUFFER) frame.close();
        SaverImplementation.IMAGE_BUFFER.clear();
        CaptureController.mCameraCharacteristics = previousCharacteristics;
        exif.close();
        paths.close();
        processors.close();
        app.close();
    }

    private Image sensorImage() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 48 * 2).order(ByteOrder.nativeOrder());
        for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++)
            buffer.putShort((short) (64 + (x * 3 + y * 31) % 900));
        buffer.rewind();
        Image.Plane plane = mock(Image.Plane.class);
        when(plane.getBuffer()).thenReturn(buffer);
        when(plane.getRowStride()).thenReturn(128);
        when(plane.getPixelStride()).thenReturn(2);
        Image image = mock(Image.class);
        when(image.getPlanes()).thenReturn(new Image.Plane[]{plane});
        when(image.getFormat()).thenReturn(ImageFormat.RAW_SENSOR);
        when(image.getWidth()).thenReturn(64);
        when(image.getHeight()).thenReturn(48);
        when(image.getTimestamp()).thenReturn(100L);
        return image;
    }

    @Test public void iszKeepsEveryNativeRawSampleEvenWhenTheSavedPreferenceRequestsWide169() {
        ImageSaver saver = new ImageSaver(mock(ProcessingEventsListener.class));
        saver.iszAspect4x3 = true;
        saver.setFrameCount(1);
        saver.initProcess(sensorImage());
        ImageFrame frame = saver.snapshotFrames().get(0);
        assertEquals(64, frame.width);
        assertEquals(48, frame.height);
        assertEquals(64 * 48 * 2, frame.buffer.capacity());
        for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++)
            assertEquals(64 + (x * 3 + y * 31) % 900, frame.buffer.getShort(2 * (y * 64 + x)) & 0xffff);
    }

    @Test public void ordinaryFramesRetainTheirExistingWide169Crop() {
        ImageSaver saver = new ImageSaver(mock(ProcessingEventsListener.class));
        saver.setFrameCount(1);
        saver.initProcess(sensorImage());
        ImageFrame frame = saver.snapshotFrames().get(0);
        assertEquals(64, frame.width);
        assertEquals(32, frame.height);
        assertEquals(64 + (8 * 31) % 900, frame.buffer.getShort(0) & 0xffff);
    }

    @Test public void queuedRawSaverForwardsTheOriginalAspectPolicyAfterAnotherShotStarts() {
        ImageSaver first = new ImageSaver(mock(ProcessingEventsListener.class));
        first.iszAspect4x3 = true;
        first.setFrameCount(1);
        first.initProcess(sensorImage());
        DefaultSaver implementation = (DefaultSaver) first.implementation;
        ArrayList<ImageFrame> owned = first.snapshotFrames();
        first.detachForQueue(true);
        first.iszAspect4x3 = false;
        ImageSaver next = new ImageSaver(mock(ProcessingEventsListener.class));
        next.iszAspect4x3 = false;
        settings.aspect169 = false;
        first.runRaw(CaptureController.mCameraCharacteristics, null, null, new ArrayList<>(), 0, new HashMap<>());
        assertTrue(implementation.scamProcessor.ownedIszAspect4x3);
        assertEquals(48, owned.get(0).height);
        for (ImageFrame frame : owned) frame.close();
    }
}
