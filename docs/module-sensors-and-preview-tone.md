# Module sensor profiles and preview tone correction

2026-09-18. Follow-up to the user's video and demosaic.cpp.

## Sensor overrides

SensorConfig now edits stable ModuleRegistry slots, with current module selected when opening the editor. Display names and assigned Camera IDs are shown. Selecting an editor target does not activate that camera. Hidden configured slots remain accessible.

Legacy physical-sensor values are copied once into each slot, without deleting originals or overwriting existing slot values. A migration marker survives reset. Black/white overrides, exposure limits, OIS, session type and custom vendor tags are scoped to the module. Hardware settings remain independent even when common image-processing settings are selected. Copying exposes individual fields under Cameras and Sensors; unselected fields are preserved. Reset confirms its target and only clears that slot.

## Preview

The supplied C++ source has dynamic preference refresh per module and a newer four-layer EF model with a target white point; the previously examined binary's embedded shader was not identical. Its host-specific EF settings are not SCAMERA photo settings, so transplanting those defaults alone cannot match our output.

RAW sampling remains library-derived. The preview now uses AutoExposureCurve.calculateCurve, shared with still processing, fed by a sparse WB/LSC-corrected RGB histogram and capture noise metadata. Module changes reset temporal smoothing; manual exposure bypasses adaptive preview gain. The current module's ACES tone/gamma settings and darktable pointwise look are applied. ACES, HSV saturation and darktable GLSL functions are shared assets, not independent approximations that can drift. Photo shader expansion was compared with the prior shader: unchanged except whitespace. Preview refreshes preferences on module change and every 500 ms.

Remaining differences: sampled versus full-frame metering, per-frame versus burst noise, Camera2/DCP preview matrix versus the full photo color pipeline, approximate gamma texture interpolation, local Exposure Fusion gain maps, local/spatial operations and optional later post-processing (Capture One, OpenDRT/Sky, LUT/profile tables) are not reproduced in full. Exact HDR preview parity is not claimed; verify bright windows/dark interiors and all physical modules on the device.

Validation: module migration/isolation/reset/copy/editor tests, shared exposure response tests, existing settings/preview regressions; GLES3 rendering verifies shadow and ACES changes affect actual pixels. Separate compilation covers SDR plus all 12 ACES tone curves with darktable. The test script also runs the previous 14 RAW GPU scenarios.
