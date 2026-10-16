# PD2454 SDK RAW routes, updated 2026-10-15

Latest status: FULLRES-NATIVE-MOSAIC-20261015.md. Owner now requires native-mosaic
FULLRES and ISZ2x/4x without HW remosaic, plus DCG, on every rear module.
SDK remosaiced FULLRES50 has now saved real Sabre2x photos on main/UW/tele;
it is an intermediate path, not completion of the updated native-mosaic request.
Dynamic capture count and a stock-derived auto-AE fix are prepared in30820,
not yet installed/tested on the phone. Read the new note before older statuses.

## Current owner scope, 2026-10-15

The owner now requests a local handoff for another AI to build a new APK. Do not
push or run Actions. SHDR (formerly Hybrid) is the only merge; SCAM HDR is being
removed. Stock NICE AE/ZSL bracket equivalence is no longer a task or completion
gate. Older Actions/bracket requirements below are historical and superseded.

The current primary prompt is CONTINUE-SNAPDRAGON-CLIENT.txt. Read the updated
HANDOFF.md and FULLRES-SABRE2X-SHDR-20261015.md. Preserve owner HEAD
0a1573918363d3610868371be2b9820a8e7752e4 and the full dirty tree.

Sabre2x's artificial 16MP input cap has been removed in Java and native code.
Explicit output2x requests true grid2; no 1x/upscale fallback. Large outputs use
FP16 transport, overflow-safe sizing and real GPU/storage limits. 94 Java tests
passed; the native 50MP transport/guard test passed on device UID2000. This is
not a live 201MP photo. Rebuild the current worker before any new APK; the old
prepared SDK50 worker and old SCAM_TONE_FILES checks are incompatible after P129.

DCG remains unverified. Fresh native force12 probes reject RAW4096x2632 (-38),
stall with advertised4096x3072 plus SHDR/count/DCG controls, or crash without
those controls in Bayer RS stats configuration (no observer in that crash).
The subsequent native record/useCase3 probe configured but delivered no frames.
A passive function-entry observer confirmed HDRFeatureMode1 and nonnull primary
and alternate AFD/RS configs on that path. Thus the earlier null is not its
remaining cause. The provider survived; no production DCG preset was enabled.
See reports/pd2454-native-dcg14-video-afd-verification-20261015.txt and the
individual commands/results/observer log. Missing live useCase echo is recorded;
do not overstate downstream video classification. The observer is detached.

Prior APK30802 FULLRES50 and APK30805 tele HW-remosaic4x/4:3 photos remain
hardware evidence for those builds. They do not contain the new Sabre2x changes
or establish that all later P129/colour changes were tested on the phone.

Read this after SNAPDRAGON-CLIENT-HANDOFF-20261013.md. Preserve concurrent work in
the current migration/nice-camera2 checkout. No push is authorized. HEAD at the
start of these tests was a3b6d1663b2ff0273a058187dbfcfab69a64681b.
The checkout advanced concurrently to owner commit
a0f5911462253ae3443797cf3eba4ae746b5118a. Preserve it; this is not the APK's
source provenance, which remains the immutable snapshot recorded below.
Later owner HEAD observed during handoff validation:
821e1eac04087cde2273bf3c6c8490ea9527c734. Preserve subsequent commits too.

## Device and scope

- Only ADB 10AFAQ1QE60071E (vivo X200 Ultra / PD2454) is used. The second attached
  phone belongs to other work and must not be touched.
- Root is used for passive HAL research. Camera2 capture and the production
  worker run under the ordinary application UID. No firmware edits are made.
- The SDK50/tele force6 hardware proof is limited to the tested PD2454 firmware.
  Do not extend its modes or readiness to other vivo devices without evidence.
- The owner prefers ISZ without remosaic, DCG RAW, and FULLRES. A demonstrated
  remosaic fallback is allowed when necessary, with an honest label and 4:3
  processed output. forceSensorMode17 is native 16:10; do not advertise a
  remosaic route as native-mosaic ISZ or auto-enable an unverified preset.
- Keep the existing DCG and FULLRES buttons. ISZ uses separate zoom modules.
  Mode selection must exclude the other two features without editing module
  tunable-key definitions or discarding another AI's changes.

## FULLRES evidence and production boundary

- Earlier genuine com.scamera.probe v20 / UID10467 tests showed main camera3 SDK
  UI16, RAW8192x6144 buffer usage0, PRIVATE preview, and JPEG supply real
  HWREMOSAIC mode13 RAW under automatic AE in the earlier bright scene.
- Three serial STILL requests each supplied a different, nonzero RAW50MP and
  a JPEG with the exact matching sensor timestamp. See
  reports/pd2454-sdk-full-serial3-jpeg-timestamps-result-20261014.txt. Their
  timestamps are 265709699631946, 265710243672779, and 265710754390279.
- Adding PRIVATE preview to the STILL targets stops both RAW and JPEG delivery.
  Keep PRIVATE configured and repeating; target only RAW and JPEG for capture.
- The production driver is wired locally into CaptureController. It requires
  RAW/JPEG/result identity, distinct timestamps, nonzero RAW pixels, correct
  dimensions, and actual mode13. It sends three serial future NORMAL frames
  into existing Hybrid processing. This is PSL, not stock NICE/ZSL/bracket.
- SDK properties40/33 are acquired before open and restored after CameraDevice
  close, including errors. The helper remains dependency-free and usable by the
  standalone ordinary-UID probe. RAW50 repeating delivery is not proven.
- Diagnostic SCAMERA30801 APK SHA256:
  80832302eaa7bd979c02ebceb441db5b442250ad8df01b66f96adff61fae8658.
  It is built from source-snapshot-20261014-180942, has all25 pinned runtime/model
  assets and a rootless worker, and is NOT a GitHub Actions final artifact.
- Main UI test reports/pd2454-sdk50-main-first-capture-20261014.txt exposed a
  production bug: FULLRES was shown ON while prepareVivoSdkRawSession excluded
  the internal enum MOTION used by the only user-facing Photo mode. It captured ordinary12MP
  Hybrid RAW instead. Do not cite that JPEG as FULLRES success. The local mode
  guard now accepts CameraMode.isStill; meaningful production reachability
  and existing-switch regressions passed34/34 checks. The corrected production
  driver was subsequently verified in diagnostic30802 as described below.
  The only user-facing mode remains Photo (internal MOTION enum).
- Production diagnostic30802 / org.codeaurora.snapcam UID10349 completed
  main camera3 FULLRES50 under automatic AE ISO250 / 16666667ns. Three matched
  future NORMAL RAW timestamps were 293766248467779, 293766692538613, and
  293767103286113. Actual capture mode13 and RAW8192x6144 were recorded; the
  ordinary-UID Hybrid PSL worker saved JPEG8192x6144 with ArkLumaSharpen.
  Evidence: reports/pd2454-sdk50-30802-capture-result-20261015.txt and
  reports/sdk50-live-20261015/SCA_20261015_000719.jpg. The worker log field
  root=10349 denotes its UID, not root privilege. JPEG SHA256:
  8876589daefd4e0419cfd6387e62899466c75226d646420b5d8d4236947921e4.
- Installed diagnostic30802 APK SHA256:
  31e3cc46a1c707d67d64d111902e42dab852173265d5155cb21b642aca9c5843.
  File: deliverables/local-sdk50-20261014/SCAMERA-0.98-30802-SDK50-LOCAL-DIAGNOSTIC.apk.
  Its source snapshot is source-snapshot-20261014-232329. This is a local
  diagnostic APK, not the final GitHub Actions artifact. Both APK and JPEG
  hashes were independently checked on the host. Main3 is the only verified
  production SDK50 camera; this does not establish tele200 or RAW ZSL.
- Existing PostPipeline uses ArkLumaSharpen. RawTherapee was removed by prior
  P118 changes; earlier SDK integration reports claiming it was retained were
  inaccurate. Preserve the current pipeline and do not roll back the other AI.

## New source after diagnostic30802: availability and processed ISZ 4:3

- SDK FULLRES availability on verified PD2454 main3 now defaults to offered
  when vivo_sdk_fullres is absent; an explicit devfile value0 opts out. The
  user's FULLRES toggle still defaults OFF. The change is included in installed
  diagnostic30805. Existing Pixel FULLRES semantics are preserved.
- Existing DCG/FULLRES buttons and ISZ module selection exclude the other
  features through a central coordinator. There is no new row of buttons.
  SDK capture failure disables FULLRES with feedback instead of silently
  delivering 12MP while it stays ON; a later manual bright-scene retry is allowed.
- A per-shot ISZ flag is frozen at shutter and transferred through ImageSaver,
  DefaultSaver, and ScamProcessor. It bypasses the legacy 16:9 RAW crop only for
  that shot, then crops the processed RGB to an exact even-centered 4:3 (3:4
  portrait) rectangle without stretching. HDR gain-map coordinates are cropped
  to the same fractional rectangle. RAW payload/CFA and the native whole DNG
  remain unchanged; no unsupported DngCreator DefaultCrop API was invented.
- IszCapturePolicy separates an ISZ profile from arbitrary forced modes/DCG.
  PD2454 owner routes, validated per-slot markers, Pixel crop modules, and an
  actually active CROPPED_RAW session are checked; per-camera offered flags do
  not establish that the selected slot is ISZ. No preset6/17 was auto-enabled.
- Grouped host runs passed81 distinct checks for the 4:3/availability baseline,
  plus compilation and existing SCAM capture checks, before diagnostic30805 was
  installed. The subsequent enabled Session/Integer force-key refinement passed
  its9 policy tests (including2 new regressions) and compilation. Host geometry
  tests do not prove a working ISZ sensor path.

## Public SportStagger route and RAW sink failures

- Real public/session key vivo.control.SportStagger writes StreamHandler+0x1fc.
  UI35 + SportStagger1 + motionCaptureMode0 selects SCENE_SHDR_SNAPSHOT0xf00.
  motionCaptureMode writes+0x2ac; value3 overrides this to FULL_BYPASS0x1000.
- Live ordinary-UID probe confirms scene3840, UI35, sport1, override0, SDK1,
  actual STAGGER mode2 and stable preview, but app RAW4096x3072 is all zeros.
  Reports pd2454-sdk-sport-stagger-raw-{result,observed}-20261014.txt.
- Raw buffer usage0 correctly classifies EXT_RAW_OUT7 / variant15. The internal
  graph has two RAW10 RDI streams; this alone does not bind them to app RAW.
- Full SDK capture protocol with fresh capture IDs/PAP and sensorMode0 changes
  captureRequire from no capture info to hasAlgoCaptureInfo=true / result1.
  It then crashes in InRTDecisionRule+0x1b72a0 while cloning a null RAW feature
  source via libvcf_platform_utils.so+0xb4fb4. This is not evidence that extra
  RTYUV/Nav streams alone fix it. See pd2454-sdk-sport-full-protocol-* reports.
- A realtime transport candidate with RAW usage65539, streamsUsage[2,1,12],
  RECORD repeating and RAW target still classified group0 / variant0 under
  scene0xf00 and timed out with no preview/RAW. Group0 alone does not disprove
  the classifier branch: helper0x3ce70 can look up setting key12 and fall back
  when no StreamLocation exists. F00 XML has no such location. Observe the
  actual key/lookup before calling it a VIDEO_RAW binding failure. See
  pd2454-sdk-sport-video-raw-ring-* reports.
- Exact capture observer confirms InRT mainCamType1 / cameraIds[3], correct
  for this main camera. It requests feature0 named Wide and receives zero
  streams, then clones an empty source. This disproves the speculative camera
  ID/Master alias mismatch. Evidence: pd2454-sdk-f00-exact-source-observed-
  20261014.txt. The missing feature source remains unresolved.
- Correct enum: 0x704 is OIS_STATIC_CALIBRATION, not SHDR_SNAPSHOT. Portrait IC
  0x1900..0x1906 and third-party HDR0x707 are separate routes.

## ISZ and DCG boundaries

- Latest native main3 constant SESSION+REQUEST force3 and force7 probes each
  failed with onError4, previewFrames0, and no RAW. Both requested4096x3072,
  usage0, PRIVATE preview, and JPEG. force3 selects a two-exposure mode while
  desiredHdrRaw remains0; force7 selects a single-exposure mode and also fails.
  Thus the HDR mismatch is a clue, not a complete explanation of the failure.
  Evidence: reports/pd2454-native-main-sticky-force{3,7}-{result,observed}-20261015.txt.
- A separate rootless native tele camera5 v21 probe / UID10467 with constant
  force6 in SESSION and REQUEST delivered real RAW4080x3072, JPEG, and result
  at timestamp300318370906892, with58 preview frames. Whole-payload statistics
  are min36/max1023; the sampled log reports min100/max1021. The selected mode6
  has a4080x3072 ROI inside16320x12288, remosaicType1 (HW), pixelsPerColor16.
  RAW correlation against the mode2 baseline (timestamp300282799964345) peaks
  at4x:0.577, compared with1x:0.101 and2x:0.298. This is a demonstrated 4x
  sensor crop with remosaic, not a2x or native-mosaic route. Evidence:
  reports/pd2454-native-tele-force6-{result,observed}-20261015.txt and
  reports/pd2454-native-tele-force6-fov-20261015.json. Production integration
  subsequently passed on diagnostic30805, as detailed below. Derive display
  zoom from the existing tele base rather than hard-coding a1x-relative label.
- SDK UI71 + standard CONTROL_ZOOM_RATIO2 + is_pro_mode1 enables the modern VAS
  ROI detector and yields [3,3,-1]. Hardware ROI/export is still unproven:
  the app RAW is dummy/BIN. vivo.control.zoom_ratio3 alone is insufficient.
- Request-only clearing SDK40 does not refresh the StreamHandler cached SDK
  flag. Missing session keys cause CameraService reconfiguration and AppType2;
  preserving every session key prevents that reconfigure but leaves SDK route
  scene513 cached. No request-only escape is proven.
- DCG public controls can initialize actual DCG3/14 and matching desired/cache
  state. Snapshot/record RAW still fails in the VCF/CHI graph. RECORD+PRIVATE
  fixes one null/empty-node crash but does not deliver RAW. Native-mosaic ISZ
  and DCG RAW remain unfinished; the tele HW-remosaic fallback is verified.

## Continuation

1. Installed diagnostic30805 has verified the camera5/back8 HW-remosaic4x
   fallback through the overflow menu, eight pre-shutter RAWs, ordinary-UID
   Hybrid processing, and a saved4:3 JPEG/HDR shot. Preserve that route and
   rerun the main3 SDK50 path when its source changes. Keep auto-AE refusal
   explicit. The strict force-key policy passed9/9 host tests.
2. Decode the missing RAW feature source at InRTDecisionRule0x1b5cbc..0x1b72a0
   before proposing more F00 target combinations. Correct the VIDEO_RAW12
   classifier assumptions from actual group0 evidence.
3. Tele force6 HW-remosaic4x is verified in production on the tested PD2454
   firmware. Preserve its real4x label and RAW geometry when continuing research.
   Continue native-mosaic ISZ FOV/CFA and DCG RAW bit-depth/levels research;
   sensor metadata alone is insufficient. Do not infer tele200 from tele50.
4. Keep live provider PIDs fresh; passive observers require READY and must not
   be detached before the probe. Preserve runnable commands and original logs.
5. Current delivery is a local source/evidence handoff and a new local APK built
   by the next AI. No push or Actions is requested. Verify that APK's SHA256.

## Later integration and diagnostic corrections, 2026-10-15

- Native tele force6 also delivers continuous PREVIEW RAW:39 distinct nonzero
  frames and35 preview frames, without STILL capture. See
  reports/pd2454-native-tele-force6-raw-ring-result-20261015.txt. This proves
  continuous transport; it does not prove the stock NICE bracket or scheduler.
- VivoPd2454Isz offers a separate camera5 module with base zoom multiplied by4
  and an explicit remosaic label. Its typed Session/Integer6 is retained in
  every native request; its RAW reader uses usage0. The default stock preview
  profile is excluded for this preset. The existing FULLRES/DCG switches are
  mutually exclusive with it. The native RAW/DNG buffer remains intact.
- PD2454 now has9 rear module slots, preserving all8 existing owner slots.
  Other devices and front modules retain8. An unsuccessful allocation does not
  consume the offer; deleting a successfully offered module keeps it deleted.
  A30803 allocation failure had already consumed the old phone's offer marker:
  no speculative reset migration was added. A ninth module was created through
  the ordinary app UI, camera5, zoom9.6, sensor crop true, Session/Integer6.
- The strip clips overflow and its horizontal drag controls zoom. A long hold
  on an existing lens button now opens all visible modules when they overflow;
  selecting one uses the existing switch handler. Compile and4 isolated UI
  tests passed. Capacity/coordinator/session tests passed67/67.
- Earlier raw_use_case commands incorrectly used --ei despite getLongExtra.
  They therefore tested useCase0. Fresh v23 commands use --el and explicitly
  log RAW stream usecase6. Logical0 + physical RAW3 still selected BIN mode1;
  corrected direct main force7 still failed without RAW.
- MCTFE input properties, translation, crop mapping and frame metadata return0;
  its backend libcamxhwlfrontend.so+0x64b00 returns4, causing system event1047.
  Exact evidence: pd2454-native-main-force7-isz-usecase6-observed-20261015.txt.
  The deeper backend observer v3 caused SIGILL at an instrumented interior
  instruction. Retire its v1/v2/v3 probes: that crash is instrumentation failure,
  not additional evidence against the sensor route. No public fix is proven.
- Diagnostic30804 APK SHA256:
  6f530e87cff82cd55524f77ef810755174f6cce2beb5255bdb985f5393d57ab0.
  It lacks the overflow selector. Installed diagnostic30805 was built from
  immutable source-snapshot-20261015-033646. APK SHA256:
  4ea7694e2f3f0bd3e74d777144495fa68133c0fa2c439adf2f2c38d10bee1bae.
  File: deliverables/local-sdk50-20261015/SCAMERA-0.98-30805-ISZ43-SDK50-LOCAL-DIAGNOSTIC.apk.
  The host hash agrees with its .sha256 and manifest (25 pinned assets).
  Overflow selection passed4/4 host UI tests, including a real stationary hold
  and unchanged zoom drag. Its live tele4x remosaic capture also passed as
  detailed below. Native-mosaic ISZ and DCG RAW are unfinished. No push/Actions run occurred;
  this local diagnostic is not the final Actions APK with SHA256SUMS verification.

## Production tele HW-remosaic4x capture, diagnostic30805

- The overflow menu selected the existing ordinary-UI back8 module: camera5,
  zoom9.6 (= owner base tele2.4 multiplied by4), sensor crop true, remosaic
  label, and enabled Session/Integer forceSensorMode6. Capture logs show
  mode1 ->6; MosaicStream measured block1 (ordinary Bayer, not Quad/Tetra).
- At04:01:41 the internal MOTION / user-facing Photo route selected eight
  matched pre-shutter RAW ring frames with zero future requests. Hybrid
  processing ran as worker UID10349. This proves a real past-frame ring capture
  for this fallback. It does not prove the stock NICE4N+S+ES+L bracket; even
  the log flag stockAePlan=true does not change the observed8N/future0 shape.
- The native RAW input remained4080x3072. Existing Hybrid settings produced
  a2x merge8160x6144, then Lanczos output5184x3904; the final ISZ RGB crop
  produced5184x3888 with the gain map registered to the same rectangle.
  No stretching or change to the native RAW/CFA buffer was introduced by this
  crop. DNG handling retains the native buffer; a saved DNG was not verified
  separately in this run. The5184 output is not a5184 sensor-readout claim.
- Saved reports/isz-live-20261015/SCA_20261015_040141.jpg is5184x3888 (exact4:3),
  33355826 bytes, SHA256:
  ed5246ae5efe7c0f38d674a08dc480126bda7680537d25d27b3bccbb62716293.
  Hash, primary JPEG dimensions, and byte count were independently verified.
  Evidence: reports/pd2454-30805-isz-capture-result-20261015.txt and
  reports/pd2454-30805-isz-verification-20261015.txt, plus
  reports/isz-live-20261015/JPEG-SHA256.json. The coordinating agent inspected
  readable monitor pixels/text; no stock image-quality equivalence was tested.
- Main/UW native-mosaic ISZ, DCG RAW, tele200, SDK50 RAW repeating and the stock
  NICE ZSL bracket remain open. Final Actions delivery is unavailable without
  the owner's separate push command and an artifact/SHA256SUMS verification.

## Checkpoint boundary

- The local checkpoint preserves the complete current tree, patches, HEAD/refs,
  and immutable snapshot SOURCESHA/Git metadata. Original Git remains untouched.
- history.bundle is intentionally omitted because historical signing blobs
  exist in the history. Do not recreate or distribute that bundle as a handoff.
