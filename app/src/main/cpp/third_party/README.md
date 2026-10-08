# Vendored native sources

Copied without changes, only the files the library build needs (no tests, tools, examples, contrib).

| Directory | Source | Commit | Licence |
|---|---|---|---|
| `jpegli/lib/jpegli`, `jpegli/lib/base` | https://github.com/google/jpegli | `031a0077f5799a6041004267fc12b956c1f52a20` (2026-06-01) | BSD-3-Clause (`jpegli/LICENSE`, patent grant `jpegli/PATENTS`) |
| `jpegli/include/jpeglib.h`, `jmorecfg.h` | https://github.com/libjpeg-turbo/libjpeg-turbo (jpegli's submodule) | `8ecba3647edb6dd940463fedf38ca33a8e2a73d1` | IJG / BSD (`jpegli/include/LICENSE-libjpeg-turbo.md`, `README.ijg`) |
| `jpegli/include/jconfig.h` | generated from libjpeg-turbo `jconfig.h.in` as jpegli's CMake does (JPEG_LIB_VERSION 62, 8-bit, MEM_SRCDST) | | as above |
| `highway/hwy` | https://github.com/google/highway (jpegli's submodule) | `271a9a0ed9de1232d9117f1572c3fe28f8542ec1` | Apache-2.0 / BSD-3-Clause (`highway/LICENSE`) |

Used by `scamera-jpeg.cpp` (library `scameraJpeg`): every saved JPEG (plain, Ultra HDR base and gain map) is encoded with
jpegli, 4:4:4. Measured on the OPPO PHY110 (12.6 MP SCAMERA shot, arm64): q98 4:4:4 4.55 MB in 160 ms (the old
Bitmap.compress q98 4:2:0 file of the same shot: 4.56 MB), q95 4:4:4 2.66 MB in 147 ms.

## Fetched at build time

Not vendored (libaom alone is ~6.6 MB of source): `../scamera-avif-deps.cmake` downloads the pinned release archives
once into `app/build/native-deps` (override: `-DSCAMERA_DEPS_CACHE=`), checks their SHA-256 and builds them with the
app's NDK toolchain. Needs Perl (Git for Windows ships one) for libaom's generated headers; the host build of
`tools/check_avif.py` also needs nasm on x86.

| Library | Version | Archive | SHA-256 | Licence |
|---|---|---|---|---|
| libavif | 1.4.2 (2026-05-26) | https://github.com/AOMediaCodec/libavif/archive/refs/tags/v1.4.2.tar.gz | `2b645287340ba5a631d268b551dc2d72bd73ac33335962dd36dcdb6d8366921d` | BSD-2-Clause (libyuv subset BSD-3-Clause) |
| libaom | 3.15.1 (2026-09-21) | https://storage.googleapis.com/aom-releases/libaom-3.15.1.tar.gz | `8ca0c52746174603500f0adb6f2a215d69c9ca2aab2acb3caa06fb791d8d01bf` | BSD-2-Clause + AOMedia Patent License 1.0 |

Used by `scamera-avif.cpp` (library `scameraAvif`) through `scamera-avif-core.h`: the AVIF photo format, AV1 still
image (`AOM_USAGE_ALL_INTRA`), encoder only (Android decodes AVIF itself from Android 12), 8 / 10 / 12-bit, 4:4:4 /
4:2:0, lossless. arm64 code paths: NEON, dot product, i8mm, SVE / SVE2, chosen at run time.
