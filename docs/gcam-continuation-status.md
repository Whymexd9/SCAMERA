# GCam continuation, 2026-09-19

Base: codex/approved-ui-concepts, 18db9f5d368d6a42419e5befabcfa91ba13d23eb.

## Scope

User removes original items 7–10 from the plan, plus exclusions 13, 15, 16, 17, 18, 19. Existing Saliency is retained; no new work on items 7–10.
The user's additional term "TFlight" is provisionally understood as TFLite:
no new neural model/runtime integration in this branch. Existing baseline
features are not removed. SoftPQE remains outside this task.

The separately prepared Hotshot changes are NOT ancestors of this branch,
and this branch has no Hotshot asset, selector, preprocessing, or inference.

## This change: gyro motion foundation (item 6, partial)

- Correct ZSL first-row exposure window: timestamp through timestamp+exposure,
  rather than the interval before the frame timestamp.
- Record interval starts and ends, synchronize ring access and read chronological
  order after wrap. Prorate movements at partial interval boundaries.
- Missing history, discontinuities, invalid samples, unknown camera clock source,
  invalid exposure or arithmetic overflow produce no usable samples, not a claim
  that the frame is stable. Camera/gyro comparison requires REALTIME timestamps.
- Fill per-sample timestamps and signed integrated motion; preserve X sign
  convention used by the existing post-shutter path.
- Deep-copy all three motion arrays when snapshotting bursts.
- Keep the squared angular-path score through CompleteSequence, as required by
  ESD4D's squared exposure-ratio comparison. Do not truncate long exposures to
  an average sample count or fabricate missing motion from adjacent frames.
- Skip gyro-based frame ranking/removal if any frame has unknown gyro data;
  unknown frames are excluded from ESD4D's motion reference statistic.

This is SCAMERA integration/correction, not Google's complete blur kernel,
OIS compensation, rolling-shutter model, or a complete Sabre MFSR port.
The window covers the first row's exposure, not full rolling-shutter readout.

## Verification

Standalone Java check passes: constant angular rate, fractional boundaries,
wrapped history, duration squared scaling, return motion, independent snapshots,
missing interval coverage, invalid clock, zero exposure, overflow and NaN.
The same check is included in the Android build workflow. Android compilation,
end-to-end capture timing and Vivo motion/OIS testing are separate gates.

## Remaining work, not claimed complete

Sabre reconstruction and noise-aware rejection must be wired to the current
MFSR path, not just copied into an unused shader. Main has separate Sabre
rejection commits; they are not a complete reconstruction algorithm. They also
need review against the newer branch's CFA phase and bracket handling.

Still requires implementation/audit: reference selection, bracketing policy,
sharpness/focus measures, non-neural parts of BurstCurator, tracking/documents,
Cyclops, AE distribution and shutter/gain policy, anti-flicker, three-frequency
sharpening, Shadow Level Matching, geometric tone blending, rolloff/digital gain,
local tone/dehaze/clarity, flare, cross-camera colour, allowed relighting,
noise conversion, preview/photo matching and hot pixels.

Baseline already contains RAW quality selection, Saliency detail protection,
false-colour median/headroom fixes and UI features (favourites, precision rulers,
DCP import, lens UI). Their presence is not proof of complete donor equivalence.
Unrecovered donor contracts must remain explicitly incomplete; do not substitute
heuristics and call them the original Google implementation.

## Follow-up: exposure factorization (items 22 and 23)

TetModel now clips the policy waypoints to sensor limits BEFORE logarithmic
interpolation. Anti-flicker rounds to the nearest period and compensates gain
in either direction, backs down one period for shutter/base-gain constraints,
and rejects a candidate that exceeds available gain. It preserves the achieved
clipped policy product rather than trying to recover an unreachable target.
The existing short/long IsoExpoSelector call sites consume the result.

All 192 original ARM64 outputs from ArkCam native-math-checks.json are checked
by tools/java/TetModelCheck.java and tools/fixtures/gcam-tet.csv. Maximum absolute
error is 2.9443e-6. Additional checks cover upward snapping, cap fallback, gain
limits, minimum shutter, non-finite target handling and sensor output bounds.
The check runs in the APK workflow. Default knots remain sensor-14 policy;
this does not establish device-specific AE calibration or learned AE metering.

## Agreed-list audit (all original numbers retained)

“Present” means SCAMERA has an implementation, not equivalence to every Google
branch. “Partial” and “open” MUST NOT be reported as completed donor ports.

| Items | Status | Evidence / remaining work |
|---|---|---|
| 1 Sabre MFSR | Open | Existing native remosaic is not a complete Sabre port; reconstruction, alignment and CFA integration remain. |
| 2 Noise-aware rejection | Open | Separate main-branch shader patches need integration review; HDR overwrite and guide-noise scaling prevent blindly copying them. |
| 3 Bracketing | Partial | IsoExpoSelector has short/long capture and ratio ceiling; independent learned short/long AE is not reconstructed. |
| 4 Reference selection | Partial | HexQuadZslSelector implements RAW-quality chronological selection; not original BurstCurator policy. |
| 5 Sharpness/focus | Partial | RawFrameQuality is CFA-aware; donor byte-luma metric is not interchangeable with RAW; focus-specific branches remain. |
| 6 Gyro blur | Partial | GyroExposureWindow fixes actual capture windows; OIS and full row-dependent blur kernels remain. |
| 7 BurstCurator | Removed from plan | User request; no further implementation. |
| 8 Quality/composition | Excluded | User exclusion. |
| 9 Saliency | Removed from plan; existing code retained | No further implementation; removal from plan does not uninstall the existing feature. |
| 10 Hotshot | Excluded | Explicit user exclusion; no asset or inference integration. |
| 11 Point2Mask | Blocked/excluded new model | Fourth input channel prompt contract unrecovered; new TFLite integration excluded provisionally. |
| 12 Tracking | Open/excluded new model | Paired model state and runtime wiring remain; no new TFLite integration. |
| 13 Faces | Excluded | User exclusion. |
| 14 Documents | Open/excluded new model | Detector is a new TFLite model; no new integration under current scope. |
| 15–19 Depth, FusionZoom, Face Deblur, DeepRestore, Focus Stack | Excluded | Original explicit exclusions. |
| 20 Cyclops | Open | Selected mask/blend kernels recovered; upstream flow, calibration, masks and complete trigger are missing. |
| 21 AE luminance distribution | Open | ProcessQcStats/learned metering input contract is incomplete; TET alone does not implement it. |
| 22 Shutter/gain factorization | Implemented arithmetic | TetModel, 192 original reference cases; sensor policy still requires hardware validation. |
| 23 Anti-flicker choice | Implemented arithmetic | TetModel nearest-period branch; only existing TET-enabled bracket call sites. |
| 24 Three-frequency sharpening | Open | Five-knot curve recovered, but full decomposition/application not recovered; RTSharpening is a different implementation. |
| 25 False colour | Present/adapted | FalseColorSuppression: Oklab median and chroma gating, not every donor branch. |
| 26 Highlights/HDR | Partial | False-colour HDR headroom fixes and existing exposure/tone paths; full Google finish pipeline not reconstructed. |
| 27 Shadow Level Matching | Open | Verified S16 core lacks full upstream levels and all nonlinear/F16 branches; not inserted into unrelated RGB space. |
| 28 Logarithmic tone blend | Open | Positive-map unit-gain formula verified; suitable upstream maps and production integration remain. |
| 29 Rolloff/digital gain | Open | Scalar split verified; compatible tone-stage integration/calibration remain. |
| 30 Local tone/dehaze/clarity | Partial | Existing LocalLaplacian/CaptureOne processing; no claim of donor-equivalent finish stages. |
| 31 Stray light/flare | Open | No verified complete donor correction integrated. |
| 32 Cross-camera colour | Open | DCP per-camera profiles do not establish cross-camera calibration. |
| 33 Portrait relighting | Open/excluded new model | Global correction verified in isolation; upstream relighting model/subject mask remain. |
| 34 Noise rescale | Partial | NoiseModeler exists; donor three-array/downsampling contract is different and not integrated. |
| 35 Preview/photo match | Open | Exact scale-dependent noise/sharpening equivalence remains unverified. |
| 36 Hot pixels | Present/adapted | Existing HotPixelFilter and ESD4D detection; no claim of full donor equivalence. |
| 37 Viewfinder favourites | Present | FavoriteSettingsButton / FavoriteSettings. |
| 38 Ruler step | Present | PrecisionEditor / PrecisionRuler selectable step. |
| 39 DCP import | Present | DcpSettingsFragment / DcpProfiles. |
| 40 Lens animation/haptics | Present | AuxButtonsLayout lens UI including haptic feedback. |

The donor audit itself explicitly says the complete GCam source, runtime and
all processing kernels have NOT been recovered. Completing the open rows needs
additional reverse engineering and camera integration; adding isolated unused
formulas or relabelling existing algorithms would not complete the agreed list.

## Current follow-up: requested items 1–6

This supersedes the open/partial integration status for items 1–6 in the historical
30219 audit above. The implementation is a SCAMERA adaptation of recovered
Sabre mathematics, not the entire proprietary GCam runtime or its calibration.

1. **RAW reconstruction:** selectable `pref_mfsr_engine_key=sabre` alongside the
   existing native engine. Routes capture to ESD4D, forces pyramid alignment,
   uses fractional-phase anisotropic 3x3 same-CFA gathers, recovered square-root
   eigenvalue coherence and `exp2(-0.5*q)+0.00005` kernel without determinant
   normalization. A dedicated combine pass accumulates accepted per-pixel mass
   and normalizes it; this is not an extra sharpening residual. Output stays at
   native RAW dimensions. Quad/Tetra go through the existing per-frame SCAMERA
   remosaic first; this is NOT direct proprietary Quad/Tetra Sabre reconstruction.
2. **Noise-dependent rejection:** recovered excess-squared-difference / max of
   texture and noise variance, transformed current-frame noise by exposure gain
   squared, and exponential confidence. Packed unfiltered guide samples have
   variance scale 1; donor bicubic RGB constants are deliberately not reused.
   HAL per-frame noise profiles take priority; missing profiles use an explicitly
   logged ISO-scaled approximation. Tiling, clipping and floor gates remain.
3. **Bracket photometry:** measured exposure products define the common linear
   reference domain; source clipping is evaluated BEFORE exposure scaling.
   A rejected donor falls back to reference, never an unaligned current pixel.
   Valid shorter donors can replace clipped reference regions. Sabre normalizes
   accepted mass rather than applying a fixed 1/N weight to rejected frames.
4. **Reference:** one shared reference is selected before pyramid/merge. Regular
   frames preferred, latest settled focus plane used when available, sharpness
   ranked with rotational pixel-blur penalty only when gyro is known for every
   regular candidate. Unknown gyro cannot masquerade as a stable frame.
5. **Burst selection:** bounded CFA-aware quality samples all four colour phases
   and uses same-colour neighbours at periods 2/4/8. Known focus mismatch, moving
   lens and substantially weaker sharpness can reject regular donors. Bracket
   roles are preserved; optional filters roll back if fewer than three remain.
6. **Gyro:** no cyclic reuse of another frame's samples when correspondence is
   missing. Exposure-window rotation is projected into RAW pixel extent using
   focal length and physical sensor dimensions. Path extrema preserve return
   motion, unlike net angle. OIS correction and rolling-shutter per-row kernels
   are not estimated; blurPixels is an uncompensated ranking cue, not actual
   measured optical blur. Existing timestamp coverage/clock checks remain.

Tests: production mergeAlign and sabreCombine execute on Mesa GPU compute;
5 bracket gains, colour-channel isolation, non-multiple dispatch dimensions,
clipped donor/zero confidence fallback, accepted-mass normalization, motion/noise
separation and fractional reconstruction. Java checks cover focus, blur ranking,
missing gyro, optional-filter rollback and return motion. Existing RAW quality
checks cover Bayer/Quad/Tetra. CI compiles Android and runs the existing gates.

Native-engine CAL/FPN and R/B scale controls are disabled for Sabre. Native remains
the default to preserve previous user configurations. Real Vivo capture quality,
OIS behavior and sensor-specific tuning require on-device testing.

The integration review also fixed ESD4D texture ping-pong: immutable basePrimary
and baseAlter handles prevent the second and later passes from reading and
writing the same GPU image. The fix applies to both merge engines.

## Current scope update: finish stages and noise (after 30221)

User authorizes items 20–31 and 34; removes all other remaining items. Items
22/23/25 were already present and are retained. Items 7–10 remain removed from
future work; existing Saliency is retained. Cross-camera colour (32), preview/
photo matching (35) and preview hot pixels (36) are no longer planned.

New optional menu: “GCam — маски и тональная обработка”. All new image effects
and scene AE are disabled by default. This is an integration of recovered
arithmetic plus explicitly documented SCAMERA adaptations, NOT full proprietary
Cyclops/FinishRaw/learned AE equivalence.

| Item | Implemented path | Exact boundary |
|---|---|---|
| 20 | Sabre confidence → threshold → three 3x3 box passes → threshold → separable sigma=1 Gaussian → byte mask blend | Recovers selected Cyclops mask arithmetic, including even-kernel offset and truncation. Uses Sabre confidence as the initial reliability signal, not original bidirectional flow/ML. Subject and smooth masks are constant 255, gain=1; no new segmentation model. |
| 21 | Sparse RAW16 distribution meter → bounded Camera2 AE compensation | Own p50/p99 policy, one compensation step per second after AE convergence, ±2 EV from user's compensation. Requires matched preview RAW metadata. Manual shutter/ISO, flash AE and AE Lock are excluded. Turning off restores user's compensation. Not Google's learned Qualcomm metering. |
| 24 | Three-scale luminance decomposition with independent gains and recovered five-knot curves | Curve arithmetic recovered; Gaussian pyramid, noise floor and default knots are SCAMERA tuning. Neutral gains bypass rendering when other controls are neutral. |
| 26 | Optional luminance shoulder before main tone curve | Soft compression retains colour ratios and floating-point values above 1; cannot reconstruct information clipped in every RAW. |
| 27 | Match measured shadow percentile toward .03, capped by user EV, with shadow-only blend | Own upstream target/weight integration, not full S16/F16 ApplySlm or Google's missing level estimator. |
| 28 | Positive-domain geometric blend and selectable linear/geometric interpolation | Recovered geometric branch; exact black stays black. Negative linear RGB is floored before processing. |
| 29 | Recovered piecewise rolloff/digital gain split | Product preserved; rolloff capped at 1.5. Applying the rolloff to RGB is a separate SCAMERA integration curve. |
| 30 | Coarse-scale local contrast, clarity and atmospheric-scattering dehaze | Own approximations; original GCam kernels were not recovered. Controls are separate and default to zero. |
| 31 | User-controlled subtraction of a low-percentile veiling pedestal | Own simple model of broad veiling light. Does not remove local lens ghosts, reconstruct obscured content or provide optical calibration. |
| 34 | Variance scale 1/(effective temporal samples × independent spatial samples) | Sabre tracks sum(w) and sum(w²), then uses a conservative minimum-bin effective count for the scalar downstream model. Native path retains its count; ordinary confidence merge without a weight map conservatively uses 1. Software 2x2 binning contributes 4 under the independent-sample assumption. No unjustified 0.9 variance divisor at N=1. |

The finish node operates on linear camera RGB after ABLC, before the chosen tone
pipeline. The median/peak histogram targets and all aesthetic tuning are not
claimed to be Google's original settings. Dehaze/flare can darken scene shadows;
leave them at zero unless intentionally adjusting that image characteristic.

Validation: executable production GPU shaders against independent mask/blend
fixtures, all three frequency controls, neutral identity, black, HDR and colour
ratios. GLSL ES 3.10 compile checks; Java gain split, weighted effective counts,
noise identity, RAW histograms and AE bounds. Existing Sabre tests retained.
On-device AE stability, optical behaviour and final photo quality require Vivo
capture testing; no end-to-end donor equivalence is claimed.
