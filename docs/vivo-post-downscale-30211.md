# Lanczos after Vivo — 30211

Adds a final downscale to successful RAISR and SoftPQE output in HDRX, before Ultra HDR gain-map generation, overlays and JPEG encoding. The raw/DNG path is unaffected. A failed Vivo call skips this stage; a failed resize preserves the successful Vivo image.

Settings: Резкость и детализация → Масштабирование и сверхразрешение → Апскейл Vivo → Даунскейл после Vivo.

- Kernel: Off (default), Lanczos 2, 3, 4, 5. Number denotes the sinc window radius, not the reduction factor.
- Size: pre-Vivo dimensions (default), 75%, 2/3, 50%, 1/3, 25% of Vivo width/height. 50% per axis produces one quarter of its pixels.
- Both keys are module-local and part of the existing grouped copy catalog.
- Works after either successful Vivo backend, including SoftPQE's shared profile on different modules. Does not change existing Vivo backend/module availability.

Implementation: scale-expanded sinc(x) * sinc(x/a) window, pixel-center coordinates, normalized truncated edges, separable filtering in linear sRGB, float intermediates without between-pass clipping. Final output is opaque sRGB RGBA8. No bilinear substitution. Horizontal rows are cached only for the active vertical support, avoiding a full-size floating-point intermediate. Supports downscaling up to 4:1 per axis. Identity returns the existing bitmap. Larger kernels are slower and may ring more on strong edges.

Validation:
- Native production header compared with independent dense float64 NumPy evaluation for all four kernels, odd/small/asymmetric dimensions and reduction ratios.
- Constant colors/edges, identity, row padding, RGBA channels, high-frequency checkerboard attenuation, invalid geometries, and distinct kernel outputs checked.
- Android settings inflation/persistence/default/availability and output dimensions tested with Robolectric; existing camera-resume tests retained.
- Actions compiles and verifies packaging of liblanczosDownscale.so.

Device test: SoftPQE ×2 + Lanczos 3 + “Размер до апскейла Vivo” should restore the pre-Vivo image dimensions. Compare against Off, then try Lanczos 2/4/5. Check the VivoDownscale log for the actual kernel and input/output dimensions. Confirm a photo after a settings change does not restart the camera. No physical-device validation was performed here.
