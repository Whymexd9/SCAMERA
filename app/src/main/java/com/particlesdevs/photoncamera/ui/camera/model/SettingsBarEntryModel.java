/*
 *
 *  PhotonCamera
 *  SettingsBarEntryModel.java
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

package com.particlesdevs.photoncamera.ui.camera.model;

import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.StringRes;
import androidx.lifecycle.MutableLiveData;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.settings.SettingType;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.SettingsBarListener;

public class SettingsBarEntryModel {
    /** Sheet groups, in display order. */
    public static final int GROUP_SHOOT = 0;
    public static final int GROUP_FORMAT = 1;
    public static final int GROUP_VIEW = 2;
    public static final int GROUP_COUNT = 3;

    private final int id;
    private final MutableLiveData<TopBarSettingsData<?, ?>> topBarSettingsData = new MutableLiveData<>();
    private int titleStringId;
    private int stateTextStringId;
    private SettingsBarButtonModel[] settingsBarButtonModels;
    private SettingsBarListener settingsBarListener;
    private Enum<SettingType> type;
    private int group = GROUP_SHOOT;
    /** Icon of the parameter itself (row header; value icon of text chips). */
    private int icon;
    /** Button value of the default setting: a different selection counts as changed. */
    private int defaultValue;

    public SettingsBarEntryModel(@IdRes int id) {
        this.id = id;
    }

    private SettingsBarEntryModel(@IdRes int id, @StringRes int titleStringId, Enum<SettingType> type) {
        this.id = id;
        setTitleStringId(titleStringId);
        setTypeAndData(type);
    }

    public static SettingsBarEntryModel newEntry(@IdRes int id, @StringRes int titleStringId, Enum<SettingType> type) {
        return new SettingsBarEntryModel(id, titleStringId, type);
    }

    public void setType(Enum<SettingType> type) {
        this.type = type;
    }

    public void setTypeAndData(Enum<SettingType> type) {
        this.type = type;
        this.topBarSettingsData.setValue(new TopBarSettingsData<>(type));
    }

    public MutableLiveData<TopBarSettingsData<?, ?>> getTopBarSettingsData() {
        return topBarSettingsData;
    }

    public void setSettingsBarListener(SettingsBarListener settingsBarListener) {
        this.settingsBarListener = settingsBarListener;
    }

    public int getId() {
        return id;
    }

    public int getTitleStringId() {
        return titleStringId;
    }

    public void setTitleStringId(@StringRes int titleStringId) {
        this.titleStringId = titleStringId;
    }

    public int getStateTextStringId() {
        return stateTextStringId;
    }

    public void setStateTextStringId(@StringRes int stateTextStringId) {
        this.stateTextStringId = stateTextStringId;
    }

    public SettingsBarButtonModel[] getSettingsBarButtonModels() {
        return settingsBarButtonModels;
    }

    public void addSettingsBarButtonModels(SettingsBarButtonModel... settingsBarButtonModels) {
        this.settingsBarButtonModels = settingsBarButtonModels;
    }

    public int getGroup() {
        return group;
    }

    public void setGroup(int group) {
        this.group = group;
    }

    @DrawableRes
    public int getIcon() {
        return icon;
    }

    public void setIcon(@DrawableRes int icon) {
        this.icon = icon;
    }

    public int getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(int defaultValue) {
        this.defaultValue = defaultValue;
    }

    /** The selected button, or null before the entry has been updated from the settings. */
    public SettingsBarButtonModel getSelectedButtonModel() {
        if (settingsBarButtonModels != null) {
            for (SettingsBarButtonModel model : settingsBarButtonModels)
                if (model.isSelected()) return model;
        }
        return null;
    }

    /** True when the selected value differs from the default. */
    public boolean isChanged() {
        SettingsBarButtonModel selected = getSelectedButtonModel();
        return selected != null && selected.getButtonValue() != defaultValue;
    }

    @StringRes
    public static int getGroupTitleStringId(int group) {
        switch (group) {
            case GROUP_FORMAT:
                return R.string.sheet_group_format;
            case GROUP_VIEW:
                return R.string.sheet_group_view;
            default:
                return R.string.sheet_group_shoot;
        }
    }

    public void select(SettingsBarButtonModel buttonModel) {
        if (settingsBarButtonModels != null) {
            for (SettingsBarButtonModel model : settingsBarButtonModels)
                model.setSelected(model.getId() == buttonModel.getId());
            setStateTextStringId(buttonModel.getButtonStateNameStringId());
        }
        if (settingsBarListener != null) {
            settingsBarListener.onEntryUpdated(this, buttonModel);
        }
        topBarSettingsData.setValue(new TopBarSettingsData<>(type, buttonModel.getButtonValue()));
    }
}
