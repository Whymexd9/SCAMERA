#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
precision highp image2D;
uniform highp sampler2D prevAlignment;
uniform highp sampler2D baseTexture;
uniform highp sampler2D alterTexture;
uniform highp sampler2D baseCurve;
uniform highp sampler2D alterCurve;
layout(rgba16f, binding = 0) uniform highp writeonly image2D outTexture;

uniform float noiseS;
uniform float noiseO;
uniform int first;
uniform ivec2 rawHalf;
uniform float exposure;
uniform float integralNorm;
uniform float significancy;

#define TILE_AL 16
#define TILE (TILE_AL/2)
#define M_PI 3.1415926535897932384626433832795
#define OFFSETS 9
// Cost: plain L1 normalized by the local noise sigma (shot+read, scaled to
// the current pyramid level by integralNorm). NO truncation: a hot pixel is
// invisible after the normalize prefilter and the gaussian pyramid (it is
// ~1/25 of one channel of one tap), so clamping differences at k*sigma does
// not reject outliers - it only flattens the strong edges and fine texture
// the matcher runs on, which measurably destroys alignment on detailed real
// scenes (tools/alignment-bench). The noise normalization itself still
// matters: it downweights dark noisy pixels and puts the significance gate in
// statistical units.
#import median

shared mat4 inputDifferences[TILE*TILE]; // use this to store the 3x3 search grid images differences

vec4 getPixel(ivec2 coords, highp sampler2D tex) {
    return texelFetch(tex, coords, 0);
}

highp vec4 getAlignment(ivec2 coords) {
    // Clamp to the prev-alignment tile grid, i.e. the dispatch grid of the
    // level above: floor(levelAboveWidth/8) == floor(2*thisTexWidth/8).
    // The old textureSize(baseTexture)/TILE_AL-1 bound collapsed to 0 (or
    // went undefined, min>max) at coarse levels narrower than ~32 texels,
    // scrambling the coarse-offset propagation exactly where large warps
    // need it most.
    coords = clamp(coords, ivec2(0), ivec2(textureSize(prevAlignment, 0)*2/TILE));
    return texelFetch(prevAlignment, coords, 0);
}

highp vec4 alignmentToVec4(highp vec2 alignment) {
    highp vec4 converted = vec4(floor(alignment.x), floor(alignment.y), fract(alignment.x), fract(alignment.y));
    converted.xy /= vec2(rawHalf);
    return converted;
}

highp vec2 vec4ToAlignment(highp vec4 alignment) {
    // Round the integer part: it is stored as floor(v)/rawHalf in an rgba16f
    // texture, and half-float precision reconstructs e.g. 2/480*480 as 1.9998.
    // Truncating that silently biases offsets by -1px. The fract part
    // (subpixel residual) is preserved for callers, which must floor().
    return floor(alignment.xy*vec2(rawHalf) + vec2(0.5)) + alignment.zw;
}

float brightness(vec4 color) {
    return dot(color, vec4(0.25));
}

// Per-pixel noise sigma at this pyramid level for the given base brightness.
float levelNoise(float baseBrightness) {
    // sigma per frame; the base-alter difference has sqrt(2) larger sigma,
    // which is folded into the significancy threshold instead of here.
    return max(sqrt(max(baseBrightness, 0.0) * noiseS + noiseO) / integralNorm, 1e-5);
}
float alignCost(vec4 baseValue, vec4 alterValue, float sigma) {
    // Plain noise-normalized L1, unclamped - see the cost comment above.
    return dot(abs(baseValue - alterValue) / vec4(sigma), vec4(0.25));
}

mat4 getSharedDifferences(ivec2 xy, ivec2 prevOffset) {
    mat4 differences;
    vec4 baseValue = clamp(getPixel(xy, baseTexture), 0.000, 1.0);
    float baseBrightness = brightness(baseValue);
    float sigma = levelNoise(baseBrightness);
    // Base pixel unusable (clipped above the alter frame's exposure or below
    // the black floor): contribute a neutral 0 cost to every candidate so the
    // tile keeps the previous alignment instead of locking onto garbage.
    float baseWeight = (baseBrightness > brightness(clamp(baseValue, 0.0, exposure)) || baseBrightness < 0.001) ? 0.0 : 1.0;
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            vec4 alterValue = clamp(getPixel(xy + ivec2(i-1, j-1) + prevOffset, alterTexture), 0.0, exposure);
            differences[i][j] = alignCost(baseValue, alterValue, sigma) * baseWeight;
        }
    }
    return differences;
}

mat4 getOffsetDifferences(ivec2 xy) {
    mat4 differences;
    vec4 baseValue = clamp(getPixel(xy, baseTexture), 0.000, 1.0);
    float baseBrightness = brightness(baseValue);
    float sigma = levelNoise(baseBrightness);
    float baseWeight = (baseBrightness > brightness(clamp(baseValue, 0.0, exposure)) || baseBrightness < 0.001) ? 0.0 : 1.0;
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            vec2 prevOffset = vec4ToAlignment(getAlignment(xy/(2*TILE) + ivec2(i-1, j-1)))*2.0;
            if(i == 3 && j == 3) {
                prevOffset = vec2(0.0);
            }
            // floor, not ivec2() truncation: prevOffset may carry a subpixel
            // fract and trunc rounds the wrong way for negative offsets
            vec4 alterValue = clamp(getPixel(xy + ivec2(floor(prevOffset)), alterTexture), 0.0, exposure);
            differences[i][j] = alignCost(baseValue, alterValue, sigma) * baseWeight;
        }
    }
    return differences;
}

highp vec2 getPrevOffset(ivec2 tile_xy) {
    ivec2 localOffsets[OFFSETS];
    localOffsets[0] = ivec2(0, 0);
    localOffsets[1] = ivec2(1, 0);
    localOffsets[2] = ivec2(-1, 0);
    localOffsets[3] = ivec2(0, 1);
    localOffsets[4] = ivec2(0, -1);
#if OFFSETS > 5
    localOffsets[5] = ivec2(-1, -1);
    localOffsets[6] = ivec2(-1, 1);
    localOffsets[7] = ivec2(1, -1);
    localOffsets[8] = ivec2(1, 1);
#endif
    vec2 prevOffset = vec2(0.0);
    // Local thread ID within work group
    ivec2 localID = ivec2(gl_LocalInvocationID.xy) - ivec2(TILE/2, TILE/2); // 0 - TILE-1
    int localIndex = int(gl_LocalInvocationIndex); // 0 - TILE*TILE-1
    // Get previous alignment if not first level
    // split to 4 calls to increase scan window size
    // Decrease inputDifferences size to TILE*TILE
    mat4 temp = mat4(0.0);
    for (int i = 0; i < OFFSETS; i++) {
        temp += getOffsetDifferences((tile_xy+localOffsets[i]) * TILE + localID);
    }
    inputDifferences[localIndex] = temp;
    barrier();
    mat4 sum = mat4(0.0);
    // Parallel reduction for summing
    for (int stride = TILE * TILE / 2; stride > 0; stride >>= 1) {
        if (localIndex < stride) {
            inputDifferences[localIndex] += inputDifferences[localIndex + stride];
        }
        barrier();
    }

    sum = inputDifferences[0];
    // Use mat4 sum to find the best offset from (-1,-1) to (1,1)
    vec2 bestOffset = vec2(0.0);
    float minDiff = sum[0][0];

    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            if (sum[i][j] < minDiff) {
                minDiff = sum[i][j];
                if(i == 3 && j == 3) {
                    bestOffset = vec2(0.0);
                } else {
                    bestOffset = vec2(i - 1, j - 1);
                }
            }
        }
    }
    prevOffset = vec4ToAlignment(getAlignment(tile_xy / 2 + ivec2(bestOffset))) * 2.0;
    return prevOffset;
}

// Compute alignment between base and alter textures.
// Returns xy = integer offset, zw = subpixel residual in [-0.5, 0.5].
// The residual is returned separately rather than folded into xy so that a
// second refinement pass can be fed a clean integer offset: the cost grid is
// only ever evaluated at integer positions, so feeding a fractional offset
// back in would shift the search window without changing what is sampled and
// the residual would be counted twice.
highp vec4 computeAlignment(ivec2 tile_xy, vec2 prevOffset) {
    // Fill inputDifferences array with 4 calls to getSharedDifferences
    ivec2 localOffsets[OFFSETS];
    localOffsets[0] = ivec2(0, 0);
    localOffsets[1] = ivec2(1, 0);
    localOffsets[2] = ivec2(-1, 0);
    localOffsets[3] = ivec2(0, 1);
    localOffsets[4] = ivec2(0, -1);
#if OFFSETS > 5
    localOffsets[5] = ivec2(-1, -1);
    localOffsets[6] = ivec2(-1, 1);
    localOffsets[7] = ivec2(1, -1);
    localOffsets[8] = ivec2(1, 1);
#endif
    // Local thread ID within work group
    ivec2 localID = ivec2(gl_LocalInvocationID.xy) - ivec2(TILE/2, TILE/2); // 0 - TILE-1
    int localIndex = int(gl_LocalInvocationIndex); // 0 - TILE*TILE-1
    // The cost grid below is sampled at integer positions only (getSharedDifferences
    // floors it), so the search has to be anchored to an integer offset. A coarse
    // level's doubled fractional residual arriving here would ride along in
    // bestOffset while the costs that chose it were evaluated up to a pixel away,
    // and the mismatch would be stored as if it had been measured. Drop it: the
    // parabola at the end of this level re-derives the residual against this
    // level's own costs.
    prevOffset = floor(prevOffset);
    // split to 4 calls to increase scan window size and sum calls
    mat4 temp = mat4(0.0);
    for (int i = 0; i < OFFSETS; i++) {
        int targetIndex = localIndex + i * TILE*TILE;
        temp += getSharedDifferences((tile_xy+localOffsets[i]) * TILE + localID, ivec2(floor(prevOffset)));
    }
    inputDifferences[localIndex] = temp;
    // Ensure all threads have written to shared memory
    barrier();
    // Sum the differences to get final mat4 sum
    mat4 sum = mat4(0.0);
    // Parallel reduction for summing
    for (int stride = TILE * TILE / 2; stride > 0; stride >>= 1) {
        if (localIndex < stride) {
            inputDifferences[localIndex] += inputDifferences[localIndex + stride];
        }
        barrier();
    }
    // First thread has the final sum
    sum = inputDifferences[0];
    // Use mat4 sum to find the best offset from (-1,-1) to (1,1)
    highp vec2 bestOffset = prevOffset;
    float minDiff = sum[0][0];
    ivec2 bestIdx = ivec2(0, 0);

    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            if (sum[i][j] < minDiff) {
                minDiff = sum[i][j];
                bestOffset = prevOffset + vec2(i-1, j-1);
                bestIdx = ivec2(i, j);
            }
        }
    }
    // Significance gate: compare the cost improvement of the best candidate
    // over the previous alignment against the statistical noise of the summed
    // cost (CLT: std of the sum ~ sqrt(expected cost * N)). With the
    // noise-normalized L1 cost a correctly aligned tile averages ~1.13 (|N(0,
    // sqrt(2))| per pixel). If the improvement is below 'significancy'
    // standard deviations, the minimum is noise and we keep the previous
    // alignment. This stops textureless tiles from random-walking into
    // blocky misalignment while leaving genuine detail matches untouched
    // (k = 1.5, measured on real ProRAW bursts in tools/alignment-bench).
    // 'sum' comes from shared memory and is identical on every thread, so
    // the gate keeps the returned offset uniform.
    bool frozen = false;
    {
        float n = float(OFFSETS * TILE * TILE);
        float expected = 1.13; // mean per-pixel cost when aligned
        // The alter frame is brought to the base exposure by 1/exposure, which
        // scales its photon noise by the same factor. noiseS/noiseO describe the
        // base frame only, so on a bracket the cost is noisier than the model
        // predicts and a threshold built from the base noise alone lets a random
        // minimum through - the source of the blocky tiles on bracketed shots.
        // Widen the gate by the exposure ratio so a darker frame has to clear a
        // proportionally larger improvement.
        float exposureNoiseScale = max(1.0, 1.0 / max(exposure, 1e-4));
        float thresh = significancy * sqrt(expected / n) * exposureNoiseScale;
        float costPrev = sum[1][1];
        float improvement = (costPrev - minDiff) / n;
        if (improvement < thresh) {
            bestOffset = prevOffset;
            minDiff = costPrev;
            bestIdx = ivec2(1, 1);
            frozen = true;
        }
    }

    // Subpixel refinement. The integer minimum found above is the argmin of a
    // sampled cost surface; the true minimum almost never sits exactly on a
    // texel. Neighbouring tiles rounding the same underlying fractional shift
    // in opposite directions differ by a whole pixel, which is invisible on
    // flat surfaces but shows up as a step where a high-contrast edge crosses
    // the tile seam.
    //
    // Fit a parabola through the winning cost and its two neighbours on each
    // axis and take its vertex. This is the standard quadratic interpolation
    // used for correlation-peak localisation and costs three already-computed
    // values per axis.
    //
    // Only indices 1..2 have both neighbours inside the 4x4 grid (index 0 is
    // the -1 edge and index 3 is the reserved candidate, whose cost belongs to
    // a different offset entirely and must never enter the fit). On an axis
    // where the winner sits at the edge the residual is left at zero: the
    // minimum is outside the search window, the parabola would extrapolate
    // rather than interpolate, and the next pyramid level re-centres on it
    // anyway.
    // A frozen tile has no measured minimum at all - the gate fired precisely
    // because the cost surface is indistinguishable from noise. Fitting a
    // parabola to that noise hands the tile a random shift of up to half a
    // pixel, which is what covered the smooth bright areas of the first test
    // shots in blocky colour patches. Textureless tiles keep the coarse field,
    // integer and unrefined.
    highp vec2 sub = vec2(0.0);
    // Curvature has to be large enough to be a real minimum rather than the
    // noise floor of the summed cost. The summed cost of an aligned tile is
    // ~1.13*n with a standard deviation of ~sqrt(1.13*n), so anything below a
    // fraction of that is noise. 1e-9 accepted essentially everything.
    float curvatureFloor = 0.25 * sqrt(1.13 * float(OFFSETS * TILE * TILE));
    if (!frozen && bestIdx.x >= 1 && bestIdx.x <= 2 && bestIdx.y <= 2) {
        float cm = sum[bestIdx.x - 1][bestIdx.y];
        float cc = sum[bestIdx.x    ][bestIdx.y];
        float cp = sum[bestIdx.x + 1][bestIdx.y];
        float denom = cm - 2.0 * cc + cp;
        // denom > 0 means the three samples are genuinely convex, i.e. a real
        // minimum. A flat or concave triple is noise and gets no shift.
        if (denom > curvatureFloor) {
            sub.x = clamp(0.5 * (cm - cp) / denom, -0.5, 0.5);
        }
    }
    if (!frozen && bestIdx.y >= 1 && bestIdx.y <= 2 && bestIdx.x <= 2) {
        float cm = sum[bestIdx.x][bestIdx.y - 1];
        float cc = sum[bestIdx.x][bestIdx.y    ];
        float cp = sum[bestIdx.x][bestIdx.y + 1];
        float denom = cm - 2.0 * cc + cp;
        if (denom > curvatureFloor) {
            sub.y = clamp(0.5 * (cm - cp) / denom, -0.5, 0.5);
        }
    }

    return vec4(bestOffset.x, bestOffset.y, sub.x, sub.y);
}

void main() {
    //ivec2 tile_xy = ivec2(gl_GlobalInvocationID.xy)/TILE;
    ivec2 tile_xy = ivec2(gl_WorkGroupID.xy);
    int localIndex = int(gl_LocalInvocationIndex);
    // Get previous offset
    vec2 prevOffset = vec2(0.0);
    if(first == 0) {
        prevOffset = getPrevOffset(tile_xy);
    }

    // Compute alignment vector. Both passes are fed the integer offset only
    // (.xy); the subpixel residual (.zw) of the first pass is discarded because
    // the second pass re-derives it from its own cost grid.
    vec4 bestOffset = computeAlignment(tile_xy, prevOffset);
    bestOffset = computeAlignment(tile_xy, bestOffset.xy);
    if (localIndex == 0) {
        // Store integer offset plus subpixel residual. alignmentToVec4 splits
        // the sum back into floor/fract, and the fract channels are read by
        // mergeAlign.glsl's bicubic sampler (RAW MFSR path).
        imageStore(outTexture, tile_xy, alignmentToVec4(bestOffset.xy + bestOffset.zw));
    }
}
