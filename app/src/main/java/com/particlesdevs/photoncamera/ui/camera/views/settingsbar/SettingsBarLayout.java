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
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.particlesdevs.photoncamera.util.Lang;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.control.Vibration;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.settings.ShadeCatalog;
import com.particlesdevs.photoncamera.settings.ShadeTiles;
import com.particlesdevs.photoncamera.ui.camera.model.CameraFragmentModel;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * The quick-settings shade (P25, docs/settings-plan/SHADE_TASK.md). It lives in a CoordinatorLayout above the bottom bar
 * with a {@link BottomSheetBehavior} and has three levels: {@link #LEVEL_HIDDEN} (STATE_HIDDEN), {@link #LEVEL_PEEK}
 * (STATE_COLLAPSED) and {@link #LEVEL_FULL} (STATE_EXPANDED, 86 % of the viewfinder). One gesture moves one level: the
 * sheet is hideable only in PEEK and HIDDEN, so a fling from FULL lands in PEEK. The sheet itself stays VISIBLE; the
 * behavior moves it below the edge when hidden.
 * <p>
 * Content, one scroll list: the handle, the header «Закреплено», the grid of pinned tiles (4 columns, any number since
 * P167, then a dashed «Добавить» tile), the inline slider card of an open slider tile, then (seen in FULL) the curated
 * groups with an inline control and a pin per row. PEEK shows the list down to the grid (or the slider card). The
 * content is built on the first need ({@link #prewarm}, or the first level above HIDDEN), because it inflates the
 * settings tree.
 * <p>
 * The handle cycles the levels (owner's answer 3): HIDDEN -> PEEK -> FULL -> HIDDEN. While the sheet is HIDDEN a separate
 * handle stays on screen: a tap or a fling up opens PEEK. In FULL a scrim covers the viewfinder and the top bar: a tap
 * or a fling down on it lowers the sheet to PEEK, and the viewfinder gets neither focus nor pinch zoom.
 * <p>
 * Tiles and rows read and write the same keys as the settings screen (ShadeCatalog); a change from anywhere (settings,
 * a module switch, a config restore) refreshes them through one settings listener, once per batch.
 */
public class SettingsBarLayout extends LinearLayout {
    // Same values as the model, which app:sheetLevel binds straight to setSheetLevel(int).
    public static final int LEVEL_HIDDEN = CameraFragmentModel.SHEET_HIDDEN;
    public static final int LEVEL_PEEK = CameraFragmentModel.SHEET_PEEK;
    public static final int LEVEL_FULL = CameraFragmentModel.SHEET_FULL;

    /**
     * Receives the level the model should hold: the level the sheet has settled at, whoever moved
     * it (finger or code), and the level a tap or fling on a handle asks for.
     */
    public interface OnSheetLevelListener {
        void onSheetLevelChanged(int level);
    }

    private final Vibration vibration;
    private final ImageView handle;
    private final NestedScrollView listScroll;
    private final LinearLayout content;
    private final TextView headTitle;
    /** «Изменить» / «Готово». */
    private final TextView editButton;
    /** P143: the active config's name in a frame, opening the config menu (the header title only in the edit mode). */
    private final ConfigChip configChip;
    @androidx.annotation.Nullable private ConfigMenu configMenu;
    @androidx.annotation.Nullable private ConfigMenu.Actions configActions;
    /** The header's settings gear (hidden in the edit mode). */
    private final ImageView headGear;
    private final RecyclerView tiles;
    private final ItemTouchHelper dragHelper;
    private final ShadeTileAdapter adapter;
    /** Inline card of the open slider tile, under the grid. */
    private final LinearLayout sliderCard;
    private final TextView sliderTitle;
    private final TextView sliderPill;
    private final SeekBar sliderBar;
    /** FULL rows: the curated groups and the pinned settings outside them. */
    private final LinearLayout fullList;

    private ShadeHost host;
    private ShadeCatalog catalog;
    private ShadeRows rows;
    private boolean contentBuilt;
    /** Pinned keys in tile order (ui_shade_tiles). */
    private final List<String> pinned = new ArrayList<>();
    /** Pinned keys outside the curated groups, as the FULL rows were last built with. */
    private final List<String> extraRows = new ArrayList<>();
    @Nullable private String openSlider;
    private boolean sliderTracking;
    private float iconRotation;
    private boolean refreshPosted;
    /** Edit mode: tiles wobble, «×» unpins, a press-drag reorders. */
    private boolean editing;
    /** A tile is lifted under the finger. */
    private boolean dragging;
    /** A refresh that arrived during a drag, run on the drop. */
    private boolean refreshPending;

    /** Handle plus the PEEK part of the list from the last layout: the peek height the behavior should have. */
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
    /** P176: 16:9 - the HIDDEN handle is a pill over the preview, the sheet grows out of it and shrinks back (ShadeZoom). */
    private boolean pillHandle;
    @Nullable private android.animation.ValueAnimator zoom;
    static final float PILL_WIDTH_DP = 76;
    /** Scrim pieces over the viewfinder and the top bar, shown in FULL. */
    private View[] scrims = new View[0];

    private final BottomSheetBehavior.BottomSheetCallback sheetCallback = new BottomSheetBehavior.BottomSheetCallback() {
        @Override
        public void onStateChanged(@NonNull View bottomSheet, int newState) {
            updateHiddenHandle();
            if (newState == BottomSheetBehavior.STATE_EXPANDED) updateScrim(1f);
            else if (newState != BottomSheetBehavior.STATE_DRAGGING && newState != BottomSheetBehavior.STATE_SETTLING) updateScrim(0f);
            int level = levelForState(newState);
            if (level < 0) return;
            // One level per gesture: FULL can only be lowered to PEEK, PEEK can be hidden.
            // HIDDEN must stay hideable too, or the next layout puts the sheet back on screen.
            behavior.setHideable(level != LEVEL_FULL);
            sheetLevel = level;
            if (level != LEVEL_FULL) listScroll.scrollTo(0, 0);
            if (level == LEVEL_HIDDEN && editing) setEditing(false);
            if (level == LEVEL_HIDDEN) closeConfigMenu();
            else refreshConfigName();
            if (levelListener != null) levelListener.onSheetLevelChanged(level);
        }

        @Override
        public void onSlide(@NonNull View bottomSheet, float slideOffset) {
            // 0 at PEEK, 1 at FULL; between HIDDEN and PEEK (negative) there is no scrim.
            updateScrim(Math.max(0f, Math.min(1f, slideOffset)));
            updateShape();
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
        // In the layout editor (isInEditMode) the ScameraApp Application instance
        // is never created, so the static sApp is null.
        vibration = isInEditMode() ? null : ScameraApp.getVibration();
        setOrientation(VERTICAL);
        setBackground(ShadeStyle.sheet(context));
        // P185: the outline clips the sheet to its shape at 16:9 (the morph out of the pill, the rounded bottom)
        setOutlineProvider(sheetOutline);
        shapeStroke.setStyle(android.graphics.Paint.Style.STROKE);
        shapeStroke.setStrokeWidth(dp(1));
        shapeStroke.setColor(ShadeStyle.LINE);

        // Handle at the top of the sheet. A tap goes on round the cycle: PEEK -> FULL -> HIDDEN.
        // A drag past the touch slop is taken by the BottomSheetBehavior, which moves the sheet.
        handle = new ImageView(context);
        handle.setImageResource(R.drawable.sheet_handle);
        handle.setScaleType(ImageView.ScaleType.CENTER);
        handle.setContentDescription(context.getString(R.string.sheet_handle_toggle));
        handle.setOnClickListener(v -> requestSheetLevel(nextHandleLevel(sheetLevel)));
        // SIMPLE_UI §2: the concept's grab (10dp over a 40 x 4dp bar, 4dp under it), x the screen's scale
        addView(handle, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 18)));

        // The BottomSheetBehavior leaves a list alone only if it is a nested scrolling child, so
        // FULL scrolls the list, and a drag down at its top lowers the sheet to PEEK.
        listScroll = new NestedScrollView(context);
        listScroll.setId(R.id.settings_bar_scroll_view);
        listScroll.setNestedScrollingEnabled(true);
        listScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        listScroll.setVerticalScrollBarEnabled(false);
        addView(listScroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        // The tiles carry 5dp of the 10dp gap themselves: 11 + 5 = the 16dp edge.
        content.setPadding(dp(11), 0, dp(11), dp(20));
        listScroll.addView(content, new NestedScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        // SIMPLE_UI §2 header: the profile button (P255; «Перетащите плитку» while editing), «Изменить» / «Готово», the 40dp gear
        head.setPadding(com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 7), 0,
                com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 3),
                com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 6));
        headTitle = new TextView(context);
        headTitle.setText(R.string.shade_quick_title);
        headTitle.setTextColor(ShadeStyle.TEXT);
        headTitle.setMaxLines(1);
        headTitle.setTag("shade_title");
        headTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.textFixed(headTitle, 16.5f);
        // P143: the config button takes the title's place; the slot limits it, a long name shrinks inside its frame
        android.widget.FrameLayout headSlot = new android.widget.FrameLayout(context);
        headSlot.addView(headTitle, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        configChip = new ConfigChip(context);
        configChip.setOnClickListener(v -> toggleConfigMenu());
        // P255 (owner, 2026-10-19: «вернуть меню конфигов ... в шторке, но обнови его под нашу новую логику и стиль»): the
        // active profile's button in the title's place again; the title only while the tiles are edited
        headTitle.setVisibility(GONE);
        headSlot.addView(configChip, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.START));
        LayoutParams slotParams = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slotParams.setMarginEnd(dp(10));
        head.addView(headSlot, slotParams);
        editButton = new TextView(context);
        com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.textFixed(editButton, 13.5f);
        editButton.setGravity(Gravity.CENTER);
        editButton.setMinHeight(com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 40));
        editButton.setPadding(dp(4), 0, dp(4), 0);
        editButton.setTag("shade_edit");
        editButton.setOnClickListener(v -> setEditing(!editing));
        head.addView(editButton);
        headGear = new ImageView(context);
        headGear.setImageResource(R.drawable.ui_ic_gear);
        headGear.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int gearPad = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 10);
        headGear.setPadding(gearPad, gearPad, gearPad, gearPad);
        // the concept's .hgear: a 40dp disc, the card at 70 % with a line
        android.graphics.drawable.GradientDrawable gearDisc = new android.graphics.drawable.GradientDrawable();
        gearDisc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gearDisc.setColor(0xB31A1F22);
        gearDisc.setStroke(Math.max(1, dp(1)), ShadeStyle.LINE);
        headGear.setBackground(gearDisc);
        headGear.setContentDescription(context.getString(R.string.sheet_all_settings));
        headGear.setTooltipText(context.getString(R.string.sheet_all_settings));
        headGear.setTag("shade_settings");
        headGear.setOnClickListener(v -> {
            if (host != null) host.openSettings();
        });
        int gearSize = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 40);
        LayoutParams gearParams = new LayoutParams(gearSize, gearSize);
        gearParams.setMarginStart(dp(6));
        head.addView(headGear, gearParams);
        content.addView(head, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 46)));

        tiles = new RecyclerView(context);
        tiles.setLayoutManager(new GridLayoutManager(context, 4));
        tiles.setNestedScrollingEnabled(false);
        tiles.setOverScrollMode(OVER_SCROLL_NEVER);
        tiles.setClipChildren(false);
        tiles.setClipToPadding(false);
        tiles.setTag("shade_tiles");
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        tiles.setItemAnimator(animator);
        adapter = new ShadeTileAdapter(new ShadeTileAdapter.Binder() {
            @Override
            public void bind(ShadeTileView view, String key) {
                bindTile(view, key);
            }

            @Override
            public void bindAdd(ShadeTileView view) {
                view.bindAdd();
                view.setIconRotation(iconRotation);
            }

            @Override
            public void onCreated(ShadeTileAdapter.Holder holder) {
                holder.tile.setOnClickListener(v -> {
                    if (holder.tile.addTile) onAddTile();
                    else if (holder.tile.key != null && !editing) onTileTap(holder.tile.key);
                });
                holder.tile.remove.setOnClickListener(v -> {
                    if (holder.tile.key != null) togglePin(holder.tile.key);
                });
                holder.tile.setOnTouchListener(new TilePress(holder));
                holder.tile.setAccessibilityDelegate(new TileActions(holder));
            }
        });
        tiles.setAdapter(adapter);
        dragHelper = new ItemTouchHelper(new DragCallback());
        dragHelper.attachToRecyclerView(tiles);
        content.addView(tiles, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sliderCard = new LinearLayout(context);
        sliderCard.setOrientation(VERTICAL);
        sliderCard.setPadding(dp(16), dp(14), dp(16), dp(10));
        sliderCard.setBackground(ShadeStyle.card(context, ShadeStyle.CARD, ShadeStyle.LINE, 20));
        sliderCard.setVisibility(GONE);
        sliderCard.setTag("shade_slider_card");
        LinearLayout sliderTop = new LinearLayout(context);
        sliderTop.setGravity(Gravity.CENTER_VERTICAL);
        sliderTitle = new TextView(context);
        sliderTitle.setTextColor(ShadeStyle.TEXT);
        sliderTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.title(sliderTitle); // P139
        sliderTop.addView(sliderTitle, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int accent = isInEditMode() ? 0xFFFFD447 : ShadeStyle.accent(context);
        sliderPill = ShadeRows.valuePill(context, accent);
        sliderTop.addView(sliderPill);
        sliderCard.addView(sliderTop);
        sliderBar = new SeekBar(context);
        sliderBar.setTag("shade_slider_card_bar");
        sliderBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                ShadeCatalog.Entry e = openSliderEntry();
                if (fromUser && e != null) sliderPill.setText(ShadeCatalog.formatNumber(e, ShadeRows.valueAt(e, progress)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                sliderTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                sliderTracking = false;
                ShadeCatalog.Entry e = openSliderEntry();
                if (e != null) apply(e, ShadeRows.valueAt(e, seekBar.getProgress()));
            }
        });
        LayoutParams barParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        barParams.topMargin = dp(6);
        sliderCard.addView(sliderBar, barParams);
        LayoutParams cardParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(dp(5), dp(7), dp(5), 0);
        content.addView(sliderCard, cardParams);

        fullList = new LinearLayout(context);
        fullList.setOrientation(VERTICAL);
        fullList.setPadding(dp(5), 0, dp(5), 0);
        content.addView(fullList, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        styleEditButton(accent);
        headGear.setImageTintList(ColorStateList.valueOf(ShadeStyle.TEXT));
        configChip.setAccent(accent);
        refreshConfigName();
    }

    // ───────────────────────────────── P143 / P255: the profile button and its menu

    /** The active profile's name on the button (after a save, an import, the reset; when the shade shows). */
    public void refreshConfigName() {
        configChip.setName(com.particlesdevs.photoncamera.settings.ConfigState.label(getContext()));
    }

    private void toggleConfigMenu() {
        if (configMenu != null && configMenu.isShowing()) {
            configMenu.close();
            return;
        }
        refreshConfigName();
        configMenu = ConfigMenu.show(configChip, configActions(), text -> {
            refreshConfigName();
            if (host != null) host.showMessage(text);
        }, () -> configMenu = null);
    }

    private void closeConfigMenu() {
        if (configMenu != null) configMenu.dismissNow();
        configMenu = null;
    }

    /** Tests: the menu's actions instead of the files and the restart. */
    void setConfigActions(ConfigMenu.Actions actions) {
        configActions = actions;
    }

    ConfigChip configChip() {
        return configChip;
    }

    @androidx.annotation.Nullable ConfigMenu configMenu() {
        return configMenu;
    }

    private ConfigMenu.Actions configActions() {
        if (configActions != null) return configActions;
        final Context c = getContext();
        return new ConfigMenu.Actions() {
            @Override public String save(String name) {
                return com.particlesdevs.photoncamera.settings.BackupRestoreUtil.backupSettings(c, name);
            }

            @Override public String[] saved() {
                return com.particlesdevs.photoncamera.util.ConfigFolder.list(c);
            }

            @Override public String load(String fileName) {
                return com.particlesdevs.photoncamera.settings.BackupRestoreUtil.restorePreferences(c, fileName);
            }

            @Override public void importFile() {
                if (host != null) host.importConfigFile();
            }

            @Override public boolean folderOpen() {
                return com.particlesdevs.photoncamera.util.ScameraFolder.CONFIGS.grantedTree(c) != null;
            }

            @Override public void openFolder() {
                if (host != null) host.openConfigFolder();
            }

            @Override public String reset() {
                boolean ok = com.particlesdevs.photoncamera.settings.BackupRestoreUtil.resetPreferences(c);
                com.particlesdevs.photoncamera.app.ScameraApp.restartWithDelay(c, 1000);
                return ok ? c.getString(R.string.app_will_restart)
                        : com.particlesdevs.photoncamera.util.Lang.t(c, "Сброс не удался", "The reset failed");
            }

            @Override public boolean delete(String fileName) {
                return com.particlesdevs.photoncamera.util.ConfigFolder.delete(c, fileName);
            }
        };
    }

    /** SIMPLE_UI §2: «Изменить» / «Готово» as the concept's link - the accent, weight 600, no fill. */
    private void styleEditButton(int accent) {
        editButton.setText(editing ? R.string.shade_done : R.string.shade_edit);
        editButton.setTextColor(accent);
        editButton.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        editButton.setBackground(ShadeStyle.ripple(getContext(), 12));
    }

    private int dp(float f) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, f, getContext().getResources().getDisplayMetrics());
    }

    // ───────────────────────────────── content

    /** Connects the shade to the camera screen. */
    public void attach(ShadeHost host) {
        this.host = host;
    }

    /** A catalog other than the process-wide one (tests). */
    public void setCatalog(ShadeCatalog catalog) {
        this.catalog = catalog;
        contentBuilt = false;
    }

    /** Builds the tiles and rows now (it inflates the settings tree once); the camera screen calls it when idle. */
    public void prewarm() {
        ensureContent();
    }

    private ShadeCatalog catalog() {
        if (catalog == null) catalog = ShadeCatalog.get(getContext());
        return catalog;
    }

    private boolean ensureContent() {
        if (contentBuilt) return true;
        if (host == null) return false;
        contentBuilt = true;
        configChip.setAccent(ShadeStyle.accent(getContext()));
        refreshConfigName();
        pinned.clear();
        pinned.addAll(ShadeTiles.load(catalog().prefs(), catalog()::isKnown));
        adapter.submit(pinned, true);   // P167: «Добавить» always (no limit)
        rows = new ShadeRows(getContext(), catalog(), rowActions);
        buildRows();
        bindSliderCard();
        return true;
    }

    /**
     * SIMPLE_UI §2, below the tiles (reached by scrolling): one collapsible «Для опытных» card with the advanced settings
     * that are not pinned as tiles (hidden when there are none), then a full-width «Все настройки» button.
     */
    private void buildRows() {
        extraRows.clear();
        fullList.removeAllViews();
        List<String> advanced = new ArrayList<>();
        for (String key : ShadeCatalog.ADVANCED) if (!pinned.contains(key) && catalog().isKnown(key)) advanced.add(key);
        advancedShown.clear();
        for (String key : ShadeCatalog.ADVANCED) if (!pinned.contains(key)) advancedShown.add(key);
        LinearLayout advancedBody = new LinearLayout(getContext());
        advancedBody.setOrientation(VERTICAL);
        advancedBody.setTag("shade_advanced_body");
        if (!advanced.isEmpty()) {
            fullList.addView(advancedHeader(advancedBody), moreRowParams());
            rows.build(advancedBody, java.util.Collections.singletonList(ShadeCatalog.group(R.string.shade_advanced, advanced)),
                    java.util.Collections.emptyList());
            advancedBody.setVisibility(advancedOpen ? VISIBLE : GONE);
            fullList.addView(advancedBody, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            rows.build(advancedBody, java.util.Collections.emptyList(), java.util.Collections.emptyList());
        }
        fullList.addView(allSettingsCard(), moreRowParams());
    }

    /** The advanced keys the card was last built with. */
    private final List<String> advancedShown = new ArrayList<>();

    /** The «Для опытных» card is open (kept across rebuilds). */
    private boolean advancedOpen;

    /** The concept's .disc-h: «Для опытных» with its one-line contents and a chevron that turns when open. */
    private View advancedHeader(View body) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int ph = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(c, 14), pv = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(c, 12);
        row.setPadding(ph, pv, ph, pv);
        row.setBackground(ShadeStyle.pressable(c, sheetCard(c), ShadeStyle.LINE, 18));
        row.setTag("shade_advanced");
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(VERTICAL);
        TextView title = new TextView(c);
        title.setText(R.string.shade_advanced);
        title.setTextColor(ShadeStyle.TEXT);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.textFixed(title, 14);
        texts.addView(title);
        TextView summary = new TextView(c);
        summary.setText(R.string.shade_advanced_summary);
        summary.setTextColor(ShadeStyle.MUTED);
        com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.textFixed(summary, 12);
        texts.addView(summary);
        row.addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView chevron = new ImageView(c);
        chevron.setImageResource(R.drawable.ui_ic_chev);
        chevron.setImageTintList(ColorStateList.valueOf(ShadeStyle.MUTED));
        chevron.setRotation(advancedOpen ? 90f : 0f);
        int cs = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(c, 20);
        row.addView(chevron, new LayoutParams(cs, cs));
        row.setContentDescription(c.getString(R.string.shade_advanced));
        row.setOnClickListener(v -> {
            advancedOpen = !advancedOpen;
            body.setVisibility(advancedOpen ? VISIBLE : GONE);
            chevron.animate().rotation(advancedOpen ? 90f : 0f).setDuration(200).start();
            // expanded: the card scrolled to the top of the sheet
            if (advancedOpen) listScroll.post(() -> listScroll.smoothScrollTo(0, fullList.getTop() + row.getTop()));
        });
        return row;
    }

    /** The sheet's cards at its see-through alpha (the concept's --sa on .card / .disc-h). */
    private static int sheetCard(Context c) {
        return (ShadeStyle.CARD & 0x00FFFFFF) | (Math.round(255 * ShadeStyle.sheetAlpha(c)) << 24);
    }

    /** SIMPLE_UI §2: the full-width «Все настройки» button at the end (the concept's .big), as the header's gear. */
    private View allSettingsCard() {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER);
        int p = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(c, 14);
        row.setPadding(p, p, p, p);
        row.setMinimumHeight(com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(c, 52));
        row.setBackground(ShadeStyle.pressable(c, (0x232A2E) | (Math.round(255 * ShadeStyle.sheetAlpha(c)) << 24), 0, 18));
        row.setTag("shade_all_settings");
        ImageView icon = new ImageView(c);
        icon.setImageResource(R.drawable.ui_ic_gear);
        icon.setImageTintList(ColorStateList.valueOf(ShadeStyle.TEXT));
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        int is = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(c, 24);
        LayoutParams ip = new LayoutParams(is, is);
        ip.setMarginEnd(com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(c, 10));
        row.addView(icon, ip);
        TextView title = new TextView(c);
        title.setText(R.string.sheet_all_settings);
        title.setTextColor(ShadeStyle.TEXT);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.textFixed(title, 14);
        row.addView(title);
        row.setOnClickListener(v -> {
            if (host != null) host.openSettings();
        });
        return row;
    }

    private LayoutParams moreRowParams() {
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        return lp;
    }

    /** «Другие настройки» at the end of FULL: the catalog of every pinnable setting. */
    private View moreRow() {
        Context c = getContext();
        int accent = ShadeStyle.accent(c);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(14), dp(14));
        row.setMinimumHeight(dp(66));
        row.setBackground(ShadeStyle.pressable(c, ShadeStyle.CARD, ShadeStyle.LINE, 20));
        row.setTag("shade_more");
        ImageView icon = new ImageView(c);
        icon.setImageResource(R.drawable.ic_shade_plus);
        icon.setImageTintList(ColorStateList.valueOf(accent));
        LayoutParams ip = new LayoutParams(dp(26), dp(26));
        ip.setMarginEnd(dp(14));
        row.addView(icon, ip);
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(VERTICAL);
        TextView title = new TextView(c);
        title.setText(R.string.shade_more);
        title.setTextColor(ShadeStyle.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.title(title); // P139
        texts.addView(title);
        TextView summary = new TextView(c);
        summary.setText(c.getString(R.string.shade_more_summary, catalog().all().size()));
        com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.summary(summary);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        texts.addView(summary);
        row.addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chevron = new TextView(c);
        chevron.setText("\u203a");
        chevron.setTextColor(ShadeStyle.MUTED);
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        chevron.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron);
        row.setOnClickListener(v -> {
            if (host != null) host.openCatalog();
        });
        return row;
    }

    /** Pinned keys in tile order. */
    public List<String> pinnedKeys() {
        return new ArrayList<>(pinned);
    }

    /**
     * Reads the tiles and values again (a setting changed anywhere): the tile list from ui_shade_tiles, every value in
     * place. Posted, so a batch of changes (a module profile being applied) refreshes once.
     */
    public void refresh() {
        if (refreshPosted) return;
        refreshPosted = true;
        post(() -> {
            refreshPosted = false;
            refreshNow();
        });
    }

    private void refreshNow() {
        if (host != null) host.onValuesChanged();
        if (!contentBuilt) return;
        // Never mid-drag: the pressed view keeps its touch stream; the drop runs the refresh.
        if (dragging) {
            refreshPending = true;
            return;
        }
        List<String> stored = ShadeTiles.load(catalog().prefs(), catalog()::isKnown);
        if (!stored.equals(pinned)) {
            pinned.clear();
            pinned.addAll(stored);
            onPinnedChanged();
        } else {
            rebindValues();
        }
    }

    /** Values of every tile on screen, the rows and the slider card, without rebuilding a view. */
    private void rebindValues() {
        for (int i = 0; i < tiles.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = tiles.getChildViewHolder(tiles.getChildAt(i));
            if (holder instanceof ShadeTileAdapter.Holder) {
                ShadeTileView tile = ((ShadeTileAdapter.Holder) holder).tile;
                if (!tile.addTile && tile.key != null) bindTile(tile, tile.key);
            }
        }
        if (rows != null) rows.bindAll();
        bindSliderCard();
    }

    private void onPinnedChanged() {
        adapter.submit(pinned, true);
        // SIMPLE_UI §2: the «Для опытных» card lists the advanced settings not pinned: rebuilt when that set changes
        List<String> advanced = new ArrayList<>();
        for (String key : ShadeCatalog.ADVANCED) if (!pinned.contains(key)) advanced.add(key);
        if (!advanced.equals(advancedShown)) buildRows();
        if (openSlider != null && !pinned.contains(openSlider)) openSlider = null;
        rebindValues();
    }

    private void bindTile(ShadeTileView view, String key) {
        ShadeCatalog.Entry e = catalog().entry(key);
        if (e == null) return;
        view.open = key.equals(openSlider);
        view.bind(catalog(), e, catalog().unavailable(e));
        view.setIconRotation(iconRotation);
        view.remove.setContentDescription(getContext().getString(R.string.shade_remove_description, e.shortTitle));
        view.setEditing(editing, pinned.indexOf(key));
        view.setEnabled(isEnabled());
    }

    // ───────────────────────────────── taps and writes

    private void onTileTap(String key) {
        ShadeCatalog.Entry e = catalog().entry(key);
        if (e == null) return;
        String reason = catalog().unavailable(e);
        if (reason != null) {
            message(reason);
            return;
        }
        if (vibration != null) vibration.Click();
        // SIMPLE_UI §2: «Формат» opens its sheet (the two choices) instead of cycling nine values
        if (ShadeCatalog.FORMAT.equals(key) && host != null && host.openFormatSheet()) return;
        if (ShadeCatalog.SIZE.equals(key)) {
            if (host != null) host.openSizeSheet();
            return;
        }
        if (ShadeCatalog.STYLE.equals(key)) {
            catalog().cycleStyle();
            rebindValues();
            if (host != null) host.onValuesChanged();
            toastValue(e);
            return;
        }
        switch (e.kind) {
            case ShadeCatalog.TOGGLE:
                apply(e, !catalog().on(e));
                toastValue(e);
                break;
            case ShadeCatalog.SLIDER:
            case ShadeCatalog.CURVE:   // P169: the curve graph in the same card
                openSlider = key.equals(openSlider) ? null : key;
                rebindValues();
                break;
            default:
                if (e.isLongList()) {
                    openList(e);
                } else {
                    apply(e, catalog().nextValue(e));
                    toastValue(e);
                }
                break;
        }
    }

    /** The «Добавить» tile: the catalog of every pinnable setting. */
    private void onAddTile() {
        if (host != null) host.openCatalog();
    }

    /** The pins as the catalog sees and changes them. */
    public ShadeCatalogView.Pins pins() {
        return new ShadeCatalogView.Pins() {
            @Override
            public boolean pinned(String key) {
                ensureContent();
                return pinned.contains(key);
            }

            @Override
            public void toggle(String key) {
                togglePin(key);
            }

            @Override
            public int count() {
                ensureContent();
                return pinned.size();
            }
        };
    }

    /** Writes a value the way the settings screen does; camera controls go through CameraUIController. */
    private void apply(ShadeCatalog.Entry e, Object value) {
        if (host == null) return;
        if (e.isVirtual()) {
            host.applyCameraControl(e.settingType, (int) Math.round(Double.parseDouble(value.toString())));
        } else {
            catalog().write(e, value);
            host.onSettingWritten(e);
        }
        rebindValues();
        host.onValuesChanged();
    }

    private void toastValue(ShadeCatalog.Entry e) {
        message(getContext().getString(R.string.shade_toast_value, e.shortTitle, catalog().valueText(e, false)));
    }

    private void message(CharSequence text) {
        if (host != null) host.showMessage(text);
    }

    /** A long list (the tone curve, the ISZ mosaic): the list sheet of the settings screens. */
    private void openList(ShadeCatalog.Entry e) {
        catalog().refreshChoices(e);   // P170: the film LUTs as they are now
        CharSequence[] tags = new CharSequence[e.values.length];
        System.arraycopy(e.values, 0, tags, 0, tags.length);
        SettingsStyle.optionSheet(getContext(), e.title, ShadeCatalog.labels(e), tags, catalog().index(e), ShadeStyle.accent(getContext()),
                e.valueIcons, i -> {
            apply(e, e.values[i].toString());
            toastValue(e);
        });
    }

    /** Pins a setting as the last tile or unpins it (any number since P167). Stored at once. */
    public void togglePin(String key) {
        if (!ensureContent()) return;
        ShadeCatalog.Entry e = catalog().entry(key);
        if (e == null) return;
        if (pinned.remove(key)) {
            message(getContext().getString(R.string.shade_removed, e.shortTitle));
        } else {
            pinned.add(key);
            message(getContext().getString(R.string.shade_added, e.shortTitle));
        }
        ShadeTiles.save(catalog().prefs(), pinned);
        onPinnedChanged();
    }

    private final ShadeRows.Actions rowActions = new ShadeRows.Actions() {
        @Override
        public void pick(ShadeCatalog.Entry entry, Object value) {
            if (vibration != null) vibration.Click();
            apply(entry, value);
        }

        @Override
        public void togglePin(String key) {
            SettingsBarLayout.this.togglePin(key);
        }

        @Override
        public boolean pinned(String key) {
            return pinned.contains(key);
        }

        @Override
        public void openList(ShadeCatalog.Entry entry) {
            SettingsBarLayout.this.openList(entry);
        }

        @Override
        public void blocked(String reason) {
            message(reason);
        }
    };

    @Nullable
    private ShadeCatalog.Entry openSliderEntry() {
        return openSlider == null || catalog == null ? null : catalog.entry(openSlider);
    }

    /** P169: the curve editor inside the slider card (made on first use), shown instead of the bar for a CURVE tile. */
    @Nullable private LinearLayout curveBox;
    @Nullable private com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurveView curveView;

    private LinearLayout curveBox() {
        if (curveBox != null) return curveBox;
        Context c = getContext();
        curveBox = new LinearLayout(c);
        curveBox.setOrientation(VERTICAL);
        curveBox.setTag("shade_curve_box");
        curveView = new com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurveView(c);
        curveView.setTag("shade_curve_view");
        curveView.setOnCommit(curve -> {
            ShadeCatalog.Entry e = openSliderEntry();
            if (e != null && e.kind == ShadeCatalog.CURVE) apply(e, curve.serialize());
        });
        LayoutParams gp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.topMargin = dp(10);
        // P178: undo / redo over the graph
        curveBox.addView(com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurveView.withHistory(c, curveView), gp);
        LinearLayout chips = new LinearLayout(c);
        chips.setOrientation(HORIZONTAL);
        chips.addView(curveChip(Lang.t(c, "S-кривая", "S-curve"), "shade_curve_default",
                com.particlesdevs.photoncamera.processing.color.ToneCurve::defaults), new LayoutParams(0, dp(40), 1f));
        LayoutParams second = new LayoutParams(0, dp(40), 1f);
        second.setMarginStart(dp(8));
        chips.addView(curveChip(Lang.t(c, "Прямая", "Straight"), "shade_curve_identity",
                com.particlesdevs.photoncamera.processing.color.ToneCurve::identity), second);
        // P177: «Ещё…» - AgX / ACES / Uchimura (and the two above) as own curves to edit
        TextView more = curveChip(Lang.t(c, "Ещё…", "More…"), "shade_curve_templates", null);
        more.setOnClickListener(v -> com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurveCompare.pickTemplate(c,
                ShadeStyle.accent(c), next -> {
                    ShadeCatalog.Entry e = openSliderEntry();
                    if (e == null || e.kind != ShadeCatalog.CURVE || curveView == null) return;
                    curveView.edit(next);   // P178: a step
                    curveView.select(next.size() / 2);
                    apply(e, next.serialize());
                }));
        LayoutParams third = new LayoutParams(0, dp(40), 1f);
        third.setMarginStart(dp(8));
        chips.addView(more, third);
        LayoutParams cp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(10);
        curveBox.addView(chips, cp);
        // P177: «Сравнить:» the other operators on the graph
        LayoutParams cmp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cmp.topMargin = dp(8);
        cmp.bottomMargin = dp(4);
        curveBox.addView(com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurveCompare.chips(c, curveView), cmp);
        sliderCard.addView(curveBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return curveBox;
    }

    private TextView curveChip(String text, String tag,
                               @Nullable java.util.function.Supplier<com.particlesdevs.photoncamera.processing.color.ToneCurve> curve) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setTag(tag);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int accent = ShadeStyle.accent(getContext());
        chip.setTextColor(accent);
        chip.setBackground(ShadeStyle.pressable(getContext(), ShadeStyle.tint(accent, .14f), 0, 100));
        if (curve == null) return chip;   // the caller sets its own action
        chip.setOnClickListener(v -> {
            ShadeCatalog.Entry e = openSliderEntry();
            if (e == null || e.kind != ShadeCatalog.CURVE || curveView == null) return;
            com.particlesdevs.photoncamera.processing.color.ToneCurve next = curve.get();
            curveView.edit(next);   // P178: a step
            curveView.select(next.size() / 2);
            apply(e, next.serialize());
        });
        return chip;
    }

    private void bindSliderCard() {
        ShadeCatalog.Entry e = openSliderEntry();
        if (e == null || (e.kind != ShadeCatalog.SLIDER && e.kind != ShadeCatalog.CURVE) || !pinned.contains(e.key)) {
            openSlider = null;
            if (sliderCard.getVisibility() != GONE) sliderCard.setVisibility(GONE);
            return;
        }
        if (sliderCard.getVisibility() != VISIBLE) sliderCard.setVisibility(VISIBLE);
        sliderTitle.setText(e.shortTitle);
        final boolean curve = e.kind == ShadeCatalog.CURVE;
        sliderBar.setVisibility(curve ? GONE : VISIBLE);
        if (curve || curveBox != null) curveBox().setVisibility(curve ? VISIBLE : GONE);
        if (curve) {
            sliderPill.setText(catalog().valueText(e, true));
            sliderPill.setTextColor(ShadeStyle.accent(getContext()));
            com.particlesdevs.photoncamera.processing.color.ToneCurve stored = catalog().curve(e);
            if (curveView != null && !stored.serialize().equals(curveView.curve().serialize())) {
                int selected = curveView.selected();
                curveView.setCurve(stored);
                curveView.select(selected >= 0 && selected < stored.size() ? selected : stored.size() / 2);
                curveView.record();   // P178: a change made elsewhere is a step too
            }
            return;
        }
        sliderBar.setContentDescription(e.title);
        int accent = ShadeStyle.accent(getContext());
        sliderPill.setTextColor(accent);
        sliderBar.setProgressTintList(ColorStateList.valueOf(accent));
        sliderBar.setThumbTintList(ColorStateList.valueOf(accent));
        sliderBar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF4A5058));
        if (sliderTracking) return;
        float v = catalog().number(e);
        sliderPill.setText(ShadeCatalog.formatNumber(e, v));
        sliderBar.setMax(ShadeRows.steps(e));
        sliderBar.setProgress(ShadeRows.progressOf(e, v));
    }

    /** A lens switch ends the edit mode and closes the slider card (owner's answer 11). */
    public void onLensSwitch() {
        if (editing) setEditing(false);
        if (openSlider == null) return;
        openSlider = null;
        rebindValues();
    }

    // ───────────────────────────────── edit mode and reorder

    public boolean isEditing() {
        return editing;
    }

    /**
     * Edit mode: header «Перетащи плитки» / «Готово», «×» on every tile, the tiles wobble, a short press-drag lifts a tile;
     * the FULL rows and the slider card step aside. The sheet cannot be dragged meanwhile (swipes on the viewfinder still
     * move it).
     */
    public void setEditing(boolean on) {
        if (editing == on) return;
        if (on) ensureContent();
        editing = on;
        if (on) openSlider = null;
        // SIMPLE_UI §2: the title becomes «Перетащите плитку» while editing
        headTitle.setText(on ? R.string.shade_drag_title : R.string.shade_quick_title);
        headTitle.setVisibility(on ? VISIBLE : GONE);
        configChip.setVisibility(on ? GONE : VISIBLE);
        if (on) closeConfigMenu();
        styleEditButton(ShadeStyle.accent(getContext()));
        headGear.setVisibility(on ? GONE : VISIBLE);
        fullList.setVisibility(on ? GONE : VISIBLE);
        for (int i = 0; i < tiles.getChildCount(); i++) {
            View child = tiles.getChildAt(i);
            if (child instanceof ShadeTileView) {
                ShadeTileView tile = (ShadeTileView) child;
                tile.setEditing(on, tiles.getChildAdapterPosition(child));
            }
        }
        bindSliderCard();
        updateDraggable();
    }

    /** Back: the drag first, then the edit mode; false when the sheet has nothing of its own to close. */
    public boolean onBackPressed() {
        if (dragging) {
            cancelDrag();
            return true;
        }
        if (editing) {
            setEditing(false);
            return true;
        }
        return false;
    }

    /** Drops the lifted tile where it is (ItemTouchHelper settles it on a cancel). */
    private void cancelDrag() {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        tiles.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    /** Moves a tile one place (TalkBack «Раньше» / «Позже»), saves and says where it is. */
    private void moveBy(String key, int delta) {
        int from = pinned.indexOf(key), to = from + delta;
        if (from < 0 || to < 0 || to >= pinned.size()) return;
        adapter.move(from, to);
        pinned.clear();
        pinned.addAll(adapter.keys());
        saveOrder(key);
    }

    private void saveOrder(String key) {
        ShadeTiles.save(catalog().prefs(), pinned);
        ShadeCatalog.Entry e = catalog().entry(key);
        if (e != null) message(getContext().getString(R.string.shade_place, e.shortTitle, pinned.indexOf(key) + 1, pinned.size()));
    }

    /**
     * Press on a tile. Outside the edit mode a long press (~400 ms, haptic) enters it and lifts the tile at once; a move
     * before that is a swipe of the sheet or a scroll. In the edit mode the tile claims the finger at once (the list and
     * the sheet do not move) and a move or ~120 ms lifts it.
     */
    private final class TilePress implements View.OnTouchListener {
        private final ShadeTileAdapter.Holder holder;
        private final int slop;
        private float downX, downY;
        private final Runnable lift = this::lift;

        TilePress(ShadeTileAdapter.Holder holder) {
            this.holder = holder;
            slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
        }

        private void lift() {
            if (dragging || holder.tile.addTile || !isEnabled() || holder.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
            boolean fromLongPress = !editing;
            if (fromLongPress) {
                holder.tile.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                setEditing(true);
                // The rows keep their space (INVISIBLE, not GONE) while this drag runs: the list must not scroll under
                // the finger. The drop makes them GONE.
                fullList.setVisibility(INVISIBLE);
            }
            ViewParent parent = tiles.getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            dragHelper.startDrag(holder);
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (holder.tile.addTile) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    if (editing) {
                        ViewParent parent = tiles.getParent();
                        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                        postDelayed(lift, 120);
                    } else {
                        postDelayed(lift, 400);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop) {
                        removeCallbacks(lift);
                        if (editing && !dragging) lift();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(lift);
                    break;
                default:
                    break;
            }
            return false;
        }
    }

    private final class DragCallback extends ItemTouchHelper.Callback {
        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            if (adapter.isAdd(viewHolder.getBindingAdapterPosition())) return 0;
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
            int a = from.getBindingAdapterPosition(), b = to.getBindingAdapterPosition();
            if (a == RecyclerView.NO_POSITION || b == RecyclerView.NO_POSITION || adapter.isAdd(b)) return false;
            // Only notifyItemMoved: the lifted view stays the same, the others shift to make room.
            adapter.move(a, b);
            return true;
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false; // TilePress starts the drags: ~400 ms outside the edit mode, ~120 ms in it
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return false;
        }

        /** The grid never scrolls itself (all its tiles are laid out); no auto-scroll at its edges. */
        @Override
        public int interpolateOutOfBoundsScroll(@NonNull RecyclerView recyclerView, int viewSize, int viewSizeOutOfBounds,
                                                int totalSize, long msSinceStartScroll) {
            return 0;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        }

        @Override
        public void onSelectedChanged(@Nullable RecyclerView.ViewHolder viewHolder, int actionState) {
            super.onSelectedChanged(viewHolder, actionState);
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder instanceof ShadeTileAdapter.Holder) {
                dragging = true;
                updateDraggable();
                ((ShadeTileAdapter.Holder) viewHolder).tile.lift(true);
            }
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            if (!(viewHolder instanceof ShadeTileAdapter.Holder)) return;
            ShadeTileView tile = ((ShadeTileAdapter.Holder) viewHolder).tile;
            tile.lift(false);
            dragging = false;
            // The order is saved on the drop.
            List<String> order = adapter.keys();
            if (!order.equals(pinned)) {
                pinned.clear();
                pinned.addAll(order);
            }
            if (tile.key != null) saveOrder(tile.key);
            if (editing) {
                fullList.setVisibility(GONE);
                tile.setEditing(true, viewHolder.getBindingAdapterPosition());
            }
            updateDraggable();
            if (refreshPending) {
                refreshPending = false;
                refreshNow();
            }
        }
    }

    /** TalkBack: «Раньше», «Позже», «Убрать из шторки» on every tile. */
    private final class TileActions extends View.AccessibilityDelegate {
        private final ShadeTileAdapter.Holder holder;

        TileActions(ShadeTileAdapter.Holder holder) {
            this.holder = holder;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            String key = holder.tile.key;
            if (holder.tile.addTile || key == null) return;
            int at = pinned.indexOf(key);
            Context c = getContext();
            if (at > 0) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.shade_action_earlier, c.getString(R.string.shade_action_earlier)));
            if (at >= 0 && at < pinned.size() - 1) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.shade_action_later, c.getString(R.string.shade_action_later)));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.shade_action_remove, c.getString(R.string.shade_action_remove)));
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            String key = holder.tile.key;
            if (key != null && !dragging) {
                if (action == R.id.shade_action_earlier) { moveBy(key, -1); return true; }
                if (action == R.id.shade_action_later) { moveBy(key, 1); return true; }
                if (action == R.id.shade_action_remove) { togglePin(key); return true; }
            }
            return super.performAccessibilityAction(host, action, args);
        }
    }

    /** Landscape: tile icons turn with the phone ({@code app:iconRotation}). */
    public void setIconRotation(int degrees) {
        iconRotation = degrees;
        for (int i = 0; i < tiles.getChildCount(); i++) {
            View child = tiles.getChildAt(i);
            if (child instanceof ShadeTileView) ((ShadeTileView) child).icon.animate().rotation(degrees).setDuration(300).start();
        }
    }

    /** Whether the current lens has a flash; the flash tile is dimmed otherwise. */
    public void setFlashAvailable(boolean available) {
        ShadeCatalog.setFlashAvailable(available);
        refresh();
    }

    // ───────────────────────────────── settings listener

    /** The one settings listener of the shade: registered once, it refreshes the shade on screen. */
    private static final class Sync implements SettingsManager.OnSettingChangedListener {
        static final Sync INSTANCE = new Sync();
        static boolean registered;
        @Nullable SettingsBarLayout target;

        @Override
        public void onSettingChanged(SettingsManager settingsManager, String key) {
            SettingsBarLayout shade = target;
            if (shade != null) shade.refresh();
        }
    }

    /** While the camera screen is in front, every settings change refreshes this shade. */
    public void setLive(boolean live) {
        if (live) {
            if (!Sync.registered && !isInEditMode()) {
                SettingsManager manager = ScameraApp.getSettingsManagerStatic();
                if (manager != null) {
                    manager.addListener(Sync.INSTANCE);
                    Sync.registered = true;
                }
            }
            Sync.INSTANCE.target = this;
            refresh();
        } else if (Sync.INSTANCE.target == this) {
            Sync.INSTANCE.target = null;
        }
    }

    // ───────────────────────────────── burst lock, measure

    /**
     * Locks the sheet during a burst (lockUIForBurst): its controls stop reacting and it cannot
     * be dragged. Code can still move it (swipes on the viewfinder, Back).
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setTreeEnabled(this, enabled);
        updateDraggable();
    }

    private static void setTreeEnabled(View view, boolean enabled) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            child.setEnabled(enabled);
            setTreeEnabled(child, enabled);
        }
    }

    /** A finger may drag the sheet unless a burst locks it, a tile is lifted, or the edit mode is on. */
    private void updateDraggable() {
        BottomSheetBehavior<SettingsBarLayout> sheet = sheetBehavior();
        if (sheet != null) sheet.setDraggable(isDraggable());
    }

    private boolean isDraggable() {
        return isEnabled() && !editing && !dragging;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // FULL always has the same height, the 86% limit: opening the slider card never moves the
        // top of the FULL sheet. The behavior measures the sheet AT_MOST that limit.
        // SIMPLE_UI §2: one open height - the grab, the header and the tiles (+14dp), at most 86 % of the viewfinder; what
        // lies below («Для опытных», «Все настройки») is reached by scrolling the content, the sheet does not grow.
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int cap = maxSheetHeight;
        if (contentBuilt && sheetPeekHeight > 0) cap = cap > 0 ? Math.min(cap, sheetPeekHeight) : sheetPeekHeight;
        if (cap > 0 && mode != MeasureSpec.EXACTLY) {
            int size = mode == MeasureSpec.UNSPECIFIED ? cap : Math.min(MeasureSpec.getSize(heightMeasureSpec), cap);
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        updateShape();
        // OPEN: the handle, the header and the grid, or down to the slider card when it is open; +14dp (the concept).
        View last = sliderCard.getVisibility() == VISIBLE ? sliderCard : tiles;
        int peek = listScroll.getTop() + content.getTop() + last.getBottom()
                + com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(getContext(), 14);
        if (contentBuilt && peek > 0 && peek != sheetPeekHeight) {
            sheetPeekHeight = peek;
            // This runs inside the behavior's onLayoutChild; set the peek after the pass.
            if (!peekUpdatePosted) {
                peekUpdatePosted = true;
                post(peekUpdate);
            }
        }
    }

    // ───────────────────────────────── levels

    /**
     * Moves the sheet to a level ({@code app:sheetLevel} binding). Idempotent: compares with the
     * behavior state, not with {@link #sheetLevel}. Skipped while a finger drags the sheet (its
     * settle reports the level back). A new level is applied while settling, since setState
     * restarts the settle; a repeat of the last request is not (see below).
     */
    public void setSheetLevel(int level) {
        level = Math.max(LEVEL_HIDDEN, Math.min(LEVEL_FULL, level));
        // The tiles exist before the sheet shows them (the first open builds them).
        if (level != LEVEL_HIDDEN) ensureContent();
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
        if ((zoom != null || zoomPending) && (repeat || zoomTarget == target)) return;   // P176: a zoom on its way there
        if (zoom != null) zoom.end();
        zoomPending = false;
        state = sheet.getState();
        if (state != target && zoomFromPill(sheet, state, target)) return;
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

    /** The FULL height in pixels (a share of the viewfinder height). */
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
        styleHiddenHandle();
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

    /** The handle's cycle (SIMPLE_UI §2: two states): HIDDEN -> OPEN -> HIDDEN. */
    static int nextHandleLevel(int level) {
        return level == LEVEL_HIDDEN ? LEVEL_PEEK : LEVEL_HIDDEN;
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
        // P270 (owner's video 2026-10-19: a full sheet of tiles, the dimming behind it pulsing every ~1.6 s): with the tiles filling
        // the sheet, PEEK sits a few px below FULL and the scrim, the share of that travel, went 0 -> 1 -> 0 on every small
        // re-layout (a tile's text changing). No travel, nothing to dim: the scrim stays off then.
        if (scrimTravelTiny()) full = 0f;
        for (View scrim : scrims) {
            scrim.setAlpha(full);
            scrim.setVisibility(full > 0f ? VISIBLE : GONE);
        }
    }

    /** P270: FULL shows at most 24 dp more than PEEK (the sheet's tiles fill it): no scrim. */
    boolean scrimTravelTiny() {
        return scrimTravelTiny(sheetPeekHeight, getHeight(),
                com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(getContext(), 24));
    }

    static boolean scrimTravelTiny(int peek, int height, int slack) {
        return peek > 0 && height > 0 && height - peek <= slack;
    }

    /**
     * A level asked for from a handle (tap or fling) or a tile. It goes to the model first;
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

    // ───────────────────────────────── P176: the pill at 16:9

    /**
     * True while the preview runs on under the controls (16:9: BottomChrome floats the HIDDEN handle over the preview): the
     * handle is a pill instead of the sheet's full-width top edge, and the sheet opens out of it and closes into it.
     */
    public void setPillHandle(boolean pill) {
        if (pill == pillHandle) return;
        pillHandle = pill;
        styleHiddenHandle();
        updateShape();
    }

    // ───────────────────────────────── P185: the sheet's shape at 16:9

    /** The clip of the sheet in its own coordinates and its corner radius, while {@link #shaped}. */
    private final android.graphics.RectF shape = new android.graphics.RectF();
    private float shapeRadius;
    /** The sheet is clipped to {@link #shape} (16:9: in place or on its way out of the pill); else its background's outline. */
    private boolean shaped;
    private final android.graphics.Paint shapeStroke = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.view.ViewOutlineProvider sheetOutline = new android.view.ViewOutlineProvider() {
        @Override
        public void getOutline(View view, android.graphics.Outline outline) {
            if (shaped && shape.width() > 0 && shape.height() > 0)
                outline.setRoundRect(Math.round(shape.left), Math.round(shape.top), Math.round(shape.right), Math.round(shape.bottom), shapeRadius);
            else android.view.ViewOutlineProvider.BACKGROUND.getOutline(view, outline);
        }
    };

    /** The shade's corner radius (ShadeStyle.sheet's). */
    private float sheetRadius() {
        return dp(26);
    }

    /**
     * P185 (owner: «сделай такие же закругления как сверху шторки снизу»): at 16:9 the sheet floats over the preview above the
     * lens strip - its visible part (down to its container's bottom) is clipped with the top's corners at the bottom too.
     * Elsewhere, and while the morph runs (it sets the shape itself), as before.
     */
    private void updateShape() {
        if (zoom != null) return;
        boolean want = pillHandle && getParent() instanceof View && getHeight() > 0;
        if (want) {
            int visible = Math.min(getHeight(), ((View) getParent()).getHeight() - getTop());
            if (visible <= 0) want = false;
            else setShape(0f, 0f, getWidth(), visible, sheetRadius());
        }
        if (!want && shaped) {
            shaped = false;
            setClipToOutline(false);
            invalidateOutline();
            invalidate();
        }
    }

    private void setShape(float l, float t, float r, float b, float radius) {
        if (shaped && shape.left == l && shape.top == t && shape.right == r && shape.bottom == b && shapeRadius == radius) return;
        shape.set(l, t, r, b);
        shapeRadius = radius;
        shaped = true;
        setClipToOutline(true);
        invalidateOutline();
        invalidate();
    }

    @Override
    protected void dispatchDraw(android.graphics.Canvas canvas) {
        super.dispatchDraw(canvas);
        // the shape's line: all round while it morphs (the background's line lies outside the shape then), in place only
        // along the bottom (the background draws the top and the sides)
        if (shaped && shape.width() > 0 && shape.height() > 0) {
            float h = shapeStroke.getStrokeWidth() / 2f;
            float l = shape.left + h, t = shape.top + h, r = shape.right - h, b = shape.bottom - h;
            float rad = Math.max(0f, Math.min(shapeRadius - h, Math.min(r - l, b - t) / 2f));
            if (zoom != null) canvas.drawRoundRect(l, t, r, b, rad, rad, shapeStroke);
            else {
                android.graphics.Path bottom = new android.graphics.Path();
                bottom.moveTo(l, b - rad);
                bottom.arcTo(l, b - 2 * rad, l + 2 * rad, b, 180f, -90f, false);
                bottom.lineTo(r - rad, b);
                bottom.arcTo(r - 2 * rad, b - 2 * rad, r, b, 90f, -90f, false);
                bottom.lineTo(r, b - rad);
                canvas.drawPath(bottom, shapeStroke);
            }
        }
    }

    public boolean isPillHandle() {
        return pillHandle;
    }

    /** The handle as the sheet's top edge (full width) or as a pill (P176), its grip in the middle either way. */
    private void styleHiddenHandle() {
        if (hiddenHandle == null) return;
        Context c = getContext();
        ViewGroup.LayoutParams lp = hiddenHandle.getLayoutParams();
        if (lp instanceof android.widget.FrameLayout.LayoutParams) {
            android.widget.FrameLayout.LayoutParams fp = (android.widget.FrameLayout.LayoutParams) lp;
            int width = pillHandle ? dp(PILL_WIDTH_DP) : ViewGroup.LayoutParams.MATCH_PARENT;
            if (fp.width != width || fp.gravity != Gravity.CENTER) {
                fp.width = width;
                fp.gravity = Gravity.CENTER;
                hiddenHandle.setLayoutParams(fp);
            }
        }
        if (pillHandle) {
            android.graphics.drawable.GradientDrawable pill = ShadeStyle.card(c, (ShadeStyle.BG & 0x00FFFFFF) | 0xEB000000,
                    ShadeStyle.LINE, 100);
            hiddenHandle.setBackground(pill);
            hiddenHandle.setElevation(dp(3));
        } else {
            hiddenHandle.setBackground(ShadeStyle.sheet(c));
            hiddenHandle.setElevation(0f);
        }
    }

    private int zoomTarget = -1;
    /** P176: an opening waits for the pending layout (the behavior would slide from where that layout puts the sheet). */
    private boolean zoomPending;
    /** Tests: zooms started and the scale each started at. */
    int zoomRuns;
    float zoomStartScale = 1f;

    /**
     * Opens out of the pill or closes into it (P176) instead of the behavior's slide: false when it does not apply (not at
     * 16:9, not laid out, a move between PEEK and FULL), and the caller slides as usual.
     */
    private boolean zoomFromPill(BottomSheetBehavior<SettingsBarLayout> sheet, int state, int target) {
        if (!pillHandle || hiddenHandle == null || !isLaidOut() || !(getParent() instanceof View)) return false;
        boolean open = state == BottomSheetBehavior.STATE_HIDDEN && target != BottomSheetBehavior.STATE_HIDDEN;
        boolean close = target == BottomSheetBehavior.STATE_HIDDEN
                && (state == BottomSheetBehavior.STATE_COLLAPSED || state == BottomSheetBehavior.STATE_EXPANDED);
        if (!open && !close) return false;
        View parent = (View) getParent();
        int parentHeight = parentHeight(parent);
        if (parentHeight <= 0 || getHeight() <= 0) return false;
        android.graphics.RectF pill = pillRect(parent);
        if (pill == null) return false;
        sheet.setHideable(true);
        zoomTarget = target;
        if (open && (parent.isLayoutRequested() || isLayoutRequested())) {
            zoomPending = true;
            getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                private int tries;

                @Override
                public boolean onPreDraw() {
                    if (!zoomPending) {
                        getViewTreeObserver().removeOnPreDrawListener(this);
                        return true;
                    }
                    if ((parent.isLayoutRequested() || isLayoutRequested()) && ++tries < 4) return true;
                    getViewTreeObserver().removeOnPreDrawListener(this);
                    zoomPending = false;
                    zoomTarget = -1;
                    if (sheet.getState() == BottomSheetBehavior.STATE_HIDDEN && !zoomFromPill(sheet, BottomSheetBehavior.STATE_HIDDEN, target))
                        sheet.setState(target);
                    return true;
                }
            });
            return true;
        }
        if (open) {
            // in place at once (the behavior then has nothing to slide), then grown out of the pill
            int top = topFor(target, parentHeight);
            offsetTopAndBottom(top - getTop());
            sheet.setState(target);
            android.graphics.RectF visible = new android.graphics.RectF(getLeft(), top, getRight(), Math.min(parentHeight, top + getHeight()));
            runZoom(pill, visible, true, () -> {
                if (target == BottomSheetBehavior.STATE_EXPANDED && sheet.getState() == BottomSheetBehavior.STATE_EXPANDED)
                    sheet.setHideable(false);
            });
        } else {
            final boolean fromFull = state == BottomSheetBehavior.STATE_EXPANDED;
            android.graphics.RectF visible = new android.graphics.RectF(getLeft(), getTop(), getRight(), Math.min(parentHeight, getBottom()));
            runZoom(pill, visible, false, () -> {
                // below the edge at once, then HIDDEN with nothing to slide; the pill takes the sheet's place
                offsetTopAndBottom(parentHeight(parent) - getTop());
                sheet.setState(BottomSheetBehavior.STATE_HIDDEN);
                if (fromFull) updateScrim(0f);
            });
            if (fromFull) zoom.addUpdateListener(a -> updateScrim(1f - a.getAnimatedFraction()));
        }
        return true;
    }

    private void runZoom(android.graphics.RectF pill, android.graphics.RectF visible, boolean open, Runnable done) {
        setPivotX(0f);
        setPivotY(0f);
        final float pillRadius = pill.height() / 2f, radius = sheetRadius();
        // the content's own alpha comes back at the end (the morph fades it in / out)
        final float[] alphas = new float[getChildCount()];
        for (int i = 0; i < alphas.length; i++) alphas[i] = getChildAt(i).getAlpha();
        apply(ShadeZoom.frame(pill, visible, open ? 0f : 1f, pillRadius, radius), alphas);
        zoomRuns++;
        zoomStartScale = getScaleX();
        unclip(true);
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(open ? 0f : 1f, open ? 1f : 0f);
        a.setDuration(open ? ShadeZoom.OPEN_MS : ShadeZoom.CLOSE_MS);
        a.setInterpolator(open ? ShadeZoom.OPEN : ShadeZoom.CLOSE);
        a.addUpdateListener(v -> apply(ShadeZoom.frame(pill, visible, (float) v.getAnimatedValue(), pillRadius, radius), alphas));
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean finished;

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (finished) return;
                finished = true;
                zoom = null;
                zoomTarget = -1;
                unclip(false);
                done.run();
                setScaleX(1f);
                setScaleY(1f);
                setTranslationX(0f);
                setTranslationY(0f);
                setAlpha(1f);
                for (int i = 0; i < alphas.length && i < getChildCount(); i++) getChildAt(i).setAlpha(alphas[i]);
                updateShape();
                updateHiddenHandle();
            }
        });
        zoom = a;
        a.start();
    }

    private final boolean[] clipSaved = new boolean[2];

    /**
     * At 16:9 the pill lies below the sheet's container (over the preview, above the lens strip): while the sheet grows out
     * of it or shrinks into it, the container and the screen's layout do not clip it.
     */
    private void unclip(boolean on) {
        ViewParent p = getParent();
        for (int i = 0; i < clipSaved.length && p instanceof ViewGroup; i++, p = p.getParent()) {
            ViewGroup g = (ViewGroup) p;
            if (on) {
                clipSaved[i] = g.getClipChildren();
                g.setClipChildren(false);
            } else {
                g.setClipChildren(clipSaved[i]);
            }
        }
    }

    /** One frame of the morph (ShadeZoom.frame): the content's scale and place, the shape's clip, the content's alpha. */
    private void apply(float[] f, float[] alphas) {
        setScaleX(f[ShadeZoom.SCALE]);
        setScaleY(f[ShadeZoom.SCALE]);
        setTranslationX(f[ShadeZoom.TX]);
        setTranslationY(f[ShadeZoom.TY]);
        setAlpha(1f);
        android.graphics.RectF clip = ShadeZoom.localClip(f, getLeft(), getTop());
        setShape(clip.left, clip.top, clip.right, clip.bottom, f[ShadeZoom.RADIUS] / Math.max(0.05f, f[ShadeZoom.SCALE]));
        for (int i = 0; i < alphas.length && i < getChildCount(); i++) getChildAt(i).setAlpha(alphas[i] * f[ShadeZoom.CONTENT]);
    }

    /** The pill in the sheet parent's coordinates, null while it is not laid out. */
    @Nullable
    private android.graphics.RectF pillRect(View parent) {
        if (hiddenHandle.getWidth() <= 0 || hiddenHandle.getHeight() <= 0) return null;
        int[] h = new int[2], p = new int[2];
        hiddenHandle.getLocationInWindow(h);
        parent.getLocationInWindow(p);
        float left = h[0] - p[0], top = h[1] - p[1];
        return new android.graphics.RectF(left, top, left + hiddenHandle.getWidth(), top + hiddenHandle.getHeight());
    }

    private static int parentHeight(View parent) {
        return parent.getHeight();
    }

    /** The behavior's top of the sheet in a state (fit to contents: FULL is the sheet's own height, PEEK its peek height). */
    private int topFor(int state, int parentHeight) {
        int expanded = Math.max(0, parentHeight - getHeight());
        if (state == BottomSheetBehavior.STATE_EXPANDED) return expanded;
        if (state == BottomSheetBehavior.STATE_COLLAPSED)
            return Math.max(expanded, parentHeight - (behavior != null ? behavior.getPeekHeight() : getHeight()));
        return parentHeight;
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
