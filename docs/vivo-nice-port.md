# NICE HDR neural port: current boundary

## Verified from the supplied files

83 NICE VDNN files (482,698,532 bytes) were extracted from the user's existing
models archive. All 83 wrappers and their embedded QNN V3 metadata were parsed.
64 expose FLOAT32 inputs; 19 use QNN datatype 0x416. Wrapper datatype codes are
not QNN datatype codes. One wrapper's output dimensions differ from the actual
graph (the previously investigated HP9 HexQuad x2); runtime metadata must win.

For the IMX06C forward HDR candidate:

- source: MainCamera/nice_hdr_imx06c_general_forward_bayer_x1.vdnn;
- source SHA256: 7c4663c3c03394841b92cbcc207ad6bd610369c004d45d913aa950cc61511a16;
- embedded context: offset 2772, 5,840,224 bytes;
- context SHA256: a551304d938af0cab76091557414f46a64cae05aaf68ef8030c1bcc42decac8c;
- graph: nice_hdr_imx06c_general_forward_bayer_x1_quant_8w16a32b;
- input inputs_0: FLOAT32 [1,544,544,22]; output tail_conv_1_0: FLOAT32 [1,544,544,3];
- embedded build: v2.28.0.241029232508_102474, target sm8750.

MainCamera/NiceCREConfigHdrForward.xml HDRConfig declares seven inputs,
FrameTypeOrder 1,1,1,1,2,0,3 (ref=3, refn=3, clip=0, useawb=0), VST mode 2,
usesqrtev=1, no input clipping, inputScale 1/32767, outputScale
0.00008737626194488257. Those XML scales are not automatically the public QNN
FLOAT32 client-buffer scale. Seven triplets plus one plane fits 22 channels,
but the semantics/order of that extra plane and the frame codes are UNVERIFIED.
No camera pixels are supplied under this assumption.

The earlier runtime captures prove NICE libraries were loaded, not which model
was executed for an individual photograph. Static configuration candidates are
not presented as observed per-shot routing. NICE also has separate LCA, CRE,
tone/TCE and motion/IC paths; this probe covers one CRE graph only.

## App-contained execution prerequisite

Settings -> Vivo -> Diagnostics -> NICE HDR runs in a dedicated :vivo_nice
process at the ordinary application UID. Camera initialization is skipped.
No root request, firmware model reads, property writes, namespace modifications
or SELinux changes are made. The model and the hash-pinned QNN 2.29.8 runtime
are read from APK assets. The existing public FastRPC driver is a platform
dependency; it is not a bundled Vivo image-processing algorithm.

The probe verifies hashes, parses actual QNN metadata, checks tensor names,
dimensions/type and runtime build, creates the backend/device/context, and
executes two synthetic uniform tensors. Each output is poisoned before dispatch;
unwritten/nonfinite values fail the test. These tensors test execution only:
they do not assert valid photographic preprocessing, quality or stock parity.
QNN 2.28 model compatibility with the bundled 2.29.8 runtime on this phone is
specifically part of the device test, not assumed from matching tensor sizes.

Every stage is saved before the next native call. A native crash or a 180-second
timeout leaves the last report; reopening the entry allows copying it. Closing
the activity terminates only its dedicated process. Library handles remain
resident until exit; QNN context is destroyed before model/client memory.

## Remaining work before capture integration

1. Obtain the probe's actual device result; fix any model/runtime or application
   namespace/RPC incompatibility using supported access.
2. Recover input packing, channel semantics, VST and inverse VST, noise/exposure
   calibration and valid tile region from original CRE code or captured tensors.
3. Establish active model selection and actual frame roles for Photo and Night.
4. Restore alignment, overlap fusion and the post-CRE colour/tone contract, then
   compare the same RAW burst against the stock intermediate/output.

The production HDR path remains the 30227 adaptation. It is NOT silently renamed
NICE and is not replaced with an unverified network. A runtime PASS alone is not
permission to claim the neural photo pipeline is complete.

## Reproduction

`tools/inspect_vivo_nice.py` inventories supplied VDNN files and can extract only
the pinned forward candidate. Keep binary models outside git. The packaging
helper now accepts `--nice-dir`, containing `nice-main-forward-v79.bin`.
`tools/check_vivo_nice_probe.cpp` uses a mocked QNN backend under ASan/UBSan to
check argument layout, tensor validation, dispatch, poisoned output, resource
cleanup on failure and binary lifetime. This is explicitly not device inference.
