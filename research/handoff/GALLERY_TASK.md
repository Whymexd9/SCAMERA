# SCAMERA: gallery in the new card style — task for Claude Code

**Reference:** the interactive concept https://claude.ai/artifact/AE4t3NyLqPJXWG43m5PfXS. It covers the library, viewer, details sheet, delete sheet, folders sheet and compare screen. The photos in it are drawn placeholders.

**Base:** `origin/main`, new branch `gallery-ui`. The settings, shade and viewfinder styles are already in main. Reuse `SettingsStyle` and the colour tokens; if the manual-controls task has already moved them into `circularbarlib`, use that location. Reuse the route monograms (H / S) and the format icons (JPEG, HEIC, WebP, RAW, RAW + X) from the viewfinder. Do not draw new copies.

**Rules:**
- UI only. Keep the existing data and file logic: `GalleryViewModel`, `ExifDialogViewModel` / `ExifDialogModel`, `SelectionHelper`, `GalleryFileOperations`, `ImageFile` / `MediaFile`, `compare/ScaleAndPan`, the folders preference, `UltraHdrGalleryUtil`, share/edit intents, deletion.
- Every new text exists in English and Russian, following the current i18n scheme.
- No ellipsis and no overlaps at 360dp.
- Never skip, disable or weaken tests. No intermediate APKs. Do not push until I say so.

**Process:** commit in logical steps. After each step, run `bash ./gradlew :app:compileDebugJavaWithJavac :app:testDebugUnitTest` and report one short paragraph.

## Current code

- `gallery/ui/GalleryActivity`, `ui/fragments/ImageLibraryFragment`, `ImageViewerFragment`, `ImageCompareFragment`, `GallerySettingsFragment`.
- `adapters/ImageGridAdapter`, `ImageAdapter`, `DragSelectionItemTouchListener`, `LongPressItemTouchListener`, `DepthPageTransformer`.
- `views/Histogram`, `CustomSSIV`, `SquareImageView`.
- Layouts:
  - library: `fragment_gallery_image_library.xml` (FABs `share_fab`, `delete_fab`, `number_fab`, `compare_fab`, `settings_fab`; the folder chips);
  - viewer: `fragment_gallery_image_viewer.xml`, `gallery_viewer_top_buttons.xml`, `gallery_viewer_bottom_buttons.xml`;
  - details: `exif_dialog.xml`;
  - compare: `fragment_gallery_image_compare.xml` (`sync`, `screen_share_button`);
  - grid cell: `thumbnail_square_image_view.xml`;
  - landscape: `layout-land/fragment_gallery_image_viewer.xml` and `layout-land/fragment_gallery_image_compare.xml` (restyle these too; in landscape, compare panes sit side by side).

## 1. Library

- **Header:** the settings-screen header with the "SCAMERA" eyebrow and the title «Галерея».
  - Left: a 44dp card button with a camera icon that returns to the camera.
  - Right: a 44dp card button with a folder icon that opens the folders sheet (replaces `settings_fab`).
- **Folder chips:** pill chips «Все», «SCAMERA», «Скриншоты», «Download» …, each with a small count. The active chip is filled with the accent. Chips are at least 40dp tall and scroll horizontally.
- **Day sections:** a small uppercase accent label («Сегодня», «Вчера», «6 октября») with «N фото» on the right.
- **Grid:** 3 columns, 6dp gap, square thumbnails with 14dp radius and a 1dp `LINE` stroke.
- **Thumbnail badges:** in the bottom-left corner, small dark rounded badges with icons only:
  - the format icon;
  - an Ultra HDR icon (a sun) when the photo carries a gain map.
  The badges must stay legible: icon ≥ 14dp, on a solid dark backing.
- **Selection:**
  - Long press starts selection with a haptic tick, and the pressed photo is selected.
  - Dragging across thumbnails then selects them; keep `DragSelectionItemTouchListener` and make vertical drags work.
  - While selecting, a tap toggles a photo. A selected thumbnail shrinks slightly inside an accent border and shows an accent check circle.
- **Selection bar:** replaces the FABs. It is one floating card at the bottom:
  - × to clear;
  - an accent count pill with a correct plural («3 снимка»), which replaces `number_fab`;
  - «Сравнить» (enabled only with exactly 2 selected);
  - «Поделиться»;
  - «Удалить» (warm warning colour).
  The labels must not touch at 360dp: shorten the spacing or drop to icons with a `contentDescription` if needed.
- **Empty folder:** a centred muted message.

## 2. Viewer

- **Top row:**
  - back (44dp card);
  - a meta card with two lines: «Сегодня, 11:17» and «0,4× · ISO 100 · 1/250»;
  - the [route monogram | format icon] group card, as in the viewfinder, with accessible names.
  Below it on the right, a pill toggle «HDR вкл / выкл» for photos with Ultra HDR (the app's existing Ultra HDR on/off).
- **Bottom:**
  - a filmstrip card with 44dp thumbnails; the current one has an accent border and is kept centred;
  - a button card «Поделиться · Изменить · Сравнить · Сведения · Удалить»: icon above a short label, «Удалить» in the warning colour, all buttons the same height.
- **Gestures:**
  - horizontal swipe changes the photo (keep `DepthPageTransformer` or the current pager);
  - double tap zooms in and back, and a small «250 %» pill shows the zoom;
  - pinch zoom as now;
  - single tap hides or shows all controls (immersive). While hidden, a small mini-EXIF pill appears. The controls come back visible the next time the viewer opens;
  - swipe down at 1× closes the viewer.
- **«Сравнить»** opens compare with the neighbouring photo (the existing quick compare).

## 3. Details sheet (replaces `exif_dialog`)

The bottom sheet is in the card style; drag down or tap the scrim to close. Contents:
- the file name and «день, время · папка»;
- **Съёмка:** four cards with icons only (the same icons as the manual controls), each with a value: ISO, exposure time, f-number, focal length;
- **Гистограмма:** the existing `Histogram` view in a card;
- **Файл:** rows with an icon, a label and a value:
  - Склейка (H/S + «Hybrid» / «SCAM HDR»), only if the file records it; otherwise hide the row;
  - Формат (+ «Ultra HDR»);
  - Объектив and device;
  - Разрешение and MP;
  - Размер;
- Escape or back closes the sheet; focus moves into it when it opens.

## 4. Delete and folders sheets

- **Delete:** a sheet «Удалить N снимков?» with the correct plural and a short explanation, a warning-colour «Удалить» and a card-style «Отмена». Keep the current deletion behaviour exactly; do not add behaviour such as deleting paired DNGs unless it already exists.
- **Folders:** a sheet with the folders from the existing `pref_folders_list` setting as switch rows (folder icon, name, path).

## 5. Compare

- **Header:** «Сравнение», with back and swap buttons.
- **Panes:** two panes stacked vertically. Each has a small label card: route + format icons, time, ISO.
- **Gestures:** double tap zooms and drag pans. With «Зум и сдвиг вместе» on (the switch card at the bottom, replaces `sync`), both panes follow each other.
- **Share:** a share card button (the existing `screen_share_button`).
- **Back** returns to where compare was opened from: the library selection or the viewer.

## 6. Tests

- **Robolectric:**
  - the grid cell shows the right format and Ultra HDR badges;
  - the selection bar count uses correct plurals (1, 2, 5, 11, 21);
  - «Сравнить» is enabled only at exactly 2;
  - the details sheet hides the «Склейка» row when the file has no route info.
- **Layout test at 360dp:** no text overflow in the selection bar, viewer top row, button card and details rows.
- **On the phone:**
  - drag-select over two rows;
  - viewer swipe, zoom and immersive mode;
  - delete from viewer and from a selection;
  - compare with sync on and off;
  - the Ultra HDR toggle on an Ultra HDR photo.
