# HP9 HexQuad v17: unified hybrid with CPU input prefetch

The application now selects one hybrid automatically. **Нейроремозаик HP9**
shows **Гибрид CPU + GPU + NPU** as a status preference. Saved v16 CPU/GPU
choices no longer override this policy. Model x1/x2, output size, all noise and
texture settings remain independent and retain their saved values. Legacy
native CPU transport still works for regression checks and automatic fallback.

This distribution follows the user's V2454A x1/ISO800 logs: output assembly
took 2.39 s with GPU versus 4.76 s with CPU; NPU calls took about 9.35 s in both.
Input preparation was already on CPU in both runs. Its 1.72/2.87 s timings do
not demonstrate two different packing algorithms or prove GPU contention.
Profile-cache state and the photographed scene also differed between runs.

CPU retains response correction, alignment, exact sparse packing and ordered
tile accumulation. GPU retains the tested detail reconstruction, IVST and
channel mixing. NPU retains exactly the same model and synchronous calls.
One preparation thread now uses the existing bounded CPU row team to prepare
the NEXT tile while the owner processes the current tile on NPU/GPU. A barrier
prevents concurrent reuse of the row team by CPU postprocessing. NPU and EGL
calls stay on the owner thread; overlapping tiles still accumulate in raster
order. No model weights, precision, crop, halo or noise controls change.

The producer owns one extra 288x288x18 FLOAT32 buffer (about 5.7 MiB). It only
writes inactive storage. After completion, vectors swap without a tensor copy;
HexSession refreshes the QNN client-buffer descriptors immediately before each
synchronous graph execution. A missing buffer/thread uses serial packing.
The preparation destructor joins a pending job before buffers, RAW mappings,
guides or the row team can be destroyed, including on inference exceptions.
Packing exceptions propagate after the producer barrier. Existing GPU
precision/error fallback remains effective with prefetch enabled.

Reports identify `input_prefetch`, actual GPU/CPU tile counts, total packing
work, first-tile packing and exposed preparation waits. Packing work now
overlaps inference/GPU time, so stage timings must not be summed into wall time.
Both the full and cached profile checks remain unchanged. This change targets
idle intervals; its actual phone speedup still requires a new capture report.

Host checks compare all packed input bytes and final RAW16 in 24 captures
(all CFA orientations, x1/x2/full output, moving inputs and noise/detail
settings), exercise immutable inference input and CPU fallback, and run the
real GLES shader/fallback suite. Preparation tests cover overlap rendezvous,
shared-row-team barriers, repeated jobs, errors and owner-exception cleanup.
ASan/UBSan and TSan CI checks cover memory and thread ownership respectively.

# HP9 HexQuad v16: experimental GPU postprocessing with NPU inference

The top-level **Нейроремозаик HP9** screen adds **Обработка вокруг нейросети**:
**CPU + NPU — текущий** (default) or **GPU + NPU — тестовый**. The selected
mode is captured once per shot. Neural weights, QNN runtime, six-frame input,
packing, validation gates and NPU call count are unchanged.

The GPU mode uses GLES 3.1 compute for the first-RAW detail/green reference,
texture confidence, inverse VST, optional x2 area reduction, independent
luma/chroma blending and Bayer sampling. CPU code still builds coarse fields,
aligns frames, packs the network input, accumulates overlapping tiles and
writes the result. The neural network still executes on the NPU. This is not
a transfer of the network to the GPU or a zero-copy QNN implementation.

One EGL context and three compute programs are reused throughout a capture.
The first RAW and coarse fields are uploaded once; each neural output tile is
uploaded and its reconstructed Bayer tile read back. Buffers are bounded by
one RAW, coarse fields and fixed tile dimensions, with no full-frame RGB image.
The platform EGL/GLES drivers are used; no vendor GPU driver is redistributed.
An unsupported context or catchable GPU error selects the existing CPU path
for that tile and the rest of the shot, reusing the same NPU result.

The first tile is computed both ways and the CPU version is retained. GPU
processing of subsequent tiles requires maximum absolute difference <=0.0002
and RMSE <=0.00002 in normalized Bayer values. Every readback must be finite.
A precision mismatch falls back to CPU. These checks accommodate floating
point rounding; they are not a promise of bitwise equality or full-frame
equivalence on every GPU. Reports show the renderer, comparison, fallback
reason, actual GPU/CPU tile counts and GPU postprocessing time.

GPU shots use transport version 4, retaining the 112-byte v3 header and adding
a uint32 GPU flag at offset 104 (0 or 1); bytes 108..111 must remain zero.
Default CPU shots still write identical v3 headers. Model/profile cache keys
are unchanged because the inference and VST profile are unchanged; the GPU
precision check runs separately on every capture.

Real GLES shader execution on Mesa covers 24 mock-network captures: all four
CFA orientations, x1, reduced/full x2, channel/texture controls, borders and
overlaps. Packed network inputs and call counts remain identical. Maximum
final Bayer16 difference was 3/65535. Deliberate GL errors and first-tile
precision failures produce bitwise CPU output without repeated inference.
The existing 24 CPU golden fixtures remain identical. These tests validate
code and fallback behaviour; Adreno performance, resource availability in the
root worker and actual capture speed still require a phone test. Upload and
readback overhead may outweigh the saved CPU work on some devices.

# HP9 HexQuad v15: deterministic CPU acceleration

The working v14 model, six-frame selection, all noise/detail settings, VST/IVST,
registration search, pixel arithmetic, halo/overlap and validation thresholds
are retained. QNN 2.29.8, its binaries and both model weights are unchanged.

A capture now creates one bounded C++ row team (up to four threads including
its owner). Independent guide/coarse-field rows, local-flow rows, sparse input
rows and output/reference reconstruction rows run concurrently. Each pixel
keeps the same arithmetic and neighbourhood accumulation order. Tiles and NPU
calls stay sequential, preserving overlap addition order and QNN buffer lifetime.
Threads sleep between CPU stages, including while NPU inference runs. No extra
full-resolution image or additional simultaneous network is allocated. If a
thread cannot be created, the team uses fewer threads; processing is unchanged.
Exceptions return to the owner after all running row tasks reach the barrier.

Nearest-green searches stop once both neighbours have been found; later loop
iterations could not change either neighbour. This is an exact early exit.
Report disk commits are batched at most once per 500 ms during normal progress;
errors and job completion synchronously persist the accumulated bounded report.
The report still forwards every log line immediately to the app logger.

Additional timings report asset extraction, input transport, model/runtime init,
guide creation, registration, detail setup and result write/read separately.
The existing packing/NPU/assembly/total timings remain. Timers include wall time;
detail tile preparation is a subset of assembly, not an additional total.
The native-worker Gradle task now tracks HexQuad headers as build inputs too.

Regression fixtures hash every packed tensor byte and compare final Bayer16
against pre-optimization commit `00769cd858405905634b4946e89da5fbb4a736cd`:
24 captures cover x1, reduced x2, full x2, all CFA orientations, six moving
inputs, channel blends, noise-profile factors, response and texture controls.
Tests also check thread barriers/exceptions and retain existing sanitizer gates.
The desktop mock-network timing improves CPU work; it is not a phone/NPU speed
measurement. Device timing and thermal behaviour still require user validation.

# HP9 HexQuad v14: model, noise profile, ISO blending and full x2 output

The existing top-level `hexquad_denoise_screen` key is retained and renamed
**Нейроремозаик HP9**. The selected remosaic backend remains `hp9_hexquad`.
Controls are captured once per shot, so edits during processing cannot change a running job.

* `hexquad_model`: x2 (default) or experimental x1, both six real RAW frames.
  The model output is respectively 576² RGB or 288² RGB per 288² input tile.
* `hexquad_full_resolution`: x2 only, off by default. On retains every output
  RGB position before Bayer sampling, producing twice the input width/height
  (12.5MP input -> approximately 50MP). Off preserves the original IVST then
  2x2 area reduction. It is not JPEG interpolation, sensor zoom, or proof of
  four times as much captured detail. The hybrid reference is necessarily
  bilinearly sampled at output pixel centres; the neural output is not resized.
  Native output length, Java allocation, RAW dimensions, active-area geometry,
  processing dimensions and DNG/JPEG consumers follow the selected output size.
  Extra memory and postprocessing time are expected; phone testing is required.
* `hexquad_noise_overall`, `hexquad_noise_photon`, `hexquad_noise_readout`:
  0.5..2.0 each, defaults 1. They multiply VARIANCE coefficients, not sigma:
  `a' = a * overall * photon`, `b' = b * overall * readout`.
  Physical capture ISO is unchanged. The ISO50 normalization stays fixed;
  forward and inverse LUTs use the same modified a'/b'. These are experimental
  conditioning controls, not exposed internal network denoise inputs or
  `vstNormCoeff`. Some combinations can clip VST or fail colour validation.
  The default factors preserve the old VST/IVST exactly.
* `hexquad_auto_iso`: off by default. When enabled, luma/chroma blend values
  interpolate linearly in log2 ISO between ISO100 (35/85 by default) and
  ISO3200 (70/100), and clamp to the nearest endpoint outside that interval.
  Both endpoint pairs are editable 0..100; these are starting values, not
  measured optimal sensor calibration. Existing manual values 50/100 (or the
  user's saved values) are retained and used when auto is off.
* `hexquad_texture`: 0..100, default 0. Same-colour green neighbour energy
  minus expected two-sample physical sensor noise yields a smooth confidence
  mask. Expected noise uses the ORIGINAL HP9 ISO profile, regardless of the
  experimental noise multipliers. On supported texture, the local neural luma
  weight becomes `luma * (1 - texture * confidence)`; chroma is unchanged.
  It reuses the first-RAW Tetra Detail reference without sharpening. Noise and
  grid may still return; it is not a perfect texture/noise classifier.

Transport version 3 has a 112-byte little-endian header. Bytes 0..67 retain
v2 fields; 68..79 are zero. At 80: uint32 model scale; at 84/88/92: float
noise overall/photon/readout; at 96: float texture in 0..1; at 100: uint32
full output flag (only x2); 104..111 zero. Six unchanged RAW16 planes follow.
v1/v2 headers still select x2/reduced output, noise factors1, texture0.

Both x1 and x2 capture use the existing strict profiled chart gate, now with
selected model and modified VST. Synthetic physical noise stays unchanged,
so the test measures a noise-profile mismatch instead of modifying the noise
and the assumed profile together. No limits are relaxed. Failed settings do
not produce photographs or silently select another model. Process-local cache
keys include v14, ISO, CFA, model and every noise factor. Standalone historical
checks still report the baseline x1/x2 models; capture reports identify the
actual settings. A clean synthetic pass never proves real-scene image quality.

Host checks cover default VST identity, bounded roundtrips, independent profile
terms, unchanged diagnostic noise, all four CFA orientations, x1/x2/reduced/full
assembly including edge/seam pixels, reference halo, texture/noise fixtures,
old/new header rejection, exact Java->native bytes, ISO interpolation and cache
separation. Android CI builds the same native worker. No NPU is present on host.

## Previous v13 baseline and implementation history

# HP9 HexQuad: bundled diagnostic and stock normal VST

Status: **experimental six-frame capture is connected through `hp9_hexquad`.**
x2 profiled ISO100/400/800 checks pass on Vivo V2454A; a real 12.5MP capture
completed. Worker v12 corrected output precision and restored eligible ZSL;
the user reports that capture now works, but texture looks overly smoothed.
Worker v13 adds separate luma/chroma reconstruction blending. Real-scene detail,
colour and processing time for this new blend still require phone validation.
x1 remains diagnostic only. No automatic ISO reduction is used.

## Detail controls (v13 worker)

The main settings page has **HP9 HexQuad — шумоподавление**, active only for
the `hp9_hexquad` backend. Luma defaults to 50, chroma to 100. The two sliders
are **reconstruction blend weights, not internal controls of the closed model**:
100 retains that neural component; 0 uses an independent reconstruction of
the first real RAW (the same exposure used as the alignment reference).
Lower values may restore noise, CFA grid or interpolation artefacts. They
cannot guarantee recovery of detail missing from the measured RAW.

The reference adapts our Tetra Detail v2's coarse colour/energy/correlation,
directional green and regularized colour regression to bounded native tiles.
It uses the same black/white levels, CFA canonicalization and site correction
as the neural input, preserving observed samples. It does not run another
neural network or alter model ISO/VST. Coarse fields occupy about 10 MB at
12.5 MP; no second full-resolution RGB frame is retained. This extra reference
has a processing cost; v13 is a quality-control experiment, not a speedup.

After the original per-channel IVST/2x2-area reduction, the two RGB estimates
are temporarily divided by the capture neutral point. Luma is the camera-RGB
proxy `(R+2G+B)/4` and chroma is RGB minus that scalar. These components are
blended independently, then restored to the original sensor scale before
Bayer selection/overlap. This is not sRGB or a perceptual colour space.
Clipping at final Bayer16 encoding can couple the components in saturated
regions. At 100/100 the existing neural assembly is retained exactly and
reference reconstruction is skipped. The six-frame model and its gates remain.

**Дополнительный шумодав после HexQuad** defaults to off. It suppresses the
separate AI RAW, SCAMERA ESD3D2 and original RawTherapee denoisers only on a
successfully processed HexQuad photograph. Turning it on restores those stages
according to their existing individual settings; it does not enable all of
them. Other capture backends, colour/moiré corrections and sharpening retain
their own settings. This policy is frozen in the capture parameters, copied
with them, and reported together with both blend weights.

Transport v2 uses an 80-byte header with luma/chroma weights and the neutral
point. Worker accepts old 64-byte v1 headers as 100/100 and unity neutral.
NaN, infinity, invalid weights/neutral and truncated buffers are rejected.
Host tests cover endpoint identity, independent component interpolation,
measured CFA preservation in four orientations, neutral handling, borders,
tile overlaps and actual mock-network capture. They do not validate NPU image
quality on the device.

## Test capture (v13 worker)

Select **Алгоритм ремозаика → HP9 HexQuad x2 — NPU, 6 кадров (тест)** and
turn remosaic on. Use Photo mode, HP9 tele 4× ISZ, 4:3, block4, phase0,0, <=16 MP.
Software binning and 16:9 sensor-buffer cropping are rejected to preserve Tetra phase.
The controller uses six matched, equal-exposure pre-shutter RAWs in ZSL-capable
Photo sessions, and manual equal-exposure PSL in other sessions. Short/long HDR
brackets are omitted for this backend. Other backends are unchanged.
The native job runs before ESD4D, not on an already fused image. Duplicate
frames, incomplete buffers, missing measured exposure products, variable
exposure, unsupported phase and wrong block size are rejected.

Flow: packed RAW16 transport → average black subtraction / white normalization
→ optional conservative site-response correction → 8×8 colour-independent
guides → global/local translation registration → source-coordinate CFA tags
→ six sparse RGB planes with stock normal VST → bundled x2 NPU → inverse VST
→ overlapping tile assembly → Bayer16 → existing camera colour/JPEG pipeline.
Only measured samples populate each 3-channel group; there is no RGB demosaic
before the network. Unreliable warp samples are holes, never copies of frame0.

Registration is our first implementation, **not the stock warp**: global
translation search ±96 raw pixels and a local guide field refined to an integer
raw-pixel sampling offset. It does not implement stock gyro/homography/optical
flow. It may struggle with parallax, rolling shutter, large movement or thin
moving objects. More than 40% missing samples rejects the whole result.

The x2 graph emits 576² RGB from 288² RAW. We discard a 32-input-pixel halo,
retain 224², use stride192 (32-pixel weighted overlap), and average each 2×2
linear RGB block before selecting the output Bayer channel. Thus JPEG stays at
the original RAW dimensions; **this is not a 50 MP output mode**. These tile
choices are conservative app choices, not claimed stock-exact parameters.
WB/colour matrix/lens shading remain downstream and are not applied twice.

Every capture runs the unchanged colour threshold RMSE<=0.045 on x2, with the
selected input CFA at ISO100/400 and the actual capture ISO (without duplicates).
Synthetic charts retain their [-0.05,1.25] range gate. Real-image finite outputs
use stock 16-bit IVST index saturation; NaN/Inf/unwritten output remains fatal
even in the discarded halo. Synthetic tests do not prove real-scene quality.
No silent fallback is labelled neural on failure.
The raw file is mapped read-only; no six full RGB images are allocated.
The root worker has an 840-second hard timeout and the client a 900-second
wait, with tile progress in **Vivo Neural — проверка**. This is a diagnostic
ceiling, not an expected processing time. Root, bundled QNN2.29.8 and compatible
V79/FastRPC are still required. It is not a generic GPU/other-SoC backend.

Host tests cover four Bayer orientations, colour-preserving output encoding,
uneven image sizes, overlap coverage, halo rejection, bad-output rejection,
fixed QNN client-buffer storage, source tagging and known image translations.
The actual HTP inference cannot be run on the host.

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
RGB inverse LUTs. Our boundary helper implements the non-overlap branch. The capture adapter implements its own conservative weighted overlap after inverse VST; it does not claim stock-exact fusion.
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

Open **Vivo Neural — проверка → Проверить HP9 HexQuad**. It performs 83 executions:
x1 reference in RGGB and x2 in all four sensor CFA orientations, ISO 100/400,
four neutral/coloured flats plus two independent RGB ramps; six additional
BGGR charts at ISO800 and 17 targeted ISO800 diagnostic executions below.
Six identical noiseless frames represent a stationary synthetic burst only.
They are not a substitute for real aligned frames during capture.

The report gives linear-RGB RMSE, maximum error, channel means and interior
range violations. A conservative 32-input-pixel border is excluded from scores;
NaN/Inf/unwritten values remain fatal anywhere. A chart passes at RMSE <= 0.045
with no interior range violations. `HEX SUMMARY` distinguishes PASS from FAIL;
completion of diagnostics never installs or enables a capture mapping.

### BGGR capture correction (native worker v8)

The supplied v7 capture log identified sensor CFA=3 (BGGR), unlike the standalone
RGGB diagnostic. Coloured chart 2 returned approximately (0.680, 0.351, 0.118)
instead of (0.120, 0.350, 0.650), and the capture gate correctly rejected it
(worst RMSE 0.445817). NPU execution itself succeeded. The x1 ISO400 diagnostic
failure was unrelated: capture uses only x2.

Sparse RGB channel labels alone do not make the fixed weights CFA-independent.
The new input boundary reflects complete, zero-phase 8x8-period images into
canonical RGGB support: X for GRBG, Y for GBRG, both for BGGR. Registration,
site-response estimation and tile assembly operate in those coordinates.
Output Bayer16 is reflected back to the physical sensor coordinates before
returning to Java, retaining the original CFA metadata and image orientation.
This involves no RAW interpolation, channel swapping or dropped edge pixels.

The capture chart gate uses the same orientation conversion as capture and
scores the inverse-mapped output. Limits are unchanged. Standalone diagnostics
report `x2_all_CFA_gate` separately from `x1_reference_gate`. **Отчёт последней
съёмки** preserves capture diagnostics independently of subsequent self-tests.
Host tests check canonical colour support and values, asymmetric image/ramp
orientation, translated-frame motion and Bayer output for all four CFAs. These
use mock network outputs; on-device validation of the new mapping and real
photographs remains necessary.

Remaining device checks: compare real photographs at identical exposure/focus;
verify ISO calibration, thin detail, colour, residual CFA pattern, motion, tile
seams, peak memory and elapsed time. Experimental capture is explicitly opt-in.

### Real-tile finite output correction (native worker v9)

The supplied v8 capture report confirms BGGR charts now pass (worst RMSE
0.011506), six real frames reach inference, and processing passes tile8/352.
It then hits the application's range assertion. The report does not include
the violating value or frequency, so it does **not** prove harmless overshoot,
wrong normalization, or a model malfunction.

Reinspection of the stock `FP32_FP32_GeneralNetPostprocessCL` confirms that
each output is scaled/offset, saturated to a 0..65535 LUT index, truncated, and
inverse-transformed before overlap fusion. A hard [-0.05,1.25] rejection for
arbitrary real scenes was our added rule, not this stock kernel's contract.
Capture now uses that verified saturation order. It records retained min/max,
low/high index-clipping counts, fraction, old chart-range exceedances and first
tile-local coordinates/channel; a frame total includes repeated overlap samples.
Halo is discarded rather than accumulated. Significant clipping in the new
report requires further investigation; a saved image is not proof of quality.

The input boundary also now extends complete 8x8 cells while preserving each
sample's intra-cell phase. The previous pixel-wise reflect101 altered the base
frame's CFA support in edge halos. This is an app boundary correction, not a
claim to reproduce Vivo's padding. The actual-ISO chart gate, original colour
threshold, QNN status/shape checks, nonfinite rejection, sparse registration
coverage check and independent six-frame requirement all remain active.

Host fixtures verify finite overshoot is clipped *before* IVST and 2x2 averaging,
all four CFA orientations, unchanged in-range pixels, edge-phase continuity,
and NaN/Inf rejection in retained and discarded regions. HTP execution and
real-scene overshoot statistics require the next on-device capture.

### ISO800 investigation (native worker v10)

The user's v9 report fails *before* real tiles: a constant gray target 0.2
returns mean RGB (0.189681, 0.207378, 0.125218), worst RMSE 0.065633. Five other
ISO800 charts pass the existing RMSE gate. ISO100/400 checks still pass. This
localizes the observed failure to the synthetic ISO800 path, independently of
actual RAW metadata or image borders. It does not yet identify the cause.

Stock reinspection confirms that the FP32 preprocessing branch at 0x350600
uses the configured range divided by 65535 (floating types take 0x350634),
whereas quantized types also account for tensor scale. The float postprocessing
branch at 0x3315c0 uses zero offset and the 16-bit scaling path. The XML integer
quantization scales are therefore not justification to double the FP32 input.
The normal VST fixtures at ISO800 already match the stock CPU LUTs exactly for
the documented restricted profile. Full camera-specific calibration remains
unproven; neither observation establishes why the neural output is biased.

The new `HEX ISO DIAG` block executes 17 bounded synthetic cases on the phone:
repeat the identical gray input, scan seven more gray levels, repeat gray after
different inputs, then compare three independent noisy gray bursts and two
seeds each of red/blue coloured bursts. Noise uses the existing shot/read
profile and a deterministic unit-variance CLT approximation. Each of the six
frames has independent noise. Per-frame realized noise moments, input hashes,
input-buffer mutation, output means, RMSE and repeat deltas are logged.
No noise is added to real photographs. These results do not change capture ISO,
weights, VST normalization, thresholds or the gate's outcome.

Run **Проверить HP9 HexQuad** without taking a photograph and copy the complete
report through `HEX ISO DIAG END`. Failed capture gates also append diagnostics
at the actual capture ISO, then still fail. A noisy-test pass alone must not be
interpreted as proof that the noiseless error can safely be ignored.

### Noise-profiled experimental capture gate (native worker v11)

The v10 device report is repeatable: identical clean inputs have zero output
repeat delta, and QNN never changes the input buffer. At ISO800, clean gray
levels .15/.20/.25/.50 show severe colour or spatial error. With independent
profiled RAW noise in six synthetic frames, gray .20 instead gives RMSE
0.000881–0.001003 across three seeds; two blue/red seeds give RMSE
0.001270–0.001899. This demonstrates input-distribution sensitivity of this
bundled graph/runtime, not a proven internal explanation or real-scene fix.

For explicitly selected experimental capture, the admission policy now uses
noise-profiled synthetic RAW bursts. This is a deliberate test-policy change,
not a fallback that selects whichever clean/noisy result passes. It requires
all nine charts (original six, plus gray .15/.25/.50) to pass two independent
seeds at ISO100, ISO400 and the actual capture ISO (deduplicated), in the actual
CFA. The same RMSE<=0.045 and output [-0.05,1.25] thresholds apply. The clean
chart failures remain visible in standalone diagnostics. Tests cannot prove
that spatial detail, noise calibration, motion or real-image colour is correct.
The shot/read profile is the existing restricted stock-derived profile; its
synthetic noise distribution is a CLT approximation, not a full sensor model.

Capture runs 36 or 54 synthetic executions, then processes the six original
RAW frames only if every profiled chart passes. A failed profiled gate still
appends the 17 ISO diagnostics and rejects the photograph. Nonfinite output,
input mutation, bad QNN status/shape and real-burst checks remain fatal. No
noise is added to photographs; ISO, VST normalization, weights, alignment,
clipping and the real capture pipeline are unchanged.

Standalone diagnostics retain all old clean-chart and ISO diagnostic output,
then append 54 profiled executions (137 total). Final labels explicitly separate
`x2_noiseless_stress` from `x2_profiled_ISO800_BGGR`. Run a real Photo-mode burst
with HP9 HexQuad x2, Tetra4x4, tele 4x ISZ and inspect both the saved image and
**Отчёт последней съёмки**, including tile/clipping statistics. Host mocks verify
packing against six separately generated tagged RAW frames, all CFA mappings,
positive gate traversal, swapped channels, colour bias, range violations,
NaN in halo and modified input. Actual model results require the phone.

### Linear16 output, ZSL and repeat-capture cost (native worker v12)

The first real v11 report completes all 352 tiles at ISO800. All 54 profiled
charts pass; retained output is finite, 0.027954..0.063171, with zero IVST index
clipping. The supplied archive contains a JPEG only (no source RAW), and the
other two reduced JPEGs are nearly black. The report has no stage timings.
It therefore cannot establish whether all darkness originates before the model,
in the model or downstream. It does establish that the old range assertion is
no longer the failure. The saved photograph does not establish neural quality.

A concrete precision bug was found: reconstructed floats were rounded back to
the sensor's 10-bit black=64/white=1023 range. Shadows then have only a few codes;
normalized .0001 becomes exactly black. Native output now uses linear Bayer16
black=0/white=65535 and Java supplies that same contract downstream. Half a
16-bit code is the maximum final quantization error. No display gain is invented,
ISO is unchanged, and the pipeline still applies sensor WB once. Capture logs
include input/output normalized signal statistics and individual packing/NPU/
assembly durations, plus total client time, to localize remaining darkness and
performance issues on the next real frame.

HexQuad now participates in Photo/MOTION's existing ZSL RAW ring. Its capacity is
at least eight frames; six distinct pre-shutter frames with equal measured ISO
and shutter are selected. RAW Images are paired to TotalCaptureResult by
SENSOR_TIMESTAMP (Android documents equality with generated Image timestamps:
https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_TIMESTAMP).
Latest metadata is not assigned indiscriminately to the burst. Results are bounded
and cleared with preview/session reset, and the selected result/request is
snapshotted before asynchronous processing. Missing metadata, changing exposure,
duplicate timestamps or stale frames reject selection and ask to wait for stable
AE; the ZSL path does not silently capture replacement PSL frames. Non-ZSL
sessions retain the explicit six-frame PSL path and log the source.

After a successful full-suite capture, the app process caches that ISO/CFA pair.
Subsequent shots in the same process run four fresh gray/colour checks at actual
ISO instead of 36/54 charts. Failure invalidates the entry, restart/update clears
all entries, and every native job still verifies the same bundled asset hashes.
This reduces repeated diagnostic work; the 352 real-image neural executions
remain. No measured speedup is claimed before timing on the device.

Host tests cover deep-shadow quantization and continuity, downstream output
scale through actual tile assembly, all CFA orientations, cached smoke checks,
and timestamp selection with changing/missing/stale exposure metadata. Android
build validation remains necessary; actual NPU image quality and capture timing
must be measured on the phone.

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

## Display exposure trim (30177)

The supplied 2026-09-18 logs do not show a large global signal loss in the
neural reconstruction. At 08:52 the sampled input/output camera RGB means are
`0.08435,0.16986,0.09905` / `0.08521,0.17353,0.10224`; at 09:07 they are
`0.07053,0.14914,0.09226` / `0.07036,0.14860,0.09236`. These are differently
sampled image statistics, not a pixelwise radiometric calibration. Both captures
complete without IVST clipping. The JPEG from 08:52 used measured ISO 523 and
19.999998 ms, while the later test used ISO 50 and 3.238504 ms. Do not confuse them.

The 08:52 JPEG pipeline uses the curve-based auto exposure (gain 1.1846954),
ABLC (about 0.0013 per channel), then the existing Initial color/tone rendering.
Although `tonepipeline=fusion`, `exposurefusionbayer2_enable=false`, so the fusion
node passes through. No adaptive-white-point division or noise gain clamp is
reported on this capture. Its stock comparison lacks EXIF; the stock shutter,
ISO, tone rendering and exact white balance are not established.

`Settings > Нейроремозаик HP9 > Яркость снимка, EV` provides a manual display
trim from -2 to +2 EV in steps of 0.1, default 0. This is compensation for the
rendered brightness difference, **not** a claimed fix to an identified sensor
exposure or white-balance bug. It does not attempt to remove the blue cast.

The control is snapshotted with the six-frame burst and carried in `Parameters`;
editing the setting during NPU inference cannot change the in-flight photo.
It is applied by one GPU fragment pass after tone mapping / auto exposure and
Capture One, before sharpening and the watermark. Zero adds no pass. The RAW,
QNN tensors, VST/IVST, noise controls, sensor ISO and shutter remain unchanged.
The native v17 CPU/GPU/NPU hybrid stays intact.

For encoded SDR/P3 output the pass decodes the sRGB transfer, applies a shared
scalar to all three linear channels, then re-encodes. With the tone stage off it
operates directly on linear values. Transfer reference:
https://www.w3.org/TR/WCAG22/relative-luminance.html
For `g=2^EV`, positive gain is `g/(1+(g-1)*max(R,G,B))`, preserving display white
and smoothly reducing the gain toward highlights. Negative EV applies the
linear multiplier directly. Consequently the positive EV label gives the shadow
gain, not a uniform exposure shift through highlights. Linear RGB ratios and
neutral gray stay unchanged; the input tone renderer's transfer/look is retained.

`HEX SOURCE` now reports `display_exposure_ev` and the neutral RGB point.
`SCAMERA-debug.log` records the actual `HEX DISPLAY EXPOSURE` pass. The real GLES
shader test covers 442,368 channel samples: both input domains, -2..+2 EV,
shadow and highlight ramps, saturated colors, monotonicity, white anchoring,
finite range and exact zero identity. Host tests cannot establish stock matching
on the handset; compare the same scene at 0 and a positive setting.


### Tone selector wiring (30178)

The 08:52 and 09:07 logs had `tonePipeline=fusion` but the separate
`ExposureFusionBayer2.enable` tunable was false by default. The node silently
returned its input, so the selected fusion path never produced a gain map.
This was a selector/activation bug, not evidence that every tone control was
unused or that HexQuad itself lost exposure.

Tone Pipeline now solely selects Exposure Fusion. The obsolete enable field
is removed, so an old saved false value cannot override the selector. The
postpipeline logs `Exposure Fusion active: selected by Tone Pipeline`. Other tone
modes still omit this node; the neural inference and noise controls are unchanged.

The controls in the reported screenshot were also audited:

| Control | Actual consumer | Change |
| --- | --- | --- |
| Tone Pipeline | PostPipeline node selection | Fusion now runs when selected |
| Headroom Scale / Max, Output Exposure | HeadroomRender, Sky only | Separate Sky category and explicit descriptions; existing keys/values kept |
| Gamma Coefficient, Gamma Model X1/X2/X3 | Initial gamma LUT / shadow polynomial in Fusion and legacy Curve, with ACES disabled | Separate gamma category and scope descriptions |
| Epsilon | No active shader use; only commented expressions mentioned EPS | Remove nonfunctional UI field and unused define upload |

In particular, `Output Exposure=0.80` did **not** darken a Fusion-mode photo;
that multiplier is consumed only by Sky. This is a targeted audit of these
controls, not a certification of all expert tunables.

The GLES regression renders the production exposure packing, fusion and gain
map shaders in FP16 on 32 flat-field fixtures, including black and white. It
checks unity for identical exposures, finite bounded gains, spatial uniformity,
and response to changed exposure-selection weights. It does not replace a
full Android pipeline test or a real scene comparison. Android CI also builds
the changed Java code. Start the first device comparison with the new HP9
brightness correction at 0 EV: activating Fusion itself changes tone, and its
pyramid adds work to the postprocessing stage.
