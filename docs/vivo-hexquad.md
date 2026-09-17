# HP9 HexQuad reconstruction: verified boundaries, not a capture backend

Status: research and independently implemented preprocessing primitives. **Not
connected to capture, not an enabled remosaic option, no image-quality claim.**
The existing TELE capture acceptance threshold is unchanged. NPU execution of
the TELE graph succeeded on the user's phone, but its coloured charts did not
establish a correct remosaic mapping. A successful graph call alone is insufficient.

## New evidence from the supplied firmware

`libvivo_nice_cre.so` SHA256:
`41b277753f7fedbe4dda1e1c4b76d2086d6e79768904d8a4922b48a0e023e76e`.

This library contains encoded OpenCL source. Its program loader at ELF virtual
address `0x3a7270` performs byte substitution using the 256-byte table at
`0x487a98` (referenced via a relocation at `0x47acb0`). Decoding the rodata reveals
the stock warp, input packing and output LUT operations. `niceKernels.bin` is a
compiled OpenCL cache; the loader can build it from the embedded source. It is
not a missing neural model. Vendor source/binaries are not included in this repo.

The `NiceCREConfigHexQuad.xml` normal path names the HP9 HexQuad models, six input
frames, `s2d=0`, `UseClipMap=0`, VST enabled and overlap 16. These are substantially
different from the previously inspected `T2Q_TELE_3x_v1p9_frozen` model. The model
name `x2` does not identify the user's 4x ISZ shooting mode; exact mode selection
and crop coordinates must be verified separately.

`tools/inspect_vivo_hexquad.py` reads the pinned VDNN FlatBuffer wrappers without
loading a library. Measured wrapper tensors:

| Model | Input NHWC | Output NHWC | VDNN type code | Context offset / bytes |
| --- | --- | --- | --- | --- |
| HP9 hex big x1 | 1×288×288×18 | 1×288×288×3 | 9 | 0x464 / 7931360 |
| HP9 hex big x2 | 1×288×288×18 | 1×544×544×3 | 9 | 0x464 / 9758632 |
| HP9 ROI quad x1 | 1×544×544×12 | 1×544×544×3 | 4 | 0x61c / 5704776 |

These are wrapper codes, **not QNN datatype enum values**. QNN System must report
the actual graph tensors, datatype, quantization and client buffer sizes before
execution. The x2 output is 544, not 576; do not infer tile placement just from
the scale factor. ROI Quad is a separate path and is not an HP9 4x4 substitute.

## Confirmed input operation

The stock `vivoRawBackwardWarp2CanvasOrderHexQuadBufferCL` uses the source pixel's
8×8 CFA phase after registration. Tagged RAW stores a 14-bit sample and a two-bit
colour tag (R=0, G=1, B=2, hole=3). Applying the destination CFA instead of the
sampled source CFA after motion would put samples into the wrong channels.

The sparse-RAW branch of `singleFrameNetPreProcessFP32`, called six times by
`FP32_GeneralNetPreprocessCL_6`, constructs **18 channels at every spatial pixel**:

- Three channels per registered frame: R, G, B; only the measured colour is set.
- Per-frame, per-colour VST table lookup for the 14-bit sample.
- Optional left shift with ushort storage, multiplication by an explicit factor,
  upper clipping; unmeasured colours and holes remain zero.
- No Morton shuffle, no 4×4 space-to-depth, no preliminary RGB interpolation.

`vivo-hexquad-preprocess.h` implements this sparse branch with checked views and
explicit LUTs/scales. It checks holes before lookup, avoiding the stock helper's
read of a nonexistent fourth LUT plane. It accepts already registered frames;
it does not generate a burst or perform registration. Duplicating one frame six
times is not equivalent to supplying a registered six-frame burst.

## Confirmed output operation

`FP32_FP32_GeneralNetPostprocessCL` scales/offsets each channel, clamps the LUT
index to 0..65535, truncates to ushort, right-shifts it and looks up a separate
inverse VST table for R/G/B. Its optional overlap branch multiplies by row and
column masks, accumulates into a base buffer, then clips.

The helper currently implements the **non-overlap** branch, accepting explicit
LUTs/scales and rejecting nonfinite values. It does not implement output buffer
placement, blending, QNN tensor conversion or Bayer reconstruction. In particular,
it does not square the output as the TELE adapter does. XML input/output scales
must not be blindly applied to an already dequantized float tensor.

CPU noise/VST construction has also been located (`0x2a9d48`, `0x2da2c0`,
`0x2dc068`). Exposure selection, normalization, white balance and inverse LUT
generation are not yet sufficiently validated to connect to capture. Explicit
LUT parameters are intentional; identity or guessed LUTs are not a valid model
calibration.

## Runtime prerequisite and remaining work

The contexts contain build string `v2.28.0.241029232508_102474`; conversion tags
identify QNN 2.28. `libvdnn.so` explicitly names `/vendor/npu/lib/libQnnHtp.so` and
`/vendor/npu/lib/libQnnSystem.so`. The previous APK bundles a different QNN 2.25
set from `/vendor/lib64/hw`. Forward compatibility of those particular builds
has not been established. Use the matching firmware runtime for the next test.

The supplied file inventory lists these files, which are not in the supplied
archives:

| File under `/vendor/npu/lib` | Inventory bytes |
| --- | ---: |
| libQnnSystem.so | 271568 |
| libQnnHtp.so | 2013936 |
| libQnnHtpV79Stub.so | 463136 |
| libQnnHtpV79Skel.so | 8607804 |

Read-only collection on the user's rooted phone (writes only the archive):

```sh
su -c 'tar -czf /sdcard/Download/vivo-hexquad-qnn.tar.gz -C /vendor/npu/lib libQnnSystem.so libQnnHtp.so libQnnHtpV79Stub.so libQnnHtpV79Skel.so'
```

These libraries are to be inspected, pinned and bundled in the APK, not loaded
from those firmware paths during capture. The platform FastRPC driver remains
device-specific. A V79 compiled context is not a portable GPU model or a promise
of compatibility with other Qualcomm generations.

After obtaining the matching runtime:

1. Read real QNN tensor metadata, verify datatype/quantization and SDK interfaces.
2. Complete VST/IVST and frame exposure/ordering against stock functions.
3. Validate neutral/coloured fields, ramps and spatial patterns on the phone.
4. Connect registered RAW bursts, checked tile placement and overlap; verify seams,
   motion, memory and timeout handling. Enable capture only after these gates.

## Reproducible checks

```sh
g++ -std=c++17 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined \
  tools/check_vivo_hexquad.cpp -o /tmp/check-vivo-hexquad
ASAN_OPTIONS=detect_leaks=0 /tmp/check-vivo-hexquad
python tools/check_vivo_hexquad_container.py
# Optional local integration; proprietary models are intentionally not in CI:
python tools/check_vivo_hexquad_container.py --models /path/to/TeleCamera
python tools/inspect_vivo_hexquad.py /path/to/nice_ldr_hp9_general_hex_big_x1.vdnn
```

Host checks cover source CFA, all four Bayer orientations, shifted tile origins,
row padding, sparse channel/frame order, holes, LUT bounds, shift/truncation,
invalid buffers/scales, nonfinite output and malformed container offsets. All
three supplied model wrappers were checked locally. These checks do not simulate
HTP inference and do not establish remosaic quality.
