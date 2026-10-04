/*
 *
 *  PhotonCamera
 *  SettingsBarEntryView.java
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

package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.shape.AbsoluteCornerSize;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.ui.camera.model.SettingsBarButtonModel;
import com.particlesdevs.photoncamera.ui.camera.model.SettingsBarEntryModel;

/**
 * One parameter row of the settings sheet (SHADE_SPEC). On top: the parameter icon, its name
 * (wraps, never cut) and a pin. Below: a full-width segmented switch with one segment per value.
 * Flash, timer and grid segments show the value icon, the others a short label; the full value
 * name is the segment's tooltip and content description. A tap on a segment selects the value
 * through {@link SettingsBarEntryModel#select}, as the old buttons did.
 */
public class SettingsBarEntryView extends LinearLayout {
    /** Taps on the pin. The sheet owns the pinned list and calls {@link #setPinned} back. */
    public interface OnPinClickListener {
        void onPinClick(SettingsBarEntryModel entryModel);
    }

    private static final int SEGMENT_HEIGHT_DP = 28;
    private static final int SEGMENT_CORNER_DP = 9;

    private final ImageView iconView;
    private final TextView titleTextView;
    private final ImageButton pinButton;
    private final MaterialButtonToggleGroup segmentGroup;
    /** Button model per segment id. */
    private final SparseArray<SettingsBarButtonModel> buttonModels = new SparseArray<>();
    private SettingsBarEntryModel entryModel;
    private OnPinClickListener pinClickListener;
    private boolean pinned;
    /** True while the code checks a segment, so the change is not taken for a tap. */
    private boolean checkingFromCode;
    /** Typeface the segments are created with; the chosen one is drawn bold. */
    private Typeface segmentTypeface;

    public SettingsBarEntryView(Context context) {
        super(context);
        setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setOrientation(VERTICAL);
        setPadding(dp(10), 0, dp(10), dp(10));

        // Header: icon, name, pin. The pin's 32dp box reaches 8dp into the row padding,
        // so its 16dp icon lines up with the end of the segment track.
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams headerParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.setMarginEnd(-dp(8));

        iconView = new ImageView(context);
        iconView.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.sheet_dim)));
        iconView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams iconParams = new LayoutParams(dp(16), dp(16));
        iconParams.setMarginEnd(dp(6));
        header.addView(iconView, iconParams);

        // The name wraps onto more lines instead of being cut (no maxLines, no ellipsize).
        titleTextView = new TextView(context);
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleTextView.setTypeface(titleTextView.getTypeface(), Typeface.BOLD);
        titleTextView.setTextColor(ContextCompat.getColor(context, R.color.sheet_fg));
        titleTextView.setLineSpacing(0f, 1.15f);
        header.addView(titleTextView, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pinButton = new ImageButton(context);
        pinButton.setBackgroundResource(getResolvedAttr(context, android.R.attr.selectableItemBackgroundBorderless));
        pinButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pinButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        pinButton.setImageTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_selected}, new int[]{}},
                new int[]{ContextCompat.getColor(context, R.color.sheet_accent), ContextCompat.getColor(context, R.color.sheet_dim)}));
        pinButton.setOnClickListener(v -> {
            if (pinClickListener != null && entryModel != null) pinClickListener.onPinClick(entryModel);
        });
        header.addView(pinButton, new LayoutParams(dp(32), dp(32)));
        addView(header, headerParams);

        // Segmented switch: one value is always chosen, a tap on another one chooses it.
        segmentGroup = new MaterialButtonToggleGroup(context);
        segmentGroup.setOrientation(HORIZONTAL);
        // Labels autosize one by one; baseline alignment would then shift the chips vertically.
        segmentGroup.setBaselineAligned(false);
        segmentGroup.setSingleSelection(true);
        segmentGroup.setSelectionRequired(true);
        segmentGroup.setSpacing(dp(2));
        segmentGroup.setInnerCornerSize(new AbsoluteCornerSize(dp(SEGMENT_CORNER_DP)));
        segmentGroup.setBackgroundResource(R.drawable.sheet_segment_track);
        segmentGroup.setPadding(dp(2), dp(2), dp(2), dp(2));
        segmentGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            View segment = group.findViewById(checkedId);
            if (segment instanceof MaterialButton) setSegmentTypeface((MaterialButton) segment, isChecked);
            // The group also reports the segment that lost the check; only the newly checked
            // one is a choice, and only when a tap (not setChecked) checked it.
            if (!isChecked || checkingFromCode || entryModel == null) return;
            SettingsBarButtonModel buttonModel = buttonModels.get(checkedId);
            if (buttonModel != null) entryModel.select(buttonModel);
        });
        LayoutParams segmentParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        segmentParams.topMargin = dp(2);
        addView(segmentGroup, segmentParams);

        setPinned(false);
        setOnPinClickListener(null);
    }

    public void setSettingsBarEntryModel(SettingsBarEntryModel entryModel) {
        this.entryModel = entryModel;
        Context context = getContext();
        titleTextView.setText(entryModel.getTitleStringId());
        if (entryModel.getIcon() != 0) {
            iconView.setImageResource(entryModel.getIcon());
            iconView.setVisibility(VISIBLE);
        } else {
            iconView.setVisibility(GONE);
        }

        checkingFromCode = true;
        try {
            segmentGroup.clearChecked();
            segmentGroup.removeAllViews();
            buttonModels.clear();
            SettingsBarButtonModel[] models = entryModel.getSettingsBarButtonModels();
            int checkedId = View.NO_ID;
            if (models != null) {
                boolean valueIcons = showsValueIcons(entryModel);
                for (SettingsBarButtonModel model : models) {
                    MaterialButton segment = createSegment(context, model, valueIcons);
                    segmentGroup.addView(segment, new LayoutParams(0, dp(SEGMENT_HEIGHT_DP), 1f));
                    // The toggle group turns on end ellipsizing for every button it takes in;
                    // a label must never end in an ellipsis, so turn it off again.
                    segment.setEllipsize(null);
                    buttonModels.put(model.getId(), model);
                    if (model.isSelected()) checkedId = model.getId();
                }
            }
            // A button only becomes checkable inside the group, so check after adding.
            if (checkedId != View.NO_ID) segmentGroup.check(checkedId);
            segmentGroup.setVisibility(buttonModels.size() > 0 ? VISIBLE : GONE);
        } finally {
            checkingFromCode = false;
        }
    }

    /** Shows the value of {@code buttonId} as chosen without selecting it again. */
    public void setChecked(@IdRes int buttonId) {
        if (buttonModels.get(buttonId) == null || segmentGroup.getCheckedButtonId() == buttonId) return;
        checkingFromCode = true;
        try {
            segmentGroup.check(buttonId);
        } finally {
            checkingFromCode = false;
        }
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
        pinButton.setImageResource(pinned ? R.drawable.ic_sheet_pin_on : R.drawable.ic_sheet_pin);
        pinButton.setSelected(pinned);
        CharSequence description = getContext().getString(pinned ? R.string.sheet_pin_remove : R.string.sheet_pin_add);
        pinButton.setContentDescription(description);
        pinButton.setTooltipText(description);
    }

    public boolean isPinned() {
        return pinned;
    }

    /** The pin only reacts while a listener is set. */
    public void setOnPinClickListener(@Nullable OnPinClickListener listener) {
        pinClickListener = listener;
        pinButton.setEnabled(listener != null);
    }

    private MaterialButton createSegment(Context context, SettingsBarButtonModel model, boolean valueIcon) {
        MaterialButton segment = new MaterialButton(context);
        segment.setId(model.getId());
        // The settings are the source of truth. A MaterialButton saves its checked state and
        // restores it through setChecked, which the group reports as a tap; after a recreate
        // (theme change on return from SettingsActivity) a stale saved check would select the
        // old value again and overwrite a setting changed in the meantime.
        segment.setSaveEnabled(false);
        // Widget.Material3.Button sizes (24dp side padding, 4dp insets, 48/88dp minimum size,
        // 8dp icon padding) would leave ~15dp for the label of a 1/5 segment at 360dp.
        segment.setInsetTop(0);
        segment.setInsetBottom(0);
        segment.setPadding(dp(2), 0, dp(2), 0);
        segment.setMinHeight(0);
        segment.setMinWidth(0);
        segment.setMinimumHeight(0);
        segment.setMinimumWidth(0);
        segment.setStateListAnimator(null);
        // The group keeps the shape a button has when it is added: 9dp outer corners.
        segment.setCornerRadius(dp(SEGMENT_CORNER_DP));
        segment.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.sheet_segment_bg));
        segment.setRippleColor(ColorStateList.valueOf(0x29FFFFFF));
        ColorStateList foreground = ContextCompat.getColorStateList(context, R.color.sheet_segment_fg);
        segment.setTextColor(foreground);
        segment.setIconTint(foreground);
        segment.setMaxLines(1);
        segment.setEllipsize(null);
        if (valueIcon) {
            // Icon only, centred: no text, so TEXT_START puts the icon in the middle.
            segment.setIconResource(model.getIconDrawableId());
            segment.setIconSize(dp(18));
            segment.setIconPadding(0);
            segment.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        } else {
            segment.setText(model.getShortLabel(context));
            segment.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(segment, 8, 11, 1, TypedValue.COMPLEX_UNIT_SP);
        }
        if (segmentTypeface == null) segmentTypeface = segment.getTypeface();
        setSegmentTypeface(segment, false);
        CharSequence fullName = model.getButtonStateNameStringId() != 0
                ? context.getString(model.getButtonStateNameStringId())
                : model.getShortLabel(context);
        segment.setContentDescription(fullName);
        segment.setTooltipText(fullName);
        return segment;
    }

    private void setSegmentTypeface(MaterialButton segment, boolean checked) {
        segment.setTypeface(segmentTypeface, checked ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** SHADE_SPEC: flash, timer and grid show value icons; the other entries show short labels. */
    private static boolean showsValueIcons(SettingsBarEntryModel entryModel) {
        int id = entryModel.getId();
        return id == R.id.flash_entry_layout || id == R.id.timer_entry_layout || id == R.id.grid_entry_layout;
    }

    private int dp(float f) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, f, getContext().getResources().getDisplayMetrics());
    }

    private static int getResolvedAttr(Context context, int attrId) {
        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(attrId, outValue, true);
        return outValue.resourceId;
    }
}
