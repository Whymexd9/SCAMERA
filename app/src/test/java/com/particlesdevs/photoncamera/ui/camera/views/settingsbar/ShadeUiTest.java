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
import com.particlesdevs.photoncamera.app.ScameraApp;
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
    private MockedStatic<ScameraApp> camera;
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
        activity.setTheme(R.style.Theme_Scamera_SettingsActivity);
        controller.setup();
        Context context = activity;
        manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
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
        ShadeCatalog.setFlashAvailable(true);
        // the mechanics below were written for the old seven tiles: keep them as a user's custom list
        ShadeTiles.save(prefs, ShadeCatalog.OLD_DEFAULT_TILES);
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
                else if (type == SettingType.FORMAT_CHOICE)
                    com.particlesdevs.photoncamera.settings.FormatChoice.store(com.particlesdevs.photoncamera.settings.FormatChoice.values()[value]);
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
        prefs.edit().remove(ShadeTiles.KEY).commit();   // SIMPLE_UI §2: the new defaults
        layoutFull();
        assertEquals(ShadeCatalog.DEFAULT_TILES, sheet.pinnedKeys());
        RecyclerView grid = grid();
        assertEquals(ShadeCatalog.DEFAULT_TILES.size() + 1, grid.getChildCount()); // the tiles and «Добавить»
        assertNotNull(addTile());
        for (int i = 0; i < grid.getChildCount(); i++) {
            ShadeTileView t = (ShadeTileView) grid.getChildAt(i);
            // SIMPLE_UI §2: compact tiles, wider than tall, the concept's 50dp card at least
            assertTrue("wider than tall", t.getWidth() > t.getHeight());
            assertTrue("the 50dp card", t.card.getHeight() >= com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(activity, 50) - 1);
            for (android.widget.TextView text : new android.widget.TextView[]{t.value, t.name}) {
                if (text.getVisibility() != View.VISIBLE || text.getText().length() == 0) continue;
                assertNull(text.getEllipsize());
                android.text.Layout layout = text.getLayout();
                assertNotNull(layout);
                assertEquals(text.getText() + " one line", 1, layout.getLineCount());
                for (int l = 0; l < layout.getLineCount(); l++) assertEquals(text.getText() + " not cut", 0, layout.getEllipsisCount(l));
            }
        }
        // Four columns, 5dp x k apart (the concept's gap): half of it inside each tile.
        ShadeTileView first = (ShadeTileView) grid.getChildAt(0), second = (ShadeTileView) grid.getChildAt(1);
        assertEquals(2 * Math.round(com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dpF(activity, 2.5f)),
                second.getLeft() + second.card.getLeft() - (first.getLeft() + first.card.getRight()));
        render("shade-full-default.png");
    }

    @Test
    public void theAdvancedCardListsTheUnpinnedAdvancedSettings() throws Exception {
        // SIMPLE_UI §2: below the tiles one «Для опытных» card with the advanced settings not pinned, then «Все настройки»
        layoutFull();
        View header = sheet.findViewWithTag("shade_advanced");
        assertNotNull(header);
        View body = sheet.findViewWithTag("shade_advanced_body");
        assertEquals("closed at first", View.GONE, body.getVisibility());
        header.performClick();
        assertEquals(View.VISIBLE, body.getVisibility());
        for (String key : ShadeCatalog.ADVANCED) {
            boolean pinnedHere = sheet.pinnedKeys().contains(key);
            if (!catalog.isKnown(key)) continue;
            assertEquals(key, !pinnedHere, sheet.findViewWithTag("shade_row_" + key) != null);
        }
        assertNull("no «Другие настройки» row: the catalog opens from «Добавить»", sheet.findViewWithTag("shade_more"));
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
        // pinning an advanced setting as a tile takes it out of the card
        String first = null;
        for (String key : ShadeCatalog.ADVANCED) if (!sheet.pinnedKeys().contains(key) && catalog.isKnown(key)) { first = key; break; }
        assertNotNull(first);
        sheet.togglePin(first);
        layoutFull();
        assertNull(sheet.findViewWithTag("shade_row_" + first));
    }

    @Test
    public void catalogSearchFindsLumaAndPinsFromTheResults() throws Exception {
        layoutFull();
        addTile().performClick();
        assertEquals(1, catalogOpened);
        int[] closed = {0};
        ShadeCatalogView view = new ShadeCatalogView(activity, catalog, sheet.pins(), () -> closed[0]++);
        assertTrue(String.valueOf(view.shownKeys().size()), view.shownKeys().size() > 240); // 260 since P129
        assertEquals(ShadeCatalog.VIRTUAL, view.shownKeys().subList(0, ShadeCatalog.VIRTUAL.size()));
        android.widget.EditText search = view.findViewWithTag("shade_catalog_search");
        search.setText("Luma");
        assertTrue(view.shownKeys().contains("pref_scam_hybrid_post_luma"));
        assertTrue(view.shownKeys().contains("pref_scam_hybrid_dn_luma_mult"));
        assertFalse(view.shownKeys().contains("pref_show_grid_key"));
        String counter = ((android.widget.TextView) view.findViewWithTag("shade_catalog_count")).getText().toString();
        assertTrue(counter, counter.trim().endsWith(" 7"));   // P167: «in the panel: 7», no «of 12»
        // P406: the pyramid denoise's luma rows lead the noise page now; the NLM row is further down the results
        int width = dp(360), height = dp(1400);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        View row = view.findViewWithTag("catalog_row_pref_scam_hybrid_post_luma");
        assertNotNull(row);
        row.performClick();
        assertTrue(sheet.pinnedKeys().contains("pref_scam_hybrid_post_luma"));
        assertEquals(sheet.pinnedKeys(), ShadeTiles.stored(prefs));
        // the NLM and GCam luma rows of one page have distinct names since the settings audit (H5)
        assertEquals(activity.getString(R.string.shade_added, "NLM denoise: luma"), messages.get(messages.size() - 1));
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(image));
        File dir = new File("build/reports/viewfinder");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "shade-catalog-luma.png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        // SIMPLE_UI §2: the pinned setting is a tile (no rows of pinned settings under the tiles any more)
        layoutFull();
        assertTrue(sheet.pinnedKeys().contains("pref_scam_hybrid_post_luma"));
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
        assertEquals(activity.getString(R.string.shade_place, flash.shortTitle, 3, 7), messages.get(messages.size() - 1));
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
        drag("pref_scam_hybrid_bento", ShadeCatalog.FLASH, 150);
        assertEquals(0, sheet.pinnedKeys().indexOf("pref_scam_hybrid_bento"));
        assertTrue(sheet.isEditing());
        edit.performClick();
        assertFalse(sheet.isEditing());
        layoutFull();
        // TalkBack: «Позже», «Раньше», «Убрать из шторки».
        final String output = "pref_scam_hybrid_output";
        ShadeTileView moved = tile(output);
        int at = sheet.pinnedKeys().indexOf(output);
        assertTrue(moved.performAccessibilityAction(R.id.shade_action_later, null));
        assertEquals(at + 1, sheet.pinnedKeys().indexOf(output));
        layoutFull();
        assertTrue(tile(output).performAccessibilityAction(R.id.shade_action_earlier, null));
        assertEquals(at, sheet.pinnedKeys().indexOf(output));
        assertEquals(sheet.pinnedKeys(), ShadeTiles.stored(prefs));
        layoutFull();
        assertTrue(tile(output).performAccessibilityAction(R.id.shade_action_remove, null));
        assertFalse(sheet.pinnedKeys().contains(output));
    }

    @Test
    public void bothShadeSettingsEntriesOpenTheSettings() throws Exception {
        layoutFull();
        View gear = sheet.findViewWithTag("shade_settings");
        assertEquals(View.VISIBLE, gear.getVisibility());
        assertEquals(com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(activity, 40), gear.getWidth());   // the concept's 40dp disc
        gear.performClick();
        assertEquals(1, settingsOpened);
        View all = sheet.findViewWithTag("shade_all_settings");
        assertNotNull("«Все настройки» ends the sheet", all);
        ViewGroup list = (ViewGroup) all.getParent();
        assertEquals(list.getChildCount() - 1, list.indexOfChild(all));
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
        List<String> longest = Arrays.asList("pref_scam_hybrid_downsampler", "pref_show_watermark_key", "pref_scam_hybrid_zsl_frames",
                "pref_scam_hybrid_shasta", "pref_scam_hybrid_dn_luma_mult", "pref_face_detect_mode", "pref_peak_method_key",
                "pref_live_viewfinder_raw_key", "pref_show_afdata_key", "pref_scam_hybrid_cdm", ShadeCatalog.METERING_STD,
                "pref_scam_hybrid_bento_frames");
        ShadeTiles.save(prefs, longest);
        prefs.edit().putString("pref_show_afdata_key", "2")
                .putString("pref_scam_hybrid_downsampler", "bilinear").commit();
        layoutFull();
        assertEquals(longest, sheet.pinnedKeys());
        assertEquals("Уменьшение", catalog.entry("pref_scam_hybrid_downsampler").shortTitle.toString());
        RecyclerView grid = grid();
        assertEquals("twelve tiles and «Добавить» (P167: no limit)", 13, grid.getChildCount());
        for (int i = 0; i < grid.getChildCount(); i++) assertTileTextFits((ShadeTileView) grid.getChildAt(i));

        render("shade-ru-360dp.png");
        // The «Для опытных» rows' segments too: one or two lines, never an ellipsis.
        View advanced = sheet.findViewWithTag("shade_advanced");
        if (advanced != null) advanced.performClick();
        layoutFull();
        for (String key : ShadeCatalog.ADVANCED) {
                View row = sheet.findViewWithTag("shade_row_" + key);
                if (row == null) continue;
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
        // Camera controls go through CameraUIController's path. The format choice has seven options: a tap opens its list
        // sheet (icons and names), a pick stores it.
        tile(ShadeCatalog.FORMAT).performClick();
        android.app.Dialog formats = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(formats);
        View rawJpeg = formats.getWindow().getDecorView().findViewWithTag("option_" + com.particlesdevs.photoncamera.settings.FormatChoice.RAW_JPEG.ordinal());
        assertNotNull(rawJpeg);
        rawJpeg.performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList("FORMAT_CHOICE=" + com.particlesdevs.photoncamera.settings.FormatChoice.RAW_JPEG.ordinal()), cameraControls);
        assertTrue(tile(ShadeCatalog.FORMAT).active);
        assertEquals("R+J", tile(ShadeCatalog.FORMAT).value.getText().toString());
        layoutFull();
        render("shade-full-changed.png");
    }

    @Test
    public void unavailableTilesAreDimmedAndExplain() throws Exception {
        layoutFull();
        // «Даунсемплер» acts only with a 12 / 16 / 20 MP output (P129: a stored SCAM HDR route dims nothing any more)
        sheet.togglePin("pref_scam_hybrid_downsampler");
        sheet.refresh();
        layoutFull();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        ShadeTileView down = tile("pref_scam_hybrid_downsampler");
        assertTrue(down.dimmed);
        assertEquals(ShadeStyle.DIMMED, down.card.getAlpha(), 1e-6f);
        String before = prefs.getString("pref_scam_hybrid_downsampler", "lanczos");
        down.performClick();
        assertEquals(before, prefs.getString("pref_scam_hybrid_downsampler", "lanczos"));
        assertEquals(catalog.unavailable(catalog.entry("pref_scam_hybrid_downsampler")), messages.get(messages.size() - 1));
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
        sheet.togglePin("pref_scam_hybrid_dn_luma_mult");
        assertEquals(8, sheet.pinnedKeys().size());
        assertEquals(sheet.pinnedKeys(), ShadeTiles.stored(prefs));
        layoutFull();
        View card = sheet.findViewWithTag("shade_slider_card");
        assertEquals(View.GONE, card.getVisibility());
        tile("pref_scam_hybrid_dn_luma_mult").performClick();
        layoutFull();
        assertEquals(View.VISIBLE, card.getVisibility());
        render("shade-full-slider.png");
        tile("pref_scam_hybrid_dn_luma_mult").performClick();
        layoutFull();
        assertEquals(View.GONE, card.getVisibility());
        for (String key : new String[]{"pref_scam_hybrid_shasta", "pref_scam_hybrid_cdm", "pref_scam_hybrid_zsl_frames",
                "pref_scam_hybrid_downsampler"}) sheet.togglePin(key);
        assertEquals(12, sheet.pinnedKeys().size());
        layoutFull();
        // P167: no limit - «Добавить» stays at 12 and a 13th tile is pinned
        assertNotNull("«Добавить» at 12 too", addTile());
        sheet.togglePin("pref_show_afdata_key");
        assertEquals(13, sheet.pinnedKeys().size());
        layoutFull();
        assertNotNull(tile("pref_show_afdata_key"));
        assertNotNull(addTile());
        render("shade-full-thirteen.png");
        sheet.togglePin("pref_scam_hybrid_cdm");
        layoutFull();
        assertEquals(12, sheet.pinnedKeys().size());
        assertNotNull(addTile());
    }

    /** P169 / P177: the own tone curve pinned to the shade: its tile opens the curve graph in the card, a shortcut stores it. */
    @Test
    public void ownCurveTileOpensItsGraphInTheCard() throws Exception {
        String key = com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurvePreference.KEY;
        prefs.edit().putBoolean("pref_scam_hybrid_why_curve_on", true).commit();
        layoutFull();
        sheet.togglePin(key);
        layoutFull();
        ShadeTileView t = tile(key);
        assertNotNull("the curve has a tile", t);
        t.performClick();
        layoutFull();
        View card = sheet.findViewWithTag("shade_slider_card");
        assertEquals(View.VISIBLE, card.getVisibility());
        assertNotNull(sheet.findViewWithTag("shade_curve_view"));
        assertEquals("no slider bar for the curve", View.GONE, sheet.findViewWithTag("shade_slider_card_bar").getVisibility());
        assertNotNull("«Сравнить» in the card", sheet.findViewWithTag("tone_curve_compare"));
        sheet.findViewWithTag("shade_curve_identity").performClick();
        assertEquals(com.particlesdevs.photoncamera.processing.color.ToneCurve.identity().serialize(), prefs.getString(key, ""));
        // P178: a step back stores the default again (P405: the reference config's curve), a step forth the straight line
        sheet.findViewWithTag("tone_curve_undo").performClick();
        assertEquals(com.particlesdevs.photoncamera.processing.color.ToneCurve.SETTING_POINTS, prefs.getString(key, ""));
        sheet.findViewWithTag("tone_curve_redo").performClick();
        assertEquals(com.particlesdevs.photoncamera.processing.color.ToneCurve.identity().serialize(), prefs.getString(key, ""));
        sheet.findViewWithTag("tone_curve_compare_AgX").performClick();
        layoutFull();
        // the list scrolled to the card, as the owner sees it after a tap
        View scroll = sheet.findViewById(R.id.settings_bar_scroll_view);
        int top = 0;
        for (View v = card; v != scroll; v = (View) v.getParent()) top += v.getTop();
        scroll.scrollTo(0, top - dp(12));
        render("shade-curve.png");
    }

    /**
     * P176: at 16:9 the HIDDEN handle is a pill; the sheet is in place at once and grows out of the pill, shrinks back into
     * it when hidden; outside 16:9 the handle is the full-width edge and the sheet slides as before (the camera screen at
     * 16:9: Shade169Test).
     */
    @Test
    public void at169TheShadeGrowsOutOfThePillAndBack() throws Exception {
        android.os.Looper main = android.os.Looper.getMainLooper();
        CoordinatorLayout coordinator = (CoordinatorLayout) sheet.getParent();
        FrameLayout slot = new FrameLayout(activity);
        android.widget.ImageView handle = new android.widget.ImageView(activity);
        handle.setImageResource(R.drawable.sheet_handle);
        handle.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        slot.addView(handle, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        CoordinatorLayout.LayoutParams sp = new CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        sp.gravity = android.view.Gravity.BOTTOM;
        sp.bottomMargin = dp(20);
        coordinator.addView(slot, sp);
        sheet.setHiddenHandle(handle, null);
        sheet.setSheetLevel(SettingsBarLayout.LEVEL_HIDDEN);
        Shadows.shadowOf(main).idleFor(java.time.Duration.ofSeconds(1));
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, handle.getLayoutParams().width);
        sheet.setPillHandle(true);
        Shadows.shadowOf(main).idleFor(java.time.Duration.ofMillis(100));
        assertEquals("a pill", dp(SettingsBarLayout.PILL_WIDTH_DP), handle.getWidth());
        assertEquals(View.VISIBLE, handle.getVisibility());
        // open: in place at once, scaled down onto the pill, then grown to its size
        sheet.setSheetLevel(SettingsBarLayout.LEVEL_PEEK);   // the first open builds the tiles: waits for that layout
        BottomSheetBehavior<SettingsBarLayout> behavior = BottomSheetBehavior.from(sheet);
        Shadows.shadowOf(main).idleFor(java.time.Duration.ofMillis(20));
        assertEquals("in place, no slide", BottomSheetBehavior.STATE_COLLAPSED, behavior.getState());
        assertEquals("one zoom", 1, sheet.zoomRuns);
        // (the frames themselves: ShadeZoomTest; Robolectric runs an animator started in a pre-draw to its end at once)
        assertEquals("from the pill's width", dp(SettingsBarLayout.PILL_WIDTH_DP) / (float) sheet.getWidth(), sheet.zoomStartScale, 0.02f);
        Shadows.shadowOf(main).idleFor(java.time.Duration.ofMillis(600));
        assertEquals(1f, sheet.getScaleX(), 0f);
        assertEquals(0f, sheet.getTranslationY(), 0f);
        assertEquals(1f, sheet.getAlpha(), 0f);
        for (int i = 0; i < sheet.getChildCount(); i++) assertEquals("the content's alpha back", 1f, sheet.getChildAt(i).getAlpha(), 0f);
        // P185: in place at 16:9 the sheet is clipped to its visible part, rounded at the bottom like the top
        assertTrue("its shape clips it", sheet.getClipToOutline());
        android.graphics.Outline outline = new android.graphics.Outline();
        sheet.getOutlineProvider().getOutline(sheet, outline);
        android.graphics.Rect shapeRect = new android.graphics.Rect();
        assertTrue(outline.getRect(shapeRect));
        assertEquals("rounded", dp(26), outline.getRadius(), 1f);
        assertEquals("down to its container's bottom", coordinator.getHeight() - sheet.getTop(), shapeRect.bottom);
        assertEquals(SettingsBarLayout.LEVEL_PEEK, sheet.getSheetLevel());
        assertEquals(View.GONE, handle.getVisibility());
        // close: shrinks into the pill, then hidden; the pill comes back
        sheet.setSheetLevel(SettingsBarLayout.LEVEL_HIDDEN);
        Shadows.shadowOf(main).idleFor(java.time.Duration.ofMillis(600));
        assertEquals("a zoom back into the pill", 2, sheet.zoomRuns);
        assertEquals(BottomSheetBehavior.STATE_HIDDEN, behavior.getState());
        assertEquals(SettingsBarLayout.LEVEL_HIDDEN, sheet.getSheetLevel());
        assertEquals(1f, sheet.getScaleX(), 0f);
        assertEquals(View.VISIBLE, handle.getVisibility());
        // outside 16:9: the edge again, and the usual slide (no scale)
        sheet.setPillHandle(false);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, handle.getLayoutParams().width);
        assertTrue("no shape clip outside 16:9", !sheet.getClipToOutline());
        sheet.setSheetLevel(SettingsBarLayout.LEVEL_PEEK);
        Shadows.shadowOf(main).idleFor(java.time.Duration.ofSeconds(1));
        assertEquals("slides as before", 2, sheet.zoomRuns);
        assertEquals(1f, sheet.getScaleX(), 0f);
        assertEquals(SettingsBarLayout.LEVEL_PEEK, sheet.getSheetLevel());
    }

    /**
     * Audit 2026-10-27 (settings-reset-ignores-device-defaults): a double tap on a shade slider puts this phone's factory value
     * back (Redmi Note 11 Pro: RL 3 at 1.5), not the XML default (1).
     */
    @Test
    public void aSliderDoubleTapPutsThePhonesDefaultBack() {
        final String rl3 = "pref_scam_hybrid_why_sharp_rl3_amount";
        org.robolectric.shadows.ShadowBuild.setManufacturer("Xiaomi");
        org.robolectric.shadows.ShadowBuild.setDevice("viva");
        final ShadeCatalog note = new ShadeCatalog(activity, prefs);
        final List<Object> picks = new ArrayList<>();
        final ShadeRows rows = new ShadeRows(activity, note, new ShadeRows.Actions() {
            @Override public void pick(ShadeCatalog.Entry entry, Object value) {
                if (rl3.equals(entry.key)) picks.add(value);
            }
            @Override public void togglePin(String key) {}
            @Override public boolean pinned(String key) { return false; }
            @Override public void openList(ShadeCatalog.Entry entry) {}
            @Override public void blocked(String reason) {}
        });
        final android.widget.LinearLayout into = new android.widget.LinearLayout(activity);
        into.setOrientation(android.widget.LinearLayout.VERTICAL);
        rows.build(into, java.util.Collections.emptyList(), java.util.Collections.singletonList(rl3));
        final View card = rows.rowView(rl3);
        assertNotNull(card);
        final android.widget.SeekBar bar = seekBar(card);
        assertNotNull(bar);
        into.measure(View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dp(2000), View.MeasureSpec.AT_MOST));
        into.layout(0, 0, dp(360), into.getMeasuredHeight());
        final long t = android.os.SystemClock.uptimeMillis();
        final float y = bar.getHeight() / 2f;
        tap(bar, t, dp(60), y);
        tap(bar, t + 120, dp(60) + 2, y);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(picks.isEmpty());
        assertEquals("the phone's factory value", 1.5f, ((Number) picks.get(picks.size() - 1)).floatValue(), 1e-6f);
    }

    private static android.widget.SeekBar seekBar(View v) {
        if (v instanceof android.widget.SeekBar) return (android.widget.SeekBar) v;
        if (!(v instanceof ViewGroup)) return null;
        for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            final android.widget.SeekBar b = seekBar(((ViewGroup) v).getChildAt(i));
            if (b != null) return b;
        }
        return null;
    }

    private static void tap(View v, long t, float x, float y) {
        final android.view.MotionEvent down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        v.dispatchTouchEvent(down);
        down.recycle();
        final android.view.MotionEvent up = android.view.MotionEvent.obtain(t, t + 40, android.view.MotionEvent.ACTION_UP, x, y, 0);
        v.dispatchTouchEvent(up);
        up.recycle();
    }

    /**
     * P270 (owner's video 2026-10-19): a sheet whose tiles fill it has PEEK a few px under FULL; the scrim followed that travel
     * and pulsed on every small re-layout. With at most 24 dp of travel there is no scrim; a real travel keeps it.
     */
    @Test
    public void noScrimWithoutTravel() {
        assertTrue(SettingsBarLayout.scrimTravelTiny(1990, 2000, 48));
        assertTrue(SettingsBarLayout.scrimTravelTiny(2000, 2000, 48));
        assertFalse(SettingsBarLayout.scrimTravelTiny(1200, 2000, 48));
        assertFalse("before the first layout", SettingsBarLayout.scrimTravelTiny(0, 2000, 48));
    }
}
