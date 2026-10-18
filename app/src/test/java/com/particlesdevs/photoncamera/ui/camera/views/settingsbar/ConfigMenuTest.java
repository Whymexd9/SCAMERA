package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.ConfigState;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingType;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.settings.ShadeCatalog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** P143: the shade header's config button (the active config in a frame) and its menu: save, save as, load, import, reset. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ConfigMenuTest {
    private MockedStatic<ScameraApp> camera;
    private ActivityController<AppCompatActivity> controller;
    private AppCompatActivity activity;
    private SettingsBarLayout sheet;
    private final List<String> messages = new ArrayList<>(), calls = new ArrayList<>();
    private int imports;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(AppCompatActivity.class);
        activity = controller.get();
        activity.setTheme(R.style.Theme_Scamera_SettingsActivity);
        controller.setup();
        Context context = activity;
        SettingsManager manager = new SettingsManager(context);
        SharedPreferences prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        camera.when(ScameraApp::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        PreferenceKeys.setDefaults(context);
        ConfigState.clear(context);
        sheet = new SettingsBarLayout(context, null);
        sheet.setCatalog(new ShadeCatalog(context, prefs));
        sheet.attach(new ShadeHost() {
            @Override public void applyCameraControl(SettingType type, int value) { }
            @Override public void onSettingWritten(ShadeCatalog.Entry entry) { }
            @Override public void showMessage(CharSequence text) { messages.add(text.toString()); }
            @Override public void openCatalog() { }
            @Override public void openSettings() { }
            @Override public void onValuesChanged() { }
            @Override public void importConfigFile() { imports++; }
        });
        sheet.setConfigActions(new ConfigMenu.Actions() {
            @Override public String save(String name) {
                calls.add("save " + name);
                ConfigState.set(activity, name + ".xml");
                return "saved " + name;
            }
            @Override public String[] saved() { return new String[]{"Day.xml", "Night_City.xml"}; }
            @Override public String load(String fileName) { calls.add("load " + fileName); return "loaded"; }
            @Override public void importFile() { calls.add("import"); }
            @Override public String reset() { calls.add("reset"); return "reset"; }
            @Override public boolean delete(String fileName) { calls.add("delete " + fileName); return true; }
        });
        CoordinatorLayout coordinator = new CoordinatorLayout(context);
        CoordinatorLayout.LayoutParams lp = new CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setBehavior(new BottomSheetBehavior<SettingsBarLayout>());
        coordinator.addView(sheet, lp);
        activity.setContentView(coordinator, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout();
    }

    @After
    public void tearDown() {
        ConfigMenu menu = sheet.configMenu();
        if (menu != null) menu.dismissNow();
        camera.close();
        controller.pause().stop().destroy();
    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private void layout() {
        int w = dp(360), h = dp(420);
        View root = sheet.getRootView();
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        idle();
    }

    private void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
    }

    private View menuView(String tag) {
        ConfigMenu menu = sheet.configMenu();
        assertNotNull("the menu is open", menu);
        View v = menu.content().findViewWithTag(tag);
        assertNotNull(tag, v);
        return v;
    }

    private void openMenu() {
        sheet.configChip().performClick();
        idle();
        assertTrue(sheet.configMenu() != null && sheet.configMenu().isShowing());
        assertTrue("the chevron turns while open", sheet.configChip().isOpen());
    }

    @Test
    public void theButtonShowsTheActiveConfigInsideItsFrame() {
        ConfigChip chip = sheet.configChip();
        // SIMPLE_UI §2: the chip is removed from the sheet (its naming still works for the config menu's other entries)
        assertEquals(View.GONE, chip.getVisibility());
        assertEquals("no config yet", "Default", chip.getName().toString());
        ConfigState.set(activity, "Download/SCAMERA/XML/Night_City.xml");
        sheet.refreshConfigName();
        assertEquals("Night_City", chip.getName().toString());
        // a very long name: the frame keeps within its slot, the text shrinks to 9sp and then ends in an ellipsis
        ConfigState.set(activity, "Very_long_config_name_for_the_evening_street_with_neon_and_rain_2026_10_15.xml");
        sheet.refreshConfigName();
        int slot = dp(170);
        chip.measure(View.MeasureSpec.makeMeasureSpec(slot, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertTrue("within the slot: " + chip.getMeasuredWidth(), chip.getMeasuredWidth() <= slot);
        TextView name = chip.nameView();
        float sp = name.getTextSize() / activity.getResources().getDisplayMetrics().scaledDensity;
        assertEquals("shrunk to the smallest size first", ConfigChip.MIN_SP, sp, .01f);
        assertNotNull("then an ellipsis inside the frame", name.getEllipsize());
        // a name that fits a little smaller only shrinks
        ConfigState.set(activity, "Street_night_2026.xml");
        sheet.refreshConfigName();
        chip.measure(View.MeasureSpec.makeMeasureSpec(dp(150), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertTrue(chip.getMeasuredWidth() <= dp(150));
        assertNull("no ellipsis when shrinking is enough", name.getEllipsize());
        // the edit mode does not bring it back either
        sheet.setEditing(true);
        assertEquals(View.GONE, chip.getVisibility());
        sheet.setEditing(false);
        assertEquals(View.GONE, chip.getVisibility());
    }

    @Test
    public void menuSavesAsNewName() {
        openMenu();
        assertEquals("no active config: nothing to overwrite", null, sheet.configMenu().content().findViewWithTag("config_save"));
        View panel = menuView("config_save_panel");
        assertEquals(View.GONE, panel.getVisibility());
        menuView("config_save_as").performClick();
        assertEquals(View.VISIBLE, panel.getVisibility());
        ((EditText) menuView("config_name_field")).setText("My night 2");
        menuView("config_save_confirm").performClick();
        idle();
        assertEquals("spaces become «_»", List.of("save My_night_2"), calls);
        assertTrue(messages.contains("saved My_night_2"));
        assertEquals("the button shows the new config", "My_night_2", sheet.configChip().getName().toString());
        assertNull("the menu folded away", sheet.configMenu());
        assertFalse(sheet.configChip().isOpen());
        // reopened: «Сохранить» overwrites the active config
        openMenu();
        menuView("config_save").performClick();
        idle();
        assertEquals("save My_night_2", calls.get(1));
    }

    @Test
    public void menuLoadsImportsAndResetsAfterAConfirm() {
        ConfigState.set(activity, "Night_City.xml");
        openMenu();
        assertNotNull("saved configs listed", menuView("config_item_Day"));
        View item = menuView("config_item_Night_City");
        View confirm = menuView("config_load_panel_Night_City");
        item.performClick();
        assertEquals(View.VISIBLE, confirm.getVisibility());
        assertTrue("nothing loaded before the confirm", calls.isEmpty());
        // one inline step at a time
        menuView("config_reset").performClick();
        assertEquals(View.GONE, confirm.getVisibility());
        assertEquals(View.VISIBLE, menuView("config_reset_panel").getVisibility());
        menuView("config_item_Night_City").performClick();
        menuView("config_load_confirm").performClick();
        idle();
        assertEquals(List.of("load Night_City.xml"), calls);
        openMenu();
        menuView("config_import").performClick();
        idle();
        assertEquals("import", calls.get(1));
        openMenu();
        menuView("config_reset").performClick();
        menuView("config_reset_confirm").performClick();
        idle();
        assertEquals("reset", calls.get(2));
        assertTrue(messages.contains("reset"));
    }

    /** «Удалить конфиг» deletes the active config's file: the settings stay, the button says «Без конфига». */
    @Test
    public void deletingTheActiveConfigKeepsTheSettings() {
        ConfigState.set(activity, "Night_City.xml");
        sheet.refreshConfigName();
        openMenu();
        View ask = menuView("config_delete_panel");
        assertEquals(View.GONE, ask.getVisibility());
        menuView("config_delete").performClick();
        assertEquals(View.VISIBLE, ask.getVisibility());
        assertTrue("nothing deleted before the confirm", calls.isEmpty());
        menuView("config_delete_confirm").performClick();
        idle();
        assertEquals(List.of("delete Night_City.xml"), calls);
        assertNull("no active config any more", ConfigState.active(activity));
        assertEquals("No config", sheet.configChip().getName().toString());
        assertTrue(messages.contains("Deleted «Night_City.xml»"));
        assertTrue("the menu stays for more", sheet.configMenu() != null && sheet.configMenu().isShowing());
        assertEquals(View.GONE, menuView("config_save").getVisibility());
        assertEquals(View.GONE, menuView("config_delete").getVisibility());
        assertNull("its row left the list", sheet.configMenu().content().findViewWithTag("config_item_Night_City"));
        assertEquals("No config", ((TextView) menuView("config_menu_name")).getText().toString());
        // a config imported from elsewhere has no file here: nothing to delete
        sheet.configMenu().dismissNow();
        ConfigState.set(activity, "From_another_phone.xml");
        openMenu();
        assertNull(sheet.configMenu().content().findViewWithTag("config_delete"));
    }

    /** A saved config: «Удалить» asks again, «Отмена» goes back, the confirm deletes it and its row folds away. */
    @Test
    public void savedConfigsCanBeDeleted() {
        openMenu();
        menuView("config_item_Day").performClick();
        View choose = menuView("config_choose_Day"), sure = menuView("config_sure_Day");
        assertEquals(View.VISIBLE, choose.getVisibility());
        assertEquals(View.GONE, sure.getVisibility());
        choose.findViewWithTag("config_item_delete").performClick();
        assertEquals(View.GONE, choose.getVisibility());
        assertEquals(View.VISIBLE, sure.getVisibility());
        sure.findViewWithTag("config_item_keep").performClick();
        assertEquals("back to load / delete", View.VISIBLE, choose.getVisibility());
        choose.findViewWithTag("config_item_delete").performClick();
        sure.findViewWithTag("config_item_delete_confirm").performClick();
        idle();
        assertEquals(List.of("delete Day.xml"), calls);
        assertNull(sheet.configMenu().content().findViewWithTag("config_item_Day"));
        assertNotNull("the others stay", menuView("config_item_Night_City"));
        assertEquals("not the active one: the button keeps its name", "Default", sheet.configChip().getName().toString());
        // the last one gone: the section folds too
        menuView("config_item_Night_City").performClick();
        menuView("config_choose_Night_City").findViewWithTag("config_item_delete").performClick();
        menuView("config_sure_Night_City").findViewWithTag("config_item_delete_confirm").performClick();
        idle();
        assertEquals(View.GONE, menuView("config_list").getVisibility());
    }

    @Test
    public void aTapOnTheButtonAgainClosesAndHidingTheShadeDropsTheMenu() {
        openMenu();
        sheet.configChip().performClick();
        idle();
        assertNull(sheet.configMenu());
        openMenu();
        sheet.setEditing(true);
        assertNull("the edit mode closes it", sheet.configMenu());
    }

    /**
     * The whole menu scrolls inside the room under the button (owner, 2026-10-16: it could not scroll and «Сбросить настройки»
     * stayed under the screen's edge); the reset says the saved configs stay.
     */
    @Test
    public void menuScrollsWithinTheRoomBelowTheButton() {
        ConfigState.set(activity, "Night_City.xml");
        openMenu();
        ConfigMenu menu = sheet.configMenu();
        menu.limitHeight(dp(300));
        View content = menu.content();
        content.measure(View.MeasureSpec.makeMeasureSpec(dp(330), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        content.layout(0, 0, content.getMeasuredWidth(), content.getMeasuredHeight());
        androidx.core.widget.NestedScrollView scroll = (androidx.core.widget.NestedScrollView) menuView("config_menu_scroll");
        View body = scroll.getChildAt(0);
        assertTrue("the menu is taller than the room", body.getHeight() > scroll.getHeight());
        assertTrue("the card fits the room: " + content.getMeasuredHeight(), content.getMeasuredHeight() <= dp(300));
        View reset = menuView("config_reset");
        assertTrue("under the edge before scrolling", reset.getBottom() > scroll.getHeight());
        scroll.scrollTo(0, body.getHeight() - scroll.getHeight());
        int top = reset.getTop() - scroll.getScrollY();
        assertTrue("reachable at the end: " + top, top >= 0 && top + reset.getHeight() <= scroll.getHeight());
        reset.performClick();
        String text = ((android.widget.TextView) ((ViewGroup) menuView("config_reset_panel")).getChildAt(0)).getText().toString();
        assertTrue(text, text.contains("Saved configs and curves stay"));
    }

    /**
     * The menu opens at the button's laid-out place even while the button still springs back from its press dip (owner's
     * video, 2026-10-16: the card jumped 3-4 dp sideways during or right after unfolding).
     */
    @Test
    public void menuOpensAtTheButtonsPlaceDuringItsPressDip() {
        ConfigChip chip = sheet.configChip();
        chip.setScaleX(.96f);
        chip.setScaleY(.96f);
        openMenu();
        android.view.WindowManager.LayoutParams dipped =
                (android.view.WindowManager.LayoutParams) sheet.configMenu().content().getRootView().getLayoutParams();
        int dx = dipped.x, dy = dipped.y;
        sheet.configMenu().dismissNow();
        idle();
        chip.setScaleX(1f);
        chip.setScaleY(1f);
        openMenu();
        android.view.WindowManager.LayoutParams rest =
                (android.view.WindowManager.LayoutParams) sheet.configMenu().content().getRootView().getLayoutParams();
        assertEquals("x", rest.x, dx);
        assertEquals("y", rest.y, dy);
        int[] frame = ConfigMenu.frameOnScreen(chip);
        assertEquals("from the button's left", frame[0] - sheet.configMenu().content().getPaddingLeft(), rest.x);
    }

    /** «Открыть папку конфигов» until Download/SCAMERA is open to the app; it hands over to the camera's folder picker. */
    @Test
    public void openFolderRowUntilTheFolderIsOpen() {
        openMenu();
        assertNull("the folder is open (the default of the test actions)", sheet.configMenu().content().findViewWithTag("config_open_folder"));
        sheet.configMenu().dismissNow();
        idle();
        final boolean[] open = {false};
        sheet.setConfigActions(new ConfigMenu.Actions() {
            @Override public String save(String name) { return "saved"; }
            @Override public String[] saved() { return new String[0]; }
            @Override public String load(String fileName) { return "loaded"; }
            @Override public void importFile() { }
            @Override public String reset() { return "reset"; }
            @Override public boolean delete(String fileName) { return true; }
            @Override public boolean folderOpen() { return open[0]; }
            @Override public void openFolder() { calls.add("folder"); open[0] = true; }
        });
        openMenu();
        menuView("config_open_folder").performClick();
        idle();
        assertEquals(List.of("folder"), calls);
        assertFalse("the menu closes for the picker", sheet.configMenu() != null && sheet.configMenu().isShowing());
        openMenu();
        assertNull("gone once open", sheet.configMenu().content().findViewWithTag("config_open_folder"));
    }

    /** The header and the open menu rendered into build/reports/config-menu for a look (the rows unfolded at the end). */
    @Test
    public void renderForReview() throws Exception {
        ConfigState.set(activity, "Night_City.xml");
        sheet.refreshConfigName();
        layout();
        java.io.File dir = new java.io.File("build/reports/config-menu");
        dir.mkdirs();
        save(sheet, new java.io.File(dir, "header.png"), dp(360), dp(170));
        openMenu();
        menuView("config_save_as").performClick();
        idle();
        View content = sheet.configMenu().content();
        content.measure(View.MeasureSpec.makeMeasureSpec(content.getWidth() > 0 ? content.getWidth() : dp(330), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        content.layout(0, 0, content.getMeasuredWidth(), content.getMeasuredHeight());
        save(content, new java.io.File(dir, "menu.png"), content.getMeasuredWidth(), content.getMeasuredHeight());
        menuView("config_item_Day").performClick();
        idle();
        content.measure(View.MeasureSpec.makeMeasureSpec(content.getMeasuredWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        content.layout(0, 0, content.getMeasuredWidth(), content.getMeasuredHeight());
        save(content, new java.io.File(dir, "menu-saved.png"), content.getMeasuredWidth(), content.getMeasuredHeight());
        // a short room under the button: the menu scrolls, here at its top and at its end (the reset reachable)
        sheet.configMenu().limitHeight(dp(340));
        content.measure(View.MeasureSpec.makeMeasureSpec(content.getMeasuredWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        content.layout(0, 0, content.getMeasuredWidth(), content.getMeasuredHeight());
        save(content, new java.io.File(dir, "menu-short-top.png"), content.getMeasuredWidth(), content.getMeasuredHeight());
        androidx.core.widget.NestedScrollView scroll = (androidx.core.widget.NestedScrollView) menuView("config_menu_scroll");
        scroll.scrollTo(0, scroll.getChildAt(0).getHeight() - scroll.getHeight());
        menuView("config_reset").performClick();
        idle();
        content.measure(View.MeasureSpec.makeMeasureSpec(content.getMeasuredWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        content.layout(0, 0, content.getMeasuredWidth(), content.getMeasuredHeight());
        scroll.scrollTo(0, scroll.getChildAt(0).getHeight() - scroll.getHeight());
        save(content, new java.io.File(dir, "menu-short-end.png"), content.getMeasuredWidth(), content.getMeasuredHeight());
        assertTrue(new java.io.File(dir, "menu.png").length() > 0);
    }

    private void save(View v, java.io.File file, int w, int h) throws java.io.IOException {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        canvas.drawColor(0xFF0B0E12);
        v.draw(canvas);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    @Test
    public void configNamesFromPathsAndFields() {
        assertEquals("Night_1", ConfigState.base("Download/SCAMERA/XML/Night_1.xml"));
        assertEquals("x", ConfigState.base("primary:Download/x.json"));
        assertEquals("Street_night-2", ConfigState.fileSafe("  Street night-2 "));
        assertEquals("Ночь_город", ConfigState.fileSafe("Ночь, город"));
        ConfigState.set(activity, "a/b/Day.xml");
        assertEquals("Day", ConfigState.active(activity));
        ConfigState.clear(activity);
        assertNull(ConfigState.active(activity));
        assertEquals("Default", ConfigState.label(activity));
        ConfigState.set(activity, "Day.xml");
        ConfigState.detach(activity);
        assertNull(ConfigState.active(activity));
        assertEquals("its file deleted: no config, not the defaults", "No config", ConfigState.label(activity));
        ConfigState.set(activity, "Night.xml");
        assertEquals("Night", ConfigState.label(activity));
        ConfigState.clear(activity);
        assertEquals("the reset: the defaults", "Default", ConfigState.label(activity));
    }
}
