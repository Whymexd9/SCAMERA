package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.FullResTrials;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * P384 (owner, 2026-10-28: «Переместить эту группу настроек на главный экран»): the dialogs and writes of the module rows on the
 * settings' main page (ModuleRows), moved from the module pages they lived on - ModuleLensFragment's name, zoom and Camera ID
 * (P21), ModuleConceptFragment's crop sheet and copy with a crop (P148), ModuleSettingsFragment's «add a copy», and
 * ModuleProfilesFragment's per-module switch question (P193), profile pages and FULLRES trials (P189, P214h). Every key and value
 * they write is the same as before: module_name_, module_zoom_, module_sensorcrop_, module_crop_, module_visible_, module_id_,
 * user_camera_ids, hidden_camera_ids, pref_save_per_lens_settings.
 */
public final class ModuleActions {
    private ModuleActions() {}

    private static SharedPreferences prefs() {
        return ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
    }

    // ───── P21: name, zoom, Camera ID ─────

    /** «Название модуля»: the module's own name (empty = its automatic label). */
    static void rename(Context c, String slot, Runnable done) {
        EditText input = DialogStyle.field(new EditText(c));
        input.setSingleLine(true);
        input.setText(ModuleRegistry.label(slot));
        DialogStyle.builder(c).setTitle(Lang.t(c, "Название модуля", "Module name")).setView(DialogStyle.body(c, input))
                .setPositiveButton(Lang.t(c, "Сохранить", "Save"), (d, w) -> {
                    prefs().edit().putString("module_name_" + slot, input.getText().toString().trim()).apply();
                    done.run();
                })
                .setNegativeButton(Lang.t(c, "Отмена", "Cancel"), null).show();
    }

    /** Zoom ratio of the module button and whether its frame is already cropped on the sensor; «Авто» forgets both. */
    static void zoomDialog(Context c, String slot, Runnable done) {
        EditText ratio = DialogStyle.field(new EditText(c));
        ratio.setSingleLine(true);
        ratio.setHint(Lang.t(c, "Например: 0.6, 1, 3, 6", "For example: 0.6, 1, 3, 6"));
        ratio.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        ratio.setText(String.format(Locale.US, "%.2f", ModuleRegistry.zoom(slot)).replaceAll("\\.?0+$", ""));
        CheckBox crop = DialogStyle.check(new CheckBox(c));
        crop.setText(Lang.t(c, "Кроп на сенсоре (кадр уже обрезан режимом сенсора)", "Sensor crop (the sensor mode already crops the frame)"));
        crop.setChecked(ModuleRegistry.sensorCrop(slot));
        LinearLayout box = DialogStyle.body(c, ratio, crop);
        DialogStyle.builder(c).setTitle(Lang.t(c, "Зум · ", "Zoom · ") + ModuleRegistry.label(slot) + " · "
                        + c.getString(R.string.module_id_value, ModuleRegistry.camera(slot))).setView(box)
                .setPositiveButton(Lang.t(c, "Сохранить", "Save"), (d, w) -> {
                    SharedPreferences.Editor e = prefs().edit();
                    String text = ratio.getText().toString().trim().replace(',', '.');
                    if (text.isEmpty()) e.remove("module_zoom_" + slot);
                    else try {
                        Float.parseFloat(text);
                        e.putString("module_zoom_" + slot, text);
                    } catch (NumberFormatException bad) { }
                    e.putBoolean("module_sensorcrop_" + slot, crop.isChecked()).apply();
                    done.run();
                })
                .setNeutralButton(Lang.t(c, "Авто", "Auto"), (d, w) -> {
                    prefs().edit().remove("module_zoom_" + slot).remove("module_sensorcrop_" + slot).apply();
                    done.run();
                })
                .setNegativeButton(Lang.t(c, "Отмена", "Cancel"), null).show();
    }

    /**
     * The module's Camera ID («» = Auto): a bare physical id the camera list leaves out opens through its logical camera
     * ("logical-physical"); a chosen id joins the cameras the app opens (user_camera_ids) and leaves the hidden ones.
     */
    static void assign(Context c, String slot, String id) {
        if (!id.isEmpty() && !id.contains("-") && android.os.Build.VERSION.SDK_INT >= 28) try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            if (!Arrays.asList(cm.getCameraIdList()).contains(id))
                for (String logical : cm.getCameraIdList())
                    if (cm.getCameraCharacteristics(logical).getPhysicalCameraIds().contains(id)) {
                        id = logical + "-" + id;
                        break;
                    }
        } catch (Exception ignored) { }
        SharedPreferences p = prefs();
        Set<String> user = new HashSet<>(p.getStringSet("user_camera_ids", new HashSet<>()));
        if (!id.isEmpty()) user.add(id);
        Set<String> hidden = new HashSet<>(p.getStringSet("hidden_camera_ids", new HashSet<>()));
        hidden.remove(id);
        p.edit().putString("module_id_" + slot, id).putStringSet("user_camera_ids", user).putStringSet("hidden_camera_ids", hidden).apply();
    }

    // ───── P148: copies with a lens crop ─────

    /** The crop presets of a copy (1 = none). */
    static final float[] CROPS = {1f, 1.2f, 1.3f, 1.5f, 2f};

    /** A ratio as the module keys store it and the rows show it: up to three decimals, no trailing zeros («1.3», «2»). */
    public static String cropText(float v) {
        return String.format(Locale.US, "%.3f", v).replaceAll("\\.?0+$", "");
    }

    /** The crop of a copy of {@code source}: the presets (the button's zoom beside each) and a typed value. */
    static void cropSheet(Context c, String source, float current, Consumer<Float> onCrop) {
        float base = ModuleRegistry.zoom(source);
        CharSequence[] labels = new CharSequence[CROPS.length + 1], tags = new CharSequence[CROPS.length + 1];
        int selected = -1;
        for (int i = 0; i < CROPS.length; i++) {
            float k = CROPS[i];
            tags[i] = "crop_" + cropText(k);
            labels[i] = k == 1f ? Lang.t(c, "Без кропа · кнопка " + cropText(base) + "×", "No crop · button " + cropText(base) + "×")
                    : Lang.t(c, "Кроп " + cropText(k) + "× · кнопка " + cropText(base * k) + "×", "Crop " + cropText(k) + "× · button " + cropText(base * k) + "×");
            if (Math.abs(k - current) < 0.001f) selected = i;
        }
        tags[CROPS.length] = "crop_custom";
        labels[CROPS.length] = Lang.t(c, "Своё значение…", "Custom value…");
        String name = ModuleRegistry.label(source);
        SettingsStyle.optionSheet(c, Lang.t(c, "Кроп копии «" + name + "»", "Copy crop of “" + name + "”"), labels, tags, selected,
                SettingsStyle.accent(c), i -> {
                    if (i < CROPS.length) { onCrop.accept(CROPS[i]); return; }
                    EditText input = DialogStyle.field(new EditText(c));
                    input.setSingleLine(true);
                    input.setTag("crop_value");
                    input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                    input.setHint(Lang.t(c, "От 1 до 10, например 1.3", "1 to 10, e.g. 1.3"));
                    input.setText(cropText(current));
                    DialogStyle.builder(c).setTitle(Lang.t(c, "Кроп копии", "Copy crop")).setView(DialogStyle.body(c, input))
                            .setPositiveButton(Lang.t(c, "Сохранить", "Save"), (d, w) -> {
                                try {
                                    float k = Float.parseFloat(input.getText().toString().trim().replace(',', '.'));
                                    if (k >= 1f && k <= 10f) onCrop.accept(k);
                                } catch (NumberFormatException bad) { }
                            }).setNegativeButton(Lang.t(c, "Отмена", "Cancel"), null).show();
                });
    }

    /** «Кроп копии»: the copy's crop of its source changed, its button zoom with it. */
    static void cropCopy(Context c, String copy, Runnable done) {
        String source = ModuleRegistry.duplicateOf(copy);
        if (source == null) return;
        cropSheet(c, source, ModuleRegistry.crop(copy) / ModuleRegistry.crop(source), k -> {
            float base = ModuleRegistry.zoom(source);
            prefs().edit().putString("module_crop_" + copy, cropText(ModuleRegistry.crop(source) * k))
                    .putString("module_zoom_" + copy, cropText(base * k)).apply();
            done.run();
        });
    }

    /**
     * A copy of {@code slot} with {@code crop}: «Создан «1.3×»» and the new slot to {@code created} (the main page opens its card);
     * a message when the side has no free slot.
     */
    static void copyWithCrop(Context c, String slot, float crop, Consumer<String> created) {
        String copy = ModuleRegistry.duplicate(slot, crop);
        if (copy == null) {
            Toast.makeText(c, Lang.t(c, "Нет свободного слота: скройте или удалите другой модуль", "No free slot: hide or delete another module"), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(c, Lang.t(c, "Создан «" + ModuleRegistry.label(copy) + "»", "Created “" + ModuleRegistry.label(copy) + "”"), Toast.LENGTH_SHORT).show();
        created.accept(copy);
    }

    /** «Добавить кнопку с кропом»: which module to copy (the shown ones, labelled as their cards), then its crop. */
    static void addCopy(Context c, List<String> sources, Consumer<String> created) {
        if (sources.isEmpty()) return;
        CharSequence[] labels = new CharSequence[sources.size()], tags = new CharSequence[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            String s = sources.get(i);
            labels[i] = ModuleRows.badge(s) + " · " + ModuleRows.cardTitle(c, s);
            tags[i] = "source_" + s;
        }
        SettingsStyle.optionSheet(c, Lang.t(c, "Какой модуль скопировать", "Which module to copy"), labels, tags, -1,
                SettingsStyle.accent(c), i -> {
                    String source = sources.get(i);
                    cropSheet(c, source, 1.3f, k -> copyWithCrop(c, source, k, created));
                });
    }

    /** «Удалить «X»?»: a copy goes (an original can't be deleted); {@code done} after the delete. */
    static void deleteCopy(Context c, String slot, Runnable done) {
        DialogStyle.builder(c).setTitle(Lang.t(c, "Удалить «" + ModuleRegistry.label(slot) + "»?", "Delete “" + ModuleRegistry.label(slot) + "”?"))
                .setPositiveButton(Lang.t(c, "Удалить", "Delete"), (d, w) -> {
                    ModuleRegistry.remove(slot);
                    done.run();
                })
                .setNegativeButton(Lang.t(c, "Отмена", "Cancel"), null).show();
    }

    // ───── P189 / P193: the modules' own settings ─────

    /** True while {@link #perLensSwitch} writes: the page that asked rebuilds or leaves itself (SettingsFragment skips its restart). */
    static volatile boolean writingPerLens;

    /** «Свои настройки для каждого модуля»: the profiles' listener first (it carries the common settings), then the switch, at once. */
    static void perLensSwitch(boolean on) {
        PreferenceKeys.profiles();
        writingPerLens = true;
        try {
            ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit()
                    .putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue, on).commit();
        } finally {
            writingPerLens = false;
        }
    }

    /** P193: the settings changed in the common ones go to every module when they are turned on (ModuleProfiles.carry). */
    static void askPerLens(Context c, Runnable turnedOn) {
        DialogStyle.builder(c)
                .setTitle(Lang.t(c, "Свои настройки для каждого модуля", "Separate settings for each module"))
                .setMessage(Lang.t(c, "Сейчас все модули и снимки 50 / 200 Мп снимают с общими настройками. Включить свои настройки для каждого модуля? Каждый модуль начнёт с текущих настроек; свои значения модуля вернутся только там, где вы не меняли общие настройки.",
                        "All modules and 50 / 200 MP shots use the common settings now. Turn on separate settings for each module? Every module starts from the current settings; a module's own values come back only where you have not changed the common settings."))
                .setNegativeButton(Lang.t(c, "Отмена", "Cancel"), null)
                .setPositiveButton(Lang.t(c, "Включить", "Turn on"), (d, w) -> {
                    // commit: the profile listener (changed()) runs now, on this thread, before the page takes its profile
                    perLensSwitch(true);
                    turnedOn.run();
                }).show();
    }

    /** P413: the lens page of profile {@code id} (a module or one of its FULLRES shots): its sensor, RAW, levels, colour and noise. */
    static void openLens(ModuleRows.Host host, String id) {
        if (!PreferenceKeys.isPerLensSettingsOn()) {
            askPerLens(host.context(), () -> openLens(host, id));
            return;
        }
        SettingsActivity.SettingsFragment page = SettingsActivity.SettingsFragment.modulePage(id, ModuleRows.LENS);
        Bundle args = page.getArguments();
        String editing = null;
        try { editing = PreferenceKeys.profiles().editing(); } catch (RuntimeException ignored) { }
        if (args != null && editing != null) args.putString(SettingsActivity.SettingsFragment.ARG_RETURN_PROFILE, editing);
        host.open(page, "module_lens");
    }

    /**
     * A profile's pages (a module, or its FULLRES 50 / 200 MP shots): the settings root with only its values; with the modules'
     * own settings off, they are turned on first. The page gives the main settings back to the profile edited before it (the root
     * page behind it shows that profile's values).
     */
    static void openProfile(ModuleRows.Host host, String id) {
        if (!PreferenceKeys.isPerLensSettingsOn()) {
            askPerLens(host.context(), () -> openProfile(host, id));
            return;
        }
        SettingsActivity.SettingsFragment page = SettingsActivity.SettingsFragment.modulePage(id, null);
        Bundle args = page.getArguments();
        String editing = null;
        try { editing = PreferenceKeys.profiles().editing(); } catch (RuntimeException ignored) { }
        if (args != null && editing != null) args.putString(SettingsActivity.SettingsFragment.ARG_RETURN_PROFILE, editing);
        host.open(page, "module_profile");
    }

    /**
     * P214h: «Попробовать снова» of a camera's FULLRES trials - asked first (what was tried and why it failed); then the camera's
     * next FULLRES starts at its first row.
     */
    static void retryTrials(Context c, String camera, Runnable done) {
        String status = FullResTrials.status(camera);
        DialogStyle.builder(c).setTitle(c.getString(R.string.module_row_trials, camera)).setMessage(status)
                .setNegativeButton(Lang.t(c, "Отмена", "Cancel"), null)
                .setPositiveButton(c.getString(R.string.module_row_trials_retry), (d, w) -> {
                    FullResTrials.retry(camera);
                    Toast.makeText(c, Lang.t(c, "Пробы FULLRES камеры " + camera + " забыты: режимы проверяются заново с первого",
                            "FULLRES trials of camera " + camera + " forgotten: the modes are tried again from the first"), Toast.LENGTH_SHORT).show();
                    done.run();
                }).show();
    }
}
