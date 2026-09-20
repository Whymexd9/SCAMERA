# PD2454 RAW metadata and remaining tuning inputs

The supplied `libvivo.algo.metadata.so` is 154536 bytes, SHA256
`83f1f950456f62396530f80ac393eaeaa0b4602427a41f59650137cd9f76c362`.
Its six sections contain 1249 tags. `read_vivo_algo_metadata.py` extracts
the schema using the exported section names, bounds and tag-info symbols.
`check_vivo_nice_ae.py` runs the original InitializeAlgoTagInfo, GetTagName
and GetTagType functions to verify every extracted name/type. The loader
resolves the three data-symbol GOT entries used by initialization; no
algorithm instructions are changed.

## Actual RAW AE sources

VCF adapter SHA256:
`f5fdf379e0316fee77a0ff7760ef247881e4dfade98f3a7bc8b9b8f93c12e901`.
VAF system SHA256:
`3f4ee25636fb04023c338ded9f087734ab8aeb429c4e50adf19a822e5398c188`.

VCF `convertRawshotMetadataIn` reads 0x8c bytes (35 float slots) of
`vivo.control.Vivo3rdAlgoAECFrameControl` at `dcc88..dcd28`.
`dceac..dd030` copies/converts them into CameraAlgoParams; the outer
`convertShotMetadataToAlgoMetadata` publishes those fields to internal tags.
All indices below are zero-based. They are not independent bracket frames.

| VAF ID/name | External source | CameraAlgoParams offset | VCF publish |
| --- | --- | --- | --- |
| 0 / system.luxindex | AEC[0] | 0x81c | cb41c..cb460 |
| 7 / system.flash_real_gain | AEC[2] | 0x860 | cba78..cbabc |
| 0x16 / system.isp_gain_short | AEC[13] | 0x878 | cb790..cb7cc |
| 0x22 / system.exp_time_short | float(AEC[14] / 1000000.f), milliseconds | 0x884 | cb7d0..cb814 |
| 4 / system.adrc_gain | AEC[6], with capture override below | 0x890 | cb5b0..cb5ec |
| 0x3015f / parameter.raw_hdr_capture_drcgain | vivo.parameter.rawHDRCaptureDrcGain, float | separate query/shot value | cae10..cae6c; cb398..cb3d8 |

All six schema entries have type 2, count 1. The VAF producer reads tags
0x22/7/0x16 into expTime/shortGain/digitalGain, then computes analogGain as
shortGain/digitalGain (`33d134..33d14c`). When manager+0xf8 is 1, DRC comes
from max(raw_hdr_capture_drcgain, 1.f), otherwise tag 4. This branch is not
replaced with an arbitrary test of SCAMERA's merge setting.

The non-HDR ADRC capture override at `dce34..dceac` applies when the first
integer of `vcf.parameter.seamlessMode` is 4 or 0x500 and
`vivo.feedback.aeAdrcGainCapture` is present and positive; otherwise AEC[6]
is retained. Reading this metadata does not request a sensor-mode change.
The native helper requires the mode to be available before selecting this
non-HDR DRC; a missing mode is not fabricated. HDR selection independently
requires the actual HDR DRC value. Finite zero/negative HDR inputs are
preserved for the original clamp, while missing/invalid values stay flagged.

256 cases execute the original RAW copy/conversion, internal-tag publish
blocks, analog-gain division and HDR clamp. The C++ adapter agrees bit for bit.
This verifies baseline AE preparation, not all subsequent IC/flash overrides.

## Connection to real RAW transport

`VivoNiceBurst` snapshots `VivoNiceAe` after ordering its seven RAW inputs.
Every snapshot reads the timestamp-matched CaptureResult retained by its own
ImageFrame. Vendor arrays are copied so later mutation cannot alter a burst.
The former standalone lux/ADRC snapshot remains available for diagnostics;
it is not silently substituted for this recovered AEC source.

NCH v7 keeps bytes 0..159 unchanged, then adds seven 176-byte AE records.
RAW planes begin at byte 1392. The worker reads and reports each slot's AE;
it does not yet pass these fields to a TCE image call.

| Record offset | Value |
| --- | --- |
| 0 | RAW timestamp, uint64 |
| 8 | flags, uint32 |
| 12 | first seamless-mode integer, int32 |
| 16..155 | 35 original AEC float slots |
| 156 | HDR DRC, float |
| 160 | capture ADRC, float |
| 164..175 | reserved zero bytes |

Flag pairs 1/2, 4/8, 16/32 and 64/128 represent present/invalid AEC,
HDR DRC, capture ADRC and mode, respectively. Both bits of a pair are illegal;
neither means absent. Absent/invalid payloads have zero storage. Only the
recovered float inputs of AEC are validated, because other slots are opaque.
The reader checks flags, values, reserved bytes, total length and reference
timestamp. Versions 1..6 remain readable without fabricating vendor AE.

The actual Java snapshot writer is exercised through the native burst reader:
missing/invalid values, alias-independent sources, immutable copies, wrong
timestamps, byte order, corrupt/truncated transport and all seven RAW offsets.

```
python tools/read_vivo_algo_metadata.py /path/libvivo.algo.metadata.so
python tools/check_vivo_nice_ae.py /path/libvivo.algo.metadata.so /path/libvivo.vas.adapter.vcf.so /path/libvivo.vaf.system.so
python tools/check_nice_reference_metadata.py
```

## Dynamic plan configuration dependency

`NICETunning::getNiceHDRFrameNum` in `libvivo.vaf.tunning.so` at
`16b284..16b4f0` selects a configured HDR frame group by the key in preview
params+0x44c, with key 0 as fallback. It chooses between separate configuration
maps according to existing mode and preview-HDR-version metadata. A missing
fallback entry raises the donor's map error; it is not a fixed 4/4/6 policy.
`getNiceFrameNum` (`16a710`) uses lens/lux-indexed configuration vectors via
`16a7c8`, with separate normal/other-mode vectors. These selections do not
by themselves implement the complete scene/AE estimator.

`NiceConfig::readNiceAllConfig` (`1a9b24`) constructs names for
nice2.0_params, nice2.0_master, nice2.0_ultra, nice2.0_tele,
nice2.0_periscopic and the four niceLutExtendConfig lens files.
The supplied phone inventory confirms them under `/odm/etc/vas/nice2.0`,
plus `/odm/etc/vas/scenedetect/ScenedetectTuningParams.json` and
`/odm/etc/vas/frameNumCalculator/frameInfo_baseCfg_tunning.json`.
These contents are absent from the inspected donor files and archives,
including vivo-vcf-libs and vivo-nr-analysis-20260917-031320.
The inventory lists 41 files / 2579785 bytes for all `/odm/etc/vas`.
Collecting that directory and `/odm/etc/vaf` supplies configuration context
without running or modifying the camera service.

Still unfinished: scene-driven plan production and Camera2 submission,
complete TCE context/image/auxiliary-data ownership and the image call.
No APK or device image-quality equivalence is asserted by these host tests.
