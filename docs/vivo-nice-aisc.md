# NICE AISC result processing

The additional phone libraries are pinned to:

| Library | SHA256 |
| --- | --- |
| libvivo_hdr_aisc.so | b49923bb8419dfc870d5a060ca5912807ad0342ae442f532fd64977be6a5f573 |
| libbacklight.so | 8cd91510f1fb8d5299540c7bc0f827909dfd0b1a16c078923fb08bb96c34dd0b |

`vivo-nice-aisc.h` ports the complete result postprocessor at
libvivo_hdr_aisc.so 0x7924..0x7d10. It consumes two network outputs, of seven
scene values and two lighting values. With NeedSoftmax, the original applies
expf and a left-to-right float sum separately to each tensor. No max-subtraction
is introduced. Invalid/nonfinite or overflowing softmax inputs are rejected by
the portable adapter instead of publishing invalid scores.

The 324-byte output has seven 36-byte records (32-byte name plus float score),
a 30-byte category, a 30-byte accepted label, confidence at 0x138, lighting score
at 0x13c, and count 7 at 0x140. Storage starts zeroed, matching the scene caller.
The first strictly greatest score wins; the initial maximum is zero. The label
requires confidence >= its class threshold; category and lighting do not depend
on whether the label passed that threshold.

| Index | Name | Label threshold | Category |
| --- | --- | --- | --- |
| 0 | indoor | 0.75 | indoor |
| 1 | store | 0 | indoor |
| 2 | landscape | 0.65 | outdoor |
| 3 | night | 0.75 | night |
| 4 | snow | 0.5 | outdoor |
| 5 | caixia | 0.78 | outdoor |
| 6 | sunset | 0.85 | outdoor |

For classes 5/6 the lighting score is forced to float 0.9. Other classes use the
second lighting-tensor value. The scene wrapper at 0x12974..0x12bb8 in
libvivo.vaf.algo.scenedetect.so tests this score strictly greater than float 0.7,
copies the 324-byte result to preview+0x102c on successful inference, and returns
false without copying on inference error. A failure is not a new negative
classification and must not be used as valid fresh metadata.

## Verification

```
python3 tools/check_vivo_nice_aisc.py /path/libvivo_hdr_aisc.so
```

1,151 comparisons execute the entire original postprocessor and compare all
324 output bytes, including softmax on/off, class thresholds and ties. External
tensor-host-pointer access, expf and strcpy are provided by the test harness;
network inference is not stubbed into a claimed end-to-end test. Invalid outputs
are rejected without modifying the caller's destination.

## Runtime assets and remaining integration

`HdrAISCConfig.xml` was recovered from the earlier vivo-nr-analysis archive:
CPU device 0, one thread, NeedSoftmax=1, input `data`, outputs `conv2d/116` and
`conv2d/118`, ImageMean=127.5, ImageStd=0.00784313, AsyncMode=1, AsyncFreq=3.
It names `/vendor/camera3rd/nti/pj_hdrsc_light_2_t6_1_vdnn.bin`. The model was subsequently supplied and its structural validation is described below. libvdnn.so was recovered from
the earlier VivoCamera-app-libs archive (SHA256
7b6d50da0d022af4c91fd611b63f5f12dc3304de82566af07c84734d4a385cdf);
its compatibility with the vendor AISC runtime has not been established.

The asynchronous path at 0x7ff0 returns cached results while scheduling work
according to the configured interval. The scene result cannot be claimed to
belong to the current RAW without establishing that association.

This header is not yet called from the app. Missing model inference, full scene
mode selection, measured exposure-code consumption and variable NICE graph
routing still prevent an end-to-end stock ZSL/bracket claim. No APK is produced
by these host checks, and neither image quality nor camera stability is tested.

## Supplied AISC model

The received model is 1,617,496 bytes; SHA256
`b05e37e0fc04701cbb978254c613ef500c1a44337cdefce9372b1c2136a3a4a4`.
It has input `data`, dimensions [1,160,160,3], 66 named tensors and 67
graph records: one input, 54 convolution records, 10 residual-add records,
one global-mean record and one output record. The output record consumes
tensors 64/65 (`conv2d/116` and `conv2d/118`). Their weight dimensions are
[7,1,1,1280] and [2,1,1,1280], agreeing with the seven/two AISC outputs.

`tools/inspect_vivo_aisc_model.py` checks the pinned hash, bounded FlatBuffer
accesses, tensor dependencies, unique producers and all 54 weight-array sizes
against their dimensions (393,568 weight elements total). It rejects unknown
model revisions instead of guessing a schema. This is structural inspection,
not evidence that the selected libvdnn.so executes the model correctly.
No runtime has yet executed this model in this workspace. The source header
remains disconnected from CaptureController; full integration is unfinished.
