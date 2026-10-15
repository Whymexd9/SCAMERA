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
        assertEquals(View.VISIBLE, chip.getVisibility());
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
        // the edit mode shows the drag hint in its place
        sheet.setEditing(true);
        assertEquals(View.GONE, chip.getVisibility());
        sheet.setEditing(false);
        assertEquals(View.VISIBLE, chip.getVisibility());
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
    }
}
