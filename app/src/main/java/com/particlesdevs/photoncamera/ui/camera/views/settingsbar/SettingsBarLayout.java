/*
 *
 *  PhotonCamera
 *  SettingsBarLayout.java
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
import android.content.Intent;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.control.Vibration;
import com.particlesdevs.photoncamera.ui.camera.model.CameraFragmentModel;
import com.particlesdevs.photoncamera.ui.camera.model.SettingsBarButtonModel;
import com.particlesdevs.photoncamera.ui.camera.model.SettingsBarEntryModel;
import com.particlesdevs.photoncamera.ui.settings.SettingsActivity;

/**
 * Settings bottom sheet. It lives in a CoordinatorLayout with a {@link BottomSheetBehavior}
 * and has three levels: {@link #LEVEL_HIDDEN} (STATE_HIDDEN), {@link #LEVEL_PEEK}
 * (STATE_COLLAPSED) and {@link #LEVEL_FULL} (STATE_EXPANDED). One gesture moves one level:
 * the sheet is hideable only in PEEK and HIDDEN, so a fling from FULL lands in PEEK.
 * The sheet itself stays VISIBLE; the behavior moves it below the edge when hidden.
 */
public class SettingsBarLayout extends RelativeLayout implements SettingsBarListener {
    // Same values as the model, which app:sheetLevel binds straight to setSheetLevel(int).
    public static final int LEVEL_HIDDEN = CameraFragmentModel.SHEET_HIDDEN;
    public static final int LEVEL_PEEK = CameraFragmentModel.SHEET_PEEK;
    public static final int LEVEL_FULL = CameraFragmentModel.SHEET_FULL;

    /** Receives the level the sheet has settled at, whoever moved it (finger or code). */
    public interface OnSheetLevelListener {
        void onSheetLevelChanged(int level);
    }

    private final LinearLayout optionsContainer;
    private final Vibration vibration;
    private BottomSheetBehavior<SettingsBarLayout> behavior;
    /** Last settled level; -1 until the first one, so the first request is always applied. */
    private int sheetLevel = -1;
    /** Level requested before the behavior was reachable. */
    private int pendingLevel = -1;
    /** Last level passed to {@link #setSheetLevel(int)}; -1 before the first request. */
    private int requestedLevel = -1;
    private int maxSheetHeight;
    private OnSheetLevelListener levelListener;
    private View hiddenHandle;

    private final BottomSheetBehavior.BottomSheetCallback sheetCallback = new BottomSheetBehavior.BottomSheetCallback() {
        @Override
        public void onStateChanged(@NonNull View bottomSheet, int newState) {
            updateHiddenHandle();
            int level = levelForState(newState);
            if (level < 0) return;
            // One level per gesture: FULL can only be lowered to PEEK, PEEK can be hidden.
            // HIDDEN must stay hideable too, or the next layout puts the sheet back on screen.
            behavior.setHideable(level != LEVEL_FULL);
            sheetLevel = level;
            if (levelListener != null) levelListener.onSheetLevelChanged(level);
        }

        @Override
        public void onSlide(@NonNull View bottomSheet, float slideOffset) {
        }
    };

    public SettingsBarLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        // In the layout editor (isInEditMode) the PhotonCamera Application instance
        // is never created, so the static sPhotonCamera is null.
        vibration = isInEditMode() ? null : PhotonCamera.getVibration();
        setBackgroundResource(R.drawable.settings_sheet_background);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setId(R.id.settings_bar_scroll_view);
        scrollView.setPadding(dp(10), dp(10), dp(10), dp(5));
        // The BottomSheetBehavior only leaves a list alone if it is a nested scrolling child.
        // Without this it drags the sheet on every vertical move, so FULL could not scroll the
        // list; with it the list scrolls and a drag down at its top lowers the sheet.
        scrollView.setNestedScrollingEnabled(true);

        optionsContainer = new LinearLayout(context);
        optionsContainer.setOrientation(LinearLayout.VERTICAL);
        RelativeLayout.LayoutParams optionsContainerParam = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        scrollView.addView(optionsContainer, optionsContainerParam);

        LinearLayout settingsButtonContainer = new LinearLayout(context);
        settingsButtonContainer.setId(R.id.settings_bar_settings_button_container);
        settingsButtonContainer.setOrientation(LinearLayout.HORIZONTAL);
        settingsButtonContainer.setGravity(Gravity.END);
        RelativeLayout.LayoutParams settingsButtonContainerParam = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        settingsButtonContainerParam.setMargins(dp(5), dp(0), dp(5), dp(5));
        settingsButtonContainerParam.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);

        ImageButton settingsButton = new ImageButton(context);
        settingsButton.setImageResource(R.drawable.ic_settings);
        settingsButton.setBackgroundResource(getResolvedAttr(context, android.R.attr.selectableItemBackgroundBorderless));
        settingsButton.setPadding(dp(10), dp(5), dp(10), dp(5));
        settingsButton.setOnClickListener(v -> context.startActivity(new Intent(context, SettingsActivity.class)));
        LayoutParams buttonParam = new LayoutParams(dp(35), dp(35));
        buttonParam.setMargins(dp(10), dp(2.5f), dp(20), dp(2.5f));
        settingsButtonContainer.addView(settingsButton, buttonParam);

        RelativeLayout.LayoutParams scrollViewParam = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        scrollViewParam.addRule(ABOVE, R.id.settings_bar_settings_button_container);

        addView(scrollView, scrollViewParam);
        addView(settingsButtonContainer, settingsButtonContainerParam);
    }

    public void addEntry(SettingsBarEntryModel entryModel) {
        entryModel.setSettingsBarListener(this);
        SettingsBarEntryView entryView = new SettingsBarEntryView(getContext());
        entryView.setId(entryModel.getId());
        entryView.setSettingsBarEntryModel(entryModel);
        optionsContainer.addView(entryView);
    }

    private int dp(float f) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, f, getContext().getResources().getDisplayMetrics());
    }

    @Override
    public void onEntryUpdated(SettingsBarEntryModel entryModel, SettingsBarButtonModel buttonModel) {
        vibration.Click();
        for (SettingsBarButtonModel model : entryModel.getSettingsBarButtonModels()) {
            findViewById(entryModel.getId()).findViewById(model.getId()).setSelected(model.isSelected());
        }
        ((TextView) findViewById(entryModel.getId()).findViewById(android.R.id.summary)).setText(entryModel.getStateTextStringId());
    }

    public void removeEntries() {
        if (optionsContainer != null) {
            optionsContainer.removeAllViews();
        }
    }

    private int getResolvedAttr(Context context, int attrId) {
        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(attrId, outValue, true);
        return outValue.resourceId;
    }

    public void setChildVisibility(@IdRes int id, int visibility) {
        View view = findViewById(id);
        if (view != null) {
            view.setVisibility(visibility);
        }
    }

    /**
     * Moves the sheet to a level ({@code app:sheetLevel} binding). Idempotent: compares with the
     * behavior state, not with {@link #sheetLevel}. Skipped while a finger drags the sheet (its
     * settle reports the level back). A new level is applied while settling, since setState
     * restarts the settle; a repeat of the last request is not (see below).
     */
    public void setSheetLevel(int level) {
        level = Math.max(LEVEL_HIDDEN, Math.min(LEVEL_FULL, level));
        // Any rebind sends the level again: invalidateAll in CameraUIViewImpl.refresh (camera
        // restart, mode switch) and notifyChange on rotation or a new thumbnail. After a fling the
        // model keeps the old level until the sheet settles, so a repeat while settling would pull
        // the sheet back to where the finger moved it from. A real request (Back, pause, a swipe)
        // changes the model level, so it is never a repeat and still restarts the settle.
        boolean repeat = level == requestedLevel;
        requestedLevel = level;
        BottomSheetBehavior<SettingsBarLayout> sheet = sheetBehavior();
        if (sheet == null) {
            pendingLevel = level;
            return;
        }
        pendingLevel = -1;
        int state = sheet.getState();
        if (state == BottomSheetBehavior.STATE_DRAGGING) return;
        if (repeat && state == BottomSheetBehavior.STATE_SETTLING) return;
        int target = stateForLevel(level);
        if (state != target) {
            // A hideable=false behavior silently drops a STATE_HIDDEN request.
            if (level != LEVEL_FULL) sheet.setHideable(true);
            sheet.setState(target);
        }
        // Before the first layout setState applies at once and no callback follows.
        // Once laid out the settle may still be posted, so the old state can be read here.
        int now = sheet.getState();
        if (level == LEVEL_FULL && now == BottomSheetBehavior.STATE_EXPANDED) sheet.setHideable(false);
        int settled = levelForState(now);
        if (settled >= 0) sheetLevel = settled;
        updateHiddenHandle();
    }

    /** Last settled level, or -1 before the first one. */
    public int getSheetLevel() {
        return sheetLevel;
    }

    public void setOnSheetLevelListener(@Nullable OnSheetLevelListener listener) {
        levelListener = listener;
    }

    /** The FULL height limit in pixels (a share of the viewfinder height). */
    public void setMaxSheetHeight(int px) {
        if (px <= 0 || px == maxSheetHeight) return;
        maxSheetHeight = px;
        BottomSheetBehavior<SettingsBarLayout> sheet = sheetBehavior();
        if (sheet == null) return;
        sheet.setMaxHeight(px);
        requestLayout();
    }

    /** Handle next to the sheet that stays on screen while the sheet is HIDDEN. */
    public void setHiddenHandle(@Nullable View handle) {
        hiddenHandle = handle;
        updateHiddenHandle();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (pendingLevel >= 0) setSheetLevel(pendingLevel);
    }

    /** The behavior from the CoordinatorLayout params, or null outside a CoordinatorLayout. */
    @Nullable
    private BottomSheetBehavior<SettingsBarLayout> sheetBehavior() {
        if (behavior == null) {
            ViewGroup.LayoutParams params = getLayoutParams();
            if (!(params instanceof CoordinatorLayout.LayoutParams)
                    || !(((CoordinatorLayout.LayoutParams) params).getBehavior() instanceof BottomSheetBehavior)) {
                return null;
            }
            behavior = BottomSheetBehavior.from(this);
            behavior.addBottomSheetCallback(sheetCallback);
            if (maxSheetHeight > 0) {
                behavior.setMaxHeight(maxSheetHeight);
                requestLayout();
            }
        }
        return behavior;
    }

    private void updateHiddenHandle() {
        if (hiddenHandle == null) return;
        boolean hidden = behavior != null && behavior.getState() == BottomSheetBehavior.STATE_HIDDEN;
        hiddenHandle.setVisibility(hidden ? View.VISIBLE : View.GONE);
    }

    private static int stateForLevel(int level) {
        switch (level) {
            case LEVEL_FULL:
                return BottomSheetBehavior.STATE_EXPANDED;
            case LEVEL_PEEK:
                return BottomSheetBehavior.STATE_COLLAPSED;
            default:
                return BottomSheetBehavior.STATE_HIDDEN;
        }
    }

    /** Level of a settled state, -1 for dragging and settling. */
    private static int levelForState(int state) {
        switch (state) {
            case BottomSheetBehavior.STATE_EXPANDED:
                return LEVEL_FULL;
            case BottomSheetBehavior.STATE_COLLAPSED:
            case BottomSheetBehavior.STATE_HALF_EXPANDED:
                return LEVEL_PEEK;
            case BottomSheetBehavior.STATE_HIDDEN:
                return LEVEL_HIDDEN;
            default:
                return -1;
        }
    }
}
