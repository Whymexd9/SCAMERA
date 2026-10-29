package com.particlesdevs.photoncamera.ui.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;
import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsLayout;
import com.particlesdevs.photoncamera.settings.SettingsManager;

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
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P385: the user's order on the settings pages - the stored order on the main page and an inner one, «Расположение» (its two views,
 * a move saved, a group's row into another group, the closed groups, the reset), a group left empty, a moved row's dependency.
 * Renders for a look: build/reports/module-concept/p385-*.png.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class SettingsLayoutPageTest {
    private Context context;
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;

    @Before public void setUp() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
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
        SharedPreferences.Editor e = prefs.edit();
        e.putString("module_auto_back0", "0").putString("module_label_back0", "1×").putBoolean("module_visible_back0", true);
        e.putString("module_auto_back1", "2").putString("module_label_back1", "0.6×").putBoolean("module_visible_back1", true);
        e.putString("module_auto_front0", "1").putString("module_label_front0", "1×").putBoolean("module_visible_front0", true);
        e.putString("module_active", "back0").commit();
    }

    @After public void tearDown() {
        if (camera != null) camera.close();
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

    private static SettingsActivity.SettingsFragment page(SettingsActivity activity) {
        Fragment f = top(activity);
        assertTrue(String.valueOf(f), f instanceof SettingsActivity.SettingsFragment);
        return (SettingsActivity.SettingsFragment) f;
    }

    private static SettingsLayoutFragment editor(SettingsActivity activity) {
        Fragment f = top(activity);
        assertTrue(String.valueOf(f), f instanceof SettingsLayoutFragment);
        return (SettingsLayoutFragment) f;
    }

    /** The keys the page's list holds, in order. */
    private static List<String> listed(SettingsActivity.SettingsFragment page) {
        PreferenceGroupAdapter a = (PreferenceGroupAdapter) page.getListView().getAdapter();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.getItemCount(); i++) out.add(a.getItem(i).getKey());
        return out;
    }

    /** The rows the list shows under {@code group} (up to the next root group). */
    private static List<String> section(List<String> keys, String group) {
        List<String> roots = Arrays.asList("root_lenses", "root_photo", "root_shoot", "root_colour", "root_lab", "root_logs", "root_about");
        int a = keys.indexOf(group);
        assertTrue(group + " in " + keys, a >= 0);
        int b = a + 1;
        while (b < keys.size() && !roots.contains(keys.get(b))) b++;
        return new ArrayList<>(keys.subList(a + 1, b));
    }

    private static <T extends View> T find(View v, String tag) {
        View found = v.findViewWithTag(tag);
        assertNotNull(tag, found);
        @SuppressWarnings("unchecked") T t = (T) found;
        return t;
    }

    private static TextView text(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return (TextView) v;
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                TextView t = text(((ViewGroup) v).getChildAt(i), text);
                if (t != null) return t;
            }
        return null;
    }

    private static RecyclerView laidOut(SettingsLayoutFragment f) {
        RecyclerView list = find(f.requireView(), "settings_layout_list");
        list.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(20000, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 400, 20000);
        return list;
    }

    /** The accessibility action {@code label} on the card {@code card}; whether it moved. */
    private static boolean act(SettingsLayoutFragment f, String card, String label) {
        int at = f.cards().indexOf(card);
        assertTrue(card + " in " + f.cards(), at >= 0);
        View v = laidOut(f).findViewHolderForAdapterPosition(at).itemView;
        AccessibilityNodeInfo info = v.createAccessibilityNodeInfo();
        for (AccessibilityNodeInfo.AccessibilityAction a : info.getActionList())
            if (a.getLabel() != null && label.contentEquals(a.getLabel())) {
                boolean done = v.performAccessibilityAction(a.getId(), null);
                idle();
                return done;
            }
        fail("no action " + label + " on " + card);
        return false;
    }

    private SettingsLayoutFragment openEditor(SettingsActivity activity) {
        page(activity).onPreferenceTreeClick(page(activity).findPreference("settings_layout_editor"));
        return editor(activity);
    }

    private static void back(SettingsActivity activity) {
        activity.getOnBackPressedDispatcher().onBackPressed();
        idle();
    }

    private String stored() {
        return prefs.getString(SettingsLayout.KEY, "");
    }

    @Test public void theMainPageTakesTheStoredOrder() {
        // P406: the main page's groups are the reference app's
        prefs.edit().putString(SettingsLayout.KEY, "prefscreen=root_colour,root_lenses;root_colour=pref_antibanding_hz_key").commit();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            List<String> keys = listed(page(controller.get()));
            assertTrue(keys.toString(), keys.indexOf("root_colour") < keys.indexOf("root_lenses"));
            // the groups the order does not name follow the one they follow in the app's order
            assertTrue(keys.toString(), keys.indexOf("root_lenses") < keys.indexOf("root_photo"));
            assertTrue(keys.toString(), keys.indexOf("root_lab") < keys.indexOf("root_about"));
            assertTrue(section(keys, "root_colour").toString(), section(keys, "root_colour").contains("pref_antibanding_hz_key"));
            assertFalse(section(keys, "root_shoot").contains("pref_antibanding_hz_key"));
            assertNotNull("the editor's row", page(controller.get()).findPreference("settings_layout_editor"));
            assertNotNull("the header's button", page(controller.get()).requireView().findViewWithTag("settings_page_arrange"));
        }
    }

    @Test public void theEditorMovesAGroupAndARowAndSavesAtOnce() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            List<String> before = listed(page(activity));
            SettingsLayoutFragment f = openEditor(activity);
            // «Группы»: the page's groups as cards
            assertEquals("group:root_lenses", f.cards().get(0));
            assertFalse("P390: the modules are a row of their group", f.cards().contains("group:root_modules"));
            assertTrue(act(f, "group:root_shoot", "Move up"));
            assertTrue(stored(), stored().startsWith("prefscreen=root_lenses,root_shoot,root_photo,root_colour,"));
            // «Пункты»: a group's first row goes up into the group above (both share rows)
            text(f.requireView(), "Rows").performClick();
            idle();
            assertTrue(f.cards().toString(), f.cards().contains("header:root_photo"));
            assertEquals("header:root_lenses", f.cards().get(0));
            // («Сохранение и форматы фото» starts with «Формат файла»; «Основные настройки» is above it now)
            assertTrue(act(f, "row:output_settings_screen", "Move up"));
            assertTrue(stored(), SettingsLayout.parse(stored()).get("root_shoot").contains("output_settings_screen"));
            assertFalse(stored(), SettingsLayout.parse(stored()).get("root_photo").contains("output_settings_screen"));
            int at = f.cards().indexOf("row:output_settings_screen");
            assertEquals("header:root_photo", f.cards().get(at + 1));
            // «Логи» keeps its rows (a module page hides it whole)
            String now = stored();
            List<String> cards = f.cards();
            String first = null;
            for (int i = cards.indexOf("header:root_logs") + 1; i < cards.size() && first == null; i++)
                if (cards.get(i).startsWith("row:")) first = cards.get(i);
            assertNotNull(cards.toString(), first);
            assertFalse(act(f, first, "Move up"));
            assertEquals(now, stored());
            // back on the page: the new order, the rest as it was
            back(activity);
            List<String> keys = listed(page(activity));
            assertTrue(keys.toString(), keys.indexOf("root_shoot") < keys.indexOf("root_photo"));
            assertTrue(section(keys, "root_shoot").toString(), section(keys, "root_shoot").contains("output_settings_screen"));
            assertEquals(before.size(), keys.size());
        }
    }

    @Test public void theResetPutsThePageBack() {
        prefs.edit().putString(SettingsLayout.KEY, "prefscreen=root_about,root_lenses,root_photo,root_shoot,root_modules,root_colour,"
                + "root_lab,root_logs;photo_style_screen=x").commit();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsLayoutFragment f = openEditor(activity);
            assertEquals("group:root_about", f.cards().get(0));
            find(f.requireView(), "settings_page_reset").performClick();
            idle();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            // another page's line stays
            assertEquals("photo_style_screen=x", stored());
            assertEquals("group:root_lenses", f.cards().get(0));
            back(activity);
            List<String> keys = listed(page(activity));
            assertTrue(keys.toString(), keys.indexOf("root_lenses") < keys.indexOf("root_colour"));
            assertTrue(keys.toString(), keys.indexOf("root_about") > keys.indexOf("root_logs"));
        }
    }

    @Test public void aGroupLeftWithoutRowsIsHiddenAndTakesThemBack() {
        List<String> view = new ArrayList<>(), shoot = new ArrayList<>();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity.SettingsFragment root = page(controller.get());
            PreferenceGroup v = root.findPreference("root_photo"), s = root.findPreference("root_lenses");
            for (int i = 0; i < v.getPreferenceCount(); i++) view.add(v.getPreference(i).getKey());
            for (int i = 0; i < s.getPreferenceCount(); i++) shoot.add(s.getPreference(i).getKey());
        }
        List<String> all = new ArrayList<>(shoot);
        all.addAll(view);
        prefs.edit().putString(SettingsLayout.KEY, "root_lenses=" + String.join(",", all) + ";root_photo=").commit();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            List<String> keys = listed(page(activity));
            assertFalse(keys.toString(), keys.contains("root_photo"));
            for (String k : view) if (keys.contains(k)) assertTrue(k, section(keys, "root_lenses").contains(k));
            SettingsLayoutFragment f = openEditor(activity);
            text(f.requireView(), "Rows").performClick();
            idle();
            int header = f.cards().indexOf("header:root_photo");
            assertTrue(f.cards().toString(), header >= 0);
            assertEquals("empty:root_photo", f.cards().get(header + 1));
            prefs.edit().remove(SettingsLayout.KEY).commit();
            back(activity);
            keys = listed(page(activity));
            assertTrue(keys.toString(), keys.contains("root_photo"));
            assertTrue(section(keys, "root_photo").toString(), section(keys, "root_photo").contains(view.get(0)));
        }
    }

    @Test public void anInnerPageHasItsOwnOrderAndAPageWithOneRowNoButton() {
        prefs.edit().putString(SettingsLayout.KEY, "photo_processing_screen=colour_grading_screen,colour_wb_category,"
                + "colour_sat_category,colour_skin_category,colour_mixer_screen").commit();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = page(activity);
            activity.onPreferenceStartScreen(root, root.findPreference("photo_processing_screen"));
            SettingsActivity.SettingsFragment inner = page(activity);
            List<String> keys = listed(inner);
            assertEquals("colour_grading_screen", keys.get(0));
            assertNotNull(inner.requireView().findViewWithTag("settings_page_arrange"));
            back(activity);
            root = page(activity);
            activity.onPreferenceStartScreen(root, root.findPreference("shade_transparency_screen"));
            assertNull("nothing to arrange", page(activity).requireView().findViewWithTag("settings_page_arrange"));
        }
    }

    @Test public void aMovedRowStillFollowsItsSwitch() {
        PreferenceManager pm = new PreferenceManager(context);
        PreferenceScreen screen = pm.inflateFromResource(context, R.xml.preferences, null);
        pm.setPreferences(screen);
        screen.onAttached();
        PreferenceGroup curves = screen.findPreference("scam_hybrid_why_curves_screen");
        SettingsLayoutTree.Snapshot s = SettingsLayoutTree.snapshot(curves);
        assertNotNull(s);
        // the dependent row and its switch each to another group
        SettingsLayoutTree.apply(s, "scam_hybrid_why_uchi_category=pref_scam_hybrid_why_aces_pre_gain;"
                + "scam_hybrid_why_curve_category=pref_scam_hybrid_why_aces_on");
        Preference gain = screen.findPreference("pref_scam_hybrid_why_aces_pre_gain");
        TwoStatePreference on = screen.findPreference("pref_scam_hybrid_why_aces_on");
        assertEquals("scam_hybrid_why_uchi_category", gain.getParent().getKey());
        assertEquals("scam_hybrid_why_curve_category", on.getParent().getKey());
        // a partial line: the group's own rows keep their places, the newcomer after them
        assertTrue(gain.getOrder() > screen.findPreference("pref_scam_hybrid_why_uchi_on").getOrder());
        on.setChecked(true);
        assertTrue(gain.isEnabled());
        on.setChecked(false);
        assertFalse(gain.isEnabled());
        // and home again
        SettingsLayoutTree.apply(s, "");
        assertEquals("scam_hybrid_why_aces_category", gain.getParent().getKey());
        assertEquals("scam_hybrid_why_aces_category", on.getParent().getKey());
        on.setChecked(true);
        assertTrue(gain.isEnabled());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void renders() throws Exception {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            ModuleRowsTest.shot(page(activity).requireView(), 400, 760, "p385-main-header");
            SettingsLayoutFragment f = openEditor(activity);
            ModuleRowsTest.shot(f.requireView(), 400, 880, "p385-editor-groups");
            text(f.requireView(), "Rows").performClick();
            idle();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1000));
            ModuleRowsTest.shot(f.requireView(), 400, 880, "p385-editor-rows");
        }
    }
}
