#version 300 es
// Straight copy of a raw texture, used to write a reconstruction back into the
// texture it was read from. A shader cannot read and write the same texture, so
// the remosaic assembles into its own target and this puts it back where the
// merge expects the frame to be, leaving every call site that uploads a frame
// unchanged.
precision highp float;
precision highp int;
precision highp usampler2D;

uniform usampler2D RawBuffer;

out uvec4 Output;

void main() {
    Output = uvec4(texelFetch(RawBuffer, ivec2(gl_FragCoord.xy), 0).r, 0u, 0u, 0u);
}
