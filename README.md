# SCAMERA

Android RAW camera with multi-frame processing.

- **SCAM Hybrid** — the default route on every phone: a zero-shutter-lag RAW burst plus a short exposure bracket, merged on the
  GPU by a separate worker process (alignment, robust merge, highlight frames), then the ARK tone, denoise and sharpening.
- **SCAM HDR** — the neural route on Snapdragon 8 Elite phones (NPU models), with the same capture.
- **«Движение» (Motion)** — the frames of the moment of the press, a shutter that follows the motion in the scene and a fast series.
- Output: JPEG (jpegli), 10-bit HEIC, AVIF, WebP, DNG and Ultra HDR gain maps; Display P3 or sRGB.
- Per-lens modules (vendor tags, in-sensor zoom), manual controls, an in-app gallery.

## Building

The APK is built by GitHub Actions (`.github/workflows/build-test-apk.yml`): unit tests, native checks, `assembleDebug`, then
`tools/package_scam_neural.py` adds the worker and the model bundle and signs the APK. The release key is not in the repository:
the workflow reads it from the `SCAMERA_SIGNING_KEYSTORE` / `SCAMERA_SIGNING_PASSWORD` secrets (`key/README.md`).

## License

SCAMERA is free software, licensed under the GNU General Public License v3.0 (`LICENSE`).

It started as a fork of PhotonCamera (Copyright (C) 2020-2022 Eszdman and contributors, GPL-3.0); the files that still carry
PhotonCamera code keep their copyright notices. Third-party components are listed in `THIRD_PARTY_NOTICES.md`.
