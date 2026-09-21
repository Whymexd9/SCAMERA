# NICE HDR scene selection and frame planning

`vivo-nice-scene-selector.h` connects the recovered scene selection, HDR
postprocessing, image-echo decision, tuning lookup and frame calculator.
`planNiceHdrSceneFrames` returns the selected mode, native scene flags, dual-RAW
type, image-echo policy, EV0-prefix policy and the frame-count/EV plan together.
It requires an already-enabled HDR capture (type 41 or 43).

This is not yet called by CaptureController. It does not produce AE gain and
shutter arrays, acquire VCF2 buffers, or dispatch a model for a variable series.
The manual fixed-series path must not consume the zero-initialized gain/shutter
fields from this pre-AE frame plan. APK builds remain disabled for this branch.

## Native verification

Source: supplied PD2454 `libvivo.vaf.system.so`, SHA256
`3f4ee25636fb04023c338ded9f087734ab8aeb429c4e50adf19a822e5398c188`.

```
python tools/check_vivo_nice_scene_selector.py /path/to/libvivo.vaf.system.so
```

The oracle executes the **whole** `setDetectTypeForNICE` at `29bfb0`, including
its original stage, sunset, high-lux, motion, fallback-table and more-EV0 helpers.
10,000 cases match all three outputs: mode, dual-RAW type and more-EV0 flag.
All emitted modes 0 through 32 occur. Properties retain their original defaults;
notably the seven detector overrides default to -1, not zero. Only platform
property access, logging and exp2f are supplied by the harness. No branch or
scene decision is replaced.

8,314 of those cases also execute the complete original `postProcess` entry at
`29b9a0` for its enabled HDR branch, with preProcess-reset flags. They match the
64-bit scene word, motion class, force-back value, quick-night class, night flag,
extreme-scene hysteresis and image-echo output. Native helper calls execute in
the oracle, including the extreme-scene detector at `2bb3ac`.

Excluded from the portable entry point are factory self-test, property debug
overrides, the optional virtual callback controlled by scene+50, and non-HDR
capture types 40/42/44. Eligibility and sensor-quad normalization happen earlier
in the donor and are not claimed to be implemented by this header.

## Input provenance

These offsets document native domains, **not public Camera2 keys**. The selector
does not infer them from ISO or substitute an alignment-motion estimate.

| Input | Native location |
| --- | --- |
| captureType / UI flags | scene+1f0 / scene+234 |
| current quad / cached integer lux | decision+2d8 / decision+2dc |
| tuning quad / lens / UI mode | tuning+0 / preview+470 / preview+440 |
| AI scene / algorithm scene | preview+11ac / preview+5f38 (different fields) |
| seamless / HDR version / HDR type / dual type | preview+5f0c / +3c04 / +3c00 / +3bfc |
| lux / zoom / motion | preview+43c / scene+90 / preview+3ae0 |
| motion thresholds | scene.motionTuning+0 / +4 |
| exposure index / time / sensor gain / reference gain / HDR gain | preview+404 / +41c / +420 / +414 / +428 |
| exposure bias / sunset score | preview+4a0 / +4d0 |
| manual exposure / stage flag / force-back flag | scene+1b0 / +185 / +186 |
| cached fast-night / quick-night mode | decision+2c8 / scene+134 == 1 |
| motion portrait enabled / flags / threshold / state | scene+1b8 / +d4 / +1c0 / preview+328 |
| live photo | preview+3c10 |

The portable `tripod` input is the result of native `isTripodOn`; the oracle
exercises its explicit scene tripod-status branch. The sensor-driven automatic
tripod detector is not replaced with a guessed value.

## Details that affect capture

- SAT modes select 17–22 independently of the binning/quad branches.
- The quad branch handles force-back differently from the binning branch.
- Manual-exposure comparison truncates nanoseconds to integer milliseconds.
- The fallback lambda `29ed74` tests the **last EV vector element**. It always
  uses the single-group member layout and scans modes 0 through 33 in order.
  Failure to find a final EV0 returns mode 0. An empty initial vector is rejected
  in the port instead of reproducing the native out-of-bounds read.
- HDR-type 2 in the binning branch changes dual-RAW type to 2 before calculating
  the more-EV0 policy and postprocess flags.
- Extreme-scene hysteresis uses the previous extreme state and a strict lux
  comparison. Tripod and extreme flags share bits but remain separate inputs.
- Scene flags at scene+260 and `NiceDetectResult.flags` are separate contracts;
  the frame calculator supplies the latter. Do not interchange these words.

## Stock Android boundary recovered alongside this work

The complete stock APK SHA256 is
`aaf998e96056b6c56b01d0fbd9b962ed281c8d61210895acc11b4cc7a98f9bdd`.
Its classes8.dex defines the VCF2 request wrapper and capture callbacks.

`VOuterCaptureRequest.Builder` transforms RequestLeftInThisSnapshot from the
request only for versions below VCF2. On VCF2, `onCaptureProgressed` reads the
Integer[] from the **partial capture result** and calls `updateZslNumber` before
`onVifCapturePrepared`. Missing result counts default to 0 past / 1 future.
This differs from the legacy constructor default with enabled/unspecified ZSL.

`Vcf2SnapController.setCaptureNumbers` also reads result counts and sums them,
falling back to one if absent. TotalCaptureResult completion alone does not
finish the VIF path: its separate completion state waits for three events.
Consequently the legacy request bridge cannot simply be treated as the VCF2
submission/queue implementation. Callback ordering and actual RAW delivery
must be connected before replacing the fixed capture controller.
