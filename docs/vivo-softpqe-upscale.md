# Experimental Vivo neural upscale (softpqe)

This is **not a working upscaler yet**. `Vivo Upscale — проверка` loads a
bundled Vivo still-photo enhancement model into Qualcomm HTP, executes the
graph once with a flat placeholder input, and reports both the tensor
descriptors and the real, QNN-resolved quantization parameters. It proves the
execution mechanics (buffer sizes, HTP dispatch, no crash) and gives the one
missing piece of information needed for real processing; it does not process
a photograph, because a flat placeholder is not a real photograph's
quantized input. It follows the same discipline as
[vivo-neural-capture.md](vivo-neural-capture.md) and
[vivo-hexquad.md](vivo-hexquad.md): no guessed process structs are passed into
proprietary code, and a capability is only claimed once it has run.

**Why RAISR has not been removed yet.** The plan is to replace RAISR with
this once it demonstrably produces correct output on-device; ripping RAISR
out first would leave the app with no working upscaler if the softpqe path
turns out not to. The blocking gap, precisely: the graph's *input*
quantization (scale/offset or block table) is still unknown, so a real photo
cannot yet be encoded into the bytes this graph expects. The next phone
report from this probe is expected to supply it (see **Quantization** below)
- at that point tiling, real-photo encoding, and the settings swap are the
remaining work, not open unknowns.

## What this is, in the vendor's own terms

Vivo's still-photo path ("softpqe") is a separate pipeline from the HP9
HexQuad/TELE576 remosaic models already integrated: it runs **after**
remosaic/demosaic, on YUV, not on the Bayer/Tetra mosaic. It is configured by
XML files (`softpqe_configs*.xml`, supplied by the user, kept outside git)
that select a `.vdnn` model per scene, per zoom, per ISO. Quoting the
config's own comments:

- `processMode`: 0 = run Y and UV, 1 = UV only.
- `enableSuperResolution`: 0 = disabled, 1 = output original size, 2 = output
  scale size. Most sampled profiles (night, portrait, scenery) run this
  disabled or in a fixed-size "quality" mode; only the `aigc_12M`/`aigc_24M`
  and `live_photo` profiles enable actual upscaling (mode 2).
- `zoomRatioFor2xModel` / `zoomRatioFor4xModel`: 1.5 and 3.0 in every sampled
  profile. The "2x"/"4x" model names describe the model's own tile scale
  factor, not the requested camera zoom ratio.
- Telephoto capture (`ui_normal_shot/default/softpqe_configs_tele_*.xml`,
  used for `satRole=3`, i.e. `tele`) always runs the **quality** model
  (`softpqe_y_1x_*`), never the 2x/4x models in the sampled configs. The
  actual 2x/4x scale-changing models are wired for the `aigc_12M`/`aigc_24M`
  main-sensor high-resolution capture modes.

## Model family (verified by parsing the containers, not by running them)

Ten `.vdnn` files were supplied, all readable as QNN v79 context binaries by
`tools/inspect_softpqe_model.py` (offset 816, envelope/metadata layout
identical to the already-integrated TELE576 remosaic model). Real, verified
graph names and I/O tensor shapes:

| File | Graph | Input | Output |
| --- | --- | --- | --- |
| `softpqe_y_1x_0_5_0.vdnn` | `model_y_gan_f8_w8a8_sym_ep0399_stack_quant_8w8a32b` | `1×560×560×16` | `1×560×560×4` |
| `softpqe_y_1x_0_6_0.vdnn` | `sr1x_qat_ep0499_202311251515_quant_8w8a32b` | `1×560×560×16` | `1×560×560×4` |
| `softpqe_y_1x_0_6_1.vdnn` | `sr1x_qat_20240131_quant_8w8a32b` | `1×560×560×16` | `1×560×560×4` |
| `softpqe_y_2x_0_1_0.vdnn` | `keta_sr2x_qat_ep0396_202312011751_quant_8w8a32b` | `1×560×560×4` | `1×1120×1120×1` |
| `softpqe_y_2x_0_2_0.vdnn` | `keta_sr2x_qat_20240314_quant_8w8a32b` | `1×560×560×4` | `1×1120×1120×1` |
| `softpqe_y_4x_0_1_0.vdnn` | `keta_sr4x_qat_ep0368_202312011729_quant_8w8a32b` | `1×560×560×4` | `1×2240×2240×1` |
| `softpqe_y_4x_0_2_0.vdnn` | `keta_sr4x_qat_20240310_quant_8w8a32b` | `1×560×560×4` | `1×2240×2240×1` |
| `softpqe_uv_0_7_0.vdnn` | `uv_0_7_0_a08_quant_8w8a32b` | `1×560×560×5` | `1×560×560×2` |
| `softpqe_uv_0_8_0.vdnn` | `uv_0_8_0_a08_nr24_qnn_quant_8w8a32b` | `1×560×560×5` | `1×560×560×2` |

All ten declare raw `dataType=0x408` = `QNN_DATATYPE_UFIXED_POINT_8` (per
Qualcomm's published `QNN_QnnTypes.h`), distinct from the TELE576 remosaic
model's `0x232` = `QNN_DATATYPE_FLOAT_32`. These are genuinely 8-bit
unsigned fixed-point tensors on the wire - one byte per element, not four.
See **Quantization** below.

A separate, unrelated `VSR`/`videosr*` family (`sr1x_m4`, `sr1x_m5`,
`sr2x_m24`, `sr4x_m24`, `videosr2x/4x*`) was also supplied. These use a
different container revision (envelope at byte 1048/1132, not 816), a
different `dataType=0x416`, and tensor names (`x_0`→`tail_0_0`/`out_conv_0`)
that match Vivo's **video** recording super-resolution stack, not the still
photo capture path this camera uses. Out of scope; not integrated.

Real per-model config parameters recovered from `softpqe_configs_master_2x.xml`
(the `aigc_24M` profile, the only sampled file that actually wires a scale
model as primary):

```
yModelName            softpqe_y_2x_0_2_0.vdnn
auxYModelName         softpqe_y_4x_0_2_0.vdnn   (continuous-zoom blend partner)
uvModelName           softpqe_uv_0_7_0.vdnn
modelBlockDelta       16   ("the delta of block we split" - tile margin/overlap)
yModelMode            2    (0=small kernel, 1=large kernel, 2=ETA; meaning of ETA unresolved)
yModelEdgeMaskMode     6   (enum in the config's own comment only documents 0-2;
                            real deployed value is out of that documented range)
uvModelEdgeMaskMode    5   (gau(sobel(UV) x sobel(Y)))
enableInOutFusion      1
enableResidualWeight   1
enableYMultiResolutionFusion  1  (marked "will be deprecated" even in this build)
yModelOutputFakeQuantScale / Offset    0.002362848026677966 / -126
auxYModelOutputFakeQuantScale / Offset 0.0024741345550864935 / -123
uvModelOutputFakeQuantScale / Offset   0.0013194871135056019 / -119
```

`enableResidualWeight=1` is consistent with the graph output tensor names
themselves (`add_62_f_0`, `add_64_f_0`, `add_66_f_0`, `add_68_f_0` - the
output *is* an Add op's result baked into the frozen graph). This means the
residual addition happens **inside** the compiled graph; there is no separate
app-side "add a residual to a bilinear base" step to reimplement, unlike an
earlier working hypothesis from before these containers were available for
direct inspection.

No sampled config chains `y_1x`'s output into `y_2x`/`y_4x` as their input (no
config lists two `yModelName` entries). The "1x" (quality/denoise) and
"2x"/"4x" (actual upscale) models are **alternatives** selected by
`enableSuperResolution` and the active `algoConfigNx` file, not a cascade.

ISO ladder (`TotalLevNum=11`, `rawISO = shortGain × 50`): 50, 1600, 3200,
6400, 12800, 25600, 51200, 76800, 102400, 153600, 204800. Denoise strength
(`noise1Level`) ramps from ~0.09-0.1 at base ISO to 0.3-0.4 at the top of the
ladder; `inputLowTextureGain` steps down from 6 to 4 at high ISO. Post-NPU
sharpening is a conventional two-radius unsharp mask
(`postSharpeningParameters`: kernel sizes 3 and 5, small weights), run as a
**separate CPU step after the graph**, not part of it - SCAMERA already has
an equivalent (Sharpening: unsharp mask with radius/amount/contrast/halo
controls), so this does not need a dedicated port.

## Quantization

The graph name suffix `_quant_8w8a32b` (8-bit weights, 8-bit activations,
32-bit bias) confirms these are genuinely fixed-point on the wire. What was
established, against Qualcomm's own published `QNN_QnnTypes.h` (fetched and
cross-checked directly, not recalled from memory):

- `dataType=0x408` = `QNN_DATATYPE_UFIXED_POINT_8`: **one byte per element**,
  settled. This alone fixes the client-buffer size regardless of the
  quantization scheme layered on top of it.
- Each tensor's inline `Qnn_QuantizeParams_t` (`{encodingDefinition,
  quantizationEncoding, union{...}}`, the same 32-byte payload the working
  TELE576 session already reserves but never inspects) reads, from the
  `softpqe_y_2x_0_2_0.vdnn` graph metadata:

  ```
  INPUT  quant: definition=1 encoding=4
  OUTPUT quant: definition=1 encoding=4
  ```

  `definition=1` = `QNN_DEFINITION_DEFINED` (explicit params are provided).
  `encoding=4` = `QNN_QUANTIZATION_ENCODING_BLOCK`, whose union member
  `Qnn_BlockEncoding_t = { uint32_t* blockSize; Qnn_ScaleOffset_t* scaleOffset; }`
  is **two pointers**, not inline floats - per-block quantization, a step up
  in complexity from the plain per-tensor case the TELE576 float32 path never
  needed to handle at all.

The earlier attempt to interpret the 32 raw payload bytes read directly from
the `.vdnn` file as this union was wrong on its face: those bytes came from a
static file, so pointer-shaped fields in them cannot be real addresses - the
small integers observed there are not scale/offset values, they are neither
meaningful nor safely dereferenceable. The **live** union, with real heap
pointers, only exists after `QnnSystemContext_getBinaryInfo()` (`sys->info()`
in the existing session code) runs for real and resolves it. The native probe
was corrected to decode that live union instead, with bounds-checked,
switch-on-`encoding` printing (blockSize array, or axis/scale-offset arrays
for the other four documented encodings, guarded against implausible counts
before dereferencing) - see `vivo-softpqe-runtime.h`'s `describeQuant()`.
This can only run meaningfully inside the real process, after a real device
call; it is not something a static container read can recover, and the
earlier doc revision was explicit about that limit rather than guessing past
it.

**What is still not known: the actual scale/offset/block values**, and
separately, the *input* tensor's quantization is not covered by the vendor
config files at all (they document `...FakeQuantScale`/`...FakeQuantOffset`
for model **outputs** only - `0.002362848026677966`/`-126` for `y_2x`,
`0.0024741345550864935`/`-123` for the auxiliary `y_4x`, both real numbers
already in hand). Without the input side, a real photograph cannot yet be
correctly encoded into the bytes this graph expects. The probe now executes
the graph once with a flat, mid-scale placeholder (`0x80` in every input
byte) specifically to (a) prove `graphExecute()` itself works - buffer
sizing, HTP dispatch, no crash - and (b) have the live quantizeParams union
logged in a real phone report for the first time. The placeholder's output is
explicitly not claimed to be a correct upscaled image; a flat neutral input
was chosen precisely because its result is not something a viewer could
mistake for real detail.

## What the diagnostic probe actually does

`Vivo Upscale — проверка` (new settings entry, mirrors `Vivo Neural —
проверка`):

1. Verifies bundled asset hashes (model + 4 QNN libraries), execs the native
   worker the same way the working TELE576/HexQuad paths do (`app_process64`,
   outside ART's `clns-1` linker namespace, root required for the DSP driver).
2. Loads `libQnnSystem.so`, calls `getBinaryInfo` on the bundled
   `softpqe_y_2x_0_2_0`/`softpqe_y_4x_0_2_0` context, parses the graph
   metadata with the same narrow FlatBuffers reader already proven against
   TELE576, and **logs** each input/output tensor's id, raw dataType, shape,
   and the live quantizeParams union decoded per its `encoding` (scale/offset,
   axis, block-width, block-axis, or block - all five documented encodings,
   bounds-checked before any array is dereferenced).
3. Loads HTP, creates backend/device/context, retrieves the named graph.
   Every discovered HTP provider's core version is logged (the correct one is
   not yet known for these graphs, unlike TELE576's confirmed Core 2.18.0 or
   HexQuad's confirmed 2.28/2.29.8; any `major==2` provider is accepted so
   the report can establish this by observation instead of by requiring a
   version to be guessed correctly in advance).
4. Binds a flat placeholder input (`0x80` in every byte, sized exactly to the
   real `560×560×4` / `2240×2240×1`-class shapes recovered above) and calls
   `graphExecute` once. Logs success/failure and the raw output byte range.
   This proves the transport end to end - it does **not** prove the output is
   a correct upscaled image; that needs the real input quantization, still
   pending the values this same run's quant log now surfaces.

## Runtime assets

Bundled QNN libraries come from a third, distinct runtime found on the
reported device at `/odm/lib64/npuhw/qnnv3/*.so` - separate from both
already-pinned sets (`vendor/lib64/hw/*.so`, TELE576's Core 2.18.0 runtime,
and `vendor/npu/lib/*.so`, HexQuad's Core 2.28/2.29.8 runtime). The `qnnv3`
path name and its distinctness from the other two are circumstantial, not
confirmed evidence that this is the runtime the softpqe graphs actually run
under on the device.

Extracted/pinned by SHA-256 (see `tools/inspect_softpqe_model.py --extract`
for how the model files are trimmed - vendor wrapper header and trailer
stripped, matching how TELE576's `tele576-v79.bin` was extracted from its
`.so`):

| Asset | Bytes | SHA-256 |
| --- | ---: | --- |
| `softpqe_y_2x_0_2_0.bin` | 1,326,376 | `381986ea8463a49f3bce9047c02a83ce9056a08dacb9765a90a46fde6215429b` |
| `softpqe_y_4x_0_2_0.bin` | 3,276,224 | `b613899927af83cbf741ec6bbfed28de3f5bdf66aaceefbb8a12161f7a206e16` |
| `libQnnSystem.so` (qnnv3) | 2,549,880 | `57ee2bb5c0042a5bb212e79ab185915b3eba07d469ab09725529210b4a11b84b` |
| `libQnnHtp.so` (qnnv3) | 2,465,168 | `976c165553748118106773ac3db94f896806c39f49577eb8c95d70c2352bdcb7` |
| `libQnnHtpV79Stub.so` (qnnv3) | 713,912 | `e1f604549d645b3c71280a8bed83a71796bc5928013839a837023ddd695c83ce` |
| `libQnnHtpV79Skel.so` (qnnv3) | 9,463,484 | `cd3bb9982734dcc8ceb267b46449ba92e42d1f93b00270d7658cbac2ea5ba955` |

As with the existing integrations, none of these are committed to the public
repository; a private Bundled APK is produced locally with
`tools/package_vivo_neural.py --softpqe-dir`.

## Next steps, in order

1. Run the diagnostic on the phone. Read back: which HTP core version accepts
   these graphs, whether `graphExecute` on the flat placeholder succeeds, and
   critically, the logged live quantizeParams for both tensors - `encoding=4`
   (BLOCK) means `blockSize`/`scaleOffset` array contents, not a single
   number, so the report needs to actually show array values, not just
   confirm the pointers are non-null.
2. Use the input tensor's real quantization from (1) to correctly encode a
   real prepared luma tile into uint8 bytes. The 4-channel input's packing
   (a space-to-depth of some pre-upsampled base, an edge-mask channel mixed
   in per `yModelEdgeMaskMode`, or something else) is still an open question
   this repo has not tested - the config only names the edge-mask *mode*,
   not its exact channel position. Use the output tensor's already-known
   config scale/offset to dequantize the result back to a real pixel value.
3. Implement tiling (560×560 input, `modelBlockDelta`-sized margin, matching
   TELE576's halo-discard pattern) and a calibration gate - for a single luma
   channel, flat charts cannot disambiguate a channel/axis ordering error the
   way TELE576's four-colour charts did, so the gate needs a spatially
   patterned chart (e.g. a quadrant-coded tile) checked for geometric
   consistency, not just colour RMSE.
4. Wire an actual capture/post-processing path, replacing RAISR, only once
   (1)-(3) pass on-device and produce a verifiably correct upscaled image on
   a real photograph - exactly as `vivo_neural`/HP9 HexQuad were gated before
   being offered as capture backends. RAISR stays in place and selectable
   until that point, so the app is never left with zero working upscaler.
