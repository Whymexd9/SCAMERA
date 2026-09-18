# SCAMERA

Android RAW burst camera, derived from [PhotonCamera](https://github.com/eszdman/PhotonCamera)
and rebuilt around the HDR+ capture protocol described in "Burst photography for
high dynamic range and low-light imaging on mobile cameras".

[![GPLv3 license](https://img.shields.io/badge/License-GPLv3-blue.svg)](./LICENSE)

Everything below is what this fork does differently from upstream. The parts
inherited unchanged - the Camera2 plumbing, the gallery, the OpenGL framework -
are not listed.

## Capture and merge

The burst is a bracket rather than a stack of identical frames: a chosen number
of normal frames at constant exposure, up to eight short frames and up to eight
long ones, each offset by an adjustable 1-8 EV and clamped to what the sensor
advertises. The shortest sharp frame becomes the radiometric highlight
reference, and the short exposure is scaled by its own exposure product rather
than averaged as if it matched - that is what keeps highlights while limiting
ghosting.

Alignment and merging were reworked around the Wronski kernel-regression
formulation: anisotropic Gaussian RBF kernels driven by the local structure
tensor, replacing a Catmull-Rom resample whose negative lobes undershot into the
shadows. Robustness is a Wiener-style shrinkage rather than a hard threshold,
the shared per-quad weight follows Night Sight's minimum-across-channels rule,
and two guards fall back towards the unaligned frame when the alignment field is
locally inconsistent: disagreement between the four blended tiles, and flow
variation over a 3x3 tile neighbourhood.

Frames are ranked by gyro shakiness and by measured sharpness; the sharpest
regular frame becomes the reference, and frames well above the burst's average
shakiness are dropped before merging.

Tunable from the settings screen: merge robustness, clip level, tiling
tolerance, floor sigmas, maximum exposure ratio, HDR ratio ceiling, long-frame
shutter cap, the MFSR kernel shape (detail, denoise, stretch, shrink, gradient
threshold, tensor stride) and highlight recovery and protection.

## Quad Bayer and tetra sensors

A sensor that delivers 2x2 or 4x4 colour blocks is rearranged into ordinary
Bayer before anything else reads it. The reconstruction interpolates green over
its own mask, forms and interpolates the B-G and R-G differences, and assembles
on a plain 2x2 grid; a numpy model of the shader chain reproduces a reference
implementation's output to 0.4 of a 10-bit code.

The rearrangement happens **inside the merge, per frame, as each is loaded**.
Doing it afterwards cannot work: the merge packs one Bayer quad per texel and
relies on a texel holding one sample of each colour, which is false for a
mosaic, so every displacement finer than a colour block mixes neighbouring
blocks. Measured on a merged frame, 10.6% of blocks came out magenta against
0.02% on a single frame from the same camera.

Options: block size, interpolation profile (nearest, sharp, balanced, smooth),
edge-steered green, bounded colour differences, per-site block flat-field
correction, mosaic phase, and a debug switch that writes the node's own output
as a DNG for comparison against an external reference.

## Demosaic, detail and noise

- Demosaic choice: AMaZE, gradient, compute-shader and a quad-specific path.
- False colour suppression, purple and green defringing, chromatic aberration
  correction.
- ESD3D denoise with SNR-driven strength: five signal-to-noise bands, each with
  its own luma and chroma settings, chosen from the merged frame's own estimate.
- Neural denoise and alignment through ncnn: FlowNet for optical flow and
  KernelNet for anisotropic kernel parameters, on Vulkan where the ABI has it.
- RAISR upscaling, CPU and GPU implementations, with halo and aliasing controls.
- A sensor noise model that can be profiled, exported, imported and curved
  against ISO, rather than assumed.

## Tone and colour

Several complete tone pipelines, selectable: OpenDRT, an ACES chain with its own
tone curve, gamut and hue-protection controls, a darktable-style module set
(filmic contrast, colour balance, diffuse sharpen, profiled denoise, haze,
vignette, texture), and a Capture One-style rendering. Local Laplacian contrast,
adaptive black level, an auto exposure curve, and an Android Ultra HDR gain map
on output.

## Sharpening

Unsharp mask with radius, amount, contrast and a four-corner threshold map;
edge-only masking; halo control; and Richardson-Lucy deconvolution with kernel,
radius, amount, iterations, damping and its own halo margins.

## Tooling

- A unified debug log written to `Download/SCAMERA/SCAMERA-debug.log`.
- `tools/` holds headless checks that run the actual shaders against numpy
  references, plus readers for the model containers this work needed to
  understand.
- `docs/` records what was established about the hardware and what was not -
  including which questions remain open. Claims there are separated from
  evidence on purpose.
- CI builds a debug APK on every push and verifies that the settings the build
  claims to ship are present inside the APK.

## Building

`./gradlew :app:assembleDebug`. The native side needs a batch-capable ncnn
fork (`Mat::n`, `Mat::nstep`, `Layer::support_batch`) that stock Tencent
releases do not provide; the CI workflow fetches the exact build upstream
PhotonCamera vendors and caches it. Without it, CMake falls back to a stub JNI
library and the neural paths are silently disabled.

## License

    SCAMERA, a modified version of PhotonCamera
    Copyright (C) 2020-2022  Eszdman
    Copyright (C) 2025-2026  SCAMERA contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.

Upstream project: https://github.com/eszdman/PhotonCamera
