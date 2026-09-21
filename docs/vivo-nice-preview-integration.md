# Stock Photo detector controls connected to CaptureController

Source: supplied `vivo-camera-app-log.txt`, SHA-256
`8663cdf485b7f9d509732b1f7fbde38f49650b54c9fdf8ce9120497a8af3a952`.
At 20:11:10.495 the stock preview has `vivo.capability.capture.nice=1`
and `vivo.control.NiceMagicEnable=1`. Its global session parameters include
NiceMagicEnable (20:11:10.708). Both are Integer request keys in the supplied
stock APK's VivoCaptureRequestKey initializer, offsets 0x1c18 and 0x2590.

CaptureController now writes NiceMagicEnable into the Photo session parameters
and both controls into repeating preview when NICE is selected. Other capture
modes retain their existing requests. Unsupported keys are reported. The writer
does not change exposure, AE mode, sensor mode, pixel mode or stream topology.
It restores prior values if writing the preview controls fails.

This connects detector configuration to the existing shutter AE snapshot reader;
it does not establish that the device exposes all six AE fields to this app.
Host tests exercise exact types, unchanged unrelated request fields, unavailable
keys and rollback, not camera behavior. No APK was built.

The same stock log establishes a separate delivery boundary: at 20:11:16.190
the VCF2 callback reports format 33; the image node reports 2,620,471 bytes.
The requested VIF image format was 256 (JPEG). The stock Photo graph is
CaptureNode followed by Vcf2ImageCallbackANode. This is a completed compressed
image path, not evidence that its callback provides the internal RAW bracket.
The stock session also has SAT stream usages and SnapshotJpegStreamMap values
specific to its surfaces. They must not be copied into the current RAW session.

Remaining: establish a sensor-mode-preserving VCF2 session/template, connect
submission and capture-ID-scoped result delivery to CaptureController, and use
the correct output processing/save path. The manual RAW graph is still active;
these detector controls do not make the full ZSL/bracket port complete.

The VCF2 Buffer adapter now exposes `copyJpegBytes()` for HAL BLOB format 33.
It duplicates the descriptor and maps the callback's exact byte count read-only,
as the stock `Vcf2ImageCallbackProxy.getBufferFromFd` does. It preserves every
byte, including payloads following JPEG end markers; no Bitmap decode, EXIF
rewrite or JPEG encode is performed. Other formats, invalid lengths, mappings
and non-JPEG prefixes fail explicitly. The callback still owns its original FD.
This reader is not yet invoked by CaptureController; the submission/result/save
connection remains unfinished.

`check_vivo_vcf2_buffer.py` runs 11 checks through the production JNI function
and actual host mmap. The 28 framework callback/lifetime checks also pass.
Neither suite verifies Android DMA-buffer behavior or reference image quality.
The host runtime lacks JNI development headers; the buffer check accepts
`--jni-include` to use OpenJDK's real headers, without altering the Android build.
