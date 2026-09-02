# SCAMERAC

Experimental computational RAW camera for Android, based on
[PhotonCamera](https://github.com/eszdman/PhotonCamera) and HDR+ concepts.

> **Development status:** early test build. Public releases and downloadable
> APK files are intentionally not published yet.

## What is different

- ZSL and Night capture modes focused on multi-frame RAW photography.
- Configurable normal, short and long exposure frame counts.
- Adjustable exposure offsets for short and long frames.
- Highlight recovery control from `0` (ignore short frames) to `200`.
- Exposure-normalized short/normal/long RAW merging.
- Optional Qualcomm HTP/DSP-targeted AI Bayer denoising.
- Fast UNet and quality NAFNet denoising models.
- Independent AI Luma, Chroma and overall-strength controls.
- Independently switchable PhotonCamera Luma, Chroma and moiré reduction.
- Selectable original PhotonCamera or configurable Luma sharpening pipeline.

## Hardware Sharpening (Luma)

The configurable pipeline exposes eight independent groups with precise manual
numeric input:

1. Lens Deblur (Pillbox)
2. Gaussian Unsharp Mask
3. Smart Edge (Sobel Mask)
4. Bilateral Sharpening
5. Guided Filter (Local Contrast & Shadows)
6. Texture Restoration (Anti-Watercolor)
7. Richardson–Lucy Deconvolution
8. Tonal Protection (Smart Fade)

## Qualcomm NPU status

The current experimental implementation selects a Qualcomm NNAPI accelerator
by its exact device name and disables the explicit CPU fallback. The runtime is
kept alive between captures to avoid recompiling the model for every photo.
Direct QNN/QAIRT HTP integration is planned after device compatibility testing.

## Building

Requirements:

- JDK 17
- Android SDK 36
- Android NDK and CMake configured for the project

Build the debug APK:

```bash
./gradlew assembleDebug
```

## Credits

SCAMERAC is a modified fork of
[PhotonCamera by Eszdman/ParticlesDevs](https://github.com/eszdman/PhotonCamera).
AI denoising work is derived from the open-source
[Raspberry Pi AI denoise](https://github.com/raspberrypi/AI_denoise) project.

## License

Licensed under the GNU General Public License v3.0 or later. Original
PhotonCamera copyright notices are retained.
