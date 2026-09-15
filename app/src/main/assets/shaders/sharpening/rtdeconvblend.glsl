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
/** Halo suppression 0..1: how far an excursion past the local range is pulled back. */
uniform float haloSuppression;
/** Fraction of the local range left as free travel before suppression starts. */
uniform float haloTextureMargin;
/** Local contrast above which an edge counts as macro and is bounded hardest. */
uniform float haloMacroThreshold;
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
    float newY = mix(centerY, estimate, clamp(blend * amount, 0.0, 1.0));

    if (haloSuppression > 0.0) {
        // Deconvolution overshoots at edges by construction: the iteration puts
        // back energy the blur spread, and near a step it puts back more than
        // was there. Bound the result by the neighbourhood it came from.
        float lo = 1e9, hi = -1e9;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                float v = lumaAt(xy + ivec2(i, j));
                lo = min(lo, v);
                hi = max(hi, v);
            }
        }
        float range = hi - lo;
        // Texture margin: fine detail lives inside the local range, so leave a
        // slice of it as free travel and only bound what leaves that band.
        float margin = range * haloTextureMargin;
        lo -= margin;
        hi += margin;
        // Macro edges - a large local range - are where halos are visible and
        // where the overshoot is largest, so suppression is applied at full
        // strength there and eased off on fine texture.
        float macro = haloMacroThreshold <= 0.0
                ? 1.0
                : smoothstep(0.0, haloMacroThreshold, range);
        float pull = clamp(haloSuppression, 0.0, 1.0) * macro;
        if (newY > hi) newY = mix(newY, hi, pull);
        else if (newY < lo) newY = mix(newY, lo, pull);
    }

    newY = clamp(newY, 0.0, 1.0);
    Output = clamp(center * (newY / max(centerY, 1e-4)), 0.0, 1.0);
}
