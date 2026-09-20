# PD2454 NICE tuning tables

`vivo-nice-tuning.h` ports the verified table selectors, with generated data in
`vivo-nice-tuning-data.h`. **These selectors are not yet connected to capture or
TCE processing.** The current capture/worker remains the fixed forward graph.

The supplied `/odm/etc/vas/nice2.0` configs are now available. Missing tuning
files are no longer a blocker. The five source SHA256 values are embedded in
the generated header. Donor `libvivo.vaf.tunning.so` has SHA256
`48c3b6a7b8b971c1e8327d6dfa1302cf5e5b5a623cdd22626e3f728202297186`.

## Preserve duplicate object members

These files use cJSON objects as ordered containers: camera groups have
repeated empty-string keys, and threshold/CCM/LUT tables have repeated `value`
keys. A conventional dictionary parser discards entries. The extractor retains
object pairs separately from JSON arrays and rejects ambiguous unique fields.
It extracts all 121 configured single/dual HDR rows, not just the tele group.
Dual LivePhoto rows have a three-element EV vector; the extractor preserves it
and does not enforce an invented twice-the-count relationship.

## Verified selection rules

| Function | Original code | Recovered behavior |
| --- | --- | --- |
| `normalFrameCount` | `16a710`, helper `16a7c8` | First containing half-open zoom interval; if none, index zero. Mode 2 uses `_r`, tele special 1 uses `_t_4x4_r`. Unknown camera uses normal wide counts. Return 14 becomes 10, except direct stagger branch. |
| `usesDualHdrGroup` / group lookup | `16b284` | Existing seamless state 4 or 0x500 AND preview HDR version at least 2 selects dual; other states select single. Missing camera group falls back to camera 0. |
| `selectTceEffect` | `170078` | First threshold strictly above zoom; if none, `NiceTceEffect.xml` with false match status. |
| HDR row decoding | `1a3fd0` | Copies two integers and the entire variable-length EV vector. Missing member leaves the native initialized row untouched. |

The helper's float input at +4 is in the **zoom** domain. Packed Android APS2
relocations at `1d0898..1d08b0` resolve to `g_zoom_trigger_{m,u,t,p}`. It must
not be supplied lux or an ISO-derived estimate. Mode and special enum values
retain their native identities; application enums are not substitutes.

The tele TCE filename thresholds are:

| Zoom input | Filename |
| --- | --- |
| below 3 | `NiceTceEffect.xml` |
| 3 to below 10 | `NiceTceEffect_3X.xml` |
| 10 to below 30 | `NiceTceEffect_10X.xml` |
| 30 to below 999 | `NiceTceEffect_30X.xml` |
| 999 and above | `NiceTceEffect.xml`, unmatched fallback |

These are the native selector's zoom inputs, not a proven mapping of the app's
UI zoom. Filename selection does not establish the complete directory, scene
style, Create arguments, or a working TCE image call. Matching filenames exist
in the supplied donor tree.

No selector changes the sensor mode. The selectors only read existing state.
Nonfinite zoom rejection is adapter validation rather than native behavior.
Missing HDR variants return null: a native default has not been reconstructed
for those rows, and must not silently become an all-zero capture schedule.

## Verification

```
python tools/extract_vivo_nice_tuning.py /path/to/vas/nice2.0 \
  --header app/src/main/cpp/vivo-nice-tuning-data.h
python tools/check_vivo_nice_tuning.py /path/to/libvivo.vaf.tunning.so \
  /path/to/vas/nice2.0
```

The oracle runs the complete unmodified ARM64 selector/reader functions. It
shims cJSON access, formatting, allocation and memory imports, resolves donor
data relocations, and supplies the extracted config values. It compares 3240
normal-frame results, 44 TCE filename results, 121 HDR row reads (including
untouched vector tail bytes), and 120 native HDR group choices. It also verifies
that regenerating the header produces the checked-in bytes.

## Remaining integration boundary

The capture controller still drains up to four normal ZSL frames and submits a
manual long/short tail. `VivoNiceBurst` still adapts to seven graph inputs. These
must change together with scene decision, exposure scheduling, reference
selection and model selection; changing only the count would mismatch inputs.
The table reader does not calculate gain/shutter, infer HDR/motion variants, or
choose a network. `NiceHdrQuery` requires actual scene/AE outputs.

TCE Create/Process still need complete initialized arguments, per-shot image
and auxiliary metadata, GPU processing and correct output ownership. The newly
verified filename selection does not close those dependencies. No APK was
built and no claim of photographic artifact removal follows from these tests.
