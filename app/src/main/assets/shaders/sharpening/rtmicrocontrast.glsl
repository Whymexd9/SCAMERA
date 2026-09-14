// Microcontrast ported from RawTherapee's ImProcFunctions::MLmicrocontrast
// (rtengine/ipsharpen.cc), with the contrast blend mask from buildBlendMask /
// calcBlendFactor (rtengine/rt_algo.cc).
//
// The method is by Manuel Llorens (http://www.rawness.es/sharpening/), released
// under CC0 1.0; the 5x5 pyramid extension and the uniformity tables are by
// Jacques Desmis in RawTherapee. RawTherapee is Copyright (c) 2004-2010 Gabor
// Horvath and the RawTherapee development team, GNU GPL v3 or later. This file
// is a GLSL reimplementation and carries the same license.
// https://github.com/RawTherapee/RawTherapee
//
// RT's LM[] is luminance on a 0..100 scale; this shader keeps that scale so the
// tables and the 5/10/13/... breakpoints are used with RT's own numbers, and
// converts back to 0..1 at the end.
precision highp float;
precision mediump sampler2D;
uniform sampler2D InputBuffer;
/** RT sharpenMicro.amount, already divided by 1500 (3x3) or by 1500/2.7 (5x5). */
uniform float amount;
/** RT sharpenMicro.uniformity, 0..10, selecting a column of the tables. */
uniform float uniformity;
/** RT sharpenMicro.contrast/100. */
uniform float contrastThreshold;
/** RT sharpenMicro.matrix: >0.5 selects the 3x3 matrix, otherwise 5x5. */
uniform float matrix3x3;
out vec3 Output;
#define INSIZE 1,1
#import coords

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float lm(ivec2 p) {
    return dot(texelFetch(InputBuffer, mirrorCoords2(p, ivec2(INSIZE)), 0).rgb, LUMA) * 100.0;
}

float calcBlendFactor(float val, float threshold) {
    float x = -16.0 + (16.0 / max(threshold, 1e-4)) * val;
    return 0.5 * (1.0 + x / sqrt(1.0 + x * x));
}

// RT's L** and Cont* tables, indexed by uniformity.
float tableL(int which, int u) {
    float L98[11] = float[](0.001, 0.0015, 0.002, 0.004, 0.006, 0.008, 0.01, 0.03, 0.05, 0.1, 0.1);
    float L95[11] = float[](0.0012, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.12, 0.15, 0.2, 0.25);
    float L92[11] = float[](0.01, 0.015, 0.02, 0.06, 0.10, 0.13, 0.17, 0.25, 0.3, 0.32, 0.35);
    float L90[11] = float[](0.015, 0.02, 0.04, 0.08, 0.12, 0.15, 0.2, 0.3, 0.4, 0.5, 0.6);
    float L87[11] = float[](0.025, 0.03, 0.05, 0.1, 0.15, 0.25, 0.3, 0.4, 0.5, 0.63, 0.75);
    float L83[11] = float[](0.055, 0.08, 0.1, 0.15, 0.2, 0.3, 0.4, 0.5, 0.6, 0.75, 0.85);
    float L80[11] = float[](0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9);
    float L75[11] = float[](0.22, 0.25, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.95);
    float L70[11] = float[](0.35, 0.4, 0.5, 0.6, 0.7, 0.8, 0.97, 1.0, 1.0, 1.0, 1.0);
    float L63[11] = float[](0.55, 0.6, 0.7, 0.8, 0.85, 0.9, 1.0, 1.0, 1.0, 1.0, 1.0);
    float L58[11] = float[](0.75, 0.77, 0.8, 0.9, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
    if (which == 98) return L98[u];
    if (which == 95) return L95[u];
    if (which == 92) return L92[u];
    if (which == 90) return L90[u];
    if (which == 87) return L87[u];
    if (which == 83) return L83[u];
    if (which == 80) return L80[u];
    if (which == 75) return L75[u];
    if (which == 70) return L70[u];
    if (which == 63) return L63[u];
    return L58[u];
}

float tableCont(int which, int u) {
    float C0[11] = float[](0.05, 0.1, 0.2, 0.25, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9);
    float C1[11] = float[](0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 1.0);
    float C2[11] = float[](0.2, 0.40, 0.6, 0.7, 0.8, 0.85, 0.90, 0.95, 1.0, 1.05, 1.10);
    float C3[11] = float[](0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 1.0, 1.0, 1.05, 1.10, 1.20);
    float C4[11] = float[](0.8, 0.85, 0.9, 0.95, 1.0, 1.05, 1.10, 1.150, 1.2, 1.25, 1.40);
    float C5[11] = float[](1.0, 1.1, 1.2, 1.25, 1.3, 1.4, 1.45, 1.50, 1.6, 1.65, 1.80);
    if (which == 0) return C0[u];
    if (which == 1) return C1[u];
    if (which == 2) return C2[u];
    if (which == 3) return C3[u];
    if (which == 4) return C4[u];
    return C5[u];
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec3 center = texelFetch(InputBuffer, xy, 0).rgb;
    float centerY = dot(center, LUMA);
    int u = int(clamp(uniformity, 0.0, 10.0) + 0.5);
    bool k1 = matrix3x3 > 0.5;

    float v = centerY * 100.0;

    float sqrt2 = sqrt(2.0);
    float sqrt1d25 = sqrt(1.25);

    float contrast;
    if (k1) {
        contrast = sqrt(
                (lm(xy + ivec2(1, 0)) - lm(xy + ivec2(-1, 0))) * (lm(xy + ivec2(1, 0)) - lm(xy + ivec2(-1, 0)))
              + (lm(xy + ivec2(0, 1)) - lm(xy + ivec2(0, -1))) * (lm(xy + ivec2(0, 1)) - lm(xy + ivec2(0, -1)))) * 0.125;
    } else {
        contrast = sqrt(
                (lm(xy + ivec2(1, 0)) - lm(xy + ivec2(-1, 0))) * (lm(xy + ivec2(1, 0)) - lm(xy + ivec2(-1, 0)))
              + (lm(xy + ivec2(0, 1)) - lm(xy + ivec2(0, -1))) * (lm(xy + ivec2(0, 1)) - lm(xy + ivec2(0, -1)))
              + (lm(xy + ivec2(2, 0)) - lm(xy + ivec2(-2, 0))) * (lm(xy + ivec2(2, 0)) - lm(xy + ivec2(-2, 0)))
              + (lm(xy + ivec2(0, 2)) - lm(xy + ivec2(0, -2))) * (lm(xy + ivec2(0, 2)) - lm(xy + ivec2(0, -2)))) * 0.0625;
    }
    contrast = min(contrast, 1.0);

    // 3x3 core, then the 5x5 extension.
    float temp = v + 4.0 * (v * (amount + sqrt2 * amount));
    float temp1 = sqrt2 * amount * (lm(xy + ivec2(-1, -1)) + lm(xy + ivec2(1, -1))
                                  + lm(xy + ivec2(-1, 1)) + lm(xy + ivec2(1, 1)));
    temp1 += amount * (lm(xy + ivec2(0, -1)) + lm(xy + ivec2(-1, 0))
                     + lm(xy + ivec2(1, 0)) + lm(xy + ivec2(0, 1)));
    temp -= temp1;

    if (!k1) {
        float temp2 = -(lm(xy + ivec2(0, 2)) + lm(xy + ivec2(0, -2))
                      + lm(xy + ivec2(-2, 0)) + lm(xy + ivec2(2, 0)));
        temp2 -= sqrt1d25 * (lm(xy + ivec2(-1, 2)) + lm(xy + ivec2(1, 2))
                           + lm(xy + ivec2(2, 1)) + lm(xy + ivec2(-2, 1))
                           + lm(xy + ivec2(-1, -2)) + lm(xy + ivec2(1, -2))
                           + lm(xy + ivec2(2, -1)) + lm(xy + ivec2(-2, -1)));
        temp2 -= sqrt2 * (lm(xy + ivec2(-2, 2)) + lm(xy + ivec2(2, 2))
                        + lm(xy + ivec2(-2, -2)) + lm(xy + ivec2(2, -2)));
        temp2 += 18.601126159 * v; // 4 + 4*sqrt(2) + 8*sqrt(1.25)
        temp2 *= 2.0 * amount;
        temp += temp2;
    }

    temp = max(temp, 0.0);

    // RT's overshoot guard: if the new value crosses any neighbour relative to
    // the centre, it has overshot past real structure, and it is pulled most of
    // the way back to that neighbour.
    int k = k1 ? 1 : 2;
    for (int row = -2; row <= 2; ++row) {
        if (abs(row) > k) continue;
        for (int col = -2; col <= 2; ++col) {
            if (abs(col) > k) continue;
            float n = lm(xy + ivec2(col, row));
            if ((n - temp) * (v - n) > 0.0) {
                temp = mix(temp, n, 0.75);
                row = 3;
                break;
            }
        }
    }

    // Luminance pyramid: how much of the computed contrast is allowed through.
    if (v > 95.0 || v < 5.0) contrast *= tableCont(0, u);
    else if (v > 90.0 || v < 10.0) contrast *= tableCont(1, u);
    else if (v > 80.0 || v < 20.0) contrast *= tableCont(2, u);
    else if (v > 70.0 || v < 30.0) contrast *= tableCont(3, u);
    else if (v > 60.0 || v < 40.0) contrast *= tableCont(4, u);
    else contrast *= tableCont(5, u);
    contrast = min(contrast, 1.0);

    float tempL = mix(v, temp, contrast);
    // Modulation by the original luminance: the gain is capped harder as the
    // pixel approaches white or black, which is what keeps microcontrast from
    // punching holes in highlights.
    if (tempL > v) {
        float ratio = min(tempL / max(v, 1e-4), 1.7) - 1.0;
        float lim;
        if (v > 98.0) lim = 0.0;
        else if (v > 95.0) lim = tableL(95, u);
        else if (v > 92.0) lim = tableL(92, u);
        else if (v > 90.0) lim = tableL(90, u);
        else if (v > 87.0) lim = tableL(87, u);
        else if (v > 83.0) lim = tableL(83, u);
        else if (v > 80.0) lim = tableL(80, u);
        else if (v > 75.0) lim = tableL(75, u);
        else if (v > 70.0) lim = tableL(70, u);
        else if (v > 63.0) lim = tableL(63, u);
        else if (v > 58.0) lim = tableL(58, u);
        else if (v > 42.0) lim = tableL(58, u);
        else if (v > 37.0) lim = tableL(63, u);
        else if (v > 30.0) lim = tableL(70, u);
        else if (v > 25.0) lim = tableL(75, u);
        else if (v > 20.0) lim = tableL(80, u);
        else if (v > 17.0) lim = tableL(83, u);
        else if (v > 13.0) lim = tableL(87, u);
        else if (v > 10.0) lim = tableL(90, u);
        else if (v > 5.0) lim = tableL(95, u);
        else lim = tableL(98, u);
        tempL = v * (1.0 + min(ratio, lim));
    }

    float blendContrast = sqrt(
            (lm(xy + ivec2(1, 0)) - lm(xy + ivec2(-1, 0))) * (lm(xy + ivec2(1, 0)) - lm(xy + ivec2(-1, 0)))
          + (lm(xy + ivec2(0, 1)) - lm(xy + ivec2(0, -1))) * (lm(xy + ivec2(0, 1)) - lm(xy + ivec2(0, -1)))
          + (lm(xy + ivec2(2, 0)) - lm(xy + ivec2(-2, 0))) * (lm(xy + ivec2(2, 0)) - lm(xy + ivec2(-2, 0)))
          + (lm(xy + ivec2(0, 2)) - lm(xy + ivec2(0, -2))) * (lm(xy + ivec2(0, 2)) - lm(xy + ivec2(0, -2)))) * 0.0625 * 0.01;
    float blend = contrastThreshold <= 0.0 ? 1.0 : calcBlendFactor(blendContrast, contrastThreshold);

    float newY = clamp(mix(centerY, tempL * 0.01, blend), 0.0, 1.0);
    Output = clamp(center * (newY / max(centerY, 1e-4)), 0.0, 1.0);
}
