# Tetra Detail v2 — HP9 telephoto, 4x ISZ

Input remains same-colour **4x4** blocks, full **8x8** CFA periods. Output is
ordinary Bayer at the original dimensions. This updates the existing second
remosaic option; choose **Tetra Detail v2 — по мотивам Vivo**. The separate
response-correction switch remains available. The legacy SCAMERA option,
per-frame remosaic before HDR merging, and double-remosaic guard remain intact.

## Changes grounded in the supplied firmware

The supplied `libremosaiclib_s5khp3.so` is the integration selected for HP9 by
the Vivo adapter (see [investigation](hp9-stock-remosaic.md)). SHA-256:
`7495113303fb01cff07434eb9896253774846a7b2c5ff1e13017654c884c3aa5`.

Further ARM64 disassembly confirms that the calibration helper
`CHC_CFA_XTC_CAL_DATA_v2p0_LC::bilinearInterpolation` at **0xb8a60**, 116 bytes,
loads four unsigned 16-bit map samples, forms complementary horizontal and
vertical weights, adds half the weight-product for rounding, and divides by
that product. `gainMap_tetra` at **0xb8ae0** and
`GainCompensation_Tetra_intnl` at **0xc7080** contain spatially indexed gain
arithmetic. This supports a spatial response field rather than only 64 global
constants. It does not disclose the phone's calibration values.

The direction/inter-channel classes at **0x10e390 / 0x10e7b0** belong to the
uBPC subsystem. Their names must not be represented as proof that their
equations are the main learned remosaic. No OTP data, proprietary network,
or binary entry point is used here. The equations below are independent;
neither Vivo's trained reconstruction nor identical stock output is claimed.

## Reconstruction

* **Spatial response:** normalize the 16 phases within each colour quadrant
  in 32x32 RAW tiles. Take robust medians in an 8x6 spatial partition. Fit six
  smooth polynomial coefficients per phase using a checkerboard subset;
  require at least 16 supported training and 16 validation bins and at least
  35% held-out absolute-error improvement. Unsupported fields use the previous
  robust global correction. Bound local profile changes to +/-6% of the global
  estimate and final gains to 0.8–1.25. Resample to 9x7 gain nodes and interpolate
  **the same phase** bilinearly. Do not interpolate across adjacent CFA phases.
  The map is frozen across the burst. This is scene estimation, not OTP decoding.
* **Local detail confidence:** replace the full-frame standard-deviation veto
  with spatial fields of within-block gradient energy and cross-channel
  boundary-gradient products. Contrast mismatch and anticorrelated colour detail
  reduce transfer locally. Supported neutral detail can use the full measured
  residual; v1 capped it at 85% before applying the global veto.
* **Four directions:** estimate green along horizontal, vertical and both
  diagonals, with squared inverse-gradient weights. Missing directions carry
  zero weight; all measured green samples and matching output-colour samples
  remain corrected measurements. R/B reconstruction still uses the regularized
  guided colour model, with no added output sharpening.

The GPU uses an R16F prepared RAW, small cell fields, an RG16F guide, and R16UI
output. Preparation/fields are released before output allocation. Peak live
reconstruction textures are approximately **80 MB at 4080x3072**, excluding input
and the surrounding pipeline (v1 approximately 79 MB). More shader work is now
performed; phone timing and driver behaviour still need device testing.

## Evidence and limitations

`check_tetra_detail.py` runs the actual shader bodies under Mesa EGL with
half-float intermediate targets. It covers four CFA orders, all 64 phases,
borders, known phase-dependent response, diagonal edges, mixed-colour scenes,
and rejection of opposite-sign colour detail. The original shader regressions
and both Java response-estimator checks pass. CI runs these before APK assembly.

Selected 192x256 synthetic sensor-domain RMSE (10-bit signal, 40-pixel margins):

| Scene | Tetra v1 | Tetra v2 |
| --- | ---: | ---: |
| Neutral detail beside a coloured background | 11.642 | 7.442 |
| Slanted neutral edge | 3.374 | 1.674 |
| Opposite-sign R/B and G texture | 58.103 | 15.172 |
| Fine neutral sinusoidal texture | 21.011 | 19.305 |
| Smooth random neutral texture | 10.213 | 5.951 |

These are synthetic cases, not a guarantee of improvement on every texture.
The sensor cannot fully disambiguate arbitrary colour detail above its chroma
sampling bandwidth. In particular, purely chromatic texture remains limited.

For `IMG_260917_113150_656_001.dng`, the production Java estimator accepts all
four spatial fields. Independently remeasuring the prepared RAW in twelve
regions gives the following peak-to-peak normalized phase variation:

| Quadrant | Global correction: median / worst region | Spatial: median / worst |
| --- | ---: | ---: |
| B | 2.562% / 4.197% | 0.544% / 0.959% |
| G1 | 2.639% / 4.043% | 0.534% / 0.986% |
| G2 | 2.466% / 4.151% | 0.571% / 0.963% |
| R | 2.773% / 4.319% | 0.539% / 0.973% |

Reproduce with `tools/check_tetra_raw.py DNG --java-classes CLASS_DIRECTORY`,
after compiling `TetraResponseProfile` and `tools/TetraResponseMapCheck.java`.
The field is validated on held-out spatial bins, but these region measurements
reuse the supplied frame; they are not an independent sensor calibration.
Three real RAW crops were also rendered with identical simple colour processing.
The DNG is mostly water texture and supplies no ground-truth detail target.
The residual is reduced, not zero. Repeated scene patterns, dark areas and
unseen illuminants can still affect a scene-derived calibration. Final JPEGs,
runtime and visible grid on the phone require user comparison.
