precision highp float;
precision highp sampler2D;
// ARK luma sharpening (ArkLumaSharpen): one Richardson-Lucy stage of ArkCam 1.23 [helper 0x7a844; fc_cl_source.cl:
// 690-876; ark_sharpen.md 3.2; reference ark_sharpen_ref.py rl_pass] - est = Lobs; per iteration
// ratio = clamp(Lobs / max(PSF * est, 1e-4), 0.05, 10), est = max(0, est * (PSF * ratio)); the last iteration also
// blends (est - Lobs) * amount back into Lobs with the shadow fade, contrast damping and halo clamp of rl_blend.
// The TV damping term (damping 0 on X8U) is not ported. PSF (Airy, pillbox or Gaussian, built by ArkLumaSharpen) is
// symmetric in x and y: psfU holds one quadrant, q[|dy| * (half + 1) + |dx|], normalised over the whole kernel.
//  modeU 0: ratio            (Estimate -> R16F)
//  modeU 1: estimate update  (Estimate, Ratio -> R16F)
//  modeU 2: estimate update + rl_blend with Observed (the stage output)
uniform sampler2D Observed;         // Lobs: input of this stage
uniform sampler2D Estimate;         // current estimate (the first iteration passes Observed)
uniform sampler2D Ratio;            // modes 1, 2
uniform int modeU;
uniform int psfHalfU;               // <= 0 -> 2 (at most 8)
uniform float psfU[81];
uniform float amountU;
uniform float haloU;                // rl_halo_control 0..1
uniform float marginU;              // rl_halo_margin; <= 0 -> 0.15
uniform float macroU;               // rl_halo_macro; <= 0 -> 0.4
uniform float protectShadowsU;
uniform float protectHighlightsU;
uniform int nbU;                    // radius of the local min / max window (1 = 3x3); <= 0 -> 1
out vec4 Output;

// The kernel's blur buffers are half: round the convolutions to fp16 as OpenCL stores them.
float h16(float x) { return unpackHalf2x16(packHalf2x16(vec2(x, 0.0))).x; }

float convolve(sampler2D tex, ivec2 xy, ivec2 last, int h) {
    float s = 0.0;
    for (int dy = -h; dy <= h; dy++) {
        int row = (dy < 0 ? -dy : dy) * (h + 1);
        for (int dx = -h; dx <= h; dx++) {
            float w = psfU[row + (dx < 0 ? -dx : dx)];
            if (w > 0.0) s += texelFetch(tex, clamp(xy + ivec2(dx, dy), ivec2(0), last), 0).r * w;
        }
    }
    return h16(s);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(Observed, 0) - ivec2(1);
    int h = psfHalfU > 0 ? min(psfHalfU, 8) : 2;
    if (modeU == 0) {
        float b = convolve(Estimate, xy, last, h);
        float o = texelFetch(Observed, xy, 0).r;
        Output = vec4(clamp(o / max(b, 0.0001), 0.05, 10.0), 0.0, 0.0, 1.0);
        return;
    }
    float est = max(0.0, texelFetch(Estimate, xy, 0).r * convolve(Ratio, xy, last, h));
    if (modeU == 1) {
        Output = vec4(est, 0.0, 0.0, 1.0);
        return;
    }
    float o = texelFetch(Observed, xy, 0).r;
    float diff = (est - o) * amountU * smoothstep(0.002, 0.02, o);
    if (protectShadowsU > 0.001 || protectHighlightsU > 0.001) {
        float sl = 0.2 * protectShadowsU;
        float hl = 1.0 - 0.2 * protectHighlightsU;
        float mask = 1.0;
        if (hl < 0.999 && o > hl) mask = o >= 1.0 ? 0.0 : ((1.0 - o) / (1.0 - hl)) * ((1.0 - o) / (1.0 - hl));
        if (sl > 0.001 && o < sl) mask = (o / sl) * (o / sl);
        diff *= clamp(mask, 0.0, 1.0);
    }
    int nb = nbU > 0 ? nbU : 1;
    float lmin = o, lmax = o;
    for (int dy = -nb; dy <= nb; dy++) {
        for (int dx = -nb; dx <= nb; dx++) {
            float v = texelFetch(Observed, clamp(xy + ivec2(dx, dy), ivec2(0), last), 0).r;
            lmin = min(lmin, v);
            lmax = max(lmax, v);
        }
    }
    float lc = lmax - lmin;
    float macro = macroU > 0.0 ? macroU : 0.4;
    float margin = marginU > 0.0 ? marginU : 0.15;
    float ef = smoothstep(max(0.01, macro * 0.375), macro, lc);
    float md = 0.25 * (1.0 - haloU);
    float damp = mix(1.0, md, ef);
    if (lc > macro) damp = mix(damp, 0.5 * md, smoothstep(macro, macro * 1.75, lc));
    float v = o + diff * damp;
    if (haloU > 0.001) {
        float mg = (1.0 - ef) * (0.03 + lc * margin);
        float s = clamp(haloU, 0.0, 1.0);
        if (v > lmax + mg) v = lmax + mg + (v - lmax - mg) * (1.0 - s);
        else if (v < lmin - mg) v = lmin - mg - (lmin - mg - v) * (1.0 - s);
    }
    Output = vec4(max(v, 0.0), 0.0, 0.0, 1.0);
}
