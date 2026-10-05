# Hybrid final resize (CPU fallback)

The vivo upscale (RAISR, SoftPQE, VSR) and the Lanczos downscale after it were removed in the settings cleanup
(October 2026). What remains of this module is `VivoPostDownscale.resizeTo`: the CPU fallback of the hybrid's final
resize (`HybridFinalResize` on the GPU is the normal path), with the kernel from `pref_lmc_hybrid_downsampler`
(Lanczos-3, Lanczos-2, area, bilinear). `lanczos-downscale.h` is checked by `tools/check_lanczos_downscale.py`.
