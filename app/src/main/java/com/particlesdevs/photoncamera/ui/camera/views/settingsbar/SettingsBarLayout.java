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
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
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
 * Content, one scroll list: the handle, the header «Закреплено», the grid of pinned tiles (4 columns, at most 12, a
 * dashed «Добавить» tile while fewer), the inline slider card of an open slider tile, then (seen in FULL) the curated
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
            if (levelListener != null) levelListener.onSheetLevelChanged(level);
        }

        @Override
        public void onSlide(@NonNull View bottomSheet, float slideOffset) {
            // 0 at PEEK, 1 at FULL; between HIDDEN and PEEK (negative) there is no scrim.
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
        setBackground(ShadeStyle.sheet(context));

        // Handle at the top of the sheet. A tap goes on round the cycle: PEEK -> FULL -> HIDDEN.
        // A drag past the touch slop is taken by the BottomSheetBehavior, which moves the sheet.
        handle = new ImageView(context);
        handle.setImageResource(R.drawable.sheet_handle);
        handle.setScaleType(ImageView.ScaleType.CENTER);
        handle.setContentDescription(context.getString(R.string.sheet_handle_toggle));
        handle.setOnClickListener(v -> requestSheetLevel(nextHandleLevel(sheetLevel)));
        addView(handle, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

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
        head.setPadding(dp(9), dp(2), dp(5), dp(7));
        headTitle = new TextView(context);
        headTitle.setText(R.string.shade_pinned);
        head.addView(headTitle, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        editButton = new TextView(context);
        editButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        editButton.setPadding(dp(14), dp(7), dp(14), dp(7));
        editButton.setTag("shade_edit");
        editButton.setOnClickListener(v -> setEditing(!editing));
        head.addView(editButton);
        content.addView(head, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

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

        ShadeStyle.label(headTitle, accent);
        styleEditButton(accent);
    }

    private void styleEditButton(int accent) {
        editButton.setText(editing ? R.string.shade_done : R.string.shade_edit);
        editButton.setTextColor(editing ? ShadeStyle.INK : accent);
        editButton.setTypeface(null, editing ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        editButton.setBackground(ShadeStyle.pressable(getContext(), editing ? accent : ShadeStyle.tint(accent, .16f), 0, 100));
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
        ShadeStyle.label(headTitle, ShadeStyle.accent(getContext()));
        pinned.clear();
        pinned.addAll(ShadeTiles.load(catalog().prefs(), catalog()::isKnown));
        adapter.submit(pinned, pinned.size() < ShadeCatalog.MAX_TILES);
        rows = new ShadeRows(getContext(), catalog(), rowActions);
        buildRows();
        bindSliderCard();
        return true;
    }

    private void buildRows() {
        extraRows.clear();
        for (String key : pinned) if (!ShadeCatalog.isCurated(key)) extraRows.add(key);
        rows.build(fullList, catalog().visibleGroups(), extraRows);
        fullList.addView(moreRow(), moreRowParams());
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
        texts.addView(title);
        TextView summary = new TextView(c);
        summary.setText(c.getString(R.string.shade_more_summary, catalog().all().size()));
        summary.setTextColor(ShadeStyle.MUTED);
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
        adapter.submit(pinned, pinned.size() < ShadeCatalog.MAX_TILES);
        List<String> extra = new ArrayList<>();
        for (String key : pinned) if (!ShadeCatalog.isCurated(key)) extra.add(key);
        if (!extra.equals(extraRows)) buildRows();
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
        switch (e.kind) {
            case ShadeCatalog.TOGGLE:
                apply(e, !catalog().on(e));
                toastValue(e);
                break;
            case ShadeCatalog.SLIDER:
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
    }

    private void toastValue(ShadeCatalog.Entry e) {
        message(getContext().getString(R.string.shade_toast_value, e.shortTitle, catalog().valueText(e, false)));
    }

    private void message(CharSequence text) {
        if (host != null) host.showMessage(text);
    }

    /** A long list (the tone curve, the ISZ mosaic): the list sheet of the settings screens. */
    private void openList(ShadeCatalog.Entry e) {
        CharSequence[] tags = new CharSequence[e.values.length];
        System.arraycopy(e.values, 0, tags, 0, tags.length);
        SettingsStyle.optionSheet(getContext(), e.title, e.labels, tags, catalog().index(e), ShadeStyle.accent(getContext()), i -> {
            apply(e, e.values[i].toString());
            toastValue(e);
        });
    }

    /** Pins a setting as the last tile or unpins it; the 13th pin is refused. Stored at once. */
    public void togglePin(String key) {
        if (!ensureContent()) return;
        ShadeCatalog.Entry e = catalog().entry(key);
        if (e == null) return;
        if (pinned.remove(key)) {
            message(getContext().getString(R.string.shade_removed, e.shortTitle));
        } else {
            if (pinned.size() >= ShadeCatalog.MAX_TILES) {
                message(getContext().getString(R.string.shade_full, ShadeCatalog.MAX_TILES));
                return;
            }
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

    private void bindSliderCard() {
        ShadeCatalog.Entry e = openSliderEntry();
        if (e == null || e.kind != ShadeCatalog.SLIDER || !pinned.contains(e.key)) {
            openSlider = null;
            if (sliderCard.getVisibility() != GONE) sliderCard.setVisibility(GONE);
            return;
        }
        if (sliderCard.getVisibility() != VISIBLE) sliderCard.setVisibility(VISIBLE);
        sliderTitle.setText(e.shortTitle);
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
        headTitle.setText(on ? R.string.shade_drag_title : R.string.shade_pinned);
        styleEditButton(ShadeStyle.accent(getContext()));
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

        /** The grid never scrolls itself (all twelve tiles are laid out); no auto-scroll at its edges. */
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
                SettingsManager manager = PhotonCamera.getSettingsManagerStatic();
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
        // PEEK: the handle, the header and the grid, or down to the slider card when it is open.
        View last = sliderCard.getVisibility() == VISIBLE ? sliderCard : tiles;
        int peek = listScroll.getTop() + content.getTop() + last.getBottom() + dp(14);
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
