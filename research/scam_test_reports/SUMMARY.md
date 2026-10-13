SUMMARY.md was not written: the harness blocked the file write for this subagent. The full text is below, so the calling script can save it.

# SCAM TEST dumps: critic pass and SCAMERA action plan (2026-10-13)

Inputs: 5 group findings (vivo_mtk, vivo_qcom, oppo, xiaomi, pixel_samsung_moto; none missing). I also cross-checked the owner's attached log `C:\Users\MECHREVO\Desktop\log-2026-10-13 (1).txt` (LOG, SCAMERA on the vivo X500 Pro Max). Dumps are in `C:\Users\MECHREVO\Desktop\т\DUMP SCAM\`. Source: `SCAMERA-PC\project\app\src\main\java\com\particlesdevs\photoncamera\` (HEAD e4d5d55). This was a read-only pass: I edited no code.

Short names for files:
- **A5** = vivo-V2602A (X500 PM)
- **V15** = vivo-V2515 (X300)
- **QA** = vivo-V2547A, **QB** = vivo-V2562 (X300 Ultra, two firmwares)
- **CPH** = OnePlus-CPH2747
- **PKJ** = OPPO-PKJ110
- **PLG** = OPPO-PLG110
- **X15** = Xiaomi-25010PN30G
- **PX** = Pixel 10 Pro XL
- **SS** = SM-S928B
- **MO** = moto edge 50 pro

---

## 0. What the owner's log shows (X500 Pro Max, 13:07:14-13:07:48)

The APK in this log is older than P109i and P109j. It sends `vivo.control.session.seamlesscontrol=[1]` (LOG 2020), and camera 6 still shows as `back1=6 3.5×` (VivoMtkIsz lines, e.g. LOG 94).

1. **ISZ key missing on this phone.** The HAL does not know `vivo.control.seamless.roiRemosaic`.
   - The log says `vendor tag vivo.control.seamless.roiRemosaic is not supported` 20+ times (LOG 419, 615, 1152, ...).
   - The dump agrees: the key is in no request, session or result list of A5. It appears only in the selector JSON (A5 43732, 43942).
   - V15 does have it (V15 1468).
   - Result: the tele «7×» module measured only `field 1/1.15 of camera 3` (LOG 1149-1150). Every DCG14 line shows `sensorCrop -, roiRemosaic -`.
2. **DCG switch did nothing.** Results still showed `sensorScenario [2, 1]`, `[4, 1]` and dynamic white 1023 (LOG 3107, 3665).
   - The log sent `hdrMode=[1]`, `EnableDCGHDR=[1]`, `seamlesscontrol=[1]` and `shdr_enable=[1]`.
   - This fits the selector. `seamlesscontrol 1` gives DCGHDR1 = NORMAL1EXP (A5 44897). DCGHDR2 (SensorScenario 17) sits only in the video branch, behind a vivo video UIMode.
3. **Every camera switch costs 1-2 failed configures (-38) plus `onError 4`.**
   - With `com.vivo.current_ui_module=[1]`: cameras 2, 4 and 3 (LOG 90-136, 420-460, 919-1010).
   - With the vivo EIS session key: cameras 2, 3 and 4 (LOG 209-212, 1090-1107, 3236-3239).
   - From the module-switch race: LOG 567.
   - The source already turns the UI module off by default (P109e). The refusals are remembered only per process.
4. OIS is ON under the `com.android.camera` spoof (`STAB_TRACE ... res[ois=1 vs=0]`, end of LOG).
5. **Log noise.** 1342 STAB_TRACE lines and 188 `no free module slot` warnings in 35 s. P109h and P109j probably remove the slot spam.

---

## 1. Completeness critic

I did not have the group briefs. I judged each group against its themes (DCG/HDR, ISZ, full-res, OIS/gyro, ZSL, AE/ISO, gating) and the required fields.

### vivo_mtk (X500 PM + X300)
- **Does the ISZ key exist on the X500 PM?** No; the group did not check. Its ISZ test 1 (`roiRemosaic 4` + zoomRatio on camera 3) cannot work on PD2602 (section 0, item 1). The keys that do exist there are not evaluated:
  - `com.mediatek.control.capture.remosaicenable` (A5 11372 / 14937)
  - `vivo.control.sensorMode` (A5 11750 / 15315)
  - session `com.mediatek.seamlessfeature.sensorScenario` (A5 12868 / 16433)
  - results `vivo.feedback.SensorCropRegion` and `isROIRemosaicPreview` (A5 11843 / 11915)
- **What result proves the 551 route is active?** Unanswered. The finding expects `sensorScenario [2, x≠1]`. But under 551 the selector maps the main (HAL ID 0) to `PROTRAITVHDR1 = NORMAL1EXP, SensorScenario 1` (A5 44847). So success must be judged on the RAW payload, `vhdrResult` and dynamic white, not on sensorScenario.
- **Why do sessions fail with -38?** The UI module and the EIS session key fail on every camera of the X500 PM (LOG). The group did not say whether a Kuaishou spoof avoids this.
- **AE/ISO ranges and OIS for the X300 under spoof:** not covered.

### vivo_qcom (X300 Ultra)
- **No live results at zoom > 1** and no spoofed run. Both ISZ findings are therefore unproven at runtime (the group says so).
- **F3 recommends `current_ui_module=1`.** The group does not weigh that this key failed (-38) on the X200 Pro, V2515 and X500 PM (`research/vivo_x300/FINDINGS.md:39`, LOG).
- **The spoof is wired only for vivo MediaTek** (`Dcg14Mode.spoofPackage()` → `VivoMtkIsz.device()`). Applying it to the Qualcomm X300U is new work, not a setting.
- **Per-frame HAL gyro on Qualcomm vivo:** not looked for. MediaTek vivo has `com.mediatek.3afeature.gyrodata`.

### oppo (OnePlus 15, X9 Pro, X8 Ultra)
- **Is the CPH2747 RAW limit of 7 real for SCAMERA?** Not reconciled. SCAMERA shot on a OnePlus 15 on 2026-10-08 (commit 45ed01f, P58), before the RAW reader floor 16 (fa0b167, 2026-10-11). One SCAMERA launch decides it.
- **PLG110 RAW at 15 fps:** the cause is untested; no fps-range trial was run.
- **Which OPPO op modes keep the RAW stream?** PKJ has `EnableSkipRawStream = 0x8001;0x8009;0x8019` (PKJ 26164). This is flagged as a risk, but no test order is given.
- **No Find X9 Ultra dump.** That is the phone behind the HAL-death note in `CaptureController.java:2932-2934`.

### xiaomi (15 Ultra and others)
- **When does DXG engage, and what is its minimum shutter?** Answered in the dump but missed. `/odm/etc/camera/xiaomi/satsettings.json` (X15 86140-86144) has:
  - `wideDXGModeSwitchThre "1:408:420:4:5|1:380:420:2.5:3.5"`: lux-index and DR-ratio thresholds
  - `wideDxgModeExptimeMin 21332` ns
  - `DXGModeRatioRange` wide `1.0:1.9999`

  So a DXG test needs zoom 1.0-1.99 and a bright, high-DR scene.
- **No 17 Ultra dump.** That is the phone with the forced-mode exception, so mode 9 and the RAW14 layout cannot be cross-checked.
- **Gaps:** the periscope (camera 5) mode table is missing. Whether the binder spoof survives HyperOS AppOps is untested.

### pixel_samsung_moto
- **No zoom sweep and no `isSessionConfigurationSupported` check.** So the physical max-res stream inside a logical session is unproven.
- **The S24U dump is stale:** 2026-05-18, v1.11, run under the `com.ss.android.ugc.aweme` spoof. Vendor tag 0x81200021 is still unnamed.
- **Moto DCG/stagger capability:** not examined.

### Spot-check of the highest-priority findings (opened at the cited lines)

| # | Finding | Lines opened | Verdict |
|---|---|---|---|
| 1 | OPPO P1: CPH2747 RAW_SENSOR ImageReader maxImages ≤ 7; SCAMERA floor 16 | CPH 51-57, 2930-2945; `CaptureController.java:2452-2456` | **Confirmed as measured**: probe aborted at 64/32/16/8, accepted 7, hold test 7/7, YUV/PRIVATE 62. Caveat: this probe may be an artifact, given the P58 shots on 2026-10-08 (see critic). |
| 2 | Xiaomi P1: `ThirdDXGEnable` package list; DXG = modes 37/39 | X15 26881-26890 (7 packages incl. `com.google.android.youtube`, `com.xiaomi.cameratest`), 70777-70812 (PhysicalId 1 rear wide, DXG-timing [37,39,137,139]), 3711 (`clientName "com.scam.test"`), 4713 (`current_mode 1`), 4910 (`isThirdParty 1`) | **Confirmed**. Missed the satsettings thresholds and the 21.3 µs DXG minimum shutter (above). |
| 3 | Pixel P1: 48 MP tele/UW RAW only in `getHighResolutionOutputSizes`; PixelPro10 ignores it | PX 6187-6202 (camera 6 max-res map: RAW10 8064x6048 `[HIGH_RES/burst]` 15 fps), 8199-8201 (camera 9), 527 (camera 0 RAW10 8160x6144 @24, normal list), 4545 / 5915 (intrinsics 12452.6 vs 24905.2); `PixelPro10.java:219-229` calls only `getOutputSizes` | **Confirmed** |
| 4 | vivo_mtk P1: 551 = 3RD_APP_HDR for TIKTOK/WEIXIN/KUAISHOU | V15 92358-92360, A5 44673-44712, 44847, 78078-78084 | **Lines confirmed; the expectation is wrong.** The main under 551 resolves to NORMAL1EXP / SensorScenario 1, so "sensorScenario ≠ 1" is not a valid success test. |
| 5 | vivo_mtk P2: X500 PM tele ISZ via `roiRemosaic 4` | A5 key lists (absent), A5 43732, V15 1468, LOG 419/1149 | **Wrong for the X500 PM**: the key does not exist there. Valid for the X300. |
| 6 | vivo_qcom P1: tele `PreviewRoiQuadSensorMode` is 5 on QA, 31 on QB | QA 21560/21572, QB 21252/21264; camx QA 22690/22729/22789/22799 | **Confirmed.** In camera_config.xml, CameraId 3/4 are `tele3p5x`/`etele3p5x`; that is CamX numbering, not Android IDs. |

---

## 2. Ranked action plan (top 15)

Order: crash safety first, then the owner's current device (X500 PM), then cheap verified wins, then experiments that need approval.

1. **Clamp every ImageReader to ≤ 62 and settle the OnePlus 15 RAW limit** (all phones; CPH2747)
   - **Build:** in `CaptureController.java:2452-2456`, the YUV-ring branch `min(zslRingCapacity()+3, 103)` and the 103 cap become 62. Clamp the ZSL pref (`PreferenceKeys` 1381, up to 100) to ≤ 59.
   - **Measured limits:**
     - maxImages 63 aborts natively: QA/QB 4968-4979, CPH 2933
     - 30 fps RAW ring depth: X300U 58, PKJ 57-58, X15 58, PX 60, SS 56, MO 57
   - **CPH2747:** open SCAMERA on camera 2 with ZSL (RAW reader 16-20).
     - If it dies with a native abort, cap the RAW reader at 7 for MODEL CPH2747 and keep depth in the memfd ring.
     - If it runs, mark the SCAM TEST value as a probe artifact.
   - **Verify:** ExitReasons at the next start shows no `crash native`. A new log line `RAW reader maxImages=N`.
   - **Risk:** none for the clamp. The CPH cap shrinks the post-shutter reader.

2. **X500 PM 14-bit DCG through the 551 route (Kuaishou spoof)** (V2602A, physical camera 2)
   - **Build:** scam_dev string `dcg14_spoof_pkg` (default `com.android.camera`). Test `com.smile.gifmaker` first, then `com.tencent.mm`, then `com.ss.android.ugc.aweme`. All three are in `is3rdAppUseHdr` (V15 92358-92360) and in the 551 rule (A5 44673-44746).
   - **Session:** `CONTROL_AE_TARGET_FPS_RANGE` upper ≤ 30. 16:9 YUV/PRIVATE 1920x1080 first, then add RAW_SENSOR 4096x3072. No cameraScenario or hint keys.
   - **Next step:** add session + request `com.mediatek.hdrfeature.hdrMode` int32 [3] and `SessionParamhdrMode` [3].
   - **Control run:** `dcg14_spoof 0` + session `com.mediatek.seamlessfeature.cameraScenario` [551] with no hint.
   - **If samples go above 1023:** add request `com.mediatek.control.capture.raw.bpp` [14]. Leave `raw10.convertfmt` unset.
   - **Benefit:** IMX09C `DCG_SENSOR_MERGE_14BIT_BAYER` (mode 33) is in 3RD_APP_HDR only (A5 79681-79700).
   - **Verify:** RawPayloadCheck max > 1023, `android.sensor.dynamicWhiteLevel` > 1023, `com.mediatek.hdrfeature.vhdrResult` / `hdrDetectionResult` non-empty. Do **not** expect sensorScenario ≠ 1 (A5 44847). Also STAB_TRACE `res[ois=..]`.
   - **Risks:**
     - OIS may drop: `EnablePlatformOisFor3rdParty` lists Wechat only (A5 78084).
     - `Fix3rdAppP1Size` changes the P1 size (A5 78078).
     - 3rd-party 3A tuning applies.
     - A 16:9 mode against a 4:3 RAW may fall back to BIN.
   - On the X300 the same route gives `DCG_AP_MERGE_2EXP` (25), not 14-bit.

3. **Fix the X500 PM ISZ modules: `roiRemosaic` does not exist there** (V2602A cameras 2/3; the X300 keeps the current route)
   - **Build:** gate `VivoMtkIsz` on `builder.get(roiRemosaic)` resolving. Where it does not, do not label 2×/7×/14× as ISZ, and send nothing (this also removes the warning spam).
   - **Test routes on camera 3, then 2**, each with `CONTROL_ZOOM_RATIO` 2.0 and then 4.0:
     - (a) request `com.mediatek.control.capture.remosaicenable` int32 [1] (A5 14937) → selector REMOSAIC
     - (b) request `vivo.control.sensorMode` [1] (A5 15315) → REMOSAIC, camera scenario 511
     - (c) session `com.mediatek.seamlessfeature.sensorScenario` [3, 8] and [2, 8]; 8 = CROP_2X_4CELL in the selector enum (inferred)
   - **Verify:** a fresh FovSelfCheck `field 1/2.00` (not "measured earlier"), result `vivo.feedback.isROIRemosaicPreview` 1, a non-full `vivo.feedback.SensorCropRegion` (A5 15408/15480), and MosaicBlockDetector block 1.
   - **Risks:**
     - REMOSAIC may switch to 50/200 MP modes at low fps and scale into 4096x3072.
     - Route (c) is a direct mode switch; vivo-MTK ISZ is owner-approved (P109b).

4. **Read the HAL's per-frame gyro and OIS data** (read-only; vivo MTK, PLG110, X15, Pixel)
   - **vivo MTK and PLG110:** `com.mediatek.3afeature.gyrodata` (byte[]: 24-byte records = int64 ts + 3×float32 + pad, newest first) plus `gyrodatavalidnum` (int32).
     - vivo: 1 kHz, 33-34 samples per frame (A5 6160-6161, 6529; V15 3217)
     - PLG: 400 Hz (PLG 5707-5708)
     - The timestamps are on the sensor clock.
   - **X15:** `com.xiaomi.ThridPartyGyroState.gyroData` byte[4024]: count, then x/y/z float[200]; timestamps are zero (X15 4674).
   - **Pixel:** `android.statistics.oisSamples` arrive without OIS_DATA_MODE_ON (PX 1320-1326). `StabilizationTrace.wantsOisSamples` (253-259) requires mode ON, so drop that gate.
   - **Build:** in HalMotion, integrate |ω| over [ts, ts + exposure + rollingShutterSkew] for a per-frame blur score. Use it for frame ranking and the reference frame, and as an alignment seed.
   - **Verify:** a new STAB_TRACE field `halGyro n=33 span=32ms blur=...` per frame, compared with `gyroRms`.
   - **Risk:** low. Units are assumed to be rad/s.

5. **Fix the Pixel 10 Pro XL full-res stream choice** (PX)
   - **Build:** in `PixelPro10.fullResStream` (219-229), merge `getHighResolutionOutputSizes(RAW10)`. Pick by lens:
     - 1× (0 / 0-2): RAW10 8160x6144 @24
     - tele module 0-4: physical 6, RAW10 8064x6048 @15, `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION`
     - UW 0-3: physical 9, same settings
     - Hide the button on ISZ modules 0-5 / 0-6.
   - Check with `isSessionConfigurationSupported`. Fallback: MultiResolutionImageReader (PX 334).
   - **Verify:** `SCAM_CAPTURE: camera 6: full resolution 8064x6048 format 37`, the session configures, the DNG is 8064x6048, and the result `dynamicWhiteLevel` and `dynamicBlackLevel` are used.
   - **Risk:** a physical max-res stream in a logical session is untested. 15 fps. About 61 MB per frame, ring ≈ 8.

6. **Xiaomi 15 Ultra: test DXG through the package spoof** (25010PN30G, logical camera 0)
   - **Build:** the binder spoof as `com.google.android.youtube` (fallback `com.xiaomi.cameratest`). Send no `current_mode`.
   - **Scene:** zoomRatio 1.0-1.99, bright and high-DR (satsettings thresholds X15 86140-86144).
   - **Verify:**
     - result `com.xiaomi.sessionparams.clientName` = the spoofed name
     - `org.codeaurora.qcamera3.sensor_meta_data.current_mode` = 37 (4:3) or 39 (16:9)
     - `sensor_mode_info` word13 = 14
     - `dynamicWhiteLevel`
     - RawPayloadCheck layout (packed RAW14 is possible)
   - **Benefit:** DXG without forcing a mode, so the no-mode-switch rule is respected.
   - **Risks:**
     - HyperOS may reject the spoof.
     - DXG may apply to preview/video only.
     - Clamp manual shutter to ≥ 21.3 µs in DXG.
     - Avoid aweme/instagram: `HighFpsForLowLight` shortens night exposures.

7. **X300 Ultra in-sensor zoom** (V2547A/V2562; Android cam 3 main, cam 5 tele)
   - **Main:** when the RAW stream carries `CROPPED_RAW`, add session (and per-request) `org.codeaurora.qcamera3.sessionParameters.EnableInsensorZoom` = 1 and `EnableSnapshotOnlyInsensorZoom` = 0 at zoomRatio 2.0. Try two layouts:
     - (a) physical 3/5 opened directly
     - (b) logical 0 with `OutputConfiguration.setPhysicalCameraId("3")`, because camx has `enableInSensorZoomForOnlyPhysicalStream=1` (QA 22789)
   - **Tele:** read `PreviewRoiQuadSensorMode` from `/vendor/etc/camera/camera_config.xml` at runtime (5 on QA, 31 on QB) and send it as `vivo.control.forceSensorMode`. Never hard-code the PD2454 values 5/7.
   - **Verify:** `SCALER_RAW_CROP_REGION` ≈ (1020,766)-(3060,2298), `SENSOR_RAW_BINNING_FACTOR_USED` false, MosaicBlockDetector block 2 (quad, no remosaic).
   - **Risks:**
     - Above `AECGainThresholdForQCFA = 30` (≈ ISO 1500) the HAL silently falls back to binning (QA 22729).
     - The quad mosaic needs remosaic.
     - Forcing the sensor mode on a Qualcomm vivo needs owner approval.

8. **Use the HAL's brightness target (ADRC/DRC) in ScamScene and ScamAe** (Qualcomm OPPO/OnePlus, X300U, Xiaomi, Pixel)
   - **Keys:**
     - OPPO QCOM: `com.qti.stats_control.drc_gain` float[] (PKJ 12147) and `org.quic.camera2.statsconfigs.AECExposureTime` long[3] (PKJ 12235)
     - X300U: `AECCompenADRCGain` × `AECCompenDarkBoostGain` (QA 4686-4690) plus `org.codeaurora.qcamera3.stats.is_hdr_scene`
     - X15: `com.xiaomi.mivi2.adrcGain`, fallback `com.qti.chi.statsaec.previewDRCGain` (X15 4683, 4651)
     - Pixel: `CONTROL_POST_RAW_SENSITIVITY_BOOST` 231 (PX 1268)
   - Today ScamScene reads only `vivo.feedback.AdrcGain`.
   - **Use it** for the L-frame size and the tone lift, and as DNG BaselineExposure = log2(gain).
   - **Verify:** a new log line `HAL target +x.xx EV (source)` next to the final brightness, on a high-DR scene.
   - **Risk:** double compensation. Apply it in exactly one place.

9. **Reject noise profiles with O ≤ 0** (S24U main/UW, Moto logical 0)
   - **Evidence:** `SENSOR_NOISE_PROFILE` {S > 0, O = 0.0} on SS 4540, 10490, 18103 and MO 3444. `ScamHybridBurst.validNoise` (364-366) accepts O ≥ 0.
   - **Build:** treat O ≤ 0 with S > 0 as invalid and use the existing fallback (another frame's profile, the module fit, or the model). On Moto, prefer camera 4 or physical 2.
   - **Verify:** a log line `noise profile O=0 rejected -> <fallback>`.
   - **Risk:** shadow weighting changes on those modules.

10. **vivo -38 cascade: remember refusals and wait for close** (vivo MediaTek; LOG 90-212, 420-568, 919-1107, 3236)
    - **Build:** persist the UI-module and EIS-session-key refusals per `Build.FINGERPRINT` + camera id in SharedPreferences, not per process. On a module switch, wait for the previous `CameraDevice.StateCallback.onClosed` before `openCamera`, instead of the P109c retry after 500/1000 ms.
    - **Verify:** after the first launch, LOG has no `vivo HAL busy (-38)` or `onError() ... error = [4]`. Time from module tap to first frame drops.
    - **Risk:** a stale refusal after an OTA; keying on the fingerprint covers this.

11. **Camera-ID hygiene** (X300U, X15)
    - **X300U:** never open 2 (logical; no RAW within 5 s, then onError 4: QA 9230-9241), 6 (gc1, open error 4: QA 17031) or 7 (multispectral, closes: QA 18499). 8-11 vanish after one probe.
    - **X15:** opening 2-5 by ID ends in onError 4 and a provider restart (X15 11862-11867, 16752). Route them through logical 0 with `setPhysicalCameraId`, ring ≤ 58.
    - **Verify:** `CameraManager2: CameraIDs:[...]` and no `CameraDevice was already closed`.
    - **Risk:** low.

12. **Add the missing ISO and shutter range sources** (Moto, X15, X300U, S24U)
    - **Moto:** add `com.lenovo.moto.sensor.info.exposureTimeRange` int64[2] {58664, 32000000000} (MO 2288) to `ExposureRanges.vendorExposureMax` names.
    - **X15:** read `xiaomi.sensor.info.sensitivityRange` explicitly: main 50..12800 (X15 3253).
    - **X300U tele:** add ISO 50..12800 to `knownProRange` (QA 15624ff).
    - **Minimum shutter per X15 mode:** ≥ 29.6 µs remosaic/ISZ, ≥ 81 µs DOL2, ≥ 21.3 µs DXG.
    - **S24U:** run exposures above 117 ms on camera 23/56, not 0/20 (SS 2299 vs 14581).
    - **Verify:** the `ExposureRanges: shutter declared ... vendor <name>` line, and request vs result `SENSOR_EXPOSURE_TIME`.
    - **Risk:** the HAL may clamp silently.

13. **OPPO Find X9 Pro: frame timing and a DCG search** (PLG110 camera 2)
    - **Frame timing:** take spacing from result `SENSOR_FRAME_DURATION`: 66.7 ms with a RAW target on cams 2-4 (PLG 5634, 5679). Then try `CONTROL_AE_TARGET_FPS_RANGE` [30,30] on the RAW repeating request.
    - **DCG tries, in this order:**
      1. session `com.mediatek.seamlessfeature.cameraScenario` [303] + request `sensorScenario` [2,13]
      2. [304] + [2,26]
      3. `sensorScenario` [2,23] + `sensorScenarioCustomHint` [2,21]
      4. the same as 1-3 with operating mode 0x8009 (PLG 19138-19151, 18267)
    - **Verify:** result fps and frame gaps; `sensorScenario` ≠ [2,1]; RawPayloadCheck > 1023; `vhdrResult`; STAB_TRACE `res[ois]` (OIS is enabled only in OPPO op modes, PLG 18747-18770).
    - **Risks:** stalls. `isEnableHDRfor3rd=false` (PLG 17688).

14. **Pixel main and ISZ module correctness** (PX)
    - **Main stream:** RAW_SENSOR or RAW12 4080x3072 (12-bit DCG, white 4095, black 256), never RAW10 except for full-res (PX 1302, 1658-1663).
    - **0-5 / 0-6 levels:** take white, black, noise and max analog from the physical cameras (1023 / 64 / 666, PX 5157-5163).
    - **Zoom:** set `CONTROL_ZOOM_RATIO` 2.0 / 9.0 on the logical request. Log `activePhysicalId` and `com.google.pixel.experimental2022.multicam.leadCamId` per frame, and fall back when they are not 5/6.
    - **Verify:** per-frame active ID in the log; DNG white level matches the module.
    - **Risk:** low.

15. **Qualcomm OPPO/OnePlus sensor-mode trials** (PKJ110 / CPH2747 camera 2; **needs owner approval**: no-mode-switch rule)
    - **PKJ:** request `org.codeaurora.qcamera3.sensor_meta_data.current_mode` int32 3 / 4 (1dol / 2dol stagger, PKJ 26327-26328).
    - **CPH:** `current_mode` 11 (DCG 2-exp) / 16 (DCG 1-exp 12-bit) (CPH 20907, 20900). Also session `EnableHDRDCGMode`=1 + `HDRMode`=1, to see whether the HAL picks `ThirdAppEnableHdr` mode 13 by itself (CPH 23665).
    - **Verify:** result `current_mode`, `com.qti.stats_control.DCGMode`, `com.qti.stats_control.ExposureCount`, RawPayloadCheck max.
    - **Risk:** session parameters next to RAW killed the HAL on the X9 Ultra (`CaptureController.java:2932-2934`). Use the module revert path.

### Deferred (not in the top 15)
- **S24U** 10× ISZ via hidden logical 24 + CROPPED_RAW (needs the spoof).
- **Moto** `EnableInsensorZoom`.
- **X15:**
  - 0x9002 logical route (MIPI RAW14)
  - 50 MP RAW 8160x6144 (needs approval)
  - X300U DCG behind the vivo AppType (blocked by the UI-module -38)
- **OPPO custom 50/200 MP RAW sizes.**
- **Pixel** `com.google.internal.SensorBinningMode`.
- **X500 PM** `forceSensorMode` keys: they are absent from the A5 key lists, so check with `builder.get` first.