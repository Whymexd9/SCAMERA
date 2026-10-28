package com.particlesdevs.photoncamera.processing.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.graphics.Point;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;

import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Bug capture-processing-reads-live-camera: a shot queued behind a merge was developed with the camera open when its processing
 * started (CaptureController.mCameraCharacteristics, Settings.mCameraID): after a lens switch the other sensor's levels, colour
 * matrices and module settings. Its own characteristics (FillConstParameters') and camera at the press are used now.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = ParametersShotCameraTest.NoNativeAllocator.class,
        instrumentedPackages = "com.particlesdevs.photoncamera.util")
public class ParametersShotCameraTest {
    /**
     * P370 (A5): ReCalcColor no longer throws on a camera without colour matrices, so FillDynamicParameters goes on to the
     * binning check (Allocator.binning): the native library is not loaded on the JVM (RawCropTest's pattern).
     */
    @Implements(com.particlesdevs.photoncamera.util.Allocator.class)
    public static class NoNativeAllocator {
        @Implementation protected static void __staticInitializer__() { }
    }

    private MockedStatic<PreferenceKeys> prefs;
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> appStatic;
    private CameraCharacteristics previous;
    private com.particlesdevs.photoncamera.api.Settings settings;

    @Before
    public void setup() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        com.particlesdevs.photoncamera.settings.SettingsManager manager = new com.particlesdevs.photoncamera.settings.SettingsManager(context);
        appStatic = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getAppContext).thenReturn(context);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        settings = new com.particlesdevs.photoncamera.api.Settings();
        settings.mCameraID = "0";
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(settings);
        prefs = mockStatic(PreferenceKeys.class);
        previous = CaptureController.mCameraCharacteristics;
    }

    @After
    public void cleanup() {
        CaptureController.mCameraCharacteristics = previous;
        prefs.close();
        appStatic.close();
    }

    private static CameraCharacteristics camera(int white, int black) {
        final CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)).thenReturn(white);
        when(c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)).thenReturn(new BlackLevelPattern(new int[]{black, black, black, black}));
        when(c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)).thenReturn(0);
        when(c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)).thenReturn(800);
        when(c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)).thenReturn(new android.util.SizeF(6.4f, 4.8f));
        return c;
    }

    @Test
    public void theShotsOwnCameraIsRead() {
        final CameraCharacteristics shot = camera(1023, 64), other = camera(4095, 256);
        final Parameters p = new Parameters();
        p.cameraIdAtPress = "2";
        p.FillConstParameters(shot, new Point(64, 64));
        assertEquals("the camera of the press, not the one open now", 2, p.physicalID);
        CaptureController.mCameraCharacteristics = other; // the user switched lenses while the shot waited
        assertSame(shot, p.camera());
        assertSame(shot, p.shotCharacteristics());
        p.hotPixels = new android.graphics.Point[0]; // FillDynamicParameters' (a copy needs them)
        assertSame("the post pipeline's copy keeps it", shot, p.Build().camera());
        // without a press-time id: the open camera's, as before
        final Parameters open = new Parameters();
        open.FillConstParameters(shot, new Point(64, 64));
        assertEquals(0, open.physicalID);
        // nothing given at all: the open camera's (the lab's parameters, old callers)
        assertSame(other, new Parameters().camera());
    }

    @Test
    public void theLevelsAreTheShotsCamera() {
        final CameraCharacteristics shot = camera(1023, 64), other = camera(4095, 256);
        final Parameters p = new Parameters();
        p.cameraIdAtPress = "2";
        p.FillConstParameters(shot, new Point(64, 64));
        CaptureController.mCameraCharacteristics = other;
        final CaptureResult result = mock(CaptureResult.class);
        when(result.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(100);
        when(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(10_000_000L);
        try {
            p.FillDynamicParameters(result, mock(CaptureRequest.class), 100);
        } catch (RuntimeException colour) {
            // the colour pass (ReCalcColor) needs a full camera; the levels below are read before it (P370 (A5): it falls back
            // without throwing, the development then runs to the end)
        }
        assertEquals(1023, p.declaredWhite);
    }

    /**
     * Review: a FULLRES shot merged in the plain session now develops with its own (the SDK session's) characteristics instead of
     * the plain session's object; the keys where the two differ are what the processing log names (nothing for equal values).
     */
    @Test
    public void theShotAndOpenCamerasAreCompared() throws Exception {
        final CameraCharacteristics shot = camera(1023, 64);
        assertEquals(null, Parameters.shotCameraDiff(shot, shot));
        assertEquals(null, Parameters.shotCameraDiff(shot, null));
        assertEquals("same values in another object", "", Parameters.shotCameraDiff(shot, camera(1023, 64)));
        final String diff = Parameters.shotCameraDiff(shot, camera(4095, 64));
        assertTrue(diff, diff.contains("android.sensor.info.whiteLevel shot=1023 open=4095"));
        assertFalse(diff, diff.contains("blackLevelPattern"));
        final CameraCharacteristics black = camera(1023, 256);
        assertTrue(Parameters.shotCameraDiff(shot, black).contains("android.sensor.blackLevelPattern"));
        final String source = new String(Files.readAllBytes(new File(
                "src/main/java/com/particlesdevs/photoncamera/processing/processor/ScamProcessor.java").toPath()), StandardCharsets.UTF_8);
        final int fill = source.indexOf("processingParameters.FillConstParameters(characteristics, new Point(width, height));");
        assertTrue("logged right after the shot's characteristics are taken",
                fill > 0 && source.indexOf("logShotCamera(processingParameters, width, height);", fill) > fill);
    }

    @Test
    public void noLiveCameraReadLeftInTheShotsParameters() throws Exception {
        final String s = new String(Files.readAllBytes(new File(
                "src/main/java/com/particlesdevs/photoncamera/processing/render/Parameters.java").toPath()), StandardCharsets.UTF_8);
        final String without = s.replace("return shotCharacteristics != null ? shotCharacteristics : CaptureController.mCameraCharacteristics;", "")
                .replace("replaced the open camera (CaptureController.mCameraCharacteristics)", "");
        assertFalse(without.contains("CaptureController.mCameraCharacteristics"));
        assertTrue(s.contains("cameraID = cameraIdAtPress != null ? cameraIdAtPress : ScameraApp.getSettings().mCameraID;"));
    }
}
