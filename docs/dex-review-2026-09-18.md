# Review of supplied DEX files — 2026-09-18

Scope: static inspection of the four uploaded DEX files, targeted instruction-level review and comparison with current SCAMERA sources. No Android app or native library was executed. APK resources/layouts/fonts are not included, so this is a behavioural/design-code review, not a pixel comparison.

## Inputs

- `classes.dex`: SHA-256 `4db8ac27b4f40ef5a6d8ab849e15d741839dea659bacf63e02284ca2594e2cfd`
- `classes2.dex`: SHA-256 `63a22a26082e4245454092822c1bee765173de9c567baf9632bb7bdf7829e6a0`
- `classes3.dex`: SHA-256 `452636c5773631904fc9e01ed0f1197b9dff22c561e0d61a502e15573eccd476`
- `classes4(1).dex`: SHA-256 `beca7d11a6cbed0e1d88f3c39702835c9a5ddda799d5e93231aa62fcd104e3f3`

Inventory: 7,032 / 7,716 / 1,714 / 421 classes respectively. The first two DEX files largely contain obfuscated host-app code. The fourth includes the mod's named UI, lens, colour and profile components. Class names alone were not treated as evidence of working functionality: the points below are supported by method bodies or call sites.

## Useful candidates for SCAMERA

| Candidate | Evidence in supplied code | Value and boundary |
| --- | --- | --- |
| RAW viewfinder bridge | `com.mod.DemosaicProxy` in classes.dex: `tryProcessRawImage`, `processRawBuffer`, `processHardwareBuffer`, shading-map setters, CFA/crop/ISO fields. `pni.f/g`, `nrj.u`, `tbt.r/w` in classes2.dex invoke it on image/buffer and metadata paths. | Previously missing Java bridge is now available. Image wrapper accepts format IDs 32, 37, 38, passes row stride, format, dimensions and crop. Useful reference for RAW16/10/12 routing, focus peaking and coherent metadata. It does not itself guarantee native compatibility or correct results on Vivo. |
| Pinned processing controls | `mod.custom.OptionsButtonSelectedParams`: persistent ordered selection, list/switch/text/hex-float types, move/remove, separate ordinary/Pro lists, lens-resolved value keys. | User-chosen processing settings in the viewfinder, beyond the five exposure/focus controls. Rebuild on SCAMERA's typed preference catalogue rather than porting GCam keys. |
| Precision parameter carousel | `mod.custom.carousel.ArkCarouselOverlay`, `ArkRulerView`: numeric dialog, reset, drag removal, variable steps 0.001/0.01/0.1/1, `VelocityTracker`, `OverScroller`, haptic ticks. | Accurate adjustment without opening settings. Host marks some edits as requiring restart and shows Apply; do not describe every parameter as truly live. Use actual SCAMERA parameter bounds. |
| LUT editor and preview | `mod.lut.CustomLutCreator`: 6 hue/saturation/luminance sectors, brightness/contrast/gamma, shadows/highlights, skin protection, split tone; `buildPreviewBitmap`, `buildLutData`, `saveCube`. `CubeParser` / `LutApplicator` implement cube import and trilinear/tetrahedral bitmap application. | SCAMERA already has PNG LUT input; the added value is editable looks, sample-image previews and `.cube` interchange. A sample preview is not evidence of live-camera LUT preview. |
| DCP matrix import | `mod.Color.DcpParser.loadDcp` reads two illuminants, ColorMatrix1/2 and ForwardMatrix1/2; `getInterpolatedMatrix` blends matrices; `ColorTransform.SelectPseudoCT` consumes it and passes a matrix to DemosaicProxy. | Per-sensor calibrated colour profiles. This is matrix support, not proof of full DCP hue/saturation tables, look tables and tone-curve support. |
| Exportable tone/gamma presets | `mod.curve.CurvePresets` reads/writes named text files: `TONE_CURVE 17` and `GAMMA_CURVE 33`, with built-in asset fallback; `overwhelmer.*Graph` and graphlib supply graph UI. | Add editable/shareable curves alongside existing SCAMERA tone modes. File presets/assets themselves were not supplied. |
| Module-selection feedback | `mod.lens.mdmitriev.AuxComposeZoomAnimator`: selected scale animation 220ms with OvershootInterpolator, unselected 200ms with deceleration, haptic feedback, orientation rotation 220ms, label formatting. `ZoomClassifier` derives relative zoom from focal length and sensor size. | Refine the existing selector while retaining the approved size. The indicator position uses immediate setTranslationX, so this code does not prove a sliding-disc transition. |
| Active configuration/profile label | `mod.custom.ViewfinderInfoOverlay.update` displays installed configuration basename and profile name with preference-controlled visibility. | A small optional indicator prevents confusion between per-module profiles. |

Other components include watermark composition, noise-model management, configuration import/export, a gesture-operated settings curtain and shared viewfinder colour helpers. These are secondary to the candidates above; presence in the DEX is not a measured quality/performance result.

## Native integration boundaries

The earlier supplied `libvf_demosaic(1).so` exports five matching DemosaicProxy entry points: raw buffer, hardware buffer, payload hardware buffer, shading upload and shading reset. Matching names alone are not sufficient validation. Native dependencies on host static fields/preferences, context ownership, buffer lifetime, EGL state, RAW packing and module-change reset still need auditing. In particular, the payload entry point is only 8 bytes in this binary and must not be assumed to implement another complete processing path.

`DemosaicHelper` and `MC.cct.NativeCctBridge` also expose native APIs; their Java declarations do not include the native algorithms. GCam JNI wrappers and RamPatcher address/key tables are host-specific and cannot be treated as ready-made SCAMERA denoise/HDR/NPU implementations. No supplied code was transplanted or loaded.

Suggested order: finish native viewfinder compatibility analysis; prototype pinned controls and precision adjustment; then add LUT editing and calibrated matrix/curve workflows. Exact ordering remains the user's choice.

## Accent fix queued for the next APK

The screenshot shows a correctly tinted back arrow but lavender search, section icons and chevrons. Eleven `settings_concept_*.xml` vectors still used hardcoded `#D3C9F4`; these now resolve `?attr/colorAccent`. Search clear and search-match/target highlighting now use the selected AccentPalette colour rather than fixed lavender constants.

Validation this turn: XML parse and source/diff review only. No Gradle invocation, APK assembly, GitHub push or Actions run: explicitly requested by the user. Device/render regression validation is deferred to the next authorized build.
