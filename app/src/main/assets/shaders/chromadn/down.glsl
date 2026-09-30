precision highp float;
precision highp sampler2D;
// Colour noise removal, step 1: 2x2 average of the white-balanced linear RGB.
uniform sampler2D InputBuffer;
out vec4 Output;
void main() {
    ivec2 origin = ivec2(gl_FragCoord.xy) * 2;
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    vec3 sum = vec3(0.0);
    for (int j = 0; j < 2; j++)
        for (int i = 0; i < 2; i++)
            sum += max(texelFetch(InputBuffer, min(origin + ivec2(i, j), last), 0).rgb, vec3(0.0));
    Output = vec4(sum * 0.25, 1.0);
}
