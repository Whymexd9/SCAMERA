package com.particlesdevs.photoncamera.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.util.Rational;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.CaptureController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * P370 (A4): ApplyRes fixes the shot's result in that result only. It wrote the viewfinder's neutral and colour transform into
 * the static CaptureController.mCaptureResult at processing time (a single-frame shot took the viewfinder's colour, a queued shot's
 * metadata a later shot's); BL() wrote the static pattern there too. Now nothing is written but the missing dynamic black level,
 * into the result it was given.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class Camera2ApiAutoFixTest {
    private MockedStatic<CameraReflectionApi> reflection;
    private MockedStatic<ScameraApp> app;
    private CameraCharacteristics previous;
    private CaptureResult previousResult;

    @Before public void setUp() {
        reflection = mockStatic(CameraReflectionApi.class);
        final CaptureController controller = mock(CaptureController.class);
        final Rational[] viewfinder = {new Rational(101, 100), new Rational(101, 100), new Rational(101, 100)};
        controller.mPreviewTemp = viewfinder;
        controller.mColorSpaceTransform = new ColorSpaceTransform(new int[]{1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1});
        app = mockStatic(ScameraApp.class);
        app.when(ScameraApp::getCaptureController).thenReturn(controller);
        previous = CaptureController.mCameraCharacteristics;
        previousResult = CaptureController.mCaptureResult;
        final CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)).thenReturn(new BlackLevelPattern(new int[]{64, 64, 64, 64}));
        CaptureController.mCameraCharacteristics = c;
        CaptureController.mCaptureResult = mock(CaptureResult.class);
    }

    @After public void tearDown() {
        CaptureController.mCameraCharacteristics = previous;
        CaptureController.mCaptureResult = previousResult;
        app.close();
        reflection.close();
    }

    @Test public void theViewfinderColourNeverReachesAResult() {
        final CaptureResult shot = mock(CaptureResult.class);
        when(shot.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)).thenReturn(new float[]{64, 64, 64, 64});
        Camera2ApiAutoFix.ApplyRes(shot, null);
        // no write through the static result at all (the neutral, the transform, the black level)
        reflection.verify(() -> CameraReflectionApi.set(any(CaptureResult.Key.class), any()), never());
        reflection.verify(() -> CameraReflectionApi.set(any(CaptureResult.Key.class), any(), any(CaptureResult.class)), never());
    }

    @Test public void aMissingBlackLevelGoesIntoThatResultOnly() {
        final CaptureResult shot = mock(CaptureResult.class);
        Camera2ApiAutoFix.ApplyRes(shot, null);
        reflection.verify(() -> CameraReflectionApi.set(eq(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL), any(float[].class), same(shot)));
        reflection.verify(() -> CameraReflectionApi.set(eq(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL), any(float[].class),
                same(CaptureController.mCaptureResult)), never());
        reflection.verify(() -> CameraReflectionApi.set(any(CaptureResult.Key.class), any()), never());
        reflection.verify(() -> CameraReflectionApi.set(eq(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT), any(), any(CaptureResult.class)), never());
        reflection.verify(() -> CameraReflectionApi.set(eq(CaptureResult.COLOR_CORRECTION_TRANSFORM), any(), any(CaptureResult.class)), never());
        // a null result is no work (and no crash)
        Camera2ApiAutoFix.ApplyRes(null, null);
    }

    /** P370 (review): the missing black level is the shot's camera's pattern, not the camera open at processing (a lens switch). */
    @Test public void theBlackLevelIsTheShotsCamera() {
        final CameraCharacteristics shotCamera = mock(CameraCharacteristics.class);
        when(shotCamera.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)).thenReturn(new BlackLevelPattern(new int[]{256, 256, 256, 256}));
        final CaptureResult shot = mock(CaptureResult.class);
        Camera2ApiAutoFix.ApplyRes(shot, shotCamera);
        reflection.verify(() -> CameraReflectionApi.set(eq(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL),
                org.mockito.ArgumentMatchers.argThat((float[] l) -> l != null && l.length == 4 && l[0] == 256f && l[3] == 256f), same(shot)));
        reflection.verify(() -> CameraReflectionApi.set(eq(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL),
                org.mockito.ArgumentMatchers.argThat((float[] l) -> l != null && l.length == 4 && l[0] == 64f), any(CaptureResult.class)), never());
    }
}
