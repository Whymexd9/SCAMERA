precision highp float;
precision highp sampler2D;
// WHY tone (WhyFusion), P333 (owner, 2026-10-25: «замени блок локального тонмапинга с гуидед фильтра на пирамиду Лапласа»,
// after bartwronski.com/2022/02/28/exposure-fusion-local-tonemapping-for-real-time-rendering and Mertens et al.): the exposure
// fusion of the luminance as a Laplacian pyramid blend instead of the guided filter of the weights. The synthetic exposures, their
// lumas L_k and weights p_k are the guided filter's (import why_fusion: prepare); every band of the lumas is blended with the
// weights blurred to that band's scale, so an edge keeps its contrast at every scale (no halo of the weights' blur) while flat
// areas take the smooth weights. Pyramid: level l + 1 = the 5-tap binomial (1 4 6 4 1) / 16 of level l at the even sites (coarse
// i = fine 2i, edges clamped); expand = linear interpolation onto the odd sites; the reconstruction is exact for any weights.
// Level 0 (whyLow) is never stored: level 1 is reduced from prepare() of the whyLow pixels, and the last pass prepares its pixel.
//   MODE 0: level 1 of the lumas L (the four exposures)   MODE 1: level 1 of the weights p (normalised per pixel)
//   MODE 2: reduce InputBuffer (RGBA) to the next level    MODE 3: the coarsest level, F = <p, L> / sum p
//   MODE 4: F_l = expand(F_l+1) + <p_l, L_l - expand(L_l+1)> / sum p_l
//   MODE 5: MODE 4 at level 0 (its L, p prepared from InputBuffer) plus P291's monotonic envelope (gf.glsl MODE 3's): "fused"
#define MODE 0
uniform sampler2D InputBuffer;  // MODE 0, 1, 5: whyLow (after bracket_dn), linear Rec.709; MODE 2: the level to reduce
uniform sampler2D LevelL;       // MODE 3, 4: lumas L of this level (RGBA)
uniform sampler2D LevelW;       // MODE 3, 4: weights p of this level (RGBA)
uniform sampler2D CoarseL;      // MODE 4, 5: lumas of the next coarser level
uniform sampler2D CoarseF;      // MODE 4, 5: fused luma of the next coarser level (.r)
#import why_fusion
out vec4 Output;

// Linear expansion of the coarser level at fine pixel x (coarse i sits on fine 2i).
vec4 expand(sampler2D t, ivec2 xy) {
    ivec2 last = textureSize(t, 0) - ivec2(1);
    ivec2 i0 = min(xy >> 1, last);
    ivec2 i1 = min(i0 + (xy & 1), last);
    vec4 a = texelFetch(t, i0, 0), b = texelFetch(t, ivec2(i1.x, i0.y), 0);
    vec4 c = texelFetch(t, ivec2(i0.x, i1.y), 0), d = texelFetch(t, i1, 0);
    return 0.25 * (a + b + c + d);
}

float blendBand(ivec2 xy, vec4 L, vec4 p) {
    vec4 band = L - expand(CoarseL, xy);
    vec4 w = max(p, vec4(0.0));
    return expand(CoarseF, xy).r + dot(w, band) / max(w.x + w.y + w.z + w.w, 0.000001);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    const float k[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);
#if MODE == 0 || MODE == 1 || MODE == 2
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    vec4 acc = vec4(0.0);
    for (int j = 0; j < 5; j++) {
        vec4 row = vec4(0.0);
        for (int i = 0; i < 5; i++) {
            vec4 v = texelFetch(InputBuffer, clamp(2 * xy + ivec2(i - 2, j - 2), ivec2(0), last), 0);
#if MODE != 2
            float I;
            vec4 p, L;
            prepare(v.rgb, I, p, L);
#if MODE == 0
            v = L;
#else
            v = p;
#endif
#endif
            row += k[i] * v;
        }
        acc += k[j] * row;
    }
    Output = acc;
#elif MODE == 3
    vec4 w = max(texelFetch(LevelW, xy, 0), vec4(0.0));
    Output = vec4(dot(w, texelFetch(LevelL, xy, 0)) / max(w.x + w.y + w.z + w.w, 0.000001), 0.0, 0.0, 1.0);
#elif MODE == 4
    Output = vec4(blendBand(xy, texelFetch(LevelL, xy, 0), texelFetch(LevelW, xy, 0)), 0.0, 0.0, 1.0);
#else
    vec3 rgb = texelFetch(InputBuffer, xy, 0).rgb;
    float I;
    vec4 p, L;
    prepare(rgb, I, p, L);
    float fusedL = blendBand(xy, L, p);
    // P291: the monotonic envelope of the fusion's own curve, added to the blended luma (gf.glsl MODE 3)
    float sw = satShare(rgb);
    if (monoU > 0.0 && I > 0.0) {
        float rise = monoU * (1.0 - sw);
        float f = fusedAt(I, sw), env = f;
        for (int j = 1; j <= 36; j++) {
            float s = float(j) * (0.25 * 0.6931472);    // 1/4 EV steps, 9 EV down
            float fj = fusedAt(I * exp(-s), sw);
            if (fj >= 0.5) env = max(env, fj + rise * s);
        }
        fusedL += env - f;
    }
    Output = vec4(fusedL, 0.0, 0.0, 1.0);
#endif
}
