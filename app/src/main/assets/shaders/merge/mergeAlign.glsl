#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
precision highp image2D;
uniform highp usampler2D inTexture;
uniform highp sampler2D alignmentTexture;
// Tuning factor on the noise variance: larger accepts more of the aligned
// frame (more denoising, less robustness). HDR+ fixes the equivalent to 8.
#ifndef ROBUSTNESS
#define ROBUSTNESS 8.0
#endif
// Samples at or above this fraction of full scale are treated as clipped.
#ifndef CLIP_LEVEL
#define CLIP_LEVEL 0.99
#endif
// Disagreement between the four alignment tiles, in pixels, at which the local
// alignment field is considered unusable.
#ifndef TILING_TOLERANCE
#define TILING_TOLERANCE 4.0
#endif
uniform highp sampler2D alterSampler;
//layout(r16ui, binding = 0) uniform highp readonly uimage2D inTexture;
layout(rgba16f, binding = 0) uniform highp readonly image2D avrTexture;
layout(rgba8, binding = 1) uniform highp readonly image2D hotPixTexture;
layout(rgba16f, binding = 2) uniform highp readonly image2D baseTexture;
layout(rgba16f, binding = 3) uniform highp writeonly image2D outTexture;
layout(rgba16f, binding = 4) uniform highp readonly image2D alterTexture;

uniform float minLevel;
uniform uint whitelevel;
uniform vec4 blackLevel;
uniform float exposure;
uniform float exposureLow;
uniform bool createDiff;
uniform float noiseS;
uniform float noiseO;
uniform ivec2 border;
uniform ivec2 shift;
uniform ivec2 alignmentSize;
uniform ivec2 rawHalf;
uniform vec4 analogBalance;
uniform ivec2 cfaShift; // sensor red-site offset (cfa%2, cfa/2), 0..1 per axis
uniform int rawMfsr;
#define TILE 2
#define CONCAT 1
#define M_PI 3.1415926535897932384626433832795
#define TILE_AL 16

uint getBayer(ivec2 coords, highp usampler2D tex){
    return texelFetch(tex,coords,0).r;
}

// Repack raw with the same red-site origin shift as merge00 (negative shift,
// edge-duplicated fetches), so the normalized quad layout (R, Gr, Gb, B)
// matches the packed textures; blackLevel is already permuted accordingly.
vec4 getBayerVec(ivec2 coords, highp usampler2D tex){
    ivec2 sz = textureSize(tex, 0);
    ivec2 org = coords - cfaShift;
    vec4 c0 = vec4(getBayer(clamp(org, ivec2(0), sz - ivec2(1)),tex),
                   getBayer(clamp(org + ivec2(1,0), ivec2(0), sz - ivec2(1)),tex),
                   getBayer(clamp(org + ivec2(0,1), ivec2(0), sz - ivec2(1)),tex),
                   getBayer(clamp(org + ivec2(1,1), ivec2(0), sz - ivec2(1)),tex));
    return clamp((c0 - blackLevel)/(vec4(float(whitelevel))-blackLevel), 0.0, 1.0);
}

float window(float x){
    return 0.5f - 0.5f * cos(2.f * M_PI * ((0.5f * (x + 0.5f) / float(TILE_AL))));
}

float windowxy(ivec2 xy){
    return window(float(xy.x)) * window(float(xy.y));
}

vec4 windowxy4(ivec2 xy){
    return vec4(window(float(xy.x)) * window(float(xy.y)),
                window(float(xy.x+1)) * window(float(xy.y)),
                window(float(xy.x)) * window(float(xy.y+1)),
                window(float(xy.x+1)) * window(float(xy.y+1)));
}

vec2 vec4ToAlignment(vec4 alignment) {
    // Round the integer part: rgba16f precision reconstructs floor(v)/rawHalf
    // as e.g. 1.9998 and truncation would bias offsets by -1px. The fract
    // part (subpixel residual) is preserved for the caller to floor().
    return floor(alignment.xy * vec2(rawHalf) + vec2(0.5)) + alignment.zw;
}
vec2 hash22(vec2 p)
{
    vec3 p3 = fract(vec3(p.xyx) * vec3(.1031, .1030, .0973));
    p3 += dot(p3, p3.yzx+33.33);
    return fract((p3.xx+p3.yz)*p3.zy);
}

// Catmull-Rom reconstruction on the packed Bayer grid. Each RGBA channel is
// one fixed CFA site, so this improves sub-pixel reconstruction without ever
// interpolating red, green and blue sites into each other. The previous
// hardware bilinear lookup suppressed exactly the high frequencies MFSR is
// meant to recover.
float cubicWeight(float x) {
    x = abs(x);
    if (x <= 1.0) return 1.5*x*x*x - 2.5*x*x + 1.0;
    if (x < 2.0) return -0.5*x*x*x + 2.5*x*x - 4.0*x + 2.0;
    return 0.0;
}
vec4 samplePackedBicubic(highp sampler2D tex, vec2 pos) {
    ivec2 sz = textureSize(tex, 0);
    ivec2 origin = ivec2(floor(pos));
    vec2 f = fract(pos);
    vec4 sum = vec4(0.0);
    float weightSum = 0.0;
    for (int j = -1; j <= 2; ++j) {
        float wy = cubicWeight(float(j) - f.y);
        for (int i = -1; i <= 2; ++i) {
            float wxy = cubicWeight(float(i) - f.x) * wy;
            ivec2 q = clamp(origin + ivec2(i,j), ivec2(0), sz - ivec2(1));
            sum += texelFetch(tex, q, 0) * wxy;
            weightSum += wxy;
        }
    }
    return clamp(sum / max(weightSum, 1e-6), vec4(0.0), vec4(1.0));
}
void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    ivec2 outSize = imageSize(outTexture);
    vec2 uvScale = vec2(outSize-border);
    vec2 uv = vec2(xy)/uvScale + vec2(0.5)/uvScale;
    vec4 bayerBase = imageLoad(baseTexture,xy);
    vec4 bayer = getBayerVec(xy*TILE, inTexture);
    //vec4 hp = imageLoad(hotPixTexture, xy);
    //bayer = bayer * vec4(1.0-hp) + imageLoad(avrTexture, xy) * hp;
    vec4 noise = vec4(max(sqrt(max(bayer * noiseS + noiseO, 1e-6)), vec4(minLevel)));
    vec4 w[4];
    w[3] = windowxy4((TILE*xy)%TILE_AL);
    w[2] = windowxy4((TILE*xy)%TILE_AL + ivec2(TILE_AL,0));
    w[1] = windowxy4((TILE*xy)%TILE_AL + ivec2(0,TILE_AL));
    w[0] = windowxy4((TILE*xy)%TILE_AL + ivec2(TILE_AL));
    vec4 alignedSum = vec4(0.0);
    vec4 bayerNone = imageLoad(alterTexture, xy);

    // Tiling guard. The four alignment tiles blended at this pixel should agree;
    // when they disagree by more than a couple of pixels the alignment field is
    // locally inconsistent, which is exactly the situation that shows up in the
    // output as a rectangular patch lifted from the wrong place. Google run an
    // explicit HasTilingArtifacts check and fall back when it fires; we cannot
    // read the field back on the GPU cheaply, so we apply the same idea per
    // pixel: the less the tiles agree, the less any of them is trusted.
    vec2 alignAvg = vec2(0.0);
    vec2 alignVecs[4];
    for (int i = 0; i < 4; i++) {
        ivec2 t = clamp(ivec2((TILE*xy)/TILE_AL + ivec2(i % 2, i / 2)), ivec2(0), alignmentSize-1);
        alignVecs[i] = vec4ToAlignment(texelFetch(alignmentTexture, t + shift, 0));
        alignAvg += alignVecs[i] * 0.25;
    }
    float alignSpread = 0.0;
    for (int i = 0; i < 4; i++) {
        alignSpread = max(alignSpread, length(alignVecs[i] - alignAvg));
    }
    // 1 px of disagreement is normal at a tile seam; beyond TILING_TOLERANCE the
    // field is untrustworthy and we fade back towards the unaligned frame.
    float tilingTrust = 1.0 - smoothstep(1.0, TILING_TOLERANCE, alignSpread);

    for (int i = 0; i < 4; i++) {
        ivec2 xyT = clamp(ivec2((TILE*xy)/TILE_AL + ivec2(i % 2, i / 2)),ivec2(0),alignmentSize-1);
        vec4 alignLoad = texelFetch(alignmentTexture, xyT + shift, 0);
        vec2 alignF = vec4ToAlignment(alignLoad);
        ivec2 align = ivec2(floor(alignF));
        ivec2 aligned = clamp(xy + align, ivec2(0), outSize - ivec2(1));
        // Packed RGBA stores one Bayer colour per channel, therefore bilinear
        // interpolation across packed texels never mixes CFA colours. With
        // several fractional hand-tremor phases this is shift-and-add RAW
        // multi-frame super-resolution on the native output grid.
        vec4 bayerAlter = rawMfsr == 1
                ? samplePackedBicubic(alterSampler, vec2(xy) + alignF)
                : imageLoad(alterTexture, aligned);
        // Robustness: how much of the aligned sample do we trust? The previous
        // form compared relative residuals through smoothstep(.., 0.48, 0.51),
        // a transition three hundredths wide - effectively a binary switch.
        // Neighbouring pixels with nearly equal residuals landed on opposite
        // sides of it and flipped between two different sources, which is what
        // produced the blotches. Use a Wiener-style shrinkage instead: the
        // aligned sample is accepted in proportion to how well its difference
        // from the reference is explained by the noise model.
        //
        // The alternate frame is scaled by 'exposure' to reach the reference
        // level, and its noise scales with it, so the tolerance has to grow the
        // same way - otherwise a darker bracketed frame is judged against the
        // reference frame's noise and rejected almost everywhere.
        vec4 d = bayerAlter * vec4(exposure) - bayerBase;
        vec4 sigma = noise * vec4(max(exposure, 1.0));
        vec4 d2 = d * d;
        vec4 n2 = ROBUSTNESS * sigma * sigma;
        vec4 trust = n2 / (d2 + n2 + 1e-9);

        // Highlight mask. A clipped sample carries no information about the
        // scene: its true value is somewhere above the white level, so any
        // residual computed from it is meaningless and merging it drags the
        // pixel towards the clip. Google build this as a separate
        // GenerateHighlightClippingMask and mask their rejection map with it.
        // Both sides matter: a clipped alternate sample must not be merged in,
        // and where the reference itself is clipped the residual cannot decide
        // anything either.
        vec4 alterScaled = bayerAlter * vec4(exposure);
        vec4 alterOk = step(alterScaled, vec4(CLIP_LEVEL));
        vec4 baseOk  = step(bayerBase,   vec4(CLIP_LEVEL));
        trust *= alterOk * baseOk;

        trust *= vec4(tilingTrust);
        bayerAlter = mix(bayerNone, bayerAlter, trust);
        alignedSum += bayerAlter * w[i];
    }

    alignedSum = clamp(alignedSum, vec4(0.0), vec4(1.0));
    alignedSum *= vec4(exposure);

    imageStore(outTexture, xy, clamp(alignedSum, vec4(0.0), vec4(1.0)));
}
