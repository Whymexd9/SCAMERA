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
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.control.Vibration;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingType;
import com.particlesdevs.photoncamera.ui.camera.model.CameraFragmentModel;
import com.particlesdevs.photoncamera.ui.camera.model.SettingsBarButtonModel;
import com.particlesdevs.photoncamera.ui.camera.model.SettingsBarEntryModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings bottom sheet (SHADE_SPEC). It lives in a CoordinatorLayout with a
 * {@link BottomSheetBehavior} and has three levels: {@link #LEVEL_HIDDEN} (STATE_HIDDEN),
 * {@link #LEVEL_PEEK} (STATE_COLLAPSED) and {@link #LEVEL_FULL} (STATE_EXPANDED). One gesture
 * moves one level: the sheet is hideable only in PEEK and HIDDEN, so a fling from FULL lands in
 * PEEK. The sheet itself stays VISIBLE; the behavior moves it below the edge when hidden.
 * <p>
 * Content, top to bottom: the handle, then a body that holds the PEEK rows over the accordion
 * list. PEEK shows only the pinned parameters. FULL shows the accordion: one header per group,
 * exactly one group open, its parameter rows below. The list waits below the PEEK rows and slides
 * up over them as the sheet opens, so neither the sheet top nor the list jumps when the rows hide.
 * <p>
 * The handle cycles the levels (P25, owner's answer 3): HIDDEN -> PEEK -> FULL -> HIDDEN. While
 * the sheet is HIDDEN a separate handle stays on screen: a tap or a fling up opens PEEK. In FULL a
 * scrim covers the viewfinder and the top bar: a tap or a fling down on it lowers the sheet to
 * PEEK, and the viewfinder gets neither focus nor pinch zoom.
 */
public class SettingsBarLayout extends LinearLayout implements SettingsBarListener {
    // Same values as the model, which app:sheetLevel binds straight to setSheetLevel(int).
    public static final int LEVEL_HIDDEN = CameraFragmentModel.SHEET_HIDDEN;
    public static final int LEVEL_PEEK = CameraFragmentModel.SHEET_PEEK;
    public static final int LEVEL_FULL = CameraFragmentModel.SHEET_FULL;

    /** Most quick buttons in the row; pinning one more drops the oldest pin. */
    private static final int MAX_QUICK_BUTTONS = 4;
    /** Quick buttons until the user pins others (SettingType names, oldest first). */
    private static final String DEFAULT_QUICK_BUTTONS = "FLASH,TIMER,RAW,GRID";
    private static final int GROUP_COUNT = SettingsBarEntryModel.GROUP_COUNT;
    private static final int RIPPLE_COLOR = 0x29FFFFFF;

    /**
     * Receives the level the model should hold: the level the sheet has settled at, whoever moved
     * it (finger or code), and the level a tap or fling on a handle asks for.
     */
    public interface OnSheetLevelListener {
        void onSheetLevelChanged(int level);
    }

    /** One quick button: a column with the value circle and an optional label under it. */
    private static final class QuickButton {
        final SettingsBarEntryModel entry;
        final LinearLayout root;
        final ImageView circle;
        final TextView label;

        QuickButton(SettingsBarEntryModel entry, LinearLayout root, ImageView circle, TextView label) {
            this.entry = entry;
            this.root = root;
            this.circle = circle;
            this.label = label;
        }
    }

    private final Vibration vibration;
    private final ImageView handle;
    /** Quick row and group cells (PEEK). They lie over the top of the list. */
    private final LinearLayout peekRows;
    private final LinearLayout quickRow;
    private final NestedScrollView listScroll;
    private final LinearLayout[] groupHeaders = new LinearLayout[GROUP_COUNT];
    private final ImageView[] headerChevrons = new ImageView[GROUP_COUNT];
    private final LinearLayout[] headerIcons = new LinearLayout[GROUP_COUNT];
    /** Parameter rows of each group; a closed group hides this container. */
    private final LinearLayout[] groupRows = new LinearLayout[GROUP_COUNT];

    /** Entries in the order they were added. */
    private final List<SettingsBarEntryModel> entries = new ArrayList<>();
    /** Parameter row per entry id (rows keep the entry id, as the old bar did). */
    private final SparseArray<SettingsBarEntryView> entryViews = new SparseArray<>();
    /** Quick button per entry id; quick buttons have no ids of their own. */
    private final SparseArray<QuickButton> quickButtons = new SparseArray<>();
    /** Entries hidden by {@link #setChildVisibility}: id to GONE/INVISIBLE; kept across rebuilds. */
    private final SparseIntArray hiddenEntries = new SparseIntArray();
    /** Pinned parameters (SettingType names), oldest first; stored in ui_sheet_quick (PreferenceKeys.getSheetQuick). */
    private final List<String> quickTypes = loadQuickTypes();
    private int openGroup = SettingsBarEntryModel.GROUP_SHOOT;
    /** 0 = PEEK look (rows shown, list below them), 1 = FULL look (list only). */
    private float fullness;
    /** Height of the PEEK rows from the last layout; -1 before it. */
    private int peekRowsHeight = -1;
    /** Handle plus PEEK rows from the last layout: the peek height the behavior should have. */
    private int sheetPeekHeight;
    private boolean peekUpdatePosted;

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
    /** View under the HIDDEN handle that keeps the touches starting on it. */
    private View hiddenHandlePassThrough;
    /** Scrim pieces over the viewfinder and the top bar, shown in FULL. */
    private View[] scrims = new View[0];

    private final BottomSheetBehavior.BottomSheetCallback sheetCallback = new BottomSheetBehavior.BottomSheetCallback() {
        @Override
        public void onStateChanged(@NonNull View bottomSheet, int newState) {
            updateHiddenHandle();
            syncLook(newState);
            if (newState == BottomSheetBehavior.STATE_EXPANDED) updateScrim(1f);
            else if (newState != BottomSheetBehavior.STATE_DRAGGING && newState != BottomSheetBehavior.STATE_SETTLING) updateScrim(0f);
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
            // 0 at PEEK, 1 at FULL; between HIDDEN and PEEK (negative) the PEEK look stays.
            applyLook(Math.max(0f, Math.min(1f, slideOffset)));
            updateScrim(Math.max(0f, Math.min(1f, slideOffset)));
        }
    };

    /** Applies a changed peek height outside the layout pass (see {@link #onLayout}). */
    private final Runnable peekUpdate = () -> {
        peekUpdatePosted = false;
        BottomSheetBehavior<SettingsBarLayout> sheet = sheetBehavior();
        if (sheet != null && sheetPeekHeight > 0 && sheet.getPeekHeight() != sheetPeekHeight) {
            // Animated: a sheet in PEEK settles at the new height instead of jumping.
            sheet.setPeekHeight(sheetPeekHeight, true);
        }
    };

    public SettingsBarLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        // In the layout editor (isInEditMode) the PhotonCamera Application instance
        // is never created, so the static sPhotonCamera is null.
        vibration = isInEditMode() ? null : PhotonCamera.getVibration();
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.settings_sheet_background);

        // Handle at the top of the sheet. A tap goes on round the cycle: PEEK -> FULL -> HIDDEN.
        // A drag past the touch slop is taken by the BottomSheetBehavior, which moves the sheet.
        handle = new ImageView(context);
        handle.setImageResource(R.drawable.sheet_handle);
        handle.setScaleType(ImageView.ScaleType.CENTER);
        handle.setContentDescription(context.getString(R.string.sheet_handle_toggle));
        handle.setOnClickListener(v -> requestSheetLevel(nextHandleLevel(sheetLevel)));
        addView(handle, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));

        // Body under the handle: the accordion list fills it, the PEEK rows lie over its top.
        FrameLayout body = new FrameLayout(context);
        addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // The BottomSheetBehavior leaves a list alone only if it is a nested scrolling child, so
        // FULL scrolls the list, and a drag down at its top lowers the sheet to PEEK.
        listScroll = new NestedScrollView(context);
        listScroll.setId(R.id.settings_bar_scroll_view);
        listScroll.setNestedScrollingEnabled(true);
        listScroll.setPadding(dp(6), 0, dp(6), dp(8));
        LinearLayout listContent = new LinearLayout(context);
        listContent.setOrientation(VERTICAL);
        for (int group = 0; group < GROUP_COUNT; group++) {
            listContent.addView(createGroupHeader(context, group),
                    new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // Rows of all groups are built at once; a closed group hides this container.
            LinearLayout rows = new LinearLayout(context);
            rows.setOrientation(VERTICAL);
            groupRows[group] = rows;
            listContent.addView(rows, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        listScroll.addView(listContent, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(listScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        peekRows = new LinearLayout(context);
        peekRows.setOrientation(VERTICAL);
        // Quick buttons: each is a quarter of the row (weight 1 of 4), so 1-3 buttons are
        // centred by the gravity. The row is GONE while it holds no visible button.
        quickRow = new LinearLayout(context);
        quickRow.setOrientation(HORIZONTAL);
        quickRow.setGravity(Gravity.CENTER_HORIZONTAL);
        quickRow.setWeightSum(MAX_QUICK_BUTTONS);
        quickRow.setBaselineAligned(false);
        quickRow.setPadding(dp(10), 0, dp(10), dp(8));
        quickRow.setVisibility(GONE);
        peekRows.addView(quickRow, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(peekRows, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        openGroup(openGroup, false);
        refreshGroupSummaries();
        applyLook(0f);
    }

    public void addEntry(SettingsBarEntryModel entryModel) {
        entryModel.setSettingsBarListener(this);
        SettingsBarEntryView entryView = new SettingsBarEntryView(getContext());
        entryView.setId(entryModel.getId());
        entryView.setSettingsBarEntryModel(entryModel);
        entryView.setPinned(quickTypes.contains(typeName(entryModel)));
        entryView.setOnPinClickListener(this::togglePin);
        entryView.setVisibility(hiddenEntries.get(entryModel.getId(), VISIBLE));
        entryView.setEnabled(isEnabled());
        groupRows[groupOf(entryModel)].addView(entryView);
        entries.add(entryModel);
        entryViews.put(entryModel.getId(), entryView);
    }

    /**
     * End of a batch of {@link #addEntry} calls (SettingsBarEntryProvider.addEntries): builds the
     * quick buttons and the group summaries. The open group, the list scroll position (the list
     * view itself stays) and the hidden entries are kept across the rebuild.
     */
    public void onEntriesAdded() {
        // Pins of parameters the sheet no longer has (an older build's choice) would hold a slot with no button.
        if (quickTypes.removeIf(type -> entryForType(type) == null)) PreferenceKeys.setSheetQuick(String.join(",", quickTypes));
        rebuildQuickRow();
        refreshGroupSummaries();
    }

    private int dp(float f) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, f, getContext().getResources().getDisplayMetrics());
    }

    @Override
    public void onEntryUpdated(SettingsBarEntryModel entryModel, SettingsBarButtonModel buttonModel) {
        if (vibration != null) vibration.Click();
        // The row shows the chosen value as its checked segment (no summary text any more).
        SettingsBarEntryView entryView = entryViews.get(entryModel.getId());
        if (entryView != null) entryView.setChecked(buttonModel.getId());
        QuickButton quick = quickButtons.get(entryModel.getId());
        if (quick != null) bindQuickButton(quick);
        refreshGroupSummary(groupOf(entryModel));
    }

    public void removeEntries() {
        for (LinearLayout rows : groupRows) rows.removeAllViews();
        entries.clear();
        entryViews.clear();
        quickRow.removeAllViews();
        quickButtons.clear();
    }

    private int getResolvedAttr(Context context, int attrId) {
        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(attrId, outValue, true);
        return outValue.resourceId;
    }

    /**
     * Shows or hides a parameter (by its entry id) for the current mode or lens. The choice is
     * kept and applies to the parameter row, its quick button and the group summaries, also after
     * the entries are built again.
     */
    public void setChildVisibility(@IdRes int id, int visibility) {
        if (visibility == VISIBLE) {
            hiddenEntries.delete(id);
        } else {
            hiddenEntries.put(id, visibility);
        }
        SettingsBarEntryView entryView = entryViews.get(id);
        if (entryView != null) entryView.setVisibility(visibility);
        QuickButton quick = quickButtons.get(id);
        if (quick != null) {
            quick.root.setVisibility(visibility == VISIBLE ? VISIBLE : GONE);
            updateQuickRowVisibility();
        }
        // A hidden parameter no longer counts as changed in the cells and headers.
        refreshGroupSummaries();
    }

    /**
     * Locks the sheet during a burst (lockUIForBurst): its controls stop reacting and it cannot
     * be dragged. Code can still move it (swipes on the viewfinder, Back).
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        for (int i = 0; i < getChildCount(); i++) setTreeEnabled(getChildAt(i), enabled);
        updateDraggable();
    }

    /** A finger may drag the sheet unless a burst locks it. */
    private void updateDraggable() {
        BottomSheetBehavior<SettingsBarLayout> sheet = sheetBehavior();
        if (sheet != null) sheet.setDraggable(isDraggable());
    }

    private boolean isDraggable() {
        return isEnabled();
    }

    private static void setTreeEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        // A parameter row enables its own segments and pin.
        if (view instanceof SettingsBarEntryView || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) setTreeEnabled(group.getChildAt(i), enabled);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // FULL always has the same height, the 66% limit (as in the mock): opening another group
        // never moves the top of the sheet. The behavior measures the sheet AT_MOST that limit.
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        if (maxSheetHeight > 0 && mode != MeasureSpec.EXACTLY) {
            int size = mode == MeasureSpec.UNSPECIFIED
                    ? maxSheetHeight
                    : Math.min(MeasureSpec.getSize(heightMeasureSpec), maxSheetHeight);
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // In FULL the rows are INVISIBLE, not GONE, so their height stays valid here.
        int rowsHeight = peekRows.getHeight();
        if (rowsHeight != peekRowsHeight) {
            peekRowsHeight = rowsHeight;
            applyLook(fullness);
        }
        int peek = handle.getBottom() + rowsHeight;
        if (peek > 0 && peek != sheetPeekHeight) {
            sheetPeekHeight = peek;
            // This runs inside the behavior's onLayoutChild; set the peek after the pass.
            if (!peekUpdatePosted) {
                peekUpdatePosted = true;
                post(peekUpdate);
            }
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
        if (settled >= 0) {
            sheetLevel = settled;
            syncLook(now);
            updateScrim(settled == LEVEL_FULL ? 1f : 0f);
        }
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

    /**
     * Handle next to the sheet that stays on screen while the sheet is HIDDEN. A tap or a fling up
     * opens PEEK (no touch focus). The handle takes the touches that start on it, so
     * the viewfinder swipe never sees them. A touch that starts on {@code passThrough} (the lens
     * strip under the handle) is left to that view, as before the handle took touches.
     */
    public void setHiddenHandle(@Nullable View handle, @Nullable View passThrough) {
        if (hiddenHandle != null && hiddenHandle != handle) {
            hiddenHandle.setOnTouchListener(null);
            hiddenHandle.setAccessibilityDelegate(null);
        }
        hiddenHandle = handle;
        hiddenHandlePassThrough = passThrough;
        if (handle != null) {
            handle.setOnTouchListener(createHiddenHandleTouchListener());
            // The handle must stay non-clickable (a clickable view takes the DOWN of the lens
            // strip under it), so TalkBack gets its tap as an accessibility click action.
            handle.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClickable(true);
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                }

                @Override
                public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                        requestSheetLevel(LEVEL_PEEK);
                        return true;
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
        }
        updateHiddenHandle();
    }

    private View.OnTouchListener createHiddenHandleTouchListener() {
        GestureDetector detector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                requestSheetLevel(LEVEL_PEEK);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (velocityY >= 0 || Math.abs(velocityY) <= Math.abs(velocityX)) return false;
                requestSheetLevel(LEVEL_PEEK);
                return true;
            }
        });
        // The handle has no long press, so a slow tap still opens PEEK.
        detector.setIsLongpressEnabled(false);
        return (v, event) -> {
            // Not taking the DOWN leaves the whole gesture to the views below the handle.
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isTouchOn(hiddenHandlePassThrough, event)) {
                return false;
            }
            return detector.onTouchEvent(event);
        };
    }

    /** The handle's cycle: HIDDEN -> PEEK -> FULL -> HIDDEN. */
    static int nextHandleLevel(int level) {
        return level == LEVEL_FULL ? LEVEL_HIDDEN : Math.max(LEVEL_HIDDEN, level) + 1;
    }

    /**
     * The scrim of the FULL level: views over the viewfinder and the top bar (the top bar lies outside
     * camera_container). They follow the sheet between PEEK (gone) and FULL (shown), take every
     * touch, and a tap or a fling down on them lowers the sheet to PEEK.
     */
    public void setScrim(View... views) {
        scrims = views == null ? new View[0] : views;
        GestureDetector detector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                requestSheetLevel(LEVEL_PEEK);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (velocityY <= 0 || Math.abs(velocityY) <= Math.abs(velocityX)) return false;
                requestSheetLevel(LEVEL_PEEK);
                return true;
            }
        });
        detector.setIsLongpressEnabled(false);
        for (View scrim : scrims) {
            scrim.setContentDescription(getContext().getString(R.string.shade_scrim));
            scrim.setOnTouchListener((v, event) -> {
                detector.onTouchEvent(event);
                return true;
            });
            // TalkBack: a double tap lowers the sheet as a tap does.
            scrim.setOnClickListener(v -> requestSheetLevel(LEVEL_PEEK));
        }
        BottomSheetBehavior<SettingsBarLayout> sheet = sheetBehavior();
        updateScrim(sheet != null && sheet.getState() == BottomSheetBehavior.STATE_EXPANDED ? 1f : 0f);
    }

    private void updateScrim(float full) {
        for (View scrim : scrims) {
            scrim.setAlpha(full);
            scrim.setVisibility(full > 0f ? VISIBLE : GONE);
        }
    }

    /**
     * A level asked for from a handle (tap or fling) or a group cell. It goes to the model first;
     * the binding then sends it back as a repeat. It is also applied here at once.
     */
    private void requestSheetLevel(int level) {
        if (levelListener != null) levelListener.onSheetLevelChanged(level);
        setSheetLevel(level);
    }

    /** True when the touch starts inside a view that is on screen. */
    private static boolean isTouchOn(@Nullable View view, MotionEvent event) {
        if (view == null || !view.isShown() || view.getAlpha() == 0f) return false;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float x = event.getRawX() - location[0];
        float y = event.getRawY() - location[1];
        return x >= 0 && y >= 0 && x < view.getWidth() && y < view.getHeight();
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
            behavior.setDraggable(isDraggable());
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

    // ---------------------------------------------------------------- PEEK / FULL look

    /**
     * Rows and list for a behavior state. FULL hides the quick buttons and group cells; they are
     * INVISIBLE (no touches, not read by TalkBack) and keep their height for the peek. While the
     * sheet moves they are shown and fade with {@link #applyLook}.
     */
    private void syncLook(int state) {
        if (state == BottomSheetBehavior.STATE_EXPANDED) {
            applyLook(1f);
            peekRows.setVisibility(INVISIBLE);
        } else {
            peekRows.setVisibility(VISIBLE);
            if (state != BottomSheetBehavior.STATE_DRAGGING && state != BottomSheetBehavior.STATE_SETTLING) {
                applyLook(0f);
            }
        }
    }

    /**
     * 0 = PEEK: the rows show and the list waits right below them (out of view, and out of the
     * rows' touch area, so a drag that starts on the rows moves the sheet). 1 = FULL: the list
     * fills the body. In between the list slides up and the rows fade out. Only translation and
     * alpha change, no layout.
     */
    private void applyLook(float full) {
        fullness = full;
        peekRows.setAlpha(1f - full);
        listScroll.setAlpha(full);
        listScroll.setTranslationY(Math.max(0, peekRowsHeight) * (1f - full));
    }

    // ---------------------------------------------------------------- accordion

    private LinearLayout createGroupHeader(Context context, int group) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(8), dp(10), dp(8));
        header.setBackgroundResource(getResolvedAttr(context, android.R.attr.selectableItemBackground));
        header.setOnClickListener(v -> openGroup(group, true));

        ImageView chevron = new ImageView(context);
        chevron.setImageTintList(colorList(context, R.color.sheet_dim));
        chevron.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams chevronParams = new LayoutParams(dp(20), dp(20));
        chevronParams.setMarginEnd(dp(4));
        header.addView(chevron, chevronParams);

        // The group name wraps instead of being cut.
        TextView name = new AppCompatTextView(context);
        name.setText(SettingsBarEntryModel.getGroupTitleStringId(group));
        name.setAllCaps(true);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        name.setTypeface(name.getTypeface(), Typeface.BOLD);
        name.setLetterSpacing(0.07f);
        name.setTextColor(ContextCompat.getColor(context, R.color.sheet_dim));
        header.addView(name, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout icons = createIconsRow(context);
        LayoutParams iconsParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(16));
        iconsParams.setMarginStart(dp(8));
        header.addView(icons, iconsParams);

        groupHeaders[group] = header;
        headerChevrons[group] = chevron;
        headerIcons[group] = icons;
        return header;
    }

    /** Opens one group and closes the others: exactly one group is open at any time. */
    private void openGroup(int group, boolean scrollToTop) {
        openGroup = Math.max(0, Math.min(GROUP_COUNT - 1, group));
        for (int g = 0; g < GROUP_COUNT; g++) {
            boolean open = g == openGroup;
            groupRows[g].setVisibility(open ? VISIBLE : GONE);
            headerChevrons[g].setImageResource(open ? R.drawable.ic_sheet_expand_less : R.drawable.ic_sheet_expand_more);
        }
        if (scrollToTop) listScroll.scrollTo(0, 0);
    }

    // ---------------------------------------------------------------- group summaries

    private LinearLayout createIconsRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        row.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return row;
    }

    private void refreshGroupSummaries() {
        for (int group = 0; group < GROUP_COUNT; group++) refreshGroupSummary(group);
    }

    /** Header of a group: icons of its changed parameters, and the spoken summary. */
    private void refreshGroupSummary(int group) {
        List<SettingsBarEntryModel> changed = new ArrayList<>();
        for (SettingsBarEntryModel entry : entries) {
            if (groupOf(entry) == group && isEntryShown(entry) && entry.isChanged()) changed.add(entry);
        }
        fillChangedIcons(headerIcons[group], changed, 0);
        StringBuilder description = new StringBuilder(getContext().getString(SettingsBarEntryModel.getGroupTitleStringId(group)));
        for (int i = 0; i < changed.size(); i++) {
            description.append(i == 0 ? ": " : ", ").append(describe(changed.get(i)));
        }
        groupHeaders[group].setContentDescription(description);
    }

    /**
     * Icons of the changed parameters in accent colour; past {@code limit} (0 = no limit) a
     * "+N" count, and a dim dash when nothing is changed.
     */
    private void fillChangedIcons(LinearLayout row, List<SettingsBarEntryModel> changed, int limit) {
        row.removeAllViews();
        Context context = getContext();
        if (changed.isEmpty()) {
            row.addView(createSummaryText(context, "\u2014", R.color.sheet_dim, false));
            return;
        }
        int shown = limit > 0 ? Math.min(limit, changed.size()) : changed.size();
        ColorStateList accent = colorList(context, R.color.sheet_accent);
        for (int i = 0; i < shown; i++) {
            ImageView icon = new ImageView(context);
            icon.setImageResource(iconOf(changed.get(i)));
            icon.setImageTintList(accent);
            LayoutParams params = new LayoutParams(dp(15), dp(15));
            if (i > 0) params.setMarginStart(dp(3));
            row.addView(icon, params);
        }
        if (changed.size() > shown) {
            LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(dp(3));
            row.addView(createSummaryText(context, "+" + (changed.size() - shown), R.color.sheet_accent, true), params);
        }
    }

    private TextView createSummaryText(Context context, String text, @ColorRes int color, boolean bold) {
        TextView view = new AppCompatTextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setTextColor(ContextCompat.getColor(context, color));
        view.setIncludeFontPadding(false);
        view.setMaxLines(1);
        view.setEllipsize(null);
        return view;
    }

    // ---------------------------------------------------------------- quick buttons

    private void rebuildQuickRow() {
        quickRow.removeAllViews();
        quickButtons.clear();
        Context context = getContext();
        for (String type : quickTypes) {
            SettingsBarEntryModel entry = entryForType(type);
            if (entry == null) continue;
            QuickButton quick = createQuickButton(context, entry);
            quickRow.addView(quick.root, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            quickButtons.put(entry.getId(), quick);
            quick.root.setVisibility(isEntryShown(entry) ? VISIBLE : GONE);
            setTreeEnabled(quick.root, isEnabled());
            bindQuickButton(quick);
        }
        updateQuickRowVisibility();
    }

    private QuickButton createQuickButton(Context context, SettingsBarEntryModel entry) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setOnClickListener(v -> selectNextValue(entry));

        // The circle shows the column's pressed and selected state: one ripple for the whole
        // button, and a white circle with a dark icon when the value differs from the default.
        ImageView circle = new ImageView(context);
        circle.setDuplicateParentStateEnabled(true);
        circle.setBackground(ripple(context, R.drawable.sheet_quick_circle, rippleMask(GradientDrawable.OVAL, 0)));
        circle.setScaleType(ImageView.ScaleType.FIT_CENTER);
        circle.setPadding(dp(11), dp(11), dp(11), dp(11));
        circle.setImageTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_selected}, new int[]{}},
                new int[]{ContextCompat.getColor(context, R.color.sheet_on_fg), ContextCompat.getColor(context, R.color.sheet_fg)}));
        circle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(circle, new LayoutParams(dp(42), dp(42)));

        // The label always takes its line (INVISIBLE when unused), so the row height and the
        // peek height do not change with the value.
        TextView label = new AppCompatTextView(context);
        label.setGravity(Gravity.CENTER);
        label.setTypeface(label.getTypeface(), Typeface.BOLD);
        label.setTextColor(ContextCompat.getColor(context, R.color.sheet_fg));
        fitOneLine(label, 10.5f);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams labelParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14));
        labelParams.topMargin = dp(4);
        root.addView(label, labelParams);
        return new QuickButton(entry, root, circle, label);
    }

    /** Icon of the current value; a label only where that icon does not tell the value. */
    private void bindQuickButton(QuickButton quick) {
        Context context = getContext();
        SettingsBarEntryModel entry = quick.entry;
        SettingsBarButtonModel selected = entry.getSelectedButtonModel();
        quick.circle.setImageResource(iconOf(entry));
        boolean labelled = selected != null && !iconShowsValue(entry, selected);
        quick.label.setText(labelled ? selected.getShortLabel(context) : "");
        quick.label.setVisibility(labelled ? VISIBLE : INVISIBLE);
        quick.root.setSelected(entry.isChanged());
        CharSequence description = describe(entry);
        quick.root.setContentDescription(description);
        quick.root.setTooltipText(description);
    }

    private void updateQuickRowVisibility() {
        boolean any = false;
        for (int i = 0; i < quickRow.getChildCount(); i++) {
            if (quickRow.getChildAt(i).getVisibility() == VISIBLE) {
                any = true;
                break;
            }
        }
        // The peek height follows on the next layout (onLayout).
        quickRow.setVisibility(any ? VISIBLE : GONE);
    }

    /** A tap on a quick button: the next value in the order of the segments, wrapping around. */
    private static void selectNextValue(SettingsBarEntryModel entry) {
        SettingsBarButtonModel[] models = entry.getSettingsBarButtonModels();
        if (models == null || models.length == 0) return;
        int next = 0;
        for (int i = 0; i < models.length; i++) {
            if (models[i].isSelected()) {
                next = (i + 1) % models.length;
                break;
            }
        }
        entry.select(models[next]);
    }

    /**
     * Stored pins: SettingType names that still exist, without repeats, at most MAX_QUICK_BUTTONS (the newest kept).
     * Nothing stored yet: the default buttons. An empty string is a valid choice (every pin removed).
     */
    private static List<String> loadQuickTypes() {
        String stored = PreferenceKeys.getSheetQuick();
        List<String> out = new ArrayList<>();
        for (String name : (stored != null ? stored : DEFAULT_QUICK_BUTTONS).split(",")) {
            String type = name.trim();
            if (type.isEmpty() || out.contains(type)) continue;
            try {
                SettingType.valueOf(type);
            } catch (IllegalArgumentException unknown) {
                continue;
            }
            out.add(type);
        }
        while (out.size() > MAX_QUICK_BUTTONS) out.remove(0);
        return out;
    }

    /** Pin: adds the parameter to the quick buttons or removes it; a fifth pin drops the oldest. Stored at once. */
    private void togglePin(SettingsBarEntryModel entryModel) {
        String type = typeName(entryModel);
        if (type == null) return;
        if (!quickTypes.remove(type)) {
            quickTypes.add(type);
            while (quickTypes.size() > MAX_QUICK_BUTTONS) quickTypes.remove(0);
        }
        PreferenceKeys.setSheetQuick(String.join(",", quickTypes));
        for (SettingsBarEntryModel entry : entries) {
            SettingsBarEntryView entryView = entryViews.get(entry.getId());
            if (entryView != null) entryView.setPinned(quickTypes.contains(typeName(entry)));
        }
        rebuildQuickRow();
    }

    // ---------------------------------------------------------------- helpers

    @Nullable
    private SettingsBarEntryModel entryForType(String type) {
        for (SettingsBarEntryModel entry : entries) {
            if (type.equals(typeName(entry))) return entry;
        }
        return null;
    }

    @Nullable
    private static String typeName(SettingsBarEntryModel entry) {
        Enum<SettingType> type = entry.getType();
        return type != null ? type.name() : null;
    }

    private static int groupOf(SettingsBarEntryModel entry) {
        return Math.max(0, Math.min(GROUP_COUNT - 1, entry.getGroup()));
    }

    private boolean isEntryShown(SettingsBarEntryModel entry) {
        return hiddenEntries.get(entry.getId(), VISIBLE) == VISIBLE;
    }

    /** Icon of the selected value, or the parameter icon before a value is known. */
    @DrawableRes
    private static int iconOf(SettingsBarEntryModel entry) {
        SettingsBarButtonModel selected = entry.getSelectedButtonModel();
        int icon = selected != null ? selected.getIconDrawableId() : 0;
        return icon != 0 ? icon : entry.getIcon();
    }

    /** True when no other value of the entry has the same icon, so the icon alone tells the value. */
    private static boolean iconShowsValue(SettingsBarEntryModel entry, SettingsBarButtonModel selected) {
        int icon = selected.getIconDrawableId();
        if (icon == 0) return false;
        for (SettingsBarButtonModel model : entry.getSettingsBarButtonModels()) {
            if (model != selected && model.getIconDrawableId() == icon) return false;
        }
        return true;
    }

    /** "Name: value" for TalkBack and tooltips. */
    private String describe(SettingsBarEntryModel entry) {
        Context context = getContext();
        String title = context.getString(entry.getTitleStringId());
        SettingsBarButtonModel selected = entry.getSelectedButtonModel();
        if (selected == null) return title;
        String value = selected.getButtonStateNameStringId() != 0
                ? context.getString(selected.getButtonStateNameStringId())
                : selected.getShortLabel(context);
        return title + ": " + value;
    }

    /** One line that shrinks (down to 8sp) instead of being cut or ending in an ellipsis. */
    private void fitOneLine(TextView view, float maxSp) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, maxSp);
        view.setMaxLines(1);
        view.setEllipsize(null);
        view.setIncludeFontPadding(false);
        int max = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, maxSp, getResources().getDisplayMetrics()));
        int min = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8, getResources().getDisplayMetrics()));
        // Autosize needs max > min (it throws otherwise) and a fixed view size (set by the caller).
        if (max > min) {
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(view, min, max, 1, TypedValue.COMPLEX_UNIT_PX);
        }
    }

    private static ColorStateList colorList(Context context, @ColorRes int color) {
        return ColorStateList.valueOf(ContextCompat.getColor(context, color));
    }

    /**
     * The drawable with a ripple inside {@code mask}. Without a mask the ripple would take the
     * content's alpha (a faint 6-8% fill) or, over the opaque white circle, fill the square.
     */
    private static RippleDrawable ripple(Context context, @DrawableRes int content, Drawable mask) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE_COLOR), ContextCompat.getDrawable(context, content), mask);
    }

    /** Opaque shape that bounds a ripple: an oval, or a rectangle with {@code radius} corners. */
    private static Drawable rippleMask(int shape, int radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(shape);
        if (radius > 0) mask.setCornerRadius(radius);
        mask.setColor(Color.WHITE);
        return mask;
    }
}
