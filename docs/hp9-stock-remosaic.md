# HP9 stock remosaic investigation

Target: telephoto 4x ISZ, same-colour 4x4 blocks (full CFA period 8x8),
4080x3072 input converted to ordinary Bayer at the same resolution.
This diagnostic change does not implement stock remosaic or improve image quality yet.

## Evidence from the supplied PD2454 firmware

The Vivo adapter explicitly compares the sensor name to `s5khp9` in
`SamsungHP3RemosaicIntegration::create`, as well as `s5khp3`.
The selected integration loads `libremosaic_wrapper.so`, whose camera-0
configuration loads `libremosaiclib_s5khp3.so`. Thus the HP3 filename alone
is not grounds to reject this library for HP9.

The supplied dependency archive includes `libdlrmsc_android15.so` and QNN
libraries. These are Android ARM64 binaries; successful execution on the
phone, linker visibility from an app, and DSP initialization remain untested.

The adapter's HP9/HP3 path calls `remosaic_gainmap_gen` with two buffers and
lengths 9216 and 38272 bytes. The data and format must be verified before
attempting the native call; zero-filled buffers are not a valid substitute.

The HAL contains the strings `remosaicEepromData`, `remosaicOtpData`, and
`remosaicSensorOtpData`. Their existence does not establish a Camera2
namespace, advertised Java type, payload layout, or third-party availability.

`sensormode-selector/PD2454.json` explicitly maps
`vivo.control.EngineerRemosaicMode` 1/2/3/4 to 2x2 SW / 2x2 HW / 4x4 SW /
4x4 HW. Those rules are conditional on the engineering app and internal
camera IDs. The diagnostic does not set this tag or equate these IDs with
Android camera IDs.

The wrapper recognizes 4080x3072 among its crop configurations. That is
not proof that passing an already cropped RAW selects the required ISZ mode.

## Supplied single-frame DNG

`IMG_260917_113150_656_001.dng`: 4080x3072, uint16 container, black level 64,
white level 1023, MotionCam Pro. Its tags advertise 2x2 BGGR, whereas the
raw samples exhibit same-colour 4x4 blocks with an 8x8 repeat. The observed
block ordering is BGGR. Do not use the advertised 2x2 tag to demosaic this
unconverted data. The colour matrices and lens gain maps do not provide
the two Samsung remosaic calibration buffers.

## Diagnostic capture

1. Install the diagnostic build and enable the existing Full debug switch.
2. Select the HP9 telephoto and the existing 4x ISZ configuration. Capture
   one ordinary still (single frame or burst; not the ZSL route).
3. Retrieve `Download/SCAMERA/SCAMERA-debug.log`. Search for `remosaic-probe`.
4. The block identifies the selected device/physical camera, crop, exposure,
   gains, matching characteristics, advertised request/result keys, and
   available physical capture results. Missing keys and null values are
   evidence of this API path only, not proof the HAL lacks calibration.

Collection is once per selected camera per debug session, on the debug
worker. Toggle Full debug off/on to sample another capture configuration.
No preview frames are inspected and no request controls are changed.

Byte-array candidates up to 65536 bytes each (262144 bytes total) are
recorded as Base64. Concatenate the `payload ` lines for a tag through
`payload-end` to recover its bytes. Larger values are explicitly omitted;
non-byte arrays have a labelled preview only. Never reinterpret an array
preview as a complete EEPROM dump. Calibration payloads should be kept in
the device log, not committed to this repository.

## Remaining gates before stock integration

- Confirm accessible calibration buffers and their exact format/offsets.
- Confirm native input/output packing, CFA enum, and crop/mode parameters
  specifically for an already cropped 4x ISZ frame.
- Verify loading and execution on the target phone, then compare the same
  single-frame RAW with the current remosaic at equal processing settings.
- Preserve per-frame remosaic before ESD4D merging and the existing guard
  against a second remosaic pass.

No improvement in detail or suppression of the grid is claimed by this probe.
