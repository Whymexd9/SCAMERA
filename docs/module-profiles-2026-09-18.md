# Module profiles and compact camera controls

## User-facing changes

- Full-width viewfinder with black rounded-corner masks and no inset border.
- Three separate top controls and consistent thin-line icons.
- Lens selector sized against the approved 930px-wide reference: 54.6% screen width, height 8.8% screen width, selected disc 7.7%, label 2.8%.
- Manual controls start collapsed into a left-side 48dp button. The translucent background expands over 330ms and retracts over 250ms; labels are revealed without stretching. Closing retains the selected values.
- Full-width manual scales retain the approved 61.6dp height.
- Camera settings contain a per-module toggle, slot editor, and selective copying. Slots can share a physical camera while retaining different profiles. Visible buttons support tap and previous/next swipe without wrapping; transitions are throttled to 500ms.
- Copying uses the production preference hierarchy and generated processing controls. Groups can be entered, toggled wholesale, or edited individually. Select all and clear selection apply to the current group. Multiple destinations are supported.
- Manual ISO, shutter, focus, WB and EV values also persist in profiles. Restored values select the nearest supported sensor setting.

## Storage and migration

`ModuleProfiles` keeps typed snapshots (including string sets) and a separate common baseline. Disabling per-module settings restores the baseline and retains saved profiles. Camera IDs, module mappings, sensor configuration, theme and diagnostic/infrastructure preferences remain shared. Legacy per-camera JSON snapshots are read when first opening a new profile. Dynamic `pref_tunable_*`, `hexquad_*`, and `scamera_*` processing controls are included. Copying updates only explicitly selected local keys.

The stable identity is the button slot (`back0..7`, `front0..7`), not its display name or physical Camera ID. Physical-only cameras discovered under a logical camera use SCAMERA's existing `logical-physical` route. Manually entered vendor IDs still depend on what the device Camera HAL exposes to the app.

## Input analysis and live viewfinder

The supplied `classes4.dex` exposes the `mod/lens` ID discovery, slot-specific preference suffixes, and auxiliary-button behavior. The implementation uses SCAMERA's camera/session code rather than transplanting host-app classes.

The supplied `libvf_demosaic(1).so` exposes DemosaicProxy JNI entry points for RAW/hardware-buffer processing and lens shading. Its complete Java bridge/signatures are absent from the supplied DEX, so this binary is not loaded by this build. Instead, SCAMERA's own RAW renderer now receives live Camera2 WB gains, a correctly ordered colour matrix, dynamic black/white levels and available lens-shading maps (including physical-camera results). Missing metadata falls back to identity/static sensor metadata. It uses the latest preview result, not strict timestamp matching. Preview RAW is still limited to the existing RAW_SENSOR stream path; this is not a complete clone of the supplied native library's tone/LUT/peaking implementation.

The GL consumer owns a separate reusable snapshot buffer so a faster producer cannot overwrite an image during GPU upload. Black levels remain in raster-site order for all CFA phases.

## Validation scope

Automated checks cover typed profile isolation, selected-only copying, common-profile restoration, legacy migration, copy-menu traversal, existing settings/scale controls and RAW buffer ownership. Device checks remain necessary for vendor camera IDs, lens-specific RAW metadata, visual motion smoothness, capture behaviour and thermal/performance characteristics.
