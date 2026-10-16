# PD2454 client identity: local checkpoint, 2026-10-13

## Readiness

Client attribution spoof is implemented and observed on the owner's X200 Ultra (PD2454, Android 16).
This does NOT establish ISZ, DCG, FULLRES or a stock bracket. All new Qualcomm experiments default off.
Do not push. Several agents edit this checkout; preserve unrelated changes and commits.

## Evidence and identification chain

Files below are relative to `SCAMERA-PC/`, outside the Git repository unless stated otherwise.
Proprietary libraries must remain outside Git.

1. Android 16 CameraManager passes `AttributionSourceState`. Replacing getOpPackageName alone does not replace this.
   The old Binder string splice changed a string's size without updating the enclosing sized Parcelable.
   The phone reported `ParcelFormatException: Not enough data` and the old handler retried the original transaction.
2. Typed `ICameraService` proxy now clones the real Parcelable with its CREATOR and changes only packageName.
   UID, PID, attribution tag, token and the following Parcel argument are retained.
   SpoofCameraContext also returns an AttributionSource with the selected package and original UID.
3. `libcameraservice.so`: exported `findAppType` thunk 0x517a48 -> implementation 0x3fd2dc;
   `findVCFAppType` thunk 0x517a50 -> 0x3fd7a8. Main package table at 0x52cc50, 2800 bytes, 16-byte entries.
   Observed mappings: com.android.camera=1, unlisted=2, com.vivo.alphacamera=5,
   com.iqoo.engineermode=6, com.vivo.bsptest=7, WeChat=8, Kuaishou=9, YouTube=13, Douyin/TikTok=35.
   The additional VCF override table is at 0x52d740 (160 bytes); NOT the unrelated 48-byte table at 0x52d7e0.
4. CameraService propagates AppType through VivoDevice property 1 and VCFAppType through property 33.
   `libprovidertohal.so` exports gAppType at 0x24120; set_param_to_hal(1, value) writes it.
   VCF PlatformUtils queries provider parameter 1, and queries 33 separately for VCFAppType.
   Read-only root observations confirmed AppType=1 in CameraService and the provider for the typed spoof.
   No firmware patch, root shell or Frida is used by the SCAMERA identity code itself.
5. Separate real framework API: android.hardware.vivocamera.VivoCameraManager.getInstance(), getInfo(int),
   setValue(int,int), service media.vivocamera. It is callable by the ordinary SCAMERA process on this firmware.
   setValue is a one-way Binder call: an immediate getInfo can return the old value.

Disassembly/decompiled framework: `evidence/vivo_hal_2026-10-13/client_identity/`.
`evidence/vivo_hal_2026-10-13/tools/packed_relocations.py` decodes Android APS2 relocations.
`client_identity/platform_vtable.txt` resolves the PlatformEnvironImpl vtable:
address point 0x2fc128, offset 0x178=getAppType, 0x188=refreshAppType,
0x1c0=getUseVivoCamera, 0x218=goPureBypass.
`go_pure_bypass.txt` shows AppType-dependent exclusions and getUseVivoCamera; setting VCFAppType alone
does not select this route. `configure_streams_impl.txt` starts by refreshing AppType for the camera.

## Device experiments (all APKs local diagnostics)

- 27090/27091: typed attribution connects as com.android.camera, real app UID 10349.
  Normal preview + RAW session then errors with camera error 4. Provider crashed once at 0x5cae4.
- 27092: logical camera 2 with physical camera 3 streams also fails.
- 27093: setValue(33,2) after open gives AppType=1 / VCFAppType=2; RAW still fails.
- 27094: native package + setValue(1,1), immediate getInfo returns 2; asynchronous write is not a barrier.
- 27095: native open + setValue(1,1) + 100 ms wait gives AppType=1 / VCFAppType=2.
  With sensor routes off: stable ~30 fps, 20 past RAW plus 6 new RAW delivered and SCAM Hybrid JPEG saved.
  `reports/pd2454-provider-app-raw-shot-log.txt`: PID 8696, 16:15:26-16:15:32, hybrid frames=26,
  SHOT TIMELINE saved=5937 ms. This proves RAW continuity, not stock AE or sensor modes.
  A subsequent root read found provider gAppType=2: classification is reset during requests/capture.
- 27095: enabling UI=[1], seamlessMode=[3,0,0,0,5,0] on camera 3 stalls the preview.
  `reports/pd2454-provider-app-isz-log.txt`: PID 8028, PREVIEW_STALL, no frames since session start.
  No FOV result, no proof of a sensor crop.
- 27096: native open + provider AppType + vivo.control.reprocessMode=[1] causes camera error 4.
  The tag exists in the request-key dump. Do not call this a working bypass.

Related reports: pd2454-live-client-app-types.txt, pd2454-client-spoof-provider-crash.txt,
pd2454-native-open-provider-app-delayed-log.txt, pd2454-native-open-live-app-type.txt,
pd2454-reprocess-client-log.txt, and matching *-camera.txt dumps.
Launch explicitly with `am start -n org.codeaurora.snapcam/com.particlesdevs.photoncamera.ui.camera.CameraActivity`;
monkey may choose the gallery launcher instead and invalidates a camera test.

## Precise remaining boundary

Updated after further device research on 2026-10-13:

- The primary typed-spoof failure is CameraService's permission check on the first request:
  `Camera access permission lost mid-operation`. It checks the spoofed package against real UID 10349.
  Evidence: `reports/pd2454-static-service-error-origin.txt`. Hook the actual `/system/bin/cameraserver`
  executable, not its unused copy in libcameraservice.so. DebugSymbol lookup crashed cameraserver;
  use raw addresses only. The provider's empty-map crash is secondary to closing the errored session:
  the map initially has IDs 0/1/2/3 and close() clears it before the in-flight lookup.
- Real API VivoCameraManager.setValue(40,1), followed by the one-way Binder barrier/readback,
  promotes the actual SCAMERA package to AppType 1 without altering attribution. UID remains 10349.
  Logical camera 2 then delivers YUV and JPEG, but both RAW buffers are zero. Calling property 33=1
  before open does not fix this. reprocessMode=1 resets property 40 to zero (CameraService disassembly).
- ModeSelector Parameter's third component is a BYTE offset. `.4` addresses the SECOND int.
  The matching ROI array is `[3,5,0]`, not `[3,0,0,0,5,0]`. Source: get_value_tag, d230/d290;
  `client_identity/selector_tag_byte_offsets.txt`. Sensor selection still requires device verification.
- Framework physical IDs and ModeSelector camera IDs are different domains. Do not map them by number.
- Native Camera2 camera 3, session forceSensorMode=1 versus 2, both deliver nonzero 4096x3072 RAW,
  white 1023. Downsampled full-scene NCC peaks at scale 1 (0.99979): no measured ISZ.
  Evidence: `reports/pd2454-native-force1.raw`, force2.raw and corresponding logs/preview PNGs.
- Stock Pro RAW uses camera 3, UI 16 (corrected by cold-start dumpsys on 2026-10-14), YUV 1440x1080 + RAW 8192x6144 + JPEG 8192x6144 +
  RAW 4096x3072, streamsUsage `[2,1,0]`. A read-only Java observer confirms that stock's 50 MP RAW
  is zero too, while its 12 MP RAW contains actual sensor data (sample min 60/max 560).
  `reports/pd2454-stock-raw-image-contract-v3.txt`. The real stock DNG is 4096x3072, white 1023.
- Full availableStreamMap (832 bytes) was captured from stock, retained as a diagnostic binary in reports.
  Replaying it with the stock session integer tags does NOT fix SCAMERA's zero 12 MP RAW.
  `reports/pd2454-stock-map-replay.txt`, UID 10349, no root observer during this replay.

ISZ/DCG/FULLRES are NOT confirmed. Next investigate the difference in stock versus SDK client RAW routing,
including the RAW capture decision and physical stream binding. Do not count a dummy 50 MP buffer as FULLRES.
`app/src/debug/.../VivoQcomProbeActivity.java` is an ordinary-UID diagnostic, not production integration.
It restores SDK/VCF properties on normal destruction and logs empty RAW as failure; finite 20 second runs.
Latest local probe APK 27094 is not an Actions artifact. No push was authorized.

## Reproduce safely

Use developer switches only; no system changes:

- vivo_qcom_spoof=1: typed package spoof (known RAW failure).
- vivo_qcom_provider_app=1, vivo_qcom_spoof=0: ordinary open, then provider AppType=1.
- vivo_qcom_sensor_routes=1: unverified sensor/session keys; known preview stall for the tested ISZ route.
- vivo_qcom_logical=1: unverified physical stream routing via camera 2.
- vivo_qcom_vcf_bypass=1: diagnostic parameter 33=2; does not fix RAW.
- vivo_qcom_reprocess=1: diagnostic request tag; camera error 4 on the tested configuration.

All these switches default false. The temporary phone scam_dev.txt was created by this investigation
and removed afterward. Restore only our generated version metadata after confirming no other build is running;
do not reset unrelated edits. Do not apply the checkpoint patch over the existing changed tree.

## Host validation

CameraAttributionSpoofTest 2, VivoMtkIszTest 5, DcgSearchTest 5, PixelPro10Test 3: 15 tests, no failures/errors.
The first test serializes a real sized AttributionSourceState followed by a sentinel and checks the Parcel boundary.
Local APK compilation succeeds. The CI workflow includes CameraAttributionSpoofTest.
No GitHub Actions APK is delivered or claimed final; no push was authorized.

Final local diagnostic 27097, installed with the experiments off, has stable ~30 fps (PID 13483):
`reports/pd2454-client-defaults-off-log.txt`. SHA-256:
`2a3186b21ea858ed30720b0de642ce51b1e6d7a7cfdca034217fbc2b3e936aa1`.
This is not an Actions artifact. Source checkpoint includes concurrent changes; do not attribute all of it to this task.

## Further research, 2026-10-14

The earlier final diagnostic 27097 above belongs to the previous checkpoint. New local builds reused that
version counter after restoring version.properties; identify APKs by their checkpoint path and SHA-256.

- The stock cold-start Pro session has VCFAppType 2, not 1. Native read-only streamConverter observations
  show stock StreamInfo camera field +0x28 = 3 and scenario field +0x90 = 1536, UI +0x94 = 16.
  Our SDK40/VCF1 probe has camera field 2 and scenario 513. These offsets are observations, not an enum mapping.
  reports/pd2454-vcf-raw-route-stock-compare.txt contains normal Photo first, then cold-start Pro.
- SDK40 + VCFAppType 2, all stock integer keys, availableStreamMap, frameFlag/displayTimeStamp and
  dual RAW still give zero 12 MP RAW. Result activePhysicalId is 4, focalLength 4.03 despite requesting 10.09.
  reports/pd2454-stock-vcf-type2-replay.txt.
- Provider 38=1 plus satAppControlInfo [2,1] also fails to move the master or fill RAW;
  reports/pd2454-third-party-sat-replay.txt. Provider 25=1 (mAlgoPost_for_third) did not fix RAW.
- Provider keys: 7=gEngeerMode, 25=mAlgoPost_for_third, 38=mIs3rdSAT, 40=mUseVivocamera,
  52=mIs3rdUseHDR. Key 49 calls set_icframeCount, NOT mIs3rdSAT. Key 44 deliberately aborts the provider:
  never probe random keys. Most set operations reset gEngeerMode to -1 in their common epilogue.
  The initial provider_parameter_keys.txt decoder prints extra targets from adjacent basic blocks;
  only the first target of a direct case is reliable.
- Provider 52=1 is readable/writable by app UID 10349 and produces nonzero native 12 MP RAW on camera 3
  with UI 16. This is baseline RAW delivery, NOT a demonstrated HDR/DCG mode.
  PlatformUtils::utilGetIs3rdUseHDR explicitly queries 52 (8e050); go3rdHDR also has a platform capability
  gate (+0x231), config and property checks. No persistent property was changed.
- Registering VivoCameraManager.open under com.android.camera while Camera2 keeps its genuine attribution
  was accepted but still produced zero RAW. reports/pd2454-split-sdk-attribution-replay.txt.
- AlphaCamera APK/source were copied for research outside Git. Its CameraActivity and CameraSocketService
  are not exported. Its LocalServerSocket is named CameraSocketService; no ordinary-app proxy was demonstrated
  or installed. Do not replace the system APK or assume access to the private service. Alpha uses request keys
  vivo.control.engineer, vivo.control.is_engineering_mode, vivo.control.EngineerRemosaicMode and session
  forceSensorMode. Engineer values are bit masks, not UI IDs.

Next investigate how StreamHandlerBase::initAndParseParams/generateConfigInfo choose camera/scenario and
classify the DNG stream for SDK versus stock. Generic parameter replay and JPEG are insufficient.
None of ISZ/DCG/FULLRES has been completed.

Host regression on 2026-10-14: CameraResumeTest 21, CameraAttributionSpoofTest 2,
VivoQcomStreamUsageTest 1: 24 tests, zero failures/errors. Report pd2454-client-regression-20261014.txt.
The stream-usage test checks our helper's encoding; it does not validate the stock VCF contract.

Cleanup: our temporary scam_dev.txt was removed only after comparing its tokens with the saved original
research file (ADB adds doubled CRs). SDK40=0, provider25/38/52=0, VCFAppType33=2 were read back.
No Frida helper remains. The normal SCAMERA activity was reopened and configured physical camera 3;
reports/pd2454-final-native-defaults-20261014.txt. This confirms session configuration, not new sensor modes.
Generated app/version.properties was restored to HEAD (27074) without resetting other sources.

Checkpoint: ../checkpoints/snapdragon-raw-boundary-20261014-024300 (184 files verified).
Git history includes the concurrent P125 commit 568fa7a; diagnostic APK 27097 was built before P125.
APK SHA256: 25bba10258d7ea3a41c0a4ad5eabeb74e22e615d25eca5d9c0e570fbf5ba289b.


## SDK RAW breakthrough, 2026-10-14

This section supersedes the earlier claim that SDK RAW always stays zero. Production CaptureController
integration is still incomplete. No ISZ/DCG/200 MP completion or Actions APK is claimed.

- Fresh cold-start Pro opens framework camera 3, not logical camera 2. StreamHandlerBase stores the
  configuration device argument at +0x140 (2e990), so this field is a camera ID. Camera 3 chooses scenario
  1536; camera 2 chooses 513. The previous apparent ID remapping was a mistaken comparison of different cameras.
- SDK camera 3 with ordinary CPU-read ImageReader cannot configure RAW 8192x6144: CameraService rejects
  it in createConfiguredSurface before calling the provider. Its metadata contains RAW 4096x3072;
  the rounding cap excludes that size as a substitute for 8192. This error does NOT mean all RAW sizes are absent.
  Read-only CameraMetadata inspection must read mBuffer at object +8, confirmed by getAndLock disassembly.
- ImageReader.newInstance(w,h,RAW_SENSOR,maxImages,0L) bypasses flexible-consumer rounding and, on this
  PD2454 firmware, still supplies readable RAW planes. usage=65539 configured but gave no preview; it is not a fix.
  Vivo3rdController::shouldSkipCheckconfigureStream is a separate package/config check; it reads
  vendor.camera.privapp.list and /system/etc/vivo_camera_third_compat.json (remote override in /data/bbkcore).
- Working full-resolution Camera2 probe, real package org.codeaurora.snapcam UID 10349:
  SDK property 40=1 BEFORE open, VCF property 33=2, camera 3, UI 16, Pro/currentModeEx 256,
  echo=1, eis.config.enable=0, advance_fullsize=1, ai_highresolution=1, reprocessMode=0,
  RAW ImageReader usage 0, YUV preview 1440x1080, RAW 8192x6144, JPEG 8192x6144.
  Separate VivoCameraManager.open registration, the copied 832-byte availableStreamMap, all stock integer
  keys, and the auxiliary RAW reader are NOT necessary for the successful 50 MP capture.
  See reports/pd2454-sdk-full-three-stream-command.txt and corresponding result log.
- Native read-only get_sensormode observation: main capture DNGHWREMOSAIC, mode index 13,
  8192x6144 at 30 fps. Nonzero RAW repeated without any Frida helper. All 50,331,648 uint16 pixels in
  the saved first 50 MP frame are nonzero, range 64..1023; no nearest-neighbour 2x2 duplication.
  This establishes real full-resolution sensor selection, not image-quality equivalence or app ZSL integration.
- Wide camera 4 gives nonzero 8192x6144 RAW (sample 65..1023), focal 4.03. Tele camera 5 gives
  nonzero 8160x6144 RAW (sample 62..788); native selector chooses DNGHWREMOSAIC index 18 at 24 fps.
  Tele 16320x12288 produces zeros, not 200 MP. Native 200 MP plus forceSensorMode=1 refuses configure (-38).
- For Pro 12 MP, use capture sensorMode=0, advance_fullsize=0, ai_highresolution=0, same dual-RAW
  configuration: 4096x3072 is nonzero and 8192x6144 is dummy. With capture sensorMode=1 the roles reverse.
  This explains the previous stock observation. reports/pd2454-sdk-pro-bin-{command,result}-20261014.txt
  (command file actually ends -command.txt). Do not treat the dummy output as an extra frame.
- DCG: SDK40 + UI3 + EnableSHDR=1 + EnableAutoHDR=0 + eis.config.enable=0 reaches native
  DCGHDR30_LOG, mode 12, 4096x2632. PD2454 selector requires DCG_SENSORFUSION and RAW14.
  The provider then aborts during CamX/CHI resource acquisition; no DCG RAW was delivered.
  Crash: ChiFeature2RealTimeMCX::AcquireResource -> TriggerRecovery -> SignalSystemEvent -> tgkill.
  reports/pd2454-sdk-dcg-eis0-{command,result,selected-mode}-20261014.txt (command ends -command.txt),
  pd2454-dcg-provider-crash-20261014.txt. Do not repeat this unchanged failing configuration.
- ISZ: UI1 ROI request [3,5,0] gave empty RAW. Native and SDK-Pro forceSensorMode=3 did not establish
  2x FOV. Native comparisons need contemporaneous scenes; the scene changed during the engineering-package
  tests, so older reference correlations cannot establish their FOV.
- Real engineering-name experiment: standalone com.vivo.bsptest installed as UID 10466, ordinary CAMERA
  permission, genuine attribution. It produces CameraService/native AppType 7, which has no entry in this
  ModeSelector app_types table. It is NOT ENGINEER (5): get_usecase returned null and fell back to PHOTO.
  Fresh force1 versus force3 FOV correlation is 0.999893 at scale 1. Setting provider 1=5 was accepted but
  the full RAW probe still returned zeros. Alpha package already exists; it was not replaced or uninstalled.
  Standalone source/APKs are in evidence/vivo_hal_2026-10-13/standalone-bsptest, outside Git.

Files/tools: sensor_selector_observer.js, camera_static_raw_observer.js, vcf_config_origin_observer.js.
Older sensor-observer logs mislabel argument6/argument7 as ui/eis; those arguments are not decoded.
Current observer labels them explicitly. AppType argument4, returned scenario strings, selected index,
mode dimensions and mode-list entries are independently decoded from the native implementation.

SCAMERA diagnostic APK 27098 SHA256:
6ce80ba33e0982211804a534db94a7580e03923c140aeb0cffd4c04c0a7cc6e1.
It includes P125 and raw_buffer_usage/provider_sdk_after_open debug options. Later provider_app_type
and restoration edits are source-only in SCAMERA; standalone v2 includes the type option but predates
its restoration edit. These are diagnostic APKs, not final Actions artifacts.

Next implementation work: test the three-stream profile with PRIVATE/SurfaceTexture preview, adapt
CaptureController stream construction and SDK40 lifetime, avoid SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
(the device exposes no maximum-resolution map), route only the nonzero RAW, and verify real capture/processing.
The SDK Pro capture route has not demonstrated a live RAW ZSL ring. Do not force the Pixel full-resolution
path onto this device: its pixel-mode and RAW-ring assumptions differ. DCG needs a compatible CHI/VCF
stream graph; ISZ without remosaic and tele 200 MP remain open. Preserve existing source and do not push.


Final checks for this iteration: regression 24/24, source compileDebugJavaWithJavac succeeds after
provider_app_type and property-1 restoration edits, git diff --check passes. version.properties remains
27074. No firmware changes, no push. Read-only observers detached/stopped; provider restarted automatically
after the logged DCG abort. The diagnostic engineering package was tested as its own UID, not as UID spoof.

## SDK session and selector condition follow-up, 2026-10-14

- Added `capture/VivoSdkRawSession.java`: real VivoCameraManager reflection, SDK40/VCF33 readback,
  restoration on close and failed open, capture IDs and verified Pro RAW tags. It preserves Camera2
  exposure/ISO settings. Four lifecycle tests plus existing regressions pass: 28/28. It is exercised
  by the debug probe, NOT yet wired into production CaptureController.
- New standalone genuine package `com.scamera.probe`, UID10467, APK v7. No spoof or root code.
  Reusable builder: ../evidence/vivo_hal_2026-10-13/standalone-bsptest/build_probe.py --package
  com.scamera.probe --version 7. Existing SCAMERA and engineering apps were not replaced.
- PRIVATE preview (format34, usage256, 1440x1080), RAW8192x6144 usage0 and JPEG8192x6144 work
  with the new SDK helper. `pd2454-sdk-helper-private-result-20261014.txt`: nonzero RAW, sensorMode1,
  seamless [259,3,-1], ISO72, 10ms, successful provider-property restoration after close.
  The earlier UI1 experiment accidentally overwrote helper UI16 and crashed CHI; debug source now
  retains helper UI16 unless an explicit UI override was requested. Do not replay that UI1 profile.
- Current scene is dark. Both genuine snapcam and bsptest return empty50MP with automatic AE
  around ISO2300. Controlled manual ISO72/10ms selects DNGHWREMOSAIC13 and nonzero50MP; manual
  ISO2300/30ms also falls back to sensorMode0. This establishes a capture-setting-dependent gate,
  NOT its exact condition/threshold. Do not force ISO72 in production or equate it with vendor gain.
  Logs/commands: pd2454-sdk-manual-{lowiso,highiso,private}-*.txt.
- Adding the high RAW target to SDK repeating requests suppresses RAW delivery, including the
  subsequent still. PRIVATE preview/JPEG continue. This also fails at ISO72 with sensorMode1.
  pd2454-sdk-manual-private-lowiso-ring-*.txt. No full-resolution RAW ZSL ring demonstrated.
  The native full13 RAW session rejects configuration (-38), and SDK bypass/raw_ring delivers no RAW.
- DCG PRIVATE video-encoder usage65536 still selects DCGHDR30_LOG12 then aborts in CHI before
  preview. Logical camera2 + physical RAW3 + observed stock currentModeEx16384 also aborts before
  preview. See pd2454-sdk-dcg-{encoder,logical-main}-*.txt; do not repeat unchanged failed graphs.
  Stock video was read-only observed: logical2, UI3, currentModeEx16384, AutoHDR1/SHDR0,
  EnableDCGHDR1, echo1, streamsUsage [3,1,0,0], 4K60. DCGHDR60_1_4 is RAW10, not LOG RAW14.
  No recording was started. The observed Dolby UI reported off before/after the exploratory tap;
  resolution/fps and selected zoom reported unchanged. Logs: pd2454-stock-video-*.txt/xml.
- IMPORTANT CORRECTION: ModeSelector `Camera ID` is not a Camera2 ID. A read-only hook of
  get_value_cameraid returns INTERNAL0 for Camera2 camera3. get_value_uimode returns1;
  get_value_scenario returns0, and internal get_value_tag(vcf.parameter,seamlessMode,4) returns null
  even though the public result echoes [3,5,0]. The current main-camera branch needs SCENARIO6
  for ROIREMOSAIC. Treating the JSON Camera ID3/4 tuple branch as main/wide was wrong.
  pd2454-sdk-roi-selector-values-20261014.txt, selector_condition_observer.js and selector_values.txt.
  UI1 + echo1 + PAP/capture IDs + vendor zoom2, rawUsage7 or0 still returns dummy12MP and PHOTO1.
- Original installed SCAMERA diagnostic27098 predates provider restoration. Its recheck left SDK40
  enabled; it was explicitly reset via the ordinary probe, readback0. Do not assume force-stopping
  an old probe restores global provider properties. New helper/probe restore them on normal close.
- No Actions APK, push or firmware edits. Native observers are stopped. Preserve the SDK helper,
  tests, commands, logs and current dirty sources. Bright-scene request remains unanswered.

Next: trace how CamX fills find_best_sensor_mode input cameraScenario at +0x30 (type1/2), and why
the VCF selector metadata omits the external seamless tuple. Internal0 main needs scenario6; do not
blindly send the Camera ID3/4 tuple. Decode the capture-setting gate before promising FULLRES under
automatic AE. Production integration needs real RAW repeating delivery or an explicitly separate
non-ZSL capture feature; a single Pro RAW must not be presented as stock HDR/ZSL completion.

## Bright-scene serial RAW and graph gates, 2026-10-14

Owner confirmed the bright stationary scene and now directs autonomous work until rootless ISZ/DCG/FULLRES
are working. This section supersedes the earlier unanswered-scene note. Preserve all local work; no push.

- Genuine com.scamera.probe UID10467, standalone v15, no root/spoof code. Main camera3 automatic AE
  ISO250/16.7ms supplies nonzero8192x6144 RAW from mode13. Three SERIAL STILL requests with fresh SDK
  capture IDs supply three distinct RAW timestamps/hashes, plus JPEG; captureBurst(3) supplies only one.
  Evidence: reports/pd2454-bright-sdk-full-serial3-{command,result-20261014}.txt. This is future capture,
  not proof of stock HDR/ZSL. RAW repeating is still missing/unstable; production SDK wiring is not done.
- Native force7 SESSION-only RAW is not cropped: scale1/correlation0.993210 versus native reference.
  FOV artifacts are in evidence/vivo_hal_2026-10-13/bright-fov and reports/*roi7-fov*.json. Sending force7
  in requests crashes the native graph. SDK EnableInsensorZoom/CROPPED_RAW/zoom2 remains BIN1/dummyRAW.
- Stock Photo main uses SAT1 and global Vivo zoom3.0 = base1.5 * ROI2.0. Lux below380 passes detector;
  VAS returns [3,3,-1] and stock chooses ROI7. Both public roiRemosaic and seamlessMode are0 in this trace.
  SDK Vivo zoom3.0 alone does not activate it. Rule observer proves config1/scene0/UI1 -> modern0/legacy.
  Old observer label camType for scene+8 was wrong: it is subUI. The value-1 is not a missing sensor type.
  Per-camera Platform.getAppType(int) is separate from global AppType; StreamHandler discriminates SDK64.
- SESSION SHDR1/numHDRexposure2/EnableHDRDCGMode2 initializes actual main DCG3/14 and SensorFusion
  capabilities8192/type0. AutoHDR1/DCG3 initializes DCG4/10/caps8192/type1; PRIVATE preview works.
  RAW still/record requests abort RTMCX after semaphore timeout. Request num/DCG propagation fixes the
  second HDR configure cache to4/10, but snapshot Desired still has HDRExposureType0/DCGmode0/bits10.
  Same logical3/physical0 is used; no ID mismatch. Capture-only BitSwitchMode1 has not yielded RAW.
  These are enabled sensor/CHI states, not working rootless DCG RAW delivery.
- Public types are decoded: numHDRexposure/EnableHDRDCGMode and BitSwitchMode are int32;
  vivo.control.disableHDR is BYTE; ultra_highresolution is stock Integer session key. SnapshotHDRMode1
  requests MFHDR and does not by itself satisfy DCG SHDR gate. UI29+ultra flag is a 200MP candidate,
  but its stock VCF export is JPEG; a Camera2 RAW200 export has not been demonstrated.
- Existing ScamBurst rejects input above16MP; preserve that guard. RAW50MP can use existing Hybrid
  fallback and PostPipeline sharpening. Seven NORMAL future raws must not be labelled NICE/stock ZSL.
  A production serial driver must suppress intermediate onCaptureSequenceCompleted callbacks.
- Host regression28/28 and Java compilation pass; report pd2454-bright-sdk-regression2-20261014.txt.
  Observers are finite/read-only. No firmware modifications, push or final Actions artifact.

Current runnable diagnostic sources are in project/app/src/debug and evidence/.../standalone-bsptest.
Build standalone with build_probe.py --package com.scamera.probe --version15. run_saved_probe.py saves
last-run logs from a saved command. Recheck provider PID before EVERY injection; it restarts after aborts.
Use fully quoted remote su command and verify observer READY before running a probe. SDK state restores
on normal close; force-stop/old27098 does not guarantee restoration. Rootless reset_sdk must readback0.
Finite observer sources and exact static findings are under evidence/.../client_identity/agent_* and tools.
