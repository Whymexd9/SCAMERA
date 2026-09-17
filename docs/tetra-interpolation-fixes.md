# Tetra interpolation fixes

Targets the existing same-resolution 4x4 remosaic, including HP9 telephoto
4x ISZ. This is the application remosaic, not the Samsung stock library.
The stock metadata probe remains included in this build.

Changes:
- Copying colour differences no longer applies an implicit median. Smooth
  applies one requested median, not two; clamp alone no longer smooths.
- Positive mask weights normalize exactly, removing the phase-dependent
  offset caused by adding 0.001 to the denominator.
- Steered green brackets each missing site with measured neighbours and
  weights by distance. Measured sites remain measured. Empty directions
  have no contribution; image borders use available one-sided samples.
- When block response correction is enabled, coincident green samples in
  final assembly receive the same correction as the reconstruction stages.

Run `python tools/check_remosaic_shaders.py` after installing `numpy` and
`moderngl`. Tests execute translated desktop GLSL on EGL, not Android GLES.
They cover all phase offsets for block sizes 2 and 4, affine ramps, measured
sample preservation, finite border output, constant sparse chroma, exact
impulse copying, and consistent corrected integer assembly.

On float32 Mesa runs, green RMSE for old/new steered reconstruction was:

| Synthetic signal | Before | After |
| --- | ---: | ---: |
| Affine ramp | 0.00123777 | 0.0000000173 |
| Two-axis sinusoidal texture | 0.00983361 | 0.00266421 |
| Vertical step edge | 0.01591208 | 0.00754624 |

These isolated green tests do not establish final JPEG quality. Android
uses half-float intermediate textures and requires a device comparison.
Nearest-neighbour bracketing also averages less noise; noisy captures may
need the Smooth profile or downstream denoising.

For the first phone comparison use block size 4, the existing phase/CFA,
Balanced profile and steered green enabled. Leave other settings identical
between builds. Flat-field correction remains opt-in: its full-frame gain
estimator can confuse scene structure with sensor response. The separate
known sparse-support limitations of Nearest/Sharp are not fixed here;
use Balanced or Smooth for this comparison.

Compare the same scene and exposure with the previous APK at equal viewing
scale. Inspect sky/window grid, thin rails, text, noise, and coloured edges.
Per-frame remosaic before merging and the duplicate-remosaic guard are
unchanged. Stock integration still needs calibration and device validation.
