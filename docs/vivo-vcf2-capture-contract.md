# VCF2 capture-control and ready-queue boundary

Recovered on 2026-09-20. These are verified components, **not a completed
capture pipeline**. Neither header is connected to Camera2 capture yet.

## Binary provenance

| Binary | SHA256 | Relevant entry |
| --- | --- | --- |
| libvcf_core.so | c3449dbb9867118173abc649de1fbab7edebebd826603cabe51fb9b58c0f07c5 | FeatureHandler::queryCaptureControlInfo 0x58b94 |
| libvcf_session.so | 93a3149a70fea5f177bb0542278c47ec232fc477aa96cb7a63534b1f6b5f8dad | modifiedOffseByMotionInfo 0x140214 |
| libvivo.vas.adapter.vcf.so | f5fdf379e0316fee77a0ff7760ef247881e4dfade98f3a7bc8b9b8f93c12e901 | fillCaptureControlInfo 0xf0380 |

## Capture-control payload

The core reads `vcf.parameter-CaptureFrameControlInfo` through native Metadata
at 0x590e0. This is not the Java SuperNight AEC float-array contract, nor is
there evidence that a normal app Camera2 result exposes this internal tag.
The tag name has no match in the supplied decompiled stock APK sources.

`vivo-vcf-capture-control.h` extracts the following little-endian fields.
It deliberately does not declare a packed native struct, invent shutter units,
interpret direction/format enums, or claim to decode the entire payload.

| Byte offset | Type | Field |
| --- | --- | --- |
| 0x000, 0x004, 0x008 | uint32 | frameNum, batchNum, remosaicType |
| 0x00c..0x00f | byte flags | needImageEcho, needImageEchoYuvProcess, needSelectPreferred, needSubCam |
| 0x010 | byte | remosaicSizeType |
| 0x014 | uint32[16] | per-batch algorithm types |
| 0x054 | uint32[16] | per-batch frame counts |
| 0x094 | 32 records, stride 20 | format:uint32, EV:float, gain:float, shutter:float, direction:uint32 |
| 0xb10, 0xb14, 0xb18 | uint32 | shot2shotDepth, countDown, frameCatchMode |
| 0xb28 | float | pastFrameISOThreshold |
| 0xb2c | float | pastFrameExpThreshold |

Frame/batch array capacities follow their adjacent field boundaries. The
reader requires 0xb30 bytes for these fields; this is not the native ABI size.
At 0x5920c--0x59224 either zero count requests the native default. Our reader
reports DefaultRequired without fabricating a default bracket. Oversized
counts, truncation and nonfinite selected float fields are explicit added
boundary failures. Failure clears output, so stale capture data cannot survive.
Unused array records and unimplemented payload sections are not interpreted.
Batch source slices and additional adapter consistency checks are described below.

The producer sets frameCatchMode=6 in a conditional branch at 0xf35d0 and
copies two threshold fields from PreviewToQueryParams. The core's log and
argument order at 0x5ae7c--0x5af28 establish **ISO first, exposure second**.
Do not infer their order from the producer's less specific log text.

Verification:

```sh
python tools/check_vivo_vcf_capture_control.py /path/to/libvcf_core.so
```

Passed 1,141 executions of original frame/batch/flag/threshold extraction
blocks against the C++ reader, and 2,887 rejection/reset cases. These are
synthetic payloads, not real phone capture-control traces. The test does not
execute the complete query or its producer.

## Ready-frame selection

`vivo-vcf-ready-selection.h` ports 0x1417e8--0x1422a8, including calls to the
previously recovered compatibility predicate. Inputs are already-ready,
chronologically ordered frame records and externally supplied native policy.
Its output is an offset and next-only status, not a list of finished requests.

The initial reference uses the original queue size minus three, clamped at
zero. Timestamp bracketing chooses the nearer frame, with ties toward the
earlier timestamp. The stock exposure reference remains the **lower bracket**
even when the later frame is nearer. A timestamp at or after the newest frame
selects the newest frame's metadata. Left/right compatibility scans then
determine the continuous range around the reference.

Modes 1/3/6 may move the offset beyond an incompatible range when the required
window does not fit. Modes 4/5 can request future-only buffers when settled
frames are insufficient. The returned offset is clamped below at zero, not
above at the queue size: an upper clamp would destroy the future-frame meaning.
When the timestamp is newer than the queue, the late-reference branch depends
on the result of config lookup 0x1426f0. This remains an explicit caller input.
Its source has now been traced: the nested map at ConfigProvider+0x2f0 is
populated from `SPORT_PORTRAIT_SCENE_UIMODE` in CameraConfig.xml, by
libvcf_platform_utils `1c5f88..1c6768`. Lookup uses scene ID, UI mode and an
exact camera-type string. Missing scene/UI/type entries return false.
The supplied PD2454 XML lists only SCENE_BOKEH_ZOOM with Wide/Tele and
SCENE_NORMAL_PORTRAIT_EXTERNAL with TeleExternal/default, each for
MODE_PROTRAIT (stock spelling) and MODE_HUMANITY. It does not list the normal
auto mode. This is evidence for a false result in that configuration, not
permission to replace all scene lookups with a constant. The scene/UI enum
mapping and caller metadata routing remain to be connected. Reading this
policy does not require enabling any sensor-mode switching.

Verification:

```sh
python tools/check_vivo_vcf_ready_selection.py /path/to/libvcf_session.so
```

Passed 1,600 original ARM64/C++ comparisons covering timestamp boundaries,
duplicate timestamps, incomplete windows, exposure transitions, AEC states,
modes 0--8 and both config-result values. Original instructions in the tested
selection range and compatibility predicate are unchanged. The harness supplies
the external config result, disables the debug reference override, returns an
empty camera-name string and disables logs. Metadata collection, configuration
lookup, upstream movement policy and request submission are not tested.

## Integration still required

The current CaptureController still requests four normal ZSL frames; the burst
adapter still maps the available frames into seven fixed NICE network slots.
The latter network input shape alone does not establish the correct capture
schedule or reference selection. Supplying an arbitrary mode to the recovered
selector would retain the hybrid behavior the user reported.

Before using these components, recover the producer/consumer mapping for the
actual shutter/gain units, remaining direction enums, batch-to-request association and
mode/config inputs. Connect that mapping to timestamp-matched capture metadata
and preserve ownership/cleanup when a window requires future buffers.

Tone/TCE still lacks the photographic input/mask/color and gain-map routing in
the capture path. Existing FastTM conversion and synthetic QNN execution checks
do not supply that missing routing. No new APK is produced from this checkpoint,
and no artifact-fix or stock-photo-parity claim is made.


## Batch-consumer correction and recovered counts

Following processRequest exposed a semantic error in the first reader: the
names of the two batch arrays were swapped. The corrected layout is algorithm
IDs at 0x14 and frame counts at 0x54. queryCaptureControlInfo's log at 0x59600
passes the 0x54 value as its frame-count argument. More decisively,
processRequest 0x61068 stores 0x54 in the loop count, 0x61184--0x61198 consumes
that many 20-byte frames, and 0x61080 appends 0x14 to the algorithm-ID vector.
The previous extraction tests only checked round-trip byte placement; they
could not detect incorrect member names. They are retained alongside a new
consumer-level semantic test with deliberately distinct counts and IDs.

`vivo-vcf-batches.h` recovers contiguous source slices using cumulative batch
counts. It preserves source order and numeric algorithm IDs. The native loop
reads a batch's first direction at 0x6106c and uses it for the entire batch.
The adapter adds checks for nonempty batches, exact coverage of the frame list,
bounds, supported direction values and consistent direction within a batch.
These are adapter validations, not assertions that native HAL code validates
malformed input in the same way.

Direction 0 is accumulated as past frames in generateBaseFrameCaptureInfo
0x15c144--0x15c16c. Direction 1 is counted as future by
DecisionRule::calcFutureFrames 0x140a10. Both calcAllFrames 0x1409b0 and
calcFutureFrames are now implemented independently of any fixed bracket.
The latter counts exactly 1: other nonzero enum values do not count as future.
It is therefore incorrect to universally equate nonzero direction with future.

processRequest places direction-zero batches directly into the output vector
at 0x6169c, collects nonzero batches separately at 0x61614, then appends them
at 0x61780--0x617a4. The slice reader does NOT silently perform that reordering;
its returned indices identify the original payload. The association with the
separate algorithm-ID vector and final request construction still needs to be
preserved when integrating the planner. This is not yet a Camera2 scheduler.

Verification:

```sh
python tools/check_vivo_vcf_batches.py /path/to/libvcf_core.so
```

Passed 716 original batch-consumer block comparisons, 600 executions of the
complete original count functions, and six invalid-schedule checks. Fixtures
exercise empty batches in the count functions, vector/scalar lengths, multiple
batches and direction values 0/1/2/0xffffffff. Reader checks separately enforce
the supported scheduling subset. Existing extraction checks still pass.
No APK is built from this partial integration, and this correction alone does
not change the current photographic path or establish artifact removal.

## Scope and reference-calibration connection (2026-09-20)

User clarification: port the automatic photographic processing path with the
existing sensor mode. Stock sensor-mode switching, remosaic-mode transitions
and stock camera session reconfiguration are outside this task. Dynamic ZSL,
exposure brackets, motion, reconstruction and tone remain in scope; they must
operate on the existing RAW stream.

The active NICE path now retains the full timestamp-matched CaptureResult in
ImageFrame. After reference selection, HdrxProcessor reloads dynamic processing
parameters and EXIF from that reference before constructing the NICE burst.
Previously only exposure/noise scalar fields survived, while WB, lens shading,
black/white levels and downstream exposure could still come from another burst
result. A missing or mismatched reference result now fails the NICE capture
instead of silently borrowing calibration. A null second metadata-map lookup
preserves an existing ZSL association. Replacing a result resets missing noise,
and a repeated dynamic parameter fill resets dynamic-level and shading flags.

`python tools/check_nice_reference_metadata.py` compiles the actual ImageFrame
against minimal Android stubs and checks matched association, null relookup,
replacement, missing noise, missing/wrong timestamps and independent frames.
It passes on Java 17. The three changed production Java files also parse with
javac, but a full Android build and phone photography have not been checked.
This fixes a capture/processing metadata boundary; it is not an implementation
of the remaining stock reference policy or a claim that artifacts disappeared.

Further consumer tracing distinguishes the YUV-only HDR gain/shutter tags
(session updateYuvHDRGainShutterTag 0x2557c0) from RAW scheduling. Its division
by 1,000,000 must not be borrowed as proof of CaptureFrameControl shutter units.
Session mergeOlSettingToRtRequest 0x16ab80 propagates metadata and handles
isPastToCaptureFrame; tracing it has not established a Camera2 exposure writer.
The remaining dynamic scheduler and photographic Tone/TCE routing are still
unconnected. No completed-port APK is available from this checkpoint.

## Request-bound bracket roles (2026-09-20)

NICE still requests the existing Camera2 bracket without changing sensor mode.
Each new normal/long/short request now carries its immutable N/L/S role as the
CaptureRequest tag. ImageFrame recovers the role only from its own matched
CaptureResult; paired ZSL images are normal frames. HdrxProcessor constructs
NICE roles and exposure products from those matched results, not from indices
into the global fullpairs list. A missing RAW or reordered delivery can no
longer shift the subsequent frames into other bracket roles. Missing/duplicate
metadata and untagged PSL inputs fail explicitly. Non-NICE and calibration
request tagging remain unchanged.

The host regression additionally covers dropped/reordered deliveries and
unknown or mismatched roles. It does not test Android HAL delivery or establish
a stock dynamic bracket policy. The APK build is being run to exercise the
connected capture changes and previously recovered CRE motion/warp/VST work.
Full VCF scheduling and photographic Tone/TCE routing remain incomplete.

## NICE HDR plan producer (2026-09-20)

`vivo-vcf-nice-hdr-plan.h` now ports the count/batch/frame construction in
VASAdapterMetadataConvertVCF::getNiceHdrCaptureControlInfo (`101f48`), for
the existing sensor stream. It does not switch sensor modes or emulate the
seamless/dual-stream branches. It consumes a real scene/AE query decision;
it does not generate that decision from ISO, lux, user settings or gyro.

| PreviewToQueryParams offset | Meaning in the producer |
| --- | --- |
| 0x2d08 / 0x2d0c | past / future frame counts |
| 0x2d10 / 0x2d50 / 0x2d90 | future EV / gain / shutter float[16] |
| 0x2dd0 | separate short EV float[16] for QueryToShot RAW descriptors |
| 0x3d8c | nonzero selects alternate exposure mode |
| 0x2ec0 / 0x2f00 | alternate EV / alternate short EV float[16] |

`applyNiceHdrQuery` produces one-frame batches (algorithm ID 1) with native
format 0x12. Past frames use direction 0; future frames use direction 1. It
preserves the initialized AE fields of past frames and the gain/shutter of
alternate-mode frames, because the original does not write those fields.
The normal frame record gets EV while the separate QueryToShot RAW record
gets short EV; merging those arrays would change the stock request.
The original float shutter sum is truncated to int32; units are left untouched.
The stock echo marker is exactly 101.f, not zero EV. Without past frames it
selects the echo index; with past frames it does so only when the caller's
image-echo flag is enabled. Later matching markers overwrite earlier ones.
When past frames exist, scene IDs 8/31 select catch mode 3, others select 4;
without past frames the previous catch mode is retained.

Added validation bounds the total to 16 one-frame batches, rejects nonfinite
active query fields and int32 shutter-sum overflow, and publishes no partial
result on failure. It does not reject unused alternative arrays. Existing
`captureBatchSlices` accepts the produced batches without losing association.

```sh
python tools/check_vivo_vcf_nice_hdr_plan.py /path/libvivo.vas.adapter.vcf.so
```

608 original ARM64/C++ comparisons cover every total 1..16, every past/future
partition, both exposure modes and both echo policies. Distinct initialized
values test preservation of untouched fields. Nine malformed queries are
rejected. The test executes `101fa4..101fb8`, `102048..1022c8` and
`1023ec..102410`; only logging is skipped. It does not run scene/AE inference,
sensor/seamless handling, vendor metadata publishing, or Camera2.

The same producer publishes `vivo.parameter.VivoAlgoAECFrameControl` (48
elements), `VivoAlgoAECShortFrameControl` (49), and `VivoAlgoCaptureFrameControl`
(9), at `10284c`, `1028bc` and `1029d8`. These are separate from the internal
VCF capture-control payload. The supplied APK declares the first as float[]
and the count array as int[]; existence of those keys does not establish that
our current Camera2 session receives the complete stock scene decision.
Connecting the query producer, ready-queue policy and exposure request writer
is still necessary. The current camera capture path still uses its fixed
normal-frame selection and manual bracket; this helper is not advertised as
a finished dynamic capture scheduler. No APK build was started for this work.

## Queue dispatch and caller offset normalization (2026-09-21)

The user prioritized completing ZSL/bracket before TCE. Original VCF2
getOffset (142dc0) and modifiedOffseByReq (140078) are now represented by
vivo-vcf-queue-offset.h. Modes outside 1..6 return queueSize-requested without
running motion selection. Within 1..6, sync byte 0 set / byte 1 clear plus a
nonempty request-ID list selects the first occurrence of its first ID in the
queue. A missing ID returns zero; the original fallback-offset argument is not
used. If both sync bytes are set, onlyNeedNext becomes 1 before motion policy.
The remaining branch delegates to the full motion policy explicitly.

**Caller correction:** although selectReadyFrames only clamps its offset below
at zero, preparePastBuffersLocked at 127618..127748 resets a negative offset or
an offset at/after the queue end to zero. The onlyNeedNext flag independently
prevents consuming past frames. Do not interpret an out-of-range selector offset
alone as a future-frame request. pastQueueStart implements this caller boundary.

check_vivo_vcf_queue_offset.py compares 1200 cases with the original complete
getOffset/request-ID implementation and original caller normalization block.
The motion body is intercepted with a configurable return/flag, while its
arguments and call/no-call selection are checked. Logging and empty metadata-map
copy scaffolding do not replace the decision instructions. This test does not
claim that the full motion policy or production capture is connected.

SCAMERA-ZSL-Live-v1 observes the real first NICE plan, VCF2 queue before/after,
request IDs and native ready-frame metadata. It avoids CameraService TagMonitor
and copies no image pixels. The TCE v4 archive cannot supply this scheduling
context because it starts downstream of capture. Device validation of this
new collector is pending. The active app still has its old manual bracket;
there is no claim of completed stock ZSL or a new working APK at this point.


## Phone replay and delivery boundary (2026-09-21)

The v6 trace `scamera-zsl-trace.30Hy1RcT.tar.gz` records a real query with
past=4, future=1, alternateExposureMode=true. The future descriptor carries
EV code 100 and zero gain/shutter. These zeros are preserved fields in the
alternate branch, not an instruction to expose for zero nanoseconds.
The portable producer matches the recorded counts, batch arrays, frame records,
RAW descriptors, catch mode and accumulated shutter in
`tools/replay_vivo_zsl_trace.py`. This replaces synthetic-only evidence for this
one producer case; it does not implement or validate the scene/AE solver.

The producer returns catch mode 4, whereas both prepare calls use mode 6.
Ready IDs are 113..118; each queue appends 114..117 to the shared ID vector.
The duplicate ID range is two queues, not eight temporal frames. No delivery
callback was observed by v6 because it did not hook that function. A successful
prepare return is not proof that its requested future buffers were delivered.

Collector v7 retains the two known exported hooks and adds the verified export
`BufferQueue::getPastAndNextBuffers` at 0x12bb14. Its actual ABI is this=x0,
past shared_ptr vector=x1, future shared_ptr vector=x2, request-ID vector=x3.
The ELF symbol and disassembly confirm these references; shared_ptr stride is
16, request-ID stride 4. BufferPrepareInfo vector at queue+0x270 has stride 144
(12ba94..12bac4), and is recorded opaquely. No buffer object methods or pixel
pointers are invoked/read. The v5 interior hook 0x1417e8 remains removed: the
phone tombstone established SIGILL at 0x1417f4 inside its literal patch data.

## Connected app completeness check

The active manual NICE path now freezes the submitted request identities and
selected ZSL timestamps in `VivoNiceCaptureSequence`. A unique immutable tag
preserves generation, request index and N/L/S role. Every future request must
produce a distinct matching result with positive measured exposure/ISO, and a
RAW with that exact timestamp. Reordering is accepted; missing, duplicate,
foreign-series, failed or RAW-buffer-lost requests fail before NICE processing.
The expected count comes from the actual submitted request list, including a
skipped collapsed short exposure, not the difference between HAL frame numbers.
ImageSaver's reader quota counts only future requests: ZSL inputs are already
owned. Snapshot/discard synchronize with image insertion; failed series close
retained frames. Existing matched reference/noise calibration remains intact.

Host verification covers real Java production classes with Android stubs,
original donor queue dispatch and selection, the real phone producer replay,
and collector coordinator/hook simulations. These are not Android device tests.
Callback signatures follow the Android CaptureCallback API:
https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession.CaptureCallback

**Remaining blockers:** complete scene/AE query production for SCAMERA's RAW
stream; final producer-to-queue mode/context translation; exposure-code consumer
and measured future-frame association; compatible graph dispatch for variable
series. Neither reading an EV code as stops nor copying a single 4+1 trace is a
valid substitute. The capture controller therefore still reports stockVcfPlan=false.
The v7 hook set also needs a phone trace; no device connection is available in
this workspace. No full-port APK or stock-parity claim is made.
