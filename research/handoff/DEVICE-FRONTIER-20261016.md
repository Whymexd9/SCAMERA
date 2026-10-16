LATEST 2026-10-16: read FULLRES-NATIVE-READY-20261016.md and CONTINUE-ISZ-FULLRES-20261016.txt first. Native FULLRES RAW/AE is proven on all rear lenses; main/UW50 JPEG proven; tele200 JPEG remains a LOW_MEMORY boundary with a separately tested source fix. Full app integration is delegated; DCG deferred. Older statuses below are historical.

CURRENT RESULT: read NATIVE-ISZ-READY-20261016.md first. Actual SCAMERA/SHDR/JPEG
captures now prove rootless native Main2x, UW2x, Tele2x and Tele4x with exact4:3.
30826 also has the working developed-RAW viewfinder.60 isolated Java tests pass.
Native FULLRES and DCG RAW remain unresolved. No commit/push/Actions by us.
The paragraphs below are preserved historical research, including superseded
"not integrated/no JPEG proof" statements. They are not the latest readiness status.

CURRENT OWNER PRIORITY, 2026-10-16: finish ISZ and native FULLRES first; do not start new DCG work.
The updated shared HANDOFF.md (2026-10-16 11:17) and PLAN P145-P155 were reread.
Other AI committed/pushed its own changes; we did not commit/push or reset anything.
Our CTS integration snapshot includes HEAD217e6da1 plus the dirty source; the primary APK30820 is preserved.
Read agent_native_isz_integration_20261016.txt in evidence/client_identity before older instructions below.

# Device research frontier, 2026-10-16

## NEW: actual native Quad/Tetra via real CTS applicationId

Read client_identity/agent_cts_native_raw_breakthrough_20261016.txt FIRST.
Own android.camera.cts UID10469 + ONE RAW_SENSOR output + continuous force
in SESSION AND REQUEST works without root, observers, VIF or SDK during capture.
Tele5 force5 is genuine Quad/block2 in25/25 patches; force7 is Tetra/block4
in25/25. Main3 force3 and UW4 force3 are also Quad/block2 in25/25 each.
Each delivered32 unique nonzero RAWs; first payload has matching final-result
timestamp and phone/local SHA. Payloads/manifests remain in
reports/pd2454-engineer-native-raw-20261016/cts-*.
Measured tele FOV on the owner's new detailed scene:2.000x/NCC0.97034 and
approximately4x/NCC0.98789. Earlier flat-scene fits are ambiguous and retired.
New paired main/UW RAWs corroborate centered approximately2x after exposure-robust
rank/log matching; all transforms, including failed linear fits, are retained. Their descriptors have
no genuine native4x mode: do not label a software crop as native4x.

This is a real separately installed package/ordinary UID, not attribution
spoofing under the old UID and not a competing provider1 setter. Existing
SCAMERA30820 and com.scamera.probe45 are preserved. Source-only CTS/raw-only CaptureController integration is now present; no saved
production SHDR JPEG proof yet. Native FULLRES/DCG still unresolved. Usage3 rejects
custom RAW dimensions in createConfiguredSurface639; explicit usage0 passes that
server gate and fails HAL endConfigure=-38.
CTS teleDCG23 with standard3072 container configured but hit the same
HWL f8e08 null+18 stats failure. SDK bootstrap -1/1 restored before configure
has not bypassed the full-size gate. The debug-only endConfigure gate did not help: createStream fails earlier with
usage3. Do not promote these diagnostic SDK/reflection hooks to production.
Do not claim native full resolution, DCG or finished SCAMERA from the ISZ proof.

Read FULLRES-NATIVE-MOSAIC-20261015.md first; its current scope supersedes the
older hardware-remosaic fallback acceptance. No push, commit or Actions by us.
Preserve the other AI's concurrent source/commits. Parent owns all phone access.

## Reconnected follow-up: own UID can configure ENGINEER and receive BIN RAW

ADB is online again; owner reconnected it. Main remains30820. Read
client_identity/agent_engineer_own_uid_raw_followup_20261016.txt first.
The previous36-submission failure is no longer the latest admission result.
Probe40 with four guarded passive hooks observed real native SDK/classifier
interleaving, same-session VCF5/isEng1 AND VCH5; configuration succeeded
under own package/opPackage/UID10467 with SDK40=0 and no attribution spoof.
This is timing-dependent and the observer changes timing; no production fix.
Uninstrumented configure succeeded too. Probe41 RAW phase then delivered31
unique nonzero Camera2 RAW4080x3072 with matching final-result timestamps,
actual sensor mode2/white1023. First saved payload SHA256
76e878e442c764fa48cf9c6a4fba71a342dbcf0d152dafc8b39d79517432247b.
It proves baseline transport, not native ISZ/FULLRES/DCG.

Probe42/43 requests missed force5 because Alpha returned early; their31 RAWs
are actual mode2, Bayer/block1 in25/25 patches. Do NOT count them as ISZ tests.
Probe44 fixes force continuity BEFORE that return. Real uninstrumented force5
now gives no RAW and MCTFE93359c/system-event/SIGABRT. Probe45 selector1 with
no force also gives no RAW and MCTFE. Tele DCG14/native2616 geometry and
Tele FULLRES50/native1/8160x6144 both failed configure before repeating.
These custom-size failures have no observed per-camera category or sensor mode.
Probe45 is installed and SHA verified:
2b29b9283c1eb0d702f7617eed67a3b69a3f86d723856da5eb71112b7982218e.
123 original lifecycle/guard assertions +27 RAW-phase guards pass;
Android36 javac/D8/sign verification pass. Only diagnostic source changed.
Current native targets remain unfinished. No commit/push/Actions by us.

IMPORTANT instrumentation control, 05:15 on2026-10-16: the same Tele SDK50
control failed with the10-entry MCTFE/HWL observer but succeeded WITHOUT ANY
observer:581 preview frames, real RAW8160x6144/100270080B, VIFResult mode18,
UID10467, timestamp398620868259509, ISO841/10ms, black64/white1023.
Payload SHA e0484ccbaa2fc952106208f92fda58cd6d7d63405532214805113a7b94c708fb.
Files reports/pd2454-tele50-vif-native18-no-observer-* and
reports/pd2454-vif-raw-20261016/tele50-no-observer-control/manifest.json.
True function entries/fingerprints do NOT establish timing-neutral tracing.
Negative instrumented results require an uninstrumented confirmation.
Tele force20+ultra1 was then confirmed WITHOUT ANY observer:0 frames,
MCTFE system-event/SIGABRT, SDK restored. Its failure remains independently
established; exact inner error4 was observed only under instrumentation.
See reports/pd2454-tele50-vif-force20-ultra-no-observer-*.

Further uninstrumented confirmations at05:47/05:51:
Tele NoRemosaic mode5/2x with the exact existing native RAW topology still gave
0frames and MCTFE system-event/SIGABRT. Main DCG14 Pro/VIF UI16 without Log V2
also gave0frames/SIGABRT in a fresh app process; SDK restored. The first DCG
launch failed before open due to stale camera enumeration after provider death
and is explicitly NOT counted as a DCG test. Exact reports and boundaries:
agent_uninstrumented_isz_dcg_confirmation_20261016.txt in client_identity.

## Established production intermediate

Last verified SCAMERA30820 includes FULLRES SDK profiles for main3/UW4/tele5,
session-key continuity, variable FPS and automatic sensor values0. Main AE
responds, and20 distinct FULLRES RAWs were captured and all20 merged/saved at
8192x6144. This is the existing hardware-remosaic route, not native Quad/Tetra.
The later arena-owner lifetime fix is only in source, not installed30820.

## Actual ordinary-UID client bypass

Last verified standalone is probe40, package com.scamera.probe, UID10467.
APK SHA256 e526a17ac45bbfbc9c42f28d99a20906583e6fac1168e767dfc15fd444675916.
It bundles unchanged production spoof helpers and the exact30820 camera2native
JNI library; optional target is an intent extra, not a preference/system edit.
Generated VivoQcomModes is a standalone diagnostic shim, not production code.

Newest own-UID configure-only route did NOT establish admission: a bounded
public SDK setValue(1,5) worker during configure submitted36 calls, but VCF
cachedAppType2/isEng0, VCH was not reached, configure returned-38. No repeating
or STILL was submitted. Fresh PID/ordinal1/own attribution were logged; camera
closed/onClosed observed, saved0/-1/0 read back three times after worker stop.
One-way queue drain is explicitly unconfirmed. v39 had rejected legitimate
VCF default-1 before configure; v40 accepts only that ELF-proven negative state.
Host123 assertions/22 guards, Android36 javac/D8/sign verification and independent
review passed. Those checks do not prove device admission or native RAW.
See agent_engineer_own_uid_race_live_boundary_20261016.txt in client_identity
and reports/pd2454-engineer-own-uid-configure-race-v40-*.

Latest source audit separates identity from native output selection:
ENGINEER5 does not itself write noRemosaicCaptureMode or native CFA7/8.
The decoded native full/crop targets accept RAW10/RAW_SENSOR containers, but
their producer/marker wiring is still unresolved. XCFASnapshot/CropPreview
selection still comes from a static setting; no public operationMode/setter
was found. Tele SW0/CPP4 passes the generic type filter but does not by itself
prove pre-software-remosaic RAW export. Read the exact functions/offsets in
agent_engineer_xcfa_three_stream_boundary_20261016.txt. The dormant Alpha custom
session32769 has no established connection to XCFA; do not try it by guessing.

No app callback rendezvous was found between the final CameraService classifier
and HAL refresh in the decoded AIDL output route: local stream getters and
weak-listener storage do not call the application. Source order/offsets:
agent_provider1_post_classification_callback_boundary_20261016.txt.
Both providers'20 static camera records were checked:0/2/9 are logical groups,
8 is the external-lens tele profile with the same sensor-mode/RAW/DCG capability
lists as5,6/7 are GC1 PreISP/Nova rather than native RAW aliases of main/tele.
No new alias probe is justified by that inventory. Exact map/line references:
agent_pd2454_camera_alias_native_raw_boundary_20261016.txt and adjacent JSON.

After the v40 cleanup, a separate PASSIVE root read of just three actual
provider globals confirmed gAppType0/mVCFAPPType-1/mUseVivocamera0 in PID11694;
the loaded ELF was SHA-checked. No injector, camera request or memory write was
used. reports/pd2454-post-probe40-provider-state-verified-20261016.json.
This is a later point-in-time state check, not a Binder queue-drain proof.
Active camera clients[] and no injector were also checked after the run.

Per-camera category cache audit found only three resolved refresh callers:
HAL CameraDevice.open, configureStreams_Impl and its internal physical-ID loop.
The physical loop has no separate request/session-update entry. Ordinary
processCaptureRequest/isReconfigurationRequired/provider setters do not update
the map in the decoded chain. Exact inventory/provenance corrections:
agent_apptype_cache_callers_boundary_20261016.txt.

The newly pulled VIF implementation libraries have no hidden refresh in
open/initialize/callback registration. SDK ReprocessSession is not an available
bypass on this firmware: its hardcoded /vendor/lib64/libvcf_reprocess.so is
missing (stat ENOENT, reports/pd2454-vcf-reprocess-missing-20261016.json).
The decoded loader retains a nonnull but empty function table on dlopen failure,
and a subsequent constructor can call its null entry. No reprocess session was
launched. Fresh proprietary libraries stay only in evidence/x200u_PD2454;
their pull manifests record exact SHA256 and they must not be committed.

Newest source work: agent_cfa80_ingress_boundary_20261016.txt records the actual
camera3/ChiStream+80 reset and graph/pool pointer chain.20 exact instruction
checks passed. Public usage/dataspace/streamUseCase handling inspected here
updates intent+90, not native markers7/8. InitializeOverrideSession has an
EARLY GetMatchingUsecase before the reset and another afterwards; do not claim
the reset precedes all matching. A later native marker generator/public input
remains unresolved. Literal store scans are only candidate searches, not a
proof of no writer anywhere. No new live camera candidate follows from them.

Provider main explicitly configures HIDL and NDK pools with count6; the earlier
single-thread explanation is not supported. Runtime occupancy/interleaving was
not measured, and later getInfo5 cannot timestamp the36 server-side stores.
No public HIDL/AIDL endpoint selector was found. Exact source boundary:
agent_provider_threadpool_endpoint_boundary_20261016.txt.

CURRENT CONNECTION: ADB serial10AFAQ1QE60071E is offline; reconnect question
is pending. Do not reuse old provider PIDs or claim the current installed main
APK was verified. The concurrent other AI advanced HEAD to a9458172 (P151)
with additional dirty files; preserve those. No commit/push/Actions by us.

--es diagnostic_spoof_package com.vivo.alphacamera reached both:

- VCF camera3 stateAfter:appTypeForCamera5,engineer0,isEngMode1.
- VCH camera3 configAfter:appType5,engineer0.

Evidence:reports/pd2454-alpha-identity-baseline-{result,observed,crash}-20261016.txt.
Existing typed attribution works without root for this admission test. However,
PRIVATE-only/force0 then crashed the provider on its first capture request.
libvcf_camera_aidl_device.so+5cae4 dereferences a missing stream buffer-cache
bucket. This is not a working ENGINEER capture implementation or a signature
bypass requirement. Do not retry the same incomplete engineer0 graph blindly.
VivoCamera alpha installed on the phone is a privileged system app; its own
success would not establish ordinary app access. Do not overwrite/uninstall it.

## DCG: processed preview fixed, RAW still missing

Genuine SESSION Integer vivo.control.session.uiSwitch_state bit32 selects stock
Log V2 processing. Debug extra --ez log_v2 true applies it before native_raw's
early return. Main3/native12 gave552 PRIVATE frames and completed results;
the same probe30 without the flag gave0. UID10467, no spoof target, no SDK/root.
Session/cache/physical/URO retain DCG3/14bit,count2. Main count1 clears DCG3/14
under this firmware policy; do not use it as a universal fused-output fix.

Reports/pd2454-dcg14-logv2-previewonly-* and
pd2454-dcg14-probe30-nolog-control-* are the controlled evidence. Entry-only
MCTFE/CSL observations also establish real submits and successful completion
fences for the processed preview. Bit32 changes several graph/format/tuning
consumers; no single internal flag is claimed as the sole cause.

RAW boundaries observed with the same Log32 contract:

- RAW_SENSOR4096x3072/usage0 repeating ring:configured,PRIVATE0/RAW0.
- Same with public RAW streamUseCase VIDEO_RECORD3:configured,PRIVATE0/RAW0.
- Native video-RAW role12 plus gralloc65539,4096x3072:configure-38 inside CHI.
- Native mode's exact4096x2632, video-RAW role12:configure-38 before CHI init.
- Exact4096x2632,usage0/publicuseCase3:also configure-38.

Reports/pd2454-dcg14-logv2-* contain exact commands/results/observers. Do not
infer RAW transport from a selected mode, timestamps, preview or white1023.
No DCG RAW or completed SHDR DCG photo has been established.

Cold-provider checks do not establish sustained native preview: main12, UW10
and tele23 each delivered only3 PRIVATE frames/20s, even after clearing only
the idle diagnostic process. Tele correctly retained internalDCG2/14, whereas
main/UW retained3/14. Do not infer a universal count1 workaround.

The SDK-client-only UI3/SESSION32/request32 ring reached actual VCF/VCH AppType1
and577 PRIVATE frames, but0 RAW. Its VCH downstream config had only1 stream
despite2 app outputs: the SDK owns RAW delivery separately. OnDestroy restored
SDK state23s after stop; the supplemental postlude records that restoration.
Never force-stop/install over an active SDK diagnostic before restoration.

Two actual SDK STILL attempts failed at the same missing RDI output port:
libvcf_session.so classifyOLBuilder+856 (16f718), fault0x80. The first used
UI3 and diagnostic stock_pro_capture, which also set PAP on repeating requests.
Probe33 adds optional pap_capture_only to isolate that diagnostic mistake.
The second used production VivoSdkRawSession.apply (PAP only STILL), full
ProUI16/currentModeEx256/pro1/echo1, fullsize0, sensorMode0, and continuity of
127 actual preview session keys. It still failed on first STILL, preview1.
These are the serial-still and sdk-pro-logv2-still reports, not RAW successes.

A guarded GetFormat/InitializeRawFormatParams observer established real
internal4096x2632 packed14, stride7168 (format324 -> internal28). A distinct
RTMCX OnInitializeStream observer found LongRaw10, ShortRaw17 and RAW3,
all native4096x2632/BPP14. SDK used internalformat324; ordinary native RAW ring
used32. Every observed binding returned0, but PreviewRAW43 was absent in both.
None of this establishes external app RAW delivery: both yielded0 RAW.
The actual ordinary-UID callback API and fresh matching VIF capture.id contract
are now verified separately; see agent_dcg_sdk_still_vif_contract_20261016.txt.

## VIF transport: real ordinary-UID RAW, not DCG/native mosaic yet

VivoCameraManager.open/initialize plus a fresh Long vivo.capability.capture.id
matching globalCaptureId enables real onBufferCallback ParcelFileDescriptor
delivery. android.hardware.VIFResult is an actual CaptureResult with public
getCaptureId; final JSON reads real timestamps/white/black/ISO/mode keys.
Probe35 saves buffer and result JSON independently, correlated bycaptureId.

Main SDK12 control delivered3 nonempty RAW32 4096x3072/stride8192/25165824B,
3 different captureIds,3 timestamps and3 hashes, plus JPEG33. Header [3,1,0,0]
matches its actual PRIVATE+RAW+JPEG outputs. Payloads and metadata are copied
in reports/pd2454-vif-raw-20261016/serial3, verified by save_vif_probe_evidence.py.
Earlier single-frame baseline had min61/max1023, all12582912 samples nonzero,
SHA434cb716e8b1b48910f7d8a11f457dd0c086e2605f7a4f6f089fe940124b2fc8.
ImageReader RAW stayedempty because VIF owns delivery; this is not RAW ZSL.

DCG Pro STILL with correct3roles, freshVIFcaptureId and actual callback open
gave181 previewframes then the SAME classifyOLBuilder16f718 NULL RDI failure.
Fresh capture.id/role count solves neither the missing producer nor native14
transport by itself. Evidence: dcg14-sdk-pro-vif-three-stream reports.

Tele SDK50 VIF control delivered3 nonempty8160x6144 RAW32/stride16320/100270080B,
actual mode18,3 unique IDs/timestamps/hashes, real metadata black64/white1023,
ISO263/10ms. Payloads/JSON/manifest: reports/pd2454-vif-raw-20261016/tele50-baseline.
Changing only sticky forceSensorMode20 (SW0/CPP4 in descriptor) configured,
but preview0/RAW0; firstSTILL caused platform OL error/abort and secondary
cameraserver crash during VivoDevice re-registration. Provider/cameraserver
recovered automatically and SDK restoration was logged. Internal full RAW32
8160x6144/BPP10 binding returned0; it is NOT delivered native Quad proof.
Do not repeat force20 as a claimed rootless native-remosaic implementation.

The source-backed ultra_highresolution1 correction has also been tested,
not left as an untried next step. It is genuine Integer/INT32 count1 and can
change CHI's earlier GetRemosaicTypeFromSensor through ExtensionModule+a034.
Adding only this tag to the same Tele ProUI16/force20 profile still produced
0 preview/RAW/JPEG. The guarded early GetType/UpdateNZSL observer was armed
but neither function was called; no live SW0/CPP4 graph is established.
MCTFE ExecuteProcessRequest93359c triggered SignalSystemEvent/SIGABRT, then
SDK restoration and automatic provider recovery. The backtrace does not
encode the specific failing SequenceOne return. See tele50-vif-force20-ultra
reports and agent_tele_sw0_ultra_live_boundary_20261016.txt. No mode was enabled
in production from this failed diagnostic.

## Latest DCG graph and Alpha permission boundaries

The new NULL-RDI observer identified the exact DCG STILL failure: Log32 takes
scene810 with realtime preview/video only. CaptureDecisionRule asks node18/20
Wide_Dummy for usecase1; both vectors are empty, nodeBufInfo creates type3 with
NULL StreamInfo, CopyNode propagates it, and classifyOLBuilder dereferences it.
Wide_Dummy is the normal camera-type prefix; renaming streams is not a fix.

Without Log32, ProUI16 restores real node18/20 and all10 observed producers
had nonnull StreamInfo. It still gave preview0/RAW0 and a later platform OL
request4 abort. VCF selector UI16 chooses PHOTO geometry4096x3072, whereas CHI
forced12 is videoDCG14/4096x2632. This is an independently demonstrated contract
mismatch; the precise first failing backend component remains open.

The subsequent source audit did not establish a public RAW-size override:
SnapshotJpegStreamMap is [W,H,physicalCameraId], snapshotYuvStreamMap is
[W,H,streamIndex]. Their aspect-size branches do not admit the observed
RAW usage1a008a20. sensorSizeList is written from selector results into
internal metadata; its app-side input reader/registered key was not found.
availableStreamMap matches existing CHI descriptors and does not rewrite
VCF RAW W/H. Do not treat CameraCharacteristics SensorSize as a request setter.
See agent_dcg_pro_internal_dimensions_boundary_20261016.txt.

P01054 is an actual public ImageFormat and advertised physical-camera3 output
1440x1080, STANDARD1. VCF/CamX explicitly support it. ProUI16+P010 without Log
still produced preview0/RAW0 and the same platform OL abort. UI3+SHDR1/AutoHDR0
with P010 and no Log produced177 real previewframes, but the present Pro/PAP
STILL(usecase1) again queried empty capture-node18/20 and failed.
Do not report a completed DCG capture from these preview results.

Alpha probe36 now builds the actual regular0 PRIVATE1440x1080/max2 +
YUV35 1440x1080/max2 + RAW32 4080x3072/max5 graph, real static Tele3P5x strings,
engineer0 and preview-intent1, with optional source-verified remosaic1/3.
Its baseline configured and seeded both stream and buffer caches with ids0/1/2.
Start-data-delivery permission then failed(-1), causing onError4/flush/close;
the subsequent NULL FMQ read is a secondary shutdown race. Do not call the
disappearing bufferCache or FMQ initialization the established first cause.
Engineer1 was not run over this invalid baseline.

The actual CameraService classifier uses full-length+memcmp; only the exact
installed com.vivo.alphacamera belongs to ENGINEER5. A suffix package does not
inherit it. Public provider setValue(1,5) really writes global gAppType, but
Camera3Device reclassifies the saved package during configure before HAL's
per-camera refresh. onConfigured writes only the global after that refresh.
No stable rootless ENGINEER admission plus valid data delivery is established.

The permission return is now decoded as HARD_DENIED2 -> -1, rather than the
separate soft-deny1 -> -EACCES(-13). The exact failing internal permission
subcheck was not observed. Android16 connectDevice has one attribution and
no separate HAL clientName. A forged Alpha head with next attribution cannot
become trusted without package/UID validation; a real own-package head keeps
the own-package classifier. See agent_alpha_rootless_attribution_boundary and
agent_own_package_engineer_admission_boundary_20261016.txt. This is a boundary
of the inspected routes, not a universal impossibility proof.

Existing ordinary diagnostic com.vivo.bsptest/version3 has CAMERA granted,
but AppType7 has no demonstrated advantage over2 for native DCG geometry,
private/CTS admission, stream conversion or pure bypass. SDK40 promotes it
to1. No redundant BSP install/test was performed. See the two agent_bsp notes.

Probe36 first javac failed on hidden CaptureRequest.getTargets; the correction
uses public Builder.setTag/Request.getTag for Alpha RAW targets. The retry
compiled, signed, verified and installed. Failure and retry logs are retained.
Source changes remain debug-only for these new experimental paths.

## VideoSnapshot: JPEG succeeds, external Bayer RAW still has no producer

Probe37 uses actual stock VideoSnapshot template4/RECORD3/final intent2, with
no Pro/PAP/currentModeEx256. It yielded177 preview frames, then the same NULL
capture-RDI fault. The new observer decoded actual scene0x1300/UI3 as
SCENE_SAT_VIDEO_NORTYP, not scene300(BOKEH) or scene810(Log2). Native usecase9
was traced to MFNR auxiliary writers and is not a public VSS mode.

Probe38 opt-in video_encoder creates PRIVATE34/1920x1080/usage65536 and targets
it in both repeating and capture. Correct four-output wire header is
[4,1,0,0,6];6 means RECORDING, mapped to final external5 VIDEO_YUV_OUT.
FeatureHandler scans class2 outputs of THIS request and requires final5/12
plus isSupportVssScene. Its this+328 shared_ptr is FeatureControlInfo, not a
recording counter; no invented recording-start tag is needed.

Main3/UI3/P01054/noLog/force12 request:433 preview and434 encoder frames,
JPEG delivered2891621B/timestamp396173998199588. RAWStream1 produced0; the same
frame177 stayed in flight with metadata=false/buffersleft1. There were no
NULL-RDI observer calls or old classifyOLBuilder fault. CHI RecoveryThread
aborted later; SDK restored and cameras closed. No VIF RAW/final result.
Exact selected scene/mode/BPP was not observed in this run; requested mode12
is not sufficient proof of actual DCG delivery.

VSS native getVssSourceStreamId returns REAL_YUV_VIDEO_VSS_OUT or
REAL_Y2Y_VIDEO_VSS_OUT, and appendVSSBuffer fallback accepts external5. The
declared VSS graph is YUV -> JPEG; external RAW32 got no producer. Role12's
other XML uses IC portrait/Y2Y outputs, so the word RAW in its name does not
prove sensor Bayer. Do not pass P010/YUV into SHDR as RAW. Further repeating
this VSS experiment is not an established path to the requested RAW14 goal.
See reports/pd2454-dcg14-video-{snapshot,encoder}-vif-p010-* and
agent_video_encoder_vif_live_boundary_20261016.txt for exact evidence.

Stock stream roles are RAW_LONG7,RAW_SHORT8,RAW_VIDEO12. streamsUsage is an
INT32 array with count header:two outputs use{2,1,12}. Role12 additionally
requires AppStream usage bit16; usage0 maps RAW to external role7. Native
probe31 explicitly applies optional raw_usage in session and requests; older
native probes ignored it. Public streamUseCase and VCF role are different APIs.

## Native FULLRES/ISZ graph gate

noRemosaicCaptureMode is static/default0=Disabled; guarded live initialization
also reads0. Values1/2 select CropPreview/XCFASnapshot. Exact source chain and
property override name are in agent_noremo_native_graph_boundary_20261016.txt.
No ordinary app setter was found; no property/firmware/HAL writes were made.
Generic PZSL/NZSL excludes sensor remosaicType2. Genuine XCFA override is absent
from the server session whitelist and was missing at its actual CHI lookup.

Incoming Camera3 CFA0 does not rule out internal generated CFA7/8 streams.
The internal producer/compatible external native RAW contract remains open.
Main/UW do not declare a genuine4x native ROI mode in the inspected arrays;
do not label an app crop as true4x ISZ. Tele has actual2x/4x native modes.

Use only ADB10AFAQ1QE60071E. Root is passive research only. Observers have
verified true-function entries, bounded reads and finite lifetimes. Never
reuse retired HWL interior hooks; never enable a production mode from metadata
alone. Do not change kHybMergeMain1 or silently relax RAW/white/memory guards.
