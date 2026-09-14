// Final blend of the Richardson-Lucy result, from the tail of
// ImProcFunctions::deconvsharpening (rtengine/ipsharpen.cc):
//   luminance = intp(blend * amount, max(estimate, 0), luminance)
// with blend the contrast mask of buildBlendMask / calcBlendFactor
// (rtengine/rt_algo.cc), so the deconvolution is held back in flat regions.
//
// RawTherapee is Copyright (c) 2004-2010 Gabor Horvath and the RawTherapee
// development team, licensed under the GNU General Public License v3 or later.
// This file is a GLSL reimplementation and carries the same license.
// https://github.com/RawTherapee/RawTherapee
precision highp float;
precision mediump sampler2D;
uniform sampler2D InputBuffer;
uniform sampler2D EstimateBuffer;
/** RT deconvamount/100. */
uniform float amount;
/** RT sharpening.contrast/100. */
uniform float contrastThreshold;
out vec3 Output;
#define INSIZE 1,1
#import coords

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float lumaAt(ivec2 p) {
    return dot(texelFetch(InputBuffer, mirrorCoords2(p, ivec2(INSIZE)), 0).rgb, LUMA);
}

float calcBlendFactor(float val, float threshold) {
    float x = -16.0 + (16.0 / max(threshold, 1e-4)) * val;
    return 0.5 * (1.0 + x / sqrt(1.0 + x * x));
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec3 center = texelFetch(InputBuffer, xy, 0).rgb;
    float centerY = dot(center, LUMA);

    float dx1 = lumaAt(xy + ivec2(1, 0)) - lumaAt(xy + ivec2(-1, 0));
    float dy1 = lumaAt(xy + ivec2(0, 1)) - lumaAt(xy + ivec2(0, -1));
    float dx2 = lumaAt(xy + ivec2(2, 0)) - lumaAt(xy + ivec2(-2, 0));
    float dy2 = lumaAt(xy + ivec2(0, 2)) - lumaAt(xy + ivec2(0, -2));
    float contrast = sqrt(dx1 * dx1 + dy1 * dy1 + dx2 * dx2 + dy2 * dy2) * 0.0625;
    float blend = contrastThreshold <= 0.0 ? 1.0 : calcBlendFactor(contrast, contrastThreshold);

    float estimate = max(texelFetch(EstimateBuffer, xy, 0).r, 0.0);
    float newY = clamp(mix(centerY, estimate, clamp(blend * amount, 0.0, 1.0)), 0.0, 1.0);
    Output = clamp(center * (newY / max(centerY, 1e-4)), 0.0, 1.0);
}
