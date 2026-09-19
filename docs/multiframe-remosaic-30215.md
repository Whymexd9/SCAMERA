# 30215 — original CAL bank and ZSL MFSR

Source of truth: supplied `Multi-frame Remosaic.apk`, SHA256
`fa87bb606ed7cd998c7d509f4e9fdb1aa1c6d4bb48df316047ef30301f0f3af3`.
Verified classes19 CaptureController and classes20 RemosaicCaptureConfig /
RemosaicCalibrationStore instructions, not just the lossy Java decompilation.

## Original behavior restored

CAL generates 5 logarithmically spaced ISO values over SENSOR_INFO_SENSITIVITY_RANGE.
Each ISO is captured at 1 ms and 66.666667 ms, clamped to the sensor exposure range.
Four distinct dark RAWs per pair: 40 frames, 10 profiles. The original native
median residual estimator is unchanged. The prior single-profile 4-frame shortcut
was incomplete and is superseded.

SCAMERA streams ten groups of four instead of retaining 40 full-resolution RAWs.
It keeps the same original capture schedule and estimator. A completed group advances
only after its native map is saved. Failed, closed or aborted capture cancels the
unfinished bank. An atomic manifest exposes the bank only after all 10 profiles.
Bank identity includes module, mosaic, CFA and RAW dimensions. Legacy incomplete
v1 banks are not silently reused; run CAL again after updating.

FPN matching already followed the original weighted squared log-distance in ISO
and exposure (exposure weight 0.35). Scaling remains ISO / profileISO clamped
0.7–1.4. DEX confirms floating-point division despite integer-looking decompiled
Java. New tests pin these details.

## ZSL

All still modes (Photo, Motion, Night) use a rolling RAW stream. Native MFSR normal
frames are selected before the shutter from distinct timestamp/result pairs with
identical measured ISO and exposure. The middle normal frame supplies processing
metadata. Insufficient stable RAWs produce an explicit retry message, not a PSL
replacement. For bracketing, only the long/short donors are captured after the
shutter; their requested exposure is derived from the measured ZSL base. Native
reconstruction remains separated per exposure before HDR fusion. CAL necessarily
uses new dark frames at scheduled exposures, not the scene already in ZSL.

At the user's request, the global ZSL buffer is initialized to 50 on upgrade and
on fresh install. It remains adjustable from 1 to 100 in the existing menu and
is no longer overridden by MFSR burst length or module profiles. The 768 MiB
processing limit still controls how many frames are fused, not ring capacity.
RAW reader capacity reserves three additional in-flight frames. Video and unlimited
streaming are not still-photo ZSL modes. Device validation is required for the memory
cost of 50 full-resolution RAWs and continuous sensor throughput.

## Verification

Local targeted JVM tests cover original CAL schedule, sensor limits, matching,
ZSL mode/capacity independence, settings migration and camera resume. ARM64
emulation executes the original library for all CFA/mosaic modes, single-exposure
donors and signed FPN calibration. No physical Vivo is connected: successful CAL
capture, HAL scheduling, real memory use and HDR image quality require phone testing.
