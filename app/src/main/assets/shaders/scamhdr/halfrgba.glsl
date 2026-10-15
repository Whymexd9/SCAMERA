precision highp float;
precision highp int;
precision highp image2D;
// P145: the worker's half-float RGB (6 B a pixel, rows of 3 * width halves) into the RGBA16F input texture of ScamRgb, alpha
// 1.0 - the repack the CPU did per row (ScamRgb.halfRgbToRgbaRows) as a compute pass over a band of rows. The band's bytes
// are copied unchanged into the SSBO (little-endian: half i of a word is bits 16 * (i & 1)); every half is widened exactly
// by unpackHalf2x16 and stored as the same half (HalfRgbUpload checks once per process that the driver keeps every one of
// the 65536 patterns, denormals included).
#define LAYOUT //
LAYOUT

layout(std430, binding = 1) readonly buffer halfRgb { uint words[]; };
layout(rgba16f, binding = 0) writeonly uniform image2D outImage;
uniform ivec2 bandU;        // x: the image width, y: the first row of the band in the image
uniform int rowsU;          // rows in this band

float halfAt(uint i) {
    uint word = words[i >> 1];
    return unpackHalf2x16((i & 1u) == 0u ? word : word >> 16).x;
}

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= bandU.x || p.y >= rowsU) return;
    uint i = (uint(p.y) * uint(bandU.x) + uint(p.x)) * 3u;
    imageStore(outImage, ivec2(p.x, bandU.y + p.y), vec4(halfAt(i), halfAt(i + 1u), halfAt(i + 2u), 1.0));
}
