# SCAM HDR on Quad / Tetra streams (ISZ modules)

The ISZ modules of the tele lens (vendor `vivo.control.forceSensorMode`: 7 = 4x ISZ Tetra 4x4, 5 = 2x ISZ Quad 2x2)
deliver the RAW as a colour-block mosaic (4080x3072, uint16) that the camera characteristics still advertise as
an ordinary 2x2 pattern. SCAM HDR (the NICE transport, the merge and the network) reads plain bayer only, so on such a
stream it used to produce a magenta image with a dot lattice. The ZSL ring and the S / ES frames work in these modes
(measured: 49 matched RAWs, S and ES arrive in the same sensor mode), only the mosaic has to be rearranged first.

## Setting

`SCAM HDR — мозаика Quad / Tetra (ISZ)` (`pref_vivo_nice_mosaic`, per module, default `off`):

| value | what runs |
| --- | --- |
| `off` | as before: SCAM HDR needs plain bayer; the stored remosaic / HexQuad / Quad / MFSR switches of the module work as before |
| `scamera` | every frame (N, L, S, ES) through the GPU remosaic (`RemosaicCore`, backend SCAMERA), in place |
| `detail` | the same with Tetra Detail v2 (Tetra 4x4 only; Quad falls back to SCAMERA) |
| `mfr` | the equal-exposure N frames merged by Multi-frame Remosaic (CPU) into one plain-bayer frame |
| `neural` | the N frames through the NPU Quad 2x2 / HP9 HexQuad model (`HexQuadBurst.processForNice`) into one plain-bayer frame |
| `sabre` | `scamera` for the network, the alignment and the robustness, but the Sabre merge takes its donor sites straight from the frames' own mosaic samples (the kernel regression over the raw Quad / Tetra sites; up to 16 N frames, per-site-class response correction) |
| `neural_sabre` | the same with the neural merged frame as the N reference (it is registered to the reference frame's geometry) |

In `mfr` / `neural` the merged frame replaces the buffer of the N reference slot (the oldest of the four newest N), the
three newest other N stay as GPU-remosaiced donors, older N are dropped (they are in the merged frame), S / ES / L take the
GPU remosaic. The neural output (normalized linear bayer16, black 0 / white 65535) is converted back to the frames' black
and white levels (`mosaic_gain` dev key, default 1). Tetra needs 6+ N of one exposure/ISO, Quad 4+, MFR 3+ (the setting
`SCAM HDR — N-кадров из ZSL` should be 6..30). A merged mode that cannot run falls back to the GPU remosaic of every frame
(log tag `NICE_MOSAIC`).

The block size comes from the module's forced sensor mode (`ModuleRegistry.sensorMode`), else from the remosaic block
setting; the dev key `mosaic_block` (2 / 4, `nice_dev.txt`) overrides it.

## How it plugs in

`PreferenceKeys.isNiceMosaic()` (mode != off, SCAM HDR on, MFSR off) makes `isRemosaicEnabled()` return false, so every
other branch (capture plan, shutter queue, `quadCfa`, `isVivoHdrEnabled`, ESD4D, HexQuad capture) sees a plain-bayer
module, and gives `getRemosaicBlockSize()` / `getRemosaicBackend()` the values above for `RemosaicCore`.
`PreferenceKeys.mosaicBlock()` is the block for statistics and the raw viewfinder (a correct viewfinder colour on ISZ).
`HdrxProcessor` calls `VivoNiceMosaic.prepare()` before `VivoNiceBurst.process()`; afterwards `cfaPattern` is the emitted
plain pattern, `quadCfa=false`, `remosaicDone=true`, so the DNG of the reference is a valid plain-bayer DNG.

## Measured (phone on a stand, 12 N, 10x Tetra / 6.7x Quad)

* colour and brightness equal in all modes (Y within 1 %, R/G and B/G within 0.02-0.04 of each other);
* GPU remosaic of 14 frames 1.8-3.8 s; MFR 12 N 11-12.5 s; neural 12 N: Tetra 13.9 s, Quad 6.5 s; the rest of SCAM HDR 4-5 s;
* detail: neural (cleanest, sharpest) > detail ~ scamera (grainier) > mfr (softer) at 10x;
* not tested: handheld / moving scenes, other ISO.

## Sabre over the mosaic sites (`sabre`, `neural_sabre`)

The plain-bayer remosaic of every frame still feeds the network, the alignment and the robustness maps, but each N frame also
keeps a copy of its own mosaic samples (`ImageFrame.mosaic`, written after the plain frames; header tuning float 7 = block).
In the native merge (`vivo-nice-superres-gpu.h`) the donor / reference sites are read with `sampleMosaic`: the same 4x4 site
window as the Bayer lattice loops, every site with the colour of its own block, residual against the model of that colour,
response corrected by a 64-class table (`mosaicSiteGain`, class mean against the colour mean, estimated on the reference frame).
The merge only runs when Luma / Chroma are not both 1 (the tuned profile has 0 / 0) and extras are present. Not yet measured
handheld; on a tripod the colour gaps are filled only by the model, and isolated dark specks were seen on flat areas in one shot.

## Night on the ISZ modules (2026-10-02, 10x Tetra, ship's deck under lamps, phone on a stand)

* The SCAMERA planner threw "длинный кадр L не набирает экспозицию N" on the ISZ tele at night and no photo was taken: the
  vendor AE runs N at a gain far above the Camera2 sensitivity range of that mode (true ISO 3500, Camera2 ISO 800), so the
  longest shutter at the top ISO stays darker than N. `Plan.longBeyondSensor` now keeps L at the sensor maximum and the capture
  builds the long frame from the ZSL N frames at the planned ratio (`pref_nice_zsl_long` or not); the stock-solver plan already
  clamped L to N. Every mosaic mode shoots again at night on 10x and 6.7x.
* `sabre` rendered a magenta lattice on flat walls: the "fast" donor remosaic (nearest, no steering) of the previous round
  reached the N reference when it was not first in the frame list, so the network and the merge model carried a block-interpolated
  Tetra pattern. The fast path is gone (it saved 0.7 s of 2.7 s); in the merge a colour without donor weight at an output pixel
  (one colour per block window on a still burst) now follows the relative residual of the covered colours instead of its own.
* Times with 12 N: `scamera` 2.7 s remosaic + 5.3 s NICE (15 s shutter to file); `sabre` 2.0 + 5.2 (16 s); `neural` 13.7 s
  neural + 1.3 s GPU + 3.6 s NICE (25 s); `neural_sabre` 8 s neural on 6 N (the model gets just the frames it needs when the
  Sabre merge keeps the mosaic donors) + 2.8 s GPU + 5.8 s NICE (25 s); 6.7x Quad `neural` 6.4 + 0.8 + 3.7 s.
* Detail against the old non-HDR HexQuad neural remosaic of the same module (its stored profile, 30 frames, 12 s): the old
  route is sharp but far noisier (flat-wall σ 40 against 20..23) and has no HDR; among the SCAM HDR modes `scamera` and `sabre`
  keep the most micro-detail (Laplacian variance on text 755 / 1176), `neural` and `neural_sabre` are the cleanest and the
  softest (150 / 180; the text stays legible). Hand-held and daylight not re-measured in this round.

## OPPO factory configuration

`DeviceDefaults` (Find X7 Ultra PHY110, Find X8 Ultra PKJ110) applies once per defaults version, at the first start after
install or update: Autonomous HDR + SCAM HDR RAW on, bracket planner SCAMERA, 20 N frames and the tuned merge / denoise /
tone set of the vivo main camera, into the main preferences, the shared baseline and every existing module profile.
Test on any device with an empty file `force-oppo-defaults` in the app's external files directory.

## Plain-photo fixes found in an OPPO X8 Ultra log

* The photo's exposure (EXIF, DNG) and the key of the noise store were those of the last burst callback, the 1/16520 s
  highlight frame; they are now those of the normal frames.
* The dynamic noise fit collapsed on a high-contrast daylight scene (slope at the 1e-10 floor, offset 1.4e-4 = a flat floor
  ~6x the real shot noise of the shadows): ES3D used a 5.6 px kernel and smeared them, and the store kept the collapse as its
  lowest sample. A collapsed fit is now refitted for the slope over the calibrated read noise (clamped to 0.25..4x the
  calibrated slope); the store version is 6 so collapsed samples saved by older builds are dropped.
