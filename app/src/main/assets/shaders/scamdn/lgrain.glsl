precision highp float;
precision highp int;
precision highp sampler2D;
// P335 (owner, 2026-10-25: «по поводу зерна шумодава же, у нас должно быть что-то между арк и нашим»): the share keepU of the
// luma noise the denoise removed on a fine scale, put back at the end (scamdn/cbf adds it to Y of the output). Only the fine part of
// the removed r = Y_in - Y_den (r minus its 5-tap binomial blur): the grain comes back, never the blotches of the coarse levels; it
// is the frame's own noise (no synthetic grain), so it is never more than the merge left, and edges the denoise kept are not
// touched (r ~ 0 there).
// P336 (owner, 2026-10-25: a 2 px grid on a lit PC panel): without the 2 px lattice - the checker, row and column alternation of
// r over the 5 x 5 sites around (the merge's Bayer lattice sits there) is taken out; the rest of the grain stays.
// ScamDenoise runs it on level 0 (the sensor scale) after the luma levels.
uniform sampler2D Orig;     // level-0 YUV before the luma denoise (.r = Y)
uniform sampler2D Den;      // level-0 YUV after the luma denoise (.r = Y)
uniform float keepU;        // share put back (0..1)
out vec4 Output;

float removed(ivec2 q, ivec2 last) {
    q = clamp(q, ivec2(0), last);
    return texelFetch(Orig, q, 0).r - texelFetch(Den, q, 0).r;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(Den, 0) - 1;
    const float k5[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);
    float b5 = 0.0;
    for (int j = -2; j <= 2; j++)
        for (int i = -2; i <= 2; i++) b5 += k5[j + 2] * k5[i + 2] * removed(p + ivec2(i, j), last);
    // the 2 px alternations at p (sign + at p itself) over the 5 x 5 sites around, weights (1 2 2 2 1) / 8 per axis: even in the
    // offset, so a constant or a linear r cancels in them and the 2 px period comes through whole
    const float w5[5] = float[5](0.125, 0.25, 0.25, 0.25, 0.125);
    float chk = 0.0, row = 0.0, col = 0.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            float r = removed(p + ivec2(i, j), last) * w5[i + 2] * w5[j + 2];
            float si = (i & 1) == 0 ? 1.0 : -1.0, sj = (j & 1) == 0 ? 1.0 : -1.0;
            chk += si * sj * r;
            row += sj * r;
            col += si * r;
        }
    }
    float lattice = chk + row + col;
    Output = vec4(clamp(keepU, 0.0, 1.0) * (removed(p, last) - b5 - lattice), 0.0, 0.0, 1.0);
}
