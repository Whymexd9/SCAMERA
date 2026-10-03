precision highp float;
precision highp int;
precision highp sampler2D;
// Luma pyramid of GCam's LumaDenoise (9.6): Y[L+1](q) = sum_ij kD8[i] kD8[j] Y[L](clamp(2q + (i-3, j-3))),
// kD8 = [-3,-7,17,57,57,17,-7,-3]/128, edge clamp. Each equal-sign pair of taps is one bilinear fetch of the
// linearly filtered level (4 x 4 fetches); positions = integer index + constant fraction, scaled in highp.
uniform sampler2D InputBuffer;   // Y of level L in .r (level 0: the YUV texture)
out float Output;
void main() {
    highp ivec2 q = ivec2(gl_FragCoord.xy);          // highp int: vec2(mediump int) would be fp16 on Adreno (lmcdn/down2x)
    highp vec2 inv = 1.0 / vec2(textureSize(InputBuffer, 0));
    highp vec2 base = vec2(q * 2) + 0.5;
    const vec4 w = vec4(-10.0, 74.0, 74.0, -10.0) / 128.0;
    const vec4 o = vec4(-3.0 + 0.7, -1.0 + 57.0 / 74.0, 1.0 + 17.0 / 74.0, 3.0 + 0.3);
    float y = 0.0;
    for (int j = 0; j < 4; j++) {
        float r = 0.0;
        for (int i = 0; i < 4; i++) r += w[i] * texture(InputBuffer, (base + vec2(o[i], o[j])) * inv).r;
        y += w[j] * r;
    }
    Output = y;
}
