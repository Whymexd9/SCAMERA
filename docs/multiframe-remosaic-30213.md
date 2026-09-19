# Multi-frame Remosaic — APK analysis and SCAMERA integration (30213)

## Provenance and findings

Analyzed the user-supplied `Multi-frame Remosaic.apk` before integrating it.
APK SHA-256: `fa87bb606ed7cd998c7d509f4e9fdb1aa1c6d4bb48df316047ef30301f0f3af3`.
The APK's remosaic Java lives in classes20.dex, capture in classes19.dex, camera UI in classes9.dex.
Its arm64 and armv7 libraries are byte-identical to the previously supplied port archive.
Bundled arm64 library SHA-256: `efeb6894c95bd5b515ecf8894ffeed3729282346621be46f9b470d9e334d27b7`.
Only ARM64 is included because this camera build targets ARM64.

The binary implements CPU multi-frame RAW reconstruction, with quarter-size alignment guides,
affine registration, same-colour squared bilinear sample weights and robust median/MAD rejection.
The rejection threshold recovered from the machine code is clamp(5.9304 × MAD, 12, 80), in RAW code values.
Output dimensions equal input dimensions: this is not a separate 2× image upscaler.
No QNN or model files are required. The native C++ source is not present in the APK.

Useful original features: selectable CFA order, block2/block4, equal-exposure bursts, dark-frame FPN
profiles and red/blue radial scale correction. The original UI offers 10–40 frames (15 default)
and a 40-frame dark calibration sweep (10 exposure/ISO profiles × 4 frames).
Its hardcoded camera4/4096×3072 chromatic scales are device-specific and must not be copied globally.
Calibration actually uses the upper per-pixel median, not averaging: it removes a sparse median
baseline (stride251), then clamps signed residuals to ±1024.

## Integration

- Replaces the old MFSR execution path and its eight kernel/tensor controls.
- Whole burst enters the original JNI binary before ordinary ESD4D and colour processing.
  Ordinary merging and single-frame remosaic/HexQuad cannot run a second time on this result.
- Source choice: ordinary Bayer (block1), Quad2×2, Tetra4×4; CFA auto or RGGB/GRBG/GBRG/BGGR.
  These describe the RAW actually delivered by Camera2, not merely the physical sensor marketing name.
- Controls: 3–40 frames, FPN enable, one-shot dark calibration, red and blue scale .99–1.01.
  Source and controls participate in existing independent module profiles and selective copying.
  Calibration maps stay isolated by camera ID, dimensions, block and CFA; they are not copied.
- ZSL selects distinct timestamp-paired frames with identical measured ISO/exposure.
  PSL uses a fixed requested exposure for the entire burst; measured metadata is checked before JNI.
- RAW16 must be tightly packed, even-sized and divisible by the mosaic period. 4:3 and no software
  binning are required. Invalid data fails explicitly rather than running an unsafe native call.
- A 768MiB working budget estimates RAW16 frames + quarter-resolution float guides + output/FPN.
  It caps the number before allocating the capture reader/ring, e.g. a 50MP burst cannot hold15 frames.
  Other app/GPU allocations are additional; large-RAW memory and speed still require phone testing.
- New output uses the existing native allocator and goes through normal demosaic/colour/denoise,
  then optional Vivo upscaling and Lanczos downscaling. New MFSR itself retains RAW dimensions.
- Old source mosaic is preserved during profile migration; old MFSR control values are removed.

## Deliberate calibration difference

SCAMERA captures four dark RAWs at the current exposure/ISO when the one-shot switch is enabled.
It saves one profile without producing a photograph, then clears the switch. Repeat at other ISO
values to expand the bank. This replaces the original 40-frame automatic sweep to avoid excessive
memory/capture time. The nearest saved exposure/ISO profile is selected and ISO scale clamped .7–1.4.
Close the lens fully before pressing the shutter. Calibration actions are neither restored nor copied
with module profiles. Failed/aborted calibration reports an error and allows retrying.

## Verification and limits

`tools/multiframe/check_native.py` executes the original ARM64 functions in Unicorn with JNI/memory
adapters, preserving native alignment/reconstruction and C++ sorting code. All12 combinations of
three mosaic types and four CFA layouts preserve distinct constant colour channels exactly.
A seven-frame noisy Bayer fixture reduces flat-field variance from80.4447 to43.0654; native dark
calibration passes a signed-residual/outlier fixture. This establishes block1 support beyond the
original UI, not real-device image quality or motion/ghosting performance.

Android unit tests exercise actual menu inflation, removal of old controls, source persistence,
profile migration, reconstruction exclusivity, memory limits and measured-frame selection.
CI verifies native execution, unit tests, APK compilation and library packaging.
Phone testing is still required for each module's RAW mode, moving subjects, dark calibration,
first capture after changing settings, performance and comparison against ordinary merging.

## 30214: Bracketing alongside native MFSR

Requested after the 30213 build started. The delivered build is30214.
The normal-frame count remains local to MFSR; existing short/long counts and EV controls are enabled.
The general normal-frame and ZSL-ring controls remain inactive. Auxiliary counts are added to the
normal count within the same40-frame/memory budget. Normal frames can be reduced to3; if the
requested donors would leave fewer than3, capture reports that counts/resolution must be reduced.

With bracketing enabled the whole burst is captured after the shutter (PSL), at a fixed focus and
fixed exposure/ISO within each group. This avoids assigning fabricated metadata to ZSL donors.
The capture timestamps route preview/still RAW independently. Processing requires the metadata
of the middle normal frame and checks each measured exposure product against the corresponding
requested role before calling native code. DNG/JPEG exposure and noise metadata use the normal frame.

The native processor is invoked separately for each exposure/ISO/role group: the normal group
must contain at least3 real frames; donor groups can contain1. Every Quad/Tetra group is converted
to ordinary Bayer before HDR merging. No repeated copies of a single frame are manufactured.
Each output inherits the native reference (floor(N/2)) timestamp and gyro. The normal output stays
the HDR reference; the existing exposure-aware ESD4D alignment/fusion adds the long/short outputs
with its clipping, noise-floor, movement and highlight-strength gates. Native MFSR is never asked
to fuse different exposures, and the old MFSR kernel branch remains disabled. A second HDR+ denoise
pass is not selected for the native route. Final noise scaling uses the actual native normal count.
Calibration continues to use only equal-exposure dark frames, without bracket donors.

Additional checks: three mosaic types × three signal levels through the original one-frame native
function; grouping keeps different ISO/exposure/roles separate, rejects duplicate timestamps and
incomplete/variable normal groups; capture-budget tests cover normal+short+long at12 and50MP;
menu tests confirm independent counts and calibration restrictions. Phone tests must compare
bracketing off/on, 1 short, 1 long and both together, with a bright window and dark interior.
The existing HDR fusion's real-world highlight recovery and movement rejection still require testing.

## Superseded behavior in 30215

The single-profile calibration shortcut and full-PSL bracket capture described above
are replaced by the original 10-profile CAL bank and a timestamp-matched ZSL base.
See [30215 details](multiframe-remosaic-30215.md).
