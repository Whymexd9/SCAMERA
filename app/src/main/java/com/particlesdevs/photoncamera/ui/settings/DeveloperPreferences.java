package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.text.InputType;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.particlesdevs.photoncamera.settings.DevKeys;
import com.particlesdevs.photoncamera.settings.ScamDevFile;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The «Для разработчика» page (owner, 2026-10-13): one row per hidden scam_dev.txt line of {@link DevKeys}, grouped. Rows
 * are not stored as preferences: a switch writes "key 1" / "key 0", a number "key value" (empty = the line is removed and
 * the code's default applies) straight into scam_dev.txt; the summary says whether the file sets the line.
 */
final class DeveloperPreferences {
    static final String SCREEN = "developer_settings_screen";

    private DeveloperPreferences() {}

    static void populate(PreferenceScreen root, Context context) {
        PreferenceScreen screen = root == null ? null : root.findPreference(SCREEN);
        if (screen == null || context == null) return;
        screen.removeAll();
        final Context app = context.getApplicationContext();
        // P191: the file is Download/SCAMERA/scam_dev.txt (no longer Android/data); the short path the owner opens
        String path = ScamDevFile.SHOWN;
        Preference note = new Preference(context);
        note.setKey("dev_note");
        note.setSelectable(false);
        note.setIconSpaceReserved(false);
        note.setSummary(Lang.t(context,
                "Скрытые переключатели для опытов и отладки. Значения пишутся в файл " + path
                        + ". «по умолчанию» — строки в файле нет, работает значение из кода.",
                "Hidden switches for experiments and debugging. Values are written to " + path
                        + ". «default»: no line in the file, the code's value applies."));
        screen.addPreference(note);
        Preference reset = new Preference(context);
        reset.setKey("dev_reset");
        reset.setIconSpaceReserved(false);
        reset.setTitle(Lang.t(context, "Сбросить всё по умолчанию", "Reset everything to default"));
        reset.setSummary(Lang.t(context, "Удаляет из scam_dev.txt строки этой страницы", "Removes this page's lines from scam_dev.txt"));
        reset.setOnPreferenceClickListener(p -> {
            ScamDevFile.removeAll(app, DevKeys.keys());
            populate(root, context);
            return true;
        });
        screen.addPreference(reset);
        Map<String, PreferenceCategory> groups = new LinkedHashMap<>();
        // P303: the file read once for the page (it was once per key)
        final Map<String, String> values = ScamDevFile.snapshot(app);
        for (DevKeys.Key k : DevKeys.all()) {
            PreferenceCategory group = groups.get(k.group);
            if (group == null) {
                group = new PreferenceCategory(context);
                group.setKey("dev_group_" + groups.size());
                group.setTitle(k.group);
                group.setIconSpaceReserved(false);
                screen.addPreference(group);
                groups.put(k.group, group);
            }
            group.addPreference(k.kind == DevKeys.Kind.SWITCH ? switchRow(context, app, k, values.get(k.key))
                    : numberRow(context, app, k, values.get(k.key)));
        }
    }

    /**
     * The page reset of this page (audit 2026-10-27: its rows are not stored as preferences, so it changed nothing): every line of
     * this page out of scam_dev.txt, as its own «Сбросить всё по умолчанию» row does, and the rows rebuilt; the lines removed.
     */
    static int resetAll(PreferenceScreen root, Context context) {
        if (root == null || context == null) return 0;
        final Context app = context.getApplicationContext();
        final Map<String, String> values = ScamDevFile.snapshot(app);
        int set = 0;
        for (String key : DevKeys.keys()) if (values.containsKey(key)) set++;
        if (set > 0 && !ScamDevFile.removeAll(app, DevKeys.keys())) set = 0;
        populate(root, context);
        return set;
    }

    private static Preference switchRow(Context context, Context app, DevKeys.Key k, String cur) {
        SwitchPreferenceCompat p = new SwitchPreferenceCompat(context);
        p.setKey("dev_" + k.key);
        p.setPersistent(false);
        p.setIconSpaceReserved(false);
        p.setTitle(k.key);
        p.setChecked(cur != null ? number(cur) != 0f : k.def != null && k.switchDefault());
        p.setSummary(summary(context, k, cur));
        p.setOnPreferenceChangeListener((pref, value) -> {
            String v = Boolean.TRUE.equals(value) ? "1" : "0";
            boolean ok = ScamDevFile.set(app, k.key, v);
            if (ok) pref.setSummary(summary(context, k, v));
            return ok;
        });
        return p;
    }

    private static Preference numberRow(Context context, Context app, DevKeys.Key k, String cur) {
        EditTextPreference p = new EditTextPreference(context);
        p.setKey("dev_" + k.key);
        p.setPersistent(false);
        p.setIconSpaceReserved(false);
        p.setTitle(k.key);
        p.setDialogTitle(k.key);
        p.setDialogMessage(k.text + "\n" + Lang.t(context, "Пусто — по умолчанию", "Empty: default")
                + (k.def != null ? " (" + k.def + ")" : ""));
        p.setText(cur == null ? "" : cur);
        p.setSummary(summary(context, k, cur));
        p.setOnBindEditTextListener(input -> input.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL));
        p.setOnPreferenceChangeListener((pref, value) -> {
            String text = value == null ? "" : value.toString().trim().replace(',', '.');
            String v = null;
            if (!text.isEmpty()) {
                try {
                    v = ScamDevFile.number(Float.parseFloat(text));
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            boolean ok = ScamDevFile.set(app, k.key, v);
            if (ok) {
                ((EditTextPreference) pref).setText(v == null ? "" : v);
                pref.setSummary(summary(context, k, v));
            }
            return false; // the text is set above (not stored as a preference)
        });
        return p;
    }

    private static String summary(Context context, DevKeys.Key k, String cur) {
        String state;
        if (cur != null) state = Lang.t(context, "задано: ", "set: ") + cur;
        else if (k.def != null) state = Lang.t(context, "по умолчанию: ", "default: ") + k.def;
        else state = Lang.t(context, "по умолчанию (решает приложение)", "default (chosen by the app)");
        return k.text + "\n" + state;
    }

    private static float number(String v) {
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }
}
