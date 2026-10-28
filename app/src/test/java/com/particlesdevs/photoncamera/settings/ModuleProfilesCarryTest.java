package com.particlesdevs.photoncamera.settings;

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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P193 (owner, 2026-10-17: «при изменении каких-либо настроек без включенного переключателя "Свои настройки для каждого модуля"
 * все настройки должны копироваться по модулям при включении данного переключателя, а не сбрасываться на дефолтные»): the
 * settings changed while per-module settings are off reach every module and FULLRES profile when they are turned on; a
 * module's own values the owner did not change in the common settings come back; the shared settings are never touched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class ModuleProfilesCarryTest {
    private static final String CDM = "pref_scam_hybrid_cdm", LUT = "pref_film_lut_strength", KERNEL = "pref_scam_hybrid_kernel_scale";
    private static final String GRID = "pref_show_grid_key";
    /** PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS (the Key enum reads its strings through ScameraApp, mocked in setUp). */
    private static final String PER_LENS = "pref_save_per_lens_settings";
    private static final String[] IDS = {"back0", "back1", "back2", "back5", "back1@full50", "back1@full200", "common"};
    /** Every profile the camera can take: stored, stored FULLRES, never-opened FULLRES, never-opened modules. */
    private static final String[] PROFILES = {"back0", "back1", "back2", "back5", "back1@full50", "back1@full200"};
    private Context context;
    private SettingsManager manager;
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        manager = new SettingsManager(context);
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
        clearProfiles();
        for (int i = 0; i < 3; i++)
            prefs.edit().putString("module_auto_back" + i, "" + (3 + i)).putString("module_label_back" + i, new String[]{"1×", "0.6×", "3×"}[i])
                    .putBoolean("module_visible_back" + i, true).commit();
        prefs.edit().putString("module_active", "back0").commit();
    }

    @After
    public void tearDown() {
        clearProfiles();
        if (camera != null) camera.close();
    }

    private void clearProfiles() {
        context.getSharedPreferences("module_profiles_meta", Context.MODE_PRIVATE).edit().clear().commit();
        for (String id : IDS) context.getSharedPreferences("module_profile_v2_" + id, Context.MODE_PRIVATE).edit().clear().commit();
        manager.openPreferences(PreferenceKeys.Key.PER_LENS_FILE_NAME.mValue).edit().clear().commit();
    }

    private void perLens(boolean on) {
        prefs.edit().putBoolean(PER_LENS, on).commit();
        PreferenceKeys.profiles().changed(PER_LENS);
    }

    /** A change in the settings the camera uses now (the profile listener's work). */
    private void set(String key, String value) {
        prefs.edit().putString(key, value).commit();
        PreferenceKeys.profiles().changed(key);
    }

    /** The value {@code id} gives the main settings when the camera takes it. */
    private String taken(String id, String key) {
        PreferenceKeys.profiles().activate(id);
        assertEquals(id, PreferenceKeys.profiles().editing());
        return prefs.getString(key, null);
    }

    /** Own profiles for back0, back1 and back1's 200 MP shots from an earlier time with per-module settings on. */
    private void earlierPerModuleRound() {
        set(CDM, "0.9");
        PreferenceKeys.profiles();
        perLens(true);
        PreferenceKeys.profiles().activate("back1");
        set(CDM, "0.5");
        set(LUT, "37.125");
        PreferenceKeys.profiles().activate("back1@full200");
        set(CDM, "0.4");
        set(KERNEL, "0.7");
        PreferenceKeys.profiles().activate("back0");
        assertEquals("0.9", prefs.getString(CDM, ""));
        perLens(false);
    }

    /**
     * Audit 2026-10-27 (lab-app-7): «Сохранять снимки для лаборатории» and «Сохранять RAW-серию» are switches of the app, not of a
     * module: with per-module settings on, a module switch loaded a profile without them and lab shots stopped on that lens.
     */
    @Test
    public void labKeepSwitchIsGlobal() {
        final String[] keys = {com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLab.PREF, "pref_keep_raw_burst"};
        for (String key : keys) {
            assertTrue(key, ModuleProfiles.isGlobal(key));
            assertFalse(key, ModuleProfiles.isLocal(key));
        }
        perLens(true);
        PreferenceKeys.profiles().activate("back0");
        for (String key : keys) {
            prefs.edit().putBoolean(key, true).commit();
            PreferenceKeys.profiles().changed(key);
        }
        PreferenceKeys.profiles().activate("back1");
        assertEquals("back1", PreferenceKeys.profiles().editing());
        for (String key : keys) assertTrue("on another module: " + key, prefs.getBoolean(key, false));
        PreferenceKeys.profiles().activate("back1@full200");
        for (String key : keys) assertTrue("in a FULLRES profile: " + key, prefs.getBoolean(key, false));
    }

    @Test
    public void aSettingChangedWhileOffReachesEveryModuleAndFullResProfile() {
        earlierPerModuleRound();
        assertEquals("off: the common settings", "0.9", prefs.getString(CDM, ""));
        set(CDM, "0.2");
        perLens(true);
        assertEquals("0.2", prefs.getString(CDM, ""));
        for (String id : PROFILES) assertEquals(id, "0.2", String.valueOf(PreferenceKeys.profiles().snapshot(id).get(CDM)));
        for (String id : PROFILES) assertEquals(id, "0.2", taken(id, CDM));
    }

    @Test
    public void aModulesOwnValuesComeBackWhereTheCommonSettingsWereNotChanged() {
        earlierPerModuleRound();
        set(CDM, "0.2");
        perLens(true);
        assertEquals("back1's own value", "37.125", taken("back1", LUT));
        assertEquals("its 200 MP profile's own value", "0.7", taken("back1@full200", KERNEL));
        assertEquals("its 200 MP profile starts from back1's other values", "37.125", taken("back1@full200", LUT));
        assertNull("another module never had it", taken("back0", LUT));
        assertNull(taken("back5", KERNEL));

        // off and on again with nothing changed: every module as it was
        PreferenceKeys.profiles().activate("back1");
        set(CDM, "0.3");
        perLens(false);
        perLens(true);
        assertEquals("0.3", taken("back1", CDM));
        assertEquals("0.2", taken("back0", CDM));
        assertEquals("0.2", taken("back1@full200", CDM));
    }

    @Test
    public void aSettingResetWhileOffIsResetInEveryModule() {
        earlierPerModuleRound();
        assertNull("off: the common settings have no film strength", prefs.getString(LUT, null));
        set(KERNEL, "0.6");
        perLens(true);
        assertEquals("0.6", taken("back1@full200", KERNEL));
        perLens(false);
        prefs.edit().remove(KERNEL).commit();
        PreferenceKeys.profiles().changed(KERNEL);
        perLens(true);
        for (String id : PROFILES) assertNull(id, taken(id, KERNEL));
        assertEquals("a value not reset stays the module's", "37.125", taken("back1", LUT));
    }

    @Test
    public void theFirstTurnOnStartsEveryModuleFromTheCurrentSettingsNotTheOldPerCameraJson() {
        // the per-camera JSON earlier versions wrote at the first camera start (the defaults of that start)
        for (String camera : new String[]{"3", "4", "5"})
            manager.set(PreferenceKeys.Key.PER_LENS_FILE_NAME.mValue, "settings_for_camera_" + camera,
                    "{\"" + CDM + "\":\"1.0\",\"" + KERNEL + "\":\"1.0\"}");
        PreferenceKeys.profiles();
        set(CDM, "0.25");
        set(KERNEL, "0.75");
        perLens(true);
        for (String id : PROFILES) {
            assertEquals(id, "0.25", taken(id, CDM));
            assertEquals(id, "0.75", prefs.getString(KERNEL, null));
        }
    }

    @Test
    public void theSharedSettingsAreNeverPartOfAModule() {
        earlierPerModuleRound();
        set(GRID, "3");
        prefs.edit().putString("module_label_back2", "3,1×").commit();
        perLens(true);
        assertEquals("3", prefs.getString(GRID, ""));
        assertEquals("3,1×", prefs.getString("module_label_back2", ""));
        for (String id : IDS) {
            SharedPreferences profile = context.getSharedPreferences("module_profile_v2_" + id, Context.MODE_PRIVATE);
            assertFalse(id, profile.contains(GRID));
            assertFalse(id, profile.contains("module_label_back2"));
            assertFalse(id, profile.contains(PER_LENS));
        }
        for (String id : PROFILES) {
            taken(id, CDM);
            assertEquals(id, "3", prefs.getString(GRID, ""));
            assertTrue(id, prefs.getBoolean(PER_LENS, false));
        }
    }
}
