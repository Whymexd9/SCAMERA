precision highp float;
precision highp sampler2D;
// Display-encoded luminance of the tone-mapped image (the local contrast works on this plane).
uniform sampler2D InputBuffer;
out float Output;
void main() {
    vec3 c = texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0).rgb;
    Output = dot(c, vec3(0.299, 0.587, 0.114));
}
