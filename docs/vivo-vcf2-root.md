# VCF2 receiver in a root process

The uploaded SCAMERA-VCF2-connection-20260921-182318-25253 archive passes all
seven SHA256SUMS entries. The framework matches the earlier donor. Logcat is
zero bytes (timeout status 124), and the app debug file is listed as missing;
there is no new runtime exception trace in this archive.

## Service evidence

libvivocameraservice.so SHA256:
`5fbb51c02ca0c8f968c5367ceffd28782cc25a2d0e4b6d30e08e10be4dbfe438`.

| AIDL service function | Address | Observed behavior |
| --- | --- | --- |
| connectDevice | 0x7aa4c | Creates client with caller PID, requested UID and supplied package; appends it through finishConnectLocked |
| finishConnectLocked | 0x7aecc | Appends client to vector at service +0x38/+0x40 |
| CameraVIFCallback.bufferCallback | 0x7e54c | Iterates client vector; 0x7e77c reads callback at client +0x38, 0x7e7f0 invokes slot +0x40 with unchanged capture ID/buffer layout |
| CameraVIFCallback.metadataCallback | 0x7eb70 | Builds VIF metadata then broadcasts; 0x7ecac..0x7ed58 iterates the same vector, invoking callback slot +0x48 |

The inspected delivery loops do not filter by caller UID, package or request
ownership. Therefore receiver-side capture-ID filtering is necessary. This
resolves the earlier concern that moving the callback alone to root would
necessarily disconnect it from an app-owned Camera2 session. It does not prove
that every surrounding framework/service permission check permits the worker.

## Connected change

VivoVcf2Capture first attempts the existing framework connection. A missing
reflected method starts VivoVcf2Root via su/app_process64 using the installed
SCAMERA APK. The worker uses SCAMERA's real package name; it does not change
UID to the stock app, alter ART policy, patch framework files, or change SELinux.
Its minimal Context supplies the operation package read by the inspected open
method. The worker verifies both donor hashes before opening VCF2. Firmware
mismatch or root/process failure aborts this route.

The parent waits up to 30 seconds for initialization. READY means the wrapper
returned from initialize, not that HAL capture or sensor-mode preservation was
demonstrated. The parent arms one positive capture ID; the worker acknowledges
it before CaptureController's request is submitted. Session cancellation and
the 180-second capture timeout also cover the pre-submission phase. Retiring a
capture discards late acknowledgements/results. Parent stdin closure triggers
worker teardown, with a three-second deadline if framework close/init blocks.

Only callbacks matching the armed ID are read and forwarded. JPEG bytes are
copied by the existing bounded native BLOB reader, preserving all supplied
bytes and trailing gain-map/EXIF data. Final timestamp travels as an explicit
64-bit scalar; VIFResult.writeToParcel is never used. This protocol deliberately
does not claim to transmit complete Camera2/vendor metadata. CaptureController
logs the final timestamp without synthesizing a CaptureResult. Saving remains
an atomic write of the supplied JPEG.

The existing Camera2 session/profile/sensor choices remain unchanged by this
receiver change. Actual selected sensor mode must still be checked on device.
No internal RAW bracket is delivered by this JPEG transport; NICE RAW remains
the separate manual route. The original AE helpers, variable-series model
dispatch and TCE work are not completed by opening the VCF2 connection.

GCam Scene AE feedback is now inactive while NICE or VCF2 is selected, so its
saved histogram-meter setting cannot change compensation under these paths.
The existing restoration to user EV applies if that feedback previously owned
the current builder. RawTherapee sharpening on the NICE RAW path is preserved.

## Verification and remaining work

Host checks pass: 33 coordinator ordering/cancellation cases (including root
arming failures), 20 root-pipe/protocol/worker timestamp cases, 18 profile cases,
31 request-ID/single-submission cases, and the legacy-isolation regression.
The root check compiles the production worker/receiver/device/wire Java against
Android stubs; it uses a fake su process to test pipes, failures and quoting.
It does not execute Android root, the vendor service or the native BLOB reader.
The existing native reader check was attempted but cannot compile in this host
without JNI development headers. No buffer implementation was changed.

No APK was built. Device root access/delivery, the full RAW-series path, stock
AE/graph integration, TCE, and stock image-quality comparison remain unresolved.
