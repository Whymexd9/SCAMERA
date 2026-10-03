precision highp float;
precision highp int;
precision highp sampler2D;
// Chroma pyramid of GCam's ChromaDenoise: X[L+1](q) = sum_ij h[i] h[j] X[L](clamp(2q + (i-1, j-1))),
// h = {1,3,3,1}/8, all three channels (Y,U,V). The pairs (1,3) and (3,1) are bilinear fetches at 2q-0.25 and
// 2q+1.25 (exact quarter fractions), half the weight each.
uniform sampler2D InputBuffer;   // YUV of level L
out vec4 Output;
void main() {
    highp ivec2 q = ivec2(gl_FragCoord.xy);          // highp int: vec2(mediump int) would be fp16 on Adreno (lmcdn/down2x)
    highp vec2 inv = 1.0 / vec2(textureSize(InputBuffer, 0));
    highp vec2 base = vec2(q * 2) + 0.5;
    vec3 s = texture(InputBuffer, (base + vec2(-0.25, -0.25)) * inv).rgb
           + texture(InputBuffer, (base + vec2(1.25, -0.25)) * inv).rgb
           + texture(InputBuffer, (base + vec2(-0.25, 1.25)) * inv).rgb
           + texture(InputBuffer, (base + vec2(1.25, 1.25)) * inv).rgb;
    Output = vec4(0.25 * s, 1.0);
}
