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
