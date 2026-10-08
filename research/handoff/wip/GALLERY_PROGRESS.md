# Gallery task — progress notes

Branch: worktree-agent-a862548ca9b7e5d2d (base migration/nice-camera2 9cfae80). Tasks: A = GALLERY_TASK.md (card-style UI),
B = launcher icon toggle, C = P59 formats + P53 DNG preview. No push, no APKs.

## Findings before coding (2026-10-08)
- Gallery code: gallery/ui (GalleryActivity, fragments Library / Viewer / Compare / Settings), adapters (ImageGridAdapter used for
  grid, filmstrip and folder column), ExifDialogViewModel + ExifDialogModel (data binding), UltraHdrGalleryUtil.
- Compare = two ImageViewerFragment panes in mode "compare" (each its own ViewPager), sync via ScaleAndPan observable.
- B: the launcher alias already exists (manifest `GalleryActivityLauncher`, enabled=true) and a switch «Скрыть иконку галереи»
  (pref_hide_gallery_icon_key, default false = icon shown) in «Видоискатель и интерфейс»; the toggle code is duplicated in
  PhotonCamera.applyGalleryIconVisibility and SettingsActivity.toggleGalleryIconVisibility, with English-only snackbars.
- The camera does not record the merge route in the file today (ImageDescription = Parameters.toString without route).
- Drag selection bug: DragSelectionItemTouchListener sends a range only to onMultipleViewHoldersSelected (empty in the
  fragment), and a vertical drag (same column) hovers one cell only: cells in between are never selected.

## Steps
(updated as commits land)
