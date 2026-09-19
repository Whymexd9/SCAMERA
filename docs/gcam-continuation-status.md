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
