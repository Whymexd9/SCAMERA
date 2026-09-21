# PD2454 TCE runtime input snapshot

This is a diagnostic dependency of capture integration, not a finished TCE or
dynamic ZSL implementation. The active worker still uses the fixed seven-input
graph and SCAMERA's final processing. Do not publish it as a completed port.

The latest supplied SCAMERA(7) photo contains worker v23 / NCH v2; SCAMERA(6)
contains worker v22. Neither provides the newer per-RAW AEC transport or the
stock TCE runtime scene/color context. The supplied allocator libraries resolve
allocation, but do not provide those per-shot values.

## Original native dump gates

Pinned TCE SHA256:
`9f5deac3bc68fc86fcf16b98f43c232a9642bc309c7d5d42c88d6a4b596b892d`.

| Original address | Condition |
| --- | --- |
| 3e9f40..3e9f78 | Enter dump helper if portraitseg != 0 OR cached nicetce >= 4 |
| 3e63a8..3e63b4 | Write RGB/LUT image payloads only if cached nicetce >= 4 |
| 3e7620..3e7658 | Write JSON if portraitseg != 0 OR cached nicetce >= 4 |
| 3c9f80..3c9fa4 | Write segmentation input YUV if portraitseg > 0 OR nicetce >= 4 |

The property reader at 3e9144 preserves signed `atoi` results. Therefore
`vendor.vivo.vaf.dump.nice.portraitseg=-1` with general nicetce=0 selects the
JSON branch while skipping these image dump branches. This is a recovered
behavior of this exact binary, **not** an OEM-documented portable setting.
The exact property string was found only in TCE among the supplied vendor/VCF
libraries. Its three code references are the JSON gates and segmentation gate.

`check_vivo_tce_dump_gates.py` executes all four original ARM64 blocks over
24 combinations (96 branch checks), including the real signed property-reader
function. Only Android property_get and libc atoi imports have host shims.
Algorithm instructions are unmodified. This does not run the JSON serializer,
GPU processing or camera on a phone, and cannot establish device stability.

## Collector

`tools/collect_vivo_tce_json_v2.sh` checks the donor hash and refuses a nonzero
general dump setting. It changes only the live JSON selector, waits at most
90 seconds for one manual stock auto capture, restores the exact previous
value, and copies new matching TCE JSON files. It does not enable TagMonitor,
call CameraService/dumpsys, terminate an app, switch sensor modes, invoke the
shutter, clear logs or change global logging. A bounded existing TCE log is
included if available. Original camera files are never modified or removed.

Run after closing the stock camera, then follow the script's one-photo prompt:

```
su -c 'sh /sdcard/Download/collect_vivo_tce_json_v2.sh'
```

The archive is written to Download/SCAMERA. If no JSON appears, inspect the
archive before asking for another capture. A recovery script preserves the
original property value in case the shell is killed in a way traps cannot catch.
Restoration failure is an error and keeps the collector lock for investigation.

The first phone run reported `read: select: Interrupted system call`. The old
timed terminal read incorrectly treated that failure as the end of capture.
The revised collector never reads the terminal: it waits until a 90-second
`/proc/uptime` deadline, tolerating interrupted sleep without shortening the
window. Enter/EOF no longer ends collection. Explicit termination still restores
the setting and exits. Inspect the existing failed-run archive before recapture;
zero collected JSON alone does not establish that stock TCE never generated one.
The supplied `vivo-tce-json-U4ddFl7x.tar.gz` confirms the early close: its start
marker is 19:54:51 UTC and restoration is 19:54:55 UTC (2026-09-20), only four
seconds apart. Donor hash matches, search completed without errors, and both
candidate list and TCE log are empty. Thus this run did not provide the intended
90-second collection window. The revision records measured uptime duration.

`check_vivo_tce_collector.py` runs the actual shell script under host bash with
mock Android commands. Eleven cases cover previous empty/0/1/-1 values, SIGTERM,
EOF, interrupted sleep, no matching file, wrong donor, pre-existing full dump,
failed property write, restoration and exclusion of older/unrelated JSON and image files.
Android shell/property permissions and actual JSON generation remain untested.

## How to interpret the snapshot

These are per-shot TCE values, not reusable calibration constants. The native
serializer also emits placeholder fields. Never treat the complete JSON as a
lossless capture-request trace or copy it into live TCE input as defaults.

| JSON field | Native source / implication |
| --- | --- |
| RawHDRInputAEParam.AnalogGain | Process+2f8; 3e85c0..3e85dc |
| RawHDRInputAEParam.DigitalGain | Process+2f0; 3e8610..3e862c |
| RawHDRInputAEParam.AdrcGain | Process+2f4; 3e85a0..3e85bc; serializer name, not proof of Camera2 AdrcGain tag equivalence |
| RawHDRInputAEParam.ExpTime | Process+138; 3e8630..3e864c |
| RawHDRInputAEParam.ExposureVal | Process+128; 3e8650..3e866c |
| RawHDRInputAEParam.ShortGain | Process+134; 3e8710..3e872c |
| RawHDRInputAEParam.LuxIdx | Process+c0; 3e8698..3e86b8 |
| RawHDRInputAEParam.LinearGain / NormalFlag | Both literal 1, not measured metadata |
| InputParam.InputNums | Literal 1, not the ZSL/bracket frame count |
| ToneInfo | Runtime tone mode, selected effect XML, gamma and scene/LUT descriptors; LUT contents are intentionally not dumped by this collector |

Compare the real runtime snapshot against the recovered field bindings before
enabling the worker call. A JSON sample alone does not finish creation-context
initialization, missing scene/LUT transport, log encoding, dynamic model routing,
or the scheduler's connection to real capture. These remain implementation work,
not reasons to ask for the already supplied libraries/models again.

The next supplied `vivo-tce-json-RfbqHBhE.tar.gz` again came from the old
terminal-read version: `timeout-or-eof`, no uptime records, restored after
20 seconds, no JSON. The corrected collector is therefore delivered under
a distinct `_v2.sh` filename, prints its version at launch and saves
`collector-version.txt` in each archive to make version identification explicit.

## Successful live observation, 2026-09-21

`scamera-tce-trace.qgZIDuDt.tar.gz` records paired Create/Process, SetParam
keys 4 and 8, and Process return 0 after 878 ms. All twelve configuration
paths were read, including `/vendor/camera3rd/nti/nice_tce/xml/MainCamera/Common/NiceTceEffect.xml`.
This proves stock execution, not a working SCAMERA port. Pointer values are
process-local and cannot be replayed. Existing archived XML files include the
active effect file. The dump directory is relative (`data/vendor/camera/dump/...`)
and the dump property was 0 during collection.

The pinned donor SetParam jump table at 0x4c551 routes key 4 to 0x3976f8
(4-byte load), and key 8 to 0x397784 (copies bytes 0..0x54, including an
unaligned 8-byte load at +0x4d). Collector v3 copies only these verified
payload extents and the Create GPU binary path at +0x100. These were not
recorded in v2. Image pixels, LUT contents and pointer-backed scene structures
remain absent; this trace is not a complete replay fixture.

### v3 observation: QLpODGVk

The v3 trace records Create, SetParam 4/8 and Process status 0 (716 ms).
GPU binary/cache path is `/data/vendor/camera`. Key 4 payload is int32 0.
Key 8 payload is 85 bytes: `2026-09-21 04:07` followed by zero bytes. This
observed timestamp is not a tone-strength parameter or a reusable constant.
All thirteen path reads succeeded. The session wrapper now resolves the
verified SetParam export and supports these two copying branches explicitly,
with error checks and no generic pointer-retaining SetParam interface.
Host tests verify order, payloads, missing symbol and failure at either key.
The capture worker still does not call original TCE: the input RGB encoding,
pointed-to LUT/scene structures and buffer ownership must be implemented and
validated first. The supplied trace does not contain their payloads.

### Color LUT ownership

The QLpODGVk Process argument has edge 33 at +0x370 and 107811 elements
at +0x374. TCE's serializer at 3e685c..3e691c computes edge cubed times
three and reads RGB triples using ldrh with two-byte element spacing.
Thus the payload pointed to at +0x378 is 215622 bytes of uint16 samples;
the trace contains none of these samples. Values and channel ordering must
be preserved, not regenerated as an identity table.

vivo-nice-tce-lut.h owns this payload and binds its live address, edge
and count without altering adjacent Process fields. Copy/move are disabled.
The caller must retain it through Process and session destruction; capture
is not connected yet. Host tests cover the observed extent, exact samples,
guard bytes and malformed or overflowing dimensions. The analyzer now
reports LUT size, same-handle SetParam payloads and call duration.
This still cannot replay the captured stock invocation.

### v4 payload capture (awaiting phone validation)

The previous traces cannot supply LUT samples or RGB comparisons. v4 now
captures packed input/output RGB16, the verified uint16 LUT and recovered
face arrays via bounded chunks in injector stdout. No new filesystem policy
rules are required for this transport; existing attachment permissions still
apply. Other opaque pointers and segmentation masks are explicitly excluded.
A completed v4 capture is additional evidence, not yet a complete replay.
The actual agent and extractor pass host mock tests for extents, payload
identity, missing chunks, truncation and path rejection. No phone execution
of v4 or photographic TCE integration is claimed.

### v4 phone validation: DAWMDHB0

The supplied `scamera-tce-trace.DAWMDHB0.tar.gz` contains a complete v4 trace
(303988410 uncompressed bytes; SHA256
`e749341cbc050240ae17198e282def4b8fd345deb067e57af9fd743cb99ac6ba`).
The pinned donor matches. Create and SetParam 4/8 are paired with Process,
which returns status 0 in 649 ms, excluding payload-copy overhead. The final
`finished` record is present. SELinux remains Enforcing with the same policy
hash before/after. Existing property-read AVCs do not establish call failure.

The streaming extractor verified every chunk offset, extent and payload end:

| Payload | Bytes | SHA256 |
| --- | ---: | --- |
| Input RGB16, 4098 × 3074 | 75583512 | `35b59d5508986f8a24209d59d7f2c06cb312151410290fa4fa7b41c4aa6da971` |
| Output RGB16, 4096 × 3072 | 75497472 | `14669a93ec6bed2dcb9601874187ec8ccc3aadcee5f3e323bf2b857b04436feb` |
| LUT, 33³ × 3 uint16 | 215622 | `6b1db945b5a4b0b6e8e58c9a87411e9edcf81b382639fff5618b7359708a9c9b` |

Observed input channel ranges are [4792,9383], [5272,9539], [4703,9262].
Output ranges are [0,15499], [0,16383], [0,14104]. LUT samples lie in 0..16383.
These are measured sample ranges, not proof of a universal transfer function.
The first LUT coordinate varies channel 0 fastest, then channel 1, then channel 2;
retain exact samples rather than replacing this near-identity LUT with an identity.
No face arrays were emitted because this call's face count is zero.

Known Process fields: lux index 287, digital zoom 1, exposureVal 0, short/analog
 gain 9.36977767944336, exposure 19.99722671508789 ms, digital gain 1,
serializer ADRC field 1, sensor mode 1. They describe this shot only.

This closes the missing RGB/LUT fixture gap. It does not close opaque scene
pointer ownership, segmentation-mask context, linear-to-log routing, or an
on-device SCAMERA native-call comparison. Do not replay recorded addresses or
ship this per-shot LUT as universal calibration. No app integration, stock ZSL
scheduler equivalence, or independent native denoise/sharpen controls are
established by this observation. Repeating the same v4 capture is unnecessary.

The analyzer now verifies payload hashes/offsets and reports completeness,
including a captured LUT instead of the previous hardcoded false. A truncated
trace yields diagnostics and preserves evidence of earlier complete payloads;
it never becomes a complete RGB pair. `check_analyzer.py` covers those cases.
