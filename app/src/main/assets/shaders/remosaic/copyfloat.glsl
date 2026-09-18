#version 300 es
precision highp float;
uniform sampler2D InputBuffer;
out vec4 Output;
void main() {
    Output = texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0);
}
