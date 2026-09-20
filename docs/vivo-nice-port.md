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
The current forward slots are N-ref,N,N,N,L,S,ES (corrected after 30249; see below). The donor explicitly copies S when ES
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


## Sensor-independent Camera2 capture and stage diagnostics (worker v18)

The capture gate no longer uses physical camera IDs or an IMX name. Ordinary
Bayer RAW up to 16 MP is accepted on any camera, including HP9 telephoto, provided
Camera2 supplies valid measured exposure, sensitivity and SENSOR_NOISE_PROFILE
for the selected reference. Quad/Tetra RAW, remosaic and software binning remain
unsupported input layouts. Select the ordinary RAW mode for NICE on HP9.

Transport version 2 carries the reference's measured noise slope/offset and a
diagnostic flag. The mean Camera2 channel noise profile replaces the IMX06C
polynomial. VST normalization now uses this current measured profile; missing
or invalid noise metadata produces an explicit error and the existing autonomous
HDR fallback. Version 1 is retained only for compatibility with old transports.
This is an experimental cross-sensor adaptation with unchanged model weights,
not a promise of HP9-trained inference or a correction of observed color defects.

`NICE — сохранить этапы обработки` defaults on for this diagnostic build. Each
shot writes `Download/SCAMERA/NICE-<timestamp>-<id>.zip` with:

- Camera ID, CFA, dimensions, exposure, ISO, noise, black/white levels and color metadata.
- A black-subtracted Bayer-cell reference preview without white balance.
- Full 544x544 graph output from the first and middle tiles before IVST (PFM).
- Native reconstructed RGB after IVST and sampled GPU outputs after RGB import,
  denoise, exposure, headroom rendering, false-color suppression and sharpening.
- PFM float data retaining negative/HDR values; clipped PNG previews, stage range
  summaries and native execution report. Previews are at most 1024 pixels per side.

GPU diagnostics read sampled rows with a temporary read framebuffer and restore
its binding; they do not change processing shader or texture state. Export costs
time and storage and can be disabled. Files are written on-device only, never
uploaded automatically. Diagnostic export errors are logged without failing the
photo. The saved DNG remains the reference RAW, not neural output.

Host checks exercise both legacy and Camera2 noise profiles through VST/IVST,
HDR retention, snapshots, malformed version-2 headers and a non-IMX ISO range.
The Android regression test uses camera ID 77 to prevent a sensor allowlist
from returning. Image quality and real HP9 capture still require device testing.


## Fractional Bayer alignment and shutter recovery (30242)

The supplied 30241 diagnostic capture has contour-like colour artifacts already
in `02-after-ivst`, while the reference Bayer preview is clear. This localizes
the problem before GPU colour/tone and JPEG; it does not by itself distinguish
alignment, graph calibration and inference. Review found that the continuous
local warp was rounded to individual sensor pixels, changing the Bayer phase
at half-pixel displacement contours. The replacement samples each reference
site's same-colour 2x2 sublattice bilinearly and retains its tag. Tests cover all
four CFA layouts, continuous colour ramps across fractional shifts, identity,
borders and invalid coordinates; the existing mock HDR round-trip still passes.
The trained graph's actual photo quality requires another device capture.

The shutter now reports whether it accepted a press, so a busy rejection cannot
leave the UI button disabled. Request failures and aborted ordinary RAW bursts
release pending capture state and show an error. All AF/AE precapture states
have bounded waits, with a session/shot-scoped callback deadline; a timeout no
longer starts a second burst through the AF branch. An empty delivered burst is
reported explicitly. These changes apply to auxiliary cameras as well as the
main camera, without camera-ID special cases. The supplied archive contains no
HP9 attempt log, so its exact failure path remains unconfirmed. `SHUTTER` log
lines record camera ID, capture state and busy flags for the next device check.


## Shutter-bounded selection and fixed model normalization (worker v20)

The NICE hybrid path now takes the newest four timestamp-paired N RAWs up to
its shutter-time preview timestamp. Image delivery order cannot admit a later
frame. The newest selected RAW metadata sets the L/S bracket base on every
shot, replacing the previous series' cached exposure. The forward graph uses
four N slots, so selecting eight and feeding the oldest three was unnecessary.
This remains hybrid N-before/L/S-after capture, not verified stock Vivo timing.
Stock dynamic bracket scheduling and all-exposure prebuffering remain unported.

The graph's normalization scale is fixed to the recovered IMX06C ISO-50
baseline rather than the current Camera2 noise profile. The frame noise used
in the transform and inverse remains identical. On Vivo PD2454 IDs 3/4 the
matching NoiseInfoHDR polynomial replaces the generic Camera2 profile. Other
sensors retain an explicitly experimental measured-noise adaptation; the
bundled forward graph is still the IMX06C model, not an HP9 model. Matching HP9
weights and their input calibration remain required for stock-equivalent HP9.

The diagnostic ZIP exports through a single bounded background queue. A full
queue retains its completed diagnostic directory and logs its location; it
never blocks capture or shares a mutable Job with another photograph.

Host tests verify the fixed tensor scale independently of round-trip inversion,
HDR retention, four-tile blending, CFA warp continuity, and shutter cutoff/base
selection. Real NPU photo quality and disappearance of the reported artifacts
still require a fresh device capture; old model outputs cannot validate changed
model inputs. No tone model or stock-parity claim is introduced.

NICE's hybrid bracket also uses the existing timestamp router for in-flight
preview rejection, removing both HAL abort/flush calls on that path. Late
preview images cannot take L/S slots. Processing waits for N plus bracket RAWs,
not just the bracket count (which was already satisfied by buffered N frames).
The snapshot's normal RAW metadata seeds processing instead of the last S
result. The other capture modes retain their prior abort behavior.

## Second device regression: border holes (worker v21)

The two SCAMERA(5) captures (2026-09-20 11:19:50 and 11:20:46) are both
camera 3, CFA 3. Their reference Bayer previews remain plausible, but the
reconstructed native RGB is already cyan before GPU colour and tone. This
does not establish whether the model's input or output colour contract is
wrong; swapping RGB channels or adjusting white balance without resolving
that contract is not a verified fix.

An independent boundary error was identified: the adapted warp emitted tag 3
(zero tensor input) whenever donor coordinates crossed the sensor border.
The recovered `vivoRawBackwardWarp2CanvasBayerBufferCL` reflects coordinates
instead. The adapter now reflects its same-colour bilinear taps, retaining
its existing interpolation but removing these artificial holes. Constant
separate colour planes stay constant across all edges, fractional shifts,
and repeated reflections for all four CFA layouts in the host regression.
That test does not establish disappearance of the device artifacts.

Unresolved: the cyan output, stock warp/interpolation selection, dynamic
ZSL/bracket scheduling, sensor-specific model selection and real-image
tone-model integration. This is a bounded border fix, not stock equivalence.
Completing the port requires the original CRE/tone host libraries and model
assets from `Vivo-camera-files-20260919-224713.zip`; the current workspace has
recovered excerpts/configs and selected weights, not that full archive.


## Full donor recovered: RGGB and role-specific warps (worker v22)

Source: `Vivo-camera-files-20260919-224713.zip`, Google Drive file
`1G_6pkx1gfytL3pFn-ckT3OPStfXgD6_B`. Retrieved on 2026-09-20. The archive has
606 libraries, 1,873 config files and 466 model files. Original paths are
preserved in the local extraction. The earlier missing-archive blocker is
resolved; the unresolved work below is implementation/verification work.

Pinned `vendor/lib64/libvivo_nice_cre.so` SHA256:
`41b277753f7fedbe4dda1e1c4b76d2086d6e79768904d8a4922b48a0e023e76e`.

Confirmed host/kernel contracts:

- `0x2d08a4` is the UnpackAndToRGGB path. `0x2d1d38..0x2d1d98`
  sets the sensor phase offsets. Vivo enumerates BGGR as 2 and GBRG as 3;
  Camera2 enumerates GBRG as 2 and BGGR as 3. The adapter uses Camera2's
  actual red-site coordinates, without passing its enum into donor code.
- `0x3c5ec0..0x3c5f1c`: warp method 2 dispatches
  `vivoRawBackwardWarp2CanvasOrder`. Its Bayer buffer kernel transforms a
  2x2 cell origin, copies adjacent sites and tags the donor's colour.
- `0x3c5f20..0x3c5f8c`: methods 6/7 dispatch
  `vivoRawBackwardWarp2CanvasAlignPerChanInterp`. The Bayer kernel emits
  three dense planes, with half-pixel coordinates and per-row green lookup.
- MainCamera/NiceCREConfigHdrForward.xml selects `warp=2`, `swarp=6`, with
  `lwarp` defaulting to `warp` (`0x3cffbc..0x3d0000`). Model slots remain
  N-ref,N,N,N,L,S,ES (reference position corrected after 30249). The previous adapter incorrectly used sparse Bayer
  with the same reference-phase interpolation for every slot.
- GeneralNetPostprocess host bindings set rIndex=0, gIndex=1, bIndex=2.
  For example `0x33de78..0x33deb0` initializes the indices and
  `0x33e5e4..0x33e628` binds arguments 17..19. No output R/B swap is justified.
- `pixelShiftf32`/`icIVSTf32` restore the sensor origin by sampling
  `max(dst-offset,0)`. The adapter restores it in-place after stitching.
- `0x2d888c..0x2d88dc` confirms normalization ISO 50 for VstBaseISOMode=0.

The capture adapter now uses an RGGB view of all RAWs, warp=2 for N/L, and
three-plane swarp=6 for S/ES. It retains original RGB output order. Diagnostic
exports also include first-tile N-ref and S input triplets, making sparse vs
planar input inspectable. Host checks use unequal colour planes, independent
known coordinate values, all Camera2 CFAs, actual packing and HDR inversion.
They use mock inference and cannot certify actual NPU photo quality.

Remaining stock differences: motion estimates still come from SCAMERA, as do
its tile plan, N-buffer/L-S-post-trigger capture scheduling, and photographic
tone path. Full stock ZSL, dynamic bracket strategy, NICE Tone/TCE masks and
HDRNet integration are not implemented by this change. Do not label this
build stock-identical or claim the cyan device regression is verified fixed.

Independent kernel-body check:
`python tools/check_vivo_nice_stock_warp.py /path/to/decoded-source.txt`
executes the recovered OpenCL arithmetic as C++ with type/builtin shims. On
random 14-bit RAW values and 28 positive/negative translation pairs, all
458,752 ordered-Bayer and planar-RGB samples matched the adapter bit-for-bit.
This covers translation sampling, not the original homography/flow estimator
or GPU compiler rounding under arbitrary projective transforms.


## Original forward profile and tile planner (worker v23)

The user's `SCAMERA(6).zip` capture still contains coloured rings in the raw
NPU tile, before IVST and GPU tone processing. The Bayer reference does not
show these rings. This localizes the observed failure to NICE preparation or
inference; it does not establish that the changes below eliminate it.

The connected HDR adapter now applies the missing 1.1 normalization coefficient.
MainCamera and UltraWideCamera **HDRConfig** both declare againList=0,5 and
againCoeff=1.1,1.1. CRE selector 0x36cbe4 chooses the coefficient and stores it
at state+0x778; 0x2d8a30 multiplies the ISO-50 normalization by it. This is
separate from the XML vstNormCoeff=1 base coefficient. Both VST and IVST use
this corrected normalization.

Tile assembly now follows CRE BlockInit 0x2fe8f8 and planner 0x3dba84. A 544
input has 16 pixels of context on either side of a 512-pixel work area. First
and last input tiles anchor to the actual image boundaries; output crops are
asymmetric. Overlap/useFusion defaults to zero in this profile, so adjacent
output tiles are copied, not blended. The old 496-pixel step, negative-origin
first tile and weighted output overlap have been removed. At 4096x3072 this
produces 48 inference tiles instead of 63. Images smaller than one model tile
still require the adapter's explicit CFA-preserving reflection.

`tools/check_vivo_nice_stock_profile.py <libvivo_nice_cre.so>` hash-checks the
same donor as the VST oracle, executes its ARM64 planner unmodified, compiles
the connected C++ planner and compares input/output offsets, crops and work
sizes. 289 image dimensions and 1,296 descriptors match exactly, including
512/528/544 boundaries, narrow final strips and 4096x3072. This supersedes
older sections describing SCAMERA-owned tile planning.

Diagnostic exports now retain `input.nch`: the exact 128-byte native header
and all seven RAW16 planes, moved before worker-job cleanup. This allows
replaying the actual model input instead of reconstructing a burst from only
one DNG. Export remains on the existing bounded background queue.

Full-stock differences remain: SCAMERA motion estimation, Camera2 radiometry,
ZSL selection and bracket scheduling, and the photographic tone path. Tone
models' synthetic runtime checks are not photographic integration. Original
TCE FastTM config specifies NormFloat and NormValue=15615 (not 16383 or 65535);
its original host divides uint16 RGB by that value at 0x39dee4. Its low-resolution
output feeds a log-domain gain-map and guided-filter path. Simply substituting
the model for the existing RGB tone shader would use the wrong contract.
The recovered sources and host bindings are being inspected; FastTM, Adams
and HDRNet are not connected to photo processing by this commit.


FastTM arithmetic verification:
`python tools/check_vivo_nice_tce.py <libvivo_nicetce.so> [--sources <directory>]`
checks TCE SHA256
`9f5deac3bc68fc86fcf16b98f43c232a9642bc309c7d5d42c88d6a4b596b892d`
and executes original ARM64 functions 0x39dee4 and 0x39e670. NormFloat divides
interleaved uint16 RGB by 15615. Log-output conversion clamps float RGB to
[0,1], truncates float32 multiplication by 9937, indexes the original uint16
exponential table at 0x4c5aa, then limits its result to 16383. This is distinct
from the Gamma-output branch's direct multiplication by 16383.

134,880 channel samples match bit-for-bit, including all uint16 input values,
negative/above-one outputs, scalar/SIMD widths and untouched row padding. No
original instructions or imported functions are replaced. Optional extraction
writes the embedded OpenCL strings with their virtual addresses and SHA256
provenance; binary payloads remain outside git. This verifies recovered tone
arithmetic only, not routing, masks, colour calibration or neural photo quality.


## SCAMERA(7): reference-order regression identified; release withheld

The 30249 capture still has coloured concentric contours before IVST. Unlike
previous uploads, this diagnostic contains all seven RAW planes in `input.nch`.
Replaying SCAMERA's local shifts shows the first donor's Bayer-phase contours
at the same locations/shapes as the model-output artifacts. This establishes
a concrete preparation fault; no corrected NPU output has yet been captured.

Earlier interpretations of XML `ref=3, refn=3` as input slot indices were wrong.
Parser 0x3d38a4 stores these fields as exposure selectors. CRE 0x35e604 indexes
radiometric EV/ISO level arrays with them. `tools/check_vivo_nice_reference.py`
executes this complete original function without replacement instructions:
all 25 combinations of selectors 0..4 select the corresponding five EV/ISO
levels independently of the seven-frame input count.

Frame ordering is separate: SelectFrame 0x3617e8..0x361830 swaps the chosen
reference to vector index zero. ExceptNode 0x2dd594..0x2dd5c0 enforces zero
as its reference index. The connected code had incorrectly warped the first
network input and put the unwarped reference in slot three. Its reference is
now first in Java transport, native guide generation, identity warp, ISO/noise
selection and diagnostic snapshots. NCH version 3 records this convention;
versions 1/2 rotate the old four N slots and their RAW/exposure/ISO together
on load, so old diagnostic bursts retain their correct reference.

`tools/replay_vivo_nice_reference.cpp` replays the actual saved burst through
the capture preparation code, stops before inference and compares the first
triplet with the original unwarped N-reference PFM. On SCAMERA(7), all 887,808
values match exactly (maximum difference zero). Transport migration and HDR
round-trip checks pass ASan/UBSan. This does not certify final image quality.

Global alignment is still incomplete: the donor's selected warp consumes a
homography; the adapter's local 64-pixel shift grid is not stock motion
estimation. MainCamera HDRConfig specifies 1000 corners, 32768 candidates,
minimum distance 16, quality .01, Harris disabled, LK epsilon .01 / 20
iterations / ratio .7, minimum 50 points and RANSAC 100 iterations / confidence
.995 / threshold 3. Substituting these constants into a different estimator
would not establish full equivalence. Stock scheduling and Tone/TCE integration
also remain incomplete. No new test APK is requested or delivered for this
partial correction; the user explicitly requires finishing the full port first.

The full donor archive includes libraries, models and configurations but no
camera APK/JAR. Its `camera-apk-paths.txt` contains only
`cmd: Failure calling service package: Failed transaction (2147483646)`.
That archive's contents do not establish whether the stock APK was supplied
separately. The user supplied `vivo-camera-app-NUvSvK.tar.gz` on September 20;
its `apks/8-VivoCamera.apk` is the complete stock application from
`/system/app/VivoCamera/VivoCamera.apk`. All nine collected APK files match
the archive's `SHA256SUMS.txt`.

The stock APK is 436,720,224 bytes, SHA-256
`aaf998e96056b6c56b01d0fbd9b962ed281c8d61210895acc11b4cc7a98f9bdd`.
Python ZipFile successfully reads its final directory and verifies every
entry's CRC. It contains classes.dex through classes9.dex, and no embedded
`.so` entries. Its build fingerprint matches the PD2454 Android 16 donor
(`compiler260106191612`). The complete app code is now available for studying
capture-request orchestration; another APK collection is not a prerequisite.
This acquisition does not establish completion of scheduling, motion or tone
integration and does not justify releasing a partial test APK.


### Complete APK follow-up: scheduling and radiometric domains

The complete stock APK decompilation completed with 332 reported errors;
individual affected methods require DEX/disassembly inspection. Do not treat
the Java output as complete or automatically correct.

Recovered, readable call sites establish the following:

- `com.android.vcamera.util.Utils.isVCF2AndHigherVersion()` tests HAL version
  flags 16, 64 or 128; VCameraInfo reads `com.vivo.halversion` as a long.
- `VOuterCaptureRequest.Builder` skips request-side ZSL translation for those
  versions. `Vcf2SnapNonCoreHandler.onCaptureProgressed()` instead reads
  `vivo.control.RequestLeftInThisSnapshot` from the first partial result and
  invokes `updateZslNumber`. Array entries 0 and 1 become forward and backward
  counts, respectively. A missing array falls back to 0/1. These are results
  of the service's decision, not a stock fixed burst plan to copy.
- The legacy branch instead reads that key from the request, then falls back
  to `CONTROL_ENABLE_ZSL`. Copying this legacy path unconditionally would not
  reproduce VCF2 behavior.
- `Vcf2CameraManager` opens and initializes the firmware-provided
  `android.hardware.vivocamera.VivoCameraManager`/`VivoCameraDevice`, with VIF
  partial results, completed results, frame completion and buffer callbacks.
  The APK caller does not contain the whole firmware processing implementation.

The original CRE selector at 0x35e604 keeps refEV (+0xe8), refNEV (+0xf8),
refEv0EV (+0xf0) and output-map EV (+0xec) separate. All are normalized by
exposure-level array entry zero. Output-map selection also depends on the
model mode and first frame type. `tools/check_vivo_nice_reference.py` now
executes 4,500 combinations of the unmodified original function, including
non-unit bases, independent normal levels and output selection branches;
all pass. The previous 25-case check used a unit base and did not test these
branches. It must not be taken as validation of the current adapter's use of
one `range` variable for these distinct domains.

The native capture adapter still conflates those radiometric domains, in
addition to the documented alignment and Tone/TCE gaps. The misleading
comment that ref/refn identify a normal-frame slot has been corrected. No
photographic behavior change or completed port is claimed by this follow-up;
no new APK has been built or released.


### Forward exposure-domain implementation (worker v25, no APK)

The fixed forward adapter now keeps normalEV (N/S) separate from
normalizationEV (L/S). The ES/S/N/L level ordering is visible in the original
0x35d828 routing and 0x35e2ac level builder; XML ref/refn=3 selects L when all
four groups are populated. ExceptNode 0x2ddf14..0x2ddfc8 rebases on selected S,
sets ES EV to one, and separately divides refMapEV/refNEV/refEv0EV. The adapter
preserves the two float divisions of this rebase rather than collapsing them.
`check_vivo_nice_domains.py` compares its nine values directly with those
original ARM64 instructions and original getters/setters: 103 cases pass
bit-for-bit, including unequal S/ES and non-binary exposure ratios. This is a
selected-S arithmetic-block test, not a full ExceptNode execution.

VST/IVST noise normalization and public sqrt(EV) now use L/S; normal-reference
output radiance remains N/S. The mask retains separate normal and normalization
terms and the donor's double-to-float ordering for the noise offset. Forward
ref/refn noise comes from L. NCH v4 explicitly transports that profile; v1-v3
continue to load, but old transported N noise with unequal N/L ISO is rejected
before inference because the missing L noise cannot be recovered reliably.
The selected unwarped image remains input slot zero; this is independent of
radiometric reference selection. Java and native protocol changes are paired.

Host integration checks use a mock graph and cover HDR radiance 1.6, separate
RGB planes/CFA layouts, stock tile crops, distinct S/ES, legacy header migration,
v4 profile association and rejection of missing L calibration. These tests do
not establish corrected real NPU output or disappearance of the rings. Saved
pre-fix input tensor snapshots also have the old normalization: the historical
reference replay's zero difference is not expected after this domain change.

Still incomplete: motion-derived selection and failed-L alignment behavior,
stock dynamic capture scheduling, and the complete color/segmentation/TCE tone
chain. No Actions APK is produced for this bounded source correction.

### Projective donor sampling (preparatory motion port, no APK)

`vivo-nice-homography.h` represents the original backward mapping as eight
row-major float coefficients (implicit h22=1), plus `upRatio`. The two new
projective samplers accept this mapping directly. N/L warp=2 transforms each
2x2 cell origin, divides by upRatio, rounds once, then preserves donor Bayer
labels. S/ES swarp=6 instead adds the half-pixel offset before upRatio division
and uses the original dense three-plane interpolation. Both retain the donor's
single reflection followed by clamping. Nonfinite matrices, singular evaluated
coordinates and unsafe float-to-integer coordinates are rejected; those checks
are memory-safety checks, not a reconstruction of stock alignment acceptance.

`check_vivo_nice_stock_warp.py` now compares 3,473,408 tagged sample values with
the recovered OpenCL bodies executed on the CPU. Cases include existing
translations and 46 matrices at four upRatio values, with rotation, anisotropic
scale, shear and perspective, plus invalid-matrix rejection. All samples match
bit-for-bit. The `--sanitize` option checks both the adapted samplers and the
original kernel bodies with ASan/UBSan (LSan disabled on the ptraced host).
This does not establish GPU compiler arithmetic parity or photographic quality.

The existing capture alignment still produces a local shift grid. The new
projective entry points are deliberately not selected by capture until the
matrix estimator, coordinate-system/ROI conversion, validity checks and
failed-frame policy have been recovered and connected. No assertion that the
motion estimator has been ported follows from these sampler tests.

Additional source locations for that work: the CRE global-alignment path at
0x29a96c calls `vivoFindHomographyUp4` at 0x28ef00. That function reads RANSAC
parameters from its parameter object's +0x3c/+0x40/+0x44; the wrapper converts
the resulting nine doubles to floats at 0x29a9cc..0x29a9f8. Calling this internal
function in the Android worker is not yet implemented or ABI-validated.

### Original corner detection, LK and RANSAC execution (2026-09-20)

`tools/check_vivo_nice_motion.py <libvivo_nice_cre.so>` now executes the pinned
ARM64 donor instructions under Unicorn. CRE SHA-256 is checked before execution.
This is a binary oracle, not an Android implementation or a photographic test.
No instructions in the detector, tracker, estimator or matrix solver are
replaced. Imports are serviced by explicit bounded allocation/memory/libm and
single-thread C++ lifetime shims. Logging is silent; optional
`libvivo.mempool.so` loading fails to exercise CRE's built-in allocator fallback.
Unknown imports and exhausted instruction budgets fail the test.

Validated entry points:

- `0x28ef00`: original `vivoFindHomographyUp4`, with float2 source/target arrays,
  double[9] output, parameter pointer, point count, two integer extent arguments,
  integer mode, then stack output-flag pointer and log level. RANSAC parameters
  are at +0x3c/+0x40/+0x44 (100, .995f, 3).
- `0x29a548`: image descriptor A, descriptor B, input float2 points, output float2
  points, count, accepted-count pointer, parameter pointer, float[9] output H,
  then stack log level. Descriptor format 9 is the tested single-channel byte
  format; width/height are +4/+8, data pointer +0x10 and byte stride +0x30.
  LK parameters are .01f at +0x30, 20 at +0x34 and .7f at +0x38. The wrapper
  passes an 8x8 window, three levels and .0001f minimum eigenvalue internally.
- `0x2914d0`: original CPU `vivoRawGoodFeaturesToTrack`, using parameter pointer,
  image descriptor, zero mask, float2 output storage, count pointer, log level.
  Tested fields: null CPU context at +0, maxCorners=1000 at +8, minCorners=0 at
  +0xc, maxCandidates=32768 at +0x10, minDistance=16 at +0x14, useHarris=0 at
  +0x18, HarrisK=.04f at +0x1c, scale=4 at +0x20, quality=.01f at +0x24.

Direction is significant: LK tracks A points into B, but this wrapper's final
H maps B coordinates back into A. For B shifted (+2,+1), tracked points move
(+2,+1), while H is approximately translation (-2,-1). This must not be passed
blindly to a destination-to-donor sampler for an A destination. The caller's
matrix inversion, ROI and resolution conventions still require reconstruction.

Results: nine RANSAC cases (translation/rotation/perspective, each with 0/8/20
outliers among 64 points) have maximum inlier reprojection error below
0.000008 px. Four smoothed-texture LK+RANSAC translations retain 49/49 points,
with H error below .0012 px and tracking error below .0037 px. A separate
256x256 textured pair passes through the original detector and tracker without
hand-picked points: 105 detected and accepted points, H error .147697 px.
These are synthetic host results with imported host libm, not full device parity.

Capture still uses the prior local-shift estimator. Required work remains:
RAW-to-guide construction and feature-selection policy, matrix/ROI conversions,
failed-frame and motion-dependent exposure handling, dynamic ZSL/bracket
scheduling, and the complete color/segmentation/TCE tone chain. The new oracle
is not connected to Android capture, and no APK or artifact-removal claim is made.

### RAW-to-motion-guide CPU arithmetic port (2026-09-20)

`vivo-nice-guide.h` ports the fourfold guide reduction used by CRE's CPU
optical-flow path. The original `0x26fde4` wrapper invokes mean estimation at
`0x26fa70` and the fourfold reduction at `0x26d010`. This path normalizes each
frame by its exposure ratio and estimated intensity before a gamma LUT. It is
not equivalent to the existing capture guide's average of green samples.

`check_vivo_nice_guide.py <libvivo_nice_cre.so>` builds the C++ implementation
and compares its complete byte output against the unpatched original wrapper
in Unicorn. The wrapper receives two frame descriptors and calculates the
normal/donor exposure ratio itself. All 16,641 samples match bit-for-bit across
18 cases: two image dimensions, RAW10/12/14, and exposure gains .25/1/4. The
property-query import shim returns an absent property; string comparison is
emulated explicitly. Allocation remains the donor's built-in fallback path.
These imported environment shims are not a reproduction of the phone runtime.

Gamma .5 is an explicit synthetic test input, not a recovered capture default.
The XML parser reads `gammaLutCoef` into alignment parameters +0x54 (global
configuration +0x563c), and otherwise retains the initialized value. That default
and the upstream RAW black-level/bit-depth convention must be established before
selecting this guide in Camera2 capture. The C++ function accepts gamma and gain
from its caller and requires RAW with the donor's 64/1023 black convention; it
must not be fed the black-subtracted network input without a defined conversion.

This component is not enabled in capture. Original motion runtime linkage,
coordinate/ROI conversion, failed-frame handling, dynamic ZSL/bracket scheduling
and complete Tone/TCE integration remain unfinished. No new APK was built.

### Original motion linked into capture source; release still blocked (2026-09-20)

This supersedes the preceding motion integration/default-gamma status, not the
outstanding stock-parity limitations. Native worker v26 now calls the original
CPU corner detector and LK/RANSAC through `vivo-nice-stock-motion.h` during
`--nice-capture`. The Java root launcher hashes the installed CRE library before
execution; native linkage checks its version-export offset and entry-point
instructions. Different or unavailable binaries stop processing. NICE errors no
longer silently produce a photograph through the unrelated autonomous HDR path.

The constructor at 0x3ce2a0 supplies gamma=.6 at +0x563c, failed-frame fallback
method 0 at +0x56bc, and guide scale 4 at +0x5718. The supplied HDR XML omits gamma
and comments out fallback-method override. The ARM64 oracle now executes this
constructor and checks those values. Guide tests now use the recovered .6:
all 16,641 bytes still match the original wrapper across 18 synthetic cases.

The Camera2 adapter converts calibrated canonical RAW to RAW14 black=1024 before
guide generation. Original LK's donor-to-reference H is inverted and scaled
from guide to RAW coordinates, then used by both original-style Bayer and short
RGB samplers before network packing. Singular matrices, nonfinite/unwritten
outputs, and projective horizons crossing the frame are rejected. The current
50-point/.7 retention checks are adapter policy using XML values; the complete
original ROI, feature-block, random-disturbance and acceptance path is **not**
reproduced. Calibrated Camera2 RAW conversion is likewise an explicit adaptation.

CRE's failed-frame handler at 0x2e1d4c uses replacement method 0 by default and
resets failed-L refNEV when resetEvCoeff is enabled. Its replacement routine at
0x2a94bc copies the reference image descriptor and frame metadata. The adapter
replaces failed donors with Nref and recomputes exposure domains before VST.
NCH v5 adds Nref noise slope/offset at header words 28/29 so a failed L can select
Nref noise instead of retaining L calibration. Versions 1-4 remain readable;
their missing Nref calibration is a clear error if a failed L requires it.

Verification completed locally: native worker C++ syntax; ASan/UBSan capture
transport and projective integration tests (leak detection disabled because the
execution environment uses ptrace); the full original ARM64 motion oracle;
and the .6-gamma guide oracle. New integration checks cover nonaffine inversion,
horizon rejection, the actual callback-to-tensor path, and failed-L calibration
replacement. Mock-graph reconstruction error is 2.97129e-5. The Actions workflow
now includes the integration check. Android compilation, original-library loading
on the phone, and actual NPU/image-quality verification have **not** run here.

Dynamic capture remains blocked on recovering and verifying the live stock HAL
contract. Stock app code forwards `VivoAlgoAECFrameControl` and uses
`VivoAlgoCaptureFrameControl` counts/batch data, plus
`RequestLeftInThisSnapshot`; `VivoMotionAdaptiveAECInfo` supplies additional
motion/exposure state. Available uploaded logs contain no live values for these
tags. Some AEC offsets are visible in Java, but the complete gain/role/units ABI
is not established; inventing a float-array layout would create another hybrid.
No attached Android device or adb connection is available in this environment.

`tools/collect_vivo_stock_schedule.sh` collects these tags using Android's
built-in CameraService watch interface, scoped to `com.android.camera`. It needs
root and 90 seconds of stock-camera shooting: normal light, a bright window with
a dark interior, then motion in that scene. Save it in Download and run:

```sh
su -c 'sh /sdcard/Download/collect_vivo_stock_schedule.sh'
```

It writes a tar archive under Download/SCAMERA, without installing an APK or
collecting photographs. Availability of the watch interface and exposure of Vivo
private tags are checked/reported; this collector is not guaranteed to observe a
private VCF path that bypasses CameraService. Its shell syntax is checked locally;
the phone command itself remains untested. Full Tone/TCE color, masks, guided
gain-map reconstruction and routing are also unfinished. No new APK, full-port
completion, or artifact-removal claim accompanies this source change.

### Collector v2: Binder failure diagnosed (2026-09-20)

The uploaded `camera-help.txt` contains only `cmd: Failure calling service
media.camera: Failed transaction (2147483646)`. This establishes a failed Binder
command transaction, not absence of camera tag monitoring. Inspection of the
supplied `/system/lib64/libcameraservice.so` confirms `shellCommand` (0x1d46a8),
`handleWatchCommand` (0x20ccf4), and watch help strings. `Camera3Device::dump`
(0x30ff44) also parses `-m <tags>`; its literal `off` branch at 0x3102e8–0x310394
calls `TagMonitor::disableMonitoring`. The monitor option is `-m`, stored at
0x12b858. These are donor-binary observations, not assumptions about current AOSP.

Collector v1 redirected command FDs directly to shared-storage files and inherited
its terminal input. FD transfer rejection is a possible cause of this error, not
proven by this one-line report. V2 gives Binder pipe FDs for stdin/stdout/stderr,
retains actual command exit status, bounds command execution, and archives errors
instead of stopping with an isolated help file. If watch remains unavailable, it
tries the verified `dumpsys media.camera -m <tags>` path after allowing the user to
open the stock camera. Cleanup uses watch stop or `-m off`, respectively. The
fallback applies to currently connected camera clients and is not package-scoped;
users are instructed to close other camera applications. No SELinux policy,
persistent property, camera binary, or firmware changes are made. No general
logcat or photographs are collected. Phone execution and availability of private
Vivo tags remain unverified until the new archive is received.

### Collector v2 withdrawn after camera-freeze report (2026-09-20)

The user reports that collection now runs but the stock camera freezes during
it. No v2 archive has been received yet, so the active mode and precise cause
are unknown. V2's fallback performed a full camera dump every two seconds;
this is intrusive diagnostic work during capture and is a plausible contributor.
Collector v3 removes all dumpsys calls and that fallback. It uses six tags,
waits 30 seconds without service polling, then reads watch once and stops it.
Unavailable watch now fails with an archive instead of switching to full dumps.
Mock command checks verify the one-read path and error archives; they do not
prove camera responsiveness. V3 is not yet requested for a device rerun: first
stop v2 and inspect its existing archive. The user was told to use Ctrl+C and
not repeat capture while this fault is being diagnosed.

### Device archive corrects the freeze hypothesis (2026-09-20)

`vivo-stock-schedule-v2-cAKQlEPc.tar` confirms **watch mode**, not the dumpsys
fallback. `monitor-start.txt` says `Started watching 0 active clients`; all 45
watch samples say `No monitoring information to print`. `monitor-stop.txt`
confirms `Stopped watching all clients`. Therefore repeated full dumps from the
fallback do not explain this particular run. V2 did still take its full before
and after snapshots; their causal involvement is not established.

The after snapshot contains a fresh camera-service event history: all ten
camera devices are removed at 19:51:26 and re-added at 19:51:49 (device clock).
Earlier history from the before snapshot has disappeared. This is consistent
with camera service/provider restart, but is not a native crash backtrace or
proof of which process failed. Both snapshots have no active camera clients
and `Camera error traces (0)`. No live exposure/burst values were collected.
The snapshot only establishes these vendor-tag declarations:

| Tag | Numeric ID | Type |
| --- | --- | --- |
| vivo.control.RequestLeftInThisSnapshot | 0x81220067 | int32 |
| vivo.parameter.VivoMotionAdaptiveAECInfo | 0x81260014 | int32 |
| vivo.parameter.VivoAlgoAECFrameControl | 0x81260015 | float |
| vivo.parameter.VivoAlgoCaptureFrameControl | 0x8126001a | int32 |

The installed CRE/TCE/Tone hashes match the donor. Binder help now succeeds
under root `u:r:ksu:s0`, with SELinux Enforcing. No new collection/reproduction
should be requested on this evidence alone. Next diagnostic is a one-shot read
of the existing Android crash log, without camera commands, monitoring or new
photographs. Collector v3 remains unverified and must not be described as fixing
this device failure merely because its dumpsys fallback was removed.

### Crash log proves TagMonitor formatter abort; all collector versions withdrawn

Uploaded `vivo-camera-crash.txt` identifies the primary fault in **cameraserver**:
SIGABRT with `ubsan: mul-overflow`, inside `fmt::v11::detail::format_float<double>`
called by float `write_float`, then `TagMonitor::getEventDataString` at PC
0x3da93c. The cameraserver BuildId is d86792e405d8e06febf35e6a4d893e26.
At 19:51:25.985 the path is `printWatchedTags -> handleWatchCommand -> shellCommand`.
At 19:50:39.120 the same failure occurs while caching monitored events during
client disconnect (`cacheClientTagDumpIfNeeded -> removeByClient`). Thus even
monitoring without repeated polling can trigger the abort when a client closes.
The precise float value/tag responsible is not recorded in this log.

The provider subsequently aborts in `CameraProvider::binderDied` (19:51:26.719),
and the stock app throws a null List.size() exception in `SnapController.prepare`
(19:51:33.513). The observed failure begins in diagnostic metadata formatting,
not in a NICE reconstruction or Tone model stack. Our active diagnostic collector
exposed this firmware failure. The earlier polling-load explanation is superseded.

**All active collector versions v1-v3 are withdrawn.** The canonical script now
exits before any service call. The v3 single-dump/smaller-tag variant is not a fix:
it still monitored float values and used the same formatting path. We do not
patch cameraserver, suppress UBSan, disable SELinux, or request another live
reproduction. The preceding archive already confirms monitor stop succeeded.
No additional log is needed to establish this diagnostic crash mechanism.
Dynamic stock schedule recovery still lacks live values; any future approach
must avoid CameraService TagMonitor float formatting entirely and be validated
before asking the user to exercise it on the phone.

### Alternative metadata source and C++ Tone conversion (2026-09-20)

The decompiled stock app has a metadata path independent of CameraService
TagMonitor. `SuperNightCaptureCommand.executeRawVifVivoRawHdrCommand` logs
`aecFrameInfo`, forward/backward/total counts, opaque batch info, and
`motionMetering`. Its YUV path logs `aecFrameControl` and `captureFrameControl`.
The SDK `Logger.d` requires `persist.sys.log.ctrl=yes`. `VLogWrapper` can route
those logs to `/data/bbklog/camap_log/camapp_*.txt` when its file writer exists and
`persist.log.ratelimit=1`; otherwise it uses VLog. These settings are read, not
changed, by the new `tools/read_vivo_stock_logs.sh`.

This new tool is a passive snapshot of existing logcat camera SDK tags plus
bounded tails of at most ten stock-camera log files, retaining only the relevant
capture-command lines from files. It never calls cmd/dumpsys CameraService,
enables monitoring, starts capture, attaches to processes, changes properties,
or clears logs. It reports absent records honestly. It does not enable the
withdrawn collector. No device run has been observed yet.

`tools/parse_vivo_stock_schedule.py` parses observed stock Java records with
line/PID/TID provenance where present. It validates count sums, int32 arrays,
batch-length bounds and finite values. AEC decoding exposes only the proven
first-plane normal marker and indices 32..47 shutter-ms plane. Gain/other
fields stay opaque. Adjacent lines are not silently paired into a capture.
Synthetic checks cover count inconsistencies, truncation, nonfinite values,
null records, and unrelated command tags. This is an analysis tool; dynamic
Camera2 request scheduling remains blocked on actual values and full ABI mapping.

`vivo-nice-tone-conversion.h` now implements the recovered FastTM normalization
and Log-output conversion in C++: input /15615 without an input clamp, output
clamp to [0,1], truncate multiplication by 9937, lookup in the caller-supplied
9938-entry original exp table, and clamp to 16383. Nonfinite graph output throws
before indexing. The table must come from the pinned TCE; no guessed gamma is
substituted. The ARM oracle test now compiles and exercises this C++ code and
compares all 134,880 values bit-for-bit against original execution, including
row-padding checks on the original functions. Full color calibration, masks,
gain-map reconstruction and model routing are still not integrated. No new
APK or completed-stock-port claim is made.


### Actual Photo route: VCF2, not the assumed command (2026-09-20)

The full user log `vivo-camera-app-log.txt` (2,943,159 bytes, 12,984 lines,
SHA256 `8663cdf485b7f9d509732b1f7fbde38f49650b54c9fdf8ce9120497a8af3a952`) covers 20:11:10.016--20:14:05.633.
It contains three successful image callbacks for capture IDs 1789915272890,
1789915436661, 1789915440665. Each uses
`PhotoPipeline[CaptureNode,Vcf2ImageCallbackANode,]`; the capture command reports
camera ID 3, zoom 1.5, 4096x3072. `CamMngProxy` initializes the private
`android.hardware.vivocamera.VivoCameraManager`. No `SuperNightCaptureCommand`
or its numeric AEC/bracket arrays appear anywhere in the full file.
Thus this experiment confirms an incorrect diagnostic route assumption, not a
failure to enable logging or a failure by the user to take a photo.

`VivoCaptureResultKey` maps forward/backward timestamps to
`vcf.parameter.capture.past` / `vcf.parameter.capture.future`.
`Vcf2SnapNonCoreHandler.notifyPreviewReferenceCaptureComplete` passes element 1
as timestamp and element 0 as count to `ReferenceImageReader.capture`.
Its consumer logs explicitly capture-ID-associated preview-reference counts
4, 4, 6 in the past direction. These describe the preview-reference queue;
they do NOT establish L/N/S RAW exposure counts or an HDR bracket.
Motion array messages such as `[F@...` are Java object strings, not numeric
array contents. No EV, gain or shutter values can be reconstructed from them.

The passive reader now retains camera SDK lines across complete existing
camera log files (at most ten files), including VCF2, and records file read
status. It no longer silently discards everything outside the night command
or the last 1 MiB. No device property, camera call or monitoring was added.
The parser supports the file logger's year-prefixed timestamps, explicit VCF2
preview-reference events and image callbacks. It reports six real events from
this log without promoting preview counts to a RAW schedule. Shell syntax and
parser checks passed. No additional phone reproduction is requested.

Next reverse-engineering target is the actual VCF2 service/native scheduling
path used here. Repeating the old SuperNight-only collection will not recover
that path. Full dynamic exposure policy and Tone/TCE integration remain
incomplete; this diagnostic correction does not fix SCAMERA image artifacts.
