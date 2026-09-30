precision highp float;
precision highp sampler2D;
// Highlight recovery, step 2: 4x4 average of the block sums (a wider colour neighbourhood).
uniform sampler2D InputBuffer;
out vec4 Output;
void main() {
    ivec2 origin = ivec2(gl_FragCoord.xy) * 4;
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    vec4 sum = vec4(0.0);
    for (int j = 0; j < 4; j++)
        for (int i = 0; i < 4; i++)
            sum += texelFetch(InputBuffer, min(origin + ivec2(i, j), last), 0);
    Output = sum * (1.0 / 16.0);
}
