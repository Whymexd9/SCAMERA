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
