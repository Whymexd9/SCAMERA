precision highp float;
precision highp int;
precision highp sampler2D;
// LMC luma noise reduction, level 1 -> level 0 hand-over (GCam luma_denoise): the low pass LF = [1 2 1]x[1 2 1]/16
// of the denoised level 1 is kept aside; level 0 reconstructs from delta = Den1 - Y1 - LF and adds Up2(LF) back
// after its own filtering. Output RG: (delta, LF).
uniform sampler2D InputBuffer;  // Den of level 1 (.r)
uniform sampler2D Base;         // Y of level 1 (.r)
out vec4 Output;
void main() {
    ivec2 q = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(InputBuffer, 0) - 1;
    float lf = 0.0;
    for (int j = -1; j <= 1; j++)
        for (int i = -1; i <= 1; i++)
            lf += (i == 0 ? 0.5 : 0.25) * (j == 0 ? 0.5 : 0.25) * texelFetch(InputBuffer, clamp(q + ivec2(i, j), ivec2(0), last), 0).r;
    Output = vec4(texelFetch(InputBuffer, q, 0).r - texelFetch(Base, q, 0).r - lf, lf, 0.0, 1.0);
}
