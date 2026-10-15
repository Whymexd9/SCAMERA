precision highp float;
precision highp sampler2D;
// P145: the second pass of GainMapComputer.compute on the GPU - every encoded byte of the gain map (R of ultrahdr/gainmap's
// RGBA8 output) to the byte of the map whose range is [0, GainMapMax]. The table is GainMapComputer.requantisation (the
// Java arithmetic, 256 bytes as vec4 lutU[64]); a byte k / 255 written to the RGBA8 target is k again.
uniform sampler2D InputBuffer;
uniform vec4 lutU[64];
out vec4 Output;

void main() {
    int k = int(texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0).r * 255.0 + 0.5);
    float q = lutU[k >> 2][k & 3] / 255.0;
    Output = vec4(q, q, q, 1.0);
}
