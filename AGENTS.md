# APK delivery

The user requires the APK attached in chat to be byte-for-byte identical to the
final GitHub Actions APK. Download the `SCAMERA-Build-<version>` artifact and
verify its APK against `SHA256SUMS.txt` before delivering it. Do not append assets,
patch, rebuild or re-sign a downloaded APK locally for delivery.

The workflow packages and verifies the model/runtime assets before publishing.
An unsigned/incomplete template is not a finished APK. Report blocked packaging
honestly and keep device image-quality claims separate from host/CI test results.
