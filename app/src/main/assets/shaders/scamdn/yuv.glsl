precision highp float;
precision highp int;
precision highp sampler2D;
// SCAM noise reduction, level 0: the white-balanced linear camera RGB in the YUV of GCam's finish (common.cl):
// Y = Rec.709 luminance, U and V = BT.601-like colour differences divided by 0.615. Same pixel grid as the input.
// A non-finite input sample is zeroed here: every pyramid level would spread it over its whole footprint (a NaN pixel
// would blank a block of ~100 px instead of one pixel).
uniform sampler2D InputBuffer;
out vec4 Output;
float finite1(float v) { return (isnan(v) || isinf(v)) ? 0.0 : v; }
void main() {
    vec3 c = texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0).rgb;
    c = vec3(finite1(c.r), finite1(c.g), finite1(c.b));
    Output = vec4(dot(c, vec3(0.2126, 0.7152, 0.0721996)),
                  dot(c, vec3(-0.162450244, -0.546494309, 0.708944715)),
                  dot(c, vec3(0.999996748, -0.908302439, -0.091693333)), 1.0);
}
