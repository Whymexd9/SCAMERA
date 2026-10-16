# Native FULLRES and automatic exposure: PD2454, 2026-10-16

Latest owner scope: finish ISZ/FULLRES research and pass full application integration
to the other AI; defer DCG. All changes stay local. No commit,
push, Actions or upload. Preserve the other AI's commits and concurrent changes.
This note supersedes historical statements that native FULLRES cannot deliver RAW.
Read `NATIVE-ISZ-READY-20261016.md` for the independently verified ISZ profiles.

## Working Camera2 contract

The real installed `android.camera.cts` applicationId (ordinary UID10469 on this
phone) reaches AppType21. Build with `-PscameraCtsNativeRaw=true`; the default
`org.codeaurora.snapcam` applicationId remains unchanged. The primary application
30820/UID10349 was not replaced. This is a separate actual package, not a false
AttributionSource under the primary UID.

For native FULLRES, retain the SDK40=1 lease THROUGH configuration and capture;
restore the prior SDK40/33 snapshot only when the CameraDevice closes. Earlier
probes restoring SDK40 before endConfigure did not test this working contract.
Use exactly one RAW_SENSOR ImageReader, explicit usage0, maxImages4, no PRIVATE,
YUV or JPEG target. The SDK lease is an ordinary app API call. No root, Frida,
provider-memory writes or observer participated in the successful captures below.

| Lens | Camera | forceSensorMode int[] | RAW dimensions | Pixel mosaic |
|---|---|---|---|---|
| Main | 3 | [4] | 8192x6144 | Quad/block2 |
| Ultrawide | 4 | [4] | 8192x6144 | Quad/block2 |
| Tele native50 | 5 | [1] | 8160x6144 | Quad/block2 |
| Tele native200 | 5 | [0] | 16320x12288 | Tetra/block4 |

Force mode is set in BOTH session parameters and every request; tele mode0 is an
explicit native mode, not the absence of a tag. No ProUI16 or SDK streamsUsage
array. Camera2 AE_MODE_ON, AE_LOCK=false, CONTROL_MODE_AUTO. Copy actual session
keys, EV and AE regions from preview into STILL. Do not replay manual template
ISO/exposure or call ISO vendor gain. The app develops the RAW viewfinder and
performs its own SHDR/remosaic. Only the final RGB is cropped to exactly4:3.

Standalone delivered RAW on all four rows: multiple unique, nonzero payloads,
matched CaptureResult timestamps, exact dimensions/stride/extent, phone/local
SHA256. Independent pixel analysis classified25/25 tiles Quad or Tetra, rather
than relying on a requested mode echo. Evidence:

- `reports/pd2454-cts-native-full50-sdk-retained-pixels-20261016/`
- `reports/pd2454-cts-uw-native-full50-sdk-retained-pixels-20261016/`
- `reports/pd2454-cts-tele-native-full50-sdk-retained-pixels-20261016/`
- `reports/pd2454-cts-tele-native-full200-sdk-retained-pixels-20261016/`

## Actual SCAMERA UI and image evidence

- Main30830:20 matched RAW8192x6144, native block2 SHDR, JPEG8192x6144.
  `reports/pd2454-30830-main-native-fullres-ui-20261016/`.
  JPEG SHA256 `2f0d84392de357abf93e35bfc3aec4f5bb20907a787093b7ca2bb174ffa88d45`.
- Ultrawide30832:20 matched RAW8192x6144, native block2 SHDR, JPEG8192x6144.
  `reports/pd2454-30832-uw-fullres-ae-shot-20261016/`.
  JPEG SHA256 `d1421e47a624c10bd46436574fd3a0ed90cfd9c2fb30d9b21a8db3a2fa19bee2`.
- Tele30832:4 matched native200 RAW (requested20, reduced by measured memory),
  but worker rejected its outdated fixed12-bytes-per-pixel FP32 transport guard.
  No JPEG from this attempt. Source30833 makes that guard use the actual FP16
  job marker and output grid.
- Tele30833 then delivered4 matched native200 RAW, selected2 normals, independently
  measured Tetra/block4 and completed GPU merge16320x12288. During the following
  full-plane chroma/moire stage Android killed the app: ApplicationExitInfo reports
  reason3 LOW_MEMORY at18:02:28, not a camera admission failure. No200MP JPEG.
  Evidence: `reports/pd2454-30833-tele-native200-shot-20261016/` and
  `reports/pd2454-30833-tele-native200-exit-info-20261016.txt`.

The current SOURCE (after30833; not yet in an APK) bounds mosaicChromaMedian's
temporary planes to256-row cores with8*block overlap and preserved original RGB
halo. It retains the existing median/false-colour calculation rather than turning
it off. Android native reference tests cover Quad/Tetra/doubled blocks, strip
seams, borders and oscillation maps; maximum absolute difference7.15255737e-7,
green unchanged. The tiny difference comes from restarting floating box sums at
strip boundaries; byte-identical output is NOT claimed. Reports:
`pd2454-mosaic-chroma-bands-{compile,result}-20261016.txt`.
This is an independently checked MEMORY-FIX CANDIDATE, not proof that the complete
200MP app/PostPipeline/JPEG now fits. The owner delegates that integration to the
other AI. Keep the failure boundary explicit; do not label RAW delivery as a saved
photo, or native50 as native200. Tele native50/mode1 already has standalone RAW
proof and is an explicit alternative to evaluate, not a silent200MP downgrade.

There is no arbitrary three-frame capture cap. Existing real memory/GPU/buffer
bounds remain. SHDR can select fewer donors than were captured; record both
counts. The worker's sensor-grid mosaic branch reports a binned proxy's "Sabre
2x" grid; this does NOT prove explicit FULLRES2x. Only a requested16384x12288
output and saved JPEG of that size establish main/UW FULLRES2x. Tele200 doubled
would exceed this phone's actual16384-side GPU limit; do not hide a downgrade.

## Controlled FULLRES AE verification on the phone

Actual SCAMERA EV ruler, zero -> +0.8 -> zero; measured Camera2 result values.
No manual SENSOR request and no stock app involved. All stages converged
(AE_STATE2), AE_MODE1, AE_LOCKfalse. EV request indices0 ->8 ->0.

| Lens/build | Zero before | +EV | Zero return |
|---|---|---|---|
| Main30831 | 6.903ms / ISO72 | 10ms / ISO106 | 6.903ms / ISO72 |
| UW30832 | 10ms / ISO111 | 10ms / ISO271 | 10ms / ISO118 |
| Tele200/30832 | 10ms / ISO134 | 10ms / ISO273 | 10ms / ISO153 |

Reports: `pd2454-30831-main-fullres-ae-20261016`,
`pd2454-30832-uw-fullres-ae-shot-20261016`,
`pd2454-30832-tele-fullres-ae-shot-repeat-20261016` under `SCAMERA-PC/reports/`.
Each contains own-PID logs, screenshots and manifest. The first tele attempt
sampled converged OLD requests before EV reached the HAL; retained as failed
test evidence. The reproducer now requires the expected request EV index before
accepting convergence. Do not label stale queued results a successful EV stage.
AE reacts and returns to baseline within scene variability; this is not an EV
calibration or stock image-quality equivalence test.

## Source and reproduction

Changes: `VivoSdkFullRes` native profiles; `VivoPd2454NativeRaw`; RAW-only graph
and preview/capture routing in CaptureController; optional-JPEG SerialRawCapture;
strict matched RAW+metadata/native-mosaic checks in VivoSdkFullResCapture;
same-colour2x2/4x4 LiveRawFrame preview binning; first-frame known native block;
FP16-aware RawBin/SHDR and worker RGB transport limit. Existing hardware-remosaic
profiles for other applicationIds are retained. `kHybMergeMain1` was not changed.

`evidence/vivo_hal_2026-10-13/tools/validate_cts_native_fullres_ui.py` performs
real UI lens/FULLRES selection, measured EV response and optional SHDR capture.
Use bundled runtime Python (Pillow), target serial10AFAQ1QE60071E only. It stops
if CTS is not focused. It restores EV0 after the controlled EV test.

Host tests:121 tests/19 suites, zero failures/errors/skips on isolated30832
production source plus hashed latest test-source overlay:
`reports/pd2454-30832-native-fullres-host-tests-revision3-20261016/`.
The original legacy-package tests explicitly wrap org.codeaurora.snapcam, so the
CTS build does not accidentally test the other client's module policy. The
hard-limit fixture now exceeds even FP16, rather than demanding rejection of
native200 that actually fits FP16. No assertions were removed or disabled.
Bootstrap38 +endConfigure18 +lifecycle123 host assertions also pass; these are
host fakes, separate from phone evidence. Native sparse-transport test on Android
accepts native200 FP16 and50MP->201MP FP16, rejects those with FP32, rejects432MP
even with FP16, and preserves strict-grid/no-resize guards. Reports:
`pd2454-native-fullres-fp16-guard-{compile,result}-20261016.txt`.

Gracefully leave CTS via HOME and verify no active camera before updating the
APK; force-stop/LMK skips lease cleanup. The30833 LOW_MEMORY kill left SDK40=1:
this was measured passively, and a one-off ordinary-UID10471 cleanup application
called the SAME VivoCameraManager API to return40 to0 (33 remained2), then was
uninstalled. No root execution, provider memory/property patch or camera open.
Report `pd2454-owned-lmk-sdk-restore-20261016/restore.txt`; reproducer
`evidence/vivo_hal_2026-10-13/tools/restore_sdk_after_owned_lmk.py` is scoped to this
known owned failure, not a generic reset to run on another client's lease. The
earlier app_process/run-as attempt did NOT work; do not repeat it. Add robust
ownership-aware crash/lease recovery when integrating production; in-memory
try/finally alone cannot recover after LOW_MEMORY death.

Latest installed diagnostic APK30833 (not release/Actions):
`deliverables/local-shdr-20261015/SCAMERA-0.98-30833-CTS-NATIVE-ISZ-LOCAL-DIAGNOSTIC.apk`
SHA256 `c30984db256247759928100220e7c4149d9ff948751ced5996e4bb38afcf5a59`;
installed bytes independently matched. Its isolated production source snapshot is
`source-snapshot-20261016-174918/project`. It DOES NOT contain the later bounded
chroma candidate. Rebuild current source before evaluating that fix. No new full
integration APK was built after the owner's handoff instruction.

Retain settings/data. No release/Actions
APK is requested. P146 separate FULLRES settings group remains another AI's
open task; this work does not mark it implemented.

## Porting to X100 Ultra / X300 Ultra

The CTS/SDK/RAW-only mechanism is a candidate, not a verified device port. Never
remove the PD2454 guards or copy its sensor-mode indices blindly. Collect each
phone's firmware/model, full Camera2/vendor/session-key report, readable selector
and sensor descriptors, then compare actual matched RAW extent/packing/CFA and
auto exposure on every lens. No such successful capture is established here.
