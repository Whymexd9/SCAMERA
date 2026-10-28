package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.hardware.camera2.CameraCharacteristics;
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
import org.robolectric.shadows.ShadowBuild;

/**
 * P370 (A6, review): «Источник цвета» is disabled only where the choice never acts, for any brand: the OPPO Find X7 Ultra's tuned
 * matrix, or a camera whose DNG forward matrices are the sRGB placeholder. A camera whose preview transform is an ISP colour matrix
 * (the ISP branch takes it on the 12 MP photos) keeps the row enabled - the choice acts on its mosaic shots with the app's own
 * white balance (ParametersColourMethodTest) and a saved 0 or 2 must be changeable - and its summary says so.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DeviceAvailabilityColourTest {
    private MockedStatic<ScameraApp> app;
    private MockedStatic<PreferenceKeys> prefsKeys;
    private CaptureController controller;
    private CameraCharacteristics previous;

    private static ColorSpaceTransform cst(int den, int... v) {
        final Rational[] r = new Rational[9];
        for (int i = 0; i < 9; i++) r[i] = new Rational(v[i], den);
        return new ColorSpaceTransform(r);
    }

    @Before public void setUp() {
        controller = mock(CaptureController.class);
        app = mockStatic(ScameraApp.class);
        app.when(ScameraApp::getCaptureController).thenReturn(controller);
        prefsKeys = mockStatic(PreferenceKeys.class, invocation -> {
            final String name = invocation.getMethod().getName();
            if (name.equals("isScamEnabled")) return true;
            if (name.equals("routeInternalValue")) return 1f;
            return invocation.callRealMethod();
        });
        previous = CaptureController.mCameraCharacteristics;
        final CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)).thenReturn(cst(128, 70, 40, 14, 30, 90, 8, 4, 20, 82));
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)).thenReturn(cst(128, 72, 38, 14, 31, 89, 8, 5, 22, 80));
        CaptureController.mCameraCharacteristics = c;
    }

    @After public void tearDown() {
        CaptureController.mCameraCharacteristics = previous;
        ShadowBuild.reset();
        prefsKeys.close();
        app.close();
    }

    @Test public void anIspMatrixKeepsTheRowEnabled() {
        for (String brand : new String[]{"OPPO", "Google", "vivo"}) {
            ShadowBuild.setManufacturer(brand);
            ShadowBuild.setModel("PKJ110");
            controller.mColorSpaceTransform = cst(128, 200, -62, -10, -17, 172, -27, 8, -70, 191);
            assertNull(brand + ": the choice acts on mosaic shots", DeviceAvailability.colorMethodOverride());
            controller.mColorSpaceTransform = cst(1, 1, 0, 0, 0, 1, 0, 0, 0, 1);
            assertNull(brand, DeviceAvailability.colorMethodOverride());
        }
        // the row's summary says what the choice acts on there
        final String summary = org.robolectric.RuntimeEnvironment.getApplication().getString(com.particlesdevs.photoncamera.R.string.color_method_desc);
        assertTrue(summary, summary.contains("12 MP") && summary.contains("mosaic"));
    }

    @Test public void theTunedOppoGivesTheReason() {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PHY110");
        controller.mColorSpaceTransform = cst(1, 1, 0, 0, 0, 1, 0, 0, 0, 1);
        final String reason = DeviceAvailability.colorMethodOverride();
        assertNotNull(reason);
        assertTrue(reason, reason.contains("tuned") || reason.contains("настроенная"));
    }

    @Test public void placeholderForwardMatricesGiveTheReason() {
        ShadowBuild.setManufacturer("OPPO");
        controller.mColorSpaceTransform = cst(1, 1, 0, 0, 0, 1, 0, 0, 0, 1);
        final ColorSpaceTransform placeholder = cst(128, 56, 49, 18, 28, 92, 8, 2, 12, 91);
        when(CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)).thenReturn(placeholder);
        when(CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)).thenReturn(placeholder);
        final String reason = DeviceAvailability.colorMethodOverride();
        assertNotNull(reason);
        assertTrue(reason, reason.contains("placeholder") || reason.contains("заглушка"));
    }
}
