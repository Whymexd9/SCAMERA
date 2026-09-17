# Vivo neural remosaic: model discovery and device prerequisite test

This is **not a working neural remosaic backend**. The two existing remosaic
options and their processing paths are unchanged. The new main-settings entry
**Vivo Neural — проверка** tests loading a compiled model into Qualcomm HTP in a
separate app process. It does not execute the graph or process a photograph.

## Findings from the supplied firmware

`libremosaiclib_s5khp3.so` SHA-256:
`7495113303fb01cff07434eb9896253774846a7b2c5ff1e13017654c884c3aa5`.
The earlier HP9 adapter investigation established that this library is used by
an integration recognizing `s5khp9`; that alone does not identify its 4x ISZ path.

The weights are present. Four exported V79 QNN context binaries were recovered
using ELF data symbols, section-relative offsets and exported length checks.
The observed serialized metadata gives these shapes:

| Candidate | Bytes | Input | Output |
| --- | ---: | --- | --- |
| HC v1p19, 1152, QNN 2.24.1 | 805592 | 1×288×288×18 | 1×288×288×48 |
| HC v1p20, 4224×4240, QNN 2.24.1 | 1878752 | 1×1060×1056×18 | 1×1060×1056×48 |
| TELE v1p9, 576, QNN 2.24.1 | 5720672 | 1×144×144×16 | 1×144×144×64 |
| TELE v1p9, 576, QNN 2.25.1 | 5720680 | 1×144×144×16 | 1×144×144×64 |

These are serialized tensor dimensions, not a verified interpretation of colour
planes. All four serialize datatype `0x232`. Names are `input_0` and
`tetra2_g_out_BiasAdd`.

The embedded selection JSON maps `HP3/HONOR/TELE/200/v79` to the TELE QNN 2.25.1
binary. `HP3/VOLCANO/HC_E2E_NR1/12/v79` instead selects HC 1152. These are library
configuration keys, **not proof of Vivo's active camera mode**. Neither `TELE`,
`3x`, `2x`, nor `12` can be equated to the user's 4x ISZ setting without tracing
the actual caller parameters. The TELE context is used only for the device
compatibility test, not automatically chosen for RAW reconstruction.

The legacy exported `bayer_preproc_hc_v3` (VA 0x570f0) was executed in an ARM64
emulator, with profiler calls stubbed and parallel work serialized. All 4096
samples of a synthetic 64×64 RAW matched `sqrt(raw / 1023)` with this order
inside each 4×4 spatial tile:

`(0,0),(0,1),(1,0),(1,1),(0,2),(0,3),(1,2),(1,3),`
`(2,0),(2,1),(3,0),(3,1),(2,2),(2,3),(3,2),(3,3)`.

This is a **16-channel legacy kernel**. It does not establish the 18-channel HC
input, the meaning of the additional channels, or the complete preprocessing of
HP9 same-colour 4×4 input. Consequently, feeding the user RAW directly to one of
these models would be an unverified experiment, not a stock-equivalent port.

`libdlrmsc_android15.so` separately contains a TFLite model named
`kanul_model_45_12_opt_fp16_tflite` and SCBPC entry points. Its presence does not
establish that it is the full HP9 4x neural remosaic. It is not used by this test.

## What the APK actually does

1. Verifies firmware libraries against the supplied SHA-256 values. Unknown
   versions or unreadable files stop the test with their exact name/hash.
2. Copies the small QNN/RPC libraries to app-private read-only files. The large
   remosaic library is read only to extract the named data object; none of its
   constructors, calibration functions or guessed Samsung APIs are called.
3. Calls QNN System 1.1 `getBinaryInfo` on the embedded TELE context.
4. Tries the optional public `libcdsprpc.so` route declared in the manifest,
   then the verified local copy. Loads V79 stub and HTP, creates backend/device,
   and attempts `contextCreateFromBinary` with default configuration.
5. Records each stage before the next native call, frees successful handles and
   reports the result. A context load success is **not inference success**.

The uploaded RPC library depends on private platform/vendor libraries including
`libhidlbase.so`, `libutils.so`, and `vendor.qti.hardware.dsp-V1-ndk.so`. If the
phone does not expose the optional public RPC route, copying this single library
may fail to resolve its dependencies. That is a real prerequisite to solve;
this test reports it and does not alter linker namespaces, permissions or HAL
access controls. DSP device permissions, signed domain requirements, skeleton
search paths and operation packages can independently prevent context loading.

The test runs in `:vivo_neural`; PhotonCamera skips camera/GPU initialization
there. Only this process is terminated on timeout (45 seconds) or closing the
test screen. The last report survives a native crash. Reopen the entry to copy
it if the test window closes. Vendor files and model weights are not bundled in
the repository or APK. This does not address the separate RT CPU denoise crash.

## ABI evidence and scope

The QNN System provider in the supplied ELF points from VA 0x4bea0 to 0x4bea8;
its API is 1.1.0, and the four-function prefix starts at offset 32. The order is
create, getBinaryInfo, getMetadata, free. The backend provider prefix starts at
offset 40; only the documented create/free backend, device and context entries
are called, with core major 2 and minor 17 through 25 accepted. Firmware hashes
are checked before loading. Opaque tensor layouts are not guessed or passed.

References: [Qualcomm QNN System interface](https://docs.qualcomm.com/doc/80-63442-10/topic/api-rst_program_listing_file_include_QNN_System_QnnSystemInterface_h.html),
[QNN interface](https://docs.qualcomm.com/doc/80-63442-10/topic/api-rst_program_listing_file_include_QNN_QnnInterface_h.html),
[System context metadata](https://docs.qualcomm.com/doc/80-63442-10/topic/api-rst_program_listing_file_include_QNN_System_QnnSystemContext_h.html),
[Android dynamic code loading](https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading).

## Reproduction and remaining work

With the supplied library kept outside git:

```sh
python tools/inspect_vivo_neural.py /path/to/libremosaiclib_s5khp3.so
python tools/check_vivo_preprocess.py /path/to/libremosaiclib_s5khp3.so
g++ -std=c++17 -fsanitize=address,undefined tools/check_vivo_neural.cpp -o /tmp/check-vivo
/tmp/check-vivo /path/to/libremosaiclib_s5khp3.so
```

The inventory needs pyelftools; arithmetic emulation also needs unicorn and
numpy. `--extract-dir` explicitly opts into writing compiled model copies;
keep those outside git. The narrow metadata reader rejects unknown envelopes.
Local ASan/UBSan extraction tests passed with leak detection disabled because
LeakSanitizer is unsupported in the execution environment.

Before offering a neural remosaic choice: obtain the device report; establish
which model and caller mode are used for HP9 4x; reconstruct full input/output
packing, calibration, normalization, padding and overlapping tile assembly;
execute the graph on the target device; compare the same RAW against Tetra
Detail v2 for colour, grid, genuine detail, memory and processing time. None of
those remaining runtime/quality gates is claimed to have passed here.
