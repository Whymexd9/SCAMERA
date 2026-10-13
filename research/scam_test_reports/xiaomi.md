# xiaomi findings

## P1 Xiaomi 15 Ultra (25010PN30G, xuanyuan, SM8750) - HAL enables DXG (dual-gain) for a fixed package list: spoof com.google.android.youtube
- theme: gating/spoof
- confidence: verified
- evidence: /odm/etc/camera/cameracustomize.json (file): ApkFunctionType "ThirdDXGEnable" Mode 0 lines 26881-26890 = [com.xingin.xhs, com.ss.android.ugc.trill, com.ss.android.ugc.aweme, com.xiaomi.cameratest, com.zhiliaoapp.musically, com.instagram.android, com.google.android.youtube]; ThirdDXGDisable (activities) line 26904; HighFpsForLowLight lines 26948-27004 contains aweme/instagram/trill/xhs/musically but not youtube/cameratest. aispsensormodesettings.json lines 70810-70812: 'DXG-timing' = SensorModeList [37, 39, 137, 139]. camxoverridesettings.txt enableDCG14bitOfflinePreview=TRUE (27536), Enable3rdSeamlessStgCam=0x10001 (27523). HAL fills com.xiaomi.sessionparams.clientName (byte[], session/request key, tag 0x81db0001, descriptor line 96415) with the real package 'com.scam.test' in every template (line 3711) and in results (4694); xiaomi.thirdparty.isThirdParty int32 = 1 (result, line 4910). vendor.camera.aux.packagelist also lists com.xiaomi.cameratest (line 100742).
- recommendation: On xuanyuan, as a device default like the X7U spoof in DeviceDefaults: run the binder-level package spoof (capture/spoof/BinderCameraPackageSpoof) as com.google.android.youtube (fallback com.xiaomi.cameratest). Open logical camera 0 and send no current_mode. Verify in results: com.xiaomi.sessionparams.clientName = spoofed name; org.codeaurora.qcamera3.sensor_meta_data.current_mode (int32) = 37 (4:3) or 39 (16:9); sensor_mode_info word13 (bit depth) = 14; android.sensor.dynamicWhiteLevel. Sending clientName by itself has no effect, because the HAL overwrites it with the real package.
- risk: Inferred effect: DXG may be applied only to preview/video use cases, not to RAW stills. The RAW may switch to packed MIPI RAW14 behind RAW_SENSOR, as on the 17U in mode 9. The spoof is untested on HyperOS: cameraserver/AppOps may reject a package that does not match the UID. Do not use aweme or instagram (HighFpsForLowLight shortens night exposures). com.xiaomi.cameratest triggers the 'FactoryTest' function, whose behaviour is unknown.

## P1 Xiaomi 15 Ultra (25010PN30G) - Main-camera sensor mode table with DXG=37/39, DOL2=5/13, quad50M=8/16, remosaic=0; selectable via current_mode
- theme: DCG/HDR
- confidence: verified
- evidence: Characteristic org.quic.camera2.sensormode.info.SensorModeTable int32[125] (tag 0x800d0000, descriptor line 94855), camera 0 line 3066 / camera 2 line 9273: 41 modes, {w,h,fps}. 0=8192x6144@30, 1-7=4096x3072@30, 8=8192x6144@30, 9-15=4096x2304@30, 16=8192x4608@30, 17-27 and 28-32 = 4096x2560/1840 @30/@60, 33=7680x4320@30, 34-35=480 fps, 36=4080x2304@120, 37/38=4096x3072@30, 39/40=4096x2304@30. Meaning from aispsensormodesettings.json lines 70778-70830 (PhysicalId 1, rear wide): binning [1,9], quad12.5M ISZ [2,10], HW-remosaic ISZ [3,11], DOL2+binning [5,13], quad50M [8,16] (qcfaFactor 2), DXG-timing [37,39], LBMF+binning [6,14], LBMF+quad [7,15], HW remosaic [0] = 8192x6144. Result org.codeaurora.qcamera3.sensor_meta_data.current_mode int32 = 1 (line 4713; tag 0x80cb000a, descriptor 95438). sensor_mode_info byte[1968] (line 4715): word1 = mode 1, words5/6 = 4096x3072, word13 = 10 bits, word14 = 30 fps. dynamicSensorModeSwitch = 0 (4714). Physical request keys = null (lines 3628-3630). Session key org.codeaurora.qcamera3.sessionParameters.EnableHDRDCGMode is listed (line 3575). Request key com.qti.stats_control.DCGMode int32 = 0 in templates (line 3704).
- recommendation: Only with the owner's approval (the no-mode-switch rule exempts only the 17U tele). Test with the existing per-module tunable key: org.codeaurora.qcamera3.sensor_meta_data.current_mode (int32) = 37 on the main module, logical camera 0, RAW_SENSOR 4096x3072. Set it on the logical request in every request (physical keys are null). Check current_mode=37, sensor_mode_info word13=14, the dynamic white level and the RawPayloadCheck layout. Companion keys if the HAL ignores it (inferred): session EnableHDRDCGMode=1, request com.qti.stats_control.DCGMode=1, dynamicSensorModeSwitch=1, com.xiaomi.ext.control.policy.sensorMode (int32, 0xf0000006). Separately, log/decode SensorModeTable and sensor_mode_info once per camera open on every Qualcomm phone (no SCAMERA code reads them yet).
- risk: Mode switch (needs owner approval). Whether the HAL honours current_mode for isThirdParty=1 clients is inferred from the 17U precedent. The DXG RAW may be MIPI RAW14 (the stock config calls the wide's RAW 'MipiRaw14' in format 37, mivistreamformat.json 81971-81976). DXG min shutter is unknown. A request without the key keeps the last mode.

## P2 Xiaomi 15 Ultra (25010PN30G) - Stock third-party JpegR pipeline runs op mode 0x9002 with a 14-bit wide RAW: extend the P41b logical route to the main camera
- theme: DCG/HDR
- confidence: inferred
- evidence: ecoenginevendorstreamconfig.json (file): CameraUser 1 (SDK/third party) VendorOpMode 36866 (0x9002) Signature 'ThirdJpegR' with physical '// raw14 stream of wide' roleId 0 format 37, lines 74934-74978; front uses 0x9001 (line 75005). Enabled by persist.vendor.camera.sdk.third.jpegr.enable=1 (line 1577). mivistreamformat.json: RoleIdRearWide DefaultPhgStreamFormat 37 '// MipiRaw14' (lines 81971-81976). overridesettings.json supportOpModes lists 0, 0x9001 QCFALite, 0x9002 DualSAT, 0x9004 QCFAMFNR, 0x900B UltraRaw, 0x900C ManualHdUltraRaw, 0x900D ManualRaw, 0x900E ManualHdRaw (around lines 84330-84345).
- recommendation: Behind a dev switch, let XiaomiTeleZoom.logicalRoute / the xiaomi_opmode session (currently 17 Ultra only, phone()) also cover xuanyuan's main: session on logical camera 0 with operation mode 0x9002 and the RAW reader on physical camera 2. Check whether the frames arrive as MIPI RAW14 (RawPayloadCheck already handles the 17U layouts). Next tries: 0x900D (ManualRaw) / 0x900E (ManualHdRaw).
- risk: On the 13 Ultra, sessions with stock keys stopped delivering frames. 0x9002 adds SAT latency and mihal MFNR (FeatureMask 1). The wide may still be in mode 1 (10-bit) for a RAW-only session. Owner approval may be needed if the HAL changes the sensor mode.

## P2 Xiaomi 15 Ultra (25010PN30G) - 50 MP RAW sizes exist only in xiaomi.scaler.availableRawStreamConfigurations
- theme: full-res
- confidence: inferred
- evidence: Characteristic xiaomi.scaler.availableRawStreamConfigurations int32[60] (tag 0x81be0004, descriptor line 96258), cameras 0/2/3/4 at lines 3141/9362/12381/15394: formats 37 (RAW10), 32 (RAW_SENSOR), 36 (RAW_PRIVATE) at 4096x3072, 4080x3072, 4080x2304, 8160x4608, 8160x6144. The public map has RAW_SENSOR 4096x3072 only (line 2843). MAXIMUM_RESOLUTION map = null. org.codeaurora.qcamera3.quadra_cfa.is_qcfa_sensor = 1, qcfa_dimension = {8192, 6144} (lines 3314-3317); its stream list has YUV/JPEG/PRIV only. Matching sensor modes: 0 (HW remosaic 8192x6144), 8/16 (quad 50M). camxoverridesettings: isHdRawSupported=true, isUltraRawSupported=true, UltraRawFeatureMask=0x347, xiaomiMaxRAWSizes=6 (lines 27613-27622, 27788).
- recommendation: Experiment (dev switch): add a RAW_SENSOR ImageReader of 8160x6144 to logical camera 0. Non-flexible ImageReader sizes are not rounded by cameraserver (GelioSoft 108 MP precedent on the 10T Pro). Pair it with current_mode 0 (Bayer, HW remosaic), or op mode 0x900E (ManualHdRaw). Mode 8 gives a QCFA mosaic that would need remosaic in the worker. Log configure errors, rowStride and white level.
- risk: Configure may fail (the HAL validates internally). It is a mode switch (owner approval). RAW frames would be about 4x larger, so the ZSL depth shrinks accordingly (58 at 12.5 MP).

## P2 Xiaomi 15 Ultra (25010PN30G) - Per-frame gyro samples, tripod and motion flags in results: add them to HalMotion
- theme: OIS/gyro
- confidence: verified
- evidence: Result com.xiaomi.ThridPartyGyroState.gyroData byte[4024] (tag 0x822c0000, descriptor line 96627) in every live result of cameras 0/2/3 (lines 4674, 5335, 10847, 13864). Decoded layout: int32 count at 0 (=13 per 33 ms frame), float x[200] at byte 24, y[200] at 824, z[200] at 1624, int64 ts[200] at 2424 (zeros); values -0.9..0.35. Result xiaomi.thermal.IsOnTripod int32 = 0 (line 4905; descriptor 96183). Result xiaomi.ai.asd.isMotion int32 = 0 (line 4823). HalMotion.java reads only OPPO/vivo/QTI tags.
- recommendation: HalMotion.shake: parse gyroData as the RMS of sqrt(x²+y²+z²) over 'count' samples, used to rank frames within a burst. HalMotion.moving: OR in xiaomi.ai.asd.isMotion. Use IsOnTripod == 1 in the long-exposure planner (HybridPlan / ExposureRanges) to allow longer shutters.
- risk: Units and axes are undocumented (rad/s inferred). Timestamps are zero, so samples cannot be aligned to the rolling shutter; only relative ranking within a burst is safe. No such tag on the 13T, RN11P or 10TP.

## P2 Xiaomi 15 Ultra (25010PN30G) - Hidden physical IDs 2-5 fail when opened directly: use logical 0 with physical streams
- theme: other
- confidence: verified
- evidence: Cameras 2 and 3 (not in getCameraIdList) stream preview and still RAW, then the HAL closes them with onError 4 at the next configure (lines 11862-11867, 14875-14880). Camera 4 live: 'CameraDevice was already closed' (line 16752), and the provider restarted (dumpsys events ~94550-94600). Cameras 5/6/7 then gone (16755-16769). Logical camera 0 passed: RAW maxImages 62, ZSL ring 58 at 30 fps, failing at 59 (lines 5876-5902). On this rooted phone persist.vendor.camera.privapp.list=[org.codeaurora.snapcam] (99303) and vendor.camera.aux.packagelist contains org.codeaurora.snapcam (100742), so SCAMERA likely sees the hidden IDs in its list.
- recommendation: On xuanyuan, never open IDs 2-5 by ID. Route the lenses through logical camera 0 with setPhysicalCameraId outputs (the P41b pattern). Cap the RAW ZSL ring at 58 frames of 4096x3072.
- risk: The failures may partly come from the SCAM TEST stress test (62-buffer configure), but the logical camera survived the same test. A provider crash takes all cameras down for about 3 s.

## P3 Xiaomi 15 Ultra (25010PN30G) - Real ISO and exposure ranges and per-mode minimum shutter
- theme: AE/ISO
- confidence: verified
- evidence: Characteristic xiaomi.sensor.info.sensitivityRange int32[2] and exposureTimeRange int64[2] (descriptor lines 96435-96436). Main camera 0/2: {50,12800} vs declared 50-3200; {11041, 33430445787} ns vs declared 1 s (lines 3253-3255, 9472-9474). UW camera 3: {50,12800}, up to 99.9 s (12489-12491). 3x tele camera 4: {50,6400} vs declared 50-1119, up to 31.6 s (15502-15504). smartae_hardware_config.json lines 53425-53507: wide BinningNormal minShutter 11042 ns, BiningDOL2 80771, HWRemosaic/ISZQUAD 29551; tele 22518; ultra tele 23960/26010/39769/39605, ISZQUAD_4X4 81543.
- recommendation: ExposureRanges.iso: read xiaomi.sensor.info.sensitivityRange explicitly as a vendor ISO source. Today it is only picked up by the name match in Camera2ApiAutoFix.ISO; vendorExposureMax already reads exposureTimeRange. Clamp the shortest manual or bracket shutter per sensor mode: at least 29.6 µs in remosaic/ISZ, 81 µs in DOL2.
- risk: An ISO above the analog maximum is digital gain. Long exposures past the declared 1 s still need the Qualcomm FPS-limit keys (P98).

## P3 Xiaomi 15 Ultra (25010PN30G) - Xiaomi/Qualcomm ADRC gain keys for ScamScene
- theme: AE/ISO
- confidence: verified
- evidence: Results (camera 0 preview): com.xiaomi.mivi2.adrcGain float = 1.0012335 (line 4683); com.qti.chi.statsaec.previewDRCGain float (4651); org.quic.camera2.statsconfigs.AECCompenADRCGain float (4778); com.qti.chi.statsaec.AecLux = 228.96 (4644), already read by ScamScene. ScamScene.java reads ADRC only from vivo.feedback.AdrcGain.
- recommendation: In ScamScene (and ScamAe), add com.xiaomi.mivi2.adrcGain with fallback com.qti.chi.statsaec.previewDRCGain as the ADRC source when the vivo key is absent. This applies to Xiaomi Qualcomm phones.
- risk: The values are preview-stream ADRC; check that they match the RAW frame's AE before using them in the NICE/SCAM tone curve.

## P3 Xiaomi 13T (2306EPN60G, MT6896) - No DCG, stagger, scenario or full-res RAW path: do not port Dcg14Mode/DcgSearch
- theme: DCG/HDR
- confidence: verified
- evidence: Camera 0 (and identically cameras 1-7): com.mediatek.hdrfeature.availableHdrModesPhoto {0,3,1}, availableMStreamHdrModes {0,6}, availableStaggerHdrModes {0} (lines 617-620). No availableVhdrModes (vivo DCG used 128) and no com.mediatek.seamlessfeature.* keys. com.xiaomi.camera.supportedfeatures.insensorzoom = 0 (664). com.xiaomi.scaler.availableSuperResolutionStreamConfigurations: 8192x6144 only as YUV/JPEG/PRIV; RAW 4000x3000/4096x3072 (701). No scenario/sensormode text files, only tuning .so files (imx707/ov50d/ov13b10/imx596). Hidden IDs 2 (2x tele OV50D, 7.1 mm), 3 (UW), 7 (main) open by ID with RAW at 30 fps (A. SUMMARY lines 63-110). Session buffer cap 52; ZSL ring 32, memory-bound (2594-2619). Default templates have OIS OFF (1799-2125); SCAMERA already forces it on.
- recommendation: Keep the 13T on the plain binned RAW path: no DCG module and no mode keys. The hidden tele (camera 2) can be opened directly. Keep the ZSL ring at 32 frames or fewer. Real analog gain for logs: com.mediatek.3afeature.aeSensorGain (1024 = 1x) / aeIspGain (4096 = 1x) (lines 2237-2239).
- risk: The MStream HDR 6 capability is listed on every camera, which suggests a platform default rather than real sensor support. Not worth testing.

## P4 Xiaomi 15 Ultra (25010PN30G) - In-sensor zoom for third-party apps is limited to the wide; periscope mode table missing
- theme: ISZ
- confidence: verified
- evidence: camxoverridesettings.txt: ISZEnableCameraRole=0xa (27405), ISZ3PartEnableCameraRole=0x02 (27457), ISZ3PartThresholdInterval=1:360:420|1:360:420 (27458), supportISZFor3rd=FALSE (27872), ISZSupportedCase=0x3F (27410). Session key org.codeaurora.qcamera3.sessionParameters.EnableInsensorZoom listed (3577). aisp PhysicalId 5 (periscope, lines 70873-70900): binning [2,13], quad12.5M ISZ [4,6,15,17], quad50M [11,22], LBMF+quad [12,23], DOL2 [8,19]; smartae ISZQUAD_4X4 for the ultra tele (53506). Camera 5's characteristics vanished after the provider restart (16755).
- recommendation: No action under the current rule (forced ISZ only on the 17U). If the owner extends it to the 15 Ultra, first re-dump camera 5 through logical camera 0 for its SensorModeTable. Candidate modes: current_mode 4 (2x ISZ 4:3) / 15 (16:9).
- risk: Mode indices for the periscope come from the aisp map only (not verified against its table). Mode switch rule.

## P4 Redmi Note 11 Pro (2201116TG, MT6781) - 108 MP only as HAL-remosaic JPEG/YUV; holding RAW buffers stalls the HAL
- theme: full-res
- confidence: verified
- evidence: Hidden NDK tag 0x90010019 int32[24] = {33,12000,9000,0, 34,12000,9000,0, 35,12000,9000,0, ...} (line 2550). No RAW above 4000x3000 (com.xiaomi.scaler.availableStreamConfigurations, line 2500). Third-party ISP tuning libs libCamera_s5khm2sdmain*_3rd_Capture/Preview/Video.so (8143-8202). RAW hold test: 'held 1 frames at once (stalled)' (3653), then the device closed with onError 4 (3660). Front and hidden IDs: 'disabled by policy' (4415-7488).
- recommendation: No 108 MP RAW route; leave it. Keep ZslRing's copy-out-and-release design (never hold camera Images) on viva. Ask the owner for a re-dump with the app in the foreground to see the hidden cameras.
- risk: 'Disabled by policy' was probably a background restriction after the probe crash (inferred). The hidden cameras are untested.

## P5 Mi 10T Pro (M2007J3SG, SM8250, PixelOS) - Mode table and aux list recorded for the postponed 108 MP work
- theme: full-res
- confidence: verified
- evidence: SensorModeTable (tag 0x80300000, line 471): 10 modes 12032x9024@10, 6016x4512@30, 5792x4344@30, 3008x2256@30, 4864x2736@60, 1920x1080@120, 5792x3256@30, 5792x2896@30, 5760x4320@20, 1600x1216@30. qcfa_dimension {12032, 9024} (468). current_mode = tag 0x800e0009 (descriptor 13812). vendor.camera.aux.packagelist = [org.codeaurora.snapcam, com.android.camera, org.lineageos.aperture, ...] (9050). Hidden logical camera 4 fails configure with 'Broken pipe (-32)' (errors section, line 18528).
- recommendation: No action (owner postponed the 10T Pro). When resumed: RAW_SENSOR 12032x9024 ImageReader plus current_mode 0. SCAMERA's package is already in the aux list on this ROM.
- risk: 10 fps full-res mode; memory for a ~217 MB-per-frame RAW16 burst.

## notes
I found four ways to get more out of the Xiaomi 15 Ultra, but none is tested on the phone yet. They are all read from config files and dump values; whether the HAL actually does it must be checked on the device. Full report: C:\Users\MECHREVO\AppData\Local\Temp\claude\C--Users-MECHREVO-Downloads-x200u\9ac031cc-caab-40dc-bb85-1504e57b4cb6\scratchpad\reports\xiaomi.md. All four reports are in C:\Users\MECHREVO\Desktop\т\DUMP SCAM\. Read-only work: no source edits, no gradle, no commits.

Xiaomi 15 Ultra (25010PN30G, rooted):
- Main-camera sensor modes are now known, with their meaning. Mode 37 (4:3) / 39 (16:9) is DCG ("DXG-timing"), 5/13 is two-exposure stagger, 8/16 is 50 MP quad, 0 is 50 MP Bayer after hardware remosaic, 1/9 is the normal binned mode used today (10-bit, 30 fps). Source: the sensor mode table in the characteristics plus the HAL's aispsensormodesettings.json.
- The HAL turns DCG on for a fixed list of app packages (cameracustomize.json "ThirdDXGEnable"). com.google.android.youtube is on that list with no side effects; com.xiaomi.cameratest is the second choice. The HAL fills clientName with the real package and sets isThirdParty=1, so only the binder-level package spoof can change it. Since spoofing is now allowed, this is the cleanest test (F2): it does not switch sensor modes ourselves.
- Forcing current_mode 37 (F1), 50 MP RAW at 8160x6144 (F4) and the 0x9002 logical route (F3) all switch the sensor mode. That breaks the no-mode-switch rule (only the 17 Ultra tele is exempt), so they need the owner's approval first.
- Opening the hidden physical cameras 2-4 directly is unstable: the HAL dropped them and the camera provider restarted. Use logical camera 0 with physical streams instead. Logical camera 0 handled a ZSL ring of 58 RAW frames.
- On this phone, root-set properties put org.codeaurora.snapcam (SCAMERA's package) in vendor.camera.aux.packagelist and persist.vendor.camera.privapp.list.

Other Xiaomi phones:
- Xiaomi 13T (MediaTek): no DCG, stagger, scenario or full-res RAW anywhere. Don't port Dcg14Mode / DcgSearch to it. Its default templates have OIS off, but SCAMERA already forces OIS on.
- Redmi Note 11 Pro: 108 MP only as a JPEG/YUV picture, no RAW. Holding RAW buffers stalls the HAL, so the current copy-out ZSL ring is needed there.
- Mi 10T Pro: postponed by the owner; its mode table is in the report for reference.

Minor items for the 15 Ultra:
- The ultra-wide declares white level 1024 but its samples max out at 1023; set its module white-level override to 1023.
- Default templates set enableMFNR=1.
- persist.vendor.camera.3rdhighResolutionBlob.scenes=false on all three HyperOS/MIUI phones.

Re-dumps needed:
- 15 Ultra camera 5 (200 MP periscope) through logical camera 0; it vanished after the provider restart, so its mode table is missing.
- Redmi Note 11 Pro with the app in the foreground; its front and hidden cameras answered "disabled by policy".