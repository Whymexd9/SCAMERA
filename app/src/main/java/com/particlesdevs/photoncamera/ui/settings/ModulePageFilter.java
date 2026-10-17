package com.particlesdevs.photoncamera.ui.settings;

import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;

import com.particlesdevs.photoncamera.capture.VivoSdkFullRes;
import com.particlesdevs.photoncamera.settings.FullResProfiles;
import com.particlesdevs.photoncamera.settings.ModuleProfiles;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P189 (owner, 2026-10-17: «сделай отдельную группу настроек где все будет разделено по модулям»): the settings pages of one
 * module profile (a module, or its FULLRES 50 / 200 MP shots) are the usual XML pages with only the per-module values shown;
 * the shared ones (format, quality, sounds, grid, watermark...) stay on the usual pages.
 */
final class ModulePageFilter {
    /** The root row that opens the group (ModuleProfilesFragment). */
    static final String HUB = "module_profiles_screen";
    static final String CAMERAS = "camera_settings_screen", SENSOR_PAGE = "pref_sensor_config_submenu";
    /** The root pages a module page shows (an explicit list: «Система» has per-module rows by name only). */
    static final List<String> ROOT_PAGES = Collections.unmodifiableList(Arrays.asList("output_settings_screen",
            "shooting_settings_screen", "viewfinder_settings_screen", "photo_processing_screen", "scam_hybrid_screen", CAMERAS));

    private ModulePageFilter() { }

    /** Hides what is not {@code profile}'s on {@code page} (the settings root when {@code root}); returns the hidden keys. */
    static Set<String> apply(PreferenceGroup page, String profile, boolean root) {
        Set<String> hidden = new HashSet<>();
        if (root) {
            for (int i = 0; i < page.getPreferenceCount(); i++) {
                Preference p = page.getPreference(i);
                if (!ROOT_PAGES.contains(p.getKey())) hide(p, hidden);
                else if (p instanceof PreferenceGroup && visit((PreferenceGroup) p, profile, hidden) == 0) hide(p, hidden);
            }
        } else visit(page, profile, hidden);
        retitle(page, profile);
        return hidden;
    }

    /** The per-module values left visible in {@code group}; groups without one are hidden. */
    private static int visit(PreferenceGroup group, String profile, Set<String> hidden) {
        int values = 0;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            if (SENSOR_PAGE.equals(p.getKey())) { values++; continue; } // the module's sensor page: generated on its own page
            if (p instanceof PreferenceGroup) {
                int n = visit((PreferenceGroup) p, profile, hidden);
                if (n == 0) hide(p, hidden);
                values += n;
            } else if (!moduleRow(p.getKey(), profile)) hide(p, hidden);
            else if (p.isVisible() && p.isSelectable() && ModuleProfiles.isLocal(p.getKey())) values++;
        }
        return values;
    }

    /** A row of a module page: a per-module value (the hardware remosaic only where it acts), or a row without a value. */
    static boolean moduleRow(String key, String profile) {
        if (key == null) return true;
        if (VivoSdkFullRes.PREF_HW_REMOSAIC.equals(key)) return FullResProfiles.hardwareRemosaicApplies(profile);
        // P190: «Кадров в снимке 50 / 200 Мп» only in the FULLRES profile of that size
        if (FullResProfiles.framesTag(key) != null) return FullResProfiles.framesRowApplies(key, profile);
        if (key.startsWith("pref_") || key.startsWith("scamera_")) return ModuleProfiles.isLocal(key);
        return true;
    }

    private static void hide(Preference p, Set<String> hidden) {
        p.setVisible(false);
        if (p.getKey() != null) hidden.add(p.getKey());
    }

    /** «Камеры и сенсоры» of a module is its sensor and RAW; the sensor page is the module's (shared with its FULLRES shots). */
    private static void retitle(PreferenceGroup page, String profile) {
        android.content.Context c = page.getContext();
        Preference cameras = CAMERAS.equals(page.getKey()) ? page : page.findPreference(CAMERAS);
        if (cameras != null) {
            cameras.setTitle(Lang.t(c, "Сенсор и RAW", "Sensor and RAW"));
            cameras.setSummary(Lang.t(c, "Сенсор модуля, уровни RAW, модель шума", "The module's sensor, RAW levels, noise model"));
        }
        Preference sensor = page.findPreference(SENSOR_PAGE);
        if (sensor != null) {
            sensor.setTitle(Lang.t(c, "Сенсор этого модуля", "This module's sensor"));
            String slot = FullResProfiles.slotOf(profile);
            if (FullResProfiles.isVariant(profile) || !FullResProfiles.variants(slot).isEmpty()) {
                String name = ModuleRegistry.label(slot);
                sensor.setSummary(Lang.t(c, "Общие для «" + name + "» и его снимков FULLRES", "Shared by “" + name + "” and its FULLRES shots"));
            }
        }
    }
}
