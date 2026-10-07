precision highp float;
precision highp sampler2D;
// ARK tone, final step (ArkCombine): ef_guided_combine of libfc_suppressor.so [fc_cl_source.cl:1399-1844] on every
// output pixel, with the high-frequency detail taken from the full-size Sabre merge instead of Google's guide a3.
//  1. colour/base: cubic B-spline of the colour source (arkLow after bracket_dn on the 1x grid, arkMid on the 2x grid)
//     and of the fused luma; target_lin = ACES^-1(fused^2.2); gain = target_lin / (max(Y709, max/2, 1e-4) * ae);
//     rgb = orig * ae * gain (channel ratios kept: shadows are lifted without a hue shift).
//  2. Bento ceiling (clip > 1.05) -> OKLab macro contrast around L 0.4, vibrance with saturated-colour protection,
//     chroma denoise, hue direction kept -> linear -> Bento roll-off -> AgX (Kraken inset/outset of the kernel,
//     Jed Smith sigmoid, pow 2.4) -> hue lock.
//  3. detail: delta = cbrt(ae * Y_full) - cbrt(ae * B-spline(Y(arkLow))) of the full-size linear input (same colour
//     chain as ark/low.glsl), added to OKLab L after the tone and never multiplied by the shadow lift
//     [:1759-1762], halved towards white [:1786-1789]. The base luminance equals target_lin (the gain normalises
//     the colour source's own luminance away), so the delta is measured against the arkLow grid of the fusion.
//     detailRefU 1 (ArkCombine): like the kernel's guide a3 ("normalized to [0, 1]") the luminance is bounded,
//     min(ae * Y, 1), with the B-spline of its box mean (ArkDetailRef) as reference, and the delta is scaled by
//     (L_base / cbrt(ref))^2 where the tone compresses (L_base below the cube-root reference). Without both the
//     unbounded delta of a lamp edge (|delta| up to 0.7) put black trenches (8 bit 0..5) around every light.
//     sharpU 1 (ArkLumaSharpen ran): the luminance is ArkCam's own sharpened guide S(Ya), Ya = min(m * Y, 1), as the
//     kernel takes delta = cbrt(S(a3)) - cbrt(a5) [:1528-1577]; ArkDetailRef is then in the same domain m and the
//     compression scaling compares against it in the ae domain (x cbrt(ae / m)).
//  4. display: pure power 1/gamma, film toe below 0.15, optional IGN dither [:1805-1843].
// Integer grid arithmetic everywhere (the output reaches 8192 px; float pixel coordinates lose precision on Adreno).
// signedColourU 1 (the working RGB is signed: LMC hybrid without the denoise or after the NLM engine, nicergb signedU): the
// B-spline colour is
// clamped at zero after the interpolation, not per tap - the colour source holds box means of the signed merge, and a
// clamp per tap lifted the mean of a channel near zero (red mottling of a dark teal curtain under the shadow lift).
// 0 (unset: SCAM HDR, the hybrid after the LMC/GCam denoise): per tap as before. The taps can be negative there as well (a
// saturated colour outside sRGB after the colour matrix); clamping those after the interpolation would move colour edges.
#import interpolation
uniform sampler2D InputBuffer;      // full-size white-balanced linear camera RGB (the merge after denoising)
uniform sampler2D GainMap;          // lens shading gains
uniform sampler2D ArkLow;           // arkLow (before bracket_dn): Y_low of the detail
uniform sampler2D ArkColour;        // colour source (arkLow after bracket_dn, or arkMid on the 2x grid)
uniform sampler2D ArkFused;         // fused display luma (.r), arkLow size
uniform sampler2D ArkDetailRef;     // detailRefU 1: box mean of min(m * Y709, 1) (.r), arkLow size (ark/low.glsl DETAIL_REF)
uniform sampler2D ArkLumaS;         // sharpU 1: S(Ya) of ArkLumaSharpen (.r), output grid
uniform mat3 sensorToIntermediate;
uniform mat3 intermediateToSRGB;
uniform vec3 neutralPointU;         // unset -> 1
uniform float inScaleU;             // <= 0 -> 1
uniform int fU;                     // arkLow box factor (2 at 1x, 4 on the 2x grid; 1 = same size, kernel bypass); <= 0 -> 2
uniform int colourFU;               // box factor of the colour source; <= 0 -> fU
uniform int signedColourU;          // 1: B-spline colour clamped after the interpolation (signed input); 0: per tap
uniform float aeU;                  // <= 0 -> 1
uniform float clipU;                // data ceiling; <= 0 -> 1
uniform float toeU;                 // ACES toe; <= 0 -> 0.05
uniform float gammaInvU;            // 1 / ef_gamma; <= 0 -> 1 / 2.2
uniform float macroU;               // OKLab macro contrast; <= 0 -> 1.1
uniform vec3 vibU;                  // vibrance: overall, sky, green (0 = off)
uniform float chromaDnU;            // chroma denoise of strongly lifted pixels (0 = off)
uniform float clarityU;             // delta * (1 + clarity)
uniform float flatProtectU;         // flat-area protection of the delta (0 = off)
uniform float detailGainU;          // ark_detail_gain; 0 (unset) = no detail
uniform int detailRefU;             // 1: bounded, compression-scaled delta against ArkDetailRef; unset -> unbounded delta
uniform int sharpU;                 // 1: the delta's luminance is ArkLumaS; unset -> the full-size merge
uniform float sharpToneU;           // sharpU 1: ae / m (ArkLumaS domain -> ae domain); <= 0 -> 1
uniform float deltaChromaU;         // 0..1: a darkening delta scales a, b with L (hue and relative chroma kept); 0 = kernel
uniform float filmToeU;             // film toe (0 = off)
uniform vec4 agxAU;                 // slope, shoulder power, toe power, saturation; slope <= 0 -> 2.7, 1.35, 1.6, 1
uniform vec4 agxBU;                 // min EV, max EV, EV, look; all 0 (unset) -> -8.5, 3.5, 0.3, 4
uniform int ditherU;                // 1 = interleaved-gradient dither +-0.5 LSB (as the kernel); unset -> none
uniform float guardU;               // alpha = gain^-guard: the sharpening weight read by ArkSharpenGuard; <= 0 -> alpha 1
uniform int headroomU;              // 1: real headroom above 1 (clipped RAW recovered / Bento); 0: the excess is WB headroom
uniform float hlWhiteU;             // path to white of saturated light entering the shoulder (0 = off)
out vec4 Output;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 sceneLinear(ivec2 xy, ivec2 size) {
    vec3 inColor = max(texelFetch(InputBuffer, xy, 0).rgb, vec3(0.0));
    vec4 gains = textureBicubicHardware(GainMap, vec2(xy) / vec2(size));
    gains.rgb = vec3(gains.r, (gains.g + gains.b) / 2.0, gains.a);
    float lsc = dot(gains.rgb, vec3(1.0 / 3.0));
    vec3 neutral = neutralPointU.r + neutralPointU.g + neutralPointU.b > 0.0 ? neutralPointU : vec3(1.0);
    float scale = inScaleU > 0.0 ? inScaleU : 1.0;
    return intermediateToSRGB * (sensorToIntermediate * (inColor * neutral * (lsc * scale)));
}

float max3(vec3 v) { return max(v.r, max(v.g, v.b)); }
float cbrtp(float x) { return pow(x, 1.0 / 3.0); }

vec4 bsplineWeights(float x) {
    float x2 = x * x, x3 = x2 * x;
    return vec4((1.0 - 3.0 * x + 3.0 * x2 - x3) / 6.0, (4.0 - 6.0 * x2 + 3.0 * x3) / 6.0,
                (1.0 + 3.0 * x + 3.0 * x2 - 3.0 * x3) / 6.0, x3 / 6.0);
}

// Output pixel x on a grid reduced by a box of f: centre (x + 0.5) / f - 0.5 as an integer base and a fraction.
void gridPos(int x, int f, out int base, out float frac) {
    int q = x / f;
    int r = x - q * f;
    float c = float(2 * r + 1 - f) / float(2 * f);
    if (c < 0.0) { base = q - 1; frac = c + 1.0; } else { base = q; frac = c; }
}

vec3 finiteTap(vec3 c) {
    if (isnan(c.r) || isinf(c.r)) c.r = 1.0;
    if (isnan(c.g) || isinf(c.g)) c.g = 1.0;
    if (isnan(c.b) || isinf(c.b)) c.b = 1.0;
    return c;
}

vec3 sanitize(vec3 c) {
    return finiteTap(max(c, vec3(0.0)));
}

// Exact quadratic inverse of the kernel's ACES curve, pre-gain 1.5, a 2.51, d 0.59 [:987-1004].
float inverseAcescg(float y, float toe) {
    float b = max(toe * 0.75, 0.005);
    float e = max(0.14 - (toe - 0.04) * 0.8, 0.03);
    y = clamp(y, 0.0, 2.51 / 2.43 - 0.01);
    float A = 2.43 * y - 2.51;
    float B = 0.59 * y - b;
    float C = e * y;
    float disc = B * B - 4.0 * A * C;
    if (disc < 0.0) return 0.0;
    float x = (-B - sqrt(disc)) / (2.0 * A - 0.000001);
    return max(x / 1.5, 0.0);
}

vec3 toOklab(vec3 c) {
    float l = 0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b;
    float m = 0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b;
    float s = 0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b;
    l = cbrtp(max(l, 0.0)); m = cbrtp(max(m, 0.0)); s = cbrtp(max(s, 0.0));
    return vec3(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s);
}

// OKLab -> linear sRGB without the final clamp (the kernel clamps or floors at the call site).
vec3 fromOklab(vec3 lab);

// OKLab -> linear sRGB inside [0, 1]: L is kept, the chroma is reduced (hue kept) until every channel fits, so a bright
// saturated colour does not lose its texture to a per-channel clamp (a positive detail delta lowers its chroma instead).
vec3 fromOklabInGamut(vec3 lab) {
    lab.x = clamp(lab.x, 0.0, 1.0);
    vec3 c = fromOklab(lab);
    if (all(greaterThanEqual(c, vec3(-0.0005))) && all(lessThanEqual(c, vec3(1.0005)))) return clamp(c, 0.0, 1.0);
    float lo = 0.0, hi = 1.0;
    for (int i = 0; i < 10; i++) {
        float mid = 0.5 * (lo + hi);
        vec3 t = fromOklab(vec3(lab.x, lab.yz * mid));
        if (all(greaterThanEqual(t, vec3(-0.0005))) && all(lessThanEqual(t, vec3(1.0005)))) lo = mid; else hi = mid;
    }
    return clamp(fromOklab(vec3(lab.x, lab.yz * lo)), 0.0, 1.0);
}

vec3 fromOklab(vec3 lab) {
    float l = max(lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z, 0.0);
    float m = max(lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z, 0.0);
    float s = max(lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z, 0.0);
    l = l * l * l; m = m * m * m; s = s * s * s;
    return vec3(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
}

// Jed Smith tone scale of the kernel [:1102-1122], py = 0.5.
float agxTonescale(float x, float slope, float sp, float tp, float px) {
    const float py = 0.5;
    if (x >= px) {
        float sBase = slope * (1.0 - px) / (1.0 - py);
        float sDenom = pow(slope * (1.0 - px), -sp);
        float ss = pow(max((pow(sBase, sp) - 1.0) * sDenom, 0.00001), -1.0 / sp);
        float ms = slope * (x - px) / max(ss, 0.00001);
        float fs = ms / pow(1.0 + pow(max(ms, 0.0), sp), 1.0 / sp);
        return ss * fs + py;
    }
    float tBase = slope * px / py;
    float tDenom = pow(slope * px, -tp);
    float ts = pow(max((pow(tBase, tp) - 1.0) * tDenom, 0.00001), -1.0 / tp);
    float mr = slope * (px - x) / max(ts, 0.00001);
    float ft = mr / pow(1.0 + pow(max(mr, 0.0), tp), 1.0 / tp);
    return py - ts * ft;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InputBuffer, 0);
    int f = fU > 0 ? fU : 2;
    int fc = colourFU > 0 ? colourFU : f;
    float ae = aeU > 0.0 ? aeU : 1.0;
    float clipCeiling = clipU > 0.0 ? clipU : 1.0;
    float toe = toeU > 0.0 ? toeU : 0.05;
    float gammaInv = gammaInvU > 0.0 ? gammaInvU : 1.0 / 2.2;
    float macro = macroU > 0.0 ? macroU : 1.1;
    vec4 agxA = agxAU.x > 0.0 ? agxAU : vec4(2.7, 1.35, 1.6, 1.0);
    vec4 agxB = agxBU == vec4(0.0) ? vec4(-8.5, 3.5, 0.3, 4.0) : agxBU;
    int look = int(agxB.w + 0.5);

    // === 1. B-spline colour, fused luma and Y_low [:1450-1497] ===
    vec3 orig = vec3(0.0);
    float fused = 0.0;
    float yLow = 0.0;
    float yRef = 0.0;
    bool bounded = detailRefU == 1 && f != 1;
    if (f == 1) {
        // same size as the low grid: the kernel's full-resolution bypass (no interpolation)
        orig = sanitize(texelFetch(ArkColour, xy, 0).rgb);
        fused = max(texelFetch(ArkFused, xy, 0).r, 0.0);
        yLow = max(dot(texelFetch(ArkLow, xy, 0).rgb, LUMA), 0.000001);
    } else {
        int bx, by;
        float fx, fy;
        gridPos(xy.x, f, bx, fx);
        gridPos(xy.y, f, by, fy);
        vec4 wx = bsplineWeights(fx), wy = bsplineWeights(fy);
        ivec2 lastLow = textureSize(ArkLow, 0) - ivec2(1);
        ivec2 lastFused = textureSize(ArkFused, 0) - ivec2(1);
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -1; dx <= 2; dx++) {
                float w = wx[dx + 1] * wy[dy + 1];
                ivec2 p = ivec2(bx + dx, by + dy);
                fused += max(texelFetch(ArkFused, clamp(p, ivec2(0), lastFused), 0).r, 0.0) * w;
                yLow += max(dot(texelFetch(ArkLow, clamp(p, ivec2(0), lastLow), 0).rgb, LUMA), 0.000001) * w;
                if (bounded) yRef += max(texelFetch(ArkDetailRef, clamp(p, ivec2(0), lastLow), 0).r, 0.000001) * w;
            }
        }
        gridPos(xy.x, fc, bx, fx);
        gridPos(xy.y, fc, by, fy);
        wx = bsplineWeights(fx);
        wy = bsplineWeights(fy);
        ivec2 lastColour = textureSize(ArkColour, 0) - ivec2(1);
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -1; dx <= 2; dx++) {
                ivec2 p = clamp(ivec2(bx + dx, by + dy), ivec2(0), lastColour);
                vec3 tap = texelFetch(ArkColour, p, 0).rgb;
                orig += (signedColourU != 0 ? finiteTap(tap) : sanitize(tap)) * (wx[dx + 1] * wy[dy + 1]);
            }
        }
        if (signedColourU != 0) orig = max(orig, vec3(0.0));
    }

    // === 2. ACES inversion and base gain [:1500-1509] ===
    float targetLin = inverseAcescg(pow(max(fused, 0.0), 2.2), toe);
    float origLuma = max(max(dot(orig, LUMA), max3(orig) * 0.5), 0.0001);
    float gain = targetLin / max(origLuma * ae, 0.0001);
    vec3 lin = orig * ae * gain;
    // Saturation of the light (0 neutral .. 1 one channel): the shoulders below act on the max channel for low-saturation
    // colours (as the kernel) and on the luminance for saturated ones, so a saturated light keeps its tonal separation
    // instead of being pinned at its max channel (P11: the LED wall band of the X9 Ultra collapsed to one flat colour).
    float satLight = (max3(lin) - min(lin.r, min(lin.g, lin.b))) / max(max3(lin), 0.000001);
    float satW = smoothstep(0.50, 0.85, satLight);
    bool headroom = clipCeiling > 1.05 && headroomU != 0;
    // Bento headroom ceiling: everything above 1 softly into <= 1.35 [:1515-1526]; only with real headroom (clipped data
    // or Bento), not for the WB headroom of unclipped saturated colour.
    if (headroom) {
        float mf = mix(max3(lin), dot(lin, LUMA), satW);
        if (mf > 1.0) {
            float ex = mf - 1.0;
            lin *= (1.0 + ex * 0.35 / (ex + 0.35)) / mf;
        }
    }

    // === 3. detail of the full-size merge (delta), applied after the tone ===
    float delta = 0.0;
    float lRef = 0.0;
    float sharpTone = sharpU == 1 && sharpToneU > 0.0 ? sharpToneU : 1.0;
    if (detailGainU != 0.0) {
        float yS = sharpU == 1 ? max(texelFetch(ArkLumaS, xy, 0).r, 0.0) : 0.0;
        float yFull = sharpU == 1 ? yS * sharpTone / ae : dot(sceneLinear(xy, size), LUMA);
        if (f == 1) {
            // kernel full-resolution mode: asymmetric clamp against dark trenches [:1538-1548]
            float lBase = cbrtp(max(dot(orig, LUMA) * ae, 0.000001));
            delta = clamp(cbrtp(max(yFull * ae, 0.000001)) - lBase, -min(0.035, lBase * 0.25), 0.050);
        } else if (bounded) {
            // bounded like the kernel's a3: min(m * Y, 1) (or its sharpened S) against the B-spline of its box mean
            lRef = cbrtp(max(yRef, 0.000001));
            delta = cbrtp(max(sharpU == 1 ? yS : min(yFull * ae, 1.0), 0.000001)) - lRef;
        } else {
            delta = cbrtp(max(yFull * ae, 0.000001)) - cbrtp(max(yLow * ae, 0.000001));
        }
        delta *= detailGainU;
        if (abs(clarityU) > 0.001) delta *= 1.0 + clarityU;
        if (flatProtectU > 0.001) {
            float smoothFactor = 1.0 - smoothstep(0.0, (flatProtectU / 10.0) * 0.015, abs(delta));
            delta = mix(delta, 0.0, smoothFactor);
        }
    }

    // === OKLab grading [:1599-1658] ===
    vec3 lab = toOklab(lin);
    if (abs(macro - 1.0) > 0.001) lab.x = max(0.40 + (lab.x - 0.40) * macro, 0.0001);
    float sat = max(length(lab.yz), 0.0001);
    float protect = max(1.0 - sat * 2.0, 0.0);
    float satMult = 1.0 + vibU.x * protect;
    if (abs(vibU.y) > 0.001 && lab.z < 0.0) satMult += vibU.y * clamp(-lab.z / sat, 0.0, 1.0) * protect;
    if (abs(vibU.z) > 0.001 && lab.y < 0.0) satMult += vibU.z * clamp(-lab.y / sat, 0.0, 1.0) * protect;
    lab.yz *= satMult;
    if (chromaDnU > 0.001) {
        float lift = targetLin / max(origLuma * ae, 0.0001);
        if (lift > 4.0) lab.yz *= max(1.0 - chromaDnU * clamp((lift - 4.0) * 0.1, 0.0, 1.0), 0.0);
    }
    float inChroma = length(lab.yz);
    vec2 hueDir = inChroma > 0.000001 ? lab.yz / inChroma : vec2(0.0);
    vec3 scene = max(fromOklab(lab), vec3(0.0));
    // Bento roll-off of the linear maximum above 0.5 [:1673-1681] (same shoulder measure as the ceiling)
    if (headroom) {
        float ml = mix(max3(scene), dot(scene, LUMA), satW);
        if (ml > 0.5) {
            float ex = ml - 0.5;
            float room = max((clipCeiling - 0.5) * 0.35, 1.5);
            scene *= (0.5 + ex / (1.0 + ex / room)) / ml;
        }
    }
    // Path to white: saturated light whose max channel enters the AgX shoulder fades towards its luminance, as a bright
    // coloured light does on film / in HDR+ (the input here is linear camera RGB, not an already desaturated render).
    if (hlWhiteU > 0.0) {
        float mx = max3(scene);
        float yS = dot(scene, LUMA);
        float w = hlWhiteU * satW * smoothstep(0.6, 2.4, mx);
        scene = mix(scene, vec3(yS), clamp(w, 0.0, 0.9));
    }

    // === AgX (Blender 4 / Kraken matrices of the kernel) [:1079-1094, :1687-1730] ===
    vec3 ins = max(vec3(0.842479062253094 * scene.r + 0.0784335999999992 * scene.g + 0.0792237451477643 * scene.b,
                        0.0423282422610123 * scene.r + 0.8784686364697720 * scene.g + 0.0791661274605434 * scene.b,
                        0.0423756549057051 * scene.r + 0.0784336000000000 * scene.g + 0.8791429737931040 * scene.b), vec3(0.0));
    float minEv = agxB.x, maxEv = agxB.y, ev = agxB.z;
    float range = max(maxEv + max(ev, 0.0) - minEv, 1.0);
    float px = clamp(-minEv / range, 0.05, 0.95);
    vec3 lg = clamp((log2(max(ins, vec3(0.0000001)) / 0.18) + ev - minEv) / range, 0.0, 1.0);
    vec3 cur = vec3(agxTonescale(lg.r, agxA.x, agxA.y, agxA.z, px), agxTonescale(lg.g, agxA.x, agxA.y, agxA.z, px),
                    agxTonescale(lg.b, agxA.x, agxA.y, agxA.z, px));
    if (look == 3) {
        cur.r = pow(max(cur.r, 0.0), 0.96);
        cur.b = pow(max(cur.b, 0.0), 1.04);
    }
    vec3 dispLin = pow(max(cur, vec3(0.0)), vec3(2.4));
    vec3 graded = vec3(1.19687902425835 * dispLin.r - 0.09802088114013 * dispLin.g - 0.09902975707567 * dispLin.b,
                       -0.05289685400450 * dispLin.r + 1.15190312998791 * dispLin.g - 0.09896117934484 * dispLin.b,
                       -0.05297163539640 * dispLin.r - 0.09804337634422 * dispLin.g + 1.15107253721836 * dispLin.b);
    if (abs(agxA.w - 1.0) > 0.001) {
        float ol = dot(graded, LUMA);
        graded = vec3(ol) + (graded - vec3(ol)) * agxA.w;
    }
    // In gamut by chroma, not per channel: a channel of a saturated light over 1 would otherwise stop its brightening.
    graded = fromOklabInGamut(toOklab(max(graded, vec3(0.0))));

    // === hue lock and post-tone detail [:1763-1802] ===
    vec3 post = toOklab(graded);
    if (look != 3 && inChroma > 0.000001) post.yz = length(post.yz) * hueDir;
    if (lRef > 0.0) {
        // Where the tone compresses (Bento roll-off, fusion darkening, AgX shoulder: L_base below the cube-root
        // reference) the delta is scaled down with it; lifted shadows and mid-tones (ratio >= 1) keep the full delta.
        float compression = min(1.0, post.x / (lRef * (sharpTone != 1.0 ? cbrtp(sharpTone) : 1.0)));
        delta *= compression * compression;
    }
    float lBefore = post.x;
    post.x = clamp(post.x + delta * (1.0 - smoothstep(0.75, 1.0, post.x) * 0.5), 0.0001, 1.0);
    // The kernel keeps a, b while the delta moves L. At a strong edge the colour is the B-spline mix of both sides, and
    // on the dark side the delta pulls L far down: the same a, b at a low L read as a saturated purple/green line. Scaling
    // a, b with L there (a uniform linear scale of the colour) keeps the hue and the relative chroma instead.
    if (deltaChromaU > 0.0 && post.x < lBefore) post.yz *= mix(1.0, post.x / max(lBefore, 0.0001), clamp(deltaChromaU, 0.0, 1.0));
    graded = fromOklabInGamut(post);

    // === display: power 1/gamma, film toe [:1805-1825], dither [:1836-1843] ===
    vec3 outRgb = pow(max(graded, vec3(0.000001)), vec3(gammaInv));
    if (filmToeU > 0.0) {
        float ls = max(dot(outRgb, LUMA), 0.0001);
        if (ls < 0.15) outRgb *= (pow(max(ls / 0.15, 0.000001), 1.0 + filmToeU) * 0.15) / ls;
    }
    if (ditherU == 1) {
        float d1 = fract(0.06711056 * float(xy.x) + 0.00583715 * float(xy.y));
        outRgb += (fract(52.9829189 * d1) - 0.5) / 255.0;
    }
    float weight = guardU > 0.0 ? pow(max(gain, 1.0), -guardU) : 1.0;
    Output = vec4(clamp(outRgb, 0.0, 1.0), weight);
}
