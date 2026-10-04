precision highp float;
precision highp sampler2D;
// ARK luma sharpening (ArkLumaSharpen): luma_compose of ArkCam 1.23 [fc_cl_source.cl:303-503; ark_sharpen.md 3.2,
// reference research/hybrid5/ref/sharpen/ark_sharpen_ref.py luma_compose]: USM (with noise threshold), bilateral
// high-boost and guided-filter local contrast summed, damped on strong 3x3 contrast, clamped to the local min/max
// (halo control), faded towards white (protect highlights) and black (protect shadows), optional luma grain.
// Sobel and edge thinning (off in the X8U config) are not ported. On the Sabre 2x grid the spatial constants come
// scaled by ArkLumaSharpen (radii x scale, neighbourhood radius nbU), so the response matches ArkCam's on the 1x grid.
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
    for (int dy = -nb; dy <= nb; dy++) {
        for (int dx = -nb; dx <= nb; dx++) {
            float v = texelFetch(InputBuffer, clamp(xy + ivec2(dx, dy), ivec2(0), last), 0).r;
            lmin = min(lmin, v);
            lmax = max(lmax, v);
        }
    }
    float lc = lmax - lmin;
    float halo = haloU;
    float tot = 0.0;
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
        tot += (c - (ab.x * c + ab.y)) * gfLcU;
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
