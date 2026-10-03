precision highp float;
precision highp sampler2D;
// ARK tone (ArkFusion): exposure fusion of the luminance on a fast guided filter, as run_ef_pyramid_luma of
// libfc_suppressor.so [code 0x78a5c; fc_cl_source.cl:1227-1289 gf_prepare_weights, 2317-2391 downsample/upsample_2x,
// 1374-1395 gf_compute_q_and_blend]. Per arkLow pixel: lin = max(rgb, 0) * ae, guide I = max(Y709, max/2); four
// synthetic exposures em, their ACES-encoded lumas L_k = ACES(max(Y709_k, max_k/2))^(1/gamma) and Gaussian weights
// around weight_center, times the layer weights (hl, mid, ext_hl, shadow), normalised.
//   MODE 0..2: the 2x2 mean (downsample_2x, clamped) at half the arkLow size of (I, I*I) / the weights p / p * I.
//   MODE 3:    fused = sum q_k L_k at the arkLow size, q = max(a I + b, 1e-4) normalised, with a, b bilinearly
//              upsampled from the box-filtered half-size coefficients (u = (x + 0.5)/2 - 0.5, clamped at 0).
// The guided-filter statistics live in RGBA32F (the kernel's float buffers: var I = E[I^2] - E[I]^2 cancels in fp16).
#define MODE 0
uniform sampler2D InputBuffer;  // arkLow (after bracket_dn), linear Rec.709
uniform sampler2D MeanA;        // MODE 3: box-filtered a (RGBA32F, half size)
uniform sampler2D MeanB;        // MODE 3: box-filtered b
uniform vec4 expU;              // exposure multipliers em; unset -> (1, 1, 1, 1)
uniform vec4 layerU;            // layer weights (hl, mid, ext_hl, shadow); unset -> ArkCam (0.8, 1, 0.7, 0.5)
uniform float aeU;              // auto exposure; <= 0 -> 1
uniform float centerU;          // weight_center; <= 0 -> 0.6
uniform float smoothU;          // blend_smoothness; <= 0 -> 0.25
uniform float gammaInvU;        // 1 / ef_gamma; <= 0 -> 1 / 2.2
uniform float toeU;             // ACES toe; <= 0 -> 0.05
out vec4 Output;

// ACES tone scale of the kernel [fc_cl_source.cl:971-976] with pre-gain 1.5, a 2.51, d 0.59.
vec4 acescg(vec4 v, float toe) {
    vec4 x = max(v, vec4(0.0)) * 1.5;
    float b = max(toe * 0.75, 0.005);
    float e = max(0.14 - (toe - 0.04) * 0.8, 0.03);
    return (x * (2.51 * x + b)) / (x * (2.43 * x + 0.59) + e);
}

// Guide, weights and lumas of one arkLow pixel [gf_prepare_weights].
void prepare(vec3 rgb, out float I, out vec4 p, out vec4 L) {
    float ae = aeU > 0.0 ? aeU : 1.0;
    vec4 em = expU.x + expU.y + expU.z + expU.w > 0.0 ? expU : vec4(1.0);
    vec4 lw = layerU.x + layerU.y + layerU.z + layerU.w > 0.0 ? layerU : vec4(0.8, 1.0, 0.7, 0.5);
    float center = centerU > 0.0 ? centerU : 0.6;
    float smoothness = smoothU > 0.0 ? smoothU : 0.25;
    float gammaInv = gammaInvU > 0.0 ? gammaInvU : 1.0 / 2.2;
    float toe = toeU > 0.0 ? toeU : 0.05;
    vec3 lin = max(rgb, vec3(0.0)) * ae;
    if (isnan(lin.r) || isinf(lin.r)) lin.r = 1.0;
    if (isnan(lin.g) || isinf(lin.g)) lin.g = 1.0;
    if (isnan(lin.b) || isinf(lin.b)) lin.b = 1.0;
    float y709 = lin.r * 0.2126 + lin.g * 0.7152 + lin.b * 0.0722;
    I = max(y709, max(max(lin.r, lin.g), lin.b) * 0.5);
    vec4 r = lin.r * em, g = lin.g * em, b = lin.b * em;
    vec4 l709 = r * 0.2126 + g * 0.7152 + b * 0.0722;
    vec4 lmax = max(max(r, g), b);
    vec4 meter = max(l709, lmax * 0.5);
    L = pow(max(acescg(meter, toe), vec4(0.000001)), vec4(gammaInv));
    vec4 d = L - vec4(center);
    float var = max(smoothness * smoothness, 0.001);
    vec4 w = exp(-0.5 * (d * d) / var) * lw + 0.000001;
    p = w / (w.x + w.y + w.z + w.w);
}

// Bilinear tap of upsample_2x_f4 along one axis: index pair and weight for output coordinate x (integer arithmetic).
void upTap(int x, int n, out int i0, out int i1, out float f) {
    if (x <= 0) { i0 = 0; f = 0.0; }
    else if ((x & 1) == 0) { i0 = x / 2 - 1; f = 0.75; }
    else { i0 = (x - 1) / 2; f = 0.25; }
    i0 = min(i0, n - 1);
    i1 = min(i0 + 1, n - 1);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
#if MODE == 3
    float I;
    vec4 p, L;
    prepare(texelFetch(InputBuffer, xy, 0).rgb, I, p, L);
    ivec2 n = textureSize(MeanA, 0);
    int x0, x1, y0, y1;
    float fx, fy;
    upTap(xy.x, n.x, x0, x1, fx);
    upTap(xy.y, n.y, y0, y1, fy);
    vec4 a = mix(mix(texelFetch(MeanA, ivec2(x0, y0), 0), texelFetch(MeanA, ivec2(x1, y0), 0), fx),
                 mix(texelFetch(MeanA, ivec2(x0, y1), 0), texelFetch(MeanA, ivec2(x1, y1), 0), fx), fy);
    vec4 b = mix(mix(texelFetch(MeanB, ivec2(x0, y0), 0), texelFetch(MeanB, ivec2(x1, y0), 0), fx),
                 mix(texelFetch(MeanB, ivec2(x0, y1), 0), texelFetch(MeanB, ivec2(x1, y1), 0), fx), fy);
    vec4 q = max(a * I + b, vec4(0.0001));
    q /= q.x + q.y + q.z + q.w;
    Output = vec4(dot(q, L), 0.0, 0.0, 1.0);
#else
    vec4 acc = vec4(0.0);
    for (int j = 0; j < 2; j++) {
        for (int i = 0; i < 2; i++) {
            float I;
            vec4 p, L;
            prepare(texelFetch(InputBuffer, min(xy * 2 + ivec2(i, j), last), 0).rgb, I, p, L);
#if MODE == 0
            acc += vec4(I, I * I, 0.0, 0.0);
#elif MODE == 1
            acc += p;
#else
            acc += p * I;
#endif
        }
    }
    Output = acc * 0.25;
#endif
}
