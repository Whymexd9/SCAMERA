package com.particlesdevs.photoncamera.ui.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.SensorModePick;
import com.particlesdevs.photoncamera.settings.FullResProfiles;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.ModuleSensorSettings;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SensorConfigPreferenceGenerator;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.ModuleChipsPreference;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P390: since «Положи эти настройки в одну группу» on their own page «Камеры и модули» (root() opens it).
 * P384 (owner, 2026-10-28: «Переместить эту группу настроек на главный экран, переделай это меню по-своему чтобы им было очень
 * легко и просто пользоваться… Сделай это меню красивым с плавными анимациями и красивыми иконками»): the camera groups of the
 * settings' main page - the module cards (one open at a time, its rows written to the same module keys as the module pages
 * wrote), the Camera ID rows, the sensor chips; the list animates the cards through its diff; nothing a user only looks at
 * changes a stored value. Renders for a look: build/reports/module-concept/p384-*.png.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ModuleRowsTest {
    private static final String[] FILES = {"back0", "back1", "back2", "back3", "back4", "front0", "back1@full50", "back1@full200", "common"};
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
        fullRes = mockStatic(FullResProfiles.class, CALLS_REAL_METHODS);
        fullRes.when(() -> FullResProfiles.variants("back1")).thenReturn(Arrays.asList("back1@full50", "back1@full200"));
    }

    @After public void tearDown() {
        if (fullRes != null) fullRes.close();
        clearProfiles();
        if (camera != null) camera.close();
    }

    private void clearProfiles() {
        context.getSharedPreferences("module_profiles_meta", 0).edit().clear().commit();
        for (String id : FILES) context.getSharedPreferences("module_profile_v2_" + id, 0).edit().clear().commit();
    }

    /**
     * The seeded phone: back 1× (ID 0, the camera's), 3× (ID 4, a camera key, FULLRES 50 / 200 MP), 0.6× (ID 2), a hidden module
     * someone set up (ID 5), four filler slots, the front 1× (ID 1); with {@code copy} a 1.3× copy of 1× (it takes a filler).
     */
    private String seed(boolean copy) {
        SharedPreferences.Editor e = prefs.edit();
        String[][] backs = {{"0", "1×"}, {"4", "3×"}, {"2", "0.6×"}, {"5", "2×"}};
        for (int i = 0; i < backs.length; i++)
            e.putString("module_auto_back" + i, backs[i][0]).putString("module_label_back" + i, backs[i][1]).putBoolean("module_visible_back" + i, i < 3);
        e.putString("module_name_back3", "2× ISZ");
        for (int i = 4; i < 8; i++) e.putString("module_auto_back" + i, "0").putString("module_label_back" + i, "1×").putBoolean("module_visible_back" + i, false);
        e.putString("module_auto_front0", "1").putString("module_label_front0", "1×").putBoolean("module_visible_front0", true);
        e.putString("module_active", "back0");
        VendorTagUtils.TunableKey key = new VendorTagUtils.TunableKey();
        key.type = VendorTagUtils.TunableKey.TYPE_SESSION;
        key.name = "vivo.control.forceSensorMode";
        key.value = "3";
        key.tested = true;
        key.supported = true;
        e.putString("pref_sensorconfig_back1_tunablekeys", new Gson().toJson(Collections.singletonList(key)));
        e.commit();
        return copy ? ModuleRegistry.duplicate("back0", 1.3f) : null;
    }

    private static void idle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private static void idleFor(long ms) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    /** {@code ms} of a paused choreographer's frames, one vsync (16 ms) at a time: one long idleFor skips animation frames. */
    private static void frames(long ms) {
        for (long t = 0; t < ms; t += 16) idleFor(Math.min(16, ms - t));
    }

    /**
     * The page of the camera groups: P390 «Камеры и модули», opened by its row on the main page (P384 had them on the main page);
     * after a recreate the page is back by itself.
     */
    private static SettingsActivity.SettingsFragment root(SettingsActivity activity) {
        SettingsActivity.SettingsFragment f = shown(activity);
        if (!ModuleRows.PAGE.equals(f.requireArguments().getString(androidx.preference.PreferenceFragmentCompat.ARG_PREFERENCE_ROOT))) {
            Preference row = f.findPreference(ModuleRows.PAGE);
            assertNotNull("one row on the main page", row);
            assertNull("P390: the groups are not on the main page", visibleOnPage(f));
            row.performClick();
            f = shown(activity);
        }
        assertEquals(ModuleRows.PAGE, f.requireArguments().getString(androidx.preference.PreferenceFragmentCompat.ARG_PREFERENCE_ROOT));
        return f;
    }

    /** The settings page shown now. */
    private static SettingsActivity.SettingsFragment shown(SettingsActivity activity) {
        FragmentManager fm = activity.getSupportFragmentManager();
        fm.executePendingTransactions();
        idle();
        Fragment f = fm.findFragmentById(R.id.settings_container);
        assertTrue(String.valueOf(f), f instanceof SettingsActivity.SettingsFragment);
        return (SettingsActivity.SettingsFragment) f;
    }

    /** The camera groups' keys the main page's list holds (none since P390), null for none. */
    private static String visibleOnPage(SettingsActivity.SettingsFragment page) {
        List<String> keys = listed(page);
        for (String k : new String[]{ModuleRows.GROUP, ModuleRows.IDS, ModuleRows.SENSOR}) if (keys.contains(k)) return k;
        return null;
    }

    private static PreferenceGroupAdapter adapter(SettingsActivity.SettingsFragment root) {
        return (PreferenceGroupAdapter) root.getListView().getAdapter();
    }

    /** The keys the list holds now, in order. */
    private static List<String> listed(SettingsActivity.SettingsFragment root) {
        PreferenceGroupAdapter a = adapter(root);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.getItemCount(); i++) out.add(a.getItem(i).getKey());
        return out;
    }

    /** The keys the list holds between two keys (exclusive). */
    private static List<String> between(List<String> keys, String from, String to) {
        int a = keys.indexOf(from), b = to == null ? keys.size() : keys.indexOf(to);
        assertTrue(keys.toString(), a >= 0 && b > a);
        return new ArrayList<>(keys.subList(a + 1, b));
    }

    private static ModuleSection card(SettingsActivity.SettingsFragment root, String slot) {
        ModuleSection card = root.findPreference(ModuleRows.CARD + slot);
        assertNotNull(slot, card);
        return card;
    }

    private static void tap(Preference p) {
        p.performClick();
        idle();
    }

    // ───── the groups ─────

    @Test public void cardsInZoomOrderWithoutFillers() {
        String copy = seed(true);
        assertEquals("back4", copy);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity.SettingsFragment root = root(controller.get());
            List<String> keys = listed(root);
            // back by zoom (the hidden module at its place, dimmed), then the front; the untouched fillers are no card
            assertEquals(Arrays.asList("module_card_back2", "module_card_back0", "module_card_back4", "module_card_back3",
                    "module_card_back1", "module_card_front0", "module_actions", "module_row_add_copy", "module_row_per_lens",
                    "module_copy_settings"), between(keys, "root_modules", null));
            for (String filler : new String[]{"back5", "back6", "back7"}) assertNull(filler, root.findPreference(ModuleRows.CARD + filler));
            assertTrue(String.valueOf(root.findPreference(ModuleRows.ADD).getSummary()),
                    String.valueOf(root.findPreference(ModuleRows.ADD).getSummary()).endsWith(" · free places: 3"));
            ModuleSection hidden = card(root, "back3");
            assertEquals("2×", hidden.badge().toString());
            RecyclerView list = root.getListView();
            list.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY));
            list.layout(0, 0, 400, 4000);
            View row = list.findViewHolderForAdapterPosition(adapter(root).getPreferenceAdapterPosition(hidden)).itemView;
            assertEquals("a hidden module's badge is dimmed", .45f, row.findViewById(R.id.settings_module_badge).getAlpha(), 1e-3);
            View shown = list.findViewHolderForAdapterPosition(adapter(root).getPreferenceAdapterPosition(card(root, "back1"))).itemView;
            assertEquals(1f, shown.findViewById(R.id.settings_module_badge).getAlpha(), 1e-3);
            // P413: each card holds its module's Camera ID (no list of their own) and its lens page; the page shows no more groups
            for (String slot : new String[]{"back2", "back0", "back4", "back3", "back1", "front0"}) {
                assertNotNull(slot, card(root, slot).findPreference(ModuleRows.CARD_ID + slot));
                assertNotNull(slot, card(root, slot).findPreference(ModuleRows.LENS_ROW + slot));
            }
            assertNull(root.findPreference("expert_module_ids"));
            assertFalse("the lens page is no row of the page", keys.contains(ModuleRows.LENS));
        }
    }

    @Test public void kindAndSummaryTexts() {
        kindAndSummaryTexts(new String[]{"Ultra-wide", "Main camera", "Copy of “1×”", "2× ISZ", "Telephoto", "Front camera"},
                "In the camera now · ID 0", "crop 1.3× · ID 0", "hidden in the viewfinder · ID 5", "Front camera");
    }

    @Test @Config(qualifiers = "ru-w400dp-h880dp-mdpi") public void kindAndSummaryTextsInRussian() {
        kindAndSummaryTexts(new String[]{"Сверхширик", "Основная камера", "Копия «1×»", "2× ISZ", "Телевик", "Фронтальная камера"},
                "Сейчас в камере · ID 0", "кроп 1.3× · ID 0", "скрыта в видоискателе · ID 5", "Фронтальная камера");
    }

    private void kindAndSummaryTexts(String[] titles, String active, String copied, String hidden, String front) {
        String copy = seed(true);
        String[] order = {"back2", "back0", copy, "back3", "back1", "front0"};
        for (int i = 0; i < order.length; i++) assertEquals(order[i], titles[i], ModuleRows.cardTitle(context, order[i]));
        assertEquals(active, ModuleRows.cardSummary(context, "back0"));
        assertEquals(copied, ModuleRows.cardSummary(context, copy));
        assertEquals(hidden, ModuleRows.cardSummary(context, "back3"));
        assertEquals("ID 1", ModuleRows.cardSummary(context, "front0"));
        assertEquals("1.3×", ModuleRows.badge(copy));
        assertEquals("0.6×", ModuleRows.badge("back2"));
        assertEquals(front, ModuleRows.kind(context, "front0"));
        // a name of its own is the title; a sensor-cropped module says so
        prefs.edit().putString("module_name_back0", "Wide").putBoolean("module_sensorcrop_back1", true).commit();
        assertEquals("Wide", ModuleRows.cardTitle(context, "back0"));
        assertTrue(ModuleRows.cardSummary(context, "back1"), ModuleRows.cardSummary(context, "back1").startsWith(
                context.getString(R.string.module_state_sensor_crop) + " · "));
    }

    @Test public void accordionOpensOneCard() {
        seed(true);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity.SettingsFragment root = root(controller.get());
            assertFalse(listed(root).contains("module_row_name_back0"));
            tap(card(root, "back0"));
            List<String> keys = listed(root);
            assertEquals(Arrays.asList("module_row_name_back0", "module_row_zoom_back0", "module_row_card_id_back0", "module_row_button_back0",
                    "module_row_copy_back0", "module_row_lens_back0", "module_row_profile_back0"), between(keys, "module_card_back0", "module_card_back4"));
            tap(card(root, "back1"));
            keys = listed(root);
            assertFalse("one card open at a time", keys.contains("module_row_name_back0"));
            assertTrue(keys.contains("module_row_name_back1"));
            assertFalse(card(root, "back0").isExpanded());
            assertEquals("back1", root.requireArguments().getString(ModuleRows.ARG_OPEN_MODULE));
            // a recreate keeps the open card
            controller.recreate();
            root = root(controller.get());
            assertTrue(card(root, "back1").isExpanded());
            assertTrue(listed(root).contains("module_row_profile_back1@full200"));
            tap(card(root, "back1"));
            assertFalse(listed(root).contains("module_row_name_back1"));
            assertEquals("", root.requireArguments().getString(ModuleRows.ARG_OPEN_MODULE));
        }
    }

    @Test public void rowsWriteTheSameKeys() {
        seed(false);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity.SettingsFragment root = root(controller.get());
            tap(card(root, "back0"));
            List<String> before = between(listed(root), "root_modules", null);
            // the viewfinder button: module_visible_
            SwitchPreferenceCompat button = root.findPreference("module_row_button_back0");
            assertTrue("starts from the module's state, not off", button.isChecked());
            assertFalse(button.isPersistent());
            tap(button);
            assertFalse(prefs.getBoolean("module_visible_back0", true));
            assertTrue(ModuleRows.cardSummary(context, "back0"), String.valueOf(card(root, "back0").getSummary()).contains("hidden in the viewfinder"));
            assertEquals("the card stays where it was", before, between(listed(root), "root_modules", null));
            tap(button);
            assertTrue(prefs.getBoolean("module_visible_back0", false));
            // the name: module_name_
            tap(root.findPreference("module_row_name_back0"));
            androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) ShadowDialog.getLatestDialog();
            firstEdit(d).setText("Wide");
            d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
            idle();
            assertEquals("Wide", prefs.getString("module_name_back0", ""));
            assertEquals("Wide", String.valueOf(((ModuleRowPreference) root.findPreference("module_row_name_back0")).value()));
            assertEquals("Wide", String.valueOf(card(root, "back0").getTitle()));
            // the zoom: module_zoom_ / module_sensorcrop_
            tap(root.findPreference("module_row_zoom_back0"));
            d = (androidx.appcompat.app.AlertDialog) ShadowDialog.getLatestDialog();
            firstEdit(d).setText("1.2");
            d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
            idle();
            assertEquals("1.2", prefs.getString("module_zoom_back0", ""));
            assertFalse(prefs.getBoolean("module_sensorcrop_back0", true));
            assertEquals("1.2×", String.valueOf(((ModuleRowPreference) root.findPreference("module_row_zoom_back0")).value()));
            assertEquals("1.2×", card(root, "back0").badge().toString());
        }
    }

    private static android.widget.EditText firstEdit(androidx.appcompat.app.AlertDialog d) {
        java.util.ArrayDeque<View> q = new java.util.ArrayDeque<>();
        q.add(d.getWindow().getDecorView());
        while (!q.isEmpty()) {
            View v = q.poll();
            if (v instanceof android.widget.EditText) return (android.widget.EditText) v;
            if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) q.add(((android.view.ViewGroup) v).getChildAt(i));
        }
        fail("no field");
        return null;
    }

    @Test public void idStates() {
        String copy = seed(true);
        assertEquals("Auto", ModuleRows.idState(context, "back0"));
        assertEquals("Same as “1×”", ModuleRows.idState(context, copy));
        prefs.edit().putString("module_id_back1", "7").commit();
        assertEquals("Chosen by you", ModuleRows.idState(context, "back1"));
        prefs.edit().putString("module_id_" + copy, "4").commit();
        assertEquals("a copy given another camera", "Chosen by you", ModuleRows.idState(context, copy));
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity.SettingsFragment root = root(controller.get());
            // P413: the Camera ID row of the module's card
            ModuleRowPreference row = root.findPreference("module_row_card_id_back1");
            assertEquals("ID 7", String.valueOf(row.value()));
            assertEquals("Camera ID", String.valueOf(row.getTitle()));
            assertEquals("Chosen by you", String.valueOf(row.getSummary()));
            assertEquals("ID 2", String.valueOf(((ModuleRowPreference) root.findPreference("module_row_card_id_back2")).value()));
        }
    }

    @Test public void searchFindsFoldedRows() {
        seed(true);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = root(activity);
            List<SettingsSearchFragment.Entry> index = SettingsSearchFragment.index(root.getPreferenceScreen());
            Map<String, SettingsSearchFragment.Entry> byKey = new HashMap<>();
            for (SettingsSearchFragment.Entry e : index) byKey.put(e.key, e);
            for (String key : new String[]{"module_card_back1", "module_row_name_back1", "module_row_zoom_back4", "module_row_crop_back4",
                    "module_row_profile_back1@full200", "module_row_card_id_back3", "pref_sensorconfig_back2_blackleveloverride",
                    "pref_sensorconfig_back1_add_tunablekey"})
                assertTrue("indexed: " + key, byKey.containsKey(key));
            assertTrue(byKey.get("module_row_name_back1").path, byKey.get("module_row_name_back1").path.contains("Telephoto"));
            // a hit on a folded card's row opens the card; on a module's sensor row, chooses the module
            activity.openSearchResult(byKey.get("module_row_profile_back1@full200"));
            SettingsActivity.SettingsFragment page = root(activity);
            assertTrue(card(page, "back1").isExpanded());
            assertTrue(listed(page).contains("module_row_profile_back1@full200"));
            activity.getOnBackPressedDispatcher().onBackPressed();
            // P413: a module's sensor row opens that module's lens page
            activity.openSearchResult(byKey.get("pref_sensorconfig_back2_blackleveloverride"));
            page = shown(activity);
            assertEquals(ModuleRows.LENS, page.requireArguments().getString(androidx.preference.PreferenceFragmentCompat.ARG_PREFERENCE_ROOT));
            assertEquals("back2", page.moduleProfile());
            assertEquals("back2", page.requireArguments().getString(SettingsActivity.SettingsFragment.ARG_SENSOR_SLOT));
            assertTrue(listed(page).contains("pref_sensorconfig_back2_blackleveloverride"));
            assertFalse(listed(page).contains(ModuleRows.PICKER));
        }
    }

    @Test public void sectionsUseTheDiff() {
        seed(false);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = root(activity);
            assertNotNull("the root animates its difference", root.getPreferenceManager().getPreferenceComparisonCallback());
            RecyclerView list = root.getListView();
            assertTrue(list.getItemAnimator() instanceof SettingsMotion.ItemAnimator);
            assertFalse(((SettingsMotion.ItemAnimator) list.getItemAnimator()).getSupportsChangeAnimations());
            final List<String> events = new ArrayList<>();
            adapter(root).registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override public void onChanged() { events.add("changed"); }
                @Override public void onItemRangeInserted(int start, int count) { events.add("inserted " + start + " " + count); }
                @Override public void onItemRangeRemoved(int start, int count) { events.add("removed " + start + " " + count); }
            });
            int header = adapter(root).getPreferenceAdapterPosition(card(root, "back0"));
            tap(card(root, "back0"));
            assertTrue(events.toString(), events.contains("inserted " + (header + 1) + " 7"));
            assertFalse(events.toString(), events.contains("changed"));
            events.clear();
            tap(card(root, "back0"));
            assertTrue(events.toString(), events.contains("removed " + (header + 1) + " 7"));
            assertFalse(events.toString(), events.contains("changed"));
            // an inner page keeps the plain rebind
            activity.getOnBackPressedDispatcher().onBackPressed();
            SettingsActivity.SettingsFragment main = shown(activity);
            activity.onPreferenceStartScreen(main, main.findPreference("photo_style_screen"));
            SettingsActivity.SettingsFragment inner = shown(activity);
            assertEquals("photo_style_screen", inner.requireArguments().getString(androidx.preference.PreferenceFragmentCompat.ARG_PREFERENCE_ROOT));
            assertNull(inner.getPreferenceManager().getPreferenceComparisonCallback());
            assertFalse(inner.getListView().getItemAnimator() instanceof SettingsMotion.ItemAnimator);
        }
    }

    @Test public void noValueChanges() {
        seed(true);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = root(activity);
            // the first open writes the sensor rows' defaults of the modules (as the sensor page did) - never a filler's
            for (String filler : new String[]{"back5", "back6", "back7"}) assertTrue(filler + " stays free", ModuleRegistry.freeSlot(filler));
            Map<String, ?> before = new HashMap<>(prefs.getAll());
            for (ModuleSection c : new ModuleSection[]{card(root, "back2"), card(root, "back0"), card(root, "back4"), card(root, "back3"),
                    card(root, "back1"), card(root, "front0")}) {
                tap(c);
                tap(c);
            }
            // P413: each module's lens page opened and closed
            for (String slot : new String[]{"back2", "back0", "back4", "back3", "back1", "front0"}) {
                controller.get().getSupportFragmentManager().beginTransaction()
                        .replace(R.id.settings_container, SettingsActivity.SettingsFragment.sensorPage(slot)).addToBackStack("lens").commit();
                shown(controller.get());
                controller.get().getOnBackPressedDispatcher().onBackPressed();
                idle();
            }
            controller.recreate();
            root = root(controller.get());
            tap(root.findPreference("module_row_card_id_back1"));
            android.app.Dialog sheet = ShadowDialog.getLatestDialog();
            assertNotNull(sheet.findViewById(android.R.id.content).findViewWithTag("camera_id_sheet"));
            sheet.cancel();
            idle();
            assertEquals(before, new HashMap<>(prefs.getAll()));
        }
    }

    @Test public void chipDotForCustomisedModules() {
        Map<String, Object> all = new HashMap<>();
        assertFalse(ModuleSensorSettings.customised(all, "back1"));
        // the defaults a first open writes are no values of the module's own
        all.put("pref_sensorconfig_back1_sessiontype", "0");
        all.put("pref_sensorconfig_back1_oismode", "0");
        all.put("pref_sensorconfig_back1_blackleveloverride", "-1.0");
        all.put("pref_sensorconfig_back1_tunablekeys", "[]");
        all.put(SensorModePick.prefKey("back1"), "-1");
        assertFalse(ModuleSensorSettings.customised(all, "back1"));
        all.put("pref_sensorconfig_back1_tunablekeys", "[{\"name\":\"vivo.x\",\"enabled\":false}]");
        assertFalse("a switched-off key", ModuleSensorSettings.customised(all, "back1"));
        all.put("pref_sensorconfig_back1_tunablekeys", "[{\"name\":\"vivo.x\",\"enabled\":true}]");
        assertTrue(ModuleSensorSettings.customised(all, "back1"));
        all.put("pref_sensorconfig_back1_tunablekeys", "[]");
        all.put(SensorModePick.prefKey("back1"), "4");
        assertTrue("a picked sensor mode row", ModuleSensorSettings.customised(all, "back1"));
        all.put(SensorModePick.prefKey("back1"), "-1");
        all.put("pref_sensorconfig_back1_blackleveloverride", "64");
        assertTrue(ModuleSensorSettings.customised(all, "back1"));
        assertFalse("another module's values", ModuleSensorSettings.customised(all, "back0"));
        // P413: no module chips on «Камеры и модули» - each module's sensor rows head its own lens page
        seed(false);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity.SettingsFragment root = root(controller.get());
            assertFalse(listed(root).contains(ModuleRows.PICKER));
        }
    }

    /** The view the list shows for {@code p}: the list scrolled to it first. */
    private static View bound(SettingsActivity.SettingsFragment root, Preference p) {
        RecyclerView list = root.getListView();
        int pos = adapter(root).getPreferenceAdapterPosition(p);
        assertTrue(p.getKey(), pos >= 0);
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(pos, 0);
        idle();
        RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(pos);
        assertNotNull(p.getKey(), h);
        return h.itemView;
    }

    private static String text(View row, int id) {
        TextView t = row.findViewById(id);
        return t == null || t.getVisibility() != View.VISIBLE || t.getText() == null ? "" : t.getText().toString();
    }

    /**
     * The bound rows: the sensor mode row's picked row is its value on the right, not the summary's first line too; a camera key's
     * shown name breaks after its dots while the key's own title stays the name; deleting a copy is in the warning colour.
     */
    @Test public void boundRowsShowTheirTexts() {
        String copy = seed(true);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            // P413: the module's sensor rows on its lens page
            controller.get().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, SettingsActivity.SettingsFragment.sensorPage("back1")).addToBackStack("lens").commit();
            SettingsActivity.SettingsFragment lens = shown(controller.get());
            // Robolectric has no sensor mode table: the generator's row, with two rows of a table, in the module's category
            androidx.preference.PreferenceCategory category = lens.findPreference(SensorConfigPreferenceGenerator.CATEGORY_PREFIX + "back1");
            assertNotNull(category);
            androidx.preference.ListPreference mode = SensorConfigPreferenceGenerator.sensorModeRow(context, "back1",
                    Arrays.asList(new int[]{3, 4096, 3072, 30}, new int[]{4, 8192, 6144, 15}));
            SettingsStyle.style(mode, category);
            category.addPreference(mode);
            mode.setValue("4");
            idle();
            View row = bound(lens, mode);
            String value = text(row, R.id.settings_value) + text(row, R.id.settings_value_below);
            assertEquals("Mode #4: 8192x6144 @ 15fps", value);
            String summary = text(row, android.R.id.summary);
            assertFalse(summary, summary.startsWith(value));
            assertTrue(summary, summary.startsWith(context.getString(R.string.module_sensor_mode_hint)));
            assertEquals("Sensor mode", text(row, android.R.id.title));
            assertEquals("restarts the camera", View.VISIBLE, row.findViewById(R.id.settings_restart).getVisibility());
            // the camera key of 3×
            com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableKeyPreference key = null;
            for (int i = 0; i < adapter(lens).getItemCount(); i++)
                if (adapter(lens).getItem(i) instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableKeyPreference)
                    key = (com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableKeyPreference) adapter(lens).getItem(i);
            assertNotNull(key);
            assertEquals("vivo.control.forceSensorMode", key.getTitle().toString());
            assertEquals("vivo.​control.​forceSensorMode", text(bound(lens, key), android.R.id.title));
            controller.get().getOnBackPressedDispatcher().onBackPressed();
            SettingsActivity.SettingsFragment root = root(controller.get());
            // deleting the copy
            tap(card(root, copy));
            Preference delete = root.findPreference(ModuleRows.DELETE + copy);
            assertNotNull(delete);
            row = bound(root, delete);
            assertEquals(SettingsStyle.WARN, ((TextView) row.findViewById(android.R.id.title)).getCurrentTextColor());
            assertNotNull("its icon too", ((android.widget.ImageView) row.findViewById(android.R.id.icon)).getColorFilter());
            // and a row beside it that is no warning keeps the text colour (the views are recycled)
            row = bound(root, root.findPreference(ModuleRows.NAME + copy));
            assertEquals(SettingsStyle.TEXT, ((TextView) row.findViewById(android.R.id.title)).getCurrentTextColor());
        }
    }

    // ───── renders ─────

    /** {@code view} measured to {@code w} x {@code h} pixels, drawn, written to build/reports/module-concept/{name}.png. */
    static void shot(View view, int w, int h, String name) throws Exception {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        write(view, w, h, name);
    }

    private static void write(View view, int w, int h, String name) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(SettingsStyle.BG);
        view.draw(new Canvas(bitmap));
        File dir = new File("build/reports/module-concept");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    /** A bottom sheet as it is drawn: its own card at its natural height (not the window around it). */
    private static void shotSheet(android.app.Dialog sheet, String name) throws Exception {
        idleFor(1000);
        View host = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        assertNotNull(host);
        int w = host.getWidth();
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = host.getMeasuredHeight();
        host.layout(0, 0, w, h);
        write(host, w, h, name);
    }

    /** The page with {@code key}'s row at its top (the list scrolled there), at the window's size. */
    private static void shotAt(SettingsActivity.SettingsFragment page, String key, String name) throws Exception {
        RecyclerView list = page.getListView();
        Preference p = page.findPreference(key);
        assertNotNull(key, p);
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(adapter(page).getPreferenceAdapterPosition(p), 0);
        idleFor(1000);
        View view = page.requireView();
        write(view, view.getWidth(), view.getHeight(), name);
    }

    /** The whole page: its list as tall as its rows. */
    private static void shotWhole(SettingsActivity.SettingsFragment page, String name) throws Exception {
        View view = page.requireView();
        int w = view.getWidth(), h = view.getHeight();
        RecyclerView list = page.getListView();
        list.scrollToPosition(0);
        idleFor(1000);
        int rows = 0;
        for (int tall = h; tall <= 24000; tall += 2000) {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, w, tall);
            View last = list.getChildAt(list.getChildCount() - 1);
            rows = list.getChildCount();
            if (last != null && list.getChildAdapterPosition(last) == adapter(page).getItemCount() - 1 && last.getBottom() < list.getHeight()) {
                int bottom = list.getTop() + last.getBottom() + 40;
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(bottom, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, w, bottom);
                write(view, w, bottom, name);
                return;
            }
        }
        fail("the page did not fit: " + rows + " rows");
    }

    /** P384 renders (xhdpi, Russian): the main page's camera groups, a card open, a copy's card, the Camera ID rows and sheet, the sensor chips. */
    @Test @Config(qualifiers = "ru-w400dp-h880dp-xhdpi") public void rendersForReview() throws Exception {
        seed(true);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = root(activity);
            idleFor(1000);
            shotWhole(root, "p384-root-ru");
            shotAt(root, "root_modules", "p384-modules-ru");
            tap(card(root, "back0"));
            shotAt(root, "root_modules", "p384-modules-open-ru");
            tap(card(root, "back4"));
            shotAt(root, "module_card_back4", "p384-copy-card-ru");
            tap(card(root, "back4"));
            // P413: a module's lens page (the reference app's sections)
            activity.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, SettingsActivity.SettingsFragment.sensorPage("back1")).addToBackStack("lens").commit();
            SettingsActivity.SettingsFragment lens = shown(activity);
            idleFor(1000);
            shotWhole(lens, "p413-lens-back1-ru");
            shotAt(lens, "pref_sensorconfig_back1_ispsurface", "p413-lens-back1-keys-ru");
            shotAt(lens, "lens_colour_category", "p413-lens-back1-colour-ru");
            activity.getOnBackPressedDispatcher().onBackPressed();
            root = root(activity);
            prefs.edit().putString("module_id_back1", "7").commit();
            controller.pause().resume();
            idle();
            tap(root.findPreference("module_row_card_id_back0"));
            android.app.Dialog sheet = ShadowDialog.getLatestDialog();
            shotSheet(sheet, "p384-id-sheet-ru");
            View body = sheet.findViewById(android.R.id.content);
            body.findViewWithTag("camera_id_manual").performClick();
            ((android.widget.EditText) body.findViewWithTag("camera_id_field")).setText("7");
            shotSheet(sheet, "p384-id-sheet-manual-unknown-ru");
            ((android.widget.EditText) body.findViewWithTag("camera_id_field")).setText("3a!");
            shotSheet(sheet, "p384-id-sheet-manual-invalid-ru");
            sheet.cancel();
            idle();
            tap(root.findPreference("module_row_card_id_back4"));
            shotSheet(ShadowDialog.getLatestDialog(), "p384-id-sheet-copy-ru");
            ShadowDialog.getLatestDialog().cancel();
            idle();
        }
    }

    /** The main page in English: the same groups and texts. */
    @Test @Config(qualifiers = "w400dp-h880dp-xhdpi") public void renderEnglish() throws Exception {
        seed(true);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity.SettingsFragment root = root(controller.get());
            idleFor(1000);
            shotWhole(root, "p384-root-en");
            tap(card(root, "back1"));
            shotAt(root, "module_card_back1", "p384-modules-open-en");
        }
    }

    /**
     * The motion as frames (a card opening): the rows below glide, the new rows rise in, the chevron turns (P413: no module chips
     * on the page any more).
     */
    @Test @Config(qualifiers = "ru-w400dp-h880dp-xhdpi") public void renderMotionFrames() throws Exception {
        seed(true);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity.SettingsFragment root = root(controller.get());
            RecyclerView list = root.getListView();
            ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(adapter(root).getPreferenceAdapterPosition(root.findPreference("root_modules")), 0);
            idleFor(1000);
            View view = root.requireView();
            // frames only on the clock: an unpaused Robolectric choreographer runs every animation to its end within one idle()
            ShadowChoreographer.setPaused(true);
            card(root, "back0").performClick();
            long at = 0;
            for (long frame : new long[]{50, 120, 240, 600}) {
                frames(frame - at);
                at = frame;
                if (frame == 120) {
                    assertTrue("the card opens in motion", list.getItemAnimator().isRunning());
                    boolean between = false;
                    for (int i = 0; i < list.getChildCount(); i++) {
                        View row = list.getChildAt(i);
                        between |= row.getTranslationY() != 0 || (row.getAlpha() > 0 && row.getAlpha() < SettingsStyle.restingAlpha(
                                (Preference) row.getTag(R.id.settings_bound_preference)));
                    }
                    assertTrue("rows between their old and new places", between);
                }
                write(view, view.getWidth(), view.getHeight(), "p384-expand-" + frame + "ms");
            }
            assertFalse("the motion ends", list.getItemAnimator().isRunning());
            for (int i = 0; i < list.getChildCount(); i++) {
                View row = list.getChildAt(i);
                assertEquals(0f, row.getTranslationY(), 0f);
                assertEquals(SettingsStyle.restingAlpha((Preference) row.getTag(R.id.settings_bound_preference)), row.getAlpha(), 1e-3f);
            }
        }
    }

    /** A module profile's root (its own sensor only) and the settings copy (no group of the module cards). */
    @Test @Config(qualifiers = "ru-w400dp-h880dp-xhdpi") public void renderProfileRootAndCopy() throws Exception {
        seed(true);
        prefs.edit().putBoolean("pref_save_per_lens_settings", true).commit();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = root(activity);
            tap(root.findPreference("module_row_profile_back1"));
            SettingsActivity.SettingsFragment page = shown(activity);
            assertEquals("back1", page.moduleProfile());
            shotAt(page, "root_colour", "p384-profile-root-ru");
            activity.getOnBackPressedDispatcher().onBackPressed();
            root = root(activity);
            tap(root.findPreference("module_copy_settings"));
            idleFor(1000);
            Fragment copy = activity.getSupportFragmentManager().findFragmentById(R.id.settings_container);
            assertTrue(String.valueOf(copy), copy instanceof ModuleCopyFragment);
            View view = copy.requireView();
            write(view, view.getWidth(), view.getHeight(), "p384-copy-catalog-ru");
        }
    }
}
