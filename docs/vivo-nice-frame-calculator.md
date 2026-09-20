# NICE frame calculator and scene rules

The portable calculator now connects the recovered tuning-table selector to
`NiceDetectResult`. This is a complete port of **one native planning stage**,
not a complete scheduler connected to Android capture. It does not replace
`CaptureController`'s current fixed forward series or enable TCE processing.

## Full packing function

Donor `libvivo.vaf.algo.scenedetect.so` SHA256:
`3268e550541179650a02d7b94895db2430f244c54da2b34bffafbdfdba8fb226`.
`NICEFrameCalculator::packedCaptureFrameInfo` is `17ad4..182ec`.

Inputs are the existing HDR group pointer (PreviewDetectProcParam+3bf0), native
detect mode (+3bec), dualRawShotType (+3bfc), needImageEcho (+3bf8), and
moreEV0Frames (+3bf9). Only dualRawShotType equal to 1 takes the dual group
layout. `calculateNiceFrameInfo` obtains that state from the previously verified
group selector. No sensor mode is changed.

The donor clears 0x190 bytes at PreviewDetectProcParam+3c14 before packing:

| Relative offset | Content |
| --- | --- |
| 0 | flags |
| 4 | forwardInputNum, row's first integer |
| 8 | backwardInputNum, accepted entries from row's second integer |
| 0x0c | primary backward EV array |
| 0xcc | paired-short backward EV array |

Gain and shutter storage remains zero at this stage. Native forward/backward
names are preserved; their mapping to shutter time must be established at the
AE and request-producing stages, not inferred from the English names.

The single/dual jump tables select different structure members. Unsupported
modes take Binning. Missing camera groups fall back to camera 0. Missing table
members require native initialized defaults; the adapter returns an error rather
than inventing those defaults.

The constant at VA 6f08 maps table integers as follows:

| Table code | Packed native EV |
| --- | --- |
| -2 | -200 |
| -1 | -100 |
| 0 | 0 |
| 1 | 101, image-echo sentinel |
| 2 | 100 |
| other | 0 |

These numbers are not exposure multipliers or Camera2 compensation steps.
When forwardInputNum is zero, code 1 is removed if image echo is disabled.
Otherwise it is retained and marks flag 0x40000. For a dual row, the paired
short entry uses the original index plus the original row count; filtering the
primary array does not shift that source index.

If forwardInputNum is zero and moreEV0Frames is enabled, the donor inserts
three zeroes before primary EVs and increases backwardInputNum by three. It
**does not shift the paired-short EV array** in this block. This behavior is
preserved instead of silently correcting what could be a branch constraint
imposed by earlier scene decisions.

Flags use 0x20000 for detect modes 17..22, otherwise 0x10000, plus the echo bit.
All other bytes remain zero. The adapter adds bounds checks, including the
three-frame prefix. Some supplied dual LivePhoto rows lack the second half of
the EV vector; they are rejected rather than read beyond their allocation.

## Complete small scene decisions

Donor `libvivo.vaf.system.so` SHA256:
`3f4ee25636fb04023c338ded9f087734ab8aeb429c4e50adf19a822e5398c188`.

| Port | Native function | Behavior |
| --- | --- | --- |
| `classifyHdrMotion` | `29ab8c`, `29ac44` | Shaking1: value above upper threshold. Shaking2: value above lower and at/below upper threshold. |
| `niceHdrMoreFrames` | `29a920` | Off tripod: truncated lux exceeds threshold. On tripod: threshold is exactly -1. |
| `niceHdrMoreEv0` | `29a9d8` | Tele camera, lux above threshold, eligible UI modes, dualRawShotType other than 1; binning and quad use different zoom boundaries. |

Motion value is read from PreviewDetectProcParam+3ae0 and thresholds from the
object at SceneDtProcParams+178. The integer motion level at preview+11d0 is
logged but does not participate in these two comparisons. An app gyro norm or
RAW alignment displacement is **not** a proven substitute for +3ae0.

MoreEV0 permits UI modes 1,7,13,39,70,74,77. Binning's zoom interval includes
both ends; quad mode 2 excludes its upper endpoint. Other quad modes return
false. These are read-only decisions about existing state, not mode switching.
All required float inputs must be finite in the adapter.

## Verification and remaining work

```
python tools/check_vivo_nice_frame_calculator.py \
  /path/to/libvivo.vaf.algo.scenedetect.so \
  /path/to/libvivo.vaf.tunning.so /path/to/vas/nice2.0
python tools/check_vivo_nice_scene_rules.py /path/to/libvivo.vaf.system.so
```

The calculator test executes the original complete function and compares all
400 output bytes and guard bytes: 1844 valid cases; 76 missing or unsafe
configurations rejected without publishing partial output. The oracle obtains
member-name/offset mappings from the original tuning reader instructions and
jump targets from the original scene library, independently of the C++ arrays.

The scene test executes the original complete small functions: 728 motion
pairs, 120 more-frame decisions and 2230 more-EV0 decisions, including threshold
and zoom boundaries. No scene decision is replaced with a test shim.

Still unconnected: the full scene decision that chooses detect mode, provenance
of all its measurements, exposure/gain calculation, Camera2 request submission,
matching variable-length captures to a compatible model, and TCE Create/Process
with complete arguments and correctly owned output storage. The existing
seven-input forward graph cannot accept arbitrary table counts. These tests
are not device photographic validation and do not establish artifact removal.
