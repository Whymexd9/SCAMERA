## RAW tuning and progress

The independent photon/readout controls multiply the measured/calibrated
slope and offset before Java writes the existing v8 transport. The global
noise multiplier remains applied by the worker. Both new defaults are 1;
VST and inverse VST use the same resulting profile. These are experimental
input-noise controls, not independent learned luma/chroma strengths.

NICE RGB skips VivoHdrDenoise entirely. Its saved luma/chroma values remain
available for autonomous HDR without NICE. Tone remains VivoHdrTone_SCAMERA;
this change does not claim a port of original TCE. Existing contrast, gamma,
shadow, shoulder and saturation controls remain available in RAW mode.

VCF2 shows indeterminate processing while awaiting the paired result/JPEG;
it does not show manual-bracket progress. RAW retains real burst progress.
Host checks exercise independent slope/offset effects on input tensors with
matched inverse VST using a mock model, settings availability, and VCF
ordering/cancellation. Quality of changed tuning still requires phone tests.

## Explicit RAW / VCF2 selection

`pref_vivo_nice_route` defaults to `raw`. Existing installs retain the RAW
NICE capture verified in the user log dated 2026-09-21 12:49. That log does
not validate VCF2: it shows MOTION, 4 ZSL normals plus LONG/SHORT, original
NICE inference and SCAMERA RGB postprocessing.

Selecting `vcf2` enables the VCF route for visible Photo (MOTION ordinal 2)
and legacy PHOTO (3). Night retains RAW, as stated beside the selector.
The camera closes when settings open and recreates its session on return.
The selector remains editable while VCF-only availability rules disable
bypassed processing; no stored processing values are erased. RAW availability
also disables the skipped RT/ESD3D/Bayer denoise, ABLC and alternate tone stages,
while retaining actual NICE tuning, VivoHdrTone and downstream sharpening.
Existing VCF errors terminate the shot without retrying through RAW.
Device validation of the newly reachable VCF route remains outstanding.

# Stock VCF2 Photo route connected to CaptureController

Source: supplied `vivo-camera-app-log.txt`, SHA-256
`8663cdf485b7f9d509732b1f7fbde38f49650b54c9fdf8ce9120497a8af3a952`.
At 20:11:10.495 the stock preview has `vivo.capability.capture.nice=1`
and `vivo.control.NiceMagicEnable=1`. Its global session parameters include
NiceMagicEnable (20:11:10.708). Both are Integer request keys in the supplied
stock APK's VivoCaptureRequestKey initializer, offsets 0x1c18 and 0x2590.

CaptureController now writes NiceMagicEnable into the Photo session parameters
and both controls into repeating preview when NICE is selected in Photo.
The connected VCF2 route uses preview plus a JPEG stream, at the existing RAW
output dimensions; unsupported JPEG dimensions fail explicitly. Sensor/pixel
mode and exposure keys are retained from the current preview. Calibration and
other modes retain their existing capture paths.

This connects detector configuration to the existing shutter AE snapshot reader;
it does not establish that the device exposes all six AE fields to this app.
Host tests exercise exact types, unchanged unrelated request fields, unavailable
keys and rollback, not camera behavior. Android compilation passed in Actions
run 35565489928 before enabling the test APK build.

The same stock log establishes a separate delivery boundary: at 20:11:16.190
the VCF2 callback reports format 33; the image node reports 2,620,471 bytes.
The requested VIF image format was 256 (JPEG). The stock Photo graph is
CaptureNode followed by Vcf2ImageCallbackANode. This is a completed compressed
image path, not evidence that its callback provides the internal RAW bracket.
The stock session also has SAT stream usages and SnapshotJpegStreamMap values
specific to its surfaces. They must not be copied into the current RAW session.

CaptureController now creates the VCF2 connection and JPEG session, submits one
request carrying both capture IDs, waits for the matching JPEG and final VIF
metadata, and saves the JPEG atomically without further SCAMERA processing.
The native HAL owns AE, its internal ZSL/bracket schedule, reconstruction and
Tone processing. This alternative reuses the installed stock pipeline; it is
not an independent reimplementation of those algorithms. Manual frame-count
settings do not drive its internal series. Unsupported service/keys, failed
captures and a missing final callback report an error rather than substituting
the fixed manual RAW graph.

BaseCameraMode.configSessionType returns 0 for ordinary Photo (no QCom EIS).
Its setSessionStreamUsage algorithm produces {2, 1, 0} for our preview/capture
streams. The stock VcfHdrCapabilityCommand, VcfRawHdrCapabilityCommand and
VcfHDRHighLightDetectCommand pass the logged Integer AUTO values directly to
the Camera2 keys used by VivoVcf2PhotoProfile. SnapshotJpegStreamMap is built
for our selected physical stream instead of copying the stock SAT array.
The specialized VcfBackNormalMainCaptureParameter adds only an MTK long-shot
preview target; its base updateCaptureMetaFromPreview changes Samsung crops
only. Neither supplies an additional Qualcomm AE-array request writer.

Device validation remains: vendor service access from this app, accepted stream
configuration, actual unchanged sensor-mode selection, callback delivery and
image comparison. Host/CI success does not establish these hardware results.

The VCF2 Buffer adapter now exposes `copyJpegBytes()` for HAL BLOB format 33.
It duplicates the descriptor and maps the callback's exact byte count read-only,
as the stock `Vcf2ImageCallbackProxy.getBufferFromFd` does. It preserves every
byte, including payloads following JPEG end markers; no Bitmap decode, EXIF
rewrite or JPEG encode is performed. Other formats, invalid lengths, mappings
and non-JPEG prefixes fail explicitly. The callback still owns its original FD.
VivoVcf2Capture invokes this reader and pairs its output with final metadata
by capture ID. Closing the session retires pending IDs and descriptors; timeout,
Camera2 failure and vendor errors end the transaction without a retry.

`check_vivo_vcf2_buffer.py` runs 11 checks through the production JNI function
and actual host mmap. The 28 framework callback/lifetime checks also pass.
Neither suite verifies Android DMA-buffer behavior or reference image quality.
The host runtime lacks JNI development headers; the buffer check accepts
`--jni-include` to use OpenJDK's real headers, without altering the Android build.

The additional coordinator checks cover 24 ordering/cancellation cases; the
profile checks cover 18 stream-map and request-field preservation cases.
