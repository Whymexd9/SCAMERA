# SoftPQE SR-only — 30212

User request: remove SoftPQE denoising/sharpening and retain enlargement. This build removes controllable external stages; it does **not** claim to eliminate smoothing embedded in the pretrained Y super-resolution models.

Changes:
- In verified private XML copies, select documented `processMode=2` (Y only). Native UV denoising/model output is bypassed. Reconstruct V/U from the input using separable Lanczos 2, without filtering it through a denoising model or mixing in native UV.
- Disable native USM, sharpening and partial sharpening. Zero the post-sharpen weights in each profile that contains them.
- Set Y/aux-Y shot/read-noise conditioning to zero at every ISO/DRC level. Keep `maxNoise1Level=0.000001` because the vendor profile requires max > noise1. Skin overrides inherit absent auxiliary tables from the main profile.
- Remove our post-output USM, residual restoration and whole-output interpolation mix. Old NR/sharp/mix preferences no longer affect SoftPQE and are removed from its UI.
- Preserve Y/aux-Y model files, fake quantization, mandatory `blurScale=1`, ISO table and SR geometry. Main/aux SR fusion remains part of the network's reconstruction, not an extra unsharp-mask stage.
- Verify native `processMode` and sharpening flags after Init. Pinned main parser at 0x9f4b4 stores processMode at config+0x40, owned at handle+0x270, so read handle+0x2b0. Existing sharp flags: +0x2b8/+0x2bc/+0x2c0. `vivoSoftPQEGetMode` at 0x76814 reads +0x5dcc and is a separate bypass/status value; it must still be zero for successful inference.
- Keep independent Lanczos 2–5 final downscale (30211) and camera-resume fix (30210).

Diagnostics identify `SR-only v3`, read-back Y-only mode and native sharp flags. If the native configuration is ignored, fail safely and preserve the pre-Vivo image rather than calling it SR-only. On a successful call, native Y bytes are unchanged by our finishing pass; source UV interpolation replaces only the output chroma plane.

Tests: ASan/UBSan chroma implementation against a double-precision direct 2-D oracle; native Y identity, V/U ordering, constant fields, borders and output guards; actual supplied main/portrait/skin XML transformations; worker fault injection including ignored Y-only mode and sharp flags; menu removal, retained Lanczos preferences and camera-resume regression tests. These tests do not execute the real QNN model on a phone.

Detail improvement directions (not measured claims):
1. Start from minimally sharpened, well-exposed input: SoftPQE currently receives the completed RGB bitmap after the app's own tone/NR/sharp pipeline. Those independent user settings still apply.
2. Compare full ×2 output with Lanczos 2/3 at pre-Vivo dimensions. This may reduce reconstruction artifacts but does not add information beyond the SR result.
3. Future work: reduce RGB↔8-bit 4:2:0 losses, and evaluate moving SR before final sharpening. A genuinely denoise-free learned SR model would require suitable alternate weights/retraining, not a renamed slider or sharpening boost.

Phone test: same module/exposure/scene, SoftPQE on with final downscale off; compare original JPEGs to the previous version. Look for more preserved color texture/noise and fewer sharpening halos. Then enable Lanczos 3 to the pre-Vivo size. Include the new worker log if inference falls back or Y remains over-smoothed.
