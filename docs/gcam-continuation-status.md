# GCam continuation, 2026-09-19

Base: codex/approved-ui-concepts, 18db9f5d368d6a42419e5befabcfa91ba13d23eb.

## Scope

User excludes Hotshot explicitly, plus original items 8, 13, 15, 16, 17, 18, 19.
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
| 7 BurstCurator | Open | Recovered scoring fragments do not provide full options/features/selection protocol. |
| 8 Quality/composition | Excluded | User exclusion. |
| 9 Saliency | Present | SaliencyProtection and bundled existing model; photo detail-protection path. |
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
