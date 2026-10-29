precision highp float;
precision highp sampler2D;
// WHY luma sharpening (WhyLumaSharpen): one Richardson-Lucy stage of WhyCam 1.23 [helper 0x7a844; fc_cl_source.cl:
// 690-876; why_sharpen.md 3.2; reference why_sharpen_ref.py rl_pass] - est = Lobs; per iteration
// ratio = clamp(Lobs / max(PSF * est, 1e-4), 0.05, 10), est = max(0, est * (PSF * ratio)); the last iteration also
// blends (est - Lobs) * amount back into Lobs with the shadow fade, contrast damping and halo clamp of rl_blend.
// P404: the TV damping of v2.07's rl_mult (dampU, per stage; 0 = off as before). PSF (Airy, pillbox or Gaussian, built by WhyLumaSharpen) is
// symmetric in x and y: psfU holds one quadrant, q[|dy| * (half + 1) + |dx|], normalised over the whole kernel.
//  modeU 0: ratio            (Estimate -> R16F)
//  modeU 1: estimate update  (Estimate, Ratio -> R16F)
//  modeU 2: estimate update + rl_blend with Observed (the stage output)
// P364: PSF_HALF > 0 = psfHalfU known when the program is made (WhyLumaSharpen: 2 for both stages of the 1x grid, 4 on the 2x
// grid): the convolutions read 2 x 2 blocks by textureGather - (H + 1)^2 reads instead of (2H + 1)^2 texelFetch (9 instead of 25)
// - and add the same taps with the same weights in the same order (row by row, left to right). The planes are clamp-to-edge,
// so a block past the border repeats the edge texels as clamp() did: the same sums bit for bit. 0 = the loop below.
#define PSF_HALF 0
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
uniform float dampU;                // P404: total-variation damping of the update (0 = off)
out vec4 Output;

// The kernel's blur buffers are half: round the convolutions to fp16 as OpenCL stores them.
float h16(float x) { return unpackHalf2x16(packHalf2x16(vec2(x, 0.0))).x; }

#if PSF_HALF > 0
// P364: block (x0, y0) .. (x0 + 1, y0 + 1) of a gather at the centre of texel (x0 + 1, y0 + 1): .w (x0, y0), .z (x0 + 1, y0),
// .x (x0, y0 + 1), .y (x0 + 1, y0 + 1); the rows by and by + 1 of a block row are added one after the other.
float convolve(sampler2D tex, ivec2 xy, ivec2 last, int h) {
    const int H = PSF_HALF;
    vec2 inv = 1.0 / vec2(last + ivec2(1));
    float s = 0.0;
    for (int by = -H; by <= H; by += 2) {
        vec2 lower[H + 1];
        for (int k = 0; k <= H; k++) {
            int bx = 2 * k - H;
            vec4 g = textureGather(tex, (vec2(xy + ivec2(bx, by)) + 1.0) * inv);
            float w = psfU[abs(by) * (H + 1) + abs(bx)];
            if (w > 0.0) s += g.w * w;
            if (bx < H) {
                w = psfU[abs(by) * (H + 1) + abs(bx + 1)];
                if (w > 0.0) s += g.z * w;
            }
            lower[k] = g.xy;
        }
        if (by < H) {
            for (int k = 0; k <= H; k++) {
                int bx = 2 * k - H;
                float w = psfU[abs(by + 1) * (H + 1) + abs(bx)];
                if (w > 0.0) s += lower[k].x * w;
                if (bx < H) {
                    w = psfU[abs(by + 1) * (H + 1) + abs(bx + 1)];
                    if (w > 0.0) s += lower[k].y * w;
                }
            }
        }
    }
    return h16(s);
}
#else
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
#endif

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
    if (dampU > 0.0001) {
        // P404: v2.07 rl_mult - the update divided by 1 - damping x 0.002 x the curvature of the estimate's level lines
        float c0 = texelFetch(Estimate, xy, 0).r;
        float r = texelFetch(Estimate, clamp(xy + ivec2(1, 0), ivec2(0), last), 0).r, l = texelFetch(Estimate, clamp(xy + ivec2(-1, 0), ivec2(0), last), 0).r;
        float d = texelFetch(Estimate, clamp(xy + ivec2(0, 1), ivec2(0), last), 0).r, u = texelFetch(Estimate, clamp(xy + ivec2(0, -1), ivec2(0), last), 0).r;
        float dl = texelFetch(Estimate, clamp(xy + ivec2(-1, 1), ivec2(0), last), 0).r, ul = texelFetch(Estimate, clamp(xy + ivec2(-1, -1), ivec2(0), last), 0).r;
        float ur = texelFetch(Estimate, clamp(xy + ivec2(1, -1), ivec2(0), last), 0).r;
        float ux = (r - c0) / sqrt((r - c0) * (r - c0) + 0.25 * (d - u) * (d - u) + 1e-6);
        float uy = (d - c0) / sqrt(0.25 * (r - l) * (r - l) + (d - c0) * (d - c0) + 1e-6);
        float uxl = (c0 - l) / sqrt((c0 - l) * (c0 - l) + 0.25 * (dl - ul) * (dl - ul) + 1e-6);
        float uyu = (c0 - u) / sqrt(0.25 * (ur - ul) * (ur - ul) + (c0 - u) * (c0 - u) + 1e-6);
        est /= max(0.1, 1.0 - dampU * 0.002 * ((ux - uxl) + (uy - uyu)));
    }
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
