package com.particlesdevs.photoncamera.ui.settings;

import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.PixelPro10;
import com.particlesdevs.photoncamera.capture.VivoSdkFullRes;
import com.particlesdevs.photoncamera.settings.FullResProfiles;
import com.particlesdevs.photoncamera.settings.ModuleProfiles;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.settings.SettingsMigration;
import com.particlesdevs.photoncamera.settings.TunablePreferenceGenerator;
import com.particlesdevs.photoncamera.settings.TunableRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P189 (owner, 2026-10-17: «Я так и не увидел где я могу настраивать отдельно 50мп и 200мп, сделай отдельную группу настроек где
 * все будет разделено по модулям»): «Настройки по модулям» lists each module with its FULLRES 50 / 200 MP profiles; a profile's
 * pages show only its per-module values and edit that profile while the camera keeps its module.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class ModuleProfilesPageTest {
    private static final String CDM = "pref_scam_hybrid_cdm";
    private static final String[] FILES = {"back0", "back1", "back0@full", "back1@full50", "back1@full200", "common"};
    private Context context;
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;
    private MockedStatic<FullResProfiles> fullRes;

    @Before public void setUp() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        clearProfiles();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        PreferenceKeys.initialise(manager);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        // 1× ID 3 (FULLRES of unknown size, as the Pixel's) and 3× ID 5 with FULLRES 50 / 200 MP (as the X200 Ultra's tele)
        prefs.edit().putString("module_auto_back0", "3").putString("module_auto_back1", "5")
                .putString("module_label_back0", "1×").putString("module_label_back1", "3×")
                .putBoolean("module_visible_back0", true).putBoolean("module_visible_back1", true)
                .putString("module_active", "back0").putBoolean("pref_save_per_lens_settings", true)
                .putString(CDM, "0.9").commit();
        context.getSharedPreferences("module_profiles_meta", 0).edit().clear().putBoolean("baseline", true)
                .putBoolean("exists_back0", true).putBoolean("exists_back1", true).putString("active", "back0").commit();
        context.getSharedPreferences("module_profile_v2_back0", 0).edit().clear().putString(CDM, "0.9").commit();
        context.getSharedPreferences("module_profile_v2_back1", 0).edit().clear().putString(CDM, "0.5").commit();
        FullResProfiles.remember("3", true);
        fullRes = mockStatic(FullResProfiles.class, CALLS_REAL_METHODS);
        fullRes.when(() -> FullResProfiles.variants("back1")).thenReturn(Arrays.asList("back1@full50", "back1@full200"));
    }

    @After public void tearDown() {
        if (fullRes != null) fullRes.close();
        PixelPro10.setFullRes(false);
        FullResProfiles.remember("3", false);
        clearProfiles();
        if (camera != null) camera.close();
    }

    private void clearProfiles() {
        context.getSharedPreferences("module_profiles_meta", 0).edit().clear().commit();
        for (String id : FILES) context.getSharedPreferences("module_profile_v2_" + id, 0).edit().clear().commit();
    }

    private static void idle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private static Fragment top(SettingsActivity activity) {
        FragmentManager fm = activity.getSupportFragmentManager();
        fm.executePendingTransactions();
        idle();
        return fm.findFragmentById(R.id.settings_container);
    }

    private static ModuleProfilesFragment openGroup(SettingsActivity activity) {
        activity.openModuleProfiles("module_profiles");
        Fragment group = top(activity);
        assertTrue(String.valueOf(group), group instanceof ModuleProfilesFragment);
        return (ModuleProfilesFragment) group;
    }

    private static void back(SettingsActivity activity) {
        activity.getOnBackPressedDispatcher().onBackPressed();
        top(activity);
    }

    private static void collect(View view, List<View> out) {
        if (view.getTag() instanceof String && ((String) view.getTag()).startsWith("profile_")) out.add(view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), out);
    }

    private static String text(View view) {
        if (view instanceof TextView) return ((TextView) view).getText().toString();
        StringBuilder out = new StringBuilder();
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) out.append(text(((ViewGroup) view).getChildAt(i))).append('\n');
        return out.toString();
    }

    private Map<String, Object> state() {
        Map<String, Object> all = new HashMap<>();
        all.put("main", new HashMap<>(prefs.getAll()));
        all.put("meta", new HashMap<>(context.getSharedPreferences("module_profiles_meta", 0).getAll()));
        for (String id : FILES) all.put(id, new HashMap<>(context.getSharedPreferences("module_profile_v2_" + id, 0).getAll()));
        return all;
    }

    @Test public void rootRowAndSearchOpenTheGroup() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            FragmentManager fm = activity.getSupportFragmentManager();
            SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) top(activity);
            Preference hub = root.findPreference(ModulePageFilter.HUB);
            assertNotNull(hub);
            assertTrue(hub.isVisible());
            assertEquals(context.getString(R.string.prefs_module_profiles_screen_summary), String.valueOf(hub.getSummary()));
            // the real click of the empty screen row (androidx does not navigate it by itself)
            hub.performClick();
            assertTrue(top(activity) instanceof ModuleProfilesFragment);
            assertEquals(1, fm.getBackStackEntryCount());
            back(activity);
            assertEquals(0, fm.getBackStackEntryCount());
            assertFalse(activity.isFinishing());
            assertSame(root, top(activity));

            SettingsSearchFragment.Entry entry = null;
            for (SettingsSearchFragment.Entry e : SettingsSearchFragment.index(root.getPreferenceScreen()))
                if (ModulePageFilter.HUB.equals(e.key)) entry = e;
            assertNotNull("the search finds the group", entry);
            activity.openSearchResult(entry);
            assertTrue(top(activity) instanceof ModuleProfilesFragment);
            back(activity);
            assertEquals(0, fm.getBackStackEntryCount());
            assertFalse(activity.isFinishing());
            camera.verify(() -> ScameraApp.restartApp(any(Context.class)), never());
        }
    }

    @Test public void groupListsModulesThenTheirFullResProfiles() {
        groupListsModulesThenTheirFullResProfiles("3× · 200 MP", "FULLRES 200 MP shots", "same as “3×”", "in the camera now");
    }

    @Test @Config(qualifiers = "ru-w400dp-h880dp-mdpi") public void groupListsModulesThenTheirFullResProfilesInRussian() {
        groupListsModulesThenTheirFullResProfiles("3× · 200 Мп", "Снимки FULLRES 200 Мп", "пока как у «3×»", "сейчас в камере");
    }

    private void groupListsModulesThenTheirFullResProfiles(String title200, String shots200, String unopened, String inCamera) {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            ModuleProfilesFragment group = openGroup(controller.get());
            List<View> rows = new ArrayList<>();
            collect(group.requireView(), rows);
            List<String> tags = new ArrayList<>();
            for (View row : rows) tags.add((String) row.getTag());
            assertEquals(Arrays.asList("profile_back0", "profile_back0@full", "profile_back1", "profile_back1@full50", "profile_back1@full200"), tags);
            String full200 = text(rows.get(4));
            assertTrue(full200, full200.contains(title200) && full200.contains(shots200) && full200.contains(unopened));
            assertTrue(text(rows.get(0)), text(rows.get(0)).contains(inCamera));
            assertFalse(text(rows.get(2)), text(rows.get(2)).contains(inCamera));
            for (View row : rows) assertEquals(1f, row.getAlpha(), 1e-3);
        }
    }

    @Test public void fullResProfileIsEditedWhileTheCameraKeepsItsModule() {
        fullResProfileIsEdited(false);
    }

    @Test public void fullResProfileEditIsReturnedWhenTheSettingsClose() {
        fullResProfileIsEdited(true);
    }

    private void fullResProfileIsEdited(boolean finish) {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            ModuleProfilesFragment group = openGroup(activity);
            group.requireView().findViewWithTag("profile_back1@full200").performClick();
            Fragment top = top(activity);
            assertTrue(String.valueOf(top), top instanceof SettingsActivity.SettingsFragment);
            SettingsActivity.SettingsFragment page = (SettingsActivity.SettingsFragment) top;
            assertEquals("back1@full200", page.moduleProfile());
            assertEquals("back1@full200", PreferenceKeys.profiles().editing());
            assertEquals("never opened: the 3×'s values", "0.5", prefs.getString(CDM, ""));
            assertFalse("FULLRES stays as it was", PixelPro10.fullResChosen());
            assertEquals("the camera keeps its module", "back0", ModuleRegistry.active());
            assertTrue(text(page.requireView()), text(page.requireView()).contains("3× · 200 MP"));
            TextView chip = page.requireView().findViewWithTag("settings_chip");
            assertNotNull(chip);
            assertTrue(chip.getText().toString(), chip.getText().toString().startsWith("Editing: 3× · 200 MP · ID 5"));
            assertFalse(chip.getText().toString(), chip.getText().toString().endsWith("▾"));
            assertNull("no search on a module page", page.requireView().findViewWithTag("settings_search"));

            prefs.edit().putString(CDM, "0.3").commit();   // an edit of the 3× 200 MP shots
            if (finish) {
                activity.finish();
                controller.pause();
            } else {
                back(activity);
                assertTrue(top(activity) instanceof ModuleProfilesFragment);
                assertEquals("the group keeps the edited profile", "back1@full200", PreferenceKeys.profiles().editing());
                back(activity);
            }
            assertEquals("back0", PreferenceKeys.profiles().editing());
            assertEquals("the camera module's value back", "0.9", prefs.getString(CDM, ""));
            assertEquals("0.3", context.getSharedPreferences("module_profile_v2_back1@full200", 0).getString(CDM, ""));
            assertEquals("the 3×'s own unchanged", "0.5", context.getSharedPreferences("module_profile_v2_back1", 0).getString(CDM, ""));
            if (!finish) {
                Fragment root = top(activity);
                assertTrue(root instanceof SettingsActivity.SettingsFragment);
                assertNull(((SettingsActivity.SettingsFragment) root).moduleProfile());
                TextView rootChip = root.requireView().findViewWithTag("settings_chip");
                assertTrue(rootChip.getText().toString(), rootChip.getText().toString().startsWith("Editing: 1×"));
            }
        }
    }

    private PreferenceScreen inflate() {
        SettingsMigration.prepare(context, prefs);
        PreferenceManager pm = new PreferenceManager(context);
        PreferenceScreen screen = pm.inflateFromResource(context, R.xml.preferences, null);
        pm.setPreferences(screen);
        for (Class<?> type : TunableRegistry.TUNABLE_CLASSES) TunablePreferenceGenerator.registerTunableClass(type);
        TunablePreferenceGenerator.generatePreferences(context, screen);
        return screen;
    }

    /** Visible with every parent group. */
    private static boolean shown(Preference p) {
        for (Preference q = p; q != null; q = q.getParent()) if (!q.isVisible()) return false;
        return true;
    }

    private static void leaves(PreferenceGroup group, List<Preference> out) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            if (p instanceof PreferenceGroup) leaves((PreferenceGroup) p, out);
            else out.add(p);
        }
    }

    @Test public void modulePagesShowOnlyModuleValues() {
        PreferenceScreen root = inflate();
        ModulePageFilter.apply(root, "back0", true);
        List<String> visible = new ArrayList<>();
        for (int i = 0; i < root.getPreferenceCount(); i++) if (root.getPreference(i).isVisible()) visible.add(root.getPreference(i).getKey());
        assertEquals(ModulePageFilter.ROOT_PAGES, visible);
        List<Preference> leaves = new ArrayList<>();
        for (String key : ModulePageFilter.ROOT_PAGES) leaves((PreferenceGroup) root.findPreference(key), leaves);
        int local = 0;
        for (Preference p : leaves) {
            String key = p.getKey();
            if (key == null) continue;
            if (shown(p) && (key.startsWith("pref_") || key.startsWith("scamera_")))
                assertTrue("a shared row on a module page: " + key, ModuleProfiles.isLocal(key));
            if (ModuleProfiles.isLocal(key) && p.isSelectable() && !VivoSdkFullRes.PREF_HW_REMOSAIC.equals(key)) {
                assertTrue("a module value hidden: " + key, shown(p));
                local++;
            }
        }
        assertTrue("module values: " + local, local > 50);
        for (String key : new String[]{"pref_photo_format", "pref_jpeg_quality", "watermark_screen", "pref_zsl_buffer_count_key",
                "pref_antibanding_hz_key", "pref_camera_sounds_key", "pref_show_grid_key", "pref_face_detect_mode", "pref_low_light_boost"}) {
            Preference p = root.findPreference(key);
            assertNotNull(key, p);
            assertFalse("shared: " + key, shown(p));
        }
        assertFalse(shown(root.findPreference(VivoSdkFullRes.PREF_HW_REMOSAIC)));
        assertNotNull(root.findPreference(ModulePageFilter.SENSOR_PAGE));
        assertTrue(shown(root.findPreference(ModulePageFilter.SENSOR_PAGE)));
        assertEquals("Sensor and RAW", String.valueOf(root.findPreference(ModulePageFilter.CAMERAS).getTitle()));

        PreferenceScreen fifty = inflate();
        ModulePageFilter.apply(fifty, "back0@full50", true);
        assertTrue("hardware remosaic acts at 50 MP", shown(fifty.findPreference(VivoSdkFullRes.PREF_HW_REMOSAIC)));
        PreferenceScreen twoHundred = inflate();
        ModulePageFilter.apply(twoHundred, "back0@full200", true);
        assertFalse("no hardware 200 MP mode", shown(twoHundred.findPreference(VivoSdkFullRes.PREF_HW_REMOSAIC)));
    }

    @Test public void availabilityKeepsSharedRowsHidden() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            activity.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, SettingsActivity.SettingsFragment.modulePage("back0", "shooting_settings_screen"))
                    .addToBackStack("module").commit();
            SettingsActivity.SettingsFragment page = (SettingsActivity.SettingsFragment) top(activity);
            assertTrue(page.isResumed());
            prefs.edit().putString("pref_scam_hybrid_zsl_frames", "12").commit();
            idle();
            assertFalse(page.findPreference("pref_zsl_buffer_count_key").isVisible());
            assertFalse(page.findPreference("pref_antibanding_hz_key").isVisible());
            assertTrue(page.findPreference("pref_scam_hybrid_zsl_frames").isVisible());
        }
    }

    @Test public void navigationInsideTheGroupStaysOnTheModule() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            activity.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, SettingsActivity.SettingsFragment.modulePage("back1@full200", null))
                    .addToBackStack("module").commit();
            SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) top(activity);
            for (String key : new String[]{"scam_hybrid_screen", ModulePageFilter.CAMERAS}) {
                activity.onPreferenceStartScreen(root, root.findPreference(key));
                Fragment next = top(activity);
                assertTrue(key + ": " + next, next instanceof SettingsActivity.SettingsFragment);
                assertEquals(key, "back1@full200", ((SettingsActivity.SettingsFragment) next).moduleProfile());
                if (key.equals(ModulePageFilter.CAMERAS)) {
                    SettingsActivity.SettingsFragment cameras = (SettingsActivity.SettingsFragment) next;
                    assertEquals("Sensor and RAW", String.valueOf(cameras.getPreferenceScreen().getTitle()));
                    Preference sensor = cameras.findPreference(ModulePageFilter.SENSOR_PAGE);
                    assertTrue(String.valueOf(sensor.getSummary()), String.valueOf(sensor.getSummary()).contains("Shared by “3×”"));
                    sensor.performClick();
                    Fragment sensorPage = top(activity);
                    assertTrue(sensorPage instanceof SettingsActivity.SettingsFragment);
                    assertEquals("the module's own sensor", "back1",
                            sensorPage.requireArguments().getString(SettingsActivity.SettingsFragment.ARG_SENSOR_SLOT));
                    assertNull(((SettingsActivity.SettingsFragment) sensorPage).moduleProfile());
                } else back(activity);
                assertEquals("back1@full200", PreferenceKeys.profiles().editing());
            }
        }
    }

    @Test public void perLensOffAsksFirst() {
        prefs.edit().putBoolean("pref_save_per_lens_settings", false).commit();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) top(activity);
            assertEquals("All modules share the settings now", String.valueOf(root.findPreference(ModulePageFilter.HUB).getSummary()));
            ModuleProfilesFragment group = openGroup(activity);
            View row = group.requireView().findViewWithTag("profile_back1");
            assertEquals(.55f, row.getAlpha(), 1e-3);
            Map<String, Object> before = state();
            String editing = PreferenceKeys.profiles().editing();
            row.performClick();
            idle();
            androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            assertEquals(editing, PreferenceKeys.profiles().editing());
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            idle();
            assertEquals("cancel changes nothing", before, state());
            assertTrue(top(activity) instanceof ModuleProfilesFragment);

            group.requireView().findViewWithTag("profile_back1").performClick();
            idle();
            dialog = (androidx.appcompat.app.AlertDialog) ShadowDialog.getLatestDialog();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            Fragment page = top(activity);
            assertTrue(PreferenceKeys.isPerLensSettingsOn());
            assertEquals("back1", PreferenceKeys.profiles().editing());
            assertTrue(String.valueOf(page), page instanceof SettingsActivity.SettingsFragment);
            assertEquals("back1", ((SettingsActivity.SettingsFragment) page).moduleProfile());
        }
    }

    @Test public void recreateAndCameraTakeBack() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            ModuleProfilesFragment group = openGroup(controller.get());
            group.requireView().findViewWithTag("profile_back1@full200").performClick();
            top(controller.get());
            assertEquals("back1@full200", PreferenceKeys.profiles().editing());

            controller.recreate();
            Fragment page = top(controller.get());
            assertTrue(String.valueOf(page), page instanceof SettingsActivity.SettingsFragment);
            assertEquals("back1@full200", ((SettingsActivity.SettingsFragment) page).moduleProfile());
            assertEquals("back1@full200", PreferenceKeys.profiles().editing());

            // the camera came back meanwhile (Home -> camera -> back to the settings)
            ModuleRegistry.syncProfile();
            assertEquals("back0", PreferenceKeys.profiles().editing());
            controller.pause().resume();
            idle();   // the page's recreate runs
            page = top(controller.get());
            assertEquals("the page takes its profile again", "back1@full200", PreferenceKeys.profiles().editing());
            assertEquals("0.5", prefs.getString(CDM, ""));
            assertTrue(String.valueOf(page), page instanceof SettingsActivity.SettingsFragment);
            assertEquals("back1@full200", ((SettingsActivity.SettingsFragment) page).moduleProfile());
            SettingsActivity activity = controller.get();
            controller.pause().resume();
            idle();
            assertSame("no recreate when the profile is held", activity, controller.get());
            assertSame(page, top(controller.get()));
            assertEquals("back1@full200", PreferenceKeys.profiles().editing());
        }
    }

    @Test public void copyCatalogHasNoHubGroup() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            FragmentManager fm = controller.get().getSupportFragmentManager();
            ModuleCopyFragment copy = new ModuleCopyFragment();
            fm.beginTransaction().replace(R.id.settings_container, copy).commitNow();
            assertNotNull(copy.requireView().findViewWithTag("group_output_settings_screen"));
            assertNull(copy.requireView().findViewWithTag("group_" + ModulePageFilter.HUB));
        }
    }
}
