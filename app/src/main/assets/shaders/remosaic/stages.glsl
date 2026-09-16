#version 300 es
// Stages of the remosaic that touch the mosaic itself: masking a channel out of
// the raw, forming colour differences, and writing the final bayer.
//
// Ported from the RemosaicApp reference (Chaquopy/Python, remosaic.py). The
// sequence is: align white balance, interpolate green over its mask, take B-G
// and R-G at the sites that have them, interpolate those differences, median
// them, add green back, then emit on a plain 2x2 bayer grid and undo the white
// balance. Interpolating differences rather than the channels themselves is
// what keeps colour from bleeding across edges - the difference is nearly flat
// where the channels are not.
precision highp float;
precision highp int;
precision highp usampler2D;

uniform usampler2D RawBuffer;
/** Interpolated green, from the mask blur. */
uniform sampler2D GreenBuffer;
/** Interpolated B-G difference. */
uniform sampler2D DiffBBuffer;
/** Interpolated R-G difference. */
uniform sampler2D DiffRBuffer;

uniform int rawWidth;
uniform int rawHeight;
uniform int blockSize;
uniform ivec2 phase;
uniform ivec4 quadColors;
uniform float blackLevel;
uniform float whiteLevel;
uniform float gainB;
uniform float gainR;
/** 0 mask green, 1 mask B difference, 2 mask R difference, 3 assemble. */
uniform int stage;

out vec4 Output;

int colorAt(ivec2 xy) {
    int period = blockSize * 2;
    int ry = (xy.y + phase.y) % period;
    int rx = (xy.x + phase.x) % period;
    int idx = (ry < blockSize)
            ? ((rx < blockSize) ? 0 : 1)
            : ((rx < blockSize) ? 2 : 3);
    return (idx == 0) ? quadColors.x : (idx == 1) ? quadColors.y
         : (idx == 2) ? quadColors.z : quadColors.w;
}

/** Target colour on the plain 2x2 bayer the output is written on. */
int targetColorAt(ivec2 xy) {
    int idx = (xy.y % 2) * 2 + (xy.x % 2);
    return (idx == 0) ? quadColors.x : (idx == 1) ? quadColors.y
         : (idx == 2) ? quadColors.z : quadColors.w;
}

float alignedAt(ivec2 xy) {
    float range = max(whiteLevel - blackLevel, 1.0);
    float v = (float(texelFetch(RawBuffer, xy, 0).r) - blackLevel) / range;
    v = clamp(v, 0.0, 1.0);
    int c = colorAt(xy);
    if (c == 2) v *= gainB;
    else if (c == 0) v *= gainR;
    return v;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);

    if (stage == 3) {
        int tc = targetColorAt(xy);
        int sc = colorAt(xy);
        float g = texelFetch(GreenBuffer, xy, 0).x;
        float outv;

        if (tc == 1) {
            // A green target that is already a green sample keeps its own
            // value: interpolating a pixel that was measured would only blur it.
            outv = (sc == 1) ? alignedAt(xy) : g;
        } else if (tc == 2) {
            outv = (g + texelFetch(DiffBBuffer, xy, 0).x) / max(gainB, 1e-6);
        } else {
            outv = (g + texelFetch(DiffRBuffer, xy, 0).x) / max(gainR, 1e-6);
        }

        float range = max(whiteLevel - blackLevel, 1.0);
        outv = clamp(outv, 0.0, 1.0) * range + blackLevel;
        Output = vec4(outv, 0.0, 0.0, 1.0);
        return;
    }

    // Masking stages: x carries the value where the site has that colour, y the
    // mask. The blur that follows needs both to normalise correctly.
    int want = (stage == 0) ? 1 : (stage == 1) ? 2 : 0;
    int c = colorAt(xy);
    if (c != want) {
        Output = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    float v = alignedAt(xy);
    if (stage != 0) v -= texelFetch(GreenBuffer, xy, 0).x;
    Output = vec4(v, 1.0, 0.0, 1.0);
}
