precision highp float;
precision highp int;
precision highp sampler2D;
// SCAM noise reduction, noise statistics of one pyramid level (the measured counterpart of GCam's
// ScaleForStride: our merge leaves correlated noise, so the per-level, per-stride noise is measured instead of
// derived from a white spectrum). One output texel per block of 16 x 16 level pixels (4 x 4 sub-blocks of 4 x 4):
//   x = packHalf(mean Y, texture T)   T = variance of the 16 sub-block means of Y, in noise units
//   y = packHalf(gY1, gY2)            g = v / 0.945 / model_Y(mean Y): v = the median over the 16 sub-blocks of the
//   z = packHalf(gU1, gU2)                mean squared difference x(p + s e) - x(p) (s = 1, 2; horizontal and vertical
//   w = packHalf(gV1, gV2)                pairs inside the sub-block) minus the block's own linear gradient (plane fit
//                                         of the sub-block means): smooth illumination gradients and an edge crossing a
//                                         few sub-blocks do not count as noise; 0.945 = median bias for white noise.
// g is the variance of a pixel difference at distance s relative to the single-frame model of Y at the block's
// level; the host takes the median over the flattest blocks (lowest T). Blocks that leave the image are marked
// with mean Y = -1.
uniform sampler2D InputBuffer;   // Y,U,V of the level in rgb (a luma level: Y in r, the rest unused)
uniform int blockU;              // block side in level pixels (16; unset = 16)
uniform vec2 modelU;             // (s_Y, r_Y): single-frame variance of Y = s_Y * Y + r_Y (unset = 1e-4, 1e-6)
out uvec4 Output;

float median16(float v[16]) {
    for (int i = 1; i < 16; i++) {
        float x = v[i];
        int j = i - 1;
        while (j >= 0 && v[j] > x) { v[j + 1] = v[j]; j--; }
        v[j + 1] = x;
    }
    return 0.5 * (v[7] + v[8]);
}

void main() {
    int B = blockU > 0 ? blockU : 16;
    int sub = max(B / 4, 3);
    vec2 model = modelU.x > 0.0 ? modelU : vec2(1.0e-4, 1.0e-6);
    ivec2 size = textureSize(InputBuffer, 0);
    ivec2 o = ivec2(gl_FragCoord.xy) * B;
    if (o.x + 4 * sub > size.x || o.y + 4 * sub > size.y) {
        Output = uvec4(packHalf2x16(vec2(-1.0, 0.0)), 0u, 0u, 0u);
        return;
    }
    // pass 1: sub-block means, texture T, the block's linear gradient per channel (least squares over the 4 x 4 means)
    vec3 sx = vec3(0.0), sy = vec3(0.0), tot = vec3(0.0);
    float m0 = 0.0, sumM = 0.0, sumM2 = 0.0;
    for (int sj = 0; sj < 4; sj++) {
        for (int si = 0; si < 4; si++) {
            vec3 s = vec3(0.0);
            for (int j = 0; j < sub; j++)
                for (int i = 0; i < sub; i++)
                    s += texelFetch(InputBuffer, o + ivec2(si * sub + i, sj * sub + j), 0).rgb;
            vec3 m = s / float(sub * sub);
            tot += m;
            sx += (float(si) - 1.5) * m;
            sy += (float(sj) - 1.5) * m;
            if (sj == 0 && si == 0) m0 = m.x;     // shift: the variance of the means without cancellation
            sumM += m.x - m0;
            sumM2 += (m.x - m0) * (m.x - m0);
        }
    }
    sx /= 20.0 * float(sub);                     // per level pixel
    sy /= 20.0 * float(sub);
    float meanY = tot.x / 16.0;
    float var = max(model.x * max(meanY, 0.0) + model.y, 1.0e-12);
    float subVar = max(sumM2 / 16.0 - (sumM / 16.0) * (sumM / 16.0), 0.0);
    float t = min(subVar * float(sub * sub) / var, 60000.0);
    // pass 2: per sub-block mean squared difference without the gradient (pairs inside the sub-block)
    float m1[16], m2[16], u1[16], u2[16], v1[16], v2[16];
    float n1 = float(2 * sub * (sub - 1)), n2 = float(2 * sub * (sub - 2));
    for (int sj = 0; sj < 4; sj++) {
        for (int si = 0; si < 4; si++) {
            vec3 e1 = vec3(0.0), e2 = vec3(0.0);
            for (int j = 0; j < sub; j++) {
                for (int i = 0; i < sub; i++) {
                    ivec2 p = o + ivec2(si * sub + i, sj * sub + j);
                    vec3 c = texelFetch(InputBuffer, p, 0).rgb;
                    vec3 d;
                    if (i + 1 < sub) { d = texelFetch(InputBuffer, p + ivec2(1, 0), 0).rgb - c - sx; e1 += d * d; }
                    if (j + 1 < sub) { d = texelFetch(InputBuffer, p + ivec2(0, 1), 0).rgb - c - sy; e1 += d * d; }
                    if (i + 2 < sub) { d = texelFetch(InputBuffer, p + ivec2(2, 0), 0).rgb - c - 2.0 * sx; e2 += d * d; }
                    if (j + 2 < sub) { d = texelFetch(InputBuffer, p + ivec2(0, 2), 0).rgb - c - 2.0 * sy; e2 += d * d; }
                }
            }
            int k = sj * 4 + si;
            e1 /= n1;
            e2 /= n2;
            m1[k] = e1.x; m2[k] = e2.x; u1[k] = e1.y; u2[k] = e2.y; v1[k] = e1.z; v2[k] = e2.z;
        }
    }
    float norm = 1.0 / (0.945 * var);
    vec2 gY = min(vec2(median16(m1), median16(m2)) * norm, vec2(60000.0));
    vec2 gU = min(vec2(median16(u1), median16(u2)) * norm, vec2(60000.0));
    vec2 gV = min(vec2(median16(v1), median16(v2)) * norm, vec2(60000.0));
    Output = uvec4(packHalf2x16(vec2(meanY, t)), packHalf2x16(gY), packHalf2x16(gU), packHalf2x16(gV));
}
