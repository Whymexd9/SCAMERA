package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.TwoStatePreference;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * P319 (user report 2026-10-22: «Неплохо было бы добавить описание пунктов по длинному нажатию. Что делать и как настраивать
 * Таблицу яркости или цвета?»): a long press on a settings row opens its help - what the row does and how to set it (a text
 * of its own where one is written: res strings_help.xml, else the row's summary), its current value, «В шторку». The texts
 * are found through this map of keys and key families, never by name (the release build shrinks unreferenced strings).
 */
public final class SettingHelp {
    private SettingHelp() {}

    /** Exact keys, then families (a regex over the key): the first match gives the help text. */
    private static final Map<String, Integer> EXACT = new LinkedHashMap<>();
    private static final Map<Pattern, Integer> FAMILIES = new LinkedHashMap<>();

    static {
        EXACT.put("scam_hybrid_nr_why_luma_screen", R.string.help_nr_luma_table);
        EXACT.put("scam_hybrid_nr_why_chroma_screen", R.string.help_nr_chroma_table);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_dn_(luma|chroma)_t\\d_snr"), R.string.help_nr_table_snr);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_dn_luma_t\\d_strength"), R.string.help_nr_luma_strength);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_dn_chroma_t\\d_strength"), R.string.help_nr_chroma_strength);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_dn_(luma|chroma)_t\\d_revert"), R.string.help_nr_table_revert);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_dn_(luma|chroma)_t\\d_outlier"), R.string.help_nr_table_outlier);
        EXACT.put("pref_camera_sounds_all", R.string.help_sounds);
        EXACT.put("settings_layout_editor", R.string.help_settings_layout);
        EXACT.put("pref_live_viewfinder_raw_key", R.string.help_live_viewfinder_raw);
        EXACT.put("pref_photo_format", R.string.help_photo_format);
        EXACT.put("pref_film_lut", R.string.help_film_lut);
        EXACT.put("photo_size_screen", R.string.help_photo_size);
        EXACT.put("pref_scam_hybrid_output", R.string.help_output);
        EXACT.put("pref_wide169_key", R.string.help_wide169);
        EXACT.put("pref_crop_upscale", R.string.help_crop_upscale);
        EXACT.put("pref_ultrahdr_key", R.string.help_ultrahdr);
        EXACT.put("pref_photo_color_space", R.string.help_colour_space);
        EXACT.put("watermark_screen", R.string.help_watermark);
        EXACT.put("pref_motion_mode", R.string.help_motion_mode);
        EXACT.put("pref_antibanding_hz_key", R.string.help_antibanding);
        EXACT.put("pref_show_grid_key", R.string.help_grid);
        EXACT.put("pref_horizon", R.string.help_horizon);
        EXACT.put("pref_peak_method_key", R.string.help_peaking);
        EXACT.put("pref_face_detect_mode", R.string.help_face_detect);
        EXACT.put("pref_tracking_af_mode", R.string.help_tracking_af);
        EXACT.put("pref_split_af_ae", R.string.help_split_af_ae);
        EXACT.put("pref_low_light_boost", R.string.help_low_light_boost);
        EXACT.put("pref_mosaic_wb_corrections", R.string.help_mosaic_wb);
        EXACT.put("photo_style_screen", R.string.help_photo_style);
        EXACT.put("lens_filter_screen", R.string.help_lens_filter);
        EXACT.put("film_sim_screen", R.string.help_film_sim);
        EXACT.put("pref_scam_hybrid_colour_warmth", R.string.help_warmth);
        EXACT.put("pref_scam_hybrid_colour_tint", R.string.help_tint);
        EXACT.put("colour_mixer_screen", R.string.help_colour_mixer);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_colour_mix_\\w+_(hue|sat|light)"), R.string.help_colour_mixer);
        EXACT.put("colour_grading_screen", R.string.help_colour_grading);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_colour_grade_\\w+"), R.string.help_colour_grading);
        EXACT.put("colour_calibration_screen", R.string.help_colour_calibration);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_colour_cal_\\w+"), R.string.help_colour_calibration);
        EXACT.put("pref_color_method_key", R.string.help_colour_method);
        EXACT.put("backup_settings_screen", R.string.help_backup);
        // 2026-10-27 (owner: settings anyone can set up alone): the rows of «Снимок», «Съёмка» and «Экран камеры» that came from
        // the expert pages say what they do and where they were
        EXACT.put("pref_scam_hybrid_why_ae_target", R.string.help_brightness);
        EXACT.put("pref_scam_hybrid_why_macro_contrast", R.string.help_contrast);
        EXACT.put("pref_scam_hybrid_why_agx_sat", R.string.help_saturation);
        EXACT.put("pref_scam_hybrid_why_sharp_gain", R.string.help_sharpness);
        EXACT.put("pref_scam_hybrid_dn_luma_mult", R.string.help_noise);
        EXACT.put("pref_ettr", R.string.help_ettr);
        EXACT.put("focus_screen", R.string.help_focus);
        EXACT.put("pref_af_mode_key", R.string.help_af_mode);
        EXACT.put("pref_show_afdata_key", R.string.help_info_overlay);
        // P384 (owner, 2026-10-28: «Переместить эту группу настроек на главный экран»): the camera groups of the main page; each
        // text names the old place, so the search still finds the old words
        EXACT.put("root_modules", R.string.help_modules_group);
        EXACT.put("expert_module_ids", R.string.help_module_ids);
        EXACT.put("expert_module_sensor", R.string.help_module_sensor);
        EXACT.put("module_sensor_picker", R.string.help_module_sensor);
        EXACT.put("module_row_per_lens", R.string.help_module_profile);
        FAMILIES.put(Pattern.compile("module_card_\\w+"), R.string.help_module_card);
        FAMILIES.put(Pattern.compile("module_row_(name|button)_\\w+"), R.string.help_module_card);
        FAMILIES.put(Pattern.compile("module_row_zoom_\\w+"), R.string.help_module_zoom);
        FAMILIES.put(Pattern.compile("module_row_(crop|copy|delete)_\\w+"), R.string.help_module_crop);
        EXACT.put("module_row_add_copy", R.string.help_module_crop);
        FAMILIES.put(Pattern.compile("module_row_profile_.+"), R.string.help_module_profile);
        FAMILIES.put(Pattern.compile("module_row_id_\\w+"), R.string.help_module_ids);
        FAMILIES.put(Pattern.compile("pref_sensorconfig_\\w+_sensormoderow"), R.string.help_sensor_mode);
    }

    /** The help text of {@code key}, 0 without one of its own. */
    @StringRes
    static int helpRes(@Nullable String key) {
        if (key == null) return 0;
        Integer exact = EXACT.get(key);
        if (exact != null) return exact;
        for (Map.Entry<Pattern, Integer> e : FAMILIES.entrySet()) if (e.getKey().matcher(key).matches()) return e.getValue();
        return 0;
    }

    /**
     * Whether a long press on {@code p} opens its help: a keyed row (a page too; P384: a module card's header), not a category
     * header.
     */
    static boolean helps(@Nullable Preference p) {
        if (p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.ModuleSection) return p.getKey() != null;
        return p != null && p.getKey() != null && !(p instanceof PreferenceCategory)
                && !(p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableCheckBoxPreference);
    }

    /** The help sheet of {@code p}. */
    static void show(Context c, Preference p) {
        final String key = p.getKey();
        final LinearLayout body = SettingsStyle.sheetBody(c, p.getTitle() == null ? key : p.getTitle(), key);
        body.setTag("setting_help");
        final int res = helpRes(key);
        CharSequence text = res != 0 ? c.getText(res) : p.getSummary();
        if (TextUtils.isEmpty(text)) text = Lang.t(c, "Описания для этого пункта пока нет.", "No description for this row yet.");
        body.addView(paragraph(c, text, SettingsStyle.TEXT, 15));
        final CharSequence value = value(c, p);
        if (value != null) body.addView(paragraph(c, Lang.t(c, "Сейчас: ", "Now: ") + value, SettingsStyle.MUTED, 13));
        if (p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.ModuleSection)
            body.addView(paragraph(c, Lang.t(c, "Нажатие открывает и закрывает модуль.", "A tap opens and closes the module."), SettingsStyle.MUTED, 13));
        else if (p instanceof PreferenceGroup)
            body.addView(paragraph(c, Lang.t(c, "Нажатие открывает раздел.", "A tap opens the section."), SettingsStyle.MUTED, 13));
        SettingsStyle.sheet(c, body).show();
    }

    @Nullable
    private static CharSequence value(Context c, Preference p) {
        if (p instanceof TwoStatePreference) return ((TwoStatePreference) p).isChecked() ? Lang.t(c, "включено", "on") : Lang.t(c, "выключено", "off");
        if (p instanceof ListPreference) return ((ListPreference) p).getEntry();
        return null;
    }

    private static TextView paragraph(Context c, CharSequence text, int colour, float sp) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(colour);
        t.setTextSize(sp);
        t.setLineSpacing(0, 1.15f);
        int pad = Math.round(4 * c.getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad, pad, 2 * pad);
        return t;
    }
}
