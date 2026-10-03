precision highp float;
precision highp sampler2D;
// Post-network luma noise, coarse step 1: factor x factor average of u
// (factor = 4 * outputScale: the blotch stage runs at a quarter of the SENSOR resolution on any grid).
uniform sampler2D InputBuffer;
uniform int factorU;
out float Output;
void main() {
    int factor = factorU > 0 ? factorU : 4; // unset uniform (0) = the 1x reduction
    ivec2 origin = ivec2(gl_FragCoord.xy) * factor;
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    float sum = 0.0;
    for (int j = 0; j < factor; j++)
        for (int i = 0; i < factor; i++)
            sum += texelFetch(InputBuffer, min(origin + ivec2(i, j), last), 0).r;
    Output = sum / float(factor * factor);
}
