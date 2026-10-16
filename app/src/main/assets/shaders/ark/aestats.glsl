precision highp float;
precision highp int;
precision highp sampler2D;
// P145: the per-pixel part of the ARK Smart-HDR auto exposure (ArkAe.stats) on the GPU instead of a read-back of arkLow
// and two single-threaded CPU passes. Compute, an 8x8 group per 64x64 tile of arkLow (each invocation 8x8 pixels).
// PASS 1: the largest finite sample of r, g, b as an order-preserving uint key (ArkAe.maxKey) -> aeMax[0] (atomicMax).
// PASS 2: with invClipU = 1 / clip from that maximum - the luma and max-channel 4096-bin histograms, weighted by the
// metering (whole numbers: one shared uint per bin holds the luma count in the low and the max count in the high 16 bits,
// a tile adds at most 4096 x 10), then added to aeHistY / aeHistM; the weighted sums of luma and log luma per group ->
// aeSums[2 * group] (the CPU adds them in double). Same arithmetic as ArkAe.stats: Rec.709 luma, NaN -> 0, the bin
// int(clamp(v * invClip, 0, 1) * 4095.99). P159: the max channel above the ceiling in 48 log2 bins (12 an octave,
// ArkAe.tailBin) -> aeTail.
#define PASS 1
#define LAYOUT //
LAYOUT

uniform sampler2D arkLow;
uniform ivec2 sizeU;        // arkLow width, height
uniform float invClipU;     // PASS 2
uniform int modeU;          // metering: 0 average, 1 centre weighted, 2 spot

const int TILE = 64;
const int GROUP = 8;        // the layout: GROUP x GROUP invocations

#if PASS == 1
layout(std430, binding = 1) buffer aeMax { uint maxKey[]; };
shared uint localMax;

uint orderKey(float f) {
    uint b = floatBitsToUint(f);
    return (b & 0x80000000u) != 0u ? ~b : (b | 0x80000000u);
}

void main() {
    if (gl_LocalInvocationIndex == 0u) localMax = 0u;
    barrier();
    ivec2 origin = ivec2(gl_WorkGroupID.xy) * TILE + ivec2(gl_LocalInvocationID.xy);
    uint best = 0u;
    for (int j = 0; j < TILE / GROUP; j++) {
        for (int i = 0; i < TILE / GROUP; i++) {
            ivec2 p = origin + ivec2(i * GROUP, j * GROUP);
            if (p.x >= sizeU.x || p.y >= sizeU.y) continue;
            vec3 v = texelFetch(arkLow, p, 0).rgb;
            for (int c = 0; c < 3; c++) {
                float f = v[c];
                if (!isnan(f) && !isinf(f)) best = max(best, orderKey(f));
            }
        }
    }
    if (best != 0u) atomicMax(localMax, best);
    barrier();
    if (gl_LocalInvocationIndex == 0u && localMax != 0u) atomicMax(maxKey[0], localMax);
}
#else
layout(std430, binding = 2) buffer aeHistY { uint histY[]; };
layout(std430, binding = 3) buffer aeHistM { uint histM[]; };
layout(std430, binding = 4) buffer aeSums { float sums[]; };
layout(std430, binding = 5) buffer aeTail { uint tail[]; };
shared uint bins[4096];
shared uint tailBins[48];

void main() {
    uint li = gl_LocalInvocationIndex;
    for (uint b = li; b < 4096u; b += uint(GROUP * GROUP)) bins[b] = 0u;
    if (li < 48u) tailBins[li] = 0u;
    barrier();
    ivec2 origin = ivec2(gl_WorkGroupID.xy) * TILE + ivec2(gl_LocalInvocationID.xy);
    float halfW = float(sizeU.x) * 0.5, halfH = float(sizeU.y) * 0.5;
    float sumY = 0.0, sumLog = 0.0;
    for (int j = 0; j < TILE / GROUP; j++) {
        for (int i = 0; i < TILE / GROUP; i++) {
            ivec2 p = origin + ivec2(i * GROUP, j * GROUP);
            if (p.x >= sizeU.x || p.y >= sizeU.y) continue;
            vec3 v = texelFetch(arkLow, p, 0).rgb;
            float rr = v.r, gg = v.g, bb = v.b;
            float yv = gg * 0.7152 + rr * 0.2126 + bb * 0.0722;
            // Java's Math.max gives NaN for any NaN argument; the NaN is then counted as 0
            bool nanM = isnan(rr) || isnan(gg) || isnan(bb) || isnan(yv);
            float mv = nanM ? 0.0 : max(max(max(gg, bb), rr), yv);
            if (isnan(yv)) yv = 0.0;
            uint w = 1u;
            if (modeU >= 1) {
                float dx = (float(p.x) - halfW) / halfW, dy = (float(p.y) - halfH) / halfH, d2 = dx * dx + dy * dy;
                if (modeU == 2) w = d2 < 0.04 ? 10u : d2 < 0.16 ? 2u : 0u;
                else if (modeU == 1) w = d2 < 0.25 ? 4u : d2 < 0.5625 ? 2u : 1u;
            }
            int iy = int(clamp(yv * invClipU, 0.0, 1.0) * 4095.99);
            int im = int(clamp(mv * invClipU, 0.0, 1.0) * 4095.99);
            if (w != 0u) {
                atomicAdd(bins[iy], w);
                atomicAdd(bins[im], w << 16);
                if (mv * invClipU > 1.0) atomicAdd(tailBins[min(47, max(0, int(log2(mv * invClipU) * 12.0)))], w);
                sumY += yv * float(w);
                sumLog += log(max(yv, 1e-4)) * float(w);
            }
        }
    }
    barrier();
    for (uint b = li; b < 4096u; b += uint(GROUP * GROUP)) {
        uint n = bins[b];
        if ((n & 0xffffu) != 0u) atomicAdd(histY[b], n & 0xffffu);
        if ((n >> 16) != 0u) atomicAdd(histM[b], n >> 16);
    }
    if (li < 48u && tailBins[li] != 0u) atomicAdd(tail[li], tailBins[li]);
    barrier();
    // the group's sums: a tree over the 64 invocations in the (now free) shared bins
    bins[2u * li] = floatBitsToUint(sumY);
    bins[2u * li + 1u] = floatBitsToUint(sumLog);
    barrier();
    for (uint s = uint(GROUP * GROUP) / 2u; s > 0u; s >>= 1) {
        if (li < s) {
            bins[2u * li] = floatBitsToUint(uintBitsToFloat(bins[2u * li]) + uintBitsToFloat(bins[2u * (li + s)]));
            bins[2u * li + 1u] = floatBitsToUint(uintBitsToFloat(bins[2u * li + 1u]) + uintBitsToFloat(bins[2u * (li + s) + 1u]));
        }
        barrier();
    }
    if (li == 0u) {
        uint group = gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x;
        sums[2u * group] = uintBitsToFloat(bins[0]);
        sums[2u * group + 1u] = uintBitsToFloat(bins[1]);
    }
}
#endif
