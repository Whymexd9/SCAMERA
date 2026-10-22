package com.particlesdevs.photoncamera.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.particlesdevs.photoncamera.R;

import org.junit.Test;

/**
 * P319 (user report: «Что делать и как настраивать Таблицу яркости или цвета?»): a long press opens help; every row of the
 * brightness and colour tables finds its text by its key family, other rows fall back to their summary (0 here).
 */
public class SettingHelpTest {
    @Test public void theTablesExplainEveryRow() {
        assertEquals(R.string.help_nr_luma_table, SettingHelp.helpRes("scam_hybrid_nr_why_luma_screen"));
        assertEquals(R.string.help_nr_chroma_table, SettingHelp.helpRes("scam_hybrid_nr_why_chroma_screen"));
        for (int t = 1; t <= 5; t++) {
            assertEquals(R.string.help_nr_table_snr, SettingHelp.helpRes("pref_scam_hybrid_dn_luma_t" + t + "_snr"));
            assertEquals(R.string.help_nr_luma_strength, SettingHelp.helpRes("pref_scam_hybrid_dn_luma_t" + t + "_strength"));
            assertEquals(R.string.help_nr_table_revert, SettingHelp.helpRes("pref_scam_hybrid_dn_luma_t" + t + "_revert"));
            assertEquals(R.string.help_nr_table_outlier, SettingHelp.helpRes("pref_scam_hybrid_dn_luma_t" + t + "_outlier"));
        }
        for (int t = 1; t <= 4; t++) {
            assertEquals(R.string.help_nr_table_snr, SettingHelp.helpRes("pref_scam_hybrid_dn_chroma_t" + t + "_snr"));
            assertEquals(R.string.help_nr_chroma_strength, SettingHelp.helpRes("pref_scam_hybrid_dn_chroma_t" + t + "_strength"));
            assertEquals(R.string.help_nr_table_outlier, SettingHelp.helpRes("pref_scam_hybrid_dn_chroma_t" + t + "_outlier"));
        }
        assertNotEquals(0, SettingHelp.helpRes("pref_camera_sounds_all"));
        // the ordinary rows of «Фото», «Съёмка», «Экран камеры», «Цвет и стиль» with a text of their own
        for (String key : new String[]{"photo_size_screen", "pref_scam_hybrid_output", "pref_wide169_key", "pref_ultrahdr_key",
                "pref_motion_mode", "pref_antibanding_hz_key", "pref_show_grid_key", "pref_horizon", "pref_peak_method_key",
                "pref_scam_hybrid_colour_warmth", "pref_scam_hybrid_colour_tint", "backup_settings_screen"})
            assertNotEquals(key, 0, SettingHelp.helpRes(key));
        assertEquals(R.string.help_colour_mixer, SettingHelp.helpRes("pref_scam_hybrid_colour_mix_aqua_sat"));
        assertEquals(R.string.help_colour_grading, SettingHelp.helpRes("pref_scam_hybrid_colour_grade_mids_hue"));
        assertEquals(R.string.help_colour_calibration, SettingHelp.helpRes("pref_scam_hybrid_colour_cal_blue_sat"));
        assertEquals(0, SettingHelp.helpRes("pref_some_row_without_help"));
        assertEquals(0, SettingHelp.helpRes(null));
    }
}
