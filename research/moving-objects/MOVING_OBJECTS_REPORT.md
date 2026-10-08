# Moving objects on the native mosaic merge (Quad / Tetra ISZ)

Date: 2026-10-08. Owner's report: vivo X200 Ultra (V2454A), 4x ISZ (camera 5, Tetra, native mosaic T1), a moving
fishing boat. A "honeycomb" lattice showed around the boat and on the moving water next to it. The owner suspected the same on 2x ISZ.

## Shot

`IMG_20261008_141810.webp` / `NICE-20261008-141810` dump:
- ISO 50, 1/1660 s;
- `HYBRID MOSAIC NATIVE: block 4 (Tetra T1: native)`, 30 frames;
- 6.1 night point (key 28): Tetra kernel 1, edge scale 0.4, flat x4;
- merge factors fell from 0.85 to about 0.2 by frame 12 (average 0.31): the boat and the waves are rejected in most donors.

`vivo_4x_isz_before.jpg` is a 3x crop of the water next to the hull.

## Cause

On the native path the base frame's kernel never widened where donors were rejected:
- **Tetra:** the inherited "split" rule `b^2 F + b^2 - 1 < widenBelow` is `16 F + 15 < 4`, which is never true.
- **Quad:** the same rule is `4 F + 3 < 4`, which holds only below F = 0.25.

Where a moving object leaves the base alone (or with a few donors), its narrow native texture kernel (edge scale 0.4 at the night key)
samples one frame's sparse colour blocks:
- **Tetra:** a 4x4 block of each colour per 8x8 cell, so the output shows the 4/8 px block lattice.
- **Quad:** the same, finer (2 px).

The binned path avoids this with its widening and the colour-difference pass. Neither exists on the native path: the
colour-difference pass is off there (`native variants not built`).

## Fix

`mosaicNativeWiden 2` (new default): the base kernel sigma is scaled by `mix(M, 1, smoothstep(0, K, F))`, where F is the accepted
donor frames of the output pixel, the same sum the merge already computes. This applies in both native merges (`kHybMergeMosaic` and
`kHybMergeMosaicFast`), whatever `s61Mode` says.

| block | M (sigma x at F = 0) | K (no widening from) | keys |
|---|---|---|---|
| Quad | 2.5 | 4 frames | `mosaicNativeWidenMul`, `mosaicNativeWidenFrames` |
| Tetra | 3 | 8 frames | `mosaicTetraWidenMul`, `mosaicTetraWidenFrames` |

Rules 0 and 1 stay available. The plain-Bayer merge (`kHybMergeMain1` and the binned passes) is untouched.

## Test bursts

The synthetic bursts of P29 (`syn_b2.nch`, `syn_b4.nch`: 16 frames, ground truth `syn_gt.npy`) were modified with `mkmove.py`. In
three rectangles every donor holds its own mosaic from 64 f px further right: an object moving between frames, with the base
unchanged. Variants:
- `syn_b4m` / `syn_b2m`: every donor moved, F ≈ 0;
- `syn_b4p2`: 2 donors static;
- `syn_b4p6`: 6 donors static.

Each burst was replayed on the OPPO PHY110 (Adreno 750) with the worker and the vivo shot's own tuning file (`rp.sh`).
`score3.py` gives luma PSNR against the ground truth, as full band / low-passed (Gaussian 1.5 px). The low-passed number shows
the block lattice without the aliased zone-plate tops that no single frame resolves.

| burst | region | before | after (default) |
|---|---|---|---|
| Tetra, all donors moved | moving zone plate | 20.27 / 33.53 | 19.83 / **36.88** |
| Tetra, 2 static donors | moving zone plate | 20.00 / 33.64 | 19.76 / **36.23** |
| Tetra, 6 static donors | moving zone plate | 20.07 / 34.28 | 19.83 / **35.75** |
| Tetra, static burst | zone plate (colour) | 23.96 / 32.67 | 23.92 / 32.98 |
| Tetra, static burst | B/W edges | 42.36 / 48.12 | 42.36 / 48.12 |
| Quad, all donors moved | moving neutral bars | 14.52 / 25.58 | 14.57 / 25.62 |
| Quad, all donors moved | static bars | 18.27 / 43.23 | 18.60 / **44.41** |
| Quad, static burst | B/W edges | 45.89 / 53.06 | 45.89 / 53.06 |

`synthetic_crops.png` (contrast-stretched 4x crops; rows: zone plate, zone plate 2, bars) has six columns:

| column | burst | worker |
|---|---|---|
| 1 | Tetra static | before |
| 2 | Tetra moving | before |
| 3 | Tetra moving | after |
| 4 | Quad static | before |
| 5 | Quad moving | before |
| 6 | Quad moving | after |

Moving Tetra areas before the fix show the honeycomb; after the fix they show clean, softer waves. The Quad moving areas lose the
fine ripple and the broken blocks.

Sweep on the same bursts (Tetra M / K: 2.5 / 6, 3 / 5, 3 / 8, 3.5 / 8; Quad: 2 / 4, 2.5 / 4, 3 / 4, 2.5 / 6):
- **Tetra:** 3.5 / 8 scores higher on the low-passed number (38.0) by blurring more. 3 / 8 is the chosen balance.
- **Quad:** the variants differ by ≤ 0.1 dB.

What the change costs is detail inside the moving object itself, where one frame cannot resolve it anyway. Static areas whose donors
are partly rejected (aliasing, F < K) also become slightly softer: on the static Tetra burst the full-band PSNR moved by -0.04 to
-0.16 dB, while the low-passed PSNR rose by 0.3 to 0.6 dB.

## Open

- A real handheld moving-object burst (NCH) from the vivo for a device check; this shot's dump holds only previews.
- A native colour-difference pass (`chromaDiff` on the native grid) would sharpen the moving object's luma at the same chroma.
