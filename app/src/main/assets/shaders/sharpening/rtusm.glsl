// Unsharp mask ported from RawTherapee's ImProcFunctions::sharpening and
// sharpenHaloCtrl (rtengine/ipsharpen.cc), with the contrast blend mask from
// buildBlendMask / calcBlendFactor (rtengine/rt_algo.cc) and the four-point
// threshold curve from Threshold::multiply (rtengine/params/threshold.h).
//
// RawTherapee is Copyright (c) 2004-2010 Gabor Horvath and the RawTherapee
// development team, licensed under the GNU General Public License v3 or later.
// This file is a GLSL reimplementation of those routines and is distributed
// under the same license.  https://github.com/RawTherapee/RawTherapee
//
// RawTherapee works on the L channel of Lab scaled to 0..32768 and its UI
// values (threshold, edges_tolerance) are in that scale. Here luminance is
// 0..1, so every level-valued parameter is converted on the Java side by
// dividing by 32768 and each is documented at its uniform.
precision highp float;
precision mediump sampler2D;
uniform sampler2D InputBuffer;
/** RT sharpening.radius, in pixels (RT default 0.5). */
uniform float radius;
/** RT sharpening.amount as a fraction (RT UI 0..1000 percent, default 200). */
uniform float amount;
/** RT sharpening.contrast/100: blend-mask threshold (RT default 0.20). */
uniform float contrastThreshold;
/** Threshold curve, RT's four knots, converted from 0..32768 to 0..1. */
uniform float thrBottomLeft;
uniform float thrTopLeft;
uniform float thrBottomRight;
uniform float thrTopRight;
/** RT sharpening.edgesonly. */
uniform float edgesOnly;
/** RT sharpening.edges_radius, in pixels (RT default 1.9). */
uniform float edgesRadius;
/** RT sharpening.edges_tolerance, converted from 0..32768 (RT default 1800). */
uniform float edgesTolerance;
/** RT sharpening.halocontrol. */
uniform float haloControl;
/** RT sharpening.halocontrol_amount/100 (RT default 0.85). */
uniform float haloAmount;
out vec3 Output;
#define INSIZE 1,1
#import coords

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float lumaAt(ivec2 p) {
    return dot(texelFetch(InputBuffer, mirrorCoords2(p, ivec2(INSIZE)), 0).rgb, LUMA);
}

// RawTherapee calcBlendFactor: a sigmoid whose inflexion point sits at the
// contrast threshold, so sharpening fades out in flat regions instead of
// switching off at a contour.
float calcBlendFactor(float val, float threshold) {
    float x = -16.0 + (16.0 / max(threshold, 1e-4)) * val;
    return 0.5 * (1.0 + x / sqrt(1.0 + x * x));
}

// RawTherapee Threshold::multiply with is_double = true and init_eql = false,
// which is how SharpeningParams::threshold is constructed (20, 80, 2000, 1200).
// The curve rises from bottom_left to top_left, holds at y_max, and falls again
// between top_right and bottom_right.
float thresholdMultiply(float x, float yMax) {
    if (thrBottomRight == thrTopRight && x == thrBottomRight) return yMax;
    if (x >= thrBottomRight) return 0.0;
    if (x > thrTopRight) {
        return yMax * (1.0 - (x - thrTopRight) / max(thrBottomRight - thrTopRight, 1e-6));
    }
    if (x >= thrTopLeft) return yMax;
    if (x > thrBottomLeft) {
        return yMax * (x - thrBottomLeft) / max(thrTopLeft - thrBottomLeft, 1e-6);
    }
    return 0.0;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec3 center = texelFetch(InputBuffer, xy, 0).rgb;
    float centerY = dot(center, LUMA);

    // RT buildBlendMask: contrast from the 2-tap and 4-tap gradients.  RT's
    // 0.0625/327.68 scale converts its 0..32768 L into the same 0..1 range this
    // shader already works in, so only the 0.0625 factor remains.
    float contrast = sqrt(
            (lumaAt(xy + ivec2(1, 0)) - lumaAt(xy + ivec2(-1, 0))) * (lumaAt(xy + ivec2(1, 0)) - lumaAt(xy + ivec2(-1, 0)))
          + (lumaAt(xy + ivec2(0, 1)) - lumaAt(xy + ivec2(0, -1))) * (lumaAt(xy + ivec2(0, 1)) - lumaAt(xy + ivec2(0, -1)))
          + (lumaAt(xy + ivec2(2, 0)) - lumaAt(xy + ivec2(-2, 0))) * (lumaAt(xy + ivec2(2, 0)) - lumaAt(xy + ivec2(-2, 0)))
          + (lumaAt(xy + ivec2(0, 2)) - lumaAt(xy + ivec2(0, -2))) * (lumaAt(xy + ivec2(0, 2)) - lumaAt(xy + ivec2(0, -2)))) * 0.0625;
    float blend = contrastThreshold <= 0.0 ? 1.0 : calcBlendFactor(contrast, contrastThreshold);

    // base: RT sharpens lab->L directly, or the bilateral-filtered copy when
    // "edges only" is set, so that the unsharp difference is taken against an
    // edge-preserving version and flat noise is not amplified.
    float base = centerY;
    if (edgesOnly > 0.5) {
        float sum = 0.0;
        float wsum = 0.0;
        float sigmaS = max(edgesRadius, 0.05);
        for (int i = -3; i <= 3; i++) {
            for (int j = -3; j <= 3; j++) {
                float s = lumaAt(xy + ivec2(i, j));
                float ws = exp(-float(i * i + j * j) / (2.0 * sigmaS * sigmaS));
                float wr = exp(-abs(s - centerY) / max(edgesTolerance, 1e-5));
                sum += s * ws * wr;
                wsum += ws * wr;
            }
        }
        base = sum / max(wsum, 1e-5);
    }

    // b2: the gaussian blur of the base, RT's sharpenParam.radius.
    float blurSum = 0.0;
    float blurW = 0.0;
    float sigma = max(radius, 0.05);
    for (int i = -3; i <= 3; i++) {
        for (int j = -3; j <= 3; j++) {
            float w = exp(-float(i * i + j * j) / (2.0 * sigma * sigma));
            blurSum += lumaAt(xy + ivec2(i, j)) * w;
            blurW += w;
        }
    }
    float b2 = blurSum / max(blurW, 1e-5);

    float diff = base - b2;
    float delta = thresholdMultiply(abs(diff), amount * diff);
    float newY = centerY + delta;

    if (haloControl > 0.5) {
        // RT sharpenHaloCtrl: three overlapping 3x3 averages, each biased toward
        // its own centre, bound how far the sharpened value may travel. Beyond
        // that bound the excursion is scaled down rather than clipped, which is
        // what keeps the limit from drawing an edge of its own.
        float np1 = 2.0 * (lumaAt(xy + ivec2(0, -2)) + lumaAt(xy + ivec2(1, -2)) + lumaAt(xy + ivec2(2, -2))
                         + lumaAt(xy + ivec2(0, -1)) + lumaAt(xy + ivec2(1, -1)) + lumaAt(xy + ivec2(2, -1))
                         + lumaAt(xy + ivec2(0,  0)) + lumaAt(xy + ivec2(1,  0)) + lumaAt(xy + ivec2(2,  0))) / 27.0
                 + lumaAt(xy + ivec2(1, -1)) / 3.0;
        float np2 = 2.0 * (lumaAt(xy + ivec2(0, -1)) + lumaAt(xy + ivec2(1, -1)) + lumaAt(xy + ivec2(2, -1))
                         + lumaAt(xy + ivec2(0,  0)) + lumaAt(xy + ivec2(1,  0)) + lumaAt(xy + ivec2(2,  0))
                         + lumaAt(xy + ivec2(0,  1)) + lumaAt(xy + ivec2(1,  1)) + lumaAt(xy + ivec2(2,  1))) / 27.0
                 + lumaAt(xy + ivec2(1, 0)) / 3.0;
        float np3 = 2.0 * (lumaAt(xy + ivec2(0,  0)) + lumaAt(xy + ivec2(1,  0)) + lumaAt(xy + ivec2(2,  0))
                         + lumaAt(xy + ivec2(0,  1)) + lumaAt(xy + ivec2(1,  1)) + lumaAt(xy + ivec2(2,  1))
                         + lumaAt(xy + ivec2(0,  2)) + lumaAt(xy + ivec2(1,  2)) + lumaAt(xy + ivec2(2,  2))) / 27.0
                 + lumaAt(xy + ivec2(1, 1)) / 3.0;
        float maxN = max(max(np1, np2), np3);
        float minN = min(min(np1, np2), np3);
        maxN = max(maxN, centerY);
        minN = min(minN, centerY);
        float scale = 1.0 - clamp(haloAmount, 0.0, 1.0);
        if (newY > maxN) {
            newY = maxN + (newY - maxN) * scale;
        } else if (newY < minN) {
            newY = minN - (minN - newY) * scale;
        }
    }

    newY = mix(centerY, newY, blend);
    newY = clamp(newY, 0.0, 1.0);
    Output = clamp(center * (newY / max(centerY, 1e-4)), 0.0, 1.0);
}
