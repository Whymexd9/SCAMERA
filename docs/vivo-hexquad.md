# HP9 HexQuad: bundled diagnostic and stock normal VST

Status: the settings check runs the HP9 x1/x2 models with their bundled runtime.
**HexQuad is not connected to capture yet.** A successful graph call or a passing
synthetic chart alone is insufficient to enable real-frame processing. The old
TELE capture backend and its acceptance threshold remain unchanged.

## Evidence and corrected tensor contract

The supplied `libvivo_nice_cre.so` has SHA256
`41b277753f7fedbe4dda1e1c4b76d2086d6e79768904d8a4922b48a0e023e76e`.
Its loader at ELF VA `0x3a7270` decodes embedded OpenCL using a byte substitution
table at `0x487a98` (relocation `0x47acb0`). This exposes the stock sparse-RAW
packing and inverse-LUT operations. `niceKernels.bin` is an optional compiled
OpenCL cache, not a missing neural model. Vendor source and binaries are not
committed to this repository.

`NiceCREConfigHexQuad.xml` names HP9, six input frames, `s2d=0`, `UseClipMap=0`,
VST and overlap 16. The x2 model name does not identify the user's 4x ISZ mode;
exact mode selection and crop coordinates still need verification.

The outer VDNN wrapper is not the actual QNN buffer contract. The newly supplied
QNN System ARM64 parser was executed in emulation and returned:

| Model | QNN graph | Actual input NHWC | Actual output NHWC | VDNN output |
| --- | --- | --- | --- | --- |
| hex big x1 | vmcc_1000_quant_8w16a32b | 1×288×288×18 | 1×288×288×3 | 1×288×288×3 |
| hex big x2 | vmcc_2000_quant_8w16a32b | 1×288×288×18 | **1×576×576×3** | 1×544×544×3 |

Both actual graph inputs/outputs are FLOAT32 (`0x232`), Tensor V1, Graph V3,
Binary V3. The outer VDNN type code 9 must not be treated as a QNN enum. The
wrapper's 544 size is not the x2 allocation size. Interpreting it as a valid
central crop is still a hypothesis; the correct spatial placement remains open.
ROI Quad x1 (wrapper input 544×544×12) is a separate model, not a 4×4 substitute.

`tools/inspect_vivo_hexquad.py` still reports the **wrapper** metadata and safely
extracts pinned private contexts. Their hashes are pinned in `HEX_FILES`.
The on-device worker validates the actual graph descriptors again before use.

## Confirmed stock boundary operations

The stock HexQuad warp tags each sample using the **source coordinate's** 8×8
CFA phase, after registration. Low 14 bits hold RAW data; the high two bits are
R=0, G=1, B=2, hole=3. Destination CFA is not valid for tagging warped samples.

`singleFrameNetPreProcessFP32` / `FP32_GeneralNetPreprocessCL_6` generate 18
channels per spatial pixel: R/G/B per frame with only the measured colour set.
They apply per-frame, per-colour VST LUTs, ushort shift, scale and clipping.
There is no Morton shuffle, space-to-depth or preliminary RGB interpolation.
Our checked implementation tests holes before lookup to avoid a nonexistent
fourth LUT plane. It accepts registered tagged frames; it does not align them.

`FP32_FP32_GeneralNetPostprocessCL` scales and offsets network values, clamps the
LUT index to 0..65535, truncates to ushort, right-shifts, then applies separate
RGB inverse LUTs. Our boundary helper implements the non-overlap branch. Stock
weighted overlap must accumulate before final clipping and is not implemented.
Neither the TELE square() decode nor an XML quantization scale is blindly reused.

## Normal-exposure VST validated against stock

`vivo-hexquad-vst.h` implements only this explicit subset: equal exposures,
unity WB, black already subtracted, `hdrvstmode=1`, no AVST,
`vstBaseISOMode=0`, norm coefficient 1. HP9 XML noise coefficients are used.

Stock normalization (`0x2d8850`), VST (`0x2da2c0`), IVST (`0x2dc068`) and noise
profile (`0x2a9d48`) were executed under AArch64 emulation. At ISO 50, 100, 400
and 800, all 49,152 forward entries and all 65,536 inverse entries per channel
match the host implementation exactly. The norm is derived at **ISO 50** and
is 125.07814025878906; normalizing independently to one at every ISO is incorrect.

This does not establish Android-to-stock ISO conversion, frame exposure ratios,
real black calibration or WB configuration. Diagnostic charts use explicit
synthetic linear RAW and this limited profile.

## Supplied runtime: 2.29.8, not 2.28

The models were compiled with `v2.28.0.241029232508_102474` (Core 2.21).
The supplied `/vendor/npu/lib` runtime reports **v2.29.8.250123143957_105779**,
Core **2.22.0**, System **1.2.0**. Its provider registration/getBuildId and System
metadata parser were executed in emulation. The adapter requires the exact
pinned build. A successful metadata parse does not prove NPU execution.
Qualcomm does not promise general ABI backward compatibility:
[API overview](https://docs.qualcomm.com/doc/80-63442-10/topic/api_overview.html).

The two runtime generations live under separate APK asset prefixes and run in
separate fresh workers. No Vivo algorithms/models are loaded from firmware paths.
Only the platform FastRPC driver remains device-specific. V79 contexts are not
portable GPU models or a promise of support on other Qualcomm generations.

## On-device diagnostic and remaining gates

Open **Vivo Neural — проверка → Проверить HP9 HexQuad**. It performs 24 executions:
x1/x2, ISO 100/400, four neutral/coloured flats plus two independent RGB ramps.
Six identical noiseless frames represent a stationary synthetic burst only.
They are not a substitute for real aligned frames during capture.

The report gives linear-RGB RMSE, maximum error, channel means and interior
range violations. A conservative 32-input-pixel border is excluded from scores;
NaN/Inf/unwritten values remain fatal anywhere. A chart passes at RMSE <= 0.045
with no interior range violations. `HEX SUMMARY` distinguishes PASS from FAIL;
completion of diagnostics never installs or enables a capture mapping.

Next gates: inspect the phone report; establish actual ISO/exposure/WB and burst
ordering; verify spatial phase and valid x2 crop; integrate registered RAW bursts
and overlap; check motion, seams, memory and timeouts before enabling capture.

## Building and checks

The private packager now needs both runtime directories:

```sh
python tools/package_vivo_neural.py --template template.apk \
  --bundle-dir /path/to/tele-assets --hexquad-dir /path/to/hexquad-assets \
  --apksigner /path/to/apksigner.jar --output SCAMERA-HexQuad-check.apk
```

The HexQuad directory contains the two extracted contexts (`hexquad-x1-v79.bin`,
`hexquad-x2-v79.bin`) and the four supplied QNN libraries. Hashes, distinct prefixes,
APK signature/CRC and the standalone ARM64 worker are verified by the packager.

CI checks sparse RAW boundaries, stock-derived normal VST fixtures, positive and
negative chart fixtures, stable input buffer addresses and malformed containers.
Private exhaustive stock comparison (requires pyelftools and unicorn):

```sh
python tools/check_vivo_hexquad_stock.py --library /path/to/libvivo_nice_cre.so \
  --output-dir /tmp/stock-hp9-luts
g++ -std=c++14 -O2 tools/check_vivo_hexquad_vst.cpp -o /tmp/check-vst
/tmp/check-vst /tmp/stock-hp9-luts
```

Host checks do not emulate HTP weights or prove camera image quality.
