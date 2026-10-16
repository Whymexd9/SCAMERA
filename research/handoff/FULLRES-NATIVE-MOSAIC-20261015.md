# Current request and local state, updated 2026-10-16

LATEST: `FULLRES-NATIVE-READY-20261016.md` supersedes this historical frontier:
native RAW FULLRES and AE work on main/UW/tele; main/UW50 SHDR JPEG proven.
Tele200 GPU merge works but the app was killed LOW_MEMORY before JPEG; bounded
chroma source candidate has separate tests. Full app integration is delegated;
DCG is deferred. No commit/push/Actions/upload.

This file supersedes earlier acceptance of HW-remosaic fallback as completion.
Owner now requires FULLRES and ISZ 2x/4x WITHOUT hardware remosaic, plus DCG,
on every rear camera. All feed SHDR under the ordinary app UID. No push/Actions.
Do not claim that hardware support on every module has been established.
Preserve the concurrent other AI's commits and edits; no reset/clean/patch replay.

## Completed intermediate work

- PD2454 main3 SDK RAW8192x6144/mode13, UW4 RAW8192x6144/mode11,
  tele5 RAW8160x6144/mode18 are wired into CaptureController and validated by
  exact RAW/result/JPEG timestamps, dimensions, nonzero payload and actual mode.
  These are HW-remosaiced Bayer, not the requested native mosaics.
- All three completed production SHDR/Sabre2x photos as ordinary UID10349.
  Main30818: JPEG16384x12288, SCA_20261015_120310.jpg,
  SHA256 259424167563ebe2b40ea3777adf25fe698606fdf1ffdd4862b3eea1d2826617.
  Tele30819: JPEG16320x12288, SCA_20261015_145325.jpg,
  SHA256 5b1bb43b8890a64e6b37762f3488a2626cea563489c18be6b7ba50e2a69f2d56.
  UW30819: JPEG16384x12288, SCA_20261015_150822.jpg; log saved. The owner
  subsequently removed the JPEG; do not attempt recovery or claim a local copy.
  Main owner capture on30819: SCA_20261015_151454.jpg, native grid16384x12288,
  saved39729436B. This confirms delivery/processing, not correct auto exposure.
- Copying all availableSessionKeys from actual preview to STILL fixed the
  mid-capture FPS change/reconfigure/onError4. Motion fixed30fps returned empty
  UW FULLRES RAW; variable FpsRangeAuto with Motion enabled worked in30819.
- Static3-frame limit removed in current source. Capture uses the existing
  getHybridZslFrames setting (including Motion overrides) and caps retained RAW
  bytes at half available memory. Serial capture and hybridFuture allow64,
  matching existing transport; UI setting already spans4..44. Existing later
  SHDR memory selection may retain fewer frames for merging; it is separate
  from delivered RAW count. APK30820 captured20 distinct matched RAWs and
  merged all20 normals at sensor output resolution, then saved a50MP JPEG.
  reports/pd2454-30820-main-count-ae-result-20261015.txt contains the capture,
  merge and save. SCA_20261015_205030.jpg is8192x6144,36482145bytes,
  SHA256 e155a3bb35d40e53082c994ef93e9e2916e9fc33f8e1cb63298f064802882ca0.
  This proves20-frame sensor output, not20-frame Sabre2x under memory pressure.
- Latest targeted host tests:38, no failures/errors. A real callback/binding
  test completes44 matched FULLRES frames; three camera profiles and session
  keys are exercised. Latest run log:reports/pd2454-fullres-count-ae-tests-retry-20261015.txt.
  Shared Gradle test XML is overwritten by the other AI's later test runs.
  Source also releases the ShotArena capture-owner reference immediately
  after detachForQueue; frame references keep it alive until processing ends.
  This lifecycle fix is not included in installed30820 yet.

## Auto exposure regression and verified fix

Owner screenshots Screenshot_20261015_151434.jpg/151438.jpg show blown FULLRES
preview versus normal preview. Historical30819 captures across modules repeatedly
report ISO250/16666667ns despite changed scenes. CONTROL_AE_MODE_ON by itself
does not establish automatic behavior.

Stock AdvanceCaptureRequestTemplate sets SENSOR_SENSITIVITY=0 and
SENSOR_EXPOSURE_TIME=0; ExposureTimeCommand/SensitivityCommand forward the
manual/auto values. Current production inherited the template's nonzero sensor
values. applyAutomaticExposure sets AE ON, lock false, ISO/time/frame
duration0 in the SDK graph only, for preview and STILL. SDK preview metadata
now logs once per2s, including requested and actual ISO/time and AE state.
Installed30820 requests0 and reports actual ISO160/10ms, AEstate2; after
metering changed, actual20ms and changing ISO were recorded with AE ON,
lock false and sensor requests still0. The20-frame shot completed with AE.
See pd2454-30820-initial-ae-result, pd2454-30820-main-count-ae-result and
pd2454-30820-ae-count20-result (all20261015). This verifies AE response;
the earlier daylit-window screenshots were not repeated in identical light.

Installed diagnostic APK30820:
deliverables/local-shdr-20261015/SCAMERA-0.98-30820-SHDR-FULLRES2X-LOCAL-DIAGNOSTIC.apk
SHA256 19541ebf918840482693800346faf044ab54d5e1f0ad35e841c8821dbafa3274.
Snapshot source-snapshot-20261015-160944, fresh current native worker,19 pins.
Source subsequently gained the arena lifetime fix and debug probe experiments.
Use install -r with the existing diagnostic key; do not downgrade/uninstall.

## Native-mosaic facts and next experiments

Existing actual selected-mode descriptor dump (not guessed model numbers):
reports/pd2454-native-main-force7-isz-usecase6-observed-20261015.txt:
- main Camera2 ID3: mode4,8192x6144,10bit,remosaicType2,CPP4,full sensor crop.
  Mode3 is4096x3072,CPP4,2x ROI. Current SDK mode13 isCPP1/remosaicType1.
reports/pd2454-native-tele-force6-observed-20261015.txt:
- tele Camera2 ID5: mode0,16320x12288,10bit,remosaicType0,CPP16,full sensor crop;
  mode20,8160x6144,remosaicType0,CPP4,full sensor crop;
  mode1/30,8160x6144,remosaicType2,CPP4,full sensor crop;
  mode5/29,4080x3072,remosaicType2,CPP4,2x ROI;
  mode7/31,4080x3072,remosaicType2,CPP16,4x ROI.
  Current mode18 isCPP1/remosaicType1. Dump's internal camera index is3;
  do not repeat earlier unverified assertion that tele internal index is2.
These descriptors alone DO NOT establish delivered native mosaics. Need real
nonzero payload, measured block2/4, correct FOV and metadata.

Probe v30 is installed (ordinary UID10467). It includes opt-in
--ez sdk_auto_exposure true using the same automatic-exposure helper,
RAW10 packing validation, RAW-VIF10, preview_only and raw_only diagnostics,
plus the source-backed Log V2 session bit and optional existing spoof helpers.
v30 SHA25632375b22ad647e0485dd05cbbc376e734270d61fefb9a5f32751b0a8bd5f9679.
No new HAL keys are invented. Existing force_sensor_mode/force_sensor_request
and provider_sdk_session can test the grounded full-sensor candidates.
Native_raw still returns before tag_ints; AutoHDR via tag_ints remains untested.
First establish native full-sensor RAW; do not expand numeric mode enumeration.
Fresh UW inventory is reports/pd2454-uw-native-mode-inventory-20261015.json,
from pd2454-uw-native-inventory-observed-20261015.txt:Camera2 ID4 is internal2.
UW mode4 is native50MP/CPP4/NoRemosaic2;3/21 are2x Quad. Mode10 is fused14bit
DCG3 at4096x2632. No genuine4x native-mosaic mode is declared for main/UW;
UW2048x1536 mode16 still covers the2x ROI. Do not label software crop true4x ISZ.

## Delivery/graph boundaries established on the phone

- Genuine XCFA session tag org.quic.camera.XCFARemosaicType.XCFARemosaicType
  (int32,0x809e0000) was echoed as0 in the app but absent from CHI session
  metadata:exact CHI lookup returned-2, same metadata pointer, guarded
  observerv3. It is absent from the advertised session whitelist. Android16
  Camera3Device/SessionConfigurationUtils filter explains this boundary;
  JNI/NDK/reflection on the same Camera2 path does not bypass the server list.
  See client_identity/agent_xcfa_session_filter_20261015.txt and
  reports/pd2454-xcfa-main-full-raw10-single-observed-20261015.txt.
- Correct native main4/CPP4 selection with RAW_SENSOR or single RAW10 still
  delivered no frames and hit a deliberate MCTFE pipeline failure. RAW-VIF10
  full+binned+PRIVATE was rejected during configuration, before CHI initialize.
  Native full RAW-only was also rejected before CHI (Function not implemented).
  These are distinct failures, not evidence that RAW10 cannot be unpacked.
- Native main12 DCG14 with only PRIVATE preview (no RAW/JPEG stream at all)
  initially configured and selected the descriptor but delivered zero frames.
  Therefore RAW export size is not the only remaining DCG cause.
  A fresh20261016 entry-only observer establishes cache/physical/URO DCG3,
  14bit,count2; field3a0=1 selects MCX mode730=3 over the unused fallbackC30=0.
  FeatureUpdate leaves Desired asDCG3/14. RT/ISP HDR count2 is intentional
  fusion policy in this firmware, not evidence of a two-stream mismatch.
  Do not repeat per-requestDCG3 as a presumed fix or force HDRcount1:the latter
  clears3/14 under the active policy. Reports/pd2454-dcg14-native-state-*-20261016.txt.
  MCTFE stage/CSL observers later proved actual backend submit0, not merely a
  frontend early-success. Pipeline callback/fence paths were then observed.
- NEW:the genuine SESSION Integer vivo.control.session.uiSwitch_state bit32
  enables the stock Log V2 processing graph. The debug --ez log_v2 true applies
  it before native_raw returns. Same probe30/UID10467 PRIVATE-only main12 gave
  552 real frames and completed results; probe30 without this flag gave0.
  reports/pd2454-dcg14-logv2-previewonly-result-20261016.txt and
  pd2454-dcg14-probe30-nolog-control-result-20261016.txt are the A/B evidence.
  This changes several graph/format/tuning consumers; do not attribute the
  entire fix exclusively to one internal flag or apply it to all photo modes.
  Adding advertised RAW_SENSOR4096x3072 in a repeating RAW ring again caused
  PRIVATE0/RAW0 despite retained DCG3/14 state. RAW export is still unsolved:
  pd2454-dcg14-logv2-raw-ring-{result,observed,camera-service}-20261016.txt.
  A working processed preview and white metadata1023 do not prove exported RAW14.
- RTMCX contains actual no-remosaic full/crop ports, but requires incoming
  ChiStream.cfaPattern markers7/8 and another graph gate. The allowed upstream
  setter is not established. The gate is now identified as static
  noRemosaicCaptureMode:default0=Disabled,1=CropPreview,2=XCFASnapshot.
  Exact chain StaticSettings+7588 -> ExtensionModule+90a4, plus LIVE observerv5
  initialize reads0, is documented in agent_noremo_native_graph_boundary_20261016.txt.
  persist.vendor.camera.noRemosaicCaptureMode is empty on the phone. No property
  or override-file writes were made. Generic PZSL/NZSL excludes remosaicType2,
  so simply changing UI16 to29 is not an established native-mosaic fix.
  Camera3 ingress CFA0 does not disprove internal synthesized CFA7/8 streams.
  Passive copies libvchinterfacewrapper.so and libvchinterface.so were pulled
  on20261016. They are proprietary research evidence; do not commit them.

Our app NPU remosaic also still has16MP guards in ScamMosaic, HexQuadBurst,
scam-quad-capture.h/scam-hexquad-capture.h. Its models run tiled, but lifting the
guards needs overflow/memory/geometry checks and native/phone validation after
actual native-mosaic RAW delivery. Sabre2x input cap was already removed;
that does not remove these separate NPU guards.

DCG has no successful delivered RAW on PD2454. Main force12 descriptor is
14bit/fused DCG3; processed preview now works with the stock Log V2 session bit,
but the RAW-ring graph still stalls. The earlier non-HDR graph crashed at null
AFD RS config; the video graph addressed that null without resolving RAW export.
See pd2454-native-dcg14-video-afd-verification-20261015.txt. No DCG production
availability was enabled. Do not relabel normal RAW/ISO/selected mode as DCG.

Only ADB10AFAQ1QE60071E. Other connected phone must not be touched. Root is
passive HAL research only; no runtime HAL modifications for production.
Owner explicitly freed the phone for testing. Parent controls all camera
experiments; close other camera clients gracefully before opening the probe.
Do not touch kHybMergeMain1, CFA/black/white/noise or other AI's sharpening.
201MP UltraHDR linear snapshot still exceeds ByteBuffer capacity; saved SDR
fallback is not proof of HDR gain map201MP. Tele50->Sabre200 is NOT sensorRAW200.
