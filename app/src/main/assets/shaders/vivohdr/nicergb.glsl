precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform sampler2D GainMap;
uniform vec3 whitePoint;
uniform vec3 clipLevel;
uniform vec2 inverseSize;
uniform ivec2 cropOffset;
// Per-channel highlight recovery (LMC hybrid). Unset uniforms read 0 = the legacy neutralisation at clipLevel below.
uniform int hlModeU;              // 1: per-channel recovery (clipLoU/clipHiU), 0: legacy
uniform vec3 clipLoU;             // clip of the base frames per camera channel, raw units before WB/LSC (<= 0: none)
uniform vec3 clipHiU;             // clip of the Bento ultrashort (about k * white) per channel (<= 0: none)
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
// 1 (LMC hybrid): the worker's signed values pass on. The merge keeps its noise signed and clips once; a per-pixel
// max(0) here lifted the mean of every channel near zero by up to half its noise - x1.5 red along the frame edge of a dark
// teal curtain, where fewer donors overlap (OPPO X8U 2026-10-07), and red mottling of dark saturated colours once the ARK
// tone lifted them. The clamp now follows the noise reduction (LmcDenoise / NiceDenoise output) or an average (ark/low,
// ark/combine). 0 (unset): clamp at zero (SCAM HDR route, whose stages expect it).
uniform int signedU;
out vec3 Output;

// Clip level of every channel at this pixel (raw units); vec3(-1) = no channel can be clipped here.
// Worker flags (vivo-nice-hybrid.h, clipFlags trailer): bits 0-2 = R/G/B from the clipped mean, 3 = a clipped sample
// was excluded nearby (clip border), 4 = inside the Bento mask, 5 = the clipped mean holds the ultrashort frame,
// 6 = border colour already rebuilt by the worker (main: defringe). Keep in sync with hlrecovery/chanprep.glsl.
vec3 levelAt(ivec2 p) {
    bool lo = clipLoU.g > 0.0, hi = clipHiU.g > 0.0;
    if (clipFlagsU == 0) return hi ? clipHiU : (lo ? clipLoU : vec3(-1.0));
    uint f = uint(texelFetch(ClipFlags, p, 0).r * 255.0 + 0.5);
    if ((f & 15u) == 0u) return vec3(-1.0);                 // every colour from unclipped samples
    if ((f & 32u) != 0u) return hi ? clipHiU : clipLoU;     // clipped mean of the ultrashort: k * white
    if ((f & 7u) != 0u) return lo ? clipLoU : clipHiU;      // clipped mean of the base frames: white
    // Clip border, every colour from unclipped samples. Inside the Bento mask the value is ultrashort content (up to k):
    // only the ultrashort's own clip can censor it, and none when it never clipped. The excluded clipped samples there
    // are the base frame's, kept at a weight 1 - m (float rounding of the mask, or a mask lowered by the LMC intensity
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
        // non-finite samples are zeroed as max(., 0) did on the GPUs it ran on (and lmcdn/yuv does)
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
        // edges. Keep the luminance, take the colour of the local light (a 1-2 px band, like ArkCam's OKLab defringe).
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
            } else if ((f & 7u) == 0u && (f & 64u) == 0u) {
                w = 1.0;                                    // no worker pass: the blanket border defringe
            }
            w *= clamp(fringe, 0.0, 1.0);
            if (w > 0.0) {
                vec3 q = localLight(ivec2(gl_FragCoord.xy)).rgb;
                c = mix(c, q * dot(c, vec3(1.0 / 3.0)), w);
            }
        }
        if (L.g <= 0.0) { Output = c; return; }
        // A clipped-mean channel close to its clip is a lower bound as well: it mixes real values of clipped cells with
        // saturated samples (the first rows inside a lamp: R 5.8, G 7.2, B 8 of k = 8 -> magenta). Its share of the
        // estimate fades in from 0.6 of the clip (a real colour far below, a red LED's G and B, is not touched).
        vec3 satR = max(sat, fl * smoothstep(vec3(0.60), vec3(0.985), rel));
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
        //   neutral white core of GCam / ArkCam wherever neutral is consistent with the lower bounds; the local light only
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
