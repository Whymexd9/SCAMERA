package com.particlesdevs.photoncamera.ui.camera;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ImageButton;

import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.capture.Dcg14Mode;
import com.particlesdevs.photoncamera.capture.PixelPro10;
import com.particlesdevs.photoncamera.capture.SensorModeSwitches;
import com.particlesdevs.photoncamera.capture.VivoQcomModes;
import com.particlesdevs.photoncamera.capture.VivoSdkFullRes;
import com.particlesdevs.photoncamera.capture.spoof.CameraPackageSpoof;
import com.particlesdevs.photoncamera.control.ZoomController;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class SensorModeSwitchesTest {
    private MockedStatic<ScameraApp> app;
    private MockedStatic<PreferenceKeys> preferences;
    private MockedStatic<PixelPro10> pixel;
    private MockedStatic<VivoQcomModes> vivo;
    private MockedStatic<CameraPackageSpoof> spoof;
    private SharedPreferences modules;
    private Settings settings;
    private CameraFragment fragment;
    private Context context;

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
        PreferenceKeys.initialise(manager);
        settings = new Settings();
        settings.mCameraID = "3";
        settings.selectedMode = CameraMode.userModeAt(0);
        app.when(ScameraApp::getSettings).thenReturn(settings);
        preferences = mockStatic(PreferenceKeys.class, CALLS_REAL_METHODS);
        pixel = mockStatic(PixelPro10.class, CALLS_REAL_METHODS);
        pixel.when(PixelPro10::device).thenReturn(false);
        vivo = mockStatic(VivoQcomModes.class);
        vivo.when(VivoQcomModes::device).thenReturn(true);
        spoof = mockStatic(CameraPackageSpoof.class);
        spoof.when(() -> CameraPackageSpoof.signature(context)).thenReturn("off");
        modules = manager.getDefaultPreferences();
        modules.edit().clear().apply();
        Dcg14Mode.setMode(Dcg14Mode.OFF);
        PixelPro10.setFullRes(false);
        module("back0", "3", "1", null);
        module("back1", "3", "2", "back0");
        modules.edit().putString("module_active", "back1").apply();
        fragment = mock(CameraFragment.class);
        fragment.captureController = mock(CaptureController.class);
        when(fragment.getString(anyInt())).thenAnswer(call -> context.getString(call.getArgument(0)));
    }

    @After public void cleanup() {
        spoof.close();
        vivo.close();
        pixel.close();
        preferences.close();
        app.close();
    }

    private void module(String slot, String camera, String zoom, String source) {
        SharedPreferences.Editor edit = modules.edit().putString("module_auto_" + slot, camera)
                .putString("module_zoom_" + slot, zoom).putBoolean("module_visible_" + slot, true);
        if (source == null) edit.remove("module_duplicate_" + slot);
        else edit.putString("module_duplicate_" + slot, source);
        edit.apply();
    }

    private String force(int mode) {
        String json = "[{\"key\":\"vivo.control.forceSensorMode\",\"value\":\"" + mode + "\",\"enabled\":true}]";
        modules.edit().putString("pref_sensorconfig_back1_tunablekeys", json)
                .putBoolean("module_sensorcrop_back1", false).apply();
        return json;
    }

    private void toggle(Class<?> button) throws Exception {
        Method toggle = button.getDeclaredMethod("toggle", CameraFragment.class, ImageButton.class);
        toggle.setAccessible(true);
        toggle.invoke(null, fragment, new ImageButton(context));
    }

    @Test public void fullResUiFromRemosaicIszSelectsBaseAndActuallyEnablesSdkInMotion() throws Exception {
        String json = force(6);
        Dcg14Mode.setMode(Dcg14Mode.DCG);
        assertTrue(VivoSdkFullRes.offered("3"));
        assertFalse(VivoSdkFullRes.available("3"));
        toggle(FullResButton.class);
        assertEquals(CameraMode.MOTION, settings.selectedMode);
        assertEquals("back0", ModuleRegistry.active());
        assertEquals("3", PreferenceKeys.getCameraID());
        assertTrue(PixelPro10.fullResOn());
        assertFalse(Dcg14Mode.on());
        assertTrue(VivoSdkFullRes.available("3"));
        assertEquals(1f, ZoomController.residual(), 0f);
        assertEquals(json, modules.getString("pref_sensorconfig_back1_tunablekeys", ""));
        verify(fragment.captureController, times(1)).restartCamera();
    }

    @Test public void dcgUiFromSdkCropSelectsBaseAndDisablesFullResBeforeOneRestart() throws Exception {
        modules.edit().putInt("module_vivo_qcom_isz_back1", 2).apply();
        PixelPro10.setFullRes(true);
        toggle(Dcg14Button.class);
        assertEquals("back0", ModuleRegistry.active());
        assertTrue(Dcg14Mode.on());
        assertFalse(PixelPro10.fullResOn());
        verify(fragment.captureController, times(1)).restartCamera();
    }

    @Test public void selectingForcedIszViaTheRegistryDisablesBothFlagsAndPreservesOwnerKeys() {
        String json = force(17);
        Dcg14Mode.setMode(Dcg14Mode.DCG);
        PixelPro10.setFullRes(true);
        ModuleRegistry.select("back1");
        assertFalse(Dcg14Mode.on());
        assertFalse(PixelPro10.fullResOn());
        assertEquals("back1", ModuleRegistry.active());
        assertEquals(json, modules.getString("pref_sensorconfig_back1_tunablekeys", ""));
        verifyNoInteractions(fragment.captureController);
    }

    @Test public void ordinaryTeleAndAReusedSlotWithStaleSdkMarkerDoNotDisableFlags() {
        vivo.when(VivoQcomModes::device).thenReturn(false);
        module("back1", "5", "3.5", null);
        modules.edit().putInt("module_vivo_qcom_isz_back1", 2).apply();
        Dcg14Mode.setMode(Dcg14Mode.DCG);
        PixelPro10.setFullRes(true);
        ModuleRegistry.select("back1");
        assertFalse(SensorModeSwitches.sensorPreset("back1"));
        assertTrue(Dcg14Mode.on());
        assertTrue(PixelPro10.fullResOn());
        assertFalse(VivoSdkFullRes.offered("5"));
    }

    @Test public void movingBetweenSdkRearLensesKeepsFullResButFrontClearsIt() {
        module("back2", "4", "0.6", null);
        modules.edit().putString("module_active", "back0").apply();
        PixelPro10.setFullRes(true);
        assertTrue(VivoSdkFullRes.available("3"));
        ModuleRegistry.select("back2");
        assertEquals("back2", ModuleRegistry.active());
        assertTrue(PixelPro10.fullResOn());
        assertTrue(VivoSdkFullRes.offered("4"));
        module("back3", "5", "2.4", null);
        ModuleRegistry.select("back3");
        assertTrue(PixelPro10.fullResOn());
        assertTrue(VivoSdkFullRes.offered("5"));
        module("front0", "1", "1", null);
        ModuleRegistry.select("front0");
        assertFalse(PixelPro10.fullResOn());
        assertFalse(VivoSdkFullRes.offered("1"));
    }

    @Test public void cleanInstallOffersVerifiedMainSdkWithoutEnablingTheUserToggle() {
        ModuleRegistry.select("back0");
        assertFalse(PreferenceKeys.scamDevOverrides("vivo_sdk_fullres"));
        assertTrue(VivoSdkFullRes.offered("3"));
        assertTrue(VivoSdkFullRes.offered("4"));
        assertTrue(VivoSdkFullRes.offered("5"));
        assertFalse(PixelPro10.fullResOn());
    }

    @Test public void explicitDevFileZeroHidesSdkWithoutEnablingTheUserToggle() throws Exception {
        java.io.File file = new java.io.File(context.getExternalFilesDir(null), "scam_dev.txt");
        java.lang.reflect.Field stamp = PreferenceKeys.class.getDeclaredField("scamDevStamp");
        stamp.setAccessible(true);
        java.nio.file.Files.write(file.toPath(), "vivo_sdk_fullres 0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        stamp.setLong(null, -1);
        try {
            assertTrue(PreferenceKeys.scamDevOverrides("vivo_sdk_fullres"));
            assertFalse(VivoSdkFullRes.offered("3"));
            assertFalse(PixelPro10.fullResOn());
        } finally {
            java.nio.file.Files.delete(file.toPath());
            stamp.setLong(null, -1);
        }
    }

    @Test public void aPerCameraOfferAndAHiddenSdkSourceDoNotProveAnIszSelection() {
        modules.edit().putBoolean("module_isz_offered_3", true).apply();
        PixelPro10.setFullRes(true);
        ModuleRegistry.select("back1");
        assertFalse(SensorModeSwitches.sensorPreset("back1"));
        assertTrue(PixelPro10.fullResOn());
        modules.edit().putInt("module_vivo_qcom_isz_back1", 2).putBoolean("module_visible_back0", false).apply();
        ModuleRegistry.select("back1");
        assertFalse(SensorModeSwitches.sensorPreset("back1"));
        assertTrue(PixelPro10.fullResOn());
    }

    @Test public void aMissingPlainModuleRefusesToggleWithoutChangingConfigurationOrRestarting() throws Exception {
        String json = force(6);
        modules.edit().putBoolean("module_visible_back0", false).apply();
        Dcg14Mode.setMode(Dcg14Mode.DCG);
        toggle(FullResButton.class);
        assertEquals("back1", ModuleRegistry.active());
        assertFalse(PixelPro10.fullResOn());
        assertTrue(Dcg14Mode.on());
        assertEquals(json, modules.getString("pref_sensorconfig_back1_tunablekeys", ""));
        verify(fragment.captureController, never()).restartCamera();
        verify(fragment).showSnackBar(anyString());
    }

    @Test public void switchingOffDoesNotSelectAnotherModuleOrDisableTheOtherFlag() throws Exception {
        force(6);
        PixelPro10.setFullRes(true);
        Dcg14Mode.setMode(Dcg14Mode.DCG);
        toggle(FullResButton.class);
        assertEquals("back1", ModuleRegistry.active());
        assertFalse(PixelPro10.fullResOn());
        assertTrue(Dcg14Mode.on());
        verify(fragment.captureController, times(1)).restartCamera();
    }

    @Test public void pixelCropSelectionAndFullResUiKeepTheSameLensForAllThreeCropIds() throws Exception {
        pixel.when(PixelPro10::device).thenReturn(true);
        for (String[] pair : new String[][]{{"0-5", "2"}, {"6", "0-4"}, {"0/9", "3"}}) {
            module("back0", pair[1], "1", null);
            module("back1", pair[0], "2", null);
            modules.edit().putBoolean("module_sensorcrop_back1", false).putString("module_active", "back0").apply();
            PixelPro10.setFullRes(true);
            ModuleRegistry.select("back1");
            assertFalse(PixelPro10.fullResOn());
            toggle(FullResButton.class);
            assertEquals("back0", ModuleRegistry.active());
            assertEquals(pair[1], settings.mCameraID);
            assertTrue(PixelPro10.fullResOn());
            assertEquals(Boolean.FALSE, ModuleRegistry.sensorCropExplicit("back1"));
        }
        verify(fragment.captureController, times(3)).restartCamera();
    }
}
