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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** P385: the settings order is one shared main setting: every module has it, the config file carries it to this and another phone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class SettingsLayoutConfigTest {
    private Context context;
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;

    @Before
    public void setUp() {
        // SCAM HDR and its settings exist only on the Snapdragon 8 Elite; these tests cover both routes.
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        // the settings keys resolve their names through it: never null under the mock (a Key enum first loaded here kept
        // null names for every later test of the JVM)
        camera.when(() -> ScameraApp.getStringStatic(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> org.robolectric.RuntimeEnvironment.getApplication().getString(i.<Integer>getArgument(0)));
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        camera.when(ScameraApp::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        PreferenceKeys.setDefaults(context);
    }

    @After
    public void tearDown() {
        camera.close();
    }

    @Test
    public void theOrderTravelsWithTheConfigAndStaysWithEveryModule() throws Exception {
        assertFalse(ModuleProfiles.isLocal(SettingsLayout.KEY));
        assertFalse(BackupRestoreUtil.deviceOnly(SettingsLayout.KEY));
        assertFalse(SettingsMigration.isObsolete(SettingsLayout.KEY));
        String order = "prefscreen=root_view,root_colour;root_shoot=pref_ettr,pref_motion_mode";
        prefs.edit().putString(SettingsLayout.KEY, order).commit();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConfigXml.write(out, BackupRestoreUtil.header(RuntimeEnvironment.getApplication()), BackupRestoreUtil.collect(RuntimeEnvironment.getApplication()));
        ConfigXml.Config config = ConfigXml.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(order, config.files.get(ConfigXml.MAIN).get(SettingsLayout.KEY));
        // same phone
        prefs.edit().putString(SettingsLayout.KEY, "prefscreen=root_about").commit();
        BackupRestoreUtil.apply(RuntimeEnvironment.getApplication(), config);
        assertEquals(order, prefs.getString(SettingsLayout.KEY, null));
        // another phone: the main settings travel, the order with them
        prefs.edit().remove(SettingsLayout.KEY).commit();
        config.attributes.put("device", "oppo/op627cl1");
        BackupRestoreUtil.apply(RuntimeEnvironment.getApplication(), config);
        assertEquals(order, prefs.getString(SettingsLayout.KEY, null));
        // a config without it: the app's order
        config.files.get(ConfigXml.MAIN).remove(SettingsLayout.KEY);
        BackupRestoreUtil.apply(RuntimeEnvironment.getApplication(), config);
        assertFalse(prefs.contains(SettingsLayout.KEY));
    }
}
