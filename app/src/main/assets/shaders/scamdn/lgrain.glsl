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

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    // P364: r = Orig - Den of the 6 x 6 texels p - 2 .. p + 3 from 9 textureGathers per input (.r of a 2 x 2 footprint; both
    // textures CLAMP_TO_EDGE, so a texel off the frame reads its edge texel as the clamped texelFetch did) instead of 50 texelFetch
    // pairs: the same texels, the same differences, the sums below in the same order (the 5 x 5 part, wx, wy = offset + 2)
    highp vec2 inv = 1.0 / vec2(textureSize(Den, 0));
    float rm[36];
    for (int b = 0; b < 3; b++) {
        for (int a = 0; a < 3; a++) {
            // the footprint of the texels p + (2a - 2 .. 2a - 1, 2b - 2 .. 2b - 1): their shared corner, p + (2a - 1, 2b - 1)
            highp vec2 at = vec2(p + ivec2(2 * a - 1, 2 * b - 1)) * inv;
            vec4 d = textureGather(Orig, at, 0) - textureGather(Den, at, 0);   // (x0,y1) (x1,y1) (x1,y0) (x0,y0)
            int k = 12 * b + 2 * a;
            rm[k] = d.w; rm[k + 1] = d.z; rm[k + 6] = d.x; rm[k + 7] = d.y;
        }
    }
    const float k5[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);
    float b5 = 0.0;
    for (int j = -2; j <= 2; j++)
        for (int i = -2; i <= 2; i++) b5 += k5[j + 2] * k5[i + 2] * rm[(j + 2) * 6 + i + 2];
    // the 2 px alternations at p (sign + at p itself) over the 5 x 5 sites around, weights (1 2 2 2 1) / 8 per axis: even in the
    // offset, so a constant or a linear r cancels in them and the 2 px period comes through whole
    const float w5[5] = float[5](0.125, 0.25, 0.25, 0.25, 0.125);
    float chk = 0.0, row = 0.0, col = 0.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            float r = rm[(j + 2) * 6 + i + 2] * w5[i + 2] * w5[j + 2];
            float si = (i & 1) == 0 ? 1.0 : -1.0, sj = (j & 1) == 0 ? 1.0 : -1.0;
            chk += si * sj * r;
            row += sj * r;
            col += si * r;
        }
    }
    float lattice = chk + row + col;
    Output = vec4(clamp(keepU, 0.0, 1.0) * (rm[14] - b5 - lattice), 0.0, 0.0, 1.0);
}
