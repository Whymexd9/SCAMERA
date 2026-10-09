precision highp float;
precision highp sampler2D;
// Post-network luma noise, step 1: variance-stabilised luminance u = sqrt(Y + c), so the
// noise of the model output (about a*Y + b) has the constant variance a/4 everywhere.
// Signed input (signedU 1, the SCAM Hybrid: PostPipeline.signedRgb): a pixel with a negative channel takes the luminance of
// its signed values, held at -offsetC only for the square root; a clamp per channel lifted the luminance of dark pixels and
// the non-local means averaged the lifted values (the hybrid clips once, after averaging). Pixels without a negative
// channel, and signedU 0, give the value of before bit for bit.
uniform sampler2D InputBuffer;
uniform float offsetC;
uniform int signedU;
out float Output;
void main() {
    vec3 cs = texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0).rgb;
    vec3 c = max(cs, vec3(0.0));
    const vec3 lw = vec3(0.2126, 0.7152, 0.0722);
    if (signedU != 0 && any(lessThan(cs, vec3(0.0)))) Output = sqrt(max(dot(cs, lw), -offsetC) + offsetC);
    else Output = sqrt(dot(c, lw) + offsetC);
}
