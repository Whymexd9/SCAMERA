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
| 0x014 | uint32[16] | per-batch frame counts |
| 0x054 | uint32[16] | per-batch algorithm types |
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
Batch membership semantics and sum constraints remain unestablished.

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
actual shutter/gain units, direction enums, batch-to-request association and
mode/config inputs. Connect that mapping to timestamp-matched capture metadata
and preserve ownership/cleanup when a window requires future buffers.

Tone/TCE still lacks the photographic input/mask/color and gain-map routing in
the capture path. Existing FastTM conversion and synthetic QNN execution checks
do not supply that missing routing. No new APK is produced from this checkpoint,
and no artifact-fix or stock-photo-parity claim is made.
