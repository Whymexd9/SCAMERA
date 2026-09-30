precision highp float;
precision highp sampler2D;
// Post-network luma noise, step 1: variance-stabilised luminance u = sqrt(Y + c), so the
// noise of the model output (about a*Y + b) has the constant variance a/4 everywhere.
uniform sampler2D InputBuffer;
uniform float offsetC;
out float Output;
void main() {
    vec3 c = max(texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0).rgb, vec3(0.0));
    Output = sqrt(dot(c, vec3(0.2126, 0.7152, 0.0722)) + offsetC);
}
