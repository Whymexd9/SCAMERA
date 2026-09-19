# 30227 follow-up from SCAMERA(1).zip

The archive contains two original JPEGs and log-2026-09-20.txt. Both JPEG EXIF
records identify 0.98(30227), camera 3, 3072x4096 and 8 normal frames. The first
capture's complete processing log identifies 10 total RAWs (8 normal, one long,
one short), Vivo HDR enabled, RGB NR luma 0.6/chroma 1.0, and RT USM amount 2.0.
The log ends before the second capture's complete processing result. The legacy
EXIF DenoiseOn=false does not imply that the independent Vivo NR node was off.

Visible: substantial coloured shadow noise, locally uneven/noisy texture.
JPEGs alone do not establish which intermediate stage caused every artefact.
The previous fix did not resolve the user's complaint; no claim of complete
on-device repair is made here.

## Confirmed code defects and regression checks

- `Log.writeToFile` queried SAF accessible paths on the calling thread for every
  line, then resolved the directory/file again for each queued line. Sequential
  log records in this capture show approximately 23-27 ms spacing even for
  simple property enumeration. All destination changes, SAF queries, directory
  checks, writes and writer closing now belong to the writer thread. Cached
  open writer, bounded queue and 5-second failed-open retry prevent per-line
  provider calls and unbounded backlog. Storage error reporting avoids recursive
  use of the same logger. AsyncLogTest checks caller-side zero storage access,
  bounded stalled queue, and no repeated lookups with an open writer.
- The HDR flow consistency veto also rejected textureless noisy regions, where
  geometric displacement cannot be resolved. A production-shader fixture with
  eight static noisy frames and an inconsistent tile field previously retained
  single-frame noise (MSE ratio 1.000). The HDR-only correction measures local
  structure relative to sensor noise; it retains the geometric veto at visible
  structure/clipping and uses the existing radiometric motion rejection in flat
  regions. MSE ratio is 0.109 in this synthetic fixture, effective samples 7.98.
  A visibly different moving patch remains rejected. This is not an estimate
  of improvement on the phone. Existing highlight/clipping/colour tests pass.
- Next log reports average effective samples and single-frame fraction, besides
  the conservative minimum used by downstream denoising.

## NICE status

Original NICE context and pinned QNN assets are bundled in the delivered APK.
The separate NICE settings test now includes root execution through the same
worker mechanism as HTP remosaic. This does not enable NICE in photographs:
22-channel semantics, exact forward-HDR VST/IVST, alignment/routing and photo
quality still require verification. See vivo-nice-port.md.


## Highlight follow-up from 1000738692.jpg

The additional 1542x2048 JPEG visibly contains tan rectangular cells inside the
lamp diffuser. It has no EXIF/version metadata. This is a separate failure from
shadow grain; the initial JPEG review underemphasized it. Neither original
30227 JPEG contains MPF/Ultra HDR gain-map payload markers; `hasGainMap` in the
legacy EXIF description refers to lens shading, not proof of an Ultra HDR JPEG.
The new JPEG also has no gain-map payload. Do not attribute these blocks to an
HDR viewer without evidence.

A production-shader saturated-lamp fixture with corrupted flow in the clipped
reference reproduces switching between highlight recovery and fallback cells.
Added a coarse GPU flow completion pass: only coherent, photometrically valid
unclipped neighbours seed the saturated hole; conflicting neighbour motions
remain unresolved. It changes vectors only in saturated reference windows.
The bounded 32-step propagation uses GPU barriers without per-pass CPU stalls.
Synthetic central-lamp mean absolute error changes from 0.4125 to 0.000468.
Fully clipped frames without valid seeds behave exactly as before. Moving-object,
clipping and shadow regressions remain passing. Device confirmation is pending.

A second confirmed boundary bug clipped white-balanced values to 1 in
Bayer2Float and hard-coded AMaZE's clip threshold to 1. In Vivo HDR mode,
Bayer2Float now retains positive HDR radiance, skips a second single-frame
inpaint stage, and passes its WB/LSC domain bound to AMaZE. The actual conversion
shader regression retains R=2.0 and B=1.2; non-HDR clamp behaviour is unchanged.
These tests establish corrected mechanisms, not a claim that every artefact in
the user's original RAW burst has been eliminated without reproducing it.
