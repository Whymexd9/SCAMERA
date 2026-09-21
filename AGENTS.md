# ZSL/bracket port scope

User clarification (2026-09-21): do not switch sensor modes. This is the only
scope restriction on changes needed for the port. Camera session configuration,
streams, AE, VCF2 frame delivery and processing may be changed to follow the
stock implementation. The earlier requirement to retain the existing camera
session and RAW stream is superseded. Any session or stream changes must still
preserve the sensor mode; using a stock path does not waive that restriction.

Finish connecting AE and VCF2 frame delivery to CaptureController and complete
the port before building the test APK. Do not build intermediate APKs.

# APK delivery

The user requires the APK attached in chat to be byte-for-byte identical to the
final GitHub Actions APK. Download the `SCAMERA-Build-<version>` artifact and
verify its APK against `SHA256SUMS.txt` before delivering it. Do not append assets,
patch, rebuild or re-sign a downloaded APK locally for delivery.

The workflow packages and verifies the model/runtime assets before publishing.
An unsigned/incomplete template is not a finished APK. Report blocked packaging
honestly and keep device image-quality claims separate from host/CI test results.
