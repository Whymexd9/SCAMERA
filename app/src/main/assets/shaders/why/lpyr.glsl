precision highp float;
precision highp sampler2D;
// WHY tone (WhyFusion), P333 (owner, 2026-10-25: «замени блок локального тонмапинга с гуидед фильтра на пирамиду Лапласа»,
// after bartwronski.com/2022/02/28/exposure-fusion-local-tonemapping-for-real-time-rendering and Mertens et al.): the exposure
// fusion of the luminance as a Laplacian pyramid blend instead of the guided filter of the weights. The synthetic exposures, their
// lumas L_k and weights p_k are the guided filter's (import why_fusion: prepare); every band of the lumas is blended with the
// weights blurred to that band's scale (a plain binomial blur, not edge-aware). Flat areas take the per-pixel fusion exactly; a
// strong edge gets Mertens' halo: a bright band on its bright side and a dark band on its dark side about as wide as the coarse
// levels (host, 12 MP whyLow at ef_coarse 48: about +-0.03-0.05 of fused luma over ~30 whyLow px; the guided filter, edge-aware,
// stays within ~1 %), and a small light a dark ring; a larger ef_coarse (fewer levels) narrows it, its height stays. Pyramid: level l + 1 = the 5-tap binomial (1 4 6 4 1) / 16 of level l at the even sites (coarse
// i = fine 2i, edges clamped); expand = linear interpolation onto the odd sites; the reconstruction is exact for any weights.
// Level 0 (whyLow) is never stored: level 1 is reduced from prepare() of the whyLow pixels, and the last pass prepares its pixel.
//   MODE 0: level 1 of the lumas L (the four exposures)   MODE 1: level 1 of the weights p (normalised per pixel)
//   MODE 2: reduce InputBuffer (RGBA) to the next level    MODE 3: the coarsest level, F = <p, L> / sum p
//   MODE 4: F_l = expand(F_l+1) + <p_l, L_l - expand(L_l+1)> / sum p_l
//   MODE 5: MODE 4 at level 0 (its L, p prepared from InputBuffer), the saturated-light share back to the per-pixel fusion (P310),
//           plus P291's monotonic envelope (gf.glsl MODE 3's): "fused"
//   MODE 6: level 1 of the guide's log light log2(I + edgeMixU.z) (R16F; MODE 2 reduces it further): the edge share of MODE 5
#define MODE 0
// P378 SKY 1 (WhyFusion, MODE 5 with the guided share and a sky mask): the clear sky keeps the pyramid's fusion (M1); 0: the
// shader before P378, token for token.
#define SKY 0
uniform sampler2D InputBuffer;  // MODE 0, 1, 5: whyLow (after bracket_dn), linear Rec.709; MODE 2: the level to reduce
uniform sampler2D LevelL;       // MODE 3, 4: lumas L of this level (RGBA)
uniform sampler2D LevelW;       // MODE 3, 4: weights p of this level (RGBA)
uniform sampler2D CoarseL;      // MODE 4, 5: lumas of the next coarser level
uniform sampler2D CoarseF;      // MODE 4, 5: fused luma of the next coarser level (.r)
uniform sampler2D GuidedF;      // MODE 5, P349: the guided filter's fused luma of the same pixels (why/gf MODE 3, .r)
uniform vec2 guidedMixU;        // MODE 5, P349: its share over the guide I (x AE) from x to y; y <= x / unset: the pyramid alone
// MODE 5 (window halo): the guided filter's share also where a pixel's log light differs from its coarsest level's (the guide
// pyramid GuideC, a node every w px): over x .. y EV of |log2(I + z) - coarse|, both sides of a strong edge (Mertens' halo:
// a glow on the bright side, a dark band on the dark side, as wide as the coarse levels); y <= x / unset: off
uniform sampler2D GuideC;
uniform vec4 edgeMixU;
#if SKY == 1
uniform sampler2D WhySky;       // P378: the sky mask (WhySky; whyLow or reduced, linear: normalised coordinates): g = the clear sky
uniform float skyGuidedU;       // P378 (M1): how much of the guided share the clear sky gives up (the sky term's strength)
#endif
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

// The coarsest guide level at fine pixel xy: linear between its nodes (node i on fine i * edgeMixU.w), what the chain of the
// pyramid's expand gives.
float coarseGuide(ivec2 xy) {
    ivec2 last = textureSize(GuideC, 0) - ivec2(1);
    vec2 u = vec2(xy) / edgeMixU.w;
    ivec2 i0 = min(ivec2(floor(u)), last);
    ivec2 i1 = min(i0 + ivec2(1), last);
    vec2 f = clamp(u - vec2(i0), 0.0, 1.0);
    float a = texelFetch(GuideC, i0, 0).r, b = texelFetch(GuideC, ivec2(i1.x, i0.y), 0).r;
    float c = texelFetch(GuideC, ivec2(i0.x, i1.y), 0).r, d = texelFetch(GuideC, i1, 0).r;
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float blendBand(ivec2 xy, vec4 L, vec4 p) {
    vec4 band = L - expand(CoarseL, xy);
    vec4 w = max(p, vec4(0.0));
    return expand(CoarseF, xy).r + dot(w, band) / max(w.x + w.y + w.z + w.w, 0.000001);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    const float k[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);
#if MODE == 0 || MODE == 1 || MODE == 2 || MODE == 6
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    vec4 acc = vec4(0.0);
    for (int j = 0; j < 5; j++) {
        vec4 row = vec4(0.0);
        for (int i = 0; i < 5; i++) {
            vec4 v = texelFetch(InputBuffer, clamp(2 * xy + ivec2(i - 2, j - 2), ivec2(0), last), 0);
#if MODE == 6
            v = vec4(log2(guideOf(v.rgb) + edgeMixU.z));
#elif MODE != 2
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
    float sw = satShare(rgb);
    // P291: the monotonic envelope of the fusion's own curve, added to the blended luma (gf.glsl MODE 3)
    float lift = 0.0;
    if (monoU > 0.0 && I > 0.0) {
        float rise = monoU * (1.0 - sw);
        float f = fusedAt(I, sw), env = f;
        for (int j = 1; j <= 36; j++) {
            float s = float(j) * (0.25 * 0.6931472);    // 1/4 EV steps, 9 EV down
            float fj = fusedAt(I * exp(-s), sw);
            if (fj >= 0.5) env = max(env, fj + rise * s);
        }
        lift = env - f;
    }
    // Window halo (owner's window burst, 2026-10-28: a glow 40-60 px wide inside the glass along each frame bar, the bar darker,
    // the transom outlined): Mertens' halo of every strong edge, not only of light above white (P349). Where a pixel's log light
    // differs from the coarsest level's (the reach of the halo, both sides of the edge) its band blend is the guided filter's
    // (edge-aware); P310 and P291 then act on it as on the pyramid's. Off: edgeMixU.y <= x.
    if (edgeMixU.y > edgeMixU.x) {
        float e = smoothstep(edgeMixU.x, edgeMixU.y, abs(log2(I + edgeMixU.z) - coarseGuide(xy)));
        if (e > 0.0) fusedL = mix(fusedL, max(texelFetch(GuidedF, xy, 0).r, 0.0) - lift, e);
    }
    // P310: saturated bright light keeps the per-pixel fusion (dot(p, L) = fusedAt(I, sw)): the coarse bands would build a narrow
    // light from the room's bright exposures, where it clips (a violet 2-16 px LED bar at 8 x white in a 0.03 room: fused 0.98-1.04
    // instead of the guided filter's 0.795, flat white after the tone)
    fusedL = mix(fusedL, dot(p, L), sw);
    fusedL += lift;
    // P349 (owner, 2026-10-26, the sunset with the ladder: «на ступеньке обводка ещё больше стала»): Mertens' halo of a dark
    // object in light above white - a bright band under each rung and a dark one above it, ~100 px, in the sun's glow (the warm
    // glow takes the pyramid since P348) - where ArkCam's guided filter, edge-aware, has none. Light above white takes the
    // guided filter's fusion, the mid-tones and shadows keep the pyramid's; on flat areas the two agree, so the share needs no
    // spatial smoothing.
#if SKY == 1
    // P378 (M1, owner: the reference app's clouds modelled, ours flat - local contrast 1.3x lower in the sky, 1.8x in the tops):
    // the guided filter, edge-aware, follows a cloud's own texture above white and flattens it; the clear sky (away from dark
    // objects and clipped cores, where Mertens' halo would show) keeps the pyramid's bands
    float clearSky = clamp(textureLod(WhySky, (vec2(xy) + 0.5) / vec2(textureSize(InputBuffer, 0)), 0.0).g, 0.0, 1.0);
    if (guidedMixU.y > guidedMixU.x) fusedL = mix(fusedL, max(texelFetch(GuidedF, xy, 0).r, 0.0),
                                                  smoothstep(guidedMixU.x, guidedMixU.y, I) * (1.0 - skyGuidedU * clearSky));
#else
    if (guidedMixU.y > guidedMixU.x) fusedL = mix(fusedL, max(texelFetch(GuidedF, xy, 0).r, 0.0), smoothstep(guidedMixU.x, guidedMixU.y, I));
#endif
    Output = vec4(fusedL, 0.0, 0.0, 1.0);
#endif
}
