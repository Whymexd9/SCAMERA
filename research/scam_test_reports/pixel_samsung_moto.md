# pixel_samsung_moto findings

## P1 Pixel 10 Pro XL - 48 MP tele/UW RAW is never found (high-res size list ignored), and the full-res button on ISZ modules jumps the field of view
- theme: full-res
- confidence: verified
- evidence: Report (characteristic, max-res maps):
- Camera 6: SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION RAW10 8064x6048 [HIGH_RES/burst], minFrameDur 66666666 ns (15 fps), stall 0 (PX 6202).
- Camera 9: same (PX 8201).
- SCAM TEST prints [HIGH_RES/burst] only for getHighResolutionOutputSizes() (scam-test Fmt.java:245-247).
- Cameras 0/2/5: RAW10 8160x6144 @24 fps in the normal list (PX 527, 3457, 5511).
- Cameras 3 and 4: no max-res map (PX 3808, 4491).
- Camera 6 max-res array Rect(0,0-8064,6048) at 0.7 um = 5.645 mm, the width of camera 4. Intrinsic fx 24905 cx 3997 (PX 5915) equals camera 4's fx 12452 (PX 4545) at twice the grid, so it is the whole tele field.
- Camera 5 max-res = full main 8160x6144.

Code:
- PixelPro10.java:220-229 calls only map.getOutputSizes(format), so it returns null on 0-6 and 0-9. The comment at :45 claims 8064x6048 on 6/9.
- FullResButton.java:48 and CaptureController.java:2424 decide per physical ID.
- recommendation: 1. In fullResStream/maxResSize, merge getHighResolutionOutputSizes(RAW10) with getOutputSizes.
2. Choose the full-res stream by lens:
   - 1x (0/0-2): own RAW10 8160x6144 @24.
   - Tele module 0-4: physical 6 with SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, RAW10 8064x6048 @15.
   - UW 0-3: physical 9, same settings.
   - Hide the button on ISZ modules 0-5/0-6 (their default stream is already the native-resolution centre).
3. Check with isSessionConfigurationSupported. Fallback: MultiResolutionImageReader RAW10 on logical 0 (multi-res map lists 8064x6048 for IDs 6 and 9, PX 334).
4. Read dynamicWhiteLevel/dynamicBlackLevel (PX 767-768) on max-res frames.
- risk: Physical max-res stream on hidden 6/9 inside a logical session is untested. 15 fps halves ring fill rate. About 61 MB per frame.

## P2 Pixel 10 Pro XL - Physical 5 (2.0x) and 6 (9.0x) are true in-sensor crops; send matching zoomRatio and check the active camera; 9 can be a 1x macro module
- theme: ISZ
- confidence: inferred
- evidence: Characteristics:
- Physical sizes: 5 = 4.896 mm vs 9.792 (main, PX 5154); 6 = 2.8224 vs 5.6448 (tele, PX 5857); 9 = 2.8224 vs 5.6448 (UW, PX 7853). Pitch is native 1.2/0.7 um.
- FOV vs main (focal/width): 5 = 2.000, 4 = 4.503, 6 = 9.006, 9 = 1.016, 3 = 0.5078 = zoomRatioRange min 0.50783783 (PX 174/202). So zoomRatio 1.0 = camera 2 (DefaultPhysicalCamId {2}, PX 575).
- Intrinsics of 5/6 are copies of the parent: 2849.37 (PX 5211 = 3149); 12452.6 (PX 5914 = 4545).
- Camera 9 minimum focus 50 dpt vs main 9.52 (PX 7859).
- Physical request keys include android.control.zoomRatio, exposureTime, sensitivity (PX 858-867).
- Live results at zoom 1: activePhysicalId=2 (PX 1297); vendor result com.google.pixel.experimental2022.multicam.leadCamId int32 {2} (PX 1365).
- Hidden IDs not openable directly (PX 12682-12683).
- recommendation: 1. Keep "0-5"/"0-6": session on logical 0, readers on the physical camera.
2. Also set CONTROL_ZOOM_RATIO = 2.0 / 9.0 on the logical request, or use the XiaomiTeleZoom P41b layout: logical viewfinder at that zoom plus physical RAW reader.
3. Log activePhysicalId and leadCamId per frame; abort or fall back if they are not 5/6.
4. Optionally add 0-9 as a "1x macro" module, chosen by minimum focus distance.
5. Never take FOV from LENS_INTRINSIC_CALIBRATION or LENS_DISTORTION on 5/6/9.
- risk: Untested whether physical-5 buffers are filled while the HAL's active camera is 2. The tele crop may fall back to 4 or 2 in low light.

## P3 Pixel 10 Pro XL - Main RAW_SENSOR/RAW12 carry 12-bit DCG; RAW10 drops 2 bits; the ISZ crops are plain 10-bit
- theme: DCG/HDR
- confidence: verified
- evidence: Characteristics (cameras 0/2):
- whiteLevel 4095, BlackLevelPattern 256 (PX 170, 539, 560).
- RAW12 0x26 4080x3072 @30 fps stall 0 only on 0/2 (PX 427, 3354-3355).

Results:
- dynamicWhiteLevel 4095, dynamicBlackLevel 255.76 (PX 1302-1303).

Measured RAW_SENSOR buffer:
- Max 1793 within 4095. Low-2-bit histogram flat [783590, 782524, 783725, 783601], so real 12-bit samples (PX 1658-1663).

Physical 5 (ISZ):
- white 1023, black 64, no RAW12, max analog 666 vs 333 (PX 5157-5163).
- Vendor floatSensorMaxAnalogSensitivity 666.67 vs 333.33 (PX 5572 vs 589).
- recommendation: - On the main module stream RAW_SENSOR or RAW12 4080x3072, not RAW10 (except full-res, where only RAW10 exists).
- On 0-5/0-6/0-9, take levels, noise and ISO limits from the physical camera, never from logical 0's 4095/256.
- No DCG assumptions on the ISZ modules.
- risk: Low.

## P4 Galaxy S24 Ultra - 10x in-sensor zoom on the 5x tele via CROPPED_RAW: use logical 24, which reports rawCropRegion
- theme: ISZ
- confidence: inferred
- evidence: CROPPED_RAW (use case 6) listed on:
- tele 7 (SS 15878), 54 (SS 28525), logical 24 (SS 23908).
- main 0/5/20/23/56 (SS 2365, 14311, 16637, 21502, 30755).

Tele characteristics:
- binningFactor 2x2 (SS 24049).
- Camera 24 zoomRatioRange [1.0, 8.0] (SS 23843).

Live results:
- Logical 24: activePhysicalId=54 (SS 25308), rawCropRegion Rect(0,0-4080,3060) (SS 25312).
- Main at 1x on logical 0: rawCropRegion Rect(32,0-4048,3060) (SS 4533); rawBinningFactorUsed=true (SS 4542).
- Physical 54 and 56 opened directly do not return rawCropRegion in results (SS 29776-30673, 32034-32930).

Code:
- CroppedRaw.java:48 records Rect(0,0-0,0) from the tele in the owner's logical-0 session.
- IszModule.ensure (:39-56) handles only the main module.
- recommendation: 1. Extend IszModule to every visible back module whose camera lists use case 6 and has binningFactor 2x2. Create a "10×" duplicate at zoom 2 for the 5x module.
2. Run it on hidden logical 24 (package spoof): RAW stream setStreamUseCase(6), CONTROL_ZOOM_RATIO 2.0.
3. Read SCALER_RAW_CROP_REGION from the logical result, plus SENSOR_RAW_BINNING_FACTOR_USED == false as confirmation.
4. Check activePhysicalId == "54".
- risk: No zoom sweep in the report. Logical 24 can fall back to main 56 at close focus or in low light. Without the spoof only 0-7 is available, and its crop report was empty.

## P5 Galaxy S24 Ultra + Motorola Edge 50 Pro - SENSOR_NOISE_PROFILE has O = 0 on S24U main/UW and on the Moto 3rd-party logical 0; SCAMERA accepts it as valid
- theme: AE/ISO
- confidence: verified
- evidence: S24U (result, S non-zero, O = 0.0):
- Logical 0 main {3.1352e-4, 0.0} at ISO 668 (SS 4540, 5045).
- UW 2 {0.002407, 0.0} (SS 10490).
- 20/23/56/58 same (SS 18103, 22933, 32097, 34351).
- Teles 21/24/52 have non-zero O (SS 20531, 25319, 27615).

Moto:
- Logical 0 {3.1438e-4, 0.0} (MO 3444, 3802).
- Logical 4 {1.469e-3, 2.29e-6} (MO 12320).
- Physical 2 {1.173e-3, 3.04e-7} (MO 7995).

Code: ScamHybridBurst.java:364-366 validNoise accepts offset >= 0.
- recommendation: - Treat O <= 0 with S > 0 as invalid and use the existing fallback (another frame's profile, the per-module fitted profile, or the built-in model).
- On Moto prefer camera 4 or physical 2 over logical 0 when the HAL profile is used.
- risk: Visual impact on shadows not measured. Choosing a fallback model changes noise weighting on these modules.

## P6 Galaxy S24 Ultra + Motorola Edge 50 Pro - Exposure ceilings: S24U main 117 ms on logicals 0/20 vs 255 ms on 5/23/56; Moto vendor range up to 32 s is not read
- theme: AE/ISO
- confidence: verified
- evidence: S24U (characteristics):
- Camera 0 exposureTimeRange [82896, 117413040], maxFrameDuration 254991000 (SS 2299, 2676). Camera 20 same.
- Main 5/23/56: [43220, 254947000] (SS 14581, 21653, 30906).

Moto:
- Camera2 range 249.86 ms on 0/4/5, 252.8 ms on 2. QTI iso_exp_priority.exposure_time_range (0x80040004) is the same (MO 2236, 6792).
- Vendor characteristic com.lenovo.moto.sensor.info.exposureTimeRange int64[2] = {58664, 32000000000} on all sensors (MO 2288, 6855; dumpsys 20277).
- Request templates carry org.quic.camera.manualExposure.disableFPSLimits (byte) and overrideSensorFrameDuration (int64) (MO 2921-2922).

Code: ExposureRanges.java:87 reads only org.codeaurora...exposure_time_range and xiaomi.sensor.info.exposureTimeRange.
- recommendation: - S24U long or night shots: run on 23 or 56 (or 0-5) for up to 255 ms, instead of 0/20.
- Moto: add "com.lenovo.moto.sensor.info.exposureTimeRange" to the vendorExposureMax names, so the ruler reaches 32 s and LongExposureKeys sends the FPS-limit keys past 250 ms.
- risk: Unverified that the Moto HAL honours more than 250 ms (check result exposure). Physical-stream exposure limit inside a logical-0 session on S24U is unverified.

## P7 Pixel 10 Pro XL - OIS samples arrive every frame although only OIS data mode OFF is advertised
- theme: OIS/gyro
- confidence: verified
- evidence: Characteristics:
- availableOisDataModes [0(OFF)] on 0/2/5/9 (PX 570, 3500, 5552, 8242). Only camera 6 lists ON (PX 6243).

Results:
- Logical 0 preview: android.statistics.oisSamples, 6 samples about 5 ms apart (PX 1320-1326).
- Still RAW: 7 samples (PX 1530-1537).
- Vendor com.google.pixel.experimental2021.statsOisShiftDacX/Y int32[] and statsOisTimestamps int64[] (PX 1358-1360).

Property: persist.vendor.camera.ois_with_system_imu=true (PX 29380).

S24U and Moto list oisSamples but delivered none.

Code: StabilizationTrace.wantsOisSamples requires mode ON.
- recommendation: On Pixel, consume STATISTICS_OIS_SAMPLES whenever non-null (preview, ring and RAW results) as alignment and motion priors, without requesting OIS_DATA_MODE_ON.
- risk: Sign and scale convention against gyro needs one calibration shot.

## P8 Pixel 10 Pro XL - AE adds 2.31x digital gain after the RAW (postRawSensitivityBoost 231); SCAMERA never reads it
- theme: AE/ISO
- confidence: inferred
- evidence: - Characteristic postRawSensitivityBoostRange [100, 1599] (PX 201).
- Preview and still results: postRawSensitivityBoost 231 with sensitivity 333 = maxAnalog and exposure 33.3 ms (PX 1268, 1304, 1311, 1477, 1513, 1520).
- Vendor floatSensorSensitivity 333.333 (PX 1350); baseIso 20.833 (PX 595).
- No CONTROL_POST_RAW_SENSITIVITY_BOOST anywhere in the SCAMERA source.
- recommendation: - When planning bursts from the Pixel preview AE, use total gain = sensitivity × boost/100.
- Realise the extra +1.2 EV with longer exposure (up to 16 s allowed, PX 171) or more frames.
- Analog gain = sensitivity / 20.83.
- Make it a tunable.
- risk: The underexposure may be intended highlight protection (HDR+ style). The effect on SCAMERA brightness is inferred.

## P9 Motorola Edge 50 Pro - ISZ path is EnableInsensorZoom plus the in-sensor-crop result; super_zoom probably only raises digital zoom to 30x
- theme: ISZ
- confidence: inferred
- evidence: Session keys on logical 0/4:
- org.codeaurora.qcamera3.sessionParameters.EnableInsensorZoom int32 (MO 2552)
- EnableSnapshotOnlyInsensorZoom int32 (MO 2560)
- ExtendedMaxZoom (MO 2565)
- com.lenovo.moto.control.super_zoom byte (MO 2538)
- SATFallback.T2W / W2UW byte (MO 2531-2532)
- clientapp.is_motcamera2 byte (MO 2536)
- Types from the dumpsys descriptors (MO 28284-28802).

Static:
- com.lenovo.moto.staticinfo.support_in-sensor-crop=1 (MO 2321).
- superZoomLevel float 30.0 (MO 2318) vs zoomRatioRange [0.5, 20.0] (MO 1838).

Results:
- com.lenovo.moto.in-sensor-crop.enable byte = 0 at 1x (MO 3489, 3848).
- com.qti.sensorbps.mode_index = 1 (MO 3514).

Other:
- SensorModeTable (MO 2252): mode 0 = 8192x6144 @21; modes 1/5/6/9 = 4096x3072 @30.
- No rawCropRegion, no CROPPED_RAW (MO 1895).
- quadra_cfa.is_remosaic_by_hw=0.
- recommendation: Test on main module 0 or 4:
1. Session EnableInsensorZoom=1 (then plus super_zoom=1), logical viewfinder at zoomRatio 2.0, RAW 4096x3072.
2. Log in-sensor-crop.enable and sensorbps.mode_index per frame.
3. When the flag is 1, treat the RAW as a 2x crop (CroppedRaw factor path keyed on that vendor result) and do not crop again.
4. If the tele is used through a logical camera, set SATFallback.T2W=0.
- risk: EnableSnapshotOnlyInsensorZoom suggests ISZ may be YUV/snapshot only. The crop RAW may be non-remosaiced quad CFA. The Moto HAL dropped frames when 62 RAW / 20 YUV buffers were held (MO 4113-4121).

## P10 all three - Measured ZSL ring depths and buffer limits
- theme: ZSL
- confidence: verified
- evidence: - Pixel 0: RAW_SENSOR 4080x3072 30 fps stall 0. Ring 60 OK, limited by maxImages 62, 23.9 MB/frame. Hold test 45 frames, 0 failures (PX 1666-1689).
- S24U 0: ring 56 (fails at 57). Holding 62 RAW gave 193 capture failures (SS 5417-5443). Hidden IDs: 57.
- Moto 0: ring 57 (fails at 58). RAW hold 61 failures; YUV hold stalled at 20 (MO 4113-4138).
- recommendation: Ring caps:
- Pixel ≤ 60 binned; about 8 at full-res RAW10 50 MP (62.7 MB per frame).
- S24U ≤ 56.
- Moto ≤ 57.
Keep releasing ring buffers during the shot (copy out). On Moto never hold more than about 20 HAL buffers while streaming.
- risk: Ring depths were measured in dim indoor light (Pixel ring ran at about 15 fps). Memory at full-res is untested.

## P11 Galaxy S24 Ultra + Motorola Edge 50 Pro - No full-resolution RAW via public Camera2 on S24U or Moto
- theme: full-res
- confidence: verified
- evidence: S24U:
- No streamConfigurationMapMaximumResolution on any camera.
- pixelArraySizeMaximumResolution = binned 4080x3060 (SS 14586, 24056, 30911).
- Vendor static 0x81200021 (name unknown) = {20, 16320x12240, 1, 37(RAW10); 20, 8160x6120, 0, 37; 20, 4080x3060, 2, 32; 52, 3648x2736, 2, 32} (SS 2791). This hints at Samsung-private 200 MP / 50 MP RAW10 configs.

Moto:
- No max-res maps.
- 8192x6144 exists only as SensorModeTable mode 0 @21 fps (MO 2252) and remosaic YUV (quadra_cfa.high_resolution_sizes, is_remosaic_yuv_output_supported=1).
- recommendation: Keep the full-res button Pixel-only. Do not expose it on S24U or Moto. The Samsung 0x81200021 configs need the vendor tag name (registry not reachable, no root) before any attempt.
- risk: None for SCAMERA as is.

## P12 Pixel 10 Pro XL - Vendor binning/HDR+ session keys and a default EIS-ON template are worth a test matrix
- theme: other
- confidence: inferred
- evidence: Session keys:
- com.google.internal.SensorBinningMode byte (PX 828), with persist.vendor.camera.allow_external_binning_setting=true (PX 29377).
- com.google.pixel.experimental2019.SensorModeFullFov (PX 840).
- com.google.pixel.experimental2017.request.disable_hdrplus (PX 839).

Result key: com.google.pixel.experimental2025.sensorBinningFactor (PX 819), not populated in the live results.

Templates: TEMPLATE_PREVIEW and STILL_CAPTURE default videoStabilizationMode=1(ON) (PX 926, 980).
- recommendation: - Try SensorBinningMode (0/1/2) as a session parameter on the main as an alternative unbinned/crop route. Log sensorBinningFactor and the RAW size and levels.
- Set CONTROL_VIDEO_STABILIZATION_MODE OFF explicitly in Pixel RAW sessions if the viewfinder field must match the RAW.
- risk: Semantics unknown. Session parameters next to RAW streams have killed HALs on other brands (CaptureController note, P54d).

## notes
Full Markdown report: C:\Users\MECHREVO\AppData\Local\Temp\claude\C--Users-MECHREVO-Downloads-x200u\9ac031cc-caab-40dc-bb85-1504e57b4cb6\scratchpad\reports\pixel_samsung_moto.md.

File abbreviations:
- PX = SCAM-TEST_Google-Pixel_10_Pro_XL_20261012-172642.txt (v1.13, root)
- SS = SCAM-TEST_samsung-SM-S928B_20260518-132600.txt (v1.11, no root, run under spoofed package com.ss.android.ugc.aweme)
- MO = SCAM-TEST_motorola-motorola_edge_50_pro_20261011-172952.txt (v1.11, root)

Camera maps:
- Pixel: logical 0 = [2 main DCG 4095/256; 5 main 2x crop; 3 UW; 9 UW 2x crop; 4 tele 4.5x; 6 tele 2x crop = 9.0x]. Hidden IDs 2-9 cannot be opened directly (CAMERA_DISCONNECTED, PX 12678-12687).
- S24U: logical 0 = [2, 5, 6, 7]. Hidden logicals 20/21/23/24 and physicals 52/54/56/58 opened only under the spoofed package; 5/6/7 refused.
- Moto: logical 0 (3rd-party) and 4 = [2 OV50E, 3 HI1336, 5 S5K3K1]. All hidden IDs open directly.

Existing SCAMERA code checked:
- PixelPro10 (ensureModules is correct: it uses physical size, and intrinsics of 5/6/9 are copies of the parent lens).
- CroppedRaw, IszModule (main only), RawUnpack.shiftFor, ScamHybridBurst.validNoise, ExposureRanges.vendorExposureMax, StabilizationTrace.

Not covered by the reports: zoom sweeps, actual switching points (only geometry and zoomRatioRange are verified), physical-stream session support, and sessions with vendor session keys. Those items are marked inferred.