precision highp float;
precision highp int;
precision highp sampler2D;
// P335 (owner, 2026-10-25: «по поводу зерна шумодава же, у нас должно быть что-то между арк и нашим»): the share keepU of the
// luma noise the denoise removed on the finest scale, put back at the end (scamdn/cbf adds it to Y of the output). Only the high
// band of the removed part r = Y_in - Y_den (r minus its 1-2-1 blur): the grain of the pixels comes back, never the blotches of the
// coarse levels; it is the frame's own noise (no synthetic grain), so it is never more than the merge left, and edges the
// denoise kept are not touched (r ~ 0 there). ScamDenoise runs it on level 0 (the sensor scale) after the luma levels.
uniform sampler2D Orig;     // level-0 YUV before the luma denoise (.r = Y)
uniform sampler2D Den;      // level-0 YUV after the luma denoise (.r = Y)
uniform float keepU;        // share of the high band put back (0..1)
out vec4 Output;

float removed(ivec2 q, ivec2 last) {
    q = clamp(q, ivec2(0), last);
    return texelFetch(Orig, q, 0).r - texelFetch(Den, q, 0).r;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(Den, 0) - 1;
    float c = removed(p, last);
    float b = 0.25 * c
            + 0.125 * (removed(p + ivec2(1, 0), last) + removed(p - ivec2(1, 0), last) + removed(p + ivec2(0, 1), last) + removed(p - ivec2(0, 1), last))
            + 0.0625 * (removed(p + ivec2(1, 1), last) + removed(p - ivec2(1, 1), last) + removed(p + ivec2(1, -1), last) + removed(p - ivec2(1, -1), last));
    Output = vec4(clamp(keepU, 0.0, 1.0) * (c - b), 0.0, 0.0, 1.0);
}
