package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingType;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.settings.ShadeCatalog;
import com.particlesdevs.photoncamera.settings.ShadeTiles;

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

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** P25: the shade's tiles and rows on a 360dp phone, rendered into build/reports/viewfinder. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ShadeUiTest {
    private MockedStatic<PhotonCamera> camera;
    private ActivityController<AppCompatActivity> controller;
    private AppCompatActivity activity;
    private SettingsManager manager;
    private SharedPreferences prefs;
    private ShadeCatalog catalog;
    private SettingsBarLayout sheet;
    private final List<String> messages = new ArrayList<>();
    private final List<String> cameraControls = new ArrayList<>();
    private final List<String> written = new ArrayList<>();
    private int catalogOpened, settingsOpened, valueChanges;

    @Before
    public void setUp() {
        // SCAM HDR and its settings exist only on the Snapdragon 8 Elite; these tests cover both routes.
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        controller = Robolectric.buildActivity(AppCompatActivity.class);
        activity = controller.get();
        activity.setTheme(R.style.Theme_Photon_SettingsActivity);
        controller.setup();
        Context context = activity;
        manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(PhotonCamera.class);
        camera.when(PhotonCamera::getAppContext).thenReturn(context);
        camera.when(PhotonCamera::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> PhotonCamera.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(PhotonCamera::getSettingsManagerStatic).thenReturn(manager);
        camera.when(PhotonCamera::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        PhotonCamera app = mock(PhotonCamera.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> PhotonCamera.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        PreferenceKeys.setDefaults(context);
        ShadeCatalog.setFlashAvailable(true);
        catalog = new ShadeCatalog(context, prefs);
        sheet = new SettingsBarLayout(context, null);
        sheet.setCatalog(catalog);
        sheet.attach(new ShadeHost() {
            @Override
            public void applyCameraControl(SettingType type, int value) {
                cameraControls.add(type + "=" + value);
                // What CameraUIController.onChanged stores for these types.
                if (type == SettingType.FLASH) PreferenceKeys.setAeMode(value);
                else if (type == SettingType.TIMER) PreferenceKeys.setCountdownTimerIndex(value);
                else if (type == SettingType.RAW) PreferenceKeys.setSaveRaw(value);
                else if (type == SettingType.AE_METERING_STD) PreferenceKeys.setAeMeteringStd(value);
            }

            @Override
            public void onSettingWritten(ShadeCatalog.Entry entry) {
                written.add(entry.key);
            }

            @Override
            public void showMessage(CharSequence text) {
                messages.add(text.toString());
            }

            @Override
            public void openCatalog() {
                catalogOpened++;
            }

            @Override
            public void openSettings() {
                settingsOpened++;
            }

            @Override
            public void onValuesChanged() {
                valueChanges++;
            }
        });
        CoordinatorLayout coordinator = new CoordinatorLayout(context);
        CoordinatorLayout.LayoutParams lp = new CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setBehavior(new BottomSheetBehavior<SettingsBarLayout>());
        coordinator.addView(sheet, lp);
        activity.setContentView(coordinator, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @After
    public void tearDown() {
        camera.close();
        controller.pause().stop().destroy();
    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    /** Lays the sheet out at its FULL size (86 % of a 480dp viewfinder) and runs pending posts. */
    private void layoutFull() {
        int width = dp(360), height = Math.round(dp(480) * .86f);
        sheet.setMaxSheetHeight(height);
        sheet.prewarm();
        for (int i = 0; i < 3; i++) {
            sheet.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            sheet.layout(0, 0, width, height);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
    }

    private void render(String name) throws Exception {
        Bitmap image = Bitmap.createBitmap(sheet.getWidth(), sheet.getHeight(), Bitmap.Config.ARGB_8888);
        image.eraseColor(0xFF45525B);
        sheet.draw(new Canvas(image));
        File dir = new File("build/reports/viewfinder");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private RecyclerView grid() {
        return sheet.findViewWithTag("shade_tiles");
    }

    private ShadeTileView tile(String key) {
        RecyclerView grid = grid();
        for (int i = 0; i < grid.getChildCount(); i++) {
            ShadeTileView t = (ShadeTileView) grid.getChildAt(i);
            if (key.equals(t.key)) return t;
        }
        return null;
    }

    private ShadeTileView addTile() {
        RecyclerView grid = grid();
        for (int i = 0; i < grid.getChildCount(); i++) {
            ShadeTileView t = (ShadeTileView) grid.getChildAt(i);
            if (t.addTile) return t;
        }
        return null;
    }

    @Test
    public void defaultTilesRenderSquareWithoutEllipsis() throws Exception {
        layoutFull();
        assertEquals(ShadeCatalog.DEFAULT_TILES, sheet.pinnedKeys());
        RecyclerView grid = grid();
        assertEquals(9, grid.getChildCount()); // 8 tiles and «Добавить»
        assertNotNull(addTile());
        for (int i = 0; i < grid.getChildCount(); i++) {
            ShadeTileView t = (ShadeTileView) grid.getChildAt(i);
            assertTrue("square at least", t.getHeight() >= t.getWidth());
            for (android.widget.TextView text : new android.widget.TextView[]{t.value, t.name}) {
                if (text.getVisibility() != View.VISIBLE || text.getText().length() == 0) continue;
                assertNull(text.getEllipsize());
                android.text.Layout layout = text.getLayout();
                assertNotNull(layout);
                assertTrue(text.getText() + " fits", layout.getLineCount() <= 2);
                for (int l = 0; l < layout.getLineCount(); l++) assertEquals(text.getText() + " not cut", 0, layout.getEllipsisCount(l));
            }
        }
        // Four columns, 10dp apart: cards 5dp inside each tile.
        ShadeTileView first = (ShadeTileView) grid.getChildAt(0), second = (ShadeTileView) grid.getChildAt(1);
        assertEquals(dp(10), second.getLeft() + second.card.getLeft() - (first.getLeft() + first.card.getRight()));
        render("shade-full-default.png");
    }

    @Test
    public void fullLevelHasARowWithAPinPerCuratedSetting() throws Exception {
        layoutFull();
        for (ShadeCatalog.Group group : catalog.visibleGroups())
            for (String key : group.keys) {
                assertNotNull(key, sheet.findViewWithTag("shade_row_" + key));
                assertNotNull(key, sheet.findViewWithTag("shade_pin_" + key));
            }
        // The whole scroll list, as FULL shows it while scrolling.
        View list = ((ViewGroup) sheet.findViewById(R.id.settings_bar_scroll_view)).getChildAt(0);
        list.measure(View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        list.layout(0, 0, list.getMeasuredWidth(), list.getMeasuredHeight());
        Bitmap image = Bitmap.createBitmap(list.getWidth(), list.getHeight(), Bitmap.Config.ARGB_8888);
        image.eraseColor(ShadeStyle.BG);
        list.draw(new Canvas(image));
        File dir = new File("build/reports/viewfinder");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "shade-full-list.png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        // Pinning from a row adds the tile; the row's pin turns on.
        View pin = sheet.findViewWithTag("shade_pin_pref_lmc_hybrid_cdm");
        assertFalse(pin.isSelected());
        pin.performClick();
        assertTrue(sheet.pinnedKeys().contains("pref_lmc_hybrid_cdm"));
        assertTrue(sheet.findViewWithTag("shade_pin_pref_lmc_hybrid_cdm").isSelected());
    }

    @Test
    public void catalogSearchFindsLumaAndPinsFromTheResults() throws Exception {
        layoutFull();
        addTile().performClick();
        assertEquals(1, catalogOpened);
        sheet.findViewWithTag("shade_more").performClick();
        assertEquals(2, catalogOpened);
        int[] closed = {0};
        ShadeCatalogView view = new ShadeCatalogView(activity, catalog, sheet.pins(), () -> closed[0]++);
        assertTrue(view.shownKeys().size() > 300);
        assertEquals(ShadeCatalog.VIRTUAL, view.shownKeys().subList(0, 4));
        android.widget.EditText search = view.findViewWithTag("shade_catalog_search");
        search.setText("Luma");
        assertTrue(view.shownKeys().contains("pref_lmc_hybrid_post_luma"));
        assertTrue(view.shownKeys().contains("pref_lmc_hybrid_dn_luma_mult"));
        assertFalse(view.shownKeys().contains("pref_show_grid_key"));
        String counter = ((android.widget.TextView) view.findViewWithTag("shade_catalog_count")).getText().toString();
        assertTrue(counter, counter.contains("8"));
        int width = dp(360), height = dp(800);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        View row = view.findViewWithTag("catalog_row_pref_lmc_hybrid_post_luma");
        assertNotNull(row);
        row.performClick();
        assertTrue(sheet.pinnedKeys().contains("pref_lmc_hybrid_post_luma"));
        assertEquals(sheet.pinnedKeys(), ShadeTiles.stored(prefs));
        assertEquals(activity.getString(R.string.shade_added, "Luma Denoise"), messages.get(messages.size() - 1));
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(image));
        File dir = new File("build/reports/viewfinder");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "shade-catalog-luma.png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        // The pinned setting outside the curated groups gets a row in FULL under «Добавлено из настроек».
        layoutFull();
        assertNotNull(sheet.findViewWithTag("shade_row_pref_lmc_hybrid_post_luma"));
        ((View) view.findViewWithTag("shade_catalog_close")).performClick();
        assertEquals(1, closed[0]);
    }

    private void touch(RecyclerView grid, int action, float x, float y, long downTime) {
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent e = android.view.MotionEvent.obtain(downTime, now, action, x, y, 0);
        grid.dispatchTouchEvent(e);
        e.recycle();
    }

    private void relayoutGrid() {
        RecyclerView grid = grid();
        grid.measure(View.MeasureSpec.makeMeasureSpec(grid.getWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        grid.layout(grid.getLeft(), grid.getTop(), grid.getRight(), grid.getTop() + grid.getMeasuredHeight());
    }

    /** Drags the tile of {@code key} onto the place of {@code onto} in small steps, with a layout pass after each. */
    private void drag(String key, String onto, long holdMs) {
        RecyclerView grid = grid();
        ShadeTileView a = tile(key), b = tile(onto);
        float ax = a.getLeft() + a.getWidth() / 2f, ay = a.getTop() + a.getHeight() / 2f;
        float bx = b.getLeft() + b.getWidth() / 2f, by = b.getTop() + b.getHeight() / 2f;
        // A finger goes a little past the target's centre (ItemTouchHelper swaps once an edge passes the target's edge).
        bx += Math.signum(bx - ax) * .3f * b.getWidth();
        by += Math.signum(by - ay) * .3f * b.getHeight();
        long down = android.os.SystemClock.uptimeMillis();
        touch(grid, android.view.MotionEvent.ACTION_DOWN, ax, ay, down);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(holdMs));
        for (int i = 1; i <= 10; i++) {
            touch(grid, android.view.MotionEvent.ACTION_MOVE, ax + (bx - ax) * i / 10f, ay + (by - ay) * i / 10f, down);
            relayoutGrid();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20));
        }
        touch(grid, android.view.MotionEvent.ACTION_UP, bx, by, down);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
        relayoutGrid();
    }

    @Test
    public void longPressLiftsATileIntoTheEditModeAndTheDropSavesTheOrder() throws Exception {
        layoutFull();
        List<String> before = sheet.pinnedKeys();
        // A short press is a tap (the grid tile cycles), no edit mode.
        assertFalse(sheet.isEditing());
        drag(ShadeCatalog.FLASH, ShadeCatalog.FORMAT, 450);
        assertTrue("a long press enters the edit mode", sheet.isEditing());
        List<String> after = sheet.pinnedKeys();
        assertNotEquals(before, after);
        assertEquals(2, after.indexOf(ShadeCatalog.FLASH));
        assertEquals(after, ShadeTiles.stored(prefs));
        ShadeCatalog.Entry flash = catalog.entry(ShadeCatalog.FLASH);
        assertEquals(activity.getString(R.string.shade_place, flash.shortTitle, 3, 8), messages.get(messages.size() - 1));
        // Edit mode: «×» on the tiles, the rows step aside, «Готово».
        assertEquals(View.VISIBLE, tile(ShadeCatalog.TIMER).remove.getVisibility());
        android.widget.TextView edit = sheet.findViewWithTag("shade_edit");
        assertEquals(activity.getString(R.string.shade_done), edit.getText().toString());
        render("shade-edit.png");
        // In the edit mode a tap does not change the value; «×» unpins.
        String timer = String.valueOf(PreferenceKeys.getCountdownTimerIndex());
        tile(ShadeCatalog.TIMER).performClick();
        assertEquals(timer, String.valueOf(PreferenceKeys.getCountdownTimerIndex()));
        tile(ShadeCatalog.TIMER).remove.performClick();
        assertFalse(sheet.pinnedKeys().contains(ShadeCatalog.TIMER));
        // Back leaves the edit mode first.
        assertTrue(sheet.onBackPressed());
        assertFalse(sheet.isEditing());
        assertFalse(sheet.onBackPressed());
        layoutFull();
        assertEquals(View.GONE, tile(ShadeCatalog.FORMAT).remove.getVisibility());
    }

    @Test
    public void editButtonAndTalkBackActionsReorder() throws Exception {
        layoutFull();
        android.widget.TextView edit = sheet.findViewWithTag("shade_edit");
        assertEquals(activity.getString(R.string.shade_edit), edit.getText().toString());
        edit.performClick();
        assertTrue(sheet.isEditing());
        layoutFull();
        // In the edit mode a press-drag lifts the tile at once (no long press).
        drag("pref_lmc_hybrid_bento", ShadeCatalog.FLASH, 150);
        assertEquals(0, sheet.pinnedKeys().indexOf("pref_lmc_hybrid_bento"));
        assertTrue(sheet.isEditing());
        edit.performClick();
        assertFalse(sheet.isEditing());
        layoutFull();
        // TalkBack: «Позже», «Раньше», «Убрать из шторки».
        ShadeTileView route = tile(ShadeCatalog.ROUTE);
        int at = sheet.pinnedKeys().indexOf(ShadeCatalog.ROUTE);
        assertTrue(route.performAccessibilityAction(R.id.shade_action_later, null));
        assertEquals(at + 1, sheet.pinnedKeys().indexOf(ShadeCatalog.ROUTE));
        layoutFull();
        assertTrue(tile(ShadeCatalog.ROUTE).performAccessibilityAction(R.id.shade_action_earlier, null));
        assertEquals(at, sheet.pinnedKeys().indexOf(ShadeCatalog.ROUTE));
        assertEquals(sheet.pinnedKeys(), ShadeTiles.stored(prefs));
        layoutFull();
        assertTrue(tile(ShadeCatalog.ROUTE).performAccessibilityAction(R.id.shade_action_remove, null));
        assertFalse(sheet.pinnedKeys().contains(ShadeCatalog.ROUTE));
    }

    @Test
    public void bothShadeSettingsEntriesOpenTheSettings() throws Exception {
        layoutFull();
        View gear = sheet.findViewWithTag("shade_settings");
        assertEquals(View.VISIBLE, gear.getVisibility());
        assertEquals(dp(36), gear.getWidth());
        gear.performClick();
        assertEquals(1, settingsOpened);
        View all = sheet.findViewWithTag("shade_all_settings");
        assertNotNull("«Все настройки» ends FULL", all);
        ViewGroup list = (ViewGroup) all.getParent();
        assertEquals(list.getChildCount() - 1, list.indexOfChild(all));
        assertEquals(list.getChildCount() - 2, list.indexOfChild(sheet.findViewWithTag("shade_more")));
        all.performClick();
        assertEquals(2, settingsOpened);
        // The header gear steps aside in the edit mode.
        sheet.setEditing(true);
        assertEquals(View.GONE, gear.getVisibility());
        sheet.setEditing(false);
        assertEquals(View.VISIBLE, gear.getVisibility());
        // A write tells the camera screen (top bar badges).
        int before = valueChanges;
        tile("pref_show_grid_key").performClick();
        assertTrue(valueChanges > before);
    }

    /** Every text of a tile on one or two lines, no ellipsis, no word split across lines. */
    private void assertTileTextFits(ShadeTileView t) {
        for (android.widget.TextView text : new android.widget.TextView[]{t.value, t.name}) {
            if (text.getVisibility() != View.VISIBLE || text.getText().length() == 0) continue;
            android.text.Layout layout = text.getLayout();
            assertNotNull(layout);
            assertTrue(text.getText() + " in at most two lines", layout.getLineCount() <= 2);
            for (int l = 0; l < layout.getLineCount(); l++) {
                assertEquals(text.getText() + " not cut", 0, layout.getEllipsisCount(l));
                int end = layout.getLineEnd(l);
                if (l < layout.getLineCount() - 1 && end > 0 && end < text.getText().length())
                    assertTrue(text.getText() + " breaks between words",
                            Character.isWhitespace(text.getText().charAt(end - 1)) || Character.isWhitespace(text.getText().charAt(end))
                                    || text.getText().charAt(end - 1) == '+' || text.getText().charAt(end - 1) == '-');
            }
        }
    }

    @Test
    @Config(qualifiers = "ru-w360dp-h800dp-xhdpi")
    public void russianTilesFitAt360dpWithoutEllipsis() throws Exception {
        // The twelve longest names and values of the curated rows, in Russian, on the narrowest phone.
        List<String> longest = Arrays.asList("pref_lmc_hybrid_downsampler", "pref_show_watermark_key", "pref_lmc_hybrid_zsl_frames",
                "pref_vivo_nice_zsl_frames", "pref_vivo_nice_long_boost_ev", "pref_vivo_nice_mosaic", "pref_peak_method_key",
                "pref_live_viewfinder_raw_key", "pref_show_afdata_key", "pref_lmc_hybrid_cdm", ShadeCatalog.METERING_STD,
                "pref_lmc_hybrid_bento_frames");
        ShadeTiles.save(prefs, longest);
        prefs.edit().putString("pref_vivo_nice_mosaic", "neural_sabre").putString("pref_show_afdata_key", "2")
                .putString("pref_lmc_hybrid_downsampler", "bilinear").commit();
        layoutFull();
        assertEquals(longest, sheet.pinnedKeys());
        assertEquals("Даунсемплер", catalog.entry("pref_lmc_hybrid_downsampler").shortTitle.toString());
        RecyclerView grid = grid();
        assertEquals(12, grid.getChildCount());
        for (int i = 0; i < grid.getChildCount(); i++) assertTileTextFits((ShadeTileView) grid.getChildAt(i));
        render("shade-ru-360dp.png");
        // The rows' segments too: one or two lines, never an ellipsis.
        for (ShadeCatalog.Group group : catalog.visibleGroups())
            for (String key : group.keys) {
                View row = sheet.findViewWithTag("shade_row_" + key);
                List<View> segments = new ArrayList<>();
                for (int s = 0; s < 8; s++) {
                    View seg = row.findViewWithTag("segment_" + s);
                    if (seg != null) segments.add(seg);
                }
                for (View seg : segments) {
                    android.text.Layout layout = ((android.widget.TextView) seg).getLayout();
                    assertNotNull(key, layout);
                    assertTrue(key + " segment in two lines", layout.getLineCount() <= 2);
                    for (int l = 0; l < layout.getLineCount(); l++) assertEquals(key, 0, layout.getEllipsisCount(l));
                }
            }
    }

    @Test
    public void tilesHighlightNonDefaultValues() throws Exception {
        layoutFull();
        ShadeTileView grid = tile("pref_show_grid_key");
        assertFalse(grid.active);
        grid.performClick();
        assertEquals("1", prefs.getString("pref_show_grid_key", null));
        assertTrue(written.contains("pref_show_grid_key"));
        assertTrue(tile("pref_show_grid_key").active);
        assertTrue(messages.get(messages.size() - 1).endsWith(activity.getString(R.string.three_x3)));
        // A toggle that is on by default (Ultra HDR) lights up when switched off.
        boolean ultraDefault = activity.getResources().getBoolean(R.bool.pref_ultrahdr_default);
        ShadeTileView ultra = tile("pref_ultrahdr_key");
        assertFalse(ultra.active);
        ultra.performClick();
        assertEquals(!ultraDefault, prefs.getBoolean("pref_ultrahdr_key", ultraDefault));
        assertTrue(tile("pref_ultrahdr_key").active);
        // Camera controls go through CameraUIController's path.
        tile(ShadeCatalog.FORMAT).performClick();
        assertEquals(Arrays.asList("RAW=1"), cameraControls);
        assertTrue(tile(ShadeCatalog.FORMAT).active);
        assertEquals("R+J", tile(ShadeCatalog.FORMAT).value.getText().toString());
        layoutFull();
        render("shade-full-changed.png");
    }

    @Test
    public void unavailableTilesAreDimmedAndExplain() throws Exception {
        layoutFull();
        manager.set("default_scope", PreferenceKeys.ROUTE_KEY, "scamhdr");
        sheet.refresh();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        ShadeTileView bento = tile("pref_lmc_hybrid_bento");
        assertTrue(bento.dimmed);
        assertEquals(ShadeStyle.DIMMED, bento.card.getAlpha(), 1e-6f);
        String before = prefs.getString("pref_lmc_hybrid_bento", "1");
        bento.performClick();
        assertEquals(before, prefs.getString("pref_lmc_hybrid_bento", "1"));
        assertEquals(catalog.unavailable(catalog.entry("pref_lmc_hybrid_bento")), messages.get(messages.size() - 1));
        // No flash on this lens: the flash tile is dimmed, a tap says why.
        sheet.setFlashAvailable(false);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(tile(ShadeCatalog.FLASH).dimmed);
        tile(ShadeCatalog.FLASH).performClick();
        assertTrue(cameraControls.isEmpty());
        assertEquals(activity.getString(R.string.shade_reason_no_flash), messages.get(messages.size() - 1));
        ShadeCatalog.setFlashAvailable(true);
    }

    @Test
    public void sliderTileOpensTheInlineCardAndPinsAreLimitedToTwelve() throws Exception {
        layoutFull();
        sheet.togglePin("pref_lmc_hybrid_dn_luma_mult");
        assertEquals(9, sheet.pinnedKeys().size());
        assertEquals(sheet.pinnedKeys(), ShadeTiles.stored(prefs));
        layoutFull();
        View card = sheet.findViewWithTag("shade_slider_card");
        assertEquals(View.GONE, card.getVisibility());
        tile("pref_lmc_hybrid_dn_luma_mult").performClick();
        layoutFull();
        assertEquals(View.VISIBLE, card.getVisibility());
        render("shade-full-slider.png");
        tile("pref_lmc_hybrid_dn_luma_mult").performClick();
        layoutFull();
        assertEquals(View.GONE, card.getVisibility());
        for (String key : new String[]{"pref_lmc_hybrid_shasta", "pref_lmc_hybrid_cdm", "pref_lmc_hybrid_sharp_mode"}) sheet.togglePin(key);
        assertEquals(12, sheet.pinnedKeys().size());
        layoutFull();
        assertNull("no «Добавить» tile at 12", addTile());
        sheet.togglePin("pref_vivo_nice_mosaic");
        assertEquals(12, sheet.pinnedKeys().size());
        assertEquals(activity.getString(R.string.shade_full, 12), messages.get(messages.size() - 1));
        sheet.togglePin("pref_lmc_hybrid_cdm");
        layoutFull();
        assertEquals(11, sheet.pinnedKeys().size());
        assertNotNull(addTile());
        render("shade-full-eleven.png");
    }
}
