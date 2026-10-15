package com.particlesdevs.photoncamera.capture;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;

import com.particlesdevs.photoncamera.api.CameraManager2;
import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.spoof.CameraPackageSpoof;
import com.particlesdevs.photoncamera.control.ZoomController;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.settings.TunableKeyManager;

import com.google.gson.GsonBuilder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class IszCapturePolicyTest {
    private MockedStatic<ScameraApp> app;
    private MockedStatic<PreferenceKeys> preferences;
    private MockedStatic<PixelPro10> pixel;
    private MockedStatic<VivoQcomModes> vivo;
    private MockedStatic<VivoQcomIsz> x300;
    private MockedStatic<CameraManager2> cameraManager;
    private MockedStatic<ZoomController> zoom;
    private MockedStatic<CameraPackageSpoof> spoof;
    private SharedPreferences modules;
    private Context context;
    private CameraCharacteristics characteristics;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        SettingsManager manager = new SettingsManager(context);
        app = mockStatic(ScameraApp.class);
        app.when(ScameraApp::getAppContext).thenReturn(context);
        app.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        app.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(call -> context.getString(call.getArgument(0)));
        ScameraApp instance = mock(ScameraApp.class);
        when(instance.getSettingsManager()).thenReturn(manager);
        app.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(instance);
        app.when(ScameraApp::getSettings).thenReturn(new Settings());
        PreferenceKeys.initialise(manager);
        preferences = mockStatic(PreferenceKeys.class, CALLS_REAL_METHODS);
        pixel = mockStatic(PixelPro10.class, CALLS_REAL_METHODS);
        pixel.when(PixelPro10::device).thenReturn(false);
        vivo = mockStatic(VivoQcomModes.class);
        vivo.when(VivoQcomModes::device).thenReturn(true);
        x300 = mockStatic(VivoQcomIsz.class, CALLS_REAL_METHODS);
        x300.when(VivoQcomIsz::enabled).thenReturn(false);
        cameraManager = mockStatic(CameraManager2.class);
        zoom = mockStatic(ZoomController.class);
        zoom.when(ZoomController::residual).thenReturn(1f);
        spoof = mockStatic(CameraPackageSpoof.class);
        spoof.when(() -> CameraPackageSpoof.signature(context)).thenReturn("off");
        modules = manager.getDefaultPreferences();
        modules.edit().clear().apply();
        Dcg14Mode.setMode(Dcg14Mode.OFF);
        PixelPro10.setFullRes(false);
        characteristics = mock(CameraCharacteristics.class);
        module("back0", "3", "1", null);
        module("back1", "3", "2", "back0");
    }

    @After public void cleanup() {
        spoof.close();
        zoom.close();
        cameraManager.close();
        x300.close();
        vivo.close();
        pixel.close();
        preferences.close();
        app.close();
    }

    private void module(String slot, String camera, String ratio, String source) {
        SharedPreferences.Editor edit = modules.edit().putString("module_auto_" + slot, camera)
                .putString("module_zoom_" + slot, ratio).putBoolean("module_visible_" + slot, true);
        if (source == null) edit.remove("module_duplicate_" + slot);
        else edit.putString("module_duplicate_" + slot, source);
        edit.apply();
    }

    private void force(int value) {
        TunableKeyManager.saveKeys(context, "back1", Collections.singletonList(new VendorTagUtils.TunableKey(
                VendorTagUtils.TunableKey.TYPE_SESSION, VivoQcomIsz.KEY, Integer.class, value)));
        modules.edit().remove("module_sensorcrop_back1").apply();
    }

    private boolean isz(String camera, CaptureResult result, boolean croppedRaw) {
        return IszCapturePolicy.isIsz("back1", characteristics, result, camera, camera, croppedRaw);
    }

    @Test public void pd2454ModesAreSpecificToTheirCameraAndExcludeOpenGateDcg() {
        force(3);
        assertTrue(isz("3", null, false));
        assertTrue(isz("4", null, false));
        assertFalse(isz("5", null, false));
        for (int mode : new int[]{5, 7, 6, 17}) {
            force(mode);
            assertTrue(isz("5", null, false));
            assertFalse(isz("3", null, false));
            assertFalse(isz("4", null, false));
        }
        for (int mode : new int[]{1, 11, 13, 20, 31}) {
            force(mode);
            modules.edit().putBoolean("module_sensorcrop_back1", true).apply();
            assertFalse(isz("3", null, false));
            assertFalse(isz("5", null, false));
        }
    }

    @Test public void resultAndDisabledPrettySessionKeysDoNotActivateIszAspect() {
        force(3);
        assertTrue(isz("3", null, false));
        VendorTagUtils.TunableKey key = new VendorTagUtils.TunableKey(
                "CaptureResult", VivoQcomIsz.KEY, Integer.class, 3);
        TunableKeyManager.saveKeys(context, "back1", Collections.singletonList(key));
        assertFalse(isz("3", null, false));
        key.type = VendorTagUtils.TunableKey.TYPE_SESSION;
        key.enabled = false;
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(Collections.singletonList(key))
                .replace("\"value\": \"3\"", "\"value\":\"3\"");
        modules.edit().putString("pref_sensorconfig_back1_tunablekeys", json).apply();
        assertFalse(isz("3", null, false));
        assertEquals(json, modules.getString("pref_sensorconfig_back1_tunablekeys", ""));
    }

    @Test public void forcedPresetRequiresTheExactSessionIntegerKey() {
        for (VendorTagUtils.TunableKey key : new VendorTagUtils.TunableKey[]{
                new VendorTagUtils.TunableKey("CaptureRequest", VivoQcomIsz.KEY, Integer.class, 3),
                new VendorTagUtils.TunableKey(VendorTagUtils.TunableKey.TYPE_SESSION, VivoQcomIsz.KEY, Long.class, 3L),
                new VendorTagUtils.TunableKey(VendorTagUtils.TunableKey.TYPE_SESSION, "other.forceSensorMode", Integer.class, 3)}) {
            TunableKeyManager.saveKeys(context, "back1", Collections.singletonList(key));
            assertFalse(isz("3", null, false));
        }
        force(3);
        assertTrue(isz("3", null, false));
    }

    @Test public void x300ModesRequireDeviceGateAndMatchingCameraRole() {
        vivo.when(VivoQcomModes::device).thenReturn(false);
        force(VivoQcomIsz.MAIN_2X);
        cameraManager.when(() -> CameraManager2.vivoCameraType(characteristics)).thenReturn("Master");
        assertFalse(isz("3", null, false));
        x300.when(VivoQcomIsz::enabled).thenReturn(true);
        assertTrue(isz("3", null, false));
        cameraManager.when(() -> CameraManager2.vivoCameraType(characteristics)).thenReturn("Wide");
        assertFalse(isz("3", null, false));
        force(VivoQcomIsz.WIDE_2X);
        assertTrue(isz("4", null, false));
        cameraManager.when(() -> CameraManager2.vivoCameraType(characteristics)).thenReturn("Tele3P5x");
        force(VivoQcomIsz.TELE_4X);
        assertTrue(isz("5", null, false));
        force(VivoQcomIsz.TELE_DCG);
        assertFalse(isz("5", null, false));
        cameraManager.when(() -> CameraManager2.vivoCameraType(characteristics)).thenReturn("Tele3P5xExternal");
        force(VivoQcomIsz.TELE_2X);
        assertFalse(isz("5", null, false));
    }

    @Test public void sdkMarkerRequiresExistingVisibleSourceOnSameCameraAtTwoTimes() {
        modules.edit().putInt("module_vivo_qcom_isz_back1", 2).apply();
        assertTrue(isz("3", null, false));
        modules.edit().putBoolean("module_visible_back0", false).apply();
        assertFalse(isz("3", null, false));
        module("back0", "4", "1", null);
        assertFalse(isz("3", null, false));
        module("back0", "3", "1", null);
        module("back1", "3", "1.5", "back0");
        assertFalse(isz("3", null, false));
        module("back1", "3", "2", "back99");
        assertFalse(isz("3", null, false));
    }

    @Test public void offeredFlagAndDigitalDuplicateAreNotSlotEvidence() {
        modules.edit().putBoolean("module_isz_offered_3", true)
                .putBoolean("module_vivo_qcom_roi_offered_3_main", true).apply();
        zoom.when(ZoomController::residual).thenReturn(2f);
        assertFalse(isz("3", null, false));
        assertFalse(isz("3", null, true));
    }

    @Test public void explicitSensorCropAndPixelSensorCamerasRemainRecognized() {
        modules.edit().putBoolean("module_sensorcrop_back1", true).apply();
        assertTrue(isz("3", null, false));
        modules.edit().remove("module_sensorcrop_back1").apply();
        pixel.when(PixelPro10::device).thenReturn(true);
        for (String camera : new String[]{"5", "6", "9", "0-5"}) {
            module("back1", camera, "2", null);
            assertTrue(isz(camera, null, false));
        }
        module("back1", "2", "1", null);
        assertFalse(isz("2", null, false));
    }

    @Test public void croppedRawRequiresActualSessionAndCropOrZoomEvidence() {
        when(characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)).thenReturn(new long[]{6L});
        when(characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).thenReturn(new Rect(0, 0, 4080, 3060));
        CaptureResult result = mock(CaptureResult.class);
        when(result.get(CaptureResult.SCALER_RAW_CROP_REGION)).thenReturn(new Rect(1020, 765, 3060, 2295));
        assertFalse(isz("3", result, false));
        assertTrue(isz("3", result, true));
        when(result.get(CaptureResult.SCALER_RAW_CROP_REGION)).thenReturn(new Rect(32, 0, 4048, 3060));
        assertFalse(isz("3", result, true));
        zoom.when(ZoomController::residual).thenReturn(1.79f);
        assertFalse(isz("3", null, true));
        zoom.when(ZoomController::residual).thenReturn(2f);
        assertTrue(isz("3", null, true));
        assertFalse(isz("3", null, false));
        when(characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)).thenReturn(new long[]{0L});
        assertFalse(isz("3", null, true));
    }

    @Test public void fullResolutionAndDcgNeverInheritIszAspectFromPreset() {
        force(3);
        assertTrue(isz("3", null, false));
        PixelPro10.setFullRes(true);
        assertFalse(isz("3", null, false));
        PixelPro10.setFullRes(false);
        Dcg14Mode.setMode(Dcg14Mode.DCG);
        assertFalse(isz("3", null, false));
    }
}
