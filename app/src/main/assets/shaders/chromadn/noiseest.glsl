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
