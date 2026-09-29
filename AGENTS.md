# ZSL/bracket port scope

The target Vivo X200 Ultra has root, confirmed by the user. Root-backed workers
are allowed and already used by VivoNeuralClient/VivoNeuralWorker. Do not treat
ordinary app-UID limitations as the only available implementation path. Root on
the phone does not imply that this workspace has a live connection to it.

User clarification (2026-09-21): do not switch sensor modes. This is the only
scope restriction on changes needed for the port. Camera session configuration,
streams, AE, VCF2 frame delivery and processing may be changed to follow the
stock implementation. The earlier requirement to retain the existing camera
session and RAW stream is superseded. Any session or stream changes must still
preserve the sensor mode; using a stock path does not waive that restriction.

Current user direction (2026-09-21): proceed without VCF2. Use Camera2 RAW
delivery and connect the recovered AE planning to CaptureController. The VCF2
JPEG route is retired, including old saved route selections. This supersedes
the earlier requirement to use VCF2 for delivery; it does not establish that
Camera2 can reproduce the stock internal dual-stream or ZSL scheduling.
Complete the port before building the test APK. Do not build intermediate APKs.

# APK delivery

The user requires the APK attached in chat to be byte-for-byte identical to the
final GitHub Actions APK. Download the `SCAMERA-Build-<version>` artifact and
verify its APK against `SHA256SUMS.txt` before delivering it. Do not append assets,
patch, rebuild or re-sign a downloaded APK locally for delivery.

The workflow packages and verifies the model/runtime assets before publishing.
An unsigned/incomplete template is not a finished APK. Report blocked packaging
honestly and keep device image-quality claims separate from host/CI test results.

# Confirmed bracket scope (2026-09-21)

The user excluded 4+1 from the port. Target only four separate normal RAWs,
one short, one extra-short and one long. Do not repeat normal frames or reuse
S for ES to fill the graph. A 4+1 runtime trace is no longer a completion gate.
This scope change does not waive stock AE/RAW delivery, unchanged sensor mode,
RawTherapee sharpening preservation or the final GitHub Actions artifact gate.

# Local migration entry point
Read ../HANDOFF.md and ../START-HERE.md first. The original worktree changes
are already present. ../evidence contains preserved research and older sources.
