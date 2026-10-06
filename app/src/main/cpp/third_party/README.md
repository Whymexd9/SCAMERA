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
