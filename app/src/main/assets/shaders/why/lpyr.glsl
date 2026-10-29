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
//   MODE 7 (P391): level 1 of the guide's log light and the per-pixel fusion, (G, Fpp) = (log2(I + bandEdgeU.z), dot(p, L)) (RG16F;
//           MODE 2 reduces it further, 2 levels beyond the pyramid's)
//   MODE 8 (P391): the edge measure D of a level l >= 1: |G_l - G_l+3| (G_l+3 bilinear from its nodes, what the chain of expands
//           gives) less k of its noise sigmas in quadrature (R16F, EV)
//   MODE 9 (P391): MODE 8 at level 0 (G_0 of each whyLow pixel; R16F at whyLow's size)
//   MODE 10 (P391): the edge share of a level l >= 1 (RG16F): r = max(the ramp of this level's cone max of D, the carry: the 3 x 3
//           max of the finer level's share round 2 x), g = the carry alone (the coarsest level's share, the fractional depth's base)
#define MODE 0
// P378 SKY 1 (WhyFusion, MODE 5 with the guided share and a sky mask): the clear sky keeps the pyramid's fusion (M1); 0: the
// shader before P378, token for token.
#define SKY 0
// P391 EDGE_BANDS 1 (owner, 2026-10-28: an edge-aware pyramid without the halos at window frames, twigs, lamps, the horizon): every
// band of MODE 4 / 5 is the band P of the per-pixel fusion Fpp = dot(p, L) (its own Gaussian pyramid, MODE 7) plus the pyramid's
// excess E = M - P (M: the band blended by the weights, as before; E: its local-contrast gain) times 1 - a. a is the share of an
// edge: D = |G_l - G_l+3|, the guide's log light at the level against its Gaussian 3 levels down (about half the EV of a step, the
// light's own deviation in a texture: a +-0.5 EV texture reads as a 1 EV step, not as a 2 EV one as the level's band does), less
// k noise sigmas in quadrature; through a ramp in the EV of a step (bandEdgeU.xy, the level's step response folded in), the max of
// D less a fall per level px over (2 r + 1)^2 (a strong edge reaches the band's tails and the sky beside a thin twig, a texture's
// peak hardly spreads), at least the 3 x 3 max of the finer level's share (a coarse band never keeps more excess than the finer one
// cut round the same edge: no dark fringe beside thin dark objects). The coarsest level is the pyramid's blend moved towards Fpp's
// Gaussian by the carried share (a = 0 everywhere: the pyramid; a = 1: the per-pixel fusion). Flat areas give fusedAt; an edge of 2
// EV and more takes the per-pixel fusion's bands, a +-0.5 EV texture keeps the pyramid's (host GL, 12 MP whyLow at 5 levels: the
// horizon's halo 0.032 -> 0.0008, a window frame's 0.071 -> 0.0006, a lamp's dark ring -0.012 -> -0.0001, twigs 0.006 .. 0.011
// beside them -> -0.0001 .. +0.0009 (no fringe), rungs in a glow p95 0.014 -> 0.0008, a 2 EV step 0.070 -> 0.0024; a +-0.5 EV
// texture >= 0.90 x the pyramid's contrast; a 1 - 1.5 EV step keeps 0.032 - 0.036, the pyramid's pop / the shipped shares').
// Review of P391: the level's own band |G_l - expand G_l+1| as the measure (0.15 .. 0.5 EV) kept +-0.5 EV textures at x0.34 - 0.57
// and, cut on the fine levels only, left a dark fringe of -0.008 beside thin dark twigs.
// Smoothing every level's weights by a guided filter instead of the binomial (Kou / Li) left the frame and horizon halos as they
// were (0.033 - 0.046): their height is made at the finest level by the edge's own band, the coarse levels only widen it.
// noiseBandU (item 4): on the fine levels the excess passes only where the guide's band stands x .. y noise sigmas out of the
// frame's noise (the grain is not lifted beyond the curve's own gain; a band the pyramid lowers is never touched). lowCutU (item 2):
// the lowest band's reconstruction is moved by 1 - f towards the base of one level less (f = 0: one level less, exactly).
// 0: the shader before P391, token for token.
#define EDGE_BANDS 0
// P400 / P401 WINDOW 1 (owner, 2026-10-29, the ship's bridge: «на кранах видно, такую проблему мы уже решали очень давно»; «изучи что
// было на версии 31091», «делай. При этом у нас остаётся пирамида Лапласа»): build 31091 fused with the kernel's weights (centre
// 0.6, highlight layer 0.8) and a darkest exposure of 2^-1.5 effH: a window's clouds, sun, crane and tower kept their contrast, the
// frame ~0.45 EV darker, a dark ring round a lamp. P291 (headroom weights: centre 0.75, highlight layer 0.4, white lit paper, no
// ring) and P340 (the darkest exposure down to the ceiling) put everything from ~0.4 to ~8 x white on 0.86 .. 0.99. In a room (the
// frame's median under daylight, set by WhyFusion) a pixel inside a daylit area - the log light's Gaussian at the window level,
// winU.yz: EV 10.5 .. 12 - above the mid-tones (I 0.12 .. 0.3: the room and a backlit face keep theirs) takes winU.x of Fused31:
// this pyramid drawn with build 31091's weights and darkest exposure and P291's monotonic envelope (no ring). The room, lamp-lit
// paper and every scene outside keep the fusion as it is. 0: the shader before P400, token for token.
#define WINDOW 0
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
#if EDGE_BANDS == 1 || MODE >= 7
uniform sampler2D LevelGF;      // MODE 3, 4, 8: (G, Fpp) of this level (RG)
uniform sampler2D CoarseGF;     // MODE 4, 5: (G, Fpp) of the next coarser level
uniform sampler2D RefG;         // MODE 8, 9: (G, Fpp) of the level 3 down (the edge measure's reference)
uniform sampler2D LevelA;       // MODE 3, 4: the edge share of this level (MODE 10's RG); MODE 5: level 0's D (MODE 9's); MODE 10: D
uniform sampler2D FineA;        // MODE 10: level 0's D (level 1) or the finer level's share (MODE 10's)
uniform vec4 bandEdgeU;         // x, y: the edge ramp of this level (EV of D); z: the guide's light floor (x AE) of G; w: the strength
uniform ivec2 bandReachU;       // x: the reach r of the cone max of D round a pixel (level px; MODE 10: < 0 none, the coarsest level);
                                // y: MODE 10 of level 1, level 0's reach (the carry: its cone one px wider round 2 x)
uniform vec4 coneU;             // x: the fall of D per level px of this level (EV); MODE 10: y, z, w: level 0's ramp and fall (level 1:
                                // FineA holds level 0's D), y >= z: FineA holds the finer level's share (levels >= 2)
uniform float refScaleU;        // MODE 8, 9: the node spacing of RefG in this level's px (8)
uniform vec4 noiseBandU;        // x, y: the excess passes from x to y noise sigmas of the guide's band (y <= x: off); z, w: S, O of
                                // the guide I's noise (variance z I + w, the fusion's domain: x AE)
uniform vec4 noiseBetaU;        // the white-noise factor of the band of levels 0 .. 3 (0: no cap on that level)
uniform int levelU;             // MODE 4: this level's index (1 ..)
uniform float lowCutU;          // MODE 4 (the lowest band), 5 (one level): 1 - f of the fractional depth; 0 / unset: whole levels
uniform vec3 bandNoiseU;        // MODE 8, 9: k N_l, the noise sigmas of D taken out in quadrature (y, z: S, O of the guide I's
                                // noise); x <= 0 / unset: none
#endif
#if WINDOW == 1
uniform vec4 winU;              // x: Fused31's share, y, z: the window's log light ramp (log2(I + bandEdgeU.z)), w: unused
uniform float winScaleU;        // the node spacing of WinMean in whyLow px
uniform sampler2D WinMean;      // the log light's Gaussian at the window level (MODE 7's pyramid, .r)
uniform sampler2D Fused31;      // the fused luma of build 31091's weights and darkest exposure (this shader's MODE 5, .r)
#endif
// P404: the display standard of the fusion's lumas (import why_fusion; 0 = the pure power, as before)
#define GAMMA_TYPE 0
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

#if WINDOW == 1
// P400: a level's .r at whyLow pixel xy, linear between its nodes (node i on i * scale)
float nodeAt(sampler2D t, ivec2 xy, float scale) {
    ivec2 last = textureSize(t, 0) - ivec2(1);
    vec2 u = vec2(xy) / scale;
    ivec2 i0 = min(ivec2(floor(u)), last);
    ivec2 i1 = min(i0 + ivec2(1), last);
    vec2 f = clamp(u - vec2(i0), 0.0, 1.0);
    float a = texelFetch(t, i0, 0).r, b = texelFetch(t, ivec2(i1.x, i0.y), 0).r;
    float c = texelFetch(t, ivec2(i0.x, i1.y), 0).r, d = texelFetch(t, i1, 0).r;
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
#endif

float blendBand(ivec2 xy, vec4 L, vec4 p) {
    vec4 band = L - expand(CoarseL, xy);
    vec4 w = max(p, vec4(0.0));
    return expand(CoarseF, xy).r + dot(w, band) / max(w.x + w.y + w.z + w.w, 0.000001);
}

#if EDGE_BANDS == 1 || MODE == 8 || MODE == 9
// P391: k noise sigmas of D at a level whose light (log2 Gaussian) is g: noise alone is no edge (a deep shadow at SNR 3 has a
// light noise of ~0.5 EV at level 0, near the ramp's start)
float edgeNoise(float g) {
    if (bandNoiseU.x <= 0.0) return 0.0;
    float z = bandEdgeU.z;
    float Il = max(exp2(g) - z, 0.0);
    return bandNoiseU.x * sqrt(max(bandNoiseU.y * Il + bandNoiseU.z, 0.0)) / ((Il + z) * 0.6931472);
}

// P391: G of the level 3 down at xy, linear between its nodes (node i on i * refScaleU): what the chain of the pyramid's expand gives
float refAt(ivec2 xy) {
    ivec2 last = textureSize(RefG, 0) - ivec2(1);
    vec2 u = vec2(xy) / refScaleU;
    ivec2 i0 = min(ivec2(floor(u)), last);
    ivec2 i1 = min(i0 + ivec2(1), last);
    vec2 f = clamp(u - vec2(i0), 0.0, 1.0);
    float a = texelFetch(RefG, i0, 0).r, b = texelFetch(RefG, ivec2(i1.x, i0.y), 0).r;
    float c = texelFetch(RefG, ivec2(i0.x, i1.y), 0).r, d = texelFetch(RefG, i1, 0).r;
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// P391: the edge measure D of light g at xy: |g - G_l+3|, its noise taken out in quadrature
float edgeMeasure(float g, ivec2 xy) {
    float d = abs(g - refAt(xy));
    float n = edgeNoise(g);
    return sqrt(max(d * d - n * n, 0.0));
}
#endif

#if EDGE_BANDS == 1 || MODE == 10
// P391: the largest D(q) - fall x max(Chebyshev |q - c| - slack, 0) of t's .r over |q - c| <= r + slack (slack 1: the 3 x 3 max of
// the window's max round each of c's neighbours)
float coneMax(sampler2D t, ivec2 c, int r, float fall, int slack) {
    ivec2 last = textureSize(t, 0) - ivec2(1);
    int w = r + slack;
    float b = -1024.0;
    for (int j = -w; j <= w; j++)
        for (int i = -w; i <= w; i++)
            b = max(b, texelFetch(t, clamp(c + ivec2(i, j), ivec2(0), last), 0).r - fall * float(max(max(abs(i), abs(j)) - slack, 0)));
    return b;
}
#endif

#if EDGE_BANDS == 1
// P391: the base of the coarsest level at xy (MODE 3; MODE 4 at the lowest band: one level less): the pyramid's blend of LevelL by
// LevelW moved towards the per-pixel fusion's Gaussian (LevelGF.g) by the carried share (LevelA.g) - bit for bit the same in both
float baseOf(ivec2 xy) {
    vec4 w = max(texelFetch(LevelW, xy, 0), vec4(0.0));
    float pyr = dot(w, texelFetch(LevelL, xy, 0)) / max(w.x + w.y + w.z + w.w, 0.000001);
    float s = texelFetch(LevelA, xy, 0).g * clamp(bandEdgeU.w, 0.0, 1.0);
    return pyr + s * (texelFetch(LevelGF, xy, 0).g - pyr);
}

// P391: the band of level lvl: the per-pixel fusion's band P plus the pyramid's excess E = M - P times 1 - a (a = 0 and no cap: M
// itself); on the fine levels (noiseBetaU > 0) an excess that raises the band (E P > 0) passes only where the guide's band bG stands
// out of the noise of the guide at the level's light g (its log2 Gaussian): sigma_G = beta sigma_I(I) / ((I + z) ln 2)
float edgeBand(float M, float P, float bG, float g, int lvl, float a) {
    float E = M - P;
    float Ec = E;
    if (noiseBandU.y > noiseBandU.x && lvl < 4 && E * P > 0.0) {
        float beta = lvl == 0 ? noiseBetaU.x : lvl == 1 ? noiseBetaU.y : lvl == 2 ? noiseBetaU.z : noiseBetaU.w;
        if (beta > 0.0) {
            float z = bandEdgeU.z;
            float Il = max(exp2(g) - z, 0.0);
            float sG = beta * sqrt(max(noiseBandU.z * Il + noiseBandU.w, 0.0)) / ((Il + z) * 0.6931472);
            Ec = E * smoothstep(noiseBandU.x, noiseBandU.y, bG / max(sG, 1.0e-30));
        }
    }
    return a <= 0.0 && Ec == E ? M : P + (1.0 - a) * Ec;
}
#endif

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    const float k[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);
#if MODE == 0 || MODE == 1 || MODE == 2 || MODE == 6 || MODE == 7
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    vec4 acc = vec4(0.0);
    for (int j = 0; j < 5; j++) {
        vec4 row = vec4(0.0);
        for (int i = 0; i < 5; i++) {
            vec4 v = texelFetch(InputBuffer, clamp(2 * xy + ivec2(i - 2, j - 2), ivec2(0), last), 0);
#if MODE == 6
            v = vec4(log2(guideOf(v.rgb) + edgeMixU.z));
#elif MODE == 7
            {
                float I7;
                vec4 p7, L7;
                prepare(v.rgb, I7, p7, L7);
                v = vec4(log2(I7 + bandEdgeU.z), dot(p7, L7), 0.0, 0.0);
            }
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
#elif MODE == 8
    Output = vec4(edgeMeasure(texelFetch(LevelGF, xy, 0).r, xy), 0.0, 0.0, 1.0);
#elif MODE == 9
    Output = vec4(edgeMeasure(log2(guideOf(texelFetch(InputBuffer, xy, 0).rgb) + bandEdgeU.z), xy), 0.0, 0.0, 1.0);
#elif MODE == 10
    {
        float own = 0.0;
        if (bandReachU.x >= 0) own = smoothstep(bandEdgeU.x, bandEdgeU.y, coneMax(LevelA, xy, bandReachU.x, coneU.x, 0));
        float carry = coneU.y < coneU.z ? smoothstep(coneU.y, coneU.z, coneMax(FineA, 2 * xy, bandReachU.y, coneU.w, 1))
                : coneMax(FineA, 2 * xy, 1, 0.0, 0);
        Output = vec4(max(own, carry), carry, 0.0, 1.0);
    }
#elif MODE == 3
#if EDGE_BANDS == 1
    // P391: the pyramid's blend moved towards the per-pixel fusion's Gaussian by the carried edge share
    Output = vec4(baseOf(xy), 0.0, 0.0, 1.0);
#else
    vec4 w = max(texelFetch(LevelW, xy, 0), vec4(0.0));
    Output = vec4(dot(w, texelFetch(LevelL, xy, 0)) / max(w.x + w.y + w.z + w.w, 0.000001), 0.0, 0.0, 1.0);
#endif
#elif MODE == 4
#if EDGE_BANDS == 1
    {
        vec4 w4 = max(texelFetch(LevelW, xy, 0), vec4(0.0));
        float M4 = dot(w4, texelFetch(LevelL, xy, 0) - expand(CoarseL, xy)) / max(w4.x + w4.y + w4.z + w4.w, 0.000001);
        vec2 gf4 = texelFetch(LevelGF, xy, 0).rg, gc4 = expand(CoarseGF, xy).rg;
        float a4 = texelFetch(LevelA, xy, 0).r * clamp(bandEdgeU.w, 0.0, 1.0);
        float v4 = expand(CoarseF, xy).r + edgeBand(M4, gf4.y - gc4.y, abs(gf4.x - gc4.x), gf4.x, levelU, a4);
        // the fractional depth: the lowest band moved by 1 - f towards the base of one level less
        if (lowCutU > 0.0) v4 = lowCutU >= 1.0 ? baseOf(xy) : mix(v4, baseOf(xy), lowCutU);
        Output = vec4(v4, 0.0, 0.0, 1.0);
    }
#else
    Output = vec4(blendBand(xy, texelFetch(LevelL, xy, 0), texelFetch(LevelW, xy, 0)), 0.0, 0.0, 1.0);
#endif
#else
    vec3 rgb = texelFetch(InputBuffer, xy, 0).rgb;
    float I;
    vec4 p, L;
    prepare(rgb, I, p, L);
#if EDGE_BANDS == 1
    float fusedL;
    {
        vec4 w5 = max(p, vec4(0.0));
        float M5 = dot(w5, L - expand(CoarseL, xy)) / max(w5.x + w5.y + w5.z + w5.w, 0.000001);
        float g5 = log2(I + bandEdgeU.z);
        vec2 gc5 = expand(CoarseGF, xy).rg;
        float a5 = smoothstep(bandEdgeU.x, bandEdgeU.y, coneMax(LevelA, xy, bandReachU.x, coneU.x, 0)) * clamp(bandEdgeU.w, 0.0, 1.0);
        fusedL = expand(CoarseF, xy).r + edgeBand(M5, dot(p, L) - gc5.y, abs(g5 - gc5.x), g5, 0, a5);
        // one level: the fractional depth moves it towards no level, the per-pixel fusion
        if (lowCutU > 0.0) fusedL = lowCutU >= 1.0 ? dot(p, L) : mix(fusedL, dot(p, L), lowCutU);
    }
#else
    float fusedL = blendBand(xy, L, p);
#endif
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
#if WINDOW == 1
    if (winU.x > 0.0 && I > 0.0) {
        float inWindow = smoothstep(winU.y, winU.z, nodeAt(WinMean, xy, winScaleU));
        float share = clamp(winU.x, 0.0, 1.0) * inWindow * smoothstep(0.12, 0.3, I);
        if (share > 0.0) fusedL = mix(fusedL, texelFetch(Fused31, xy, 0).r, share);
    }
#endif
    Output = vec4(fusedL, 0.0, 0.0, 1.0);
#endif
}
