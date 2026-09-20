> The settings diagnostic now runs the separately bundled HP9 HexQuad models.
> See [vivo-hexquad.md](vivo-hexquad.md). The TELE capture implementation described
> below is unchanged and has not passed colour calibration on the reported device.

# Experimental Vivo neural capture

This adds a third remosaic choice, `vivo_neural`, for HP9 telephoto 4× ISZ
RAW with same-colour 4×4 blocks (CFA period 8×8). The previous two choices
are unchanged. This is an experimental adaptation of an embedded TELE
network, **not a verified copy of Vivo's active 4× pipeline**.

## What runs

1. Existing GPU sensor-response correction and black/white normalization.
   No white balance is applied before inference; normal downstream WB remains.
2. The Bundled APK extracts its own model, four QNN libraries and an ARM64
   executable into a private per-job folder. A Java verifier started with `su`
   checks the asset hashes, then **execs the native executable**. QNN no longer
   runs through JNI or ART's `clns-1` linker namespace. No Termux is needed.
3. Bundled QnnSystem reads the bundled TELE576 context metadata. The helper requires
   graph `T2Q_TELE_3x_v1p9_frozen`, FLOAT32 input `[1,144,144,16]` and output
   `[1,144,144,64]`. Qualcomm HTP executes the graph through QNN Core 2.18.0.
4. Up to eight real executions of flat colour charts test two input CFA hypotheses
   and two output CFA hypotheses. Worst chart RMSE must be <= 0.045. A failed
   gate stops the job; it never substitutes interpolation and calls it neural.
5. CPU packs normalized RAW using square root and Morton 4×4 channel order;
   NPU inference runs overlapping 576×576 input tiles. The CPU decodes output
   using Morton 8×8 order and squaring, discards the 64-pixel input halo,
   and selects nearest matching-colour samples for original-resolution Bayer.
   If the accepted input hypothesis uses 2×2 blocks, same-colour 2×2 binning
   adapts the original 4×4 sensor mosaic. This hypothesis can lose detail.
6. GPU restores the RAW integer range. Existing processing continues before
   HDR merging. This is not an all-GPU path: packing, reprojection and file IPC
   currently use CPU. Each frame starts a fresh worker/context and calibration.

## Runtime restrictions

Root is required in this version. The **Bundled APK** contains TELE576 weights,
QnnSystem, QnnHtp, V79Stub and V79Skel, all pinned by SHA-256. It does not read
Vivo model or algorithm libraries from firmware. The platform FastRPC driver
(`libcdsprpc.so`) and its Android/Qualcomm dependencies still come from the
phone, like GPU drivers. They must match the device and cannot be made portable
by copying Vivo's driver into an APK. V79 is targeted; compatibility with other
Qualcomm devices is not established and no GPU fallback is claimed.

Only ARM64, zero Tetra phase, dimensions divisible by 8 and frames up to 16 MP
are accepted. Four CFA orientations are handled by phase-preserving flips.
Unsupported runtimes or layouts stop with a report.

The root helper is separate from the camera PID. Native execution and cleanup
have a 180-second alarm; Java waits at most 200 seconds. A native crash fails
that capture and records a report. It does not change SELinux or vendor files.
Input/output are temporary app-private files. The app precreates output so it
remains app-owned after root writes it. Closing the probe activity can leave
its helper alive until the hard timeout; a persistent service is not implemented.

## What was actually verified

- The supplied phone report establishes successful root HTP backend/device/
  context creation with Core 2.18.0, backend 5.25.0, SDK v2.25.23. It did not
  execute a graph.
- ARM64 emulation of the supplied stock Halide kernels verifies the square-root
  input transfer and Morton 4×4 packing, and squared output transfer with
  Morton 8×8 unpacking. Stock integer rounding/dither is not copied.
- ARM64 emulation of the supplied QnnSystem parser against the actual TELE
  context yields BinaryInfo v1, GraphInfo v1, Tensor v1, input ID 1 (`input_0`),
  output ID 129 (`tetra2_g_out_BiasAdd`), and the dimensions above. This parser
  returns success and a valid owned pointer while leaving infoSize zero.
  Its tensor storage is 144 bytes even for v1; V1 payload is 112 bytes.
- Host ASan/UBSan tests check channel order, tile coverage, edge CFA phase,
  four sensor orientations, chart selection and wrong-output rejection using
  a clearly labelled synthetic fixture. They do not execute neural weights.
- Mesa shader tests check sensor range reconstruction. Android CI checks
  compilation and helper packaging, including no shared C++ runtime dependency.

Actual graph execution from this APK, chart acceptance, processing time,
real-scene detail, grid removal and tile seams must still be tested on the
phone. The colour gate alone cannot validate spatial alignment or image quality.
There is no demonstrated binding between this TELE model and Vivo's active
4× ISZ mode. A GPU version of these compiled HTP weights is not provided.

## Test on the phone

Open **Vivo Neural — проверка** on the main settings page, grant root and run
it. Success now requires `CFA TEST PASSED` and `NEURAL JOB OK`, not just model
loading. Copy the entire report on failure. Then choose **Vivo Neural — NPU,
root (эксперимент)** in the remosaic backend list and first test a single frame
in telephoto 4× ISZ mode. The capture report must include `NEURAL FRAME COMPLETE`.
Compare the same saved unremosaiced RAW where possible: flat regions, slanted
edges, fine textures, saturated highlights and tile boundaries. Keep the
existing backend selected for normal shooting until this validation passes.

## ABI references

Minimal declarations are restricted to hash-checked bundled QNN libraries, not advertised
as a general QNN SDK replacement. Public layouts and function order:

- [Qualcomm QnnInterface.h](https://docs.qualcomm.com/doc/80-63442-10/topic/api-rst_program_listing_file_include_QNN_QnnInterface_h.html)
- [Qualcomm QnnSystemContext.h](https://docs.qualcomm.com/doc/80-63442-10/topic/api-rst_program_listing_file_include_QNN_System_QnnSystemContext_h.html)

The stock library SHA-256 is
`7495113303fb01cff07434eb9896253774846a7b2c5ff1e13017654c884c3aa5`.
The selected embedded symbol is
`T2Q_TELE_3x_v1p9_240628_576_576_v79_O3_2251_bin` (5,720,680 bytes), SHA-256
`32983328ace406ffbfb165a09b1bfd69015e5da224a6d4b3e1f7f0cac74d1c9b`.

## Building a Bundled APK

CI emits `SCAMERA-template-*` containing the app and native worker, plus
`apksigner.jar`. The template intentionally lacks proprietary model/runtime
assets and reports an incomplete APK before requesting root. For a private
build, place the five assets listed in `VivoNeuralWorker.FILES` in a local
directory (`tele576-v79.bin` is the extracted TELE576 context), then run:

```sh
python3 tools/package_vivo_neural.py --template template.apk \
  --bundle-dir /absolute/path/to/assets --apksigner /absolute/path/to/apksigner.jar \
  --output SCAMERA-Bundled.apk
```

Python 3.11+ and Java 17 are required. The packager verifies exact hashes, checks
that the worker is a real Android ARM64 executable, signs with the project's
existing test key, and verifies the final signature, CRCs and embedded hashes.
Vendor assets are not committed to the public source repository. App source
and packaging remain reproducible with the supplied local assets.

## Additional HP9 archive

`NiceCREConfigHexQuad.xml` and the `nice_ldr_hp9_general_hex_big_x1/x2.vdnn`
files identify another candidate stock path: 18-channel 288x288 input,
3-channel outputs, VST/IVST and QNN 2.28 compiled payloads. The ROI Quad
variant uses 12 input channels. Their exact preprocessing and connection to
active 4x ISZ have not been established. These are **not** substituted into
the TELE576/QNN 2.25 path: the ABI and tensor layouts differ, and their presence
does not make the current implementation a verified copy of stock 4x AI.

## Output validation diagnostics (30156)

The 30155 phone report reached the output validator after graphExecute returned
success; it did not establish whether the rejected value was NaN, infinity,
untouched memory or a finite value outside [-0.25, 2]. The worker now reports
counts, min/max/mean, the first invalid value's index and bits, and centre
channels. A distinctive NaN sentinel helps identify buffers left unwritten.
Neutral charts run before colour charts. Invalid output rejects that input CFA
hypothesis but allows the other hypothesis to be tested. Driver/API failures
still abort immediately. Acceptance still requires four valid charts and the
same RMSE threshold. No range limit is relaxed and no invalid values are clamped
into a passing test. This is diagnostic progress, not proof of working inference
or image quality on the device.

## Discarded-halo validation (30157)

The 30156 phone report has 1,327,104 finite outputs, no NaN/Inf or sentinel,
and a centre near sqrt(0.2), matching its neutral input. Eleven values exceed
the range gate. The first reported index 1,318,007 decodes through Morton8 to
pixel (15,1149), in the discarded output halo. The other ten locations were
not recorded, so their exclusion must still be confirmed by the next phone run.

The old gate tested the entire convolution tile even though reconstruction
uses only the interior. Finite range outliers now reject only when inside
[123,1028) on both axes. This conservative region includes the retained core
plus every neighbour visited by the sampler, for both input scale factors
and output CFA block sizes. The sampler itself refuses to read outside that
validated region. Shared tile/halo constants keep these bounds in sync.
NaN/Inf/unwritten output remains fatal anywhere; the four-chart RMSE acceptance
threshold and interior range limits are unchanged. Reports distinguish
outside_used from outside_halo and include the first outlier's coordinates.

Host ASan/UBSan tests reproduce the reported edge value, reject interior and
protected-boundary outliers, retain non-finite rejection, exercise both scale
factors and CFA hypotheses around all tile edges, and pass colour calibration
with a synthetic halo outlier. Actual colour-chart acceptance, scene quality
and spatial alignment still need phone validation.


## Colour-layout diagnostics (30158)

The 30157 phone report confirms eight successful HTP graph executions and
fully written finite outputs. All block-4 range outliers lie in the discarded
halo. However, the colour gate fails (worst RMSE about 0.31), and block-2 also
has protected-region range outliers. This is successful inference transport,
not a validated remosaic. No colour threshold is relaxed.

Fresh ARM64 emulation of bayer_preproc_hc_v3 (0x570f0) and its BGGR variant
bayer_preproc_hc_bo3_v3 (0x5c308), with unique uint16 pixel values, reproduces
the existing Morton4 ordering, sqrt(value / 1023) transfer, and BGGR flips.
The library's lookup tables at 0x31804 and 0x31964 match Morton4 and Morton8.
These are evidence about those stock functions, not proof that TELE576 uses
that entire path in the active HP9 4x mode.

Native worker v5 adds a probe-only fallback after MappingError. The capture
path is unchanged. Runtime/driver errors are not caught as layout failures.
The fallback performs ten additional colour-chart executions: stock Morton4
input with CFA blocks 4, 2 and 1, and raster4 input with blocks 2 and 1. Neutral
inputs are identical across these hypotheses and already ran in calibration.
Bayer1 is diagnostic model identification only; no sensor-mode change or 4x4
binning is installed. Raster4/block4 is a duplicate on these flat charts and
is omitted.

For each hypothesis it reports all 64 channel means separately for the four
tensor-cell parities, and compares three output index interpretations:
measured stock Morton8, raster8, and four colour planes with 4x4 raster positions.
The latter two are hypotheses, not identified stock formats. Output CFA blocks
1, 2, 4 and 8 and four red-quadrant orientations are ranked. Scores use squared
network outputs without clipping, retain within-phase variance, and compare
the central 640x640 region. A free-colour RMSE reports the error even if every
pixel were allowed to choose its closest test-chart colour; a large value
there cannot be fixed merely by permuting channels.

A diagnostic candidate never enables capture, even if its flat-field score is
low. A correct spatial reconstruction, colour ordering and compatibility with
Tetra4 input still need verification before an alternative can be integrated.
The original MappingError is rethrown after diagnostics, and the same native
180-second timeout remains. Open Vivo Neural — проверка to collect this report;
a normal failed capture intentionally does not run the extended sweep.

Host ASan/UBSan tests cover every diagnostic output interpretation and CFA
period, red/blue reversal rejection, within-phase variance, nonfinite output,
the ten-chart sweep, and propagation of driver errors. These are synthetic
transport/analysis fixtures; they do not run HTP weights on the host.



## One final APK from Actions

The test workflow now packages, verifies and signs all 17 pinned model/runtime
entries inside Actions. The downloadable `SCAMERA-Build-<version>` artifact
contains the final APK and SHA256SUMS.txt. Deliver that exact APK; never append
assets or re-sign it locally after downloading. A successful source compile
without the private bundle must not publish an incomplete APK.

Vendor binaries remain outside public git. Bootstrap once with the repository
Actions secret `SCAMERA_NEURAL_ASSETS_URL`, an HTTPS download of
`scamera-neural-assets-v1.zip` (SHA256
`7a2c0d642f3fce5dd20f4f5c0bb94cbd9f6216cae7fd504cb380f94b85c54587`).
The archive contains bundle/, hexquad/ and nice/ files verified individually
against the Java asset manifest. Signed URLs and credentials must not be
committed to source or entered in public PR comments.

After the first successful build, subsequent runs restore the hash-checked
`SCAMERA-neural-assets-v1` Actions artifact and refresh its 90-day retention.
The temporary bootstrap secret can then be removed. If all copies expire,
provision the same pinned bundle again. Artifact redirects do not receive the
GitHub authorization header. Bundle URLs are omitted from failure messages.


An encrypted seed is also available on `codex/encrypted-neural-assets-v1`.
The source-side `tools/neural-assets-encrypted.json` pins its commit and all
ciphertext hashes. `SCAMERA_NEURAL_ASSETS_KEY` holds the random 256-bit AES-GCM
key in repository Actions secrets; the key is never committed. GCM authentication
and the plaintext/file hashes are checked before packaging. This removes the
need for a public model URL and allows recovery after the Actions cache expires.
Only encrypted bytes are stored in git; authorized final APK artifacts contain
the runtime/model files required by the app.
