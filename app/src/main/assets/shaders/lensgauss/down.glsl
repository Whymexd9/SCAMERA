precision highp float;
precision highp sampler2D;
// P289: the next level of the box pyramid (DiffusionGauss.down): the mean of the 2 x 2 pixels under each one, the edge clamped.
uniform sampler2D InputBuffer;
out vec4 Output;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy) * 2;
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    vec4 s = texelFetch(InputBuffer, min(p, last), 0) + texelFetch(InputBuffer, min(p + ivec2(1, 0), last), 0)
           + texelFetch(InputBuffer, min(p + ivec2(0, 1), last), 0) + texelFetch(InputBuffer, min(p + ivec2(1, 1), last), 0);
    Output = s * 0.25;
}
