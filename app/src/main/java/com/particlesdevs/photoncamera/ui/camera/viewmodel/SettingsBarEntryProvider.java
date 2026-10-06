/*
 *
 *  PhotonCamera
 *  SettingsBarEntryProvider.java
 *  Copyright (C) 2020 - 2021  Vibhor Srivastava
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 * /
 */

package com.particlesdevs.photoncamera.ui.camera.viewmodel;

import androidx.annotation.BoolRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingType;
import com.particlesdevs.photoncamera.ui.camera.model.SettingsBarButtonModel;
import com.particlesdevs.photoncamera.ui.camera.model.SettingsBarEntryModel;
import com.particlesdevs.photoncamera.ui.camera.model.TopBarSettingsData;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.SettingsBarLayout;

import java.util.ArrayList;
import java.util.List;

public class SettingsBarEntryProvider extends ViewModel {
    private final SettingsBarEntryModel timerEntry = SettingsBarEntryModel.newEntry(R.id.timer_entry_layout, R.string.countdown_timer, SettingType.TIMER);
    private final SettingsBarEntryModel flashEntry = SettingsBarEntryModel.newEntry(R.id.flash_entry_layout, R.string.flash, SettingType.FLASH);
    private final SettingsBarEntryModel gridEntry = SettingsBarEntryModel.newEntry(R.id.grid_entry_layout, R.string.turn_on_grid, SettingType.GRID);
    private final SettingsBarEntryModel saveRawEntry = SettingsBarEntryModel.newEntry(R.id.saveraw_entry_layout, R.string.raw_string, SettingType.RAW);
    private final SettingsBarEntryModel aeMeteringStdEntry = SettingsBarEntryModel.newEntry(R.id.ae_metering_std_entry_layout, R.string.ae_metering_std, SettingType.AE_METERING_STD);
    private final SettingsBarEntryModel hybridOutputEntry = SettingsBarEntryModel.newEntry(R.id.hybrid_output_entry_layout, R.string.hybrid_output_title, SettingType.HYBRID_OUTPUT);
    private final SettingsBarEntryModel hybridDownsamplerEntry = SettingsBarEntryModel.newEntry(R.id.hybrid_downsampler_entry_layout, R.string.hybrid_downsampler_title, SettingType.HYBRID_DOWNSAMPLER);
    private final List<SettingsBarEntryModel> allEntries = new ArrayList<>(12);

    /** Sheet order: Shoot, then Format, then View; within a group as listed in SHADE_SPEC. */
    public SettingsBarEntryProvider() {
        allEntries.add(flashEntry);
        allEntries.add(timerEntry);
        allEntries.add(aeMeteringStdEntry);
        allEntries.add(saveRawEntry);
        allEntries.add(hybridOutputEntry);
        allEntries.add(hybridDownsamplerEntry);
        allEntries.add(gridEntry);
    }

    public void createEntries() {
        resetRemovedSettings();
        createFlashEntry();
        createTimerEntry();
        createSaveRawEntry();
        createGridEntry();
        createAeMeteringStdEntry();
        createHybridOutputEntry();
        createHybridDownsamplerEntry();
        updateAllEntries();
    }

    public void updateAllEntries() {
        updateEntry(gridEntry, PreferenceKeys.getGridValue());
        updateEntry(flashEntry, PreferenceKeys.getAeMode());
        updateEntry(timerEntry, PreferenceKeys.getCountdownTimerIndex());
        updateEntry(saveRawEntry, PreferenceKeys.isSaveRaw());
        updateEntry(aeMeteringStdEntry, PreferenceKeys.getAeMeteringStd());
        updateEntry(hybridOutputEntry, PreferenceKeys.hybridOutputIndex());
        updateEntry(hybridDownsamplerEntry, PreferenceKeys.hybridDownsamplerIndex());
    }

    /*
     * Sheet data per entry: group, parameter icon, default value and short labels.
     * Defaults are only read: default_prefs.xml, PreferenceKeys.setDefaults and the fallbacks of
     * PreferenceKeys.hybridOutputMode() ("sensor") / hybridDownsampler() ("lanczos"), both index 0.
     * Neutral short labels ("24", "R+J", "4x4") are plain strings like the chip texts.
     * Parameters without a drawable of their own use the sheet's vectors (ic_sheet_*, mock docs/shade-sheet.html);
     * grid values show their own pattern (ic_grid_3x3/4x4/golden/diagonal, ic_sheet_grid_off). ic_grid_on/off stay for
     * the top bar's ic_grid_toggle.
     */
    private static void describe(SettingsBarEntryModel entry, int group, @DrawableRes int icon, int defaultValue) {
        entry.setGroup(group);
        entry.setIcon(icon);
        entry.setDefaultValue(defaultValue);
    }

    /**
     * Parameters the sheet no longer offers, left over from PhotonCamera: the HDRX switch (read by nothing), EIS (only
     * while recording video), exposure bracketing (only the old PhotonCamera planner; the hybrid and SCAM HDR plan their
     * own frames),  Quad Bayer (switches the RAW stream to the full sensor mode,
     * which the ZSL burst of the hybrid must not) and a fixed preview FPS (caps the exposure of the ZSL frames). They go
     * back to their defaults, so a value chosen in an older build cannot stay on unseen.
     */
    private static void resetRemovedSettings() {
        boolean hdrx = PhotonCamera.getResourcesStatic().getBoolean(R.bool.pref_hdrx_mode_default);
        boolean eis = PhotonCamera.getResourcesStatic().getBoolean(R.bool.pref_eis_photo_default);
        boolean quad = PhotonCamera.getResourcesStatic().getBoolean(R.bool.pref_quad_bayer_default);
        if (PreferenceKeys.isHdrXOn() != hdrx) PreferenceKeys.setHdrX(hdrx);
        if (PreferenceKeys.isEisPhotoOn() != eis) PreferenceKeys.setEisPhoto(eis);
        if (PreferenceKeys.isQuadBayerOn() != quad) PreferenceKeys.setQuadBayer(quad);
        if (PreferenceKeys.getFpsMode() != 0) PreferenceKeys.setFpsMode(0);
        if (PreferenceKeys.getBracketingMode() != 0) PreferenceKeys.setBracketingMode(0);
    }

    /** Button value (0/1) of a boolean default from default_prefs.xml. */
    private static int defaultFlag(@BoolRes int boolId) {
        return PhotonCamera.getResourcesStatic().getBoolean(boolId) ? 1 : 0;
    }

    /** Button value of an integer default stored as a string in default_prefs.xml. */
    private static int defaultIndex(@StringRes int stringId) {
        return Integer.parseInt(PhotonCamera.getResourcesStatic().getString(stringId).trim());
    }

    private void createHybridOutputEntry() {
        describe(hybridOutputEntry, SettingsBarEntryModel.GROUP_FORMAT, R.drawable.ic_sheet_resolution, 0);
        hybridOutputEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newTextButtonModel(R.id.hybrid_output_sensor_button, "1\u00d7", R.string.hybrid_output_sensor, 0, hybridOutputEntry),
                SettingsBarButtonModel.newTextButtonModel(R.id.hybrid_output_12_button, "12", R.string.hybrid_output_12, 1, hybridOutputEntry),
                SettingsBarButtonModel.newTextButtonModel(R.id.hybrid_output_16_button, "16", R.string.hybrid_output_16, 2, hybridOutputEntry),
                SettingsBarButtonModel.newTextButtonModel(R.id.hybrid_output_20_button, "20", R.string.hybrid_output_20, 3, hybridOutputEntry),
                SettingsBarButtonModel.newTextButtonModel(R.id.hybrid_output_2x_button, "2\u00d7", R.string.hybrid_output_2x, 4, hybridOutputEntry)
        );
    }

    private void createHybridDownsamplerEntry() {
        describe(hybridDownsamplerEntry, SettingsBarEntryModel.GROUP_FORMAT, R.drawable.ic_sheet_downsampler, 0);
        hybridDownsamplerEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newTextButtonModel(R.id.hybrid_ds_lanczos_button, "Lanc", R.string.hybrid_ds_lanczos, 0, hybridDownsamplerEntry),
                SettingsBarButtonModel.newTextButtonModel(R.id.hybrid_ds_bicubic_button, "Bicub", R.string.hybrid_ds_bicubic, 1, hybridDownsamplerEntry),
                SettingsBarButtonModel.newTextButtonModel(R.id.hybrid_ds_area_button, "Area", R.string.hybrid_ds_area, 2, hybridDownsamplerEntry),
                SettingsBarButtonModel.newTextButtonModel(R.id.hybrid_ds_bilinear_button, "Bilin", R.string.hybrid_ds_bilinear, 3, hybridDownsamplerEntry)
        );
    }

    public void addObserver(Observer<TopBarSettingsData<?, ?>> observer) {
        allEntries.forEach(settingsBarEntryModel -> settingsBarEntryModel.getTopBarSettingsData().observeForever(observer));
    }

    public void removeObserver(Observer<TopBarSettingsData<?, ?>> observer) {
        allEntries.forEach(settingsBarEntryModel -> settingsBarEntryModel.getTopBarSettingsData().removeObserver(observer));
    }

    public void addEntries(SettingsBarLayout settingsBarLayout) {
        settingsBarLayout.removeEntries();
        allEntries.forEach(settingsBarLayout::addEntry);
        // End of the batch: the sheet builds its quick buttons and group summaries.
        settingsBarLayout.onEntriesAdded();
    }

    private void  createSaveRawEntry() {
        describe(saveRawEntry, SettingsBarEntryModel.GROUP_FORMAT, R.drawable.ic_raw, defaultIndex(R.string.pref_raw_mode_default_value));
        saveRawEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newButtonModel(R.id.raw_off_button, R.drawable.ic_raw_off, R.string.jpg_only, 0, saveRawEntry).withShortLabel("JPEG"),
                SettingsBarButtonModel.newButtonModel(R.id.raw_on_button, R.drawable.ic_raw, R.string.raw_plus_jpg, 1, saveRawEntry).withShortLabel("R+J"),
                SettingsBarButtonModel.newButtonModel(R.id.raw_only_button, R.drawable.ic_raw, R.string.raw_string, 2, saveRawEntry).withShortLabel("RAW")
        );
    }

    private void createFlashEntry() {
        describe(flashEntry, SettingsBarEntryModel.GROUP_SHOOT, R.drawable.ic_flash_on, defaultIndex(R.string.pref_ae_mode_default));
        flashEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newButtonModel(R.id.torch_button, R.drawable.ic_torch, R.string.torch, 0, flashEntry).withShortLabel(R.string.sheet_short_torch),
                SettingsBarButtonModel.newButtonModel(R.id.flash_odd_button, R.drawable.ic_flash_off, R.string.off, 1, flashEntry).withShortLabel(R.string.sheet_short_off)
        );
    }

    private void createTimerEntry() {
        describe(timerEntry, SettingsBarEntryModel.GROUP_SHOOT, R.drawable.ic_sheet_timer, 0);
        timerEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newButtonModel(R.id.timer_off_button, R.drawable.ic_timeroff, R.string.off, 0, timerEntry).withShortLabel(R.string.sheet_short_off),
                SettingsBarButtonModel.newButtonModel(R.id.timer3s_button, R.drawable.ic_timer3s, R.string.t_3s, 1, timerEntry).withShortLabel(R.string.sheet_short_3s),
                SettingsBarButtonModel.newButtonModel(R.id.timer10s_button, R.drawable.ic_timer10s, R.string.t_10s, 2, timerEntry).withShortLabel(R.string.sheet_short_10s)
        );
    }

    private void createGridEntry() {
        describe(gridEntry, SettingsBarEntryModel.GROUP_VIEW, R.drawable.ic_grid_on, defaultIndex(R.string.pref_show_grid_default));
        gridEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newButtonModel(R.id.grid_off_button, R.drawable.ic_sheet_grid_off, R.string.off, 0, gridEntry).withShortLabel(R.string.sheet_short_off),
                SettingsBarButtonModel.newButtonModel(R.id.grid_33_button, R.drawable.ic_grid_3x3, R.string.three_x3, 1, gridEntry).withShortLabel("3\u00d73"),
                SettingsBarButtonModel.newButtonModel(R.id.grid_44_button, R.drawable.ic_grid_4x4, R.string.four_x4, 2, gridEntry).withShortLabel("4\u00d74"),
                SettingsBarButtonModel.newButtonModel(R.id.grid_gr_button, R.drawable.ic_grid_golden, R.string.golden_ratio, 3, gridEntry).withShortLabel("\u03a6"),
                SettingsBarButtonModel.newButtonModel(R.id.grid_dt_button, R.drawable.ic_grid_diagonal, R.string.diag_triangle, 4, gridEntry).withShortLabel(R.string.sheet_short_diagonal)
        );
    }

  private void createAeMeteringStdEntry() {
        // No per-value icons: every value shows the parameter icon. Value -1 (the default) is "Auto".
        final int icon = R.drawable.ic_sheet_metering;
        describe(aeMeteringStdEntry, SettingsBarEntryModel.GROUP_SHOOT, icon, -1);
        aeMeteringStdEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newButtonModel(R.id.ae_metering_std_off_button, icon, R.string.ae_metering_off, -1, aeMeteringStdEntry).withShortLabel(R.string.sheet_short_auto),
                SettingsBarButtonModel.newButtonModel(R.id.ae_metering_std_center_button, icon, R.string.ae_metering_center, 0, aeMeteringStdEntry).withShortLabel(R.string.sheet_short_center),
                SettingsBarButtonModel.newButtonModel(R.id.ae_metering_std_average_button, icon, R.string.ae_metering_average, 1, aeMeteringStdEntry).withShortLabel(R.string.sheet_short_average),
                SettingsBarButtonModel.newButtonModel(R.id.ae_metering_std_spot_button, icon, R.string.ae_metering_spot, 2, aeMeteringStdEntry).withShortLabel(R.string.sheet_short_spot)
        );
    }

    private void updateEntry(SettingsBarEntryModel entry, int value) {
        for (SettingsBarButtonModel buttonModel : entry.getSettingsBarButtonModels()) {
            if (buttonModel.getButtonValue() == value) {
                buttonModel.setSelected(true);
                entry.setStateTextStringId(buttonModel.getButtonStateNameStringId());
            } else {
                buttonModel.setSelected(false);
            }
        }
    }

    private void updateEntry(SettingsBarEntryModel entry, boolean value) {
        for (SettingsBarButtonModel buttonModel : entry.getSettingsBarButtonModels()) {
            if ((buttonModel.getButtonValue() == 1) == value) {
                buttonModel.setSelected(true);
                entry.setStateTextStringId(buttonModel.getButtonStateNameStringId());
            } else {
                buttonModel.setSelected(false);
            }
        }
    }
}