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

## Lightcraft (colour controls of «Цвет», P134)

The band centres of the colour mixer, its raised-cosine band weights and the shape of the colour-grading zones in
`processing/color/ColourLook.java` and `assets/shaders/why/combine.glsl` (COLOUR_LOOK) follow the published formulas of
Lightcraft, <https://github.com/storytold/lightcraft>, available under the MIT License or the Apache License 2.0 at your
option. No Lightcraft source code is included; the implementation is SCAMERA's own.

## RawTherapee «Inpaint opposed» highlight reconstruction (P362)

The highlight reconstruction of `assets/shaders/hlrecovery/opposed.glsl` (its chrominance correction, on the GPU) and the
opposed branch of `assets/shaders/scamhdr/scamrgb.glsl` are ported from RawTherapee, <https://github.com/RawTherapee/RawTherapee>,
`rtengine/hilite_recon.cc` (`RawImageSource::highlight_recovery_opposed`, `test_dilate`, `dilating`) at commit
`b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c`: Copyright (c) 2008-2011 Emil Martinec, (c) 2019 Ingo Weyrich, (c) 2022 darktable developers (the
opposed reconstruction by Hanno Schwalm, with Iain and garagecoder from G'MIC), under the GNU General Public License
version 3 or later - the licence of SCAMERA itself (LICENSE). The chrominance is taken against the opposed reference at
every counted pixel, as in darktable (RawTherapee's buffer there holds the pixel's own value, so its sum is 0).

## LibRaw 0.21.3 (camera RAW in the gallery and its editor, P352)

`libscameraRaw.so` statically links LibRaw 0.21.3 (<https://www.libraw.org>, <https://github.com/LibRaw/LibRaw>, tag
0.21.3, archive SHA-256 `dc3d8b54e333d9d5441336049db255d14b27f19bd326a306cf5aea866806780a`), Copyright (C) 2008-2021 LibRaw
LLC, used under the GNU Lesser General Public License 2.1 (or, at your option, the CDDL 1.0). LibRaw uses code from Dave
Coffin's dcraw.c and the DCB demosaic / FBDD denoise (BSD-like 3-clause, Copyright (C) 2010 Jacek Gozdz). The unchanged
sources are in `app/src/main/cpp/third_party/libraw` (the corresponding source of the library in the APK); the licence texts
ship in the APK as `assets/licenses/LibRaw-LGPL-2.1.txt`, `LibRaw-CDDL-1.0.txt` and `LibRaw-COPYRIGHT.txt`. LibRaw is
linked into its own shared library behind a small JNI wrapper (`app/src/main/cpp/scamera-raw.cpp`), so it can be replaced
by a modified build of the same version.

## LightCraft (the photo editor's colour stage and parametric curve, P351)

The OkLCh colour stage of the gallery's editor (vibrance with skin protection, saturation, the 8-band mixer and its
raised-cosine band weights, the B&W mix, the 3-way colour grading weights) in `gallery/editor/EditMath.java` and
`assets/shaders/editor/main.glsl`, and the parametric curve regions in `EditMath.parametric`, are ported from LightCraft,
<https://github.com/storytold/lightcraft>, `crates/pipeline/src/colorops.rs` and `crates/pipeline/src/curve.rs` at commit
`bb08a697a41b2561a54f1b740245119503b24758`, Copyright (c) 2026 ArtCraft Team and the LightCraft contributors, used under
the MIT License (LightCraft is MIT OR Apache-2.0); the text is `assets/licenses/LightCraft-MIT.txt`. No LightCraft assets
are included.
