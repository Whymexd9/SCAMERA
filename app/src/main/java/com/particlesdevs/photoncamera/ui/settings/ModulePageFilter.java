package com.particlesdevs.photoncamera.ui.settings;

import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.capture.VivoSdkFullRes;
import com.particlesdevs.photoncamera.settings.FullResProfiles;
import com.particlesdevs.photoncamera.settings.ModuleProfiles;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.SensorConfigPreferenceGenerator;

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
    /**
     * P384: «Сенсор по модулям» of the main page - on a module's root page its own sensor category only (the generated rows are the
     * module's by their key, not by ModuleProfiles: the group counts as a value and is not visited).
     */
    static final String SENSOR_GROUP = SensorConfigPreferenceGenerator.GROUP_KEY;
    /**
     * SIMPLE_UI §4: the groups a module page never shows (the root is now groups of rows): «Система» has per-module rows by
     * name only; the app's and the about rows are shared. P384: the module cards and the Camera ID rows are every module's, not one
     * profile's.
     */
    static final List<String> NOT_MODULE = Collections.unmodifiableList(Arrays.asList("root_logs", "root_lab", "root_about",
            ModuleRows.GROUP, ModuleRows.IDS));

    private ModulePageFilter() { }

    /** Hides what is not {@code profile}'s on {@code page} (the settings root when {@code root}); returns the hidden keys. */
    static Set<String> apply(PreferenceGroup page, String profile, boolean root) {
        Set<String> hidden = new HashSet<>();
        visit(page, profile, hidden);
        retitle(page, profile);
        return hidden;
    }

    /** The per-module values left visible in {@code group}; groups without one are hidden. */
    private static int visit(PreferenceGroup group, String profile, Set<String> hidden) {
        int values = 0;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            if (SENSOR_GROUP.equals(p.getKey())) { values++; continue; } // the module's sensor: shown by its own category
            if (NOT_MODULE.contains(p.getKey())) { hide(p, hidden); continue; }
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
        // P266 (owner: no two rows of one meaning): a FULLRES profile counts its frames by «Кадров в снимке 50 / 200 Мп» - the
        // general «Кадров в склейке» there would be a second, mostly ignored count
        if ("pref_scam_hybrid_zsl_frames".equals(key) && FullResProfiles.isVariant(profile)) return false;
        if (key.startsWith("pref_") || key.startsWith("scamera_")) return ModuleProfiles.isLocal(key);
        return true;
    }

    private static void hide(Preference p, Set<String> hidden) {
        p.setVisible(false);
        if (p.getKey() != null) hidden.add(p.getKey());
    }

    /** P384: the sensor group of a module's root page is that module's sensor (shared with its FULLRES shots). */
    private static void retitle(PreferenceGroup page, String profile) {
        android.content.Context c = page.getContext();
        Preference sensor = SENSOR_GROUP.equals(page.getKey()) ? page : page.findPreference(SENSOR_GROUP);
        if (sensor != null) {
            sensor.setTitle(c.getString(R.string.module_sensor_this));
            String slot = FullResProfiles.slotOf(profile);
            if (FullResProfiles.isVariant(profile) || !FullResProfiles.variants(slot).isEmpty())
                sensor.setSummary(c.getString(R.string.module_sensor_shared, ModuleRegistry.label(slot)));
        }
    }
}
