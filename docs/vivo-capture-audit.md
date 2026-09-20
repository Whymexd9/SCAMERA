# NICE capture audit and internal controls

## Current capture versus stock

| Stage | SCAMERA active path | Recovered Vivo path / remaining dependency |
| --- | --- | --- |
| Normal RAWs | Latest up to four timestamp-matched buffered RAWs at or before the last preview result at shutter | Past/future counts from the scene/AE query; not a fixed four-frame rule |
| Empty or unmatched buffer | Manual post-shutter capture fallback | Stock scheduler behavior has not been reproduced |
| Long/short | Standard manual Camera2 requests after ZSL, long then short; SCAMERA EV/ratio limits | Vendor AE arrays and capture-control payloads from VCF |
| Reference | First normal input | CRE selects a reference before arranging model slots |
| Model | Fixed 4N/L/S/ES forward graph; missing N slots repeat, S may repeat into ES | Scene-dependent model and capture dispatch; arbitrary counts cannot be fed to this graph |
| Tone | SCAMERA VivoHdrTone | Original TCE is not connected; captured pointers alone cannot be replayed |

Timestamp provenance supports true buffered ZSL normals, not full stock
capture equivalence. Missing shutter sensor timestamps now exclude buffered
frames instead of bypassing the cutoff check. No sensor-clock/host-clock
subtraction is used. The stock one-for-one conversion remains blocked by
complete scene/AE query inputs and compatible variable-length model dispatch.
Do not submit partial vendor request payloads mixed with manual exposure.

## Diagnostic coverage

`NICE_CAPTURE`: shutter sensor cutoff, requested counts, submitted role and
exposure/ISO/AE/ZSL/frame duration; timestamp-matched actual exposure/ISO,
rolling shutter and available six vendor planning fields (absence explicit).
`NICE_PIPELINE`: seven selected slots, original timestamp, ZSL/PSL provenance,
duplicates, source counts, relative time, reference policy, active fixed graph,
TCE state, and separate postprocessing strengths.
Native worker v29: original/calibrated and effective noise coefficients, actual
VST settings, model, tile count, CPU alignment time, inference time and total
reconstruction time. Existing Pipeline timing logs cover executed GPU nodes.
All use the existing logger mirrored to SCAMERA full debug; disabling image
ZIP export does not disable text diagnostics. Logs are observations, not proof
of stock equivalence or calibrated image quality.

## Controls before inference

NCH v8 retains v7 length and stores normCoefficient/noiseScale as little-endian
floats in previously reserved base-header words 30/31. v1–v7 retain defaults.

- VST normalization coefficient: donor forward default 1.1. UI bounds
  0.55–2.2 are experimental adapter limits, not vendor-certified tuning limits.
- Noise variance multiplier: default 1, bounds 0.25–4; multiplies shot slope
  and read variance used to construct the forward and inverse VST. This is an
  adapter control over the noise model, not a recovered stock strength slider.
  Calibration inputs are preserved and logged separately. It does not disable
  learned denoising, offer independent luma/chroma controls, or change weights.
- Separate learned sharpening control is not established. No post-RGB shader
  is presented as a native NICE sharpening control. TCE XML controls remain
  unavailable until the original photo processing path is working.

Host tests establish header compatibility, rejection of malformed tuning,
actual changes to the 22-channel model input, and VST/IVST radiance round trips
with a mock graph. Photographic tuning quality and NPU behavior require device
validation. Defaults retain the previous computation.
