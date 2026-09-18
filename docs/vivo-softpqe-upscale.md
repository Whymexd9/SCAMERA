# Experimental Vivo neural upscale (softpqe)

This is **not a working upscaler**. It adds a diagnostic-only probe, `Vivo
Upscale — проверка`, that loads a bundled Vivo still-photo enhancement model
into Qualcomm HTP and reports the tensor descriptors QNN itself resolves. It
does not execute the graph and does not process a photograph. It follows the
same discipline as [vivo-neural-capture.md](vivo-neural-capture.md) and
[vivo-hexquad.md](vivo-hexquad.md): no guessed process structs are passed into
proprietary code, and a capability is only claimed once it has run.

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

All ten declare raw `dataType=0x408`, distinct from the TELE576 remosaic
model's `0x232` (confirmed FLOAT32 there by successful end-to-end execution).
See **Quantization is unresolved** below.

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

## Quantization is unresolved

The graph name suffix `_quant_8w8a32b` (8-bit weights, 8-bit activations,
32-bit bias) and the config's per-model `...FakeQuantScale`/`...FakeQuantOffset`
pairs both point at the input/output tensors being genuinely fixed-point on
the wire, not float32 carrying a training-time simulation like the name
"fake quant" might suggest in isolation.

Each tensor's inline `Quant` struct (`{definition, encoding, payload[32]}`,
same ABI already used by the working TELE576 session) was read directly from
the `softpqe_y_2x_0_2_0.vdnn` graph metadata:

```
INPUT  quant: definition=1 encoding=4
OUTPUT quant: definition=1 encoding=4
```

`encoding=4` does not match a plain per-tensor `{float scale; int32 offset}`
pair - the 32-byte payload decodes as small integers, not a scale-shaped
float, consistent with an **indirect** encoding (most likely per-axis
scale/offset, itself another table reached through the payload) rather than
the simple per-tensor case the TELE576 float32 path never needed to
handle. Decoding a QNN axis-scale-offset record correctly needs either the
real QNN SDK headers or ARM64 emulation of the vendor's own parser (as was
done for the HC preprocessing kernel); neither is available in this session.

Consequently: **the client-buffer byte width, and the exact dequantization
of both input and output, are not established.** Guessing 1 byte vs. 4 bytes
per element, or applying the config's output-side scale/offset as if it were
the tensor's own encoding, would mean feeding an unverified buffer layout
into proprietary code - exactly what this project's own rules forbid. This
blocks real graph execution until either the encoding is decoded from a
`libQnnSystem.so`/`libQnnHtp.so` parser (host emulation, not attempted here)
or a phone report from `graphExecute` itself reveals the required sizing
through its own error/success behaviour.

## What the diagnostic probe actually does

`Vivo Upscale — проверка` (new settings entry, mirrors `Vivo Neural —
проверка`):

1. Verifies bundled asset hashes (model + 4 QNN libraries), execs the native
   worker the same way the working TELE576/HexQuad paths do (`app_process64`,
   outside ART's `clns-1` linker namespace, root required for the DSP driver).
2. Loads `libQnnSystem.so`, calls `getBinaryInfo` on the bundled
   `softpqe_y_2x_0_2_0`/`softpqe_y_4x_0_2_0` context, parses the graph
   metadata with the same narrow FlatBuffers reader already proven against
   TELE576, and **logs** each input/output tensor's id, raw dataType, shape
   and raw `Quant{definition,encoding,payload}` bytes.
3. Loads HTP, creates backend/device/context, retrieves the named graph.
   Every discovered HTP provider's core version is logged (the correct one is
   not yet known for these graphs, unlike TELE576's confirmed Core 2.18.0 or
   HexQuad's confirmed 2.28/2.29.8; any `major==2` provider is accepted so
   the report can establish this by observation instead of by requiring a
   version to be guessed correctly in advance).
4. Stops. **`graphExecute` is never called.** Success here proves the model
   and runtime load and the graph is retrievable; it proves nothing about
   inference, quantization, or image quality.

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

1. Run the diagnostic on the phone; record which HTP core version actually
   accepts these graphs and whether context/graph creation succeeds at all.
2. Decode the tensor quantization encoding, either by ARM64-emulating the
   bundled `libQnnSystem.so`'s own metadata parser against a real tensor
   record, or from `graphExecute`'s own error behaviour once a first client
   buffer size is attempted.
3. Only after (2): implement tiling (560×560 input, `modelBlockDelta`-sized
   margin, matching TELE576's halo-discard pattern) and a calibration gate -
   for a single luma channel, flat charts cannot disambiguate a channel/axis
   ordering error the way TELE576's four-colour charts did, so the gate needs
   a spatially patterned chart (e.g. a quadrant-coded tile) checked for
   geometric consistency, not just colour RMSE.
4. Wire an actual capture path only once (2) and (3) pass on-device, exactly
   as `vivo_neural`/HP9 HexQuad were gated before being offered as capture
   backends.
