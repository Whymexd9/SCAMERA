precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform sampler2D GainMap;
uniform vec3 whitePoint;
uniform vec3 clipLevel;
uniform vec2 inverseSize;
uniform ivec2 cropOffset;
// Per-channel highlight recovery (SCAM Hybrid). Unset uniforms read 0 = the legacy neutralisation at clipLevel below.
uniform int hlModeU;              // 1: per-channel recovery (clipLoU/clipHiU), 0: legacy
uniform vec3 clipLoU;             // clip of the base frames per camera channel, raw units before WB/LSC (<= 0: none)
uniform vec3 clipHiU;             // clip of the Bento ultrashort (about k * white) per channel (<= 0: none)
// 1: the ultrashort clips below the worker's flag threshold (a measured plateau under 0.975 k, OnePlus 15: 0.915 k), so
// the worker never flags it (no bit 5, usClipped 0): inside the Bento mask a pixel without flags may still be clipped at
// clipHiU. 0 (unset): such a pixel is unclipped ultrashort content, as before (P58, research/highlights/ONEPLUS15_PINK.md).
uniform int clipHiUnflaggedU;
uniform float hlStrengthU;        // 0..1
uniform int clipFlagsU;           // 1: ClipFlags holds the worker's per-pixel clip flags (uint8 as R8)
uniform sampler2D ClipFlags;
uniform int chromaU;              // 1: Chroma8/32/128 hold the local highlight light (hlrecovery/chanprep + 2x reduce)
uniform sampler2D Chroma8;
uniform sampler2D Chroma32;
uniform sampler2D Chroma128;
uniform int blockU;               // output pixels per Chroma8 texel
uniform float chromaLimitU;       // deviation of the local chroma from neutral where it stops being trusted (0: 0.35)
uniform float defringeU;          // clip-border colour suppression 0..1 (0 = unset: 0.85; < 0: off)
// P362: «Inpaint opposed» (RawTherapee / darktable, hlrecovery/opposed) where the pixel holds no ultrashort content.
uniform int oppU;                 // 1: on
uniform vec3 oppChromaU;          // the global chrominance correction per channel (white-balanced)
// 1 (SCAM Hybrid): the worker's signed values pass on. The merge keeps its noise signed and clips once; a per-pixel
// max(0) here lifted the mean of every channel near zero by up to half its noise - x1.5 red along the frame edge of a dark
// teal curtain, where fewer donors overlap (OPPO X8U 2026-10-07), and red mottling of dark saturated colours once the WHY
// tone lifted them. The clamp now follows the noise reduction (ScamDenoise / ScamHdrDenoise output) or an average (why/low,
// why/combine). 0 (unset): clamp at zero (SCAM HDR route, whose stages expect it).
uniform int signedU;
out vec3 Output;

// Clip level of every channel at this pixel (raw units); vec3(-1) = no channel can be clipped here.
// Worker flags (scam-hybrid.h, clipFlags trailer): bits 0-2 = R/G/B from the clipped mean, 3 = a clipped sample
// was excluded nearby (clip border), 4 = inside the Bento mask, 5 = the clipped mean holds the ultrashort frame,
// 6 = border colour already rebuilt by the worker (main: defringe). Keep in sync with hlrecovery/chanprep.glsl.
vec3 levelAt(ivec2 p) {
    bool lo = clipLoU.g > 0.0, hi = clipHiU.g > 0.0;
    if (clipFlagsU == 0) return hi ? clipHiU : (lo ? clipLoU : vec3(-1.0));
    uint f = uint(texelFetch(ClipFlags, p, 0).r * 255.0 + 0.5);
    // Every colour from unclipped samples - except inside the Bento mask when the ultrashort's clip lies below the
    // worker's threshold (clipHiUnflaggedU): there the worker cannot tell, and leaving those pixels alone while the
    // bit-3 cells next to them were recovered gave a pink sky (R and G at the clip, B measured) with white blotches
    // (OnePlus 15 2026-10-08).
    if ((f & 15u) == 0u) return clipHiUnflaggedU != 0 && hi && (f & 16u) != 0u ? clipHiU : vec3(-1.0);
    if ((f & 32u) != 0u) return hi ? clipHiU : clipLoU;     // clipped mean of the ultrashort: k * white
    if ((f & 7u) != 0u) return lo ? clipLoU : clipHiU;      // clipped mean of the base frames: white
    // Clip border, every colour from unclipped samples. Inside the Bento mask the value is ultrashort content (up to k):
    // only the ultrashort's own clip can censor it, and none when it never clipped. The excluded clipped samples there
    // are the base frame's, kept at a weight 1 - m (float rounding of the mask, or a mask lowered by the SCAM intensity
    // error); their white (1.0) says nothing about a value that may exceed it.
    if ((f & 16u) != 0u) return hi ? clipHiU : vec3(-1.0);
    return lo ? clipLoU : clipHiU;
}

// The light around output pixel o (the statistics are on the output grid, the 16:9 crop offset is applied inside
// hlrecovery/chanprep), from the bright unclipped pixels: rgb = its chromaticity (mean 1), the
// colour of the light that clipped here; a = its level (mean channel, white-balanced; 0 = unknown), the brightness a
// clipped neighbour must at least reach. Strongly coloured surroundings (a white lamp on a red wall) say nothing about
// the lamp: their chromaticity fades to neutral.
// The three block levels are blended by their sample weights, without thresholds: the finest level with samples
// dominates, a coarser one takes over gradually where it has none (the inside of a large clipped area), a small neutral
// prior where no level has any. The former smoothsteps of the bilinearly interpolated weights (and the narrow neutral
// fade at 0.6..1 x the chroma limit) switched the prior along iso-lines of the block grid: axis-aligned rounded
// rectangles of different colour inside a clipped lamp (vivo X200 Ultra 2026-10-07; tools/check_highlight_recovery.py).
vec4 localLight(ivec2 o) {
    if (chromaU == 0) return vec4(1.0, 1.0, 1.0, 0.0);
    int b = max(blockU, 1);
    // integer base + fraction: float pixel coordinates above 2048 lose precision on Adreno
    vec2 bc = vec2(o / b) + (vec2(o - (o / b) * b) + 0.5) / float(b);
    vec4 near = texture(Chroma8, bc / vec2(textureSize(Chroma8, 0)));
    vec4 wide = texture(Chroma32, bc * 0.25 / vec2(textureSize(Chroma32, 0)));
    vec4 huge = texture(Chroma128, bc * 0.0625 / vec2(textureSize(Chroma128, 0)));
    const float PRIOR = 2.0e-5;                             // neutral prior, in sample-weight units (share of bright pixels)
    vec3 sumQ = vec3(PRIOR);
    float sumW = PRIOR;
    float level = 0.0;
    if (huge.a > 1.0e-7) {
        vec3 m = max(huge.rgb / huge.a, vec3(0.0));
        float w = 0.01 * huge.a;
        sumQ += w * m / max(dot(m, vec3(1.0 / 3.0)), 1.0e-6);
        sumW += w;
    }
    if (wide.a > 1.0e-6) {
        vec3 m = max(wide.rgb / wide.a, vec3(0.0));
        float y = dot(m, vec3(1.0 / 3.0)), w = 0.1 * wide.a;
        sumQ += w * m / max(y, 1.0e-6);
        sumW += w;
        level = y * smoothstep(0.002, 0.03, wide.a);
    }
    if (near.a > 1.0e-6) {
        vec3 m = max(near.rgb / near.a, vec3(0.0));
        float y = dot(m, vec3(1.0 / 3.0)), w = near.a;
        sumQ += w * m / max(y, 1.0e-6);
        sumW += w;
        level = max(level, y * smoothstep(0.02, 0.12, near.a));
    }
    vec3 q = sumQ / sumW;
    q /= max(dot(q, vec3(1.0 / 3.0)), 1.0e-4);
    // Only a strongly coloured prior fades (a lamp on a red wall), gradually from the limit to 2.5 x the limit: a warm
    // lamp's own glow (0.3 from neutral) stays its colour, so the recovered core joins its unclipped rim without a step.
    // Whether the prior fits the clipped pixel itself is tested there (main: consistency with its unclipped channels).
    float limit = chromaLimitU > 0.0 ? chromaLimitU : 0.35;
    float dev = max(max(abs(q.r - 1.0), abs(q.g - 1.0)), abs(q.b - 1.0));
    q = mix(q, vec3(1.0), smoothstep(limit, 2.5 * limit, dev));
    q.g = max(q.g, min(q.r, q.b));                          // never a magenta prior: that is what clipping fakes
    return vec4(q, level);
}

void main(){
    ivec2 p=clamp(ivec2(gl_FragCoord.xy)+cropOffset,ivec2(0),textureSize(InputBuffer,0)-1);
    vec4 sites=texture(GainMap,vec2(p)*inverseSize);
    vec3 gains=vec3(sites.r,(sites.g+sites.b)*.5,sites.a);
    gains/=max(dot(gains,vec3(1.0/3.0)),1e-6);
    vec3 raw=texelFetch(InputBuffer,p,0).rgb;
    if (signedU != 0) {
        // non-finite samples are zeroed as max(., 0) did on the GPUs it ran on (and scamdn/yuv does)
        raw = vec3(isnan(raw.r) || isinf(raw.r) ? 0.0 : raw.r, isnan(raw.g) || isinf(raw.g) ? 0.0 : raw.g,
                   isnan(raw.b) || isinf(raw.b) ? 0.0 : raw.b);
    } else {
        raw = max(raw, vec3(0));
    }
    vec3 c=raw*gains/max(whitePoint,vec3(1e-6));
    if (hlModeU == 1) {
        // Per-channel recovery in camera RGB: the clip test runs on the raw channels (one level per physical channel,
        // before WB and lens shading), the reconstruction on the white-balanced values.
        // Clip border (flag bit 3): each colour comes from the unclipped samples that are left, which sit on different
        // sides of a sharp clip edge for different Bayer sites -> a dotted red/blue fringe along lamp shades and paper
        // edges. Keep the luminance, take the colour of the local light (a 1-2 px band, like WhyCam's OKLab defringe).
        // Bit 6: the worker's clip-border pass already rebuilt these colours from real-site ratios
        // (research/hybrid5/fix_rim.md): no blanket defringe there. It matters: inside a Bento highlight bit 3 is set
        // almost everywhere (the base frame's clipped samples at weight 1 - m), and the blanket defringe turned a blue
        // monitor screen (c_12: 102,141,212) white (d_12: 220,222,233).
        // Second line, with or without the worker pass: border pixels whose colours have mixed origins - some from clipped
        // means (bits 0-2: the bright side of the edge), some from the unclipped samples left (the dark side) - take the
        // colour of the local light. Not where all three are clipped means: that is the inside of a clipped area (bit 3 is
        // set there too), whose clipped means all come from the same clipped cells - a real colour where a channel did not
        // clip itself (cellClip). Recolouring it to the local light replaced the measured colour of a whole lamp core by
        // the coarse block statistics: patches with crisp edges (vivo X200 Ultra lamp 2026-10-07).
        // A clipped-mean channel AT its clip is the recovery's (a red neon's R): the defringe fades out with the least
        // saturated flagged channel's saturation.
        vec3 L = levelAt(p);
        vec3 rel = raw / max(L, vec3(1.0e-6));
        vec3 sat = L.g > 0.0 ? smoothstep(vec3(0.90), vec3(0.985), rel) : vec3(0.0);
        float fringe = defringeU == 0.0 ? 0.85 : defringeU;
        uint f = clipFlagsU != 0 ? uint(texelFetch(ClipFlags, p, 0).r * 255.0 + 0.5) : 0u;
        vec3 fl = vec3(uvec3(f, f >> 1, f >> 2) & uvec3(1u));
        if (fringe > 0.0 && (f & 8u) != 0u) {
            float w = 0.0;
            if ((f & 7u) != 0u && (f & 7u) != 7u) {
                vec3 s = mix(vec3(1.0), sat, fl);           // unflagged channels do not hold the defringe back
                // A flagged channel far below its clip is a real dim colour, not a mix with saturated samples: with
                // cellClip the clipped mean of a colour whose cells clipped in ANOTHER colour holds its real values
                // (inside a red LED, neon or traffic light G and B are flagged at a few % of the clip). The fringe
                // pixels this line is for keep every flagged channel above ~0.3 of its clip (lamp0322 / hh2241 p0.1).
                // research/hybrid5/review_fix_rim.md
                vec3 rf = mix(vec3(1.0), rel, fl);
                w = (1.0 - min(min(s.r, s.g), s.b)) * smoothstep(0.12, 0.25, min(min(rf.r, rf.g), rf.b));
            } else if ((f & 7u) == 0u && (f & 64u) == 0u && !(clipHiUnflaggedU != 0 && (f & 16u) != 0u)) {
                // no worker pass: the blanket border defringe. Not inside the Bento mask when the ultrashort clipped below
                // the worker's flag threshold (P58): there bit 3 only marks the base frames' clipped samples at weight
                // 1 - m, the pixel is recovered like its unflagged neighbours (a recoloured cell was a 5 % brighter blotch)
                w = 1.0;
            }
            w *= clamp(fringe, 0.0, 1.0);
            if (w > 0.0) {
                vec3 q = localLight(ivec2(gl_FragCoord.xy)).rgb;
                c = mix(c, q * dot(c, vec3(1.0 / 3.0)), w);
            }
        }
        // P362 (owner, 2026-10-27: «верни для пересветов там, где коротких кадров нет: одиночные кадры, «Движение», сцены, где
        // Bento отказывается»): RawTherapee's «Inpaint opposed» on the pixels without ultrashort content (clip flag bits 4 and 5
        // clear, or no ultrashort level without flags) - a clipped channel (at 0.987 of its white-balanced clip, as RT) becomes
        // max(value, reference + chrominance): the reference the mean of the other two channels' 3x3 means in cube-root
        // space, the chrominance measured around the clips (hlrecovery/opposed). As RT, nothing else touches those pixels.
        if (oppU != 0 && L.g > 0.0) {
            uint fo = clipFlagsU != 0 ? uint(texelFetch(ClipFlags, p, 0).r * 255.0 + 0.5) : 0u;
            bool us = clipFlagsU != 0 ? (fo & 48u) != 0u : clipHiU.g > 0.0;
            if (!us) {
                vec3 Lc = clipLoU.g > 0.0 ? clipLoU : clipHiU;
                vec3 clipW = 0.987 * Lc * gains / max(whitePoint, vec3(1.0e-6));
                bvec3 clipped = greaterThanEqual(c, clipW);
                if (any(clipped)) {
                    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
                    vec3 m = vec3(0.0);
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dx = -1; dx <= 1; dx++) {
                            vec3 v = texelFetch(InputBuffer, clamp(p + ivec2(dx, dy), ivec2(0), last), 0).rgb;
                            v = vec3(isnan(v.r) || isinf(v.r) ? 0.0 : v.r, isnan(v.g) || isinf(v.g) ? 0.0 : v.g,
                                     isnan(v.b) || isinf(v.b) ? 0.0 : v.b);
                            m += max(v, vec3(0.0)) * gains / max(whitePoint, vec3(1.0e-6));
                        }
                    m = pow(max(m / 9.0, vec3(0.0)), vec3(1.0 / 3.0));
                    vec3 ref = pow(0.5 * vec3(m.g + m.b, m.r + m.b, m.r + m.g), vec3(3.0)) + oppChromaU;
                    vec3 r = mix(c, max(c, ref), vec3(clipped));
                    // never magenta (owner, 2026-10-19: «никогда не было фиолетовых засветов»): a clipped G is a lower bound and the
                    // channel a white light clips first; the opposed chrominance of a fully blown lamp (G below R and B around
                    // it) left its core lavender (lamp2324 without ultrashort frames: G 1.78 against R / B 1.86) - G comes up to
                    // the lower of R and B, as in the SCAM recovery
                    if (clipped.g) r.g = max(r.g, min(r.r, r.b));
                    Output = mix(c, r, clamp(hlStrengthU, 0.0, 1.0));
                    return;
                }
                Output = c;
                return;
            }
        }
        if (L.g <= 0.0) { Output = c; return; }
        // A clipped-mean channel close to its clip is a lower bound as well: it mixes real values of clipped cells with
        // saturated samples (the first rows inside a lamp: R 5.8, G 7.2, B 8 of k = 8 -> magenta). Its share of the
        // estimate fades in from 0.6 of the clip (a real colour far below, a red LED's G and B, is not touched).
        vec3 satR = max(sat, fl * smoothstep(vec3(0.60), vec3(0.985), rel));
        // P292 (owner, 2026-10-20: a purple line along a lamp shade's shadow on lit paper): at the ultrashort frame's clip edge
        // G reaches its ceiling first; where only some of its samples clipped it is a lower bound without a flag (0.96 of the
        // ceiling: R/G 2.3 after WB, magenta). A channel at 0.8 .. 0.95 of its ceiling while another white-balanced channel
        // is brighter cannot be the colour's own (the light is at least that bright in every channel): censored as well.
        {
            float hiC = max(max(c.r, c.g), c.b);
            vec3 below = vec3(lessThan(c, vec3(hiC * 0.98)));
            satR = max(satR, below * smoothstep(vec3(0.80), vec3(0.95), rel));
        }
        if (max(max(satR.r, satR.g), satR.b) <= 0.0) { Output = c; return; }
        // A clipped channel is a lower bound. Its estimate scales a prior chromaticity to the unclipped channels (G of a
        // white highlight from R and B), and only ever raises it: a saturated single colour (neon, laser) keeps its
        // clipped channel because the estimate from its dark channels is lower.
        vec4 light = localLight(ivec2(gl_FragCoord.xy));
        vec3 q = light.rgb;
        // Local ceiling: the brightest white-balanced clip level here, or the level of the bright surroundings when
        // they are brighter (a base-frame clip inside Bento content). Estimates stop at it and an all-clipped pixel
        // goes to it, so the clipped core is never darker than its rim or its surroundings.
        vec3 Lw = L * gains / max(whitePoint, vec3(1.0e-6));
        float top = max(max(max(Lw.r, Lw.g), Lw.b), light.a);
        vec3 u = vec3(1.0) - satR;
        float U = u.r + u.g + u.b;
        // The prior: the local light where it fits this pixel, neutral where it does not (misfit: log residuals of the
        // estimate, two-sided on the unclipped channels, one-sided on the clipped ones - an estimate below a lower bound).
        // * Two or more unclipped channels: the local light must agree with their own ratio. Next to the unclipped rim it
        //   does (a continuous join, no step at the clip border); deeper in a core whose own R:B is whiter than its glow
        //   it does not, and the core goes neutral gradually.
        // * One channel left: extrapolating the other two follows every tint of the prior (a cyan cast from the censored
        //   colours along a dark edge made a lamp core cyan, an orange glow left it pink next to a clipped blue): the
        //   neutral white core of WhyCam wherever neutral is consistent with the lower bounds; the local light only
        //   where neutral is not (R and G of a warm lamp clipped, B left: neutral would put G at B's level, a dark ring).
        //   The weight follows the unclipped channels continuously.
        float trust = 0.0;
        {
            vec3 lc = log(max(c, vec3(1.0e-4)));
            vec3 rq = lc - log(max(q * (dot(u, c) / max(dot(u, q), 1.0e-6)), vec3(1.0e-4)));
            vec3 rn = lc - log(max(vec3(dot(u, c) / max(U, 1.0e-6)), 1.0e-4));
            vec3 pq = max(rq, 0.0), pn = max(rn, 0.0);
            float norm = max(U + satR.r + satR.g + satR.b, 1.0e-4);
            float dq = sqrt((dot(u, rq * rq) + dot(satR, pq * pq)) / norm);
            float dn = sqrt((dot(u, rn * rn) + dot(satR, pn * pn)) / norm);
            float need = mix(smoothstep(0.03, 0.15, dn), 1.0, clamp(U - 1.0, 0.0, 1.0));
            trust = need * (1.0 - smoothstep(0.04, 0.20, dq));
        }
        vec3 qe = mix(vec3(1.0), q, trust);
        float den = dot(u, qe);
        vec3 est = den > 1.0e-4 ? min(qe * (dot(u, c) / den), vec3(top)) : c;
        vec3 r = mix(c, max(c, est), satR);
        // Blown with at most one channel measured (vivo X200 Ultra window 2026-10-08, native Quad mosaic, no Bento: G and B
        // clipped, R at 0.65..0.95 of its clip). The clipped channels are lower bounds, and after white balance they sit
        // ABOVE the estimate wherever the channel left is below the highest white-balanced clip (R 1.2..1.8 against B's
        // 1.6 and G's 1.0): max(c, est) kept B, so the order in which the channels clip turned into colour - a lavender
        // window, and cyan threads of a net in front of it where R alone dips (the R sites see a thread the G / B sites
        // miss). Once the channel left reaches the LOWEST white-balanced clip among the censored ones (rho ~ 1: the pixel
        // is at least that bright in every channel) its colour is no longer measured: neutral white, faded in over rho
        // 0.25..0.75 (continuous in the censoring weights). Not touched: a saturated light (a cyan or green sign, the
        // channel left far below the clipped ones, rho < 0.25), and pixels with two channels left (their ratio measures
        // the colour, the prior estimate above stays). tools/check_highlight_neutral.py (scene A).
        {
            // censoring as above, but a clipped-mean channel counts as a bound only from 0.8 of its clip (not 0.6): with
            // cellClip a partially clipped sky's B at 0.8 of the clip is its measured value, and its colour (B/R) stays
            vec3 satB = max(sat, fl * smoothstep(vec3(0.80), vec3(0.985), rel));
            vec3 ub = vec3(1.0) - satB;
            float Ub = ub.r + ub.g + ub.b;
            vec3 mc = Lw / max(satB, vec3(1.0e-3));            // white-balanced clip of every censored channel, huge if not
            float mLow = min(min(mc.r, mc.g), mc.b);
            float rho = Ub > 1.0e-3 ? dot(ub, c) / (Ub * max(mLow, 1.0e-6)) : 1.0e3;
            float blown = smoothstep(0.25, 0.75, rho) * clamp(2.0 - Ub, 0.0, 1.0);
            // the level: the mean of the measured channels' level with every bound kept (G of the window raised to R),
            // not of the prior estimate (its 8 / 32 / 128-px blocks would show as level steps) and not the highest bound
            // (a flat band at B's clip, a step at the lamp's cool top edge, the net's threads gone)
            vec3 rN = Ub > 1.0e-3 ? max(c, vec3(dot(ub, c) / Ub)) : vec3(max(max(c.r, c.g), c.b));
            r = mix(r, vec3(dot(rN, vec3(1.0 / 3.0))), blown);
        }
        // Never magenta where G is censored (owner, 2026-10-19: «нам нужно чтобы у нас никогда не было фиолетовых засветов»;
        // OPPO 12 MP window, G clipped even in the ultrashort frame, R and B measured: a sky-blue local light scaled to R and
        // B put G below both - a lavender cloth). A clipped G is a lower bound and green is the channel a white light clips
        // first; a magenta result there is the censoring, not the scene: G comes up to the lower of R and B, weighted by its
        // censoring (an unclipped G keeps a real magenta).
        // P337 (owner, 2026-10-25: «фиолетовый артефакт на солнце»): ... nor pink. A backlit sea: sun glitter and the sun's rim with
        // G clipped, R measured at 1.1-1.4 x G after WB and B lower - G comes up to R where R is itself measured (a clipped G under
        // an unclipped R cannot be the darker channel of a light that bright). A blue sky with G clipped (R below G) keeps its
        // colour; where R is clipped too (an orange lamp) G stays at the lower of R and B as before.
        r.g = mix(r.g, max(r.g, mix(min(r.r, r.b), r.r, 1.0 - satR.r)), satR.g);
        // Every channel clipped: no colour left; neutral white at the brightest level, reached gradually as the
        // last channel approaches its clip (no grey plateau, no step).
        float all3 = sat.r * sat.g * sat.b;
        vec3 o = mix(r, vec3(max(max(max(r.r, r.g), r.b), top)), all3);
        Output = mix(c, o, clamp(hlStrengthU, 0.0, 1.0));
        return;
    }
    // Blown highlights: once any channel reaches its clip level the colour is no
    // longer measured; fade it to neutral at its brightest channel (white, not
    // the pink a clipped green leaves after white balance).
    float over=max(max(raw.r/clipLevel.r,raw.g/clipLevel.g),raw.b/clipLevel.b);
    float t=smoothstep(0.85,0.98,over);
    Output=mix(c,vec3(max(c.r,max(c.g,c.b))),t);
}
