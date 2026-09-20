# Manual tone controls for autonomous HDR

Settings → Vivo → Автономный HDR → Тональная обработка.

Nine controls are applied by `VivoHdrTone` / `headroom/render.glsl`, including
NICE neural-merge output and the existing single-frame RAW preview path. The
preview remains an approximation of a merged photograph. RAW is unchanged.
Settings are scoped by the existing module profile mechanism.

| Control | Range | Default |
|---|---:|---:|
| Exposure EV | -2 … 2 | 0 |
| Highlight shoulder | 0 … 2 | 1 |
| Shadow lift | 0 … 2 | 0.25 |
| Local contrast | 0 … 2 | 0.35 |
| Global contrast | 0.5 … 2 | 1 |
| Midtone gamma | 0.5 … 2 | 1 |
| Saturation | 0 … 2 | 1 |
| Display black | 0 … 0.1 | 0 |
| Display white | 0.7 … 1 | 1 |

Existing keys/defaults for shoulder, shadows and local contrast are retained.
Slider rows also support exact numeric input. Runtime getters accept decimal
commas, reject nonfinite values and clamp imported settings to the UI range.
Reset restores only the nine tone controls through the current preference store.

Exposure multiplies linear signal before the existing shoulder; it does not
change capture exposure or the network input. Global contrast is a monotonic
log-odds curve around 18% linear grey. Gamma adjusts luminance. Chroma is scaled
uniformly and compressed into the SDR gamut while preserving the adjusted
luminance. Display black/white set encoded output endpoints. New neutral settings
bypass grading exactly. The shared Sky renderer compiles without these controls.

## Native TCE boundary

This is SCAMERA grading, **not** a completed `VIVO_NICETCE_Process` integration.
The supplied firmware XML archive contains `nice_tce/xml` profiles, including
MainCamera/AlgoBase/NiceTceEffect.xml. They expose scene-dependent parameters,
not a safe replacement for the complete initialized native Create/Process
contexts described in `vivo-tce-boundary.md` and `vivo-tce-live-inputs.md`.
Do not equate equal-size SetParam payloads with the XML local-EQ structure without
tracing consumers. Native network gamma and HDRNet whitepoint are not artistic
UI gamma/white controls. No device firmware or SELinux rules are changed here.

## Validation

`tools/check_vivo_hdr_gpu.py` exercises the production GLSL (GLES compilation and
Mesa rendering): exact neutral equivalence, independent adjustments, 32 extreme
curve combinations, monotonic ramps, saturation, neutral endpoints and luminance
preservation. Existing HDR/merge/packing regressions remain in the same suite.
`tools/check_settings_model.py` checks bounds and comma input. Android
`SettingsMenuTest` checks inflation, persistence, profile scope and slider ranges.
Device image quality and visual navigation still require a Vivo test.
