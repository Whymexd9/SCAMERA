package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.PixelPro10;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P181 (owner, 2026-10-16: «ты должен был сделать отдельные настройки для 50мп/200мп как для отдельных модулей»): a module's
 * FULLRES shots keep their own settings profile, which starts as a copy of the module's and is taken while FULLRES is on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class FullResProfilesTest {
    private static final String CDM = "pref_scam_hybrid_cdm";
    private Context context;
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
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
        PixelPro10.setFullRes(false);
        context.getSharedPreferences("module_profiles_meta", Context.MODE_PRIVATE).edit().clear().commit();
        for (String id : new String[]{"back0", "back1", "back0@full", "common"})
            context.getSharedPreferences("module_profile_v2_" + id, Context.MODE_PRIVATE).edit().clear().commit();
        if (camera != null) camera.close();
    }

    @Test
    public void profileIdsNameTheModuleAndTheSize() {
        assertEquals(200, FullResProfiles.megapixels(16320, 12288));
        assertEquals(200, FullResProfiles.megapixels(16320, 12256));
        assertEquals(50, FullResProfiles.megapixels(8192, 6144));
        assertEquals(50, FullResProfiles.megapixels(8160, 6144));
        assertTrue(FullResProfiles.isVariant("back2@full200"));
        assertFalse(FullResProfiles.isVariant("back2"));
        assertEquals("back2", FullResProfiles.slotOf("back2@full200"));
        assertEquals("back2", FullResProfiles.slotOf("back2"));
        assertEquals("200", FullResProfiles.tagOf("back2@full200"));
        assertEquals("", FullResProfiles.tagOf("back0@full"));
        assertNull(FullResProfiles.tagOf("back0"));
    }

    @Test
    public void fullResKeepsItsOwnSettingsStartingFromTheModules() {
        for (int i = 0; i < 2; i++)
            prefs.edit().putString("module_auto_back" + i, "" + (3 + i)).putString("module_label_back" + i, new String[]{"1×", "0.6×"}[i])
                    .putBoolean("module_visible_back" + i, true).commit();
        prefs.edit().putString("module_active", "back0").putString(CDM, "0.5").commit();
        String perLens = PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue;
        prefs.edit().putBoolean(perLens, true).commit();
        PreferenceKeys.profiles().changed(perLens);
        // no FULLRES for this camera: the module's own profile, no FULLRES entry
        assertEquals("back0", FullResProfiles.profileFor("back0"));
        assertEquals(Collections.emptyList(), FullResProfiles.variants("back0"));
        FullResProfiles.remember("3", true);
        assertEquals(Collections.singletonList("back0@full"), FullResProfiles.variants("back0"));
        assertEquals("FULLRES off: the module's own", "back0", FullResProfiles.profileFor("back0"));

        PixelPro10.setFullRes(true);
        assertEquals("back0@full", FullResProfiles.profileFor("back0"));
        assertEquals("another camera without FULLRES keeps its own", "back1", FullResProfiles.profileFor("back1"));
        ModuleRegistry.syncProfile();
        assertEquals("back0@full", PreferenceKeys.profiles().editing());
        assertEquals("a copy of the module's", "0.5", prefs.getString(CDM, ""));
        prefs.edit().putString(CDM, "0.9").commit();
        PreferenceKeys.profiles().changed(CDM);

        PixelPro10.setFullRes(false);
        ModuleRegistry.syncProfile();
        assertEquals("back0", PreferenceKeys.profiles().editing());
        assertEquals("the module keeps its own", "0.5", prefs.getString(CDM, ""));
        PixelPro10.setFullRes(true);
        ModuleRegistry.syncProfile();
        assertEquals("FULLRES keeps its own", "0.9", prefs.getString(CDM, ""));
        assertEquals("0.9", String.valueOf(PreferenceKeys.profiles().snapshot("back0@full").get(CDM)));
        assertEquals("0.5", String.valueOf(PreferenceKeys.profiles().snapshot("back0").get(CDM)));
        assertTrue(FullResProfiles.label("back0@full").startsWith("1× · "));
        assertEquals("1×", FullResProfiles.label("back0"));

        // copied between profiles as between modules
        PreferenceKeys.profiles().copy("back0@full", Arrays.asList("back1"), Collections.singleton(CDM));
        assertEquals("0.9", String.valueOf(PreferenceKeys.profiles().snapshot("back1").get(CDM)));
    }
}
