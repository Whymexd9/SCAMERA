# CRE to TCE boundary (PD2454)

This is a recovered ABI boundary, **not a connected photographic TCE path**.
The current capture worker still returns reconstructed linear RGB to SCAMERA.
Do not use these declarations to enable TCE with fabricated scene/LUT data.

Donors:

- CRE SHA256 `41b277753f7fedbe4dda1e1c4b76d2086d6e79768904d8a4922b48a0e023e76e`.
- TCE SHA256 `9f5deac3bc68fc86fcf16b98f43c232a9642bc309c7d5d42c88d6a4b596b892d`.

## Calling convention and sizes

CRE loads TCE's exported functions at `0x38a670`. Its wrappers call Create
at `0x38a3ec`, Process at `0x38a454`, SetParam at `0x38a518`.
The actual TCE node calls Create at `0x38c8bc` and Process at `0x38dd38`.

| Operation | Established contract | Evidence |
| --- | --- | --- |
| Create | Argument pointer in x0; returns handle | CRE `38c8b8..38c8c4` |
| Create argument extent | 0x4c8 bytes copied, not 0x220/0x228 | TCE `384f50..384f5c`, `3855c8..3855e8` |
| Process | x0 argument, x1 output structure, x2 handle | CRE `38dd2c..38dd38`; TCE `391374..391398` |
| Process argument extent | 0x6d0 bytes copied | TCE `39165c..391668` |
| Extended image descriptor | 0x78-byte spacing; handle at +0x70 | CRE output descriptors at node+10a0 and +1118 |
| Output structure | At least 0x2e0 bytes, **exact sizeof not established** | TCE reads extra-output pointer at +0x2d8 |

The output is not a single image descriptor. TCE may mutate native handles
at output+0x70 and output+0xe8, writes a scalar at +0xf0, uses image descriptors
at +0x170/+0x1e8/+0x260, and reads a pointer at +0x2d8. Allocating a 64-byte
motion descriptor or even a single 120-byte extended descriptor is invalid.
The 0x6e0 gap between CRE node members is not yet proof of the output ABI size.

Process also doubles argument+0x104 when argument+0x384 is zero, **after**
copying the argument into its context. The first field is logged as digital
zoom and the second as SensorMode. This is a metadata transformation, not a
reason to switch capture sensor modes. Reusing the same mutable argument
across calls without reconstructing it could repeatedly double zoom.

Image offsets established by callers/consumers are declared in
`vivo-nice-tce-contract.h`. The block at +0x60 remains unnamed. Integer
addresses in the layout are process-local; this is not a file transport ABI.

## Log exposure before TCE

CRE `0x391f88..0x39209c` consumes five floats from the node's block at +0x1780:
reference gain, delta EV in thousandths, log ceiling, digital
gain, and DRC gain. The third float is used by LogConvert, not by
this exposure function. The result is evaluated with these rounding points:

```
delta = abs(float(deltaEvMilli / 1000))
reference = float(log(double(referenceGain)) / ln2)
digital = float(log(double(digitalGain)) / ln2)
drc = float(log(double(drcGain)) / ln2)
result = float(float(float(delta + reference) + digital) + drc)
```

The donor's double ln2 is stored at CRE VA `0x59b48`. Combining gains into one
product or replacing the computation with float log2 changes rounding.
Invalid/nonpositive gain rejection is added boundary validation; it is not
claimed as native behavior.

## Logarithmic image input

The decoded CRE OpenCL fragment at `0x16c993` contains `niceLog`, including
float and unsigned-short input variants. The source is recovered by
`tools/decode_vivo_nice_kernels.py`; fragment SHA256 is
`27b1c62d3c081065b7569c409fbbb2c90f587dfdbeb3802f383913eaab7230da`.
CRE `0x38e208..0x38e2dc` supplies its arguments:

- exposureScale = float(exp2(double(float(logExposure + 14))) - 1).
- logMaximum = the third float in the exposure block.
- zeroCode = trunc(min(double(logMaximum), 236.59423763112798)).
- oneCode = trunc(min(double(logMaximum), 354.891356446692)).
- inputScale = 1 for float input (bit depth 32), 1/65535 for uint16 input.

For each RGB channel, `niceLog` calculates
`value = input * inputScale * exposureScale`, then truncates
`clamp(native_log(value) * 1024, 0, logMaximum)` to uint16. It replaces the
result with zeroCode when value is exactly zero and oneCode when it is exactly
one. Thus normalized linear RGB cannot simply be passed to TCE as uint16.
The input bit-depth flag 32 means normalized float, not an unsigned 32-bit
integer range. The two special codes must not be replaced by zero.

`logEncoding()` ports argument preparation and rejects unsupported formats,
nonfinite/out-of-range ceilings, and nonpositive/overflowed exposure scales.
It does not replace the GPU native_log with host libm or guess the missing
scene gains. The native kernel's pixel accuracy on Adreno is not host-tested.

## Verification

```
python tools/check_vivo_nice_tce_contract.py /path/libvivo_nice_cre.so /path/libvivo_nicetce.so
```

604 complete original ARM64 log-EV executions and 300 original log-encoding
argument preparations match C++ bit for bit; 18 malformed exposure inputs
are rejected. Two original copy blocks establish
argument sizes, with guards checked around the copies. Eight original handle
mutation cases verify both output handle offsets and null preservation.
The ARM emulator supplies host libm for imported log/exp2 and implements
imported memcpy. Argument-preparation blocks stop before GPU setters.
No instructions in the tested algorithm blocks are replaced.

Remaining before capture integration: complete creation parameters and output
ownership, execution of the recovered input encoding, scene/mask/color/LUT
metadata routing, and processing/release of additional outputs. These tests
do not establish complete TCE execution or removal of photographic artifacts.
