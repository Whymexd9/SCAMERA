/*
 *
 *  PhotonCamera
 *  SettingsBarButtonModel.java
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

import android.content.Context;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.StringRes;

public class SettingsBarButtonModel {
    private final int buttonDrawableId;
    private final int buttonStateNameStringId;
    private final int buttonValue;
    private final int id;
    private final SettingsBarEntryModel entryModel;
    private View.OnClickListener buttonClickListener;
    private boolean selected;
    /** Text drawn on the chip instead of an icon (resolution "12", "2x", downsampler "Lanc"). */
    private String textLabel;
    public String getTextLabel() { return textLabel; }
    /** Short label of the value in the sheet: neutral text ("4x4", "R+J") or a string resource (Off). */
    private String shortLabel;
    private int shortLabelStringId;


    private SettingsBarButtonModel(@IdRes int id, @DrawableRes int buttonDrawableId, @StringRes int buttonStateNameStringId, int buttonValue, SettingsBarEntryModel entryModel) {
        this.id = id;
        this.buttonDrawableId = buttonDrawableId;
        this.buttonStateNameStringId = buttonStateNameStringId;
        this.buttonValue = buttonValue;
        this.entryModel = entryModel;
    }

    public static SettingsBarButtonModel newButtonModel(@IdRes int id, @DrawableRes int buttonDrawableId, @StringRes int buttonStateNameStringId, int buttonValue, SettingsBarEntryModel entryModel) {
        SettingsBarButtonModel buttonModel = new SettingsBarButtonModel(id, buttonDrawableId, buttonStateNameStringId, buttonValue, entryModel);
        buttonModel.setButtonClickListener(v -> entryModel.select(buttonModel));
        return buttonModel;
    }

    /** A chip with a short text label instead of an icon. */
    public static SettingsBarButtonModel newTextButtonModel(@IdRes int id, String textLabel, @StringRes int buttonStateNameStringId, int buttonValue, SettingsBarEntryModel entryModel) {
        SettingsBarButtonModel buttonModel = new SettingsBarButtonModel(id, 0, buttonStateNameStringId, buttonValue, entryModel);
        buttonModel.textLabel = textLabel;
        buttonModel.setButtonClickListener(v -> entryModel.select(buttonModel));
        return buttonModel;
    }

    public SettingsBarButtonModel withShortLabel(String shortLabel) {
        this.shortLabel = shortLabel;
        return this;
    }

    public SettingsBarButtonModel withShortLabel(@StringRes int shortLabelStringId) {
        this.shortLabelStringId = shortLabelStringId;
        return this;
    }

    /** Short label of the value: the one set here, else the chip text, else the full state name. */
    public String getShortLabel(Context context) {
        if (shortLabel != null) return shortLabel;
        if (shortLabelStringId != 0) return context.getString(shortLabelStringId);
        if (textLabel != null) return textLabel;
        return context.getString(buttonStateNameStringId);
    }

    /** Icon of the value; a text chip has none of its own and takes the parameter icon. */
    @DrawableRes
    public int getIconDrawableId() {
        return buttonDrawableId != 0 ? buttonDrawableId : entryModel.getIcon();
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public int getButtonDrawableId() {
        return buttonDrawableId;
    }

    public int getButtonStateNameStringId() {
        return buttonStateNameStringId;
    }

    public int getButtonValue() {
        return buttonValue;
    }

    public View.OnClickListener getButtonClickListener() {
        return buttonClickListener;
    }

    public void setButtonClickListener(View.OnClickListener buttonClickListener) {
        this.buttonClickListener = buttonClickListener;
    }

    public int getId() {
        return id;
    }

}
