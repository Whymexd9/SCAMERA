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
