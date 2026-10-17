package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * P199 (owner's X200 Ultra, 2026-10-17: «на x200u отображаются лишние модули» - «2×» twice): the CROPPED_RAW «2×» copy of the
 * main module goes when the camera has its own 2x ISZ module; a «2×» the owner made with a sensor mode, a crop or on a camera
 * without its own ISZ stays.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class IszModuleDuplicateTest {
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;

    @Before
    public void setUp() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        PreferenceKeys.initialise(manager);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
    }

    @After
    public void tearDown() {
        if (camera != null) camera.close();
    }

    /** The X200 Ultra's strip: back1 main 1x (camera 3), back6 its 2x ISZ (forceSensorMode 3), back3 the CROPPED_RAW «2×» copy. */
    private void x200(boolean nativeIsz) {
        SharedPreferences.Editor e = prefs.edit();
        String[] cams = {"4", "3", "5", "3", "3", "3", "3", "3"};
        for (int i = 0; i < 8; i++) {
            e.putString("module_auto_back" + i, cams[i]);
            e.putBoolean("module_visible_back" + i, i <= 3 || i == 6 && nativeIsz);
        }
        e.putString("module_zoom_back1", "1");
        e.putString("module_name_back3", IszModule.LABEL).putString("module_zoom_back3", "2")
                .putString(ModuleDuplicates.MARK + "back3", "back1");
        e.putString("module_name_back6", "2×").putString("module_zoom_back6", "2")
                .putString("pref_sensorconfig_back6_tunablekeys", "[{\"name\":\"vivo.control.forceSensorMode\",\"value\":\"3\"}]")
                .putString(ModuleDuplicates.MARK + "back6", "back1");
        e.commit();
    }

    @Test public void theCropCopyGoesWhenTheCameraHasItsOwnIsz() {
        x200(true);
        assertTrue("the CROPPED_RAW copy", IszModule.isOurCrop("back3"));
        assertFalse("the native ISZ module (a sensor mode)", IszModule.isOurCrop("back6"));
        IszModule.removeDuplicateOfNativeIsz();
        assertFalse(ModuleRegistry.slots().contains("back3") && ModuleRegistry.visible("back3"));
        assertTrue("the native 2x ISZ stays", ModuleRegistry.visible("back6"));
    }

    @Test public void theCropCopyStaysWithoutTheCamerasOwnIsz() {
        x200(false);
        IszModule.removeDuplicateOfNativeIsz();
        assertTrue(ModuleRegistry.visible("back3"));
    }
}
