# RawTherapee 5.12 native noise reduction

This is a native CPU port of RawTherapee's `FTblockDN.cc`, not a tuning of ESD3D2.
The original SCAMERA denoiser remains available and is the default.

## Scope and provenance

RawTherapee tag 5.12, commit `a8a3d1bc7b419ed1c974c39276c97e618a4f9a1b`.
Original wavelet decomposition, standard/BiShrink filters, DCT detail recovery,
median filters, gamma/color routines and FlatCurve sampling are retained.
The full FTblockDN translation unit, including automatic noise estimation, is built.
`UPSTREAM.json` records hashes of the source files before adapter changes.

FTblockDN numerical bodies are unchanged; desktop includes are replaced by a
small adapter. The adapter supplies planar images, the fixed ProPhoto working
space, original default noise settings, mutex/timing and parameter storage.
Color and curve functions were extracted from upstream; unrelated desktop/ICC
features are not linked. Automatic global chroma uses the original nine-region
estimator and aggregation formulas; crop coordinates are clamped for small inputs.

Both license texts ship in APK assets. The corresponding source, adapters and
build instructions are in this repository. FFTW 3.3.9 is built from the pinned
ALICE distribution mirror, which contains generated codelets absent from FFTW's
development repository. No Vivo proprietary denoise library is used.

## Pipeline and UI

RawTherapee → Детализация и шумоподавление → Алгоритм шумоподавления →
RawTherapee 5.12 — CPU. Open the adjacent native settings screen.

The stage runs after ABLC and demosaicing, before tone/color rendering. It replaces
ESD3D2 when selected, including in HDR+ mode. Tetra Detail remosaic is unchanged.
The input camera RGB is transformed into linear ProPhoto using
`sensorToProPhoto * diag(whitePoint)`; the inverse restores camera RGB afterwards.
RT consumes 65535-scaled floating point values. Capture HDR merge and optional AI
denoise are independent stages: their switches are not silently overridden.

Controls: luma, chroma, detail recovery, red-green, blue-yellow, gamma, Lab/RGB,
conservative/aggressive, manual/global auto chroma, median channels/kernel/passes,
auto gain and exposure compensation, luma and chroma curves. Automatic mode
disables the three manual chroma sliders. RGB median supports only 3×3 and 5×5,
as in this upstream implementation. Exposure compensation here belongs to the
denoise model; it does not change capture exposure.

Curves accept RawTherapee's control-point representation (decimal point):
`1; x; y; leftTangent; rightTangent; ...`. At least two points, x strictly increasing,
all values in [0,1]. `0` disables a curve. Example original luma curve:
`1; 0.05; 0.15; 0.35; 0.35; 0.55; 0.04; 0.35; 0.35`.
Sampling uses original FlatCurve at 501 LUT positions, not linear interpolation.
The luma curve replaces the luma slider, following original curve-mode dispatch.
Desktop preview/multizone UI modes are not offered; global auto and manual are.

## Validation and limits

`tools/build_rt_host.py` builds the same scalar kernel as ARM, with OpenMP.
`tools/check_rt_denoise.py` verifies no-op identity, reduction on noisy synthetic
input, independent parameter effects, all median kernels, original curves,
odd dimensions, a tile boundary, finite output, alpha preservation and rejected
input without caller mutation. CI runs these checks before building the APK and
checks that `librtDenoise.so` is packaged. Android uses static OpenMP (two denoise
threads), FFTW single precision, no fast-math and 16 KB library alignment.

These checks do not establish pixel-identical output against the complete
desktop RawTherapee renderer. Camera preparation and later rendering differ.
On-device accuracy, memory peak and speed still need a phone test. CPU readback
and several full-size buffers cost substantially more RAM than ESD3D2. Failure
keeps the original GPU image and reports a toast and log, without silently
substituting a different denoiser. The stage is CPU even when other processing
uses GPU/NPU; an equivalent GPU port has not been claimed.
