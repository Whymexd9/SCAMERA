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
    private final SettingsBarEntryModel hdrxEntry = SettingsBarEntryModel.newEntry(R.id.hdrx_entry_layout, R.string.hdrx, SettingType.HDRX);
    private final SettingsBarEntryModel timerEntry = SettingsBarEntryModel.newEntry(R.id.timer_entry_layout, R.string.countdown_timer, SettingType.TIMER);
    private final SettingsBarEntryModel quadEntry = SettingsBarEntryModel.newEntry(R.id.quad_entry_layout, R.string.quad_bayer_toggle_text, SettingType.QUAD);
    private final SettingsBarEntryModel fpsEntry = SettingsBarEntryModel.newEntry(R.id.fps_entry_layout, R.string.fps_60_toggle_text, SettingType.FPS_60);
    private final SettingsBarEntryModel flashEntry = SettingsBarEntryModel.newEntry(R.id.flash_entry_layout, R.string.flash, SettingType.FLASH);
    private final SettingsBarEntryModel gridEntry = SettingsBarEntryModel.newEntry(R.id.grid_entry_layout, R.string.turn_on_grid, SettingType.GRID);
    private final SettingsBarEntryModel eisEntry = SettingsBarEntryModel.newEntry(R.id.eis_entry_layout, R.string.eis_toggle_text, SettingType.EIS);
    private final SettingsBarEntryModel saveRawEntry = SettingsBarEntryModel.newEntry(R.id.saveraw_entry_layout, R.string.raw_string, SettingType.RAW);
    private final SettingsBarEntryModel batterySaverEntry = SettingsBarEntryModel.newEntry(R.id.batterysaver_entry_layout, R.string.energy_saving, SettingType.BATTERY_SAVER);
    private final SettingsBarEntryModel bracketingEntry = SettingsBarEntryModel.newEntry(R.id.bracketing_entry_layout, R.string.exposure_bracketing, SettingType.BRACKETING);
    private final SettingsBarEntryModel aeMeteringStdEntry = SettingsBarEntryModel.newEntry(R.id.ae_metering_std_entry_layout, R.string.ae_metering_std, SettingType.AE_METERING_STD);
    private final SettingsBarEntryModel hybridOutputEntry = SettingsBarEntryModel.newEntry(R.id.hybrid_output_entry_layout, R.string.hybrid_output_title, SettingType.HYBRID_OUTPUT);
    private final SettingsBarEntryModel hybridDownsamplerEntry = SettingsBarEntryModel.newEntry(R.id.hybrid_downsampler_entry_layout, R.string.hybrid_downsampler_title, SettingType.HYBRID_DOWNSAMPLER);
    private final List<SettingsBarEntryModel> allEntries = new ArrayList<>(12);

    /** Sheet order: Shoot, then Format, then View; within a group as listed in SHADE_SPEC. */
    public SettingsBarEntryProvider() {
//        allEntries.add(hdrxEntry);
        allEntries.add(flashEntry);
        allEntries.add(timerEntry);
        allEntries.add(bracketingEntry);
        allEntries.add(aeMeteringStdEntry);
        allEntries.add(fpsEntry);
        allEntries.add(eisEntry);
        allEntries.add(saveRawEntry);
        allEntries.add(quadEntry);
        allEntries.add(hybridOutputEntry);
        allEntries.add(hybridDownsamplerEntry);
        allEntries.add(gridEntry);
        allEntries.add(batterySaverEntry);
    }

    public void createEntries() {
        createHdrxEntry();
        createQuadBayerEntry();
        createEisEntry();
        createFlashEntry();
        createFpsEntry();
        createTimerEntry();
        createSaveRawEntry();
        createGridEntry();
        createBatterySaverEntry();
        createBracketingEntry();
        createAeMeteringStdEntry();
        createHybridOutputEntry();
        createHybridDownsamplerEntry();
        updateAllEntries();
    }

    public void updateAllEntries() {
        updateEntry(gridEntry, PreferenceKeys.getGridValue());
        updateEntry(flashEntry, PreferenceKeys.getAeMode());
        updateEntry(timerEntry, PreferenceKeys.getCountdownTimerIndex());
        updateEntry(hdrxEntry, PreferenceKeys.isHdrXOn());
        updateEntry(eisEntry, PreferenceKeys.isEisPhotoOn());
        updateEntry(fpsEntry, PreferenceKeys.getFpsMode());
        updateEntry(quadEntry, PreferenceKeys.isQuadBayerOn());
        updateEntry(saveRawEntry, PreferenceKeys.isSaveRaw());
        updateEntry(batterySaverEntry, PreferenceKeys.isBatterySaverOn());
        updateEntry(bracketingEntry, PreferenceKeys.getBracketingMode());
        updateEntry(aeMeteringStdEntry, PreferenceKeys.getAeMeteringStd());
        updateEntry(hybridOutputEntry, PreferenceKeys.hybridOutputIndex());
        updateEntry(hybridDownsamplerEntry, PreferenceKeys.hybridDownsamplerIndex());
    }

    /*
     * Sheet data per entry: group, parameter icon, default value and short labels.
     * Defaults are only read: default_prefs.xml, PreferenceKeys.setDefaults and the fallbacks of
     * PreferenceKeys.hybridOutputMode() ("sensor") / hybridDownsampler() ("lanczos"), both index 0.
     * Neutral short labels ("24", "R+J", "4x4") are plain strings like the chip texts.
     * ic_timer3s, ic_exposure, ic_photo_library and ic_tune_black_24dp stand in as parameter icons
     * until the sheet's own vectors exist.
     */
    private static void describe(SettingsBarEntryModel entry, int group, @DrawableRes int icon, int defaultValue) {
        entry.setGroup(group);
        entry.setIcon(icon);
        entry.setDefaultValue(defaultValue);
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
        describe(hybridOutputEntry, SettingsBarEntryModel.GROUP_FORMAT, R.drawable.ic_photo_library, 0);
        hybridOutputEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newTextButtonModel(R.id.hybrid_output_sensor_button, "1\u00d7", R.string.hybrid_output_sensor, 0, hybridOutputEntry),
                SettingsBarButtonModel.newTextButtonModel(R.id.hybrid_output_12_button, "12", R.string.hybrid_output_12, 1, hybridOutputEntry),
                SettingsBarButtonModel.newTextButtonModel(R.id.hybrid_output_16_button, "16", R.string.hybrid_output_16, 2, hybridOutputEntry),
                SettingsBarButtonModel.newTextButtonModel(R.id.hybrid_output_20_button, "20", R.string.hybrid_output_20, 3, hybridOutputEntry),
                SettingsBarButtonModel.newTextButtonModel(R.id.hybrid_output_2x_button, "2\u00d7", R.string.hybrid_output_2x, 4, hybridOutputEntry)
        );
    }

    private void createHybridDownsamplerEntry() {
        describe(hybridDownsamplerEntry, SettingsBarEntryModel.GROUP_FORMAT, R.drawable.ic_tune_black_24dp, 0);
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

    private void createHdrxEntry() {
        describe(hdrxEntry, SettingsBarEntryModel.GROUP_SHOOT, R.drawable.ic_hdrx_on, defaultFlag(R.bool.pref_hdrx_mode_default));
        hdrxEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newButtonModel(R.id.hdrx_off_button, R.drawable.ic_hdrx_off, R.string.off, 0, hdrxEntry).withShortLabel(R.string.sheet_short_off),
                SettingsBarButtonModel.newButtonModel(R.id.hdrx_on_button, R.drawable.ic_hdrx_on, R.string.on, 1, hdrxEntry).withShortLabel(R.string.sheet_short_on)
        );
    }

    private void createQuadBayerEntry() {
        describe(quadEntry, SettingsBarEntryModel.GROUP_FORMAT, R.drawable.ic_quad_on, defaultFlag(R.bool.pref_quad_bayer_default));
        quadEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newButtonModel(R.id.quad_off_button, R.drawable.ic_quad_off, R.string.off, 0, quadEntry).withShortLabel(R.string.sheet_short_off),
                SettingsBarButtonModel.newButtonModel(R.id.quad_on_button, R.drawable.ic_quad_on, R.string.on, 1, quadEntry).withShortLabel(R.string.sheet_short_on)
        );
    }

    private void createEisEntry() {
        describe(eisEntry, SettingsBarEntryModel.GROUP_SHOOT, R.drawable.ic_eis_on, defaultFlag(R.bool.pref_eis_photo_default));
        eisEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newButtonModel(R.id.eis_off_button, R.drawable.ic_eis_off, R.string.off, 0, eisEntry).withShortLabel(R.string.sheet_short_off),
                SettingsBarButtonModel.newButtonModel(R.id.eis_on_button, R.drawable.ic_eis_on, R.string.on, 1, eisEntry).withShortLabel(R.string.sheet_short_on)
        );
    }

    private void  createSaveRawEntry() {
        describe(saveRawEntry, SettingsBarEntryModel.GROUP_FORMAT, R.drawable.ic_raw, defaultIndex(R.string.pref_raw_mode_default_value));
        saveRawEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newButtonModel(R.id.raw_off_button, R.drawable.ic_raw_off, R.string.jpg_only, 0, saveRawEntry).withShortLabel("JPEG"),
                SettingsBarButtonModel.newButtonModel(R.id.raw_on_button, R.drawable.ic_raw, R.string.raw_plus_jpg, 1, saveRawEntry).withShortLabel("R+J"),
                SettingsBarButtonModel.newButtonModel(R.id.raw_only_button, R.drawable.ic_raw, R.string.raw_string, 2, saveRawEntry).withShortLabel("RAW")
        );
    }

    private void createBatterySaverEntry() {
        describe(batterySaverEntry, SettingsBarEntryModel.GROUP_VIEW, R.drawable.leaf_icon_15, defaultFlag(R.bool.pref_energy_safe_default));
        batterySaverEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newButtonModel(R.id.btsvr_off_button, R.drawable.ic_round_battery_alert_24, R.string.off, 0, batterySaverEntry).withShortLabel(R.string.sheet_short_off),
                SettingsBarButtonModel.newButtonModel(R.id.btsvr_on_button, R.drawable.leaf_icon_15, R.string.on, 1, batterySaverEntry).withShortLabel(R.string.sheet_short_on)
        );
    }

    private void createBracketingEntry() {
        // No per-value icons: every value shows the parameter icon.
        final int icon = R.drawable.ic_exposure;
        describe(bracketingEntry, SettingsBarEntryModel.GROUP_SHOOT, icon, 0);
        bracketingEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newButtonModel(R.id.bracketing_off_button, icon, R.string.bracketing_off, 0, bracketingEntry).withShortLabel(R.string.sheet_short_off),
                SettingsBarButtonModel.newButtonModel(R.id.bracketing_normal_button, icon, R.string.bracketing_normal, 1, bracketingEntry).withShortLabel(R.string.sheet_short_normal),
                SettingsBarButtonModel.newButtonModel(R.id.bracketing_high_button, icon, R.string.bracketing_high, 2, bracketingEntry).withShortLabel(R.string.sheet_short_high)
        );
    }

    private void createFlashEntry() {
        describe(flashEntry, SettingsBarEntryModel.GROUP_SHOOT, R.drawable.ic_flash_on, defaultIndex(R.string.pref_ae_mode_default));
        flashEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newButtonModel(R.id.torch_button, R.drawable.ic_torch, R.string.torch, 0, flashEntry).withShortLabel(R.string.sheet_short_torch),
                SettingsBarButtonModel.newButtonModel(R.id.flash_odd_button, R.drawable.ic_flash_off, R.string.off, 1, flashEntry).withShortLabel(R.string.sheet_short_off)
        );
    }

    private void createFpsEntry() {
        describe(fpsEntry, SettingsBarEntryModel.GROUP_SHOOT, R.drawable.autofps_select_24px, 0);
        fpsEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newButtonModel(R.id.fps_auto_button, R.drawable.autofps_select_24px, R.string.fps_auto, 0, fpsEntry).withShortLabel(R.string.sheet_short_auto),
                SettingsBarButtonModel.newButtonModel(R.id.fps24_button, R.drawable.fps24_select_24px, R.string.fps_24, 1, fpsEntry).withShortLabel("24"),
                SettingsBarButtonModel.newButtonModel(R.id.fps30_button, R.drawable.fps30_select_24px, R.string.fps_30, 2, fpsEntry).withShortLabel("30"),
                SettingsBarButtonModel.newButtonModel(R.id.fps60_button, R.drawable.fps60_select_24px, R.string.fps_60, 3, fpsEntry).withShortLabel("60")
        );
    }

    private void createTimerEntry() {
        describe(timerEntry, SettingsBarEntryModel.GROUP_SHOOT, R.drawable.ic_timer3s, 0);
        timerEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newButtonModel(R.id.timer_off_button, R.drawable.ic_timeroff, R.string.off, 0, timerEntry).withShortLabel(R.string.sheet_short_off),
                SettingsBarButtonModel.newButtonModel(R.id.timer3s_button, R.drawable.ic_timer3s, R.string.t_3s, 1, timerEntry).withShortLabel(R.string.sheet_short_3s),
                SettingsBarButtonModel.newButtonModel(R.id.timer10s_button, R.drawable.ic_timer10s, R.string.t_10s, 2, timerEntry).withShortLabel(R.string.sheet_short_10s)
        );
    }

    private void createGridEntry() {
        describe(gridEntry, SettingsBarEntryModel.GROUP_VIEW, R.drawable.ic_grid_on, defaultIndex(R.string.pref_show_grid_default));
        gridEntry.addSettingsBarButtonModels(
                SettingsBarButtonModel.newButtonModel(R.id.grid_off_button, R.drawable.ic_grid_off, R.string.off, 0, gridEntry).withShortLabel(R.string.sheet_short_off),
                SettingsBarButtonModel.newButtonModel(R.id.grid_33_button, R.drawable.ic_grid_on, R.string.three_x3, 1, gridEntry).withShortLabel("3\u00d73"),
                SettingsBarButtonModel.newButtonModel(R.id.grid_44_button, R.drawable.ic_grid_on, R.string.four_x4, 2, gridEntry).withShortLabel("4\u00d74"),
                SettingsBarButtonModel.newButtonModel(R.id.grid_gr_button, R.drawable.ic_grid_on, R.string.golden_ratio, 3, gridEntry).withShortLabel("\u03a6"),
                SettingsBarButtonModel.newButtonModel(R.id.grid_dt_button, R.drawable.ic_grid_on, R.string.diag_triangle, 4, gridEntry).withShortLabel(R.string.sheet_short_diagonal)
        );
    }

  private void createAeMeteringStdEntry() {
        // No per-value icons: every value shows the parameter icon. Value -1 (the default) is "Auto".
        final int icon = R.drawable.ic_exposure;
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