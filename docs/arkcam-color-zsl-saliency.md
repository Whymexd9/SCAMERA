# ArkCam points 6–7: implemented scope and validation

Source: supplied ArkCam_v4.3prerel.apk, compared against SCAMERA e984e5e
(remote equivalent cdd537e). No dependency on GCam process hooks.

## Colour artifacts / highlights

The existing false-colour node now uses a 3×3, centre-inclusive componentwise
Oklab a/b median and purple/green high-contrast gating, adapted from
`quad_speckle_filter` / `ca_defringe` in libfc_suppressor.so's embedded OpenCL.
Input and output are separate textures; the donor's in-place write race is not
copied. Existing master/strength, purple/green and red/blue CA controls are reused.
Uniform colours and luminance are preserved. Negative chroma is gamut-compressed
rather than clipping the reconstructed highlights to 1.0.

SCAMERA already has RAW inpaint-opposed recovery in Bayer2Float/OpposedGL.
This change preserves its headroom through FalseColorSuppression; it does NOT
claim to port the donor's additional RGB opposed-diffusion pipeline. Applying its
scalar clip threshold after SCAMERA's tone mapping would identify the wrong
pixels. A future alternate recovery must share the RAW/WB clipping convention,
not stack a second display-space recovery onto the existing one. Fully clipped
RGB pixels have no real detail to recover from this filter.

## ZSL / MFSR frame selection

New per-module switch: «Отбор резких кадров ZSL / MFSR», OFF by default.
Own deterministic RAW16 heuristic, not Google BurstCurator. Samples ≤3072 sites
per frame, reading same-colour neighbours at CFA periods 2/4/8 (Bayer/Quad/Tetra),
respects row padding and buffer bounds, removes a robust gradient noise floor.
No full-frame copy is added to the ring. RAW10/12 use the existing latest-window
policy because their packed input is not interpreted as RAW16.

Only a contiguous chronological, exactly equal measured ISO/exposure series is
eligible. Search may shift its end at most 250 ms before the latest matched frame;
older frames remain subject to existing absolute age limits. Score combines
worst-frame and mean sharpness, penalises age and requires 10% improvement.
Insufficient/invalid quality returns the previous policy. It does not sort the
native MFSR inputs, change frame count or mix bracket roles. The normal ZSL path,
MFSR ZSL and MFSR normal frames ahead of bracket donors use the selector.
CAL and post-shutter donor capture are unchanged. Metadata paired by timestamp.
This estimates detail, not eye openness/face expression/object motion.

## Original Google saliency model

New per-module switch under false-colour correction:
«Защита деталей объекта — Google Saliency», OFF by default.
Loads the extracted model with the existing LiteRT CPU interpreter, 2 threads.
No new donor .so, JNI namespace, fixed offsets, protobuf ABI or delegate needed.

- Original libsaliency_predictor_jni.so model offset: 0x2f5a20.
- Complete verified FlatBuffer extent: 957744 bytes (tensor tables follow weights).
- SHA256: a0bf6e5c324ad3a24b345970beb861bbe53f0f99223ec61900183ff639bc864a.
- Float RGB [1,384,512,3], RGB channels bounded 0–1; float attention [1,384,512,1].
- Native donor preprocessing divides full-range RGB by 255. Its JNI method returns
  selected regions after additional processing, not the dense mask used here.
- GPU thumbnail uses the selected tone pipeline; camera rotation is applied before
  inference and inverted for mask sampling. No full-resolution readback.
- Attention reduces median chroma suppression by up to 65%, never changes luminance
  or boosts sharpness. This use of the original model is SCAMERA integration,
  not a claim that GCam uses exactly the same downstream processing.
- An inference exception logs failure and keeps the ordinary filter/photo.
- Photo pipeline only; this is not a real-time viewfinder inference feature.

This is an attention map, NOT a semantic mask of skin/sky/person. The separate
libsegmentation_predictor_jni.so embeds point2mask_objects_512x512_2023_04_18.tflite,
float [1,512,512,4] → [1,512,512,1]. The fourth input's prompt contract is not yet
confirmed. It is deliberately not shipped as an automatic semantic segmenter.
BurstCurator's original JNI requires serialized options, per-frame metadata and
policy protobufs plus strided YUV planes; its score schema is not confirmed.
It is not part of this implementation.

## Validation

- `tools/java/RawFrameQualityCheck.java`: sharp/blur/noise RAWs, all three mosaics,
  padded strides, buffer safety, temporal window, exposure boundary, unknown scores.
- Existing `HexQuadZslSelectorCheck`: age, timestamps, ISO/exposure mismatch.
- `tools/check_arkcam_color.py`: production GLES shaders execute on Mesa; disabled
  identity, colour outlier reduction, HDR headroom, luminance, flat saturated HDR,
  attention response, CA compile branches and four orientation mappings.
- `tools/check_saliency_model.py`: real CPU inference, complete FlatBuffer, tensor
  shapes, finite probabilities, spatial response to an object versus background.
- Android Java/resources compilation and settings/resume regression checks.

No physical Vivo was connected. Real motion, HAL timing, Adreno performance,
model quality on photographs and end-to-end appearance still need phone testing.
