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
// A sample of the alternate frame below this many noise sigmas carries no usable
// signal and is not merged. 0 disables the check.
#ifndef FLOOR_SIGMAS
#define FLOOR_SIGMAS 2.0
#endif
// --- RAW MFSR kernel regression tunables (Wronski et al. 2019) ---
// Kernel support in packed-quad units for detailed and for flat areas.
#ifndef MFSR_KDETAIL
#define MFSR_KDETAIL 0.5
#endif
#ifndef MFSR_KDENOISE
#define MFSR_KDENOISE 1.0
#endif
// Stretch along the edge and shrink across it, at full coherence.
#ifndef MFSR_KSTRETCH
#define MFSR_KSTRETCH 4.0
#endif
#ifndef MFSR_KSHRINK
#define MFSR_KSHRINK 2.0
#endif
// Gradient magnitude at which a pixel starts counting as a feature, and the
// width of that transition.
#ifndef MFSR_DTH
#define MFSR_DTH 0.005
#endif
#ifndef MFSR_DTR
#define MFSR_DTR 0.02
#endif
// Lower bound on the kernel sigma in quad units, guarding against degeneracy.
#ifndef MFSR_MIN_SIGMA
#define MFSR_MIN_SIGMA 0.3
#endif
// Spacing of the coarse grid on which the kernel field is evaluated, in packed
// quads. 1 reproduces the old per-pixel behaviour.
#ifndef MFSR_TENSOR_STRIDE
#define MFSR_TENSOR_STRIDE 8
#endif
// Gradients below this many noise sigmas are treated as unmeasured.
#ifndef MFSR_GRAD_K
#define MFSR_GRAD_K 2.5
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

// ---------------------------------------------------------------------------
// Kernel regression reconstruction, after Wronski et al., "Handheld Multi-Frame
// Super-Resolution" (ACM TOG 38(4), 2019), sections 5.1-5.1.2.
//
// The previous implementation resampled with Catmull-Rom. That kernel has
// negative outer lobes: at a fractional shift of 0.5 its weights are
// (-0.0625, 0.5625, 0.5625, -0.0625), and at 0.25 the outermost weight reaches
// -0.070. Where the signal sits near black those negative lobes undershoot and
// the clamp to [0,1] truncates the excursion asymmetrically, which is what
// produced the coloured streaks in the shadows of the night test shots. The
// paper instead uses anisotropic Gaussian RBF kernels (its equation 2), whose
// weights are strictly positive, so no undershoot is possible.
//
// w_i = exp(-0.5 * d_i^T * Omega^-1 * d_i)
//
// Omega is built from the local gradient structure tensor of the base frame
// (equations 3 and 4): eigenanalysis gives the edge direction and the two
// eigenvalues; k1 and k2 then set the kernel variance along and across the
// edge. The dominant eigenvalue drives the spatial support (the trade-off
// between resolution and denoising) and the eigenvalue ratio drives the
// anisotropy. Stretching the kernel along edges is what the paper's figure 7
// shows removing zipper artifacts caused by small misalignments.

// Luminance of a packed quad. Our packed texture already holds one Bayer quad
// per texel, which is exactly the half-resolution single-channel luminance
// image the paper decimates to in 5.1.2 - so the tensor is computed here
// directly, without a separate pass.
float quadLuma(ivec2 p) {
    ivec2 sz = imageSize(baseTexture);
    ivec2 q = clamp(p, ivec2(0), sz - ivec2(1));
    return dot(imageLoad(baseTexture, q), vec4(0.25));
}

// Kernel covariance on a coarse grid, packed as (a, b, c) of the symmetric
// matrix [[a, b], [b, c]]. Note this returns Omega itself, not its inverse:
// Wronski et al. upsample the covariance values and only then compute the
// kernel weights, and interpolating an inverse is not the same operation as
// inverting an interpolant.
//
// Two departures from the per-pixel version this replaces, both aimed at the
// same failure. The structure tensor was previously estimated from a 3x3 window
// at every output pixel. In deep shadow the gradients in such a window are
// almost entirely noise, so the eigenvalues are noise, so the edge direction
// and the coherence are noise - and an anisotropic kernel steered by noise
// smears along a random direction, producing elongated structure rather than
// even grain.
//
//  - The tensor is evaluated on a grid of spacing MFSR_TENSOR_STRIDE and
//    bilinearly interpolated between nodes, after the malleable-convolution
//    idea of Jiang et al. (ECCV 2022): predict a small field of spatially
//    varying kernels and slice it into full resolution, rather than deriving a
//    kernel per pixel. Their ablation shows this raising quality, not only
//    speed - a coarser field carries a larger receptive field.
//  - Gradients below the noise floor are discarded, after the "Bounded Flow"
//    masking of Liba et al. (Night Sight, 2019), which rejects gradients where
//    ||g|| < K*sigma with K = 2.5 for exactly this reason.
vec3 kernelCovarianceAt(ivec2 node, float sigmaLuma) {
    int st = MFSR_TENSOR_STRIDE;
    float ixx = 0.0, iyy = 0.0, ixy = 0.0;
    // Gradient magnitude below which a difference is indistinguishable from
    // noise. Differencing two samples adds their variances, hence the sqrt(2).
    float gateSq = MFSR_GRAD_K * MFSR_GRAD_K * 2.0 * sigmaLuma * sigmaLuma;
    for (int j = -1; j <= 1; ++j) {
        for (int i = -1; i <= 1; ++i) {
            ivec2 p = node + ivec2(i, j) * st;
            float gx = quadLuma(p + ivec2(st, 0)) - quadLuma(p);
            float gy = quadLuma(p + ivec2(0, st)) - quadLuma(p);
            if (gx * gx + gy * gy < gateSq) {
                continue;
            }
            ixx += gx * gx;
            iyy += gy * gy;
            ixy += gx * gy;
        }
    }
    ixx /= 9.0; iyy /= 9.0; ixy /= 9.0;

    float tr = ixx + iyy;
    float det = ixx * iyy - ixy * ixy;
    float disc = sqrt(max(tr * tr * 0.25 - det, 0.0));
    float l1 = tr * 0.5 + disc;
    float l2 = max(tr * 0.5 - disc, 0.0);

    vec2 e1 = vec2(ixy, l1 - ixx);
    float e1len = length(e1);
    e1 = e1len > 1e-12 ? e1 / e1len : vec2(1.0, 0.0);
    vec2 e2 = vec2(-e1.y, e1.x);

    // Every gradient gated away leaves l1 at zero, so a textureless tile lands
    // on feature = 0 and coherence = 0: the widest, roundest kernel there is.
    // That is the correct behaviour - denoise, do not sharpen along a direction
    // we could not measure.
    float feature = clamp((sqrt(l1) - MFSR_DTH) / max(MFSR_DTR, 1e-6), 0.0, 1.0);
    float support = mix(MFSR_KDENOISE, MFSR_KDETAIL, feature);

    float coherence = (l1 + l2) > 1e-12 ? (l1 - l2) / (l1 + l2) : 0.0;
    float stretch = mix(1.0, MFSR_KSTRETCH, coherence);
    float shrink  = mix(1.0, MFSR_KSHRINK, coherence);

    // Area-preserving normalisation. Applying stretch and shrink directly to
    // the support inflates the kernel instead of only reshaping it: at
    // kDetail 0.5 with kStretch 4 the sigma along the edge reached 2.0 quads,
    // four pixels, while the across-edge sigma hit the floor - a 6.7:1 kernel
    // with twice the area of the isotropic one. Test shots at kStretch 4 came
    // out visibly softer than at 1.
    //
    // Split the anisotropy symmetrically about the requested support instead,
    // so the geometric mean of the two sigmas stays at `support` whatever the
    // coherence. kStretch/kShrink then control the shape only, which is what
    // figure 7 of Wronski et al. is about.
    float aspect = sqrt(max(stretch * shrink, 1e-6));
    float k1 = max(support * aspect, MFSR_MIN_SIGMA);
    k1 = k1 * k1;
    float k2 = max(support / aspect, MFSR_MIN_SIGMA);
    k2 = k2 * k2;

    // Omega = [e2 e1] diag(k2, k1) [e2 e1]^T.
    float a = e2.x * e2.x * k2 + e1.x * e1.x * k1;
    float c = e2.y * e2.y * k2 + e1.y * e1.y * k1;
    float b = e2.x * e2.y * k2 + e1.x * e1.y * k1;
    return vec3(a, b, c);
}

// Slice the coarse covariance field at this pixel and invert once.
vec3 kernelCovarianceInv(ivec2 xy, float sigmaLuma) {
    int st = MFSR_TENSOR_STRIDE;
    ivec2 n0 = (xy / st) * st;
    vec2 f = vec2(xy - n0) / float(st);
    vec3 c00 = kernelCovarianceAt(n0, sigmaLuma);
    vec3 c10 = kernelCovarianceAt(n0 + ivec2(st, 0), sigmaLuma);
    vec3 c01 = kernelCovarianceAt(n0 + ivec2(0, st), sigmaLuma);
    vec3 c11 = kernelCovarianceAt(n0 + ivec2(st, st), sigmaLuma);
    vec3 om = mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);

    float det = om.x * om.z - om.y * om.y;
    if (det < 1e-12) {
        // Degenerate after interpolation: fall back to an isotropic kernel of
        // the denoising width rather than dividing by nothing.
        float k = max(MFSR_KDENOISE, MFSR_MIN_SIGMA);
        k = k * k;
        return vec3(1.0 / k, 0.0, 1.0 / k);
    }
    return vec3(om.z / det, -om.y / det, om.x / det);
}

// The same kernel applied to the base frame, at zero shift.
//
// Needed because the residual that drives the robustness weight compares the
// alternate sample against the base one. On the MFSR path the alternate sample
// is a nine-tap weighted mean while the base was a single texel, so the two
// sides of the comparison had different noise bandwidth. Read noise present in
// the base texel is averaged away in the alternate, which inflates the residual
// exactly on the rows where read noise is strongest; those rows lose trust,
// merge less, and keep their noise while their neighbours are denoised. The
// result is horizontal streaking - visible even at ISO 73, and independent of
// the kernel anisotropy, which is what the test shots showed.
//
// Comparing like with like removes the bias. The merged output still uses the
// resampled alternate; only the residual changes.
vec4 baseRBF(ivec2 xy, vec3 omegaInv) {
    ivec2 sz = imageSize(baseTexture);
    vec4 sum = vec4(0.0);
    float weightSum = 0.0;
    for (int j = -1; j <= 1; ++j) {
        for (int i = -1; i <= 1; ++i) {
            vec2 d = vec2(float(i), float(j));
            float q = omegaInv.x * d.x * d.x
                    + 2.0 * omegaInv.y * d.x * d.y
                    + omegaInv.z * d.y * d.y;
            float w = exp(-0.5 * q);
            ivec2 p = clamp(xy + ivec2(i, j), ivec2(0), sz - ivec2(1));
            sum += imageLoad(baseTexture, p) * w;
            weightSum += w;
        }
    }
    return sum / max(weightSum, 1e-6);
}

// Gather over the nine closest samples, as in the paper: every output pixel is
// processed once per frame and all nine samples share one kernel function.
vec4 samplePackedRBF(highp sampler2D tex, vec2 pos, vec3 omegaInv) {
    ivec2 sz = textureSize(tex, 0);
    ivec2 origin = ivec2(floor(pos));
    vec2 f = fract(pos);
    vec4 sum = vec4(0.0);
    float weightSum = 0.0;
    for (int j = -1; j <= 1; ++j) {
        for (int i = -1; i <= 1; ++i) {
            vec2 d = vec2(float(i), float(j)) - f;
            float q = omegaInv.x * d.x * d.x
                    + 2.0 * omegaInv.y * d.x * d.y
                    + omegaInv.z * d.y * d.y;
            float w = exp(-0.5 * q);
            ivec2 p = clamp(origin + ivec2(i, j), ivec2(0), sz - ivec2(1));
            sum += texelFetch(tex, p, 0) * w;
            weightSum += w;
        }
    }
    // Weights are positive, so the normalized result cannot leave the convex
    // hull of the samples and no clamping artefact is possible.
    return sum / max(weightSum, 1e-6);
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
    // One kernel per output pixel, shared by all nine samples and all four
    // blended alignment tiles. Only needed on the MFSR path.
    vec3 omegaInv = vec3(1.0, 0.0, 1.0);
    if (rawMfsr == 1) {
        // Luma-scale sigma for the gradient gate: the packed quad's luma is the
        // mean of four channels, so its noise is the channel noise over two.
        float sigmaLuma = dot(noise, vec4(0.25)) * 0.5;
        omegaInv = kernelCovarianceInv(xy, sigmaLuma);
    }

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
    // smoothstep with edge0 >= edge1 is undefined in GLSL, so a tolerance of 0 used to
    // give driver-dependent behaviour that zeroed the trust almost everywhere instead of
    // disabling the guard. Treat anything at or below 1 px as "off".
    float tilingTrust = (TILING_TOLERANCE <= 1.0)
            ? 1.0
            : 1.0 - smoothstep(1.0, TILING_TOLERANCE, alignSpread);

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
                ? samplePackedRBF(alterSampler, vec2(xy) + alignF, omegaInv)
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
        // Compare like with like: on the MFSR path both sides go through the
        // kernel, otherwise both are raw texels.
        vec4 baseForResidual = rawMfsr == 1 ? baseRBF(xy, omegaInv) : bayerBase;
        vec4 d = bayerAlter * vec4(exposure) - baseForResidual;
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

        // One weight for all four packed CFA channels, after Liba et al.
        // (Night Sight, 2019, sec. 3.2): instead of a per-channel weight they
        // take the minimum across colour channels and merge every channel with
        // it, because in hard conditions the per-channel weights diverge and
        // the divergence shows up as colour artefacts.
        //
        // The bracket is the worst case for this - the frames differ in
        // exposure, so the channels clip and saturate at different points, and
        // the window frames in the test shots came out with magenta and green
        // fringing exactly where one channel was trusted and another was not.
        float trustMin = min(min(trust.x, trust.y), min(trust.z, trust.w));
        trust = vec4(trustMin);

        // Invalid-pixel mask, the mirror image of the highlight mask. In a much
        // shorter frame the darker parts of the scene fall below the sensor's
        // noise and quantisation floor: the sample is not a measurement of the
        // scene at all, and its difference from the reference is not explained
        // by the noise model either, so merging it corrupts the pixel. Google
        // run this as DetectInvalidPixelsfromUltraShortFrame; without it a burst
        // whose only bracket member is the ultra-short frame falls apart.
        //
        // Validity is judged before exposure scaling, on the raw level of the
        // alternate frame, and faded in over one noise sigma so the mask itself
        // does not become a hard edge.
        vec4 floorLevel = vec4(FLOOR_SIGMAS) * noise;
        vec4 alterValid = smoothstep(vec4(0.0), max(floorLevel, vec4(1e-5)), bayerAlter);
        trust *= alterValid;

        trust *= vec4(tilingTrust);
        bayerAlter = mix(bayerNone, bayerAlter, trust);
        alignedSum += bayerAlter * w[i];
    }

    alignedSum = clamp(alignedSum, vec4(0.0), vec4(1.0));
    alignedSum *= vec4(exposure);

    imageStore(outTexture, xy, clamp(alignedSum, vec4(0.0), vec4(1.0)));
}
