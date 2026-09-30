precision highp float;
precision highp sampler2D;
// Post-network luma noise, coarse step 1: 4x4 average of u.
uniform sampler2D InputBuffer;
out float Output;
void main() {
    ivec2 origin = ivec2(gl_FragCoord.xy) * 4;
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    float sum = 0.0;
    for (int j = 0; j < 4; j++)
        for (int i = 0; i < 4; i++)
            sum += texelFetch(InputBuffer, min(origin + ivec2(i, j), last), 0).r;
    Output = sum * (1.0 / 16.0);
}
