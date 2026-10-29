precision highp float;
precision highp sampler2D;
// WHY tone (WhyFusion): exposure fusion of the luminance on a fast guided filter, as run_ef_pyramid_luma of
// libfc_suppressor.so [code 0x78a5c; fc_cl_source.cl:1227-1289 gf_prepare_weights, 2317-2391 downsample/upsample_2x,
// 1374-1395 gf_compute_q_and_blend]. Per whyLow pixel: lin = max(rgb, 0) * ae, guide I = max(Y709, max/2); four
// synthetic exposures em, their ACES-encoded lumas L_k = ACES(max(Y709_k, max_k/2))^(1/gamma) and Gaussian weights
// around weight_center, times the layer weights (hl, mid, ext_hl, shadow), normalised.
//   MODE 0..2: the 2x2 mean (downsample_2x, clamped) at half the whyLow size of (I, I*I) / the weights p / p * I.
//   MODE 3:    fused = sum q_k L_k at the whyLow size, q = max(a I + b, 1e-4) normalised, with a, b bilinearly
//              upsampled from the box-filtered half-size coefficients (u = (x + 0.5)/2 - 0.5, clamped at 0).
// The guided-filter statistics live in RGBA32F (the kernel's float buffers: var I = E[I^2] - E[I]^2 cancels in fp16).
#define MODE 0
uniform sampler2D InputBuffer;  // whyLow (after bracket_dn), linear Rec.709
uniform sampler2D MeanA;        // MODE 3: box-filtered a (RGBA32F, half size)
uniform sampler2D MeanB;        // MODE 3: box-filtered b
// P404: the display standard of the fusion's lumas (import why_fusion; 0 = the pure power, as before)
#define GAMMA_TYPE 0
#import why_fusion
out vec4 Output;

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
    float fusedL = dot(q, L);
    // P291 (owner, 2026-10-20: a sheet under a desk lamp, «переходы плавные и мягкие»): with Bento headroom the highlight layer
    // lies ~5 EV under the base one (em.x 0.03 at 24 x white) and nothing between, so as the light rises past the base layer's
    // saturation the weights move to the dark layer and the fused luma FALLS (0.81 at I 0.55 -> 0.72 at I 2.3 -> 1 at I 30): a
    // dark ring round the lamp before its white core. The fusion's own curve F(I) is lifted to its monotonic envelope
    // max over I' <= I with F(I') >= 0.5 of F(I') + monoU ln(I / I') (a gentle rise instead of a plateau, so the light keeps its gradient); the
    // difference is added to the guided result. A curve that never falls (the usual layers, em.x >= 0.15) gets nothing.
    // P310: saturated light keeps the envelope's plateau (never a fall: the dark ring would come back on a coloured lamp) but not its
    // rise towards the shoulder.
    float sw = satShare(texelFetch(InputBuffer, xy, 0).rgb);
    if (monoU > 0.0 && I > 0.0) {
        float rise = monoU * (1.0 - sw);
        float f = fusedAt(I, sw), env = f;
        for (int j = 1; j <= 36; j++) {
            float s = float(j) * (0.25 * 0.6931472);    // 1/4 EV steps, 9 EV down
            float fj = fusedAt(I * exp(-s), sw);
            if (fj >= 0.5) env = max(env, fj + rise * s);   // the highlights only: the toe's own rise is slower than any monoU
        }
        fusedL += env - f;
    }
    Output = vec4(fusedL, 0.0, 0.0, 1.0);
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
