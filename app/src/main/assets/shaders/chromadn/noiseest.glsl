precision highp float;
precision highp sampler2D;
// Noise level of u: per block (8x8 sensor px), the mean absolute difference between each pixel and
// the mean of its eight neighbours (about 0.85 * sigma * sqrt(1.125) for white noise). On the Sabre
// 2x grid adjacent output pixels are kernel averages of the same donor samples, so the neighbours
// are taken one SENSOR pixel apart (pxStep = outputScale) and the block covers 8 x 8 sensor pixels;
// at pxStep 1 this is the plain 8x8 block / 3x3 neighbourhood statistic.
uniform sampler2D InputBuffer;
uniform int blockU;  // 8 * outputScale: block side in output pixels
uniform int pxStepU;   // outputScale: neighbour distance in output pixels (= one sensor pixel)
out vec4 Output;
void main() {
    int block = blockU > 0 ? blockU : 8; // unset uniforms (0) = the 1x statistic
    int pxStep = max(pxStepU, 1);
    ivec2 origin = ivec2(gl_FragCoord.xy) * block;
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1 + pxStep);
    float sum = 0.0;
    if (block == 8 && pxStep == 1 && origin.x >= 1 && origin.y >= 1 && origin.x + 8 <= last.x && origin.y + 8 <= last.y) {
        // P364: an inner block at the 1x step - three rows of its 10 x 10 window held (each pixel was fetched 9 times); the same
        // neighbours, the same sums in the same order
        float r0[10], r1[10], r2[10];
        for (int i = 0; i < 10; i++) {
            r0[i] = texelFetch(InputBuffer, origin + ivec2(i - 1, -1), 0).r;
            r1[i] = texelFetch(InputBuffer, origin + ivec2(i - 1, 0), 0).r;
        }
        for (int j = 0; j < 8; j++) {
            for (int i = 0; i < 10; i++) r2[i] = texelFetch(InputBuffer, origin + ivec2(i - 1, j + 1), 0).r;
            for (int i = 0; i < 8; i++) {
                float m = 0.0;
                m += r0[i]; m += r0[i + 1]; m += r0[i + 2];
                m += r1[i]; m += r1[i + 2];
                m += r2[i]; m += r2[i + 1]; m += r2[i + 2];
                sum += abs(r1[i + 1] - m * 0.125);
            }
            for (int i = 0; i < 10; i++) { r0[i] = r1[i]; r1[i] = r2[i]; }
        }
        Output = vec4(sum / 64.0, 0.0, 0.0, 1.0);
        return;
    }
    for (int j = 0; j < block; j++) {
        for (int i = 0; i < block; i++) {
            ivec2 p = clamp(origin + ivec2(i, j), ivec2(pxStep), last);
            float m = 0.0;
            for (int k = 0; k < 9; k++) {
                if (k == 4) continue;
                m += texelFetch(InputBuffer, p + ivec2(k % 3 - 1, k / 3 - 1) * pxStep, 0).r;
            }
            sum += abs(texelFetch(InputBuffer, p, 0).r - m * 0.125);
        }
    }
    Output = vec4(sum / float(block * block), 0.0, 0.0, 1.0);
}
