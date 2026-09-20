# NICE HDR neural capture port: verified boundaries

## Verified from the supplied files

83 NICE VDNN files (482,698,532 bytes) were extracted from the user's existing
models archive. All 83 wrappers and their embedded QNN V3 metadata were parsed.
64 expose FLOAT32 inputs; 19 use QNN datatype 0x416. Wrapper datatype codes are
not QNN datatype codes. One wrapper's output dimensions differ from the actual
graph (the previously investigated HP9 HexQuad x2); runtime metadata must win.

For the IMX06C forward HDR candidate:

- source: MainCamera/nice_hdr_imx06c_general_forward_bayer_x1.vdnn;
- source SHA256: 7c4663c3c03394841b92cbcc207ad6bd610369c004d45d913aa950cc61511a16;
- embedded context: offset 2772, 5,840,224 bytes;
- context SHA256: a551304d938af0cab76091557414f46a64cae05aaf68ef8030c1bcc42decac8c;
- graph: nice_hdr_imx06c_general_forward_bayer_x1_quant_8w16a32b;
- input inputs_0: FLOAT32 [1,544,544,22]; output tail_conv_1_0: FLOAT32 [1,544,544,3];
- embedded build: v2.28.0.241029232508_102474, target sm8750.

MainCamera/NiceCREConfigHdrForward.xml HDRConfig declares seven inputs,
FrameTypeOrder 1,1,1,1,2,0,3 (ref=3, refn=3, clip=0, useawb=0), VST mode 2,
usesqrtev=1, no input clipping, inputScale 1/32767, outputScale
0.00008737626194488257. Those XML scales are not automatically the public QNN
FLOAT32 client-buffer scale. Decoded GeneralNetPreprocessCL_7 confirms seven consecutive triplets and one
constant factor*clipMask channel. Each triplet contains tagged Bayer/planar
samples, not an automatically demosaiced RGB frame. Frame-code routing is recovered below; exact per-shot factors and active stock
kernel selection remain unverified.

The earlier runtime captures prove NICE libraries were loaded, not which model
was executed for an individual photograph. Static configuration candidates are
not presented as observed per-shot routing. NICE also has separate LCA, CRE,
tone/TCE and motion/IC paths; this probe covers one CRE graph only.

## App-contained execution prerequisite

Settings -> Vivo -> Diagnostics -> NICE HDR runs in a dedicated :vivo_nice
process. Camera initialization is skipped. There are two explicit buttons:
ordinary app-UID execution, and root execution using the existing
VivoNeuralClient -> VivoNeuralWorker -> native worker mechanism proven for HTP
remosaic. The root worker verifies the same bundled hashes and executes the
same NICE probe; it has a 150-second native alarm and a 200-second client bound.
No firmware model reads, property writes or SELinux changes are made. The model and the hash-pinned QNN 2.29.8 runtime
are read from APK assets. The existing public FastRPC driver is a platform
dependency; it is not a bundled Vivo image-processing algorithm.

The probe verifies hashes, parses actual QNN metadata, checks tensor names,
dimensions/type and runtime build, creates the backend/device/context, and
executes two synthetic uniform tensors. Each output is poisoned before dispatch;
unwritten/nonfinite values fail the test. These tensors test execution only:
they do not assert valid photographic preprocessing, quality or stock parity.
The user supplied a successful 30230 root-worker report on PD2454 Android 16:
the original graph executed twice with QNN 2.29.8; 887,808 output values per run
were finite/written. Uniform 0 input yielded min/max/mean
0.003212/0.014793/0.007921; uniform 0.125 yielded
0.396729/2.322266/1.228060. This verifies execution compatibility on this device,
not photographic input correctness. The reported root=0 is UID 0.

Every stage is saved before the next native call. A native crash or the bounded
timeout leaves the last report; reopening the entry allows copying it. Closing
the activity terminates only its dedicated process. Library handles remain
resident until exit; QNN context is destroyed before model/client memory.

## Remaining device validation and stock parity

The original forward graph is now connected to Camera2 capture, as detailed below.
Runtime compatibility and recovered VST/IVST primitives are verified. Real-photo
quality, highlight artifacts and latency still require a new capture on the device.
Alignment, per-shot radiometry and tile boundaries use explicit SCAMERA adaptations.
Stock MEE/LCA/tone model execution and comparison against stock intermediate tensors
remain unverified; this implementation does not claim complete Vivo parity.

The ordinary autonomous HDR path retains the 30230 artifact fixes. Original
NICE capture is a separate opt-in switch, described below. A runtime PASS alone is not
permission to claim the neural photo pipeline is complete.

## Reproduction

`tools/inspect_vivo_nice.py` inventories supplied VDNN files and can extract only
the pinned forward candidate. Keep binary models outside git. The packaging
helper now accepts `--nice-dir`, containing `nice-main-forward-v79.bin`.
`tools/check_vivo_nice_probe.cpp` uses a mocked QNN backend under ASan/UBSan to
check argument layout, tensor validation, dispatch, poisoned output, resource
cleanup on failure and binary lifetime. This is explicitly not device inference.


## Additional static boundary

`niceKernels.bin` is a compiled OpenCL cache. CRE's cache reader at 0x3a67e0
explicitly falls back to source compilation on missing, illegal-size/hash or
old-source cache entries. Its absence is not evidence that original kernel
sources are missing. The embedded-source decoder has now been recovered; see the evidence below.
CPU function 0x2da2c0 generates three-plane VST LUTs for integer source samples.
A FLOAT32 network input does not imply floating-point source RAW samples.


## Recovered preparation primitives

`tools/decode_vivo_nice_kernels.py` checks CRE SHA256
41b277753f7fedbe4dda1e1c4b76d2086d6e79768904d8a4922b48a0e023e76e.
Function 0x3a7270 copies embedded strings and translates each byte at 0x3a73e8
using the 256-byte permutation reached through RELATIVE relocation 0x47acb0
(target 0x487a98). The script writes readable evidence fragments plus provenance;
these are not asserted to be individual compilation units. No donor payload is
committed to git.

`app/src/main/cpp/vivo-nice-preprocess.h` independently implements:

- Mode-2 VST LUT generation with explicit reference/normalization noise slope,
  normalization variance offset, black level, exposure ratios, gains and norm.
  Double intermediates, float arithmetic and final fused rounding follow the
  inspected 0x2da2c0 branch; other VST modes are intentionally not inferred.
- Seven-frame packing corresponding to FP32_GeneralNetPreprocessCL_7. The low
  sample bits index the LUT; bits 14-15 identify R/G/B/invalid. Sparse Bayer
  repeats a plane pointer; planar RGB supplies explicit plane offsets. Each
  frame occupies channels 3*i..3*i+2; the last channel is factor*clipMask.
  Invalid tag 3 is rejected before LUT access, avoiding the donor's speculative
  fourth-plane access before masking. Strides are elements, not bytes.
- General float-output IVST LUT application: explicit scale/zero point,
  clamp to uint16 range, truncate then right shift, separate colour LUTs,
  optional row*column overlap-add and caller-supplied output limits. Values
  above 1 survive when the caller's HDR limit permits them. The mode-2 generator is now recovered too; see capture validation below.

Validation: `tools/check_vivo_nice_vst.py <CRE.so>` emulates the original ARM64
function with Unicorn; only the noise lookup is supplied as explicit test
coefficients. 18 cases / 193,536 LUT entries matched exactly (0 uint16 units
maximum error; allowed tolerance 1). Cases cover 10/14-bit inputs, 15/16-bit LUTs,
black levels, exposure ratios, AWB gains and exposure multipliers.
`tools/check_vivo_nice_preprocess.cpp` passes ASan/UBSan on tagged Bayer, planar
RGB, frame/channel order, ROI/stride, invalid tags, bounds, LUT clipping,
IVST truncation, HDR values, overlap-add and nonfinite rejection.

Further host-binding evidence: General preparation function 0x352110 builds
clipMask by truncating a host mask times 65535 at 0x352200..0x35220c. The factor
at 0x352324 has a dtype-dependent denominator; the float branch divides the
host numerator by 65535, while types 3/4 include additional host scale terms.
Do not directly substitute XML inputScale or outScale for these bindings.
The source confirms formulas. Exact stock tile planning and per-shot calibration
remain distinct from the connected Camera2 adaptation. The connected Camera2 adaptation and its remaining stock-parity limits are
described below.


## Capture integration (30235)

The original forward graph is now connected behind the separate
`pref_vivo_nice_enabled` switch under Vivo / Autonomous HDR. It defaults off.
It accepts Camera2 Bayer IMX06C IDs 3/4, up to 16 MP, with measured RAW exposure
metadata. Main and ultrawide forward VDNN hashes are identical in this donor.
Tetra/quad/remosaic/binning and other sensors are rejected; a visible message
reports fallback to the existing autonomous HDR when NICE cannot process a shot.

The pipeline is Camera2 RAW -> tagged warp -> mode-2 VST -> NHWC22 -> original
QNN graph -> mode-2 IVST -> weighted tile overlap -> sensor RGB -> WB/normalized
LSC -> existing Vivo-group denoise/tone/sharpen controls. It bypasses Bayer2Float,
AMaZE and ABLC for successful neural RGB. The ordinary RAW buffer remains the
reference for DNG saving; this is NOT a neural Bayer DNG reconstruction.

Original routing evidence at 0x35d828/0x35b9ac confirms type 1=N, 2=L, 0=S, 3=ES.
The forward slots are N,N,N,N-ref,L,S,ES. The donor explicitly copies S when ES
is absent (0x35dab8) and repeats available frames if a group lacks requested
members. SCAMERA requests at least four N, one L and one S; it logs actual slot
sources and any repetition. Every frame is transferred before releasing RAW
memory; a failed worker leaves the sources available for ordinary HDR recovery.

The Camera2 adaptation uses the recovered IMX06C HDR noise polynomial and
normalizes exposure products against the shortest frame for the unit-range
inverse transform, then restores normal-reference scene radiance. The tensor
sqrt(EV) factor and its reciprocal keep values above normal white representable.
Black removal uses Camera2 per-site levels. This is an explicit Camera2
radiometric adaptation, not a claim that proprietary runtime metadata,
OpticalBlackCorrectHDR and gain/ISO adjustments match stock bit for bit.

IVST generator 0x2dc834..0x2dc970 is now independently implemented and compared
against original ARM64 execution: 423,936 entries, zero absolute difference.
VST still matches 193,536 entries exactly. Header/RAW bounds, invalid calibration,
tagged packing, output poisoning, four-tile blending and WB/LSC are tested.
The full host chain with a mock graph preserves a clipped-reference HDR signal
of 1.6 with maximum error 0.000041962; this proves plumbing, not neural quality.

Alignment uses SCAMERA's CPU coarse-to-fine guide matching and local warp field.
The 544 tile implementation uses a 16-pixel margin and 16-pixel weighted overlap
with explicit edge reflection; these are tested SCAMERA boundary choices based
on the donor's overlap setting, not a recovered per-shot proprietary ROI planner.
Stock MEE/LCA/tone neural models are not claimed to have been ported by this work.
Only the original forward CRE neural graph runs. Photo quality, latency and
artifact freedom for this connected path require the user's physical device.

The root worker has an 840-second hard limit and a 900-second client limit.
Models/QNN are bundled and hash checked. The FastRPC platform driver remains a
system dependency. Native inference and file copies run on the processing
thread, not the UI thread. Report lines go to the detailed SCAMERA log and a
separate NICE capture report; the old runtime self-test cannot overwrite it.

## Tone runtime continuation

`vivo-nice-tone-contracts.json` records exact source/context hashes and actual QNN
metadata for five supplied candidates: FastTM, portrait/nonportrait Adams, and
HDRNet S1 coefficient/weight graphs. `tools/extract_vivo_nice_tone.py` extracts
only these contexts after validating their hashes and graph descriptors.

The diagnostics screen adds a separate root tone-model check. All five models
and the same pinned QNN runtime are APK-contained. It checks graph names, tensor
names/counts, shapes and types before dispatch. Adams uses two UFIXED16 inputs
(1024x1024 RGB and 512x512 mask), rather than interpreting its wrapper as a
single FLOAT32 image. Raw quantized uniform codes are synthetic diagnostics;
the runtime quantization descriptors are printed without claiming scene units.
Float outputs are poisoned with NaN. Quantized outputs are run twice for the
same input with distinct output poisons, rejecting unwritten/nondeterministic
results. There are two synthetic input fixtures per model. Exceptions are
reported per model; the bounded native process reports the last stage on crash.
Native/client/activity limits are 360/420/480 seconds. The capture report remains
separate. No new tone model is automatically enabled for real photographs.

Concrete mismatches prevent treating XML as a ready photographic contract:
FastTM actually outputs `_376_0`, not XML `371`; sceneseg actually has 256x256
input, not the LCA XML's 512x512 declaration. The available night models are
2-input Adams graphs, not the coefficient/weight filenames still referenced in
HDRNet configuration. The supplied S1 HDRNet pair is recorded as a candidate;
this does not establish it as the active TCE model for this firmware mode.

The MEE library exports face magic, TCM and USM/pyramid sharpening. Its supplied
configuration describes those roles; it is not evidence of an original RAW
motion-estimation engine. Prior loose references to "MEE alignment" should not
be used to infer a stock alignment contract. LCA's supplied configuration requests
scene masks, highlighting another distinct input contract.

Readable original tone/TCE OpenCL sources were recovered locally, including
exposure preparation, bilateral-grid application and CCM-protection kernels.
They remain private evidence, not bundled donor algorithms. Model selection,
mask semantics, normalization and postprocessing still need verified binding
before photographic use. First obtain the new tone runtime report on the phone;
30235's successful CRE test does not verify these additional graphs.
