// WHY tone, the exposure fusion's per-pixel part shared by why/gf.glsl (the guided filter: the post_ab old run, the RAW viewfinder
// with vf_why 2) and why/lpyr.glsl (P333: the Laplacian pyramid of the photo): the synthetic exposures, their ACES lumas and
// Gaussian weights (prepare), the saturated-light share (P310) and the fused luma of a flat neutral area (fusedAt, P291).
// Imported after the including program's InputBuffer.
uniform vec4 expU;              // exposure multipliers em; unset -> (1, 1, 1, 1)
uniform vec4 layerU;            // layer weights in expU's order; unset -> WhyCam (0.8, 1, 0.7, 0.5)
uniform float aeU;              // auto exposure; <= 0 -> 1
uniform float centerU;          // weight_center; <= 0 -> 0.6
uniform float smoothU;          // blend_smoothness; <= 0 -> 0.25
uniform float gammaInvU;        // 1 / ef_gamma; <= 0 -> 1 / 2.2
uniform float toeU;             // ACES toe; <= 0 -> 0.05
uniform float monoU;            // P291 MODE 3: rise of the fusion's monotonic envelope per ln of the light (0 / unset = the kernel)
// P310: x, y = the centre and the highlight layer weight for saturated light, z, w = the saturation ((max - min) / max) ramp from
// the given weights to those; x <= 0 / unset: every pixel takes the given weights (and monoU) as before.
uniform vec4 satLightU;
// P310: the light's level (its brightest channel x the AE, the fusion's input) over which that share fades in: only light near
// the top of the curve takes the kernel's weights; y <= 0 / unset: no level gate
uniform vec2 satLevelU;
// P348: the warm hues (r >= g >= b, (g - b) / (r - b) from x to y: orange to yellow - the sun, its glow, fire) left out of that
// share; y <= x / unset: every hue as before
uniform vec2 warmU;

// ACES tone scale of the kernel [fc_cl_source.cl:971-976] with pre-gain 1.5, a 2.51, d 0.59.
vec4 acescg(vec4 v, float toe) {
    vec4 x = max(v, vec4(0.0)) * 1.5;
    float b = max(toe * 0.75, 0.005);
    float e = max(0.14 - (toe - 0.04) * 0.8, 0.03);
    return (x * (2.51 * x + b)) / (x * (2.43 * x + 0.59) + e);
}

// P310 (owner, LED fans and an LED bar in a dark room: «куча артефактов на подсветке», «по ДД как минимум не хуже Why»): P291's
// weights with Bento headroom (centre 0.75, highlight layer 0.4, the monotonic lift) keep a white sheet under a lamp white, but
// they put every bright COLOURED light and the surfaces it lights near the top of the curve, where the AgX shoulder bleaches them:
// the LED bar a flat white bar, the violet-lit case pale lavender (WhyCam: the bar's LEDs and their pink-violet kept, the case
// deep violet). The share of saturated light takes the kernel's weights and no rise of the lift instead (host replay of the owner's
// shots: saturation of the bright pixels 0.35 -> 0.66, WhyCam 0.68; the desk-lamp sheet unchanged). Only BRIGHT light (satLevelU):
// without the gate every coloured surface at any level (skin, foliage, sky in the shadows of a lamp shot) took the kernel's weights,
// darker than a grey of the same luminance, and the guided filter spread the difference as halos.
float satShare(vec3 rgb) {
    if (satLightU.x <= 0.0) return 0.0;
    vec3 c = max(rgb, vec3(0.0));
    float mx = max(max(c.r, c.g), c.b);
    float sat = (mx - min(min(c.r, c.g), c.b)) / max(mx, 0.000001);
    float level = satLevelU.y > 0.0 ? smoothstep(satLevelU.x, satLevelU.y, mx * (aeU > 0.0 ? aeU : 1.0)) : 1.0;
    // P348 (owner, 2026-10-26, the sunset with the ladder: «очень жёсткий переход оранжевого, сделай ближе к Арк камере»): the
    // glow round a setting sun is as saturated as an LED (0.85 - 0.9) and falls 10 x within ~15 whyLow px, so the level gate
    // switched it from the pyramid to the per-pixel fusion there: a dark orange ring round a white disc, where ArkCam (no share)
    // has a bright, soft glow. Its hue is orange ((g - b) / (r - b) 0.30 - 0.58 on the owner's burst), the LEDs of P310 violet,
    // pink and blue; red (g ~ b) keeps the share. Natural warm light takes the pyramid, as ArkCam's.
    if (warmU.y > warmU.x && c.r >= c.g && c.g >= c.b && mx > c.b) {
        float t = (c.g - c.b) / (mx - c.b);
        level *= 1.0 - smoothstep(warmU.x, warmU.y, t) * (1.0 - smoothstep(0.85, 1.0, t));
    }
    return smoothstep(satLightU.z, satLightU.w, sat) * level;
}

// Guide, weights and lumas of one whyLow pixel [gf_prepare_weights].
void prepare(vec3 rgb, out float I, out vec4 p, out vec4 L) {
    float ae = aeU > 0.0 ? aeU : 1.0;
    vec4 em = expU.x + expU.y + expU.z + expU.w > 0.0 ? expU : vec4(1.0);
    vec4 lw = layerU.x + layerU.y + layerU.z + layerU.w > 0.0 ? layerU : vec4(0.8, 1.0, 0.7, 0.5);
    float center = centerU > 0.0 ? centerU : 0.6;
    float sw = satShare(rgb);
    center = mix(center, satLightU.x, sw);
    lw.x = mix(lw.x, satLightU.y, sw);
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

// P291: the fused luma of a neutral pixel of guide I without the guided filter (prepare's weights and lumas: every layer's
// meter is I x em); sw: the pixel's saturated-light share (P310).
float fusedAt(float I, float sw) {
    vec4 em = expU.x + expU.y + expU.z + expU.w > 0.0 ? expU : vec4(1.0);
    vec4 lw = layerU.x + layerU.y + layerU.z + layerU.w > 0.0 ? layerU : vec4(0.8, 1.0, 0.7, 0.5);
    float center = centerU > 0.0 ? centerU : 0.6;
    center = mix(center, satLightU.x, sw);
    lw.x = mix(lw.x, satLightU.y, sw);
    float smoothness = smoothU > 0.0 ? smoothU : 0.25;
    float gammaInv = gammaInvU > 0.0 ? gammaInvU : 1.0 / 2.2;
    float toe = toeU > 0.0 ? toeU : 0.05;
    vec4 L = pow(max(acescg(I * em, toe), vec4(0.000001)), vec4(gammaInv));
    vec4 d = L - vec4(center);
    vec4 w = exp(-0.5 * (d * d) / max(smoothness * smoothness, 0.001)) * lw + 0.000001;
    return dot(w, L) / (w.x + w.y + w.z + w.w);
}

