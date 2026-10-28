package com.particlesdevs.photoncamera.ui.settings;

import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityOptionsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.ListPreference;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.FileNamePattern;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLab;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.ModuleSensorSettings;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.BackupPreferences;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.RestorePreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunablePngPreference;
import com.particlesdevs.photoncamera.util.ConfigFolder;
import com.particlesdevs.photoncamera.util.ScameraFiles;

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
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * The settings' alert dialogs after their move to DialogStyle (owner, 2026-10-28: one look for every dialog): each still shows
 * its texts and its buttons do what they did - the page reset, the module reset, the module page's name / zoom / Camera ID /
 * copy / crop dialogs, the settings copy, «Название фото», the PNG picker and the lab's dialogs.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class SettingsDialogsTest {
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
        // two modules: 1× on camera 3, 3× on camera 5
        prefs.edit().putString("module_auto_back0", "3").putString("module_auto_back1", "5")
                .putString("module_label_back0", "1×").putString("module_label_back1", "3×")
                .putBoolean("module_visible_back0", true).putBoolean("module_visible_back1", true)
                .putString("module_active", "back0").commit();
        // the hidden filler slots a copy of a module takes
        for (int i = 2; i < 8; i++) prefs.edit().putString("module_auto_back" + i, "5").putString("module_label_back" + i, "3×")
                .putBoolean("module_visible_back" + i, false).commit();
    }

    @After public void tearDown() {
        TunablePngPreference.setImportLauncher(null);
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

    private static SettingsActivity.SettingsFragment page(SettingsActivity activity, String key) {
        SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) top(activity);
        activity.onPreferenceStartScreen(root, root.findPreference(key));
        return (SettingsActivity.SettingsFragment) top(activity);
    }

    private static AlertDialog latest() {
        android.app.Dialog d = ShadowDialog.getLatestDialog();
        assertTrue(String.valueOf(d), d instanceof AlertDialog);
        assertTrue(d.isShowing());
        return (AlertDialog) d;
    }

    private static String title(AlertDialog d) {
        TextView t = d.findViewById(androidx.appcompat.R.id.alertTitle);
        return t == null || !t.isShown() ? null : t.getText().toString();
    }

    private static String message(AlertDialog d) {
        // the dialog's own message, or a custom view's (a preference dialog's layout has one too)
        for (TextView t : views(d.getWindow().getDecorView(), TextView.class, new ArrayList<>()))
            if (t.getId() == android.R.id.message && t.isShown()) return t.getText().toString();
        return null;
    }

    private static String button(AlertDialog d, int which) {
        return d.getButton(which).getText().toString();
    }

    private static void click(AlertDialog d, int which) {
        d.getButton(which).performClick();
        idle();
    }

    private static <T extends View> List<T> views(View v, Class<T> type, List<T> out) {
        if (type.isInstance(v)) out.add(type.cast(v));
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) views(((ViewGroup) v).getChildAt(i), type, out);
        return out;
    }

    private static <T extends View> T first(AlertDialog d, Class<T> type) {
        List<T> all = views(d.getWindow().getDecorView(), type, new ArrayList<>());
        assertFalse("a " + type.getSimpleName() + " in the dialog", all.isEmpty());
        return all.get(0);
    }

    private static void pick(AlertDialog d, int position) {
        ListView list = d.getListView();
        list.performItemClick(null, position, position);
        idle();
    }

    // ───── SettingsActivity ─────

    @Test public void thePageResetAsksAndResetsOnlyOnConfirm() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity activity = controller.setup().get();
            SettingsActivity.SettingsFragment page = page(activity, "photo_style_screen");
            ListPreference mono = page.findPreference("pref_scam_hybrid_colour_mono");
            mono.setValue("neutral");
            View reset = page.requireView().findViewWithTag("settings_page_reset");
            reset.performClick();
            idle();
            AlertDialog d = latest();
            String name = String.valueOf(page.getPreferenceScreen().getTitle());
            assertEquals("Reset this page?", title(d));
            assertEquals("Every setting of «" + name + "» goes back to its default. Pages inside it stay as they are.", message(d));
            assertEquals("Reset", button(d, DialogInterface.BUTTON_POSITIVE));
            assertEquals("Cancel", button(d, DialogInterface.BUTTON_NEGATIVE));
            click(d, DialogInterface.BUTTON_NEGATIVE);
            assertFalse(d.isShowing());
            assertEquals("cancel changes nothing", "neutral", mono.getValue());
            assertNull(ShadowToast.getTextOfLatestToast());

            reset.performClick();
            idle();
            click(latest(), DialogInterface.BUTTON_POSITIVE);
            assertEquals("off", mono.getValue());
            assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().startsWith("Settings reset: "));
            reset.performClick();
            idle();
            click(latest(), DialogInterface.BUTTON_POSITIVE);
            assertEquals("Everything is at its default already", ShadowToast.getTextOfLatestToast());
        }
    }

    /** The root page's reset opens «Сбросить настройки» (the preference library's dialog, in the app's theme). */
    @Test public void theRootResetOpensTheFullResetDialog() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity activity = controller.setup().get();
            Fragment root = top(activity);
            root.requireView().findViewWithTag("settings_page_reset").performClick();
            top(activity);
            AlertDialog d = latest();
            assertEquals(activity.getString(R.string.reset_preferences_warning), message(d));
            assertEquals(activity.getString(R.string.yes), button(d, DialogInterface.BUTTON_POSITIVE));
            assertFalse(d.getButton(DialogInterface.BUTTON_POSITIVE).isAllCaps());
            click(d, DialogInterface.BUTTON_NEGATIVE);
            assertFalse(d.isShowing());
        }
    }

    /** «Сохранить текущие как…» and «Загрузить из файла» keep the preference library's dialogs, in the app's theme and field. */
    @Test public void theBackupAndRestoreDialogsKeepTheirTextsAndCallbacks() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity activity = controller.setup().get();
            SettingsActivity.SettingsFragment page = page(activity, "backup_settings_screen");
            final List<Object> changes = new ArrayList<>();
            BackupPreferences backup = page.findPreference(activity.getString(R.string.pref_backup_preferences_key));
            backup.setOnPreferenceChangeListener((p, v) -> { changes.add(v); return false; });
            backup.performClick();
            top(activity);
            AlertDialog d = latest();
            assertEquals(String.valueOf(backup.getDialogTitle() != null ? backup.getDialogTitle() : backup.getTitle()), title(d));
            assertEquals("All settings and module profiles are saved to an XML file in the folder " + ConfigFolder.RELATIVE, message(d));
            EditText name = d.findViewById(android.R.id.edit);
            assertTrue(name.getText().toString(), name.getText().toString().matches(".*\\d{8}_\\d{6}.*"));
            assertTrue("the app's field", name.getBackground() instanceof android.graphics.drawable.GradientDrawable);
            assertEquals(SettingsStyle.TEXT, name.getCurrentTextColor());
            name.setText("night");
            click(d, DialogInterface.BUTTON_POSITIVE);
            assertEquals(Arrays.asList("night"), changes);

            changes.clear();
            RestorePreference restore = page.findPreference(activity.getString(R.string.pref_restore_preferences_key));
            restore.setOnPreferenceChangeListener((p, v) -> { changes.add(v); return false; });
            restore.performClick();
            top(activity);
            d = latest();
            assertEquals(String.valueOf(restore.getDialogTitle() != null ? restore.getDialogTitle() : restore.getTitle()), title(d));
            assertEquals("Choose a file…", d.getListView().getAdapter().getItem(0).toString());
            pick(d, 0);
            assertEquals(Arrays.asList(RestorePreference.PICK), changes);
        }
    }

    @Test public void theModuleResetAsksFirst() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class);
             MockedStatic<ModuleSensorSettings> sensor = mockStatic(ModuleSensorSettings.class, CALLS_REAL_METHODS)) {
            SettingsActivity activity = controller.setup().get();
            SettingsActivity.SettingsFragment page = SettingsActivity.SettingsFragment.sensorPage("back1");
            activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings_container, page).commitNow();
            idle();
            sensor.when(() -> ModuleSensorSettings.reset(anyString())).thenAnswer(inv -> null);
            sensor.clearInvocations();
            page.findPreference("pref_reset_sensor_config_settings").performClick();
            idle();
            AlertDialog d = latest();
            assertEquals("Reset the module settings?", title(d));
            assertEquals("3× · ID 5", message(d));
            assertEquals("Cancel", button(d, DialogInterface.BUTTON_NEGATIVE));
            click(d, DialogInterface.BUTTON_NEGATIVE);
            sensor.verify(() -> ModuleSensorSettings.reset(anyString()), never());
            page.findPreference("pref_reset_sensor_config_settings").performClick();
            idle();
            d = latest();
            assertEquals("Reset", button(d, DialogInterface.BUTTON_POSITIVE));
            click(d, DialogInterface.BUTTON_POSITIVE);
            sensor.verify(() -> ModuleSensorSettings.reset("back1"));
        }
    }

    // ───── The module page (ModuleLensFragment, ModuleConceptFragment) ─────

    @Test public void theModulePageDialogsKeepTheirTextsAndActions() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity activity = controller.setup().get();
            FragmentManager fm = activity.getSupportFragmentManager();
            ModuleLensFragment page = ModuleLensFragment.module("back0");
            fm.beginTransaction().replace(R.id.settings_container, page).commitNow();
            idle();

            // name
            page.requireView().findViewWithTag("Name").performClick();
            idle();
            AlertDialog d = latest();
            assertEquals("Module name", title(d));
            EditText name = first(d, EditText.class);
            assertEquals("1×", name.getText().toString());
            assertEquals("Save", button(d, DialogInterface.BUTTON_POSITIVE));
            name.setText("Wide");
            click(d, DialogInterface.BUTTON_NEGATIVE);
            assertEquals("", prefs.getString("module_name_back0", ""));
            page.requireView().findViewWithTag("Name").performClick();
            idle();
            d = latest();
            first(d, EditText.class).setText("Wide");
            click(d, DialogInterface.BUTTON_POSITIVE);
            assertEquals("Wide", prefs.getString("module_name_back0", ""));

            // zoom and sensor crop
            page.requireView().findViewWithTag("Zoom and sensor crop").performClick();
            idle();
            d = latest();
            assertEquals("Zoom · Wide · ID 3", title(d));
            EditText ratio = first(d, EditText.class);
            assertEquals("1", ratio.getText().toString());
            assertEquals("For example: 0.6, 1, 3, 6", ratio.getHint().toString());
            CheckBox crop = first(d, CheckBox.class);
            assertEquals("Sensor crop (the sensor mode already crops the frame)", crop.getText().toString());
            assertEquals("Auto", button(d, DialogInterface.BUTTON_NEUTRAL));
            ratio.setText("1,5");
            crop.setChecked(true);
            click(d, DialogInterface.BUTTON_POSITIVE);
            assertEquals("1.5", prefs.getString("module_zoom_back0", ""));
            assertTrue(prefs.getBoolean("module_sensorcrop_back0", false));
            page.requireView().findViewWithTag("Zoom and sensor crop").performClick();
            idle();
            click(latest(), DialogInterface.BUTTON_NEUTRAL);
            assertFalse(prefs.contains("module_zoom_back0"));
            assertFalse(prefs.contains("module_sensorcrop_back0"));

            // Camera ID: the list, then a typed one
            page.requireView().findViewWithTag("Camera ID").performClick();
            idle();
            d = latest();
            assertEquals("Camera ID · Wide", title(d));
            assertEquals("Auto", d.getListView().getAdapter().getItem(0).toString());
            assertEquals("Enter Camera ID manually", d.getListView().getAdapter().getItem(1).toString());
            pick(d, 1);
            assertFalse(d.isShowing());
            AlertDialog manual = latest();
            assertEquals("Manual Camera ID", title(manual));
            EditText id = first(manual, EditText.class);
            assertEquals("For example: 3", id.getHint().toString());
            id.setText("7");
            click(manual, DialogInterface.BUTTON_POSITIVE);
            assertEquals("7", prefs.getString("module_id_back0", ""));
            assertTrue(prefs.getStringSet("user_camera_ids", null).contains("7"));
            page.requireView().findViewWithTag("Camera ID").performClick();
            idle();
            pick(latest(), 0);
            assertEquals("auto", "", prefs.getString("module_id_back0", "x"));
        }
    }

    @Test public void aCopyOfAModuleIsCroppedAndDeletedThroughItsDialogs() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity activity = controller.setup().get();
            FragmentManager fm = activity.getSupportFragmentManager();
            String copy = ModuleRegistry.duplicate("back0", 1.3f);
            assertNotNull(copy);
            ModuleLensFragment page = ModuleLensFragment.module(copy);
            fm.beginTransaction().replace(R.id.settings_container, page).commitNow();
            idle();

            // the crop of the copy, typed
            page.requireView().findViewWithTag("Copy crop").performClick();
            idle();
            BottomSheetDialog sheet = (BottomSheetDialog) ShadowDialog.getLatestDialog();
            sheet.findViewById(android.R.id.content).findViewWithTag("option_crop_custom").performClick();
            idle();
            AlertDialog d = latest();
            assertEquals("Copy crop", title(d));
            EditText value = (EditText) d.getWindow().getDecorView().findViewWithTag("crop_value");
            assertEquals("1.3", value.getText().toString());
            assertEquals("1 to 10, e.g. 1.3", value.getHint().toString());
            value.setText("1.4");
            click(d, DialogInterface.BUTTON_NEGATIVE);
            assertEquals(1.3f, ModuleRegistry.crop(copy), 1e-4f);
            page.requireView().findViewWithTag("Copy crop").performClick();
            idle();
            ((BottomSheetDialog) ShadowDialog.getLatestDialog()).findViewById(android.R.id.content).findViewWithTag("option_crop_custom").performClick();
            idle();
            d = latest();
            ((EditText) d.getWindow().getDecorView().findViewWithTag("crop_value")).setText("1.4");
            click(d, DialogInterface.BUTTON_POSITIVE);
            assertEquals(1.4f, ModuleRegistry.crop(copy), 1e-4f);

            // delete it
            page = (ModuleLensFragment) top(activity);
            page.requireView().findViewWithTag("Delete copy").performClick();
            idle();
            d = latest();
            assertEquals("Delete “" + ModuleRegistry.label(copy) + "”?", title(d));
            assertEquals("Delete", button(d, DialogInterface.BUTTON_POSITIVE));
            assertEquals("Cancel", button(d, DialogInterface.BUTTON_NEGATIVE));
            click(d, DialogInterface.BUTTON_NEGATIVE);
            assertEquals("back0", ModuleRegistry.duplicateOf(copy));
            page.requireView().findViewWithTag("Delete copy").performClick();
            idle();
            click(latest(), DialogInterface.BUTTON_POSITIVE);
            assertNull("the copy is gone", ModuleRegistry.duplicateOf(copy));
        }
    }

    @Test public void theSettingsCopyPicksItsSourceAndConfirms() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity activity = controller.setup().get();
            ModuleCopyFragment copy = new ModuleCopyFragment();
            activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings_container, copy).commitNow();
            idle();
            copy.requireView().findViewWithTag("source_module").performClick();
            idle();
            AlertDialog d = latest();
            assertEquals("Source", title(d));
            int count = d.getListView().getAdapter().getCount();
            assertTrue(String.valueOf(count), count >= 2);
            String second = d.getListView().getAdapter().getItem(1).toString();
            pick(d, 1);
            assertFalse(d.isShowing());
            List<TextView> texts = views(copy.requireView().findViewWithTag("source_module"), TextView.class, new ArrayList<>());
            assertTrue(texts.stream().anyMatch(t -> t.getText().toString().equals("Source: " + second)));

            copy.requireView().findViewWithTag("primary_action").performClick();
            idle();
            d = latest();
            assertNull(title(d));
            assertEquals("The selected settings are copied", message(d));
            assertEquals("Done", button(d, DialogInterface.BUTTON_POSITIVE));
            click(d, DialogInterface.BUTTON_POSITIVE);
            assertFalse(d.isShowing());
        }
    }

    // ───── «Название фото» ─────

    @Test public void theFileNameDialogResetsKeepsOpenAndSavesOnlyOnSave() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity activity = controller.setup().get();
            PreferenceKeys.setFileNamePattern("IMG_yyyy");
            final int[] saved = {0};
            FileNameDialog.show(activity, () -> saved[0]++);
            idle();
            AlertDialog d = latest();
            assertEquals("Photo file name", title(d));
            EditText field = first(d, EditText.class);
            assertEquals("IMG_yyyy", field.getText().toString());
            assertEquals("Reset", button(d, DialogInterface.BUTTON_NEUTRAL));
            assertEquals("Cancel", button(d, DialogInterface.BUTTON_NEGATIVE));
            assertEquals("Save", button(d, DialogInterface.BUTTON_POSITIVE));
            click(d, DialogInterface.BUTTON_NEUTRAL);
            assertTrue("«Reset» keeps the dialog open", d.isShowing());
            assertEquals(FileNamePattern.DEFAULT, field.getText().toString());
            click(d, DialogInterface.BUTTON_NEGATIVE);
            assertFalse(d.isShowing());
            assertEquals("IMG_yyyy", PreferenceKeys.getFileNamePattern());
            assertEquals(0, saved[0]);

            FileNameDialog.show(activity, () -> saved[0]++);
            idle();
            d = latest();
            first(d, EditText.class).setText("PIC_yyyyMMdd");
            click(d, DialogInterface.BUTTON_POSITIVE);
            assertEquals(FileNamePattern.clean("PIC_yyyyMMdd"), PreferenceKeys.getFileNamePattern());
            assertEquals(1, saved[0]);
        }
    }

    // ───── TunablePngPreference ─────

    @Test public void thePngPickerPicksImportsAndRemoves() throws Exception {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity activity = controller.setup().get();
            SettingsActivity.SettingsFragment page = page(activity, "interface_settings_screen");
            File dir = new File(activity.getFilesDir(), "tunable_pngs");
            dir.mkdirs();
            for (String n : new String[]{"a.png", "b.png"}) new File(dir, n).createNewFile();
            final List<String[]> launched = new ArrayList<>();
            TunablePngPreference.setImportLauncher(new ActivityResultLauncher<String[]>() {
                @Override public void launch(String[] input, ActivityOptionsCompat options) { launched.add(input); }
                @Override public void unregister() {}
                @Override public ActivityResultContract<String[], ?> getContract() { return null; }
            });
            TunablePngPreference png = new TunablePngPreference(page.getPreferenceManager().getContext());
            png.setKey("test_png");
            png.setTitle("Lens mask");
            page.getPreferenceScreen().addPreference(png);

            png.performClick();
            idle();
            AlertDialog d = latest();
            assertEquals("Lens mask", title(d));
            List<String> items = new ArrayList<>();
            for (int i = 0; i < d.getListView().getAdapter().getCount(); i++) items.add(d.getListView().getAdapter().getItem(i).toString());
            assertEquals("None", items.get(0));
            assertTrue(items.toString(), items.containsAll(Arrays.asList("a.png", "b.png")));
            assertEquals("Import new PNG", button(d, DialogInterface.BUTTON_NEUTRAL));
            assertEquals("Cancel", button(d, DialogInterface.BUTTON_NEGATIVE));
            pick(d, items.indexOf("a.png"));
            assertFalse(d.isShowing());
            assertEquals("a.png", png.getSharedPreferences().getString("test_png", ""));

            png.performClick();
            idle();
            d = latest();
            assertEquals(items.indexOf("a.png"), d.getListView().getCheckedItemPosition());
            click(d, DialogInterface.BUTTON_NEUTRAL);
            assertEquals(1, launched.size());
            assertArrayEquals(new String[]{"image/png"}, launched.get(0));

            png.performClick();
            idle();
            d = latest();
            ListView list = d.getListView();
            int a = items.indexOf("a.png");
            list.getOnItemLongClickListener().onItemLongClick(list, null, a, a);
            idle();
            AlertDialog remove = latest();
            assertEquals("Remove PNG", title(remove));
            assertEquals("Remove a.png?", message(remove));
            assertEquals("Remove", button(remove, DialogInterface.BUTTON_POSITIVE));
            click(remove, DialogInterface.BUTTON_NEGATIVE);
            assertTrue(new File(dir, "a.png").exists());
            list.getOnItemLongClickListener().onItemLongClick(list, null, a, a);
            idle();
            click(latest(), DialogInterface.BUTTON_POSITIVE);
            assertFalse(new File(dir, "a.png").exists());
            assertEquals("the removed PNG was the chosen one", "", png.getSharedPreferences().getString("test_png", ""));
            assertEquals("None selected", String.valueOf(png.getSummary()));
            AlertDialog again = latest();
            assertNotSame("the picker opens again without it", d, again);
            assertEquals(items.size() - 1, again.getListView().getAdapter().getCount());
        }
    }

    // ───── The lab ─────

    @Test public void theLabAsksBeforeDeletingAShotAndSaysWhyASeriesDoesNotOpen() throws Exception {
        File shot = new File(ScamLab.root(), "2026-10-19_15-30-12");
        assertTrue(shot.mkdirs() || shot.isDirectory());
        Files.write(new File(shot, ScamLab.BURST).toPath(), new byte[256]);
        Files.write(new File(shot, ScamLab.META).toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        // a kept series of the old kind: no sidecar, and no lab shot of its size to take the parameters from
        File kept = new File(ScameraFiles.dir(), "scam_burst-20261019_153012.nch");
        ByteBuffer h = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0, 0x3143484e).putInt(4, 11).putInt(8, 64).putInt(12, 48).putInt(16, 0).putInt(20, 2).putInt(52, 1);
        try (FileOutputStream out = new FileOutputStream(kept)) { out.write(h.array()); }
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity activity = controller.setup().get();
            ScamLabFragment lab = ScamLabFragment.list();
            activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings_container, lab).commitNow();
            idle();
            View row = lab.requireView().findViewWithTag("lab_shot_" + shot.getName());
            assertNotNull(row);
            row.performLongClick();
            idle();
            AlertDialog d = latest();
            assertEquals("Delete the shot?", title(d));
            assertEquals("19.10.2026 15:30:12\nThe burst, parameters and preview are deleted from Download/SCAMERA/Lab.", message(d));
            assertEquals("Delete", button(d, DialogInterface.BUTTON_POSITIVE));
            assertEquals("Cancel", button(d, DialogInterface.BUTTON_NEGATIVE));
            click(d, DialogInterface.BUTTON_NEGATIVE);
            assertTrue(shot.isDirectory());
            row.performLongClick();
            idle();
            click(latest(), DialogInterface.BUTTON_POSITIVE);
            assertFalse("deleted", shot.exists());

            View series = lab.requireView().findViewWithTag("lab_series_" + kept.getName());
            assertNotNull(series);
            series.performClick();
            AlertDialog error = null;
            for (int i = 0; i < 500 && error == null; i++) {
                idle();
                android.app.Dialog last = ShadowDialog.getLatestDialog();
                if (last instanceof AlertDialog && last.isShowing()) error = (AlertDialog) last;
                else Thread.sleep(10);
            }
            assertNotNull("the import's error", error);
            assertEquals("The series cannot be opened", title(error));
            assertTrue(message(error), message(error).startsWith("The series was kept without its shot's parameters"));
            assertEquals(activity.getString(android.R.string.ok), button(error, DialogInterface.BUTTON_POSITIVE));
            click(error, DialogInterface.BUTTON_POSITIVE);
            assertFalse(error.isShowing());
        } finally {
            kept.delete();
        }
    }
}
