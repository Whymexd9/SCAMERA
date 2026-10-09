precision highp float;
precision highp sampler2D;
// Block means of a level (4x4 taps per block) written to a small RGBA texture for CPU read-back:
// the noise floor of each band is a low percentile of its local amplitude.
uniform sampler2D InputBuffer;
out vec4 Output;
void main() {
    ivec2 size = textureSize(InputBuffer, 0);
    vec2 outSize = vec2(96.0, 72.0);
    vec2 p = floor(gl_FragCoord.xy);
    vec2 lo = p / outSize * vec2(size);
    vec2 hi = (p + vec2(1.0)) / outSize * vec2(size);
    float sum = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 q = mix(lo, hi, (vec2(float(i), float(j)) + 0.5) / 4.0);
            sum += texelFetch(InputBuffer, clamp(ivec2(q), ivec2(0), size - ivec2(1)), 0).r;
        }
    }
    Output = vec4(sum / 16.0, 0.0, 0.0, 1.0);
}
