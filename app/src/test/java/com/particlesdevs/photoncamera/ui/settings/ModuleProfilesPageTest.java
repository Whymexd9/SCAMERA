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
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

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
import com.particlesdevs.photoncamera.ui.settings.custompreferences.ModuleRowPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.ModuleSection;

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
 * pages show only its per-module values and edit that profile while the camera keeps its module. P384 (owner, 2026-10-28:
 * «Переместить эту группу настроек на главный экран»): the profiles are rows of each module's card in «Модули камеры» on the main
 * page (no page in between); the per-module switch and copying are rows under the cards.
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

    private static SettingsActivity.SettingsFragment root(SettingsActivity activity) {
        Fragment root = top(activity);
        assertTrue(String.valueOf(root), root instanceof SettingsActivity.SettingsFragment);
        assertNull(((SettingsActivity.SettingsFragment) root).moduleProfile());
        return (SettingsActivity.SettingsFragment) root;
    }

    /** The profile rows of {@code slot}'s card, in order. */
    private static List<ModuleRowPreference> profileRows(SettingsActivity.SettingsFragment root, String slot) {
        ModuleSection card = root.findPreference(ModuleRows.CARD + slot);
        assertNotNull(slot, card);
        List<ModuleRowPreference> out = new ArrayList<>();
        for (int i = 0; i < card.getPreferenceCount(); i++)
            if (card.getPreference(i).getKey().startsWith(ModuleRows.PROFILE)) out.add((ModuleRowPreference) card.getPreference(i));
        return out;
    }

    private static List<String> keys(List<? extends Preference> rows) {
        List<String> out = new ArrayList<>();
        for (Preference p : rows) out.add(p.getKey());
        return out;
    }

    private static void back(SettingsActivity activity) {
        activity.getOnBackPressedDispatcher().onBackPressed();
        top(activity);
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

    /**
     * P384 (was: «Камеры и модули» found by its row and its search hit): the modules are a group of the main page - no page in
     * between; the per-module switch says whether the modules have their own settings now; the search finds the group by its old
     * name and the switch by the old group's words, and a hit on a folded card's row opens the card; back leaves the settings page
     * as it was, no restart.
     */
    @Test public void modulesOnTheMainPageAndFound() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            FragmentManager fm = activity.getSupportFragmentManager();
            SettingsActivity.SettingsFragment root = root(activity);
            assertNull(root.findPreference("module_profiles_screen"));
            assertNull("no «Камеры и модули» page in between", root.findPreference("camera_settings_screen"));
            PreferenceGroup modules = root.findPreference(ModuleRows.GROUP);
            assertNotNull(modules);
            assertTrue(modules.isVisible());
            SwitchPreferenceCompat perLens = root.findPreference(ModuleRows.PER_LENS);
            assertTrue(perLens.isChecked());
            assertEquals("Each module and its FULLRES 50 / 200 MP shots with their own settings", String.valueOf(perLens.getSummary()));
            controller.pause();
            prefs.edit().putBoolean("pref_save_per_lens_settings", false).commit();
            controller.resume();
            idle();
            assertFalse(perLens.isChecked());
            assertEquals("All modules shoot with the common settings now", String.valueOf(perLens.getSummary()));
            controller.pause();
            prefs.edit().putBoolean("pref_save_per_lens_settings", true).commit();
            controller.resume();
            idle();
            assertTrue(perLens.isChecked());

            List<SettingsSearchFragment.Entry> index = SettingsSearchFragment.index(root.getPreferenceScreen());
            Map<String, String> words = SettingsSearchFragment.searchWords(context, index);
            SettingsSearchFragment.Entry group = null, perModule = null, profile = null;
            for (SettingsSearchFragment.Entry e : index) {
                if (ModuleRows.GROUP.equals(e.key)) group = e;
                if (ModuleRows.PER_LENS.equals(e.key)) perModule = e;
                if ("module_row_profile_back1@full200".equals(e.key)) profile = e;
            }
            assertNotNull("the search finds the group", group);
            assertTrue("found by the old page's name", group.matches("cameras and modules", words.get(group.key)));
            assertNotNull(perModule);
            assertTrue("found by the old group's words", perModule.matches("settings per module", words.get(perModule.key))
                    && perModule.matches("FULLRES", words.get(perModule.key)));
            assertNotNull("a folded card's row is found", profile);
            activity.openSearchResult(profile);
            SettingsActivity.SettingsFragment page = root(activity);
            ModuleSection card = page.findPreference(ModuleRows.CARD + "back1");
            assertTrue("the hit's card is open", card.isExpanded());
            PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) page.getListView().getAdapter();
            assertTrue(adapter.getPreferenceAdapterPosition(page.findPreference("module_row_profile_back1@full200")) >= 0);
            assertEquals(1, fm.getBackStackEntryCount());
            back(activity);
            assertEquals(0, fm.getBackStackEntryCount());
            assertFalse(activity.isFinishing());
            assertSame(root, top(activity));
            camera.verify(() -> ScameraApp.restartApp(any(Context.class)), never());
        }
    }

    @Test public void groupListsModulesThenTheirFullResProfiles() {
        groupListsModulesThenTheirFullResProfiles("3× · 200 MP", "FULLRES 200 MP shots", "same as “3×”", "in the camera now");
    }

    @Test @Config(qualifiers = "ru-w400dp-h880dp-mdpi") public void groupListsModulesThenTheirFullResProfilesInRussian() {
        groupListsModulesThenTheirFullResProfiles("3× · 200 Мп", "Снимки FULLRES 200 Мп", "пока как у «3×»", "сейчас в камере");
    }

    /** P384: each module's card holds its own profile and then its FULLRES 50 / 200 MP ones (the former «Настройки по модулям» list). */
    private void groupListsModulesThenTheirFullResProfiles(String title200, String shots200, String unopened, String inCamera) {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity.SettingsFragment root = root(controller.get());
            assertEquals(Arrays.asList("module_row_profile_back0", "module_row_profile_back0@full"), keys(profileRows(root, "back0")));
            List<ModuleRowPreference> rows = profileRows(root, "back1");
            assertEquals(Arrays.asList("module_row_profile_back1", "module_row_profile_back1@full50", "module_row_profile_back1@full200"), keys(rows));
            ModuleRowPreference full200 = rows.get(2);
            String text = full200.getTitle() + "\n" + full200.getSummary();
            assertTrue(text, text.contains(title200) && text.contains(shots200) && text.contains(unopened));
            ModuleRowPreference back0 = profileRows(root, "back0").get(0);
            assertTrue(String.valueOf(back0.getSummary()), String.valueOf(back0.getSummary()).contains(inCamera));
            assertFalse(String.valueOf(rows.get(0).getSummary()), String.valueOf(rows.get(0).getSummary()).contains(inCamera));
            // the module's own profile beside its FULLRES ones says which it is
            assertTrue(String.valueOf(rows.get(0).getSummary()), String.valueOf(rows.get(0).getSummary()).endsWith(
                    root.getString(R.string.module_row_profile_regular)));
            List<ModuleRowPreference> all = new ArrayList<>(profileRows(root, "back0"));
            all.addAll(rows);
            for (ModuleRowPreference row : all) {
                assertFalse(row.getKey(), row.dimmed());
                assertEquals(row.getKey(), 1f, SettingsStyle.restingAlpha(row), 1e-3);
            }
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
            SettingsActivity.SettingsFragment root = root(activity);
            root.findPreference("module_row_profile_back1@full200").performClick();
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
            assertEquals("the main page's profile, given back on the way back", "back0",
                    page.requireArguments().getString(SettingsActivity.SettingsFragment.ARG_RETURN_PROFILE));

            prefs.edit().putString(CDM, "0.3").commit();   // an edit of the 3× 200 MP shots
            if (finish) {
                activity.finish();
                controller.pause();
            } else {
                back(activity);
                assertSame("back on the main page", root, top(activity));
            }
            assertEquals("back0", PreferenceKeys.profiles().editing());
            assertEquals("the camera module's value back", "0.9", prefs.getString(CDM, ""));
            assertEquals("0.3", context.getSharedPreferences("module_profile_v2_back1@full200", 0).getString(CDM, ""));
            assertEquals("the 3×'s own unchanged", "0.5", context.getSharedPreferences("module_profile_v2_back1", 0).getString(CDM, ""));
            if (!finish) {
                Fragment back = top(activity);
                assertTrue(back instanceof SettingsActivity.SettingsFragment);
                assertNull(((SettingsActivity.SettingsFragment) back).moduleProfile());
                TextView rootChip = back.requireView().findViewWithTag("settings_chip");
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
        // SIMPLE_UI §4: the root is groups of rows; a module page never shows the app's, the about or the system rows
        for (String key : ModulePageFilter.NOT_MODULE) assertFalse(key, shown(root.findPreference(key)));
        List<Preference> leaves = new ArrayList<>();
        leaves(root, leaves);
        int local = 0;
        for (Preference p : leaves) {
            String key = p.getKey();
            if (key == null) continue;
            boolean shared = false;   // under a group a module page never shows (ModulePageFilter.NOT_MODULE)
            for (Preference q = p; q != null; q = q.getParent()) if (ModulePageFilter.NOT_MODULE.contains(q.getKey())) shared = true;
            if (shared) continue;
            if (shown(p) && (key.startsWith("pref_") || key.startsWith("scamera_")))
                assertTrue("a shared row on a module page: " + key, ModuleProfiles.isLocal(key));
            if (ModuleProfiles.isLocal(key) && p.isSelectable() && !VivoSdkFullRes.PREF_HW_REMOSAIC.equals(key)
                    && com.particlesdevs.photoncamera.settings.FullResProfiles.framesTag(key) == null) {
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
        // P190: the FULLRES frame counts are rows of the FULLRES profiles only
        assertFalse(shown(root.findPreference(com.particlesdevs.photoncamera.settings.FullResProfiles.FRAMES_50)));
        assertFalse(shown(root.findPreference(com.particlesdevs.photoncamera.settings.FullResProfiles.FRAMES_200)));
        // P384: the module cards and the Camera ID rows are every module's; the module's sensor is its group of «Для опытных»
        assertFalse(shown(root.findPreference(ModuleRows.GROUP)));
        assertFalse(shown(root.findPreference(ModuleRows.IDS)));
        assertNotNull(root.findPreference(ModulePageFilter.SENSOR_GROUP));
        assertTrue(shown(root.findPreference(ModulePageFilter.SENSOR_GROUP)));
        assertEquals("This module's sensor", String.valueOf(root.findPreference(ModulePageFilter.SENSOR_GROUP).getTitle()));
        assertTrue("the module's RAW page", shown(root.findPreference("capture_settings_screen")));

        PreferenceScreen fifty = inflate();
        ModulePageFilter.apply(fifty, "back0@full50", true);
        assertTrue("hardware remosaic acts at 50 MP", shown(fifty.findPreference(VivoSdkFullRes.PREF_HW_REMOSAIC)));
        PreferenceScreen twoHundred = inflate();
        ModulePageFilter.apply(twoHundred, "back0@full200", true);
        assertFalse("no hardware 200 MP mode", shown(twoHundred.findPreference(VivoSdkFullRes.PREF_HW_REMOSAIC)));
        // P190: each FULLRES profile shows its own frame count only
        assertTrue(shown(fifty.findPreference(com.particlesdevs.photoncamera.settings.FullResProfiles.FRAMES_50)));
        assertFalse(shown(fifty.findPreference(com.particlesdevs.photoncamera.settings.FullResProfiles.FRAMES_200)));
        assertTrue(shown(twoHundred.findPreference(com.particlesdevs.photoncamera.settings.FullResProfiles.FRAMES_200)));
        assertFalse(shown(twoHundred.findPreference(com.particlesdevs.photoncamera.settings.FullResProfiles.FRAMES_50)));
    }

    @Test public void availabilityKeepsSharedRowsHidden() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            activity.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, SettingsActivity.SettingsFragment.modulePage("back0", "advanced_screen"))
                    .addToBackStack("module").commit();
            SettingsActivity.SettingsFragment page = (SettingsActivity.SettingsFragment) top(activity);
            assertTrue(page.isResumed());
            prefs.edit().putString("pref_scam_hybrid_zsl_frames", "12").commit();
            idle();
            assertFalse(page.findPreference("pref_zsl_buffer_count_key").isVisible());
            assertNull("SIMPLE_UI §4: «Против мерцания» lives under «Съёмка», not on this page", page.findPreference("pref_antibanding_hz_key"));
            assertTrue(page.findPreference("pref_scam_hybrid_zsl_frames").isVisible());
        }
    }

    /**
     * A module profile's root page shows its own sensor only (no chips, the other modules' categories hidden), retitled; its
     * pages stay the module's (P384: «RAW и сенсор» one level up, under «Другие тонкие настройки»).
     */
    @Test public void navigationInsideTheGroupStaysOnTheModule() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            activity.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, SettingsActivity.SettingsFragment.modulePage("back1@full200", null))
                    .addToBackStack("module").commit();
            SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) top(activity);
            assertEquals("back1@full200", root.moduleProfile());
            assertFalse(root.findPreference(ModuleRows.GROUP).isVisible());
            assertFalse(root.findPreference(ModuleRows.IDS).isVisible());
            PreferenceGroup sensor = root.findPreference(ModulePageFilter.SENSOR_GROUP);
            assertTrue(shown(sensor));
            assertEquals("This module's sensor", String.valueOf(sensor.getTitle()));
            assertTrue(String.valueOf(sensor.getSummary()), String.valueOf(sensor.getSummary()).contains("Shared by “3×” and its FULLRES shots"));
            List<String> shownCategories = new ArrayList<>();
            for (int i = 0; i < sensor.getPreferenceCount(); i++)
                if (sensor.getPreference(i) instanceof androidx.preference.PreferenceCategory && sensor.getPreference(i).isVisible())
                    shownCategories.add(sensor.getPreference(i).getKey());
            assertEquals(Arrays.asList("pref_category_sensor_back1"), shownCategories);
            assertFalse("no module chips on a module's page", root.findPreference(ModuleRows.PICKER).isVisible());
            for (String key : new String[]{"scam_hybrid_screen", "advanced_screen"}) {
                activity.onPreferenceStartScreen(root, root.findPreference(key));
                Fragment next = top(activity);
                assertTrue(key + ": " + next, next instanceof SettingsActivity.SettingsFragment);
                assertEquals(key, "back1@full200", ((SettingsActivity.SettingsFragment) next).moduleProfile());
                if (key.equals("advanced_screen")) {
                    SettingsActivity.SettingsFragment advanced = (SettingsActivity.SettingsFragment) next;
                    activity.onPreferenceStartScreen(advanced, advanced.findPreference("capture_settings_screen"));
                    Fragment raw = top(activity);
                    assertTrue(raw instanceof SettingsActivity.SettingsFragment);
                    assertEquals("capture_settings_screen", ((SettingsActivity.SettingsFragment) raw).getPreferenceScreen().getKey());
                    assertEquals("the module's RAW page", "back1@full200", ((SettingsActivity.SettingsFragment) raw).moduleProfile());
                    back(activity);
                }
                back(activity);
                assertEquals("back1@full200", PreferenceKeys.profiles().editing());
            }
        }
    }

    @Test public void perLensOffAsksFirst() {
        prefs.edit().putBoolean("pref_save_per_lens_settings", false).commit();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = root(activity);
            assertEquals("All modules shoot with the common settings now",
                    String.valueOf(root.findPreference(ModuleRows.PER_LENS).getSummary()));
            ModuleRowPreference row = root.findPreference("module_row_profile_back1");
            assertTrue("dimmed: it asks first", row.dimmed());
            assertEquals(.55f, SettingsStyle.restingAlpha(row), 1e-3);
            assertEquals("All modules share them now. Tap to turn on separate ones", String.valueOf(row.getSummary()));
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
            assertSame(root, top(activity));

            row.performClick();
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
            root(controller.get()).findPreference("module_row_profile_back1@full200").performClick();
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
            assertNotNull(copy.requireView().findViewWithTag("group_root_photo"));
            assertNull(copy.requireView().findViewWithTag("group_module_profiles_screen"));
            // P384: the main page's module cards hold nothing to copy; the module's sensor settings under «Сенсор по модулям»
            assertNull(copy.requireView().findViewWithTag("group_root_modules"));
            copy.requireView().findViewWithTag("group_root_expert").performClick();
            assertTrue(text(copy.requireView()), text(copy.requireView()).contains("Sensor per module"));
            assertNotNull(copy.requireView().findViewWithTag("parameter_sensor_copy_blackleveloverride"));
            assertNotNull(copy.requireView().findViewWithTag("parameter_sensor_copy_tunablekeys"));
        }
    }
}
