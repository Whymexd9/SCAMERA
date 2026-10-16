# Local SCAMERA transfer: native ISZ, 2026-10-16

UPDATE: read `FULLRES-NATIVE-READY-20261016.md` FIRST for the later successful
native FULLRES RAW/AE contract and remaining tele200 memory boundary. Earlier
FULLRES-negative sections below are historical. Owner delegates full application
integration to the other AI; DCG is deferred, and no commit/push/upload is allowed.

Owner priority: finish ISZ and native FULLRES before new DCG work. No commit,
push, Actions, upload or publication. Preserve the other AI's source and commits.
Read this file before the older frontier notes; their historical negative results
do not supersede the successful application captures below.

## Confirmed on PD2454, ordinary application UID

The real installed applicationId `android.camera.cts` reaches the decoded CTS
client category AppType21. This is an actual separately installed SCAMERA build,
not an AttributionSource naming another package under the primary application's
UID. Default builds remain `org.codeaurora.snapcam`.

Native ISZ requires exactly one RAW_SENSOR output, the four-argument ImageReader
constructor (default CPU-read usage3), no PRIVATE/YUV/JPEG surface, and the actual
`vivo.control.forceSensorMode` int[] in BOTH session parameters and every request.
The app develops its own RAW viewfinder, detects Quad/Tetra from pixels, merges
through SHDR and performs remosaic itself. No root, Frida, VIF, SDK override or
provider-state race was used for these successful captures.

| Module | Camera | Force mode | Input | Pixel mosaic | Saved JPEG |
|---|---|---|---|---|---|
| Main 2x | 3 | 3 | 4096x3072 | Quad/block2 | 4096x3072 |
| Ultrawide 2x | 4 | 3 | 4096x3072 | Quad/block2 | 4096x3072 |
| Tele 2x | 5 | 5 | 4080x3072 | Quad/block2 | 4080x3060 |
| Tele 4x | 5 | 7 | 4080x3072 | Tetra/block4 | 4080x3060 |

The final RGB crop is exactly4:3; preserve the native RAW extent/CFA phase.
Main/UW descriptors have no decoded native4x row. No software4x fallback was
implemented or presented as native4x. Earlier mode6/17 hardware-remosaic captures
are historical fallback evidence, not the feature verified in this table.

Detailed-scene RAW comparisons corroborate main/UW approximately2x and tele2x/4x.
The exposure-robust main/UW log/rank fits and failed linear fits are all retained
in `reports/pd2454-cts-detail2-fov-reproduced-20261016.json`. Do not report a grid
search maximum as a precision sensor ratio. Each standalone profile delivered32
distinct nonzero RAWs with matching CaptureResult timestamps and verified SHA256;
mosaic analysis independently classified25/25 spatial tiles.

## Application evidence

The primary application `org.codeaurora.snapcam` remains30820/UID10349.
The diagnostic `android.camera.cts` is an ordinary UID10469, independently installed.

LATEST30827: all FOUR profiles in the table were selected through the real UI,
rendered developed RAW and saved SHDR JPEGs, own PID14268/UID10469. Their current
logs, screenshots, JPEGs, decoded dimensions and phone/local SHA256 are in
`reports/pd2454-30827-native-isz-ui-20261016/`. Contact sheet visually inspected.
The reproducer now reads the installed package's actual UID rather than assuming
its numeric value, and supports `--profiles main2x uw2x tele2x tele4x`.

- Main2x `d3abae078ff1260874e7bdc55fca67bd1b522e2de1eb2ae82e5bead201a9de1e`
- UW2x `19f53b81032cc9d750b906974f0426ef3468ef8a1597148d3adc89ff15576635`
- Tele2x `693f5dd3c108ae1edf2e9daa2dbdd7d8ffa8140b68e36a26ecbc1f44f1a7478a`
- Tele4x `d899d575eb209878187fbc3e494a314c350e6289c1b7133ba4e73bf82eeb0917`

The following30824/30826 records are preserved earlier independent proofs.

Main2x in30824: PID31496/UID10469, one RAW surface, mode1->3, SHDR22 selected RAWs,
pixel block2 and own GPU remosaic; saved `SCA_20261016_130401.jpg`,4096x3072,
SHA256 `17fbbe78967e9889a3f7c6d59c93f30e54d872f76e74cb4e962a47d24d6ce548`.
Evidence: `reports/pd2454-30824-main-native2x-first-shot-all-20261016.txt` and
`reports/pd2454-30824-main-native2x-SCA_20261016_130401.jpg`.

Final30826 additionally fixes RAW viewfinder routing: `onMatchedLiveRaw` observes
the mosaic and requests a render; MainRenderer also honors the forced live-RAW
session. Main2x log proves `RAW developed ... block=2` and
`requestedRAW=true displaying=developed RAW`. Source preserves P134 RAW colour/LUT.
Evidence: `reports/pd2454-30826-main-native2x-render-result-20261016.txt`.
A controlled changing-light AE test was not performed for this native viewfinder.

Actual30826 UI selection, developed viewfinder, SHDR capture and JPEG save also
passed for UW2x, Tele2x and Tele4x, PID1968/UID10469. JPEG SHA256 on phone and PC:

- UW2x `14b9c9848d0edfe4e1de40fd37ddd8127cc944afd2c5d01672ba80d7904d9757`
- Tele2x `d99af515aca8d25b4cb8a447e4c3db49bcb37dcbf53a357edabdd6fc1c647514`
- Tele4x `d6158d11ee6624dc3625a62e3884c0725e2dcaaa763a5affaef1e75c1df4acad`

Evidence directory: `reports/pd2454-30826-native-isz-ui-direct-20261016/`:
selection/camera/capture logs, screenshots, JPEGs, manifest and geometry/hash report.
The JPEG contact sheet was visually inspected: meaningful scene, Tele4x tighter
than2x, no gross mosaic failure. This is not a stock image-quality equivalence test.
Reproducer: `evidence/vivo_hal_2026-10-13/tools/validate_cts_native_isz_ui.py`.

## Source and compatibility

- `capture/VivoPd2454NativeRaw.java`: exact device/package/profile guards,
  four native module offers, owner-key/delete persistence, physical camera match,
  force int[] on every builder. Excludes FULLRES/DCG and video/rawvideo/unlimited.
- `VivoPd2454Isz`, `VendorTagUtils`, `CaptureController`: native RAW-only route;
  never silently fall back to a PRIVATE stream for this mode.
- `MainRenderer`: forced RAW session uses developed RAW, not a stale PRIVATE frame.
- `CameraSounds`: exact CTS+PD2454 ToneGenerator fallback. The app otherwise
  crashed in native SoundDecoder before the camera opened. Original MP3s and
  ordinary builds are unchanged; audibility has not been assessed by the owner.
- `app/build.gradle`: opt-in `-PscameraCtsNativeRaw=true`; default package unchanged.
- SDK FULLRES fixes in the shared dirty tree remain separate. Do not replay patches.

Independent immutable30826 source tests:60 tests,0 failures/errors/skips across
8 suites. Evidence `reports/pd2454-30826-isolated-host-tests-20261016/manifest.json`.
The same8 suites were repeated on the actual30827 source snapshot:60 tests,
0 failures/errors/skips. Evidence `reports/pd2454-30827-isolated-host-tests-20261016/manifest.json`.
Shared `app/build` outputs can be overwritten by the other AI and are not the
record for that run. No full-repository test count is claimed for our changes.
`kHybMergeMain1` is unchanged by this work.

## Local build

Latest verified functional diagnostic30827:
`deliverables/local-shdr-20261015/SCAMERA-0.98-30827-CTS-NATIVE-ISZ-LOCAL-DIAGNOSTIC.apk`
SHA256 `6cb35d9960dca418d88c1295704b094c614d5852ecbd63c9db759dc39e5339fd`.
Installed bytes match (`reports/pd2454-30827-installed-apk-sha256-20261016.json`).
Fresh worker and25 pinned assets; immutable snapshot20261016-134254/project,
HEADe357ee43 plus dirty source. This includes the other AI's P156-P159 work.
Its extra debug guard changes only the bounded FULLRES experiment below.

Verified functional diagnostic:
`deliverables/local-shdr-20261015/SCAMERA-0.98-30826-CTS-NATIVE-ISZ-LOCAL-DIAGNOSTIC.apk`
SHA256 `057a52e7aeb89c1949f48db2416935e7b7fa722b88e20f6a1df350c0aa44fb88`.
The installed APK bytes matched. Fresh native worker and25 pinned assets verified.
Immutable source: `evidence/vivo_hal_2026-10-13/local-sdk50-prep-20261014/source-snapshot-20261016-130811/project`.
This is a local diagnostic with the public legacy diagnostic key, not a GitHub
Actions artifact or an owner release. Do not replace the primary package blindly.

Build a NEW version from a separate complete source snapshot:
`py -3 evidence/vivo_hal_2026-10-13/tools/snapshot_sdk50_sources.py`, then
`py -3 evidence/vivo_hal_2026-10-13/tools/build_shdr_fullres_local.py --project <snapshot/project> --version <newCode> --cts-native-raw`.
These tools do not publish or mutate the shared project build. Never read/copy
`local-secrets` or private signing material. Do not use the known-crashing archived
`VivoCtsAudioProbeActivity`; it was removed from the diagnostic manifest/source.

## Unfinished native FULLRES and DCG boundary

SDK FULLRES50 works on main/UW/tele through hardware remosaic. It does not satisfy
the requested native FULLRES. No app-delivered native50/200MP stream is confirmed.
Default RAW usage3 fails CameraService's advertised-size gate. Explicit usage0
passes that gate and reaches HAL `endConfigure=-38` on the native FULLRES profiles.
RAW10, logical0+physical3, three-stream full50, native sensorMode1 and ProUI16 do
not fix it. Exact original reports and negative results remain preserved.

The final targeted diagnostic combines usage0 with the existing decoded SDK40
bootstrap, restoring SDK at the first real endConfigure entry. Earlier gate tests
used usage3 and failed createStream BEFORE that hook ran. A new strict guard permits
only Long0 plus enabled endConfigure restoration, preserving the other decoded
FULLRES/device/own-attribution/fresh-process guards.29 guard tests and16 actual-source
proxy delegation/order/exception/cleanup checks pass. Original123 lifecycle and22
source-order checks still pass. Reproducer:
`evidence/vivo_hal_2026-10-13/client_identity/agent_cts_sdk_bootstrap_host_20261016/run_checks.py`.
These are host fakes for Android/provider dependencies, not Binder/phone evidence.

All SIX actual ordinary-UID/no-observer variants failed HAL endConfigure before
any RAW: main full4 with SDK1 and SDK-1; UW full4/SDK1; tele50 SW0-mode20 and
NoRemosaic-mode1; tele200 SW0-mode0. Each passed createStream and restored SDK40=0
before unchanged endConfigure delegation. Error: Function not implemented/onError4.
See `reports/pd2454-cts-native-fullres-end-gate-summary-20261016.json` and its
reproducible analyzer `client_identity/agent_cts_native_fullres_end_gate_summary_20261016.py`.
Do not retry these unchanged or promote the reflection/bootstrap hook to production.

The remaining research boundary is the actual writer of internal stream CFA7/8
and the corresponding full-RAW graph admission. The globally registered XCFA
session override is filtered by CameraService because it is absent from the
server's availableSessionKeys; changing a client characteristic does not bypass
that. Read `agent_xcfa_session_filter_20261015.txt` and
`agent_cfa80_ingress_boundary_20261016.txt` before proposing another tag or graph.

DCG RAW is still unresolved; no new DCG work was started after the owner's priority
instruction. Pixel/Sabre2x capability, old SDK50 captures and metadata echoes do
not prove native FULLRES or DCG. P146 separate FULLRES settings group remains open.
