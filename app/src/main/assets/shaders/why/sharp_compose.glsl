precision highp float;
precision highp sampler2D;
// WHY luma sharpening (WhyLumaSharpen): luma_compose of WhyCam 1.23 [fc_cl_source.cl:303-503; why_sharpen.md 3.2,
// reference research/hybrid5/ref/sharpen/ark_sharpen_ref.py luma_compose]: USM (with noise threshold), bilateral
// high-boost and guided-filter local contrast summed, damped on strong 3x3 contrast, clamped to the local min/max
// (halo control), faded towards white (protect highlights) and black (protect shadows), optional luma grain.
// P404: the reference app's Sobel edge boost and edge thinning (shock filter) from v2.07's luma_compose_usm (sobelU / thinU,
// 0 = off as before). On the Sabre 2x grid the spatial constants come
// scaled by WhyLumaSharpen (radii x scale, neighbourhood radius nbU), so the response matches WhyCam's on the 1x grid.
// P364: GATHER 1 - with the 1x grid's window sizes (min / max 3 x 3, USM radius 2, bilateral radius 1) the pixel's 6 x 6
// neighbourhood (-2 .. 3) is read once as nine 2 x 2 textureGather blocks instead of 44 texelFetch, and every sum takes its
// taps from it in the same order with the same expressions (the constant loops also let the compiler evaluate each of the
// USM's repeated weights once: the same values). InputBuffer is clamp-to-edge, so a block past the border repeats the edge
// texels as clamp() did (bit-identical). Other window sizes, and GATHER 0, take the loops.
#define GATHER 0
uniform sampler2D InputBuffer;      // Ya (.r), output grid
uniform sampler2D GfAB;             // (a, b) of the guided filter on the grid reduced by downU (sharp_box.glsl)
uniform int downU;                  // <= 0 -> 1
uniform float gfLcU;                // guided-filter local contrast (0 = off)
uniform float usmSigmaU;            // sigma of the USM base in output pixels (0 = no USM)
uniform float usmAmountU;
uniform float usmThreshU;           // pref_sharp_usm_thresh / 255
uniform float bilAmountU;           // 0 = no bilateral
uniform int bilRadiusU;             // window radius; <= 0 -> 1
uniform float bilSigmaSU;           // spatial sigma; <= 0 -> 0.5
uniform float bilSigmaLU;           // luma sigma (pref_sharp_bilateral_color / 100); <= 0 -> 0.1
uniform int nbU;                    // radius of the local min / max window (1 = 3x3); <= 0 -> 1
uniform float haloU;                // halo control 0..1
uniform float protectShadowsU;      // 0..1
uniform float protectHighlightsU;   // 0..1
uniform float grainU;               // pref_sharp_film_grain / 255 (0 = off)
uniform vec2 sobelU;                // P404: amount, threshold of the Sobel edge boost (amount 0 = off)
uniform float thinU;                // P404: edge thinning (0 = off)
out vec4 Output;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Output pixel x on a grid reduced by a box of f: centre (x + 0.5) / f - 0.5 as an integer base and a fraction.
void gridPos(int x, int f, out int base, out float frac) {
    int q = x / f;
    int r = x - q * f;
    float c = float(2 * r + 1 - f) / float(2 * f);
    if (c < 0.0) { base = q - 1; frac = c + 1.0; } else { base = q; frac = c; }
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    float c = texelFetch(InputBuffer, xy, 0).r;
    int nb = nbU > 0 ? nbU : 1;
    float lmin = c, lmax = c;
    float halo = haloU;
    float tot = 0.0;
#if GATHER == 1
    bool usmOn = usmSigmaU > 0.0 && abs(usmAmountU) > 0.001, bilOn = abs(bilAmountU) > 0.001;
    if (nb == 1 && (!usmOn || min(int(ceil(usmSigmaU * 3.5)), 8) == 2) && (!bilOn || (bilRadiusU > 0 ? bilRadiusU : 1) == 1)) {
        // t[(dy + 2) * 6 + dx + 2] = Ya at xy + (dx, dy), dx, dy = -2 .. 3; block (x0, y0) .. (x0 + 1, y0 + 1) by a gather at the
        // centre of texel (x0 + 1, y0 + 1): .w (x0, y0), .z (x0 + 1, y0), .x (x0, y0 + 1), .y (x0 + 1, y0 + 1)
        float t[36];
        vec2 inv = 1.0 / vec2(last + ivec2(1));
        for (int by = 0; by < 6; by += 2) {
            for (int bx = 0; bx < 6; bx += 2) {
                vec4 g = textureGather(InputBuffer, (vec2(xy + ivec2(bx - 2, by - 2)) + 1.0) * inv);
                t[by * 6 + bx] = g.w;
                t[by * 6 + bx + 1] = g.z;
                t[(by + 1) * 6 + bx] = g.x;
                t[(by + 1) * 6 + bx + 1] = g.y;
            }
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                float v = t[(dy + 2) * 6 + dx + 2];
                lmin = min(lmin, v);
                lmax = max(lmax, v);
            }
        }
        if (usmOn) {
            float inv2 = 1.0 / (2.0 * usmSigmaU * usmSigmaU);
            float sum = 0.0, wsum = 0.0;
            for (int dy = -2; dy <= 2; dy++) {
                float wy = exp(-float(dy * dy) * inv2);
                for (int dx = -2; dx <= 2; dx++) {
                    float w = wy * exp(-float(dx * dx) * inv2);
                    sum += t[(dy + 2) * 6 + dx + 2] * w;
                    wsum += w;
                }
            }
            float d = c - sum / wsum;
            if (d < 0.0) d = max(d, -0.35 * c);
            float mag = max(0.0, abs(d) - usmThreshU);
            if (halo > 0.001 && d < 0.0) mag *= 1.0 - halo;
            tot += sign(d) * mag * usmAmountU;
        }
        if (bilOn) {
            float ss = bilSigmaSU > 0.0 ? bilSigmaSU : 0.5;
            float sl = bilSigmaLU > 0.0 ? bilSigmaLU : 0.1;
            float sv = 1.0 / (2.0 * ss * ss), lv = 1.0 / (2.0 * sl * sl);
            float num = 0.0, den = 0.0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    float v = t[(dy + 2) * 6 + dx + 2];
                    float w = exp(-float(dx * dx + dy * dy) * sv) * exp(-(v - c) * (v - c) * lv);
                    num += v * w;
                    den += w;
                }
            }
            tot += (c - num / max(den, 0.0001)) * bilAmountU;
        }
    } else
#endif
    {
    for (int dy = -nb; dy <= nb; dy++) {
        for (int dx = -nb; dx <= nb; dx++) {
            float v = texelFetch(InputBuffer, clamp(xy + ivec2(dx, dy), ivec2(0), last), 0).r;
            lmin = min(lmin, v);
            lmax = max(lmax, v);
        }
    }
    if (usmSigmaU > 0.0 && abs(usmAmountU) > 0.001) {
        int r = min(int(ceil(usmSigmaU * 3.5)), 8);
        float inv = 1.0 / (2.0 * usmSigmaU * usmSigmaU);
        float sum = 0.0, wsum = 0.0;
        for (int dy = -r; dy <= r; dy++) {
            float wy = exp(-float(dy * dy) * inv);
            for (int dx = -r; dx <= r; dx++) {
                float w = wy * exp(-float(dx * dx) * inv);
                sum += texelFetch(InputBuffer, clamp(xy + ivec2(dx, dy), ivec2(0), last), 0).r * w;
                wsum += w;
            }
        }
        float d = c - sum / wsum;
        if (d < 0.0) d = max(d, -0.35 * c);
        float mag = max(0.0, abs(d) - usmThreshU);
        if (halo > 0.001 && d < 0.0) mag *= 1.0 - halo;
        tot += sign(d) * mag * usmAmountU;
    }
    if (abs(bilAmountU) > 0.001) {
        int r = bilRadiusU > 0 ? bilRadiusU : 1;
        float ss = bilSigmaSU > 0.0 ? bilSigmaSU : 0.5;
        float sl = bilSigmaLU > 0.0 ? bilSigmaLU : 0.1;
        float sv = 1.0 / (2.0 * ss * ss), lv = 1.0 / (2.0 * sl * sl);
        float num = 0.0, den = 0.0;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                float v = texelFetch(InputBuffer, clamp(xy + ivec2(dx, dy), ivec2(0), last), 0).r;
                float w = exp(-float(dx * dx + dy * dy) * sv) * exp(-(v - c) * (v - c) * lv);
                num += v * w;
                den += w;
            }
        }
        tot += (c - num / max(den, 0.0001)) * bilAmountU;
    }
    }
    float lc = lmax - lmin;
    if ((abs(sobelU.x) > 0.001 || abs(thinU) > 0.001) && all(greaterThan(xy, ivec2(0))) && all(lessThan(xy, last))) {
        // P404: v2.07 luma_compose_usm on the 3 x 3 neighbourhood (inner pixels, as there)
        float tl = texelFetch(InputBuffer, xy + ivec2(-1, -1), 0).r, tc = texelFetch(InputBuffer, xy + ivec2(0, -1), 0).r;
        float tr = texelFetch(InputBuffer, xy + ivec2(1, -1), 0).r, ml = texelFetch(InputBuffer, xy + ivec2(-1, 0), 0).r;
        float mr = texelFetch(InputBuffer, xy + ivec2(1, 0), 0).r, bl = texelFetch(InputBuffer, xy + ivec2(-1, 1), 0).r;
        float bc = texelFetch(InputBuffer, xy + ivec2(0, 1), 0).r, br = texelFetch(InputBuffer, xy + ivec2(1, 1), 0).r;
        if (abs(sobelU.x) > 0.001) {
            float gx = -tl - 2.0 * ml - bl + tr + 2.0 * mr + br, gy = -tl - 2.0 * tc - tr + bl + 2.0 * bc + br;
            if (sqrt(gx * gx + gy * gy) > sobelU.y) tot += (c - (tl + tc + tr + ml + mr + bl + bc + br) * 0.125) * sobelU.x;
        }
        if (abs(thinU) > 0.001) {
            float n1 = min(min(min(tl, tc), min(tr, ml)), min(min(mr, bl), min(min(bc, br), c)));
            float n2 = max(max(max(tl, tc), max(tr, ml)), max(max(mr, bl), max(max(bc, br), c)));
            if (n2 - n1 > 0.02) tot += ((tl + tc + tr + ml + mr + bl + bc + br - 8.0 * c < 0.0 ? n2 : n1) - c) * thinU;
        }
    }
    if (abs(gfLcU) > 0.001) {
        int d = downU > 0 ? downU : 1;
        vec2 ab;
        if (d == 1) {
            ab = texelFetch(GfAB, xy, 0).rg;
        } else {
            ivec2 lastAb = textureSize(GfAB, 0) - ivec2(1);
            int bx, by;
            float fx, fy;
            gridPos(xy.x, d, bx, fx);
            gridPos(xy.y, d, by, fy);
            vec2 a00 = texelFetch(GfAB, clamp(ivec2(bx, by), ivec2(0), lastAb), 0).rg;
            vec2 a10 = texelFetch(GfAB, clamp(ivec2(bx + 1, by), ivec2(0), lastAb), 0).rg;
            vec2 a01 = texelFetch(GfAB, clamp(ivec2(bx, by + 1), ivec2(0), lastAb), 0).rg;
            vec2 a11 = texelFetch(GfAB, clamp(ivec2(bx + 1, by + 1), ivec2(0), lastAb), 0).rg;
            ab = mix(mix(a00, a10, fx), mix(a01, a11, fx), fy);
        }
        // the local contrast of the guided filter is a linear-light difference against the radius-8 base: on the dark side of a
        // bright edge (a window frame against the sky) the base is several times the pixel and the term took 78 - 98 % of it,
        // a 5 - 12 px dark trench. The detail is bounded below by the pixel itself (c - q >= -c): a pixel keeps at least
        // 1 - gfLcU of its value; texture whose base is under 2 c is unchanged.
        tot += max(c - (ab.x * c + ab.y), -c) * gfLcU;
    }
    float ef = smoothstep(0.15, 0.40, lc);
    float md = mix(0.30, 0.0, halo);
    float damp = mix(1.0, md, ef);
    if (lc > 0.40) damp = mix(damp, 0.5 * md, smoothstep(0.40, 0.70, lc));
    tot *= damp;
    if (halo > 0.001) {
        float m = (1.0 - ef) * (0.03 + 0.15 * lc);
        float v = c + tot;
        if (v > lmax + m) tot -= (v - lmax - m) * halo;
        else if (v < lmin - m) tot += (lmin - m - v) * halo;
    }
    if (abs(tot) > 0.0001 && (protectShadowsU > 0.001 || protectHighlightsU > 0.001)) {
        float sl = 0.2 * protectShadowsU;
        float hl = 1.0 - 0.2 * protectHighlightsU;
        float mask = 1.0;
        if (c < sl && sl > 0.001) mask = (c / sl) * (c / sl);
        else if (c > hl && hl < 0.999) mask = c >= 1.0 ? 0.0 : ((1.0 - c) / (1.0 - hl)) * ((1.0 - c) / (1.0 - hl));
        tot *= clamp(mask, 0.0, 1.0);
    }
    if (abs(grainU) > 0.001) tot += (hash12(vec2(xy)) - 0.5) * 2.0 * grainU * max(0.0, 1.0 - 4.0 * (c - 0.5) * (c - 0.5));
    Output = vec4(abs(tot) > 0.0001 ? max(0.0, c + tot) : c, 0.0, 0.0, 1.0);
}
