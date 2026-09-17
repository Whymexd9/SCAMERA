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

uniform int rawWidth;
uniform int rawHeight;
uniform int blockSize;
uniform ivec2 phase;
uniform ivec4 quadColors;
uniform float blackLevel;
uniform float whiteLevel;
uniform float gainB;
uniform float gainR;
/**
 * Per-site gains inside a colour block, quadrant-major: blockGain[q * 16 + sy *
 * blockSize + sx]. The 16 sites of a tetra block differ by up to 12%, and
 * differently for each colour; left uncorrected that fixed pattern rides into
 * the interpolated green and into the differences taken against it. All ones
 * when the correction is off.
 */
uniform float blockGain[64];
/** 0 mask green, 1 mask B difference, 2 mask R difference. Assembly is in assemble.glsl. */
uniform int stage;

out vec4 Output;

/** Which of the 2x2 blocks the site falls in, in reading order. */
int quadIndexAt(ivec2 xy) {
    int period = blockSize * 2;
    int ry = (xy.y + phase.y) % period;
    int rx = (xy.x + phase.x) % period;
    return (ry < blockSize)
            ? ((rx < blockSize) ? 0 : 1)
            : ((rx < blockSize) ? 2 : 3);
}

int colorAt(ivec2 xy) {
    int idx = quadIndexAt(xy);
    return (idx == 0) ? quadColors.x : (idx == 1) ? quadColors.y
         : (idx == 2) ? quadColors.z : quadColors.w;
}

/** The site's gain within its block; the profile is measured per quadrant. */
float blockGainAt(ivec2 xy) {
    int sx = (xy.x + phase.x) % blockSize;
    int sy = (xy.y + phase.y) % blockSize;
    return blockGain[quadIndexAt(xy) * 16 + sy * blockSize + sx];
}

float alignedAt(ivec2 xy) {
    float range = max(whiteLevel - blackLevel, 1.0);
    float v = (float(texelFetch(RawBuffer, xy, 0).r) - blackLevel) / range;
    // The non-uniformity is multiplicative on the signal above black, so it is
    // divided out here, after the black level has gone and before the clamp.
    v *= blockGainAt(xy);
    v = clamp(v, 0.0, 1.0);
    int c = colorAt(xy);
    if (c == 2) v *= gainB;
    else if (c == 0) v *= gainR;
    return v;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);

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
