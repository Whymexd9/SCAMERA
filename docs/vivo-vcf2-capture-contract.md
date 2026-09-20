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
on the result of config lookup 0x1426f0. This remains an explicit caller input;
the lookup table and scene policy have not been substituted with a constant.

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
