precision highp float;
precision highp int;
precision highp sampler2D;
// Highlight recovery "Inpaint opposed" (RawTherapee rtengine/hilite_recon.cc, RawImageSource::highlight_recovery_opposed,
// GPL-3; after darktable's opposed reconstruction by Hanno Schwalm with Iain and garagecoder): the global chrominance
// correction, on the GPU. A clipped channel is later rebuilt as max(value, reference + chrominance) (scamhdr/scamrgb), the
// reference being the mean of the OTHER two channels' 3x3 means taken in cube-root space. The chrominance per channel is
// the mean of (value - reference) over the pixels near a clip of that channel (the clip masks dilated by the RT pattern:
// |dx|, |dy| <= 3 without the four corners) whose value lies between clipdark (0.03 / 0.125 / 0.03 of the clip) and the
// clip. RT itself compares there with its tmp buffer, which holds the pixel's own value for an unclipped channel: its sum
// is always 0 - the reference is taken at every counted pixel here, as in darktable.
// Values are white-balanced camera RGB (worker RGB x lens shading / white point), the clip 0.987 of the white-balanced
// clip level of the channel (RT: clipval 0.987). Only pixels without ultrashort content take part (SCAM P362: the
// recovery for single frames, «Движение» and scenes where Bento refused; inside the Bento mask the ultrashort frames hold
// the real values): clip flag bits 4 and 5 clear, or no ultrashort level at all without flags.
// Compute, 16 x 16 invocations a group, one pixel each, the tile with a 3-pixel halo in shared memory. Each group writes
// (sum r, g, b, count r, g, b) as float bits to oppSums[6 * group]; the CPU adds them (ScamRgb.opposedChroma).
#define LAYOUT //
LAYOUT

uniform sampler2D InputBuffer;   // worker RGB: camera channels, base-frame white = 1, before WB and lens shading
uniform sampler2D GainMap;
uniform vec3 whitePoint;
uniform vec2 inverseSize;
uniform ivec2 cropOffset;
uniform ivec2 sizeU;             // output size
uniform vec3 clipLoU;            // as in scamhdr/scamrgb
uniform vec3 clipHiU;
uniform int clipHiUnflaggedU;
uniform int clipFlagsU;
uniform sampler2D ClipFlags;

layout(std430, binding = 1) buffer oppSums { uint sums[]; };

const int G = 16, HALO = 3, T = G + 2 * HALO, TT = T * T;
// ~9.7 kB: within the 16 kB every GL ES 3.1 device has. After the pixels' part both arrays hold the group's reduction:
// sC the sums, sBits the three counts packed 10 bits each (at most 256 a channel).
shared vec3 sC[TT];
shared uint sBits[TT];
shared uint sAny; // a channel clipped anywhere in the tile (most tiles have none: no dilation, no reference)

// Same selection as scamhdr/scamrgb levelAt (keep in sync).
vec3 levelAt(ivec2 p, uint f) {
    bool lo = clipLoU.g > 0.0, hi = clipHiU.g > 0.0;
    if (clipFlagsU == 0) return hi ? clipHiU : (lo ? clipLoU : vec3(-1.0));
    if ((f & 15u) == 0u) return clipHiUnflaggedU != 0 && hi && (f & 16u) != 0u ? clipHiU : vec3(-1.0);
    if ((f & 32u) != 0u) return hi ? clipHiU : clipLoU;
    if ((f & 7u) != 0u) return lo ? clipLoU : clipHiU;
    if ((f & 16u) != 0u) return hi ? clipHiU : vec3(-1.0);
    return lo ? clipLoU : clipHiU;
}

// The level a channel clips at for the opposed reconstruction: the base frames' white (the ultrashort's where only it is
// known), independent of the flags of this pixel (an unclipped pixel next to a clip has none).
vec3 clipOf() {
    return clipLoU.g > 0.0 ? clipLoU : clipHiU;
}

// White balance x lens shading of input pixel p (as scamhdr/scamrgb).
vec3 wbAt(ivec2 p) {
    vec4 sites = texture(GainMap, vec2(p) * inverseSize);
    vec3 gains = vec3(sites.r, (sites.g + sites.b) * 0.5, sites.a);
    gains /= max(dot(gains, vec3(1.0 / 3.0)), 1.0e-6);
    return gains / max(whitePoint, vec3(1.0e-6));
}

ivec2 inputAt(ivec2 o) {
    return clamp(o + cropOffset, ivec2(0), textureSize(InputBuffer, 0) - ivec2(1));
}

void load(int k, ivec2 origin) {
    ivec2 t = ivec2(k % T, k / T);
    ivec2 o = clamp(origin + t - ivec2(HALO), ivec2(0), sizeU - ivec2(1));
    ivec2 p = inputAt(o);
    vec3 raw = texelFetch(InputBuffer, p, 0).rgb;
    raw = vec3(isnan(raw.r) || isinf(raw.r) ? 0.0 : raw.r, isnan(raw.g) || isinf(raw.g) ? 0.0 : raw.g,
               isnan(raw.b) || isinf(raw.b) ? 0.0 : raw.b);
    vec3 wb = wbAt(p);
    vec3 c = max(raw, vec3(0.0)) * wb;
    uint f = clipFlagsU != 0 ? uint(texelFetch(ClipFlags, p, 0).r * 255.0 + 0.5) : 0u;
    bool us = clipFlagsU != 0 ? (f & 48u) != 0u : clipHiU.g > 0.0;
    vec3 L = clipOf();
    vec3 clip = L.g > 0.0 ? 0.987 * L * wb : vec3(1.0e30);
    uint bits = 0u;
    if (!us) {
        // a channel the merge did not clip (no level here) is not a clip either
        vec3 lv = levelAt(p, f);
        if (lv.g > 0.0) {
            if (c.r >= clip.r) bits |= 1u;
            if (c.g >= clip.g) bits |= 2u;
            if (c.b >= clip.b) bits |= 4u;
        }
        bits |= 8u; // takes part
    }
    sC[k] = c;
    sBits[k] = bits;
    if ((bits & 7u) != 0u) atomicOr(sAny, 1u);
}

void main() {
    ivec2 origin = ivec2(gl_WorkGroupID.xy) * G;
    int li = int(gl_LocalInvocationIndex);
    if (li == 0) sAny = 0u;
    memoryBarrierShared(); barrier();
    for (int k = li; k < TT; k += G * G) load(k, origin);
    memoryBarrierShared(); barrier();
    ivec2 l = ivec2(gl_LocalInvocationID.xy);
    ivec2 o = origin + l;
    int ci = (l.y + HALO) * T + l.x + HALO;
    vec3 sum = vec3(0.0), cnt = vec3(0.0);
    uint own = sBits[ci];
    if (sAny != 0u && o.x < sizeU.x && o.y < sizeU.y && (own & 8u) != 0u) {
        // the clip masks dilated (RT test_dilate: |dx|, |dy| <= 3 without the corners)
        uint dil = 0u;
        for (int dy = -HALO; dy <= HALO; dy++)
            for (int dx = -HALO; dx <= HALO; dx++) {
                if (abs(dx) == HALO && abs(dy) == HALO) continue;
                dil |= sBits[ci + dy * T + dx];
            }
        dil &= 7u;
        if (dil != 0u) {
            vec3 L = clipOf();
            vec3 c = sC[ci], clip = 0.987 * L * wbAt(inputAt(o));
            vec3 dark = clip * vec3(0.03, 0.125, 0.03);
            // the opposed reference: 3x3 means, cube root, mean of the other two, cubed
            vec3 m = vec3(0.0);
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) m += sC[ci + dy * T + dx];
            m = pow(max(m / 9.0, vec3(0.0)), vec3(1.0 / 3.0));
            vec3 ref = pow(0.5 * vec3(m.g + m.b, m.r + m.b, m.r + m.g), vec3(3.0));
            for (int ch = 0; ch < 3; ch++) {
                if ((dil & (1u << uint(ch))) != 0u && c[ch] > dark[ch] && c[ch] < clip[ch]) {
                    sum[ch] += c[ch] - ref[ch];
                    cnt[ch] += 1.0;
                }
            }
        }
    }
    memoryBarrierShared(); barrier(); // every read of the tile is done: it becomes the reduction's storage
    sC[li] = sum;
    sBits[li] = uint(cnt.r) | (uint(cnt.g) << 10) | (uint(cnt.b) << 20);
    memoryBarrierShared(); barrier();
    for (int s = G * G / 2; s > 0; s >>= 1) {
        if (li < s) {
            sC[li] += sC[li + s];
            sBits[li] += sBits[li + s]; // no field reaches 1024
        }
        memoryBarrierShared(); barrier();
    }
    if (li == 0) {
        uint g = (gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x) * 6u;
        uint n = sBits[0];
        sums[g] = floatBitsToUint(sC[0].r);
        sums[g + 1u] = floatBitsToUint(sC[0].g);
        sums[g + 2u] = floatBitsToUint(sC[0].b);
        sums[g + 3u] = n & 1023u;
        sums[g + 4u] = (n >> 10) & 1023u;
        sums[g + 5u] = n >> 20;
    }
}
