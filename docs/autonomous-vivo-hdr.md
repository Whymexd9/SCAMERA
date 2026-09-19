# Autonomous HDR — Vivo-inspired adaptation

The switch `pref_vivo_hdr_enabled` selects a self-contained path for Photo/ZSL
and Night. It is off by default. It needs no root, camera service injection,
files from `/vendor`, or separately installed Vivo runtime. Only code/shaders
shipped in the APK and Android camera/graphics APIs are used for this mode.
Existing native MFSR / HexQuad / vendor remosaic are mutually exclusive; select
Bayer or SCAMERA remosaic first. Their stored settings are retained.

## Implemented path

1. Existing timestamp-associated RAW metadata, focus/sharpness/gyro selection.
   At least three normal frames and one short exposure are requested; existing
   bracket EV/count controls still allow a larger series. Missing/unusable
   donors fall back to the available reference rather than discard the photo.
2. Black-level normalization, CFA-aware packing and SCAMERA remosaic if selected.
   Pyramid alignment is forced on; FlowNet/KernelNet are bypassed in this mode.
3. Matching always uses an immutable reference. Each donor is exposure-normalized;
   calibrated or per-frame HAL noise accounts for gain-squared variance.
4. Motion rejection excludes clipped reference channels from residual testing,
   rejects invalid warped borders and uncertain flow. Soft mask erosion expands
   rejection around motion boundaries. Full clipped regions can use a valid,
   geometrically aligned short exposure; there is no semantic motion detector.
5. Inverse-variance weighted accumulation with one weight per CFA quad. A clipped
   reference can be fully replaced by a short exposure (not averaged into it).
   Bayer values are transported at the shortest exposure scale, preserving HDR
   radiance through the existing 16-bit RAW interface. Weight-square sums give a
   conservative effective sample count for downstream denoising.
6. Demosaic and black correction; separate noise-aware luma/chroma spatial filtering
   at two scales, using the scaled stack noise model. Both controls at zero bypass
   this stage. Temporal fusion still reduces noise independently.
7. Histogram exposure, SCAMERA lens shading/white balance/colour matrix, logarithmic
   highlight shoulder, bounded shadow lift and local contrast, sRGB output. This
   replaces the selected tone path rather than stacking another full curve on it.
   The existing linear scene snapshot remains available for Ultra HDR encoding.
8. Existing sensor and RawTherapee sharpening (USM, deconvolution, microcontrast)
   with a shared HDR multiplier; zero skips them. Existing JPEG/Ultra HDR saving.

Preview uses the same HDR tone renderer, without burst fusion/spatial NR.
The mode bypasses the optional Bayer AI, native Vivo upscale, GCam finish, ESD3D,
RawTherapee denoise and Local Laplacian to avoid duplicate/conflicting processing.

## Controls

All floating-point controls validate 0–2 including imported values.
Luma defaults to 0.6, chroma 1, tone 1, shadows 0.25, local contrast 0.35,
sharpen multiplier 1. Tone zero removes the HDR shoulder; colour conversion,
exposure and display gamut mapping remain. Shadow/local controls are independent.
Sharpening algorithms must be enabled in the existing sharpening menu.

## Fidelity boundary

This is NOT the original neural NICE, not its weights/inference, and not a
bit-exact BRPX port. Phone runtime evidence shows NICE/Qualcomm components loaded,
not that BRPX executed. The recovered BRPX pipeline provided the architectural
reference: exposure normalization, motion masks, highlight-dependent fusion and
confidence fallback. SCAMERA uses Bayer-domain fusion and its own pyramid warp,
mask morphology and inverse-variance weights rather than the donor RGB/OpenCL ABI.
NICE denoising is replaced with deterministic noise-aware filtering. PatchMatch
reconstruction is replaced by reference fallback, so missing detail is not
synthesized. Donor logarithmic uint16 output is deliberately replaced by linear
RAW transport followed by the application's colour/tone pipeline. LSC/AWB are
applied by the existing calibrated renderer rather than the donor 4-plane map ABI.

Still needed for a true neural reproduction: active model routing, complete
input/output tensor contract, preprocessing/VST/tiling/IVST, a runnable bundled
runtime without vendor services, and a same-burst stock reference result.

## Validation

`tools/check_vivo_hdr_gpu.py` executes production shaders through Mesa EGL and
compiles their GLES 3.1 forms. Fixtures cover exposure ratios, clipped-reference
replacement, clipped donors, moving objects, out-of-bounds warp, black input,
weighted stack noise reduction, spatial NR bypass/edges, tone monotonicity and
independent tone/shadow/local controls. Existing Sabre and GCam shader checks
protect the disabled-mode paths. APK compilation and physical phone testing are
separate gates; Mesa is not an Adreno device test.
