# Tetra Detail: independent stock-inspired remosaic

This is a working second GPU backend, not a loader, a renamed SCAMERA
profile, or a reproduction of Samsung/Vivo's proprietary neural network.
Target: HP9 telephoto 4x ISZ RAW, same-colour 4x4 blocks, complete 8x8 CFA
periods, including 4080x3072. Output is ordinary Bayer at the SAME resolution.
No extra crop, resizing or full-sensor 200MP reconstruction is performed.

## What the firmware evidence supports

The supplied PD2454 Vivo adapter explicitly recognizes `s5khp9` in
`SamsungHP3RemosaicIntegration::create` (0xbe5c, sensor comparison near
0xbe84). It loads `libremosaic_wrapper.so`, whose camera-0 configuration
loads `libremosaiclib_s5khp3.so`. The HP3 filename is not evidence of a
wrong-sensor library. See hp9-stock-remosaic.md for adapter calibration sizes.

In that HP3 binary:

| Evidence | Interpretation and limit |
| --- | --- |
| `Remosaic::Init` at 0x122ac0 calls `CDL_HPX_QMSC_V3` constructor | Active reconstruction object, verified direct call. |
| `Remosaic::Run` at 0x122b70 calls `HC_DL_QMSC_V3` or `MC_DL_QMSC_V3` | Multiple runtime paths, not a single fixed interpolation kernel. Mode meanings are not fully established. |
| HC/MC wrappers at 0x11c930 / 0x11ee50 call allocation and corresponding OPT routines | Verified code linkage; OPT routines call P2 and join a worker. |
| `CHC_CFA_XTC_LOCAL_v2p0_LC::GainCompensation_Tetra_intnl`, `XTCgainMapGen`, `CHC_CFA_XTC_CAL_DATA_v2p0_LC::gainMap_tetra` | Contains response/crosstalk calibration machinery. Exact active coefficients are unavailable. |
| `BPC_OTP_UNPACKER_V2P2`, `CSW_UBPC_TETRA_*`, direction and inter-channel estimation classes | Contains defect-pixel and cross-channel estimation machinery. Symbol presence alone does not establish execution order for this capture mode. |
| `QNN_Interface`, `DL_Interface::QNN_Inference`, embedded HP3 E2E/T2Q model symbols | Contains learned reconstruction paths. The full model architecture, weights and selected 4x ISZ path have not been reproduced. |

The generic Vivo tuning JSON includes direction, chroma-noise and sharpness
parameters, but the HP9 mapping selects the Samsung integration. Those
numbers cannot simply be transplanted into this implementation. Similarly,
`qpd4x4` strings elsewhere do not by themselves establish same-colour 4x4.

## Independent design

The firmware motivates treating response correction, cross-channel detail,
and reconstruction separately. The equations and thresholds below are our
own engineering choices, not extracted Vivo equations.

1. **Burst-stable response estimate.** Reuse the GPU 32x32 tile reduction,
   normalize each tile's 16-site profile per colour quadrant, then take
   medians. Reject dark/clipped/strongly structured tiles, require at least
   64 tiles and eight in each spatial quadrant, and reject a colour's whole
   profile if any site's spatial medians differ by more than 3%. Limit gains
   to 0.8–1.25. Freeze the result for the burst. This is a scene estimate,
   not EEPROM calibration or a full crosstalk inversion. It is separately
   switchable; periodic scene textures can still fool it.
2. **Contrast consistency.** First-frame channel moments, after global
   alignment, limit detail transfer when a colour's contrast differs from
   green. A standard-deviation ratio outside 1/1.5–1.5 disables that colour's
   transfer. Flat/dark unsupported statistics also disable transfer. This
   avoids assuming that rapid changes in colour are achromatic detail.
3. **Coarse colour field.** Average corrected 4x4 blocks; smooth colour
   estimates across seven cells to reduce phase aliasing. This field is a
   colour guide, not the final image resolution.
4. **Dense green guide.** Bracket measured greens along axes and steer by
   gradient. At R/B sites, blend in measured detail through a local colour
   ratio. Confidence decreases for dark colours, chromatic transitions,
   or inconsistent channel contrast. Known green samples remain corrected
   measurements. There is no post-sharpening in this backend.
5. **Guided reconstruction.** At missing R/B sites, fit a spatially and
   edge-weighted local model `C = a G + b` over measured 9x9 support, with
   slope regularized toward 1 and limited to 0–4. Preserve measured samples
   when source and target colours coincide, undo alignment, and quantize to
   R16UI Bayer. No incomplete masks or duplicate post remosaic are introduced.

Four reconstruction passes (two at cell resolution, two at RAW resolution)
use two small RGBA16F grids, one full-size RG16F guide and the R16UI output.
At 4080x3072 these textures total about 79 MB, excluding input, statistics
and the rest of the pipeline. This is an allocation estimate, not a phone
memory/performance measurement. Existing per-frame remosaic before ESD4D
merging and the already-remosaicked flag are preserved.

## Validation

`python tools/check_tetra_detail.py` executes the actual shader bodies under
Mesa EGL after desktop GLSL translation, with half-float intermediate targets.
It checks four CFA orders, all 64 Tetra phases on a ramp, borders and constant
colour fields. The existing shader regressions also pass.

Synthetic same-resolution Bayer RAW RMSE (10-bit signal, excluding boundaries):

| Scene | Current SCAMERA, Balanced + steered + clamp | Tetra Detail |
| --- | ---: | ---: |
| Gray fine texture | 36.476 | 20.811 |
| Gray diagonal step | 24.073 | 3.576 |
| Colour boundary | 43.407 | 4.784 |
| Colour-varying texture | 13.420 | 6.532 |

Both use the same gains estimated from the simulated mosaic. These are small
synthetic tests, not stock-camera comparisons or claims about all scenes.

On the supplied single-frame DNG, a held-out tile-row check of normalized
phase profiles reduced peak-to-peak variation from 8.79/15.95/15.11/16.18%
to 0.26/0.13/0.26/0.23% for B/G1/G2/R. This measures repeat-pattern consistency,
not overall reconstruction accuracy. A 512x512 real RAW crop was processed
by both shader paths and inspected with identical simple preview rendering;
there is no corresponding ground-truth RGB image for PSNR or detail claims.

`tools/TetraResponseProfileCheck.java` tests the actual Java estimator on
flat data, known response, spatial disagreement, dark data and insufficient
support. The APK workflow compiles and runs this standalone gate before
building the app. Device GLES, speed, burst alignment/noise behaviour and
final JPEG output still require phone testing. Strongly coloured/repetitive
textures and noise remain important comparison cases.

## Use

Enable remosaic, retain the established CFA and phase, and choose block 4.
In **Algorithm** select **Tetra Detail — inspired by Vivo**. SCAMERA remains
available as the first option. Its interpolation/steering/clamp/flat-field
controls are disabled while Tetra Detail is selected because they do not
configure this backend. The separate Tetra grid-suppression switch defaults
on; compare off if repeated scene detail or colour changes unexpectedly.

No original Vivo libraries, external model download, OTP export or stock
loader check is required to use Tetra Detail.
