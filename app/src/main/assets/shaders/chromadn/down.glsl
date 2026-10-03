precision highp float;
precision highp sampler2D;
// Colour noise removal, step 1: factor x factor average of the white-balanced linear RGB
// (factor = 2 * outputScale: the colour stage runs at half the SENSOR resolution on any grid).
uniform sampler2D InputBuffer;
uniform int factor;
out vec4 Output;
void main() {
    ivec2 origin = ivec2(gl_FragCoord.xy) * factor;
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    vec3 sum = vec3(0.0);
    for (int j = 0; j < factor; j++)
        for (int i = 0; i < factor; i++)
            sum += max(texelFetch(InputBuffer, min(origin + ivec2(i, j), last), 0).rgb, vec3(0.0));
    Output = vec4(sum / float(factor * factor), 1.0);
}
