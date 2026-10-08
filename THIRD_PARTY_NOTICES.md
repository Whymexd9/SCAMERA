# Third-party notices

## Raspberry Pi AI_denoise

The files `app/src/main/assets/models/ai_denoise/nafnet_bayer_small.tflite`
and `app/src/main/assets/models/ai_denoise/unet_bayer_fast.tflite`
come from <https://github.com/raspberrypi/AI_denoise> and are distributed
under the BSD 2-Clause License.

Copyright (c) 2025, Raspberry Pi Ltd

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## Google LiteRT

LiteRT runtime libraries are distributed under the Apache License 2.0.

## libavif and libaom (AVIF photo output)

`libscameraAvif.so` statically links libavif 1.4.2 (<https://github.com/AOMediaCodec/libavif>, BSD 2-Clause License,
Copyright 2019 Joe Drago; it carries a libyuv subset under the BSD 3-Clause License and dav1d's OBU parser under the
BSD 2-Clause License) and libaom 3.15.1 (<https://aomedia.googlesource.com/aom>, BSD 2-Clause License, Copyright (c)
2016, Alliance for Open Media, with the Alliance for Open Media Patent License 1.0). Both are fetched at build time
from their pinned release archives (`app/src/main/cpp/scamera-avif-deps.cmake`, SHA-256 checked); the full licence
texts are the `LICENSE` and `PATENTS` files of those archives.
