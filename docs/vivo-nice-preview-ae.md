# Preview AE to NICE capture plan

`vivo-nice-preview-ae.h` connects the recovered preview AE array binding to
`buildNiceHdrCapture`. This is a callable native component, not an activated
Camera2 path. It does not implement scene detection or the AE solver.

Donor: `libvivo.vas.adapter.vcf.so`, SHA256
`f5fdf379e0316fee77a0ff7760ef247881e4dfade98f3a7bc8b9b8f93c12e901`.

## Recovered route

`previewDetectMetaOut` reads `vivo.parameter.VivoAlgoAECFrameControl` as 192
bytes (`ab928..ab944`), and the paired-short field as 196 bytes
(`aba1c..aba38`). The routine has multiple scene branches and can publish
revised arrays. `ad30c..ad43c` routes the prepared arrays according to the
scene-result flags. Bits 0x10000 or 0x20000 select the NICE destination;
neither bit selects a different destination. The new binding rejects that
other branch rather than treating all AE arrays as NICE.

| Input | CameraAlgoParams destination in the NICE branch | PreviewToQueryParams destination |
| --- | --- | --- |
| primary EV[16] | 0x38b0c | 0x2d10 |
| primary gain[16] | 0x38b4c | 0x2d50 |
| primary shutter[16] | 0x38b8c | 0x2d90 |
| short EV[16] | 0x38bcc | 0x2dd0 |
| short gain[16] | 0x38c0c | 0x2e10 |
| short shutter[16] | 0x38c4c | 0x2e50 |
| EV content, two int32 | 0x38c8c | adapter 0x3568, separate from PreviewToQueryParams |

The second copy is visible at `a8f78..a9098` in
`convertAlgoMetadataToPreviewMetadata`. Its PreviewToQueryParams base is
adapter+0x6b8. These copies introduce **no unit conversion**. They must not be
replaced with Camera2 ISO estimates or interpreted as the per-RAW 35-float
`Vivo3rdAlgoAECFrameControl` used by the processing metadata path.

`ad3d4..ad3e0` separately copies the short-AEC integer tail to a caller field.
The complete route from that field to query+0x3e44 remains unresolved.
Consequently `bindNicePreviewAe` returns this integer separately and preserves
the caller's query context. The combined builder requires both values to
agree; disagreement is an explicit boundary error, not a guessed conversion.
This extra validation is adapter policy, not claimed as original Vivo behavior.

Counts, scene ID, image-echo policy, alternate arrays/mode, raw-HDR context and
capture DRC remain the responsibility of their own producers. The binding
preserves them. Alternate-mode payload zeroing and internal initialized gain/
shutter preservation remain in the existing builder. The scene-result flags
must also be supplied by their real producer; the header does not infer them
from tag presence, ISO or sensor mode. No sensor mode is changed.

## Query lifecycle

`VASAdapterVCF::queryCaptureInfoFromVAS` constructs a ProcessRequest whose first
word is 0x10 (`55e90..55efc`), fills query input metadata, invokes the VAS
interface (`55f00..55f14`), and reads back updated query context
(`55f18..55f30`). Thus copying metadata from a processed RAW is not a substitute
for the stock query lifecycle. `getQueryParams` separately publishes the
capture-control structure with a 0xb34 count (`ef800..ef814`); the previously
ported reader intentionally covers only its known fields through 0xb30.
Neither count proves ordinary Camera2 access to the internal VCF metadata.

## Verification and remaining activation work

```
python tools/check_vivo_nice_preview_ae.py /path/to/libvivo.vas.adapter.vcf.so
```

256 original ARM64 executions of `ad30c..ad43c` match the C++ array/EV/type
binding byte for byte. Tests cover both HDR bits, mixed flags, all six arrays,
opaque inactive NaN payloads, the integer short tail, preserved surrounding
memory, and the connected normal/alternate request-payload builder. Invalid
branches, counts and conflicting type context leave caller outputs unchanged.
No instructions in the tested routing block are replaced.

This test does not execute the upstream AE solver, the whole preview manager,
the complete query lifecycle or an Android camera session. CaptureController
still does not call this chain; dynamic model dispatch and the full TCE image
call remain unconnected. No APK or photographic-quality claim accompanies
this component.
# Stock exposure-table lookup (Camera2 port work)

`vivo-aec-table.h` ports the complete arithmetic of
`CExpTable::VivoExpTableEntryLiteLookUp` at 0x1774d4 in the supplied
com.vivo.stats.aec.so (SHA256
b2e3e12469a05cf62c2a5892b2b619228fb29a3ccb8fa9fe5f934df0dd5842e9).
The native table contains a divisor at +0, count at +4 and row pointer at +0x10.
Each 24-byte row contains gain at +0, uint64 shutter at +8 and the choice of
shutter-first versus gain-first interpolation at +0x10. The portable API takes
owned values, not pointers into HAL memory.

The lookup divides the requested exposure product by the table divisor, clamps
it to the endpoint products, chooses the interval with the donor's strict
comparison, and spends the remaining exposure on shutter or gain according to
that row. It preserves float32 operation order. The shutter increment is
truncated to an integer **before** being added to the integer starting shutter;
rounding the full float shutter does not reproduce the donor.

The adapter rejects nonfinite/nonpositive input, empty/single-row or oversized
tables, non-increasing row products and invalid/overflowing shutter increments.
These are defensive domain restrictions; the donor does not validate every
malformed table. Equal neighboring products are not accepted by this adapter.

`check_vivo_aec_table.py` executes the original complete ARM64 function with
only logging replaced. All 3264 valid cases match all 24 output bytes, including
unchanged fields. Cases include both interpolation orders, table endpoints,
clamping, divisors, non-float-exact integer shutters and neighboring float32
values. Source rows and output guard bytes remain unchanged. Invalid input is
rejected without publishing partial output.

This fills a missing arithmetic dependency of the stock long/normal exposure
arbitration. It is not yet called by CaptureController: selecting the correct
live tuning table, finishing normal-exposure/blur/banding adjustment, and
establishing the mapping from native gain to Camera2 sensitivity are still
required. The existing short-exposure calculation and this lookup must not be
fed Camera2 ISO in place of native gain or an invented tuning table. No APK or
stock-equivalent camera capture is claimed by this host validation.

## v21 phone replay: rLtSJNuM

The supplied archive records three table snapshots, 161 complete solver calls,
55 successful table lookups and 55 successful normal-exposure adjustments.
There are seven NICE inputs/returns, six scoped VAS conversions and no recorded
observer/tuning errors. The collection ends normally with
`observation_window_complete`; the absence of the extension's 60-second terminal
event does not imply failure when the outer capture window ends first.

Table snapshots 2 and 3 contain equal divisor, banding tolerance and row values;
their native pointer-containing headers differ. Do not report three distinct
calibration curves or hardcode pointer identity as sensor/table selection.
The observed table floor is gain 1.43, shutter 41245 in the donor's units.
Divisor is 1 and tolerance is float32 0.97. The observed nonzero banding period
is float32 8333333. These values describe this capture, not universal defaults.

`vivo-aec-adjust.h` adds the branch of `VivoNormalEVExpAdjust` at 0x17a5fc in
which blur arbitration is inactive. It preserves gain correction, the table
floor, fused distance calculations, bounded banding quantization, mode-1
minimum-period behavior and the final correction factor. All observed calls
have blur flags 01 00 and a nonzero blur-disable field. The active blur branch
is explicitly rejected, never silently treated as inactive. The sensor minimum
gain fallback is not reached with this trace's table floor; it is not inferred
from Camera2 ISO.

`python tools/check_vivo_aec_trace.py /path/to/scamera-zsl-trace.rLtSJNuM.tar.gz`
replays the actual copied inputs through both C++ ports. All 55 lookups match
all 24 output bytes; all 55 adjustments match the 16-byte exposure record plus
4-byte correction. Every sample requires its completed same-PID/same-thread
solver identity and a valid referenced table snapshot. Duplicate IDs, failed
calls, malformed extents and missing context fail. Active blur rejection is
also checked without output mutation.

This verifies the arithmetic branches exercised on this phone. It does not
supply the unported motion branch, complete upstream scene/EV arbitration,
live tuning selection or the native gain-to-Camera2 sensitivity contract.
CaptureController still uses the manual schedule. No APK was built, and no
further phone run is required merely to repeat these arithmetic checks.

## Complete normal adjustment and long-exposure arithmetic

`normalExposureAdjustment` now includes the active blur branch of the pinned
`VivoNormalEVExpAdjust` (0x17a5fc). It accepts the selected native blur table and
motion measurement explicitly. It interpolates blur pixels and the gain ceiling
against exposure product, applies the motion-dependent shutter limit, then the
gain ceiling, before the existing banding and correction-factor stages. The
native conversion constant is 1000000. Motion below float32 1e-6 skips the
motion shutter limit but still applies the gain ceiling. No gyro approximation
or default blur table is introduced. Malformed, absent or unordered active
blur input is rejected. `normalExposureWithoutBlur` remains as a compatibility
entry point that explicitly refuses active blur without its required inputs.

`vivo-aec-long.h` ports `VivoEVPlusExpCalc` (0x17a238), including both its direct
shutter-extension branch and table/arbitration branch. The direct branch retains
gain when the candidate shutter and exposure product fit the donor limits.
Otherwise it calls the ported table lookup and full normal adjustment, with
mode 0 and correction 1 as in the original arbitration path. EV and flags are
preserved. The selected table's limits must agree with the adjustment context.
The shutter cap selects the branch; it is not an extra clamp after arbitration.

`check_vivo_aec_adjust.py` executes the complete original adjustment and long
functions in ARM64 emulation. PLT calls are forwarded to the original table
validator (0x177a9c), lookup (0x1774d4), arbitration (0x17a464) and adjustment
(0x17a5fc); only logging and the explicit sensor minimum-gain accessor are
substituted. All 1229 normal-adjustment and 800 long-exposure cases match the
output bytes, including correction factors and unchanged EV/flags. Both motion
and banding are exercised together, including interval endpoints, epsilon,
sensor minimum-gain fallback and rejected malformed motion/table inputs.
The 55+55 real phone replay cases still match after this change.

This completes those two arithmetic functions, not the upstream EVBaseCalc,
EVMinusCalc/EVPlusCalc orchestration or scene/table selection. Native motion and
gain-to-Camera2 ISO provenance remain prerequisites for enabling the calculation
in CaptureController. No runtime call is added with invented inputs; the app's
manual schedule is still in use. No new phone collection or APK was generated.
