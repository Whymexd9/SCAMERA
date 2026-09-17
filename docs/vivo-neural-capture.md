# Experimental Vivo neural capture

This adds a third remosaic choice, `vivo_neural`, for HP9 telephoto 4× ISZ
RAW with same-colour 4×4 blocks (CFA period 8×8). The previous two choices
are unchanged. This is an experimental adaptation of an embedded TELE
network, **not a verified copy of Vivo's active 4× pipeline**.

## What runs

1. Existing GPU sensor-response correction and black/white normalization.
   No white balance is applied before inference; normal downstream WB remains.
2. The installed APK starts its own Java/JNI helper with `su` and
   `app_process64`. No Termux installation is needed. Firmware SHA-256 checks
   pin the exact supplied PD2454 libraries before loading native code.
3. QnnSystem reads the embedded TELE576 context metadata. The helper requires
   graph `T2Q_TELE_3x_v1p9_frozen`, FLOAT32 input `[1,144,144,16]` and output
   `[1,144,144,64]`. Qualcomm HTP executes the graph through QNN Core 2.18.0.
4. Eight real executions of flat colour charts test two input CFA hypotheses
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

Root is required in this version. Models are read from the user's installed
firmware; no vendor binary or weight is included in the repository or APK.
Only ARM64, the pinned firmware, zero Tetra phase, dimensions divisible by 8,
and frames up to 16 MP are accepted. Four CFA orientations are handled by
phase-preserving flips. Other phones/firmwares fail explicitly.

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

Minimal declarations are restricted to hash-checked firmware, not advertised
as a general QNN SDK replacement. Public layouts and function order:

- [Qualcomm QnnInterface.h](https://docs.qualcomm.com/doc/80-63442-10/topic/api-rst_program_listing_file_include_QNN_QnnInterface_h.html)
- [Qualcomm QnnSystemContext.h](https://docs.qualcomm.com/doc/80-63442-10/topic/api-rst_program_listing_file_include_QNN_System_QnnSystemContext_h.html)

The stock library SHA-256 is
`7495113303fb01cff07434eb9896253774846a7b2c5ff1e13017654c884c3aa5`.
The selected embedded symbol is
`T2Q_TELE_3x_v1p9_240628_576_576_v79_O3_2251_bin` (5,720,680 bytes), SHA-256
`32983328ace406ffbfb165a09b1bfd69015e5da224a6d4b3e1f7f0cac74d1c9b`.
