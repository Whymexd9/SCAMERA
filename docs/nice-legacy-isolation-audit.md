# NICE bracket audit — 2026-09-21

Source: codex/zsl-bracket-completion, 1e296743977a0ab00f366fc27d752e630ffa8cde.
Compared against the 15:01:16 capture log and SCAMERA-ZSL-bracket.patch.

## Stock transfer status

The RAW route remains a manual Camera2 hybrid. CaptureController logs
stockVcfPlan=false and does not apply the shutter AE plan. VivoNiceRequestPlan
is a value bridge; its legacy field writer is not a substitute for VCF2's
single-request command/template path. Scene/AE production, exposure-code
consumption, producer/queue translation and variable-graph delivery are not
fully connected. See vivo-vcf2-capture-contract.md and vivo-nice-request.md.

The 9.8 ceiling is inherited GCam tuning in IsoExpoSelector, not an established
NICE model limit. The submitted +/-4 EV became +/-1.65 EV in the supplied log.
Removing that ceiling does not implement stock exposure selection.

## Changes

- NICE bypasses getMaxHdrRatio, TetModel and the legacy readout-period shutter
  cap. Sensor bounds still apply. Legacy HDR+ behavior is retained.
- NICE's automatic base retains metered preview shutter/ISO; no second
  exposure compensation, shutter-priority curve, balance policy or old HDR
  pattern is applied. A measured ZSL base is constructed directly.
- Legacy highlight suppression cannot remove NICE's short RAWs.
- NICE bypasses SCAMERA BurstFrameSelector and gyro deblur preparation. Its
  first normal input supplies the same matched calibration used by VivoNiceBurst.
  This is an explicit interim policy, not recovered stock reference selection.
- After NICE, legacy CorrectingFlow, FalseColorSuppression, Capture One and
  CaptureSharpening are bypassed. RTSharpening and its user controls remain
  enabled as requested. Orientation/watermark remain.
- LinearExposure uses fixed fallback defaults for NICE rather than saved Sky
  Exposure tunables. VivoHdrTone remains the explicit SCAMERA fallback.

## Remaining boundaries

Manual frame-count and L/S EV controls still construct this RAW bracket.
NICE consumes four N slots, one L and S/ES; one short is duplicated for S/ES.
This change does not fabricate a stock ES schedule or change sensor modes.
The Java/native transport accepts exposure products within 1/256..256 of N;
these checks reject invalid input rather than silently changing exposures.
They are implementation bounds, not proof of model quality across the range.
The native RAW clamp to black/white and VST/IVST bounds remain unchanged;
removing them without a verified donor-domain replacement is unjustified.

NICE norm/noise controls, measured calibration, WB/LSC and the explicit
VivoHdrTone controls remain active. TCE is not connected in this RAW route.
Old remosaic/MFSR choices can also disable NICE via PreferenceKeys route
exclusivity; no claim is made that checking the NICE box overrides all modes.

## Validation

check_nice_legacy_isolation.py compiles the actual IsoExpoSelector,
HdrBracketFactors and TetModel with host Camera2 stubs. It exercises EV 1..8,
measured base handling and legacy 9.8 regression. Legacy preference getters
throw when accessed by NICE. Tolerances allow integer ISO/shutter quantization.
check_nice_capture_sequence.py checks request/result/RAW association.
These are host checks, not sensor, NPU, GPU rendering or image-quality tests.
No APK built: AGENTS.md requires the AE/VCF2 port completed before a test APK.

## Additional stock evidence and next dependency

The v8 trace scamera-zsl-trace.CqyFNMG0.tar.gz records four past buffers
(IDs 63..66) and three future buffers (83..85), successful delivery, alternate
exposure codes -100/-200/100, producer catch mode 4 and queue catch mode 6.
It does not record the actual sensor shutter/ISO for those buffers. These
codes are not Camera2 EV compensation values and must not be translated by
guessing. This observation does not establish a universal seven-frame plan.

The recovered libvcf_platform.so exports setExposureParameterForRawAlgo
at 0x27ea28. Its stock strings identify Normal exposure/gain conversion,
org.quic.camera2.statsconfigs-AECFrameControl and com.qti.sensorbps-gain
updates. This is not sufficient evidence of the upstream exposure-code solver.
The later SCAMERA-stock-AE-20260921-155858-8545.tar.gz supplies all five
requested AE libraries and five sensor tuning files; every recorded hash was
verified and missing.txt is empty. com.vivo.stats.aec.so SHA256 is
b2e3e12469a05cf62c2a5892b2b619228fb29a3ccb8fa9fe5f934df0dd5842e9.

vivo-aec-exposure.h recovers EVGapCalc arithmetic and VivoEVMinusExpCalc
for validated positive inputs, with bounded banding iteration. The original
arithmetic and output flags match in 1000 short-exposure cases; 400 gap cases
match across normal and mode-10 tuning offsets. The test executes the original
ARM64 instructions, supplying explicit context, valid-table status and silent
logging. It does not test table loading or the upstream AE solver. The native
shutter unit is deliberately not converted to Camera2 nanoseconds here.

The stock solver runs table/mode selection, debug delta EV, EVGapCalc,
decreaseEVCalc, EVBaseCalc, EVMinusCalc, EVPlusCalc, then output flags. A port of
the short-exposure leaf alone is not a substitute for that chain. These helpers
are not yet called by capture. The long path additionally invokes exposure
table arbitration, and no stock exposure-code mapping is inferred from ±100.

The v9 collector adds only the exported vivoCalculateExpInfo entry at 0x178808,
pinning the new library hash. It records eight pre-shutter and at most 120
post-shutter solver results: flat input 0xe0, common tuning prefix 0xb8,
flags 0x1c and six output records within 0x7c. Memory is read-only; no buffer
methods or internal instruction hooks are added. Results are solver-call
observations, not measured RAW metadata or proven frame-ID associations.
The subsequent gUJc3NGj trace supplies live solver results; see below.
Stock dynamic-range parity still requires matched captures after integration.

## v9 phone result and HAL code consumer

scamera-zsl-trace.gUJc3NGj.tar.gz completed normally in PID 12187, with all
six hooks installed and no observer errors. It contains 128 AE results, a
4-past/1-future query, catch modes 4/6 and successful delivery of past IDs
74..77 plus future ID 93. The sole future code is 100. The existence of S/ES
solver candidates does not mean this capture requested S/ES future frames.

The nearest recorded solver call starts 20 ms before the NICE query, in
mode 9, with tuning gaps -4/-8/+4. Its candidates are:

| Slot | Shutter native | Linear gain | Product EV relative to slot 0 |
| --- | ---: | ---: | ---: |
| 0 | 33333332 | 20.707386 | 0 |
| 1 | 33333332 | 1.899465 | -3.446480 |
| 2 | 8333333 | 1.430000 | -5.856058 |
| 4 | 85888400 | 20.707386 | +1.365498 |

Slots 3/5 are zero. Gain must not be labelled Camera2 ISO. Temporal proximity
alone does not prove this is the result consumed for ID 93. analyze_ae.py
preserves that distinction and exposes solver candidates separately from the
requested future codes and confirmed queue deliveries.

camera.qcom.so SHA256
137ac72b364f280d993744cb06cf4e6f297bbe45e1aa3248a3560eafc1ac4020
contains the missing selector at 0x8d25d4..0x8d264c: 0/-100/-200/+100 select
solver slots 0/1/2/4. The tolerance is strictly below 2^-23. Code 101 takes
the reference exposure. In the direct-table branch (input+0x50 == 0),
0x8d2800..0x8d2824 reads the selected shutter/gain/actual EV and divides the
shutter by 1,000,000. The echo branch at 0x8d28ac uses the reference uint64
shutter and preserves the sentinel EV 101. The downstream explicit-exposure
writer at 0x8d5d40 multiplies the float shutter by 1,000,000 and truncates to
uint64. Native float rounding must be retained across this conversion.

vivo-camx-ae-plan.h recovers this selector and direct-table/echo resolution.
check_vivo_camx_ae_plan.py matches the original instruction slices in 500
cases, plus code boundaries and unsupported-code rejection. It does not
emulate the separate Pro RAW arbitration branch or special code 102.
These helpers remain outside CaptureController: full upstream AE/table
production, capture context, frame metadata association, variable NICE graph
selection and TCE binding are still required. No full-port APK is built.

## Device VCF2 connection failure (16:33 trace)

`SCAMERA-debug.log (2)(5).txt` does exercise `route=VCF2_JPEG`
(logged mode MOTION). At 16:33:49.978, before service open or capture,
reflection fails on IVivoCameraDeviceCb.onBufferCallback with
NoSuchMethodException. The earlier NIGHT-only diagnosis does not apply to
this newer log. Repeating the same capture is not useful.

The supplied vivo-camera-framework.jar (SHA256 recorded in vivo-nice-request.md)
has exactly the requested signature. Its DEX hiddenapi_class_data marks all
nine callback methods and VivoCameraManager.open as restriction 2 (blocked).
This is consistent with Android filtering reflection for the application;
the app log alone cannot prove the runtime denial reason or that the installed
framework matches the supplied file. A system logcat containing ART/hiddenapi
messages for the failing process would distinguish filtering from version
mismatch. No access policy or identity has been changed.

check_vivo_vcf2_device.py now decodes the original per-member hidden-API flags
and explicitly reports this access blocker alongside host ABI/lifetime checks.
Its previous ABI success must not be interpreted as device accessibility.
Resolving access alone would still only enable the existing JPEG route, not
stock RAW-series delivery or a complete bracket port.

### Root transport investigation

The framework's VivoCameraManager.open calls connectDevice(callback,
context.getOpPackageName(), -1). The -1 requests the calling UID; moving only
this call to root is not evidence that results from the app's Camera2 session
will arrive there. Service-side client association must be inspected first.
The root file listing contains /system/lib64/libvivocameraservice.so and
/system/bin/vivocameraserver, but these binaries are absent from the supplied
local archives and the filename searches did not resolve them.

VIFResult.writeToParcel is a single return-void instruction in the supplied
framework. A worker cannot forward that Parcelable unchanged to the app;
metadata needs explicit serialization retaining capture identity. The DEX
check now verifies and reports this additional transport boundary.

tools/collect_vcf2_connection.sh collects the installed framework, service and
client binaries, their hashes, relevant process maps/status, and 45 seconds
of bounded main/system/crash logcat during one VCF2 attempt. It does not clear
logs, issue camera-service dump commands, hook processes, alter policy, or
change capture/sensor settings. Shell syntax and framework checks pass on
host; the collector still needs execution on the phone. Full port and APK
remain blocked pending service access/association and RAW-series delivery.

The subsequent 18:23 connection archive supplies the service binaries. Their
callback loops broadcast to registered clients, resolving the UID-association
uncertainty above. The root receiver is now connected to VivoVcf2Capture with
capture-ID arming/acknowledgement and explicit timestamp/JPEG transport. See
vivo-vcf2-root.md for exact addresses, validation and remaining device/RAW gaps.
GCam Scene AE feedback is also excluded from NICE/VCF2 capture paths.

### RAW-to-NICE input mapping trace

Step 1 still lacks an observed correspondence between the variable stock
capture series and model inputs. The v9 trace records queue deliveries and
candidate AE records, but not NICE input-slot assignment.

NICEIntegration::fillInputParams at 0x10a24 takes separate source/destination
indices. Instructions 0x10aa4..0x10ae4 copy VImage records (stride 0x78) to CRE
records (stride 0x198), including source+0x18 to destination+0x38. AE mappings
are documented in vivo-tce-boundary.md. Queue count alone does not establish
positional correspondence or justify duplicating S as ES.

The v10 collector pins the NICE wrapper hash and observes this exported entry:
up to 24 paired calls with bounded descriptors and AE fields, without pixels.
analyze_nice_inputs.py reports indices and bitwise AE-copy agreement; it rejects
unmatched entry/return identities. Array indices are not sensor frame IDs.
Hook/runner, bounds and association checks pass on host. A stock Photo 1x
high-contrast trace is needed before selecting this mapping in production.


### v12 device evidence: RvYoEiAn (2026-09-21)

Both observers attached: Qualcomm PID 1832 and camera3rd PID 1934. The first
process recorded one plan, 4 past + 3 future, codes -100/-200/100; the observed
queue returned IDs 111–114 and 132–134. The second process recorded seven
paired fillInputParams calls for one proc/output pair, source indices 0..6,
all destination index 0. This is sequential reuse of a destination record at
this boundary, not evidence of seven final model tensor slots.

Observed NICE input AE (milliseconds; linear gains, not Camera2 ISO):

| Source | Exposure ms | Analog gain | EV | Digital gain | DRC gain |
| --- | ---: | ---: | ---: | ---: | ---: |
| 0..3 | 2.49614191 | 7.62135792 | 0 | 1 | 1.73208380 |
| 4 | 1.75925505 | 1.43150008 | -2.91725564 | 1 | 1.73208380 |
| 5 | 0.22046000 | 1.51219499 | -5.83451128 | 1 | 1.73208380 |
| 6 | 4.98855686 | 7.62705040 | 1 | 1 | 1.73208380 |

All 42 AE field copies match bitwise; all seven observed plane-pointer copies
match within PID 1934. No missing returns or parser errors. This establishes
actual NICE input values, unlike the earlier candidate AE records. It does
not establish pixel identity with the queue IDs across processes.

Nearest pre-query AE was 19 ms earlier. Its ES candidate was 0.23197058 ms at
gain 1.43; NICE received 0.22046 ms at gain 1.51219499. Production must use
per-frame measured exposure/gain, not copy the nearest AE candidate as actual
metadata. Differences in exposure time can be compensated by gain.

At this input boundary the exposure order is N,N,N,N,S,ES,L. The existing
fixed network graph uses N,N,N,N,L,S,ES; these are different boundaries and
must not be equated or blindly reordered. A later CRE selection stage can
reorder records. This trace does not resolve the separate 4+1 capture route,
prove identical dynamic range, or complete the AE/RAW/TCE port. No APK built.


### Matched vendor exposure domain connected

VivoNiceAe now exposes the measured exposure product from the timestamp-matched
AECFrameControl snapshot: aec[14] (ns) * aec[2] (linear total gain). VivoNiceBurst
uses this domain for S/ES and L sorting and for all seven normalization ratios
when every source frame provides valid vendor AE. The same immutable snapshot
is serialized with the selected RAW, avoiding a second metadata read.
Partial vendor availability is rejected, because ISO and linear gain cannot
be mixed across frames. If the entire series lacks vendor AE, the existing
Camera2 measured-exposure * ISO adaptation remains explicitly logged; it does
not become stock-equivalent. No DRC or scene estimate enters the exposure ratio.

The actual Java AE/burst classes compile and execute with host Android stubs.
The RvYoEiAn measured values produce the expected ratios despite deliberately
uninformative Camera2 exposure/ISO, partial vendor availability is rejected,
and the all-Camera2 fallback still produces its measured ratios. These are
host checks, not a full Android APK or on-device processing test.

Recovered CRE donor SHA256 41b277753f7fedbe4dda1e1c4b76d2086d6e79768904d8a4922b48a0e023e76e
was restored from the previously supplied archives. Original ARM64 execution
passes 4,500 radiometric reference selections and 103 S/ES rebasing cases.
Disassembly 3617e8..361830 swaps an already-selected reference with slot zero;
it does not establish how the reference was chosen or turn the NICE input
source order into model input order. No speculative order change was made.
The old message claiming S duplication was supported by donor routing is now
corrected to identify this as SCAMERA fallback with an unestablished stock route.

Remaining runtime evidence gap: the 4+1 captures were logged without NICE input
observations. The only paired NICE trace is 4+3. No current evidence establishes
which graph/selection handles a stock 4+1 input; do not synthesize S/ES and call
it a complete bracket port. Root VCF2 transport still delivers JPEG, not RAW.


### Second paired trace: SQGhYcUe

The requested lower-contrast capture still selected 4+3, not 4+1. One queue
returned past IDs 60–63 and future IDs 81–83. NICE recorded seven paired inputs
in source order 0..6, all reusing destination 0; 42 AE copies and seven pointer
copies match, with no missing returns or parser errors.

| Source | Measured exposure ms | Analog gain | Reported EV |
| --- | ---: | ---: | ---: |
| 0..3 | 33.32622528 | 9.55417538 | 0 |
| 4 | 33.32622528 | 1.68071389 | -2.5 |
| 5 | 8.32622528 | 1.43121970 | -4.69999981 |
| 6 | 66.65956116 | 9.55315208 | 1 |

Digital gain is 1 for all inputs, DRC gain 1.91958988. Here S and N have equal
shutter times: their difference is gain. The actual Java burst regression now
also covers these values, so sorting/normalization cannot silently use shutter
alone. Reported EV is retained as observed, not assumed to equal the exact
logarithm of quantized measured exposure products. This trace still does not
establish the 4+1 processing path or sensor-frame identity across processes.


### User scope change: only 4+3; strict RAW intake connected

The user explicitly excluded 4+1. Previous notes requiring a 4+1 observation
are historical and no longer completion gates. AGENTS.md records this change.

VivoNiceBurst now accepts exactly seven distinct, positive RAW timestamps,
with four N, one L and two short-role frames. Role classification comes from
ImageFrame.getCaptureRole(), which uses timestamp-matched CaptureResult and
request identity; legacy ExpoPair highlight/long flags are no longer read here.
The two short RAWs are ordered by their measured exposure product. Require
ES < S < N-reference < L. Missing N/L/ES are rejected, never replaced with
repeated RAWs. Fixed network layout remains N,N,N,N,L,S,ES; the input observation
order was not substituted for network order.

Host tests execute the actual Java AE/burst implementations against both real
trace value sets. Added failures: five-frame input, duplicate timestamps, wrong
role counts, missing matched role, equal S/ES exposure. Deliberately poisoned
legacy role flags do not affect valid input. Existing actual ImageFrame tests
pass timestamp matching and role retention after reordering/dropped frames.
RawTherapee sharpening and sensor-mode settings are unchanged.

This is a processing-intake change, not completion of stock capture. Inspection
of CaptureController still shows manual setLongExpo/setUltraShortExpo and old
count/EV preferences; both repeated short requests use the same EV selector.
Do not claim this scheduler emits stock S and ES. The new intake rejects such
invalid input instead of silently presenting duplicated S as an ES frame.
The known next integration is stock AE-controlled distinct S/ES/L requests and
RAW delivery, followed by TCE and the final Actions build. No APK built.
