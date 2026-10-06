# SCAMERA: quick-settings shade — task for Claude Code

This is a separate task from `docs/settings-plan/SETTINGS_CLEANUP_PLAN.md`. Do it **after** the settings-cleanup work, on top of that branch, because it reuses pieces that work introduces:
- `SettingsStyle` (P6b);
- `pref_merge_route` (P3);
- the "Hybrid" naming (P6a);
- `SettingsMigration.removeObsolete` (section 0 recipe);
- the portable config file (P7).

If any of them is not there yet, stop and tell me instead of re-creating it differently.

Rules (same as the settings task):
- never switch sensor modes, never touch capture or processing — this is UI only;
- never skip, disable or weaken tests;
- no intermediate APKs; do not push until I say so; the APK I install comes only from GitHub Actions.

Work on a branch `shade` created from `settings-cleanup`. Commit in logical steps:
1. data model;
2. tiles and levels;
3. catalog;
4. reorder;
5. migration and tests.

After each step: run the checks, then report one short paragraph.

**Reference:** interactive concept: https://claude.ai/artifact/B8KCemNX8QeNyyR1rGx2fQ
- It supersedes the older `SHADE_SPEC.md` (concept E).
- The visual style is the same as the new settings screens (settings plan, phase P6b) and reuses `SettingsStyle`. The shade is in the same card language: `CARD` tiles with a `LINE` stroke, the user's accent, small uppercase accent labels.

**Current code:**
- `ui/camera/views/settingsbar/` (`SettingsBarLayout`, `SettingsBarEntryView`, `SettingsBarListener`);
- `ui/camera/viewmodel/SettingsBarEntryProvider` (entries + `resetRemovedSettings`);
- `model/SettingsBar*`;
- `control/Swipe.java`;
- `res/layout/camera_fragment.xml` (`settings_bar` above `layout_bottombar`, `manual_mode`).

#### 1. Levels and gestures (kept from concept E)

- **Levels:** HIDDEN (only the handle) → PEEK (pinned tiles) → FULL (pinned tiles + all shade groups). FULL is at most ~86 % of the viewfinder height.
- **Placement:** the sheet sits above `layout_bottombar` and never covers the lens buttons, the shutter or the mode switch. Use `BottomSheetBehavior` in a `CoordinatorLayout` above `layout_bottombar`.
- **Swipes on the viewfinder:** up goes one level higher, down one level lower.
- **Swipe down inside a scrolled FULL list:** first scrolls to the top, then lowers the level.
- **Handle tap:** HIDDEN → PEEK → FULL; from FULL back to PEEK. A tap on the scrim in FULL → PEEK.
- **`control/Swipe.java`:** `SwipeUp()` / `SwipeDown()` change the level. Remove the `ManualModeConsole` / `ocManual` calls from `SwipeUp()`.
- **Manual panel:** `manual_mode` is visible only in HIDDEN.

#### 2. Tiles

- **Grid:** 4 columns, square cards, 10dp gap. Maximum **12** pinned tiles. A last dashed tile "Добавить" is shown while fewer than 12 are pinned.
- **Content:** a tile shows the icon of the **current value** (e.g. flash off / auto / torch) plus a short value ("R+J", "12 МП", "0,60"), with the setting name small underneath. Toggle tiles show only the name.
- **Highlight:** a list or slider tile whose value differs from the default, and a toggle tile that is on, is filled with the accent and uses dark ink.
- **Tap by tile type:**
  - list: next value;
  - toggle: flip;
  - slider: open an inline slider card under the grid; tap again to close.
  Each tap shows a short toast "Имя: значение".
- **Unavailable settings:** if `SettingsAvailability` says a setting is unavailable, its tile is dimmed (~45 %). A tap shows the reason in a toast instead of changing the value.
- **No ellipsis:** no text is cut with an ellipsis at 360dp. Values have short labels: a `shortEntries` array per list setting in a `ShadeCatalog` registry; fall back to the full entry only if it fits.

#### 3. Any setting can be pinned

- **Pinnable settings:** every switch, list and slider in the settings tree (`SwitchPreference*`, `ManagedSwitchPreference`, `ListPreference`, `UniversalSeekBarPreference`, tunable checkbox/seekbar). Not pinnable: screens, actions, free-text `EditTextPreference`, the hybrid denoise tables.
- **Catalog:** the "Добавить" tile opens a full-screen catalog in the settings style:
  - header with the "SCAMERA" eyebrow and the title "Добавить в шторку";
  - a search field over all pinnable settings, matching title and section;
  - results grouped by settings section;
  - each row: icon, title, current value, and a "Добавить" / "В шторке" button;
  - a counter "В шторке N из 12".
  Build it from the inflated preference tree, the same source as settings search, so new settings appear automatically.
- **FULL level:** pinned tiles first, then a curated set of shade groups, accordion style with one open group:
  - Съёмка: вспышка, таймер, замер, склейка;
  - Формат: RAW, разрешение Hybrid, даунсемплер, Ultra HDR, 16:9, водяной знак;
  - Hybrid: Bento, Bento кадров, Shasta, N-кадров, Luma, Chroma, резкость, тон ARK, отбраковка;
  - SCAM HDR: N-кадров, удлинение L, мозаика ISZ, Exposure Fusion;
  - Цвет и резкость: кривая Hybrid, USM;
  - Видоискатель: сетка, пик фокуса, живой RAW, отладка.
  Each row has an inline control (segmented / switch / slider) and a pin button that pins or unpins it.
- **Storage:** the pinned list is an ordered list of preference keys in `pref_shade_tiles` (common, not per-lens).
  - Default: `flash`, `timer`, `pref_save_raw_key`, `pref_merge_route`, `pref_lmc_hybrid_output`, `pref_show_grid_key`, `pref_ultrahdr_key`, `pref_lmc_hybrid_bento`.
  - Unknown or removed keys are dropped on read; keys removed by the settings cleanup are dropped via `SettingsMigration.removeObsolete`.
  - The list is part of the portable config file (settings plan, phase P7) under `common`.
- **One source of values:** tiles read and write the same SharedPreferences keys as the settings screen. Per-lens keys go through `ModuleProfiles` as usual. Changing a value in the shade updates the settings screen and the reverse; listen with `OnSharedPreferenceChangeListener`.

#### 4. Reorder by long press

- **Entering:** a long press (~400 ms, with haptic feedback) on any pinned tile enters edit mode and immediately lifts that tile under the finger. The header button "Изменить" also enters edit mode, where a short press-and-drag (~120 ms) lifts a tile.
- **Edit mode:**
  - tiles wobble (not with reduced motion);
  - each tile shows "×" to unpin;
  - the header shows "Перетащи плитки" and "Готово".
- **Dragging:** the other tiles shift live to make room. The new order is saved on drop. Drag between rows works.
- **Implementation:** `RecyclerView` with `GridLayoutManager(4)` and `ItemTouchHelper` (drag flags in all four directions; long-press drag enabled), or an equivalent custom drag. The pressed view must keep its touch stream: no adapter rebuild in the middle of a gesture.
- **Swipes in edit mode:** a vertical swipe that starts on a tile does not move the sheet; swipes on the viewfinder still do.

#### 5. Migration

- Map the old settings-bar entries (flash, timer, RAW, grid, AE metering, hybrid resolution, hybrid downsampler) to their keys for the first `pref_shade_tiles` value.
- Delete entries for settings removed by the settings cleanup and the `resetRemovedSettings` leftovers (HDRX, EIS, energy saving, Quad, FPS, bracketing).

#### 6. Tests

- Unit test `ShadeCatalog`: every curated key exists in the inflated tree, has an icon, and every list entry has a short label of at most 8 characters.
- Unit test: the order in `pref_shade_tiles` survives save/load, unknown keys are dropped, the maximum of 12 is enforced.
- Robolectric test: the tile renders the active highlight for non-default values; the catalog search finds "Luma".
- Device checks:
  - long-press drag on a real phone;
  - swipes;
  - no ellipsis at 360dp;
  - the shade never covers the shutter.

#### 7. Viewfinder chrome in the same style

The concept shows the camera screen with an empty preview. Apply the same card style to the real viewfinder UI around the live preview. This is UI only: the preview stream, session and capture are untouched.

- **Preview frame:** rounded 26dp frame with a 1dp `LINE` stroke on `BG`, with thin corner marks. The grid overlay is drawn inside the frame with ~15 % white lines.
- **Top bar:** two card badges (`CARD` + `LINE`, 16dp radius) with accent icons:
  - "Склейка Hybrid / SCAM HDR";
  - the save format with its icon and short value (JPEG / R+J / RAW).
- **Lens strip:** one pill-shaped card (`CARD` + `LINE`) holding the lens buttons, with the existing three-dot separators between them. The active lens is filled with the accent; while zoomed between lenses, it shows the current zoom ("2,5×").
- **Lens gestures:** `ui/camera/views/AuxButtonsLayout` already supports them; restyle only and keep the behaviour exactly.
  - tap selects a lens;
  - a quick horizontal swipe on the strip goes to the next or previous lens (≥ 24dp);
  - a slower horizontal drag zooms continuously (`setZoomDrag`) and shows the zoom ruler (`setDialRefresh`), restyled as a small card above the strip with the zoom value in the accent.
  The shade's vertical swipes must not steal these horizontal gestures, and vice versa. A horizontal move on the strip never changes the shade level. The FULL shade and its scrim stop above the bottom bar, so the strip stays usable at every shade level.
- **Bottom bar size:** the whole bottom bar (`layout_bottombar` with the lens strip, shutter row and mode switch) is **15 % taller** than now, and its contents scale by the same 15 %: lens buttons, shutter, gallery thumbnail, mode switch and text sizes. The shade and the preview move up accordingly; the preview aspect ratio does not change.
- **Bottom row:** a three-column grid (`minmax(0,1fr) auto minmax(0,1fr)` equivalent: the shutter stays exactly centred and the side items never overlap it at 360dp):
  - gallery thumbnail on the left as a card;
  - shutter in the centre: a ring in `TEXT` with a filled centre;
  - the mode switch "Фото | Ночь" on the right as a segmented card; the active segment is filled with the accent.
- **Settings entry points (three):**
  - **Gear button**, top-right of the top bar: the existing `settings_button` in `layout_main_topbar.xml` → `CameraFragment.launchSettings()`, restyled as a 44dp square card (16dp radius) with an accent gear icon. It stays visible at every shade level; in FULL it sits under the scrim, and a tap on the scrim lowers the shade first.
  - **Gear in the shade header**, right of "Изменить": a 36dp square card with an accent gear, visible at PEEK and FULL (hidden in edit mode). It calls the same `launchSettings()`.
  - **"Все настройки" card** at the very end of the FULL level, under "Другие настройки": gear icon, title, the summary "Hybrid, SCAM HDR, цвет, захват, конфиг", and a chevron. It calls the same `launchSettings()`. Reuse the existing string `sheet_all_settings`.
- **Hints and toasts** use the card style (`CARD`, `LINE`, `MUTED` text).

#### 8. Format icons (RAW / JPEG)

Each value has its own icon, no letters inside icons:
- JPEG: picture frame with a mountain and sun;
- RAW: film frame with sprocket holes;
- RAW + JPEG: a film frame behind a picture frame.

The back frame is drawn only outside the front one, so the icon still works on an accent-filled tile.

Use the same icons on the tile, in the top-bar badge and in the catalog. Copy the SVG paths from the concept into 24dp vector drawables with a ~1.7dp stroke.
