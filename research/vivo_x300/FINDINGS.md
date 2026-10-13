# vivo X300 (V2515, PD2509, MT6993) — root dump findings (2026-10-13)

Inputs from the owner's rooted X300: `x300_camera_cfg.tgz` (/odm/etc/camera, /vendor/etc/camera), `x300_dumpsys_2x.txt`,
`x300_dumpsys_4x.txt`, `x300_props.txt`, `x300_stock_zoom.txt`.

## Device
- `ro.product.device` V2515, build `PD2509CF_EX_A_16.0.24.0.W20`, `persist.vivo.vendor_dyn.name` PD2509F_EX (X300; PD2502 =
  X300 Pro shares the vendor files, the selector file is `sensormode-selector/PD2502.json` for both).
- Sensors (`persist.vendor.camera.sensor0..4`): main **S5KHP9 200 MP** (`s5khp9pd2509`), front JN1, tele **IMX885 3x**
  (`imx885pd2509`), ultra-wide JN1 (`s5kjn1widepd2509`), `imx885ultrapd2509` = the same tele in the **external teleconverter**
  profile (`persist.vendor.camera.rear_camera_tele3xexternal_*`).
- Camera ids (dumpsys): 0 = logical back (physical 2, 3, 4; `multiCamZoomSteps` 0.6 / 1 / 3), 1 front, 2 main (6.25 mm, 9.14 mm
  wide), 3 tele (13.3 mm), 4 ultra-wide (2.32 mm), 5 = tele with the teleconverter (31.25 mm, `com.vivo.RAWInfo[3]` = 1), 6 =
  second logical back. The stock camera opens **device 0** only (events log: CONNECT device 0 for com.android.camera).

## RAW
- Every camera: one RAW_SENSOR size (4080x3072 / 4096x3072 / 4080x3060), **10 bit** (white 1023, black 64), no
  MAXIMUM_RESOLUTION map: no 50 / 200 MP RAW through Camera2. `com.vivo.sensorModeSizeList` of the main goes to 16320x12288.
- Stream use cases: 0..5 + three vendor values (0x0800000000010000 ...); no CROPPED_RAW (6).

## Sensor modes (scenario tables, `#` = commented out)
- Main HP9 PHOTO_DEFAULT: BIN 0 (12.5 MP), FULL_BAYER 9 (50 MP HW remosaic, full FOV), FULL_16CELL 10 (200 MP), CROP_2X_4CELL 12
  (2x ROI), FULL_4CELL:50M 13, STAGGER_2EXP 19, LBMF_2EXP 27, FAEC 35, #CROP_4X_16CELL 15, #CROP_2X_BAYER 14.
  -> no 4x ISZ on the main in photo; 4x only `CROP_4X_BAYER 14` in ENGINEER_PHOTO.
- Main VIDEO_DEFAULT: DCG_AP_MERGE_2EXP_BAYER 23; VIDEO_60FPS: DCG 38; **3RD_APP_HDR: BIN 2 + FAEC 35 + DCG_AP_MERGE_2EXP 25**;
  PROTRAIT1X/2X: DCG 33.
- Tele IMX885: CROP_2X_4CELL 12, FULL_BAYER 9, LBMF 14; no 4x, no DCG.
- Ultra-wide JN1: BIN only (+ FULL_4CELL:50M 9 in REMOSAIC).

## Selector (`PD2502.json`, enum values of the result's SensorScenario: 1 BIN, 2 FULL_4CELL, 4 FULL_BAYER, 8 CROP_2X_4CELL,
## 9 STAGGER_2EXP, 17 DCG_2EXP)
- Third-party apps: only `Ratio <= 1600 => PHOTO(532)`, `>= 1777 => VIDEO(552)`; nothing routes to 3RD_APP_HDR.
- AppType vivo (our spoof): UIMode 1/13/70/74 or 7 > `vivo.control.seamless.roiRemosaic == 4` => ROIREMOSAIC (8);
  `remosaicenable 1` (+ `seamless.remosaic.enable 1`) => REMOSAIC / SATREMOSAIC; `session.seamlesscontrol 1 / 2` =>
  PHOTOHDR1 / PHOTOHDR2 (stagger 9). UIMode 0 > `vivo.control.sensorMode 1` => REMOSAIC (4).
- **DCG**: AppType vivo > UIMode 51/3/53/62/69 (video) > EISMode {0,1,4,9,12,14,16,18,19} > FrameRate <= 30 >
  `session.seamlesscontrol == 2` => DCGHDR2 (17, ratio 16:10, binning); `== 1` => DCGHDR1 = NORMAL1EXP (no DCG).
  SCAMERA's DCG switch sent 1 -> now 2 (P109i). The UI mode key is the session key `com.vivo.current_ui_module` (the stock
  SDK sends 1 in Photo); with 1 every SCAMERA session failed -38 (X200 Pro, V2515, X500 Pro Max).
- Main camera 2 lists `availableHdrModesPhoto` {0, 3, 1}, `availableVhdrModes` {8, 128, 0}, stagger {0, 8}: DCG (128) is a
  vHDR mode -> `hdrMode` / `SessionParamhdrMode` 3 is the vHDR request, 1 is photo HDR.

## Teleconverter camera 5
- Camera 5 is the tele sensor with the external teleconverter calibration (focal 31.25 mm, 6.97x,
  `com.vivo.chi.override.CameraType` «Tele3xLongExternal», `vivo.feedback.sensor_name` IMX885ULTRA_PD2509, only it has
  `com.vivo.ExternalTeleRawCropSize` 3360x2520). Owner: the X300 does not take the extender. P109j: CameraType «…External…»
  and «DUMMY» are auxiliary (not scanned; X500 Pro Max cameras 5 «Tele3P5xExternal», 6 «Tele3P5xExternal400», 9 «DUMMY»),
  their buttons hidden and their duplicates / ISZ modules removed; scam_dev vivo_teleconverter 1 keeps them.

## Stock-camera log
- `x300_stock_zoom.txt` covers 10:52:03-10:52:18 only and no camera open (logcat ended with Termux in the background). The two
  dumpsys files were taken with every camera closed (Termux in front), so they hold static metadata only.
- Re-capture recipe (root shell): `setsid nohup logcat -b all -v threadtime -f /sdcard/x300_stock_zoom.txt >/dev/null 2>&1 &`
  and a delayed dumpsys `setsid nohup sh -c 'sleep 25; dumpsys media.camera > /sdcard/x300_dumpsys_live.txt' >/dev/null 2>&1 &`
  then switch to the camera within 25 s.

## Test plan (owner, «Для разработчика»)
1. DCG on X500 Pro Max / X300 main: DCG button on, `dcg14_seamless 2` (default now), `vivo_ui_module 3`, `dcg14_hdr_mode 3`;
   expect "DCG14 camera 2 (DCG): sensorScenario [2, 17]" and dynamic white > 1023. If -38 / no frames: `vivo_ui_module -1`.
2. Same without the spoof (`dcg14_spoof 0`, `dcg14_hdr_mode 3`): third-party path, looking for 3RD_APP_HDR (DCG 25 / 33).
