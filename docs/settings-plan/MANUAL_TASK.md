# SCAMERA: manual controls in the shade's card style — task for Claude Code

**Reference:** interactive concept https://claude.ai/artifact/2dsrwfur4fJMzSyZzCnLep

**Base:** the settings cleanup, shade and viewfinder chrome are already in `origin/main` (`SettingsStyle`, `ShadeCatalog`, `pref_merge_route`, sheet levels in `CameraFragmentModel`). Create branch `manual-ui` from `origin/main`.

**Rules:**
- UI only. Do not change the manual models' logic or values, the capture request building, AE or sensor modes. (Models: `ManualParamModel`, `IsoModel`, `ShutterModel`, `EvModel`, `FocusModel`, `WhiteBalanceModel`. Values: `ManualStops`, `ManualWhiteBalance`.)
- Never skip, disable or weaken tests. No intermediate APKs. Do not push until I say so. The APK I install comes only from GitHub Actions.
- Commit in logical steps. After each step, run `bash ./gradlew :app:compileDebugJavaWithJavac :app:testDebugUnitTest` and report one short paragraph.

**Two things specific to the current code:**
- **Style tokens:** `:app` depends on `:circularbarlib`, so the manual views cannot use `ui/settings/SettingsStyle` directly. Move the colour tokens (`BG`, `CARD`, `TEXT`, `MUTED`, `LINE`, `FIELD`, `SHEET`, `INK`, `DIMMED`) and the `dp`/`shape`/`tint` helpers into a small class in `circularbarlib` (e.g. `circularbarlib/ui/UiTokens`, next to `AccentPalette`). Make `SettingsStyle` reference that class, so there is still one source of truth. Do not duplicate the values.
- **Strings:** every new text exists in English and Russian, following the current i18n scheme: `values` English default, `values-ru` Russian, Java text through `util/Lang` on the app side. Use the library's own `res/values` and `res/values-ru` for texts inside `circularbarlib`. The concept shows the Russian strings.

## Current code

- `circularbarlib/.../console/ManualModeConsoleImpl.java`: the five models (MF, EV, ISO, shutter, WB), tab click → `setModelToKnob`, long click → `resetModel()`.
- `circularbarlib/.../ui/ExpandingManualPanel.java`: the pill that grows from the left toggle; the `buttons_container` tabs and the `knobViewContainer` scale.
- `circularbarlib/.../ui/views/scaleview/LinearScaleView.java`: the photographic ruler with a fixed marker (amber today) and an Auto button.
- `circularbarlib/.../ui/ViewObserver.java`: binds the models to the views (photographic mode, value prefix, temperature mode).
- `app/.../res/layout/camera_fragment.xml`: `manual_mode`.

## 1. Placement and layers

- **Placement:** the manual panel floats above the bottom bar, over the lower part of the preview frame, with 12dp side margins.
- **Visibility:** it is visible only while the shade is HIDDEN (`CameraFragmentModel.SHEET_HIDDEN`; check how `manual_mode` visibility is bound today and keep that binding). A swipe up or a handle tap hides it with a short fade and slide; returning to HIDDEN brings it back in the same state (open or closed, same selected parameter).
- **Gestures:** horizontal drags on the ruler never change the shade level and never reach the lens strip. Vertical swipes on the preview keep working.

## 2. Parameter strip (replaces the tabs)

- **Layout:** one row with:
  - left: a 52dp toggle card (`CARD` + `LINE`, 20dp radius) with the sliders icon in the accent. While any parameter is manual, it shows a small accent dot in its corner. A tap collapses or expands the strip; collapsing also closes the ruler.
  - right: one card holding five equal chips, in this order: ISO, shutter, EV, focus, WB.
- **No text labels anywhere in the panel** (owner's rule): chips, the ruler header and the summary pills use **icons only**. The parameter's name stays only in `contentDescription` and in toasts. Icons are 24dp vector drawables in the panel's outline style (~1.7dp stroke, round caps). Copy the paths from the concept:
  - **ISO:** a sensor square with grain dots (sensitivity / noise);
  - **shutter:** shutter blades in a circle;
  - **EV:** a square split diagonally, with «+» top-left and «−» bottom-right (exposure compensation);
  - **focus:** focus brackets with a centre ring and ticks;
  - **WB:** a thermometer with scale marks (colour temperature in K).
  Icons are `MUTED` in auto and accent in manual; the disabled EV icon is dimmed with its chip.
- **Each chip has three lines, centred:**
  - the parameter icon (22dp);
  - the value (13–14sp, tabular figures);
  - a state line, «авто» or «ручн.».
- **In auto**, the chip shows the camera's **current metered value** in `MUTED`: ISO and shutter from the capture result, focus distance, AWB colour temperature if known, otherwise «Авто».
- **In manual**, the chip has a chip-bg fill, and its icon and value are in the accent.
- **The selected chip** gets a 1.5dp accent outline.
- **Tap** selects the chip and opens its ruler; tapping the selected chip again closes the ruler.
- **Long press** (~500ms, haptic) returns that parameter to auto. This is the existing `resetModel()`; show a toast «<имя>: авто».
- **EV unavailable (owner's rule, not optional):** when ISO **and** shutter are both manual, exposure compensation is unavailable.
  - The EV chip is disabled: ~40 % alpha, value «—», state «выкл.», `isEnabled()`-style disabled for accessibility.
  - Tapping or long-pressing it shows a toast «Экспокоррекция недоступна: ISO и выдержка заданы вручную»; it does not open the ruler.
  - If the EV ruler is open at the moment the lock happens, it closes.
  - EV is left out of the manual summary (section 4) and does not light the toggle's dot.
  - A manually set EV value is **kept but not applied**. Make sure the capture request does not apply EV while locked (`ManualParamModel` / request builder). It applies again as soon as ISO or shutter returns to auto, and the chip then shows that value.
- **No ellipsis at 360dp.** Values must fit: «1/8000», «5200K», «1,2 м», «+1 1/3».

## 3. Ruler card (restyle `LinearScaleView`)

- **Card:** `CARD` + `LINE`, 20dp radius, directly above the strip.
- **Header:** the parameter icon in the accent, then the current value in an accent pill right next to it (in auto, a muted pill with the metered value); and «Всё на авто» on the right, shown only while at least one parameter is manual. «Всё на авто» resets all five.
- **Body:**
  - left: the «Авто» button, a 14dp-radius outline card, filled with the accent while the parameter is in auto;
  - right: the scale on a darker inset (`#121519`, 14dp radius).
- **Scale:**
  - the marker is **fixed in the centre** in the **accent colour** (replaces the amber `MARKER_COLOR`), with a small triangle on top;
  - minor and major ticks in `MUTED` at different alpha;
  - major values labelled above, ISO octaves for example;
  - edges fade out.
- **Interaction (keep `LinearScaleView`'s behaviour):**
  - drag moves the scale under the marker; each crossed stop commits immediately (live preview) with a light haptic tick;
  - release with velocity gives a short fling that stops at the ends; then snap to the nearest stop;
  - the first touch on a parameter in auto starts from its current metered value;
  - arrow keys step by one stop.
- **Value lists stay as today:**
  - ISO and shutter: `ManualStops` third stops, bounded by the module's real range;
  - EV: by the AE compensation step;
  - focus: from `FocusModel` (∞ … minimum distance);
  - WB: 2000–10000 K in 100 K steps.
- **No hint line** under the scale (removed by the owner). The scale and the long-press on a chip need no caption.
- **Compact height:** the whole ruler card is about **30 % lower** than the first concept, ≈ 88–90dp at 390dp width instead of ≈ 128dp:
  - card padding 7dp/8dp, 18dp radius;
  - the header row is only the icon (18dp), the value pill (13sp, 2dp vertical padding) and «Всё на авто»;
  - the «Авто» button fills the scale's height;
  - the scale is 48–50dp tall, with labels on a 12dp baseline, major ticks 13dp, minor ticks 7dp, and the marker triangle plus line in the lower 28dp.

## 4. Manual summary in the top bar

- **Placement:** in the **top bar, between the info badges and the gear**, not in the viewfinder. The order is [route | format] group → summary → gear; the gear stays pinned to the right edge.
- **Route and format become icons in one group card** (owner's decision, always, not only with manual values): a single card (`CARD` + `LINE`, 16dp radius) holding two 40dp icon buttons, route icon then format icon, separated by a thin 1dp `LINE` divider; each has a 24dp accent icon. The card has the same height as the other top-bar items. The text «Склейка Hybrid» / «JPEG» moves to `contentDescription` (and a tooltip on long press). Copy the paths from the concept:
  - **Hybrid:** a monogram «H» in a rounded frame (the same frame as the format icons); the crossbar is a filled dot where two strokes meet, standing for frames merged;
  - **SCAM HDR:** a monogram «S» in the same rounded frame, with a small filled dot top-right (a highlight / sun accent);
  - both letters use a slightly heavier stroke (~2.1dp) than the frame, so they read at 24dp;
  - **JPEG:** a picture frame with a mountain and sun;
  - **HEIC:** a frame with a leaf (efficient compression);
  - **WebP:** a globe (web format);
  - **RAW:** a film frame with sprocket holes;
  - **RAW + JPEG:** a film frame behind a picture frame (the same icons as the shade tiles);
  - **RAW + HEIC:** a film frame behind a small framed leaf;
  - **RAW + WebP:** a film frame behind a small globe.
  - **Rule for every «RAW + X» variant:** the film frame sits behind, top-right, drawn only outside the front shape; the front shape is the X icon, scaled down to the lower left. Every format value the app offers must have an icon. Add a unit test that maps each option of the format setting to a drawable.
- **Summary:** it takes all remaining width between the format icon and the gear; with icon badges it usually fits one line.
- **Visibility:** shown only while at least one parameter is manual and applied. EV is left out while it is locked.
- **The card:** `CARD` + `LINE`, 16dp radius, same height as the other top-bar items. It takes the free width between the two and never extends under the gear (`weight=1`, `minWidth=0`).
- **Content:** an accent **M**, then one item per manual parameter in the strip's order: the parameter icon (14dp, accent) + value (12–13sp, accent, tabular figures), e.g. [ISO] 2560, [shutter] 1/8, [focus] ∞, [WB] 4700K.
- **Layout:** the items flow in a row and wrap to a second line only if they don't fit. The top bar grows to fit, and the preview frame starts below the top bar's actual height. Never truncate, and never use an ellipsis.
- **Check:** a test at 360dp and 412dp with the maximum number of applied manual values (four: ISO, shutter, focus, WB; or shutter, EV, focus, WB):
  - the summary intersects neither the gear nor the route and format icons;
  - no item lies outside the card;
  - the preview frame starts below the top bar.

## 4b. Bottom bar (matches the current app)

- **One shooting mode:** there is no «Фото | Ночь» switch. If any leftover mode-switch view or reference still exists, remove it.
- **Bottom row, as in the app today:** gallery thumbnail (left), shutter (centre), front/back camera switch (right). The manual work does not touch this row.
- **Lens strip:** unchanged. On the Vivo X200 Ultra it is 0,4× · 1× · 2,4× · 5× · 10×.

## 5. Tests

- **Unit:**
  - the chip text and format for each parameter: ISO, «1/8000» / «2 с», EV in thirds «+1 1/3» / «−2/3», «∞» / «1,2 м» / «30 см», «5200K»;
  - the EV lock: disabled with ISO+shutter manual, value kept, not applied to the request, restored after ISO or shutter returns to auto;
  - «Всё на авто» resets all models.
- **Robolectric:**
  - the strip renders five chips with the correct state classes;
  - long press calls `resetModel()`;
  - no chip text overflows at 360dp.
- **On the phone:**
  - ruler drag and fling are smooth, one haptic tick per stop;
  - values match the capture result;
  - the panel hides and returns with the shade;
  - no conflict with lens-strip swipes.
