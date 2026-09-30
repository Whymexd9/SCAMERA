precision highp float;
precision highp sampler2D;
// Noise level of u: per 8x8 block, the mean absolute difference between each pixel and the mean
// of its eight neighbours (about 0.85 * sigma * sqrt(1.125) for white noise).
uniform sampler2D InputBuffer;
out vec4 Output;
void main() {
    ivec2 origin = ivec2(gl_FragCoord.xy) * 8;
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(2);
    float sum = 0.0;
    for (int j = 0; j < 8; j++) {
        for (int i = 0; i < 8; i++) {
            ivec2 p = clamp(origin + ivec2(i, j), ivec2(1), last);
            float m = 0.0;
            for (int k = 0; k < 9; k++) {
                if (k == 4) continue;
                m += texelFetch(InputBuffer, p + ivec2(k % 3 - 1, k / 3 - 1), 0).r;
            }
            sum += abs(texelFetch(InputBuffer, p, 0).r - m * 0.125);
        }
    }
    Output = vec4(sum * (1.0 / 64.0), 0.0, 0.0, 1.0);
}
