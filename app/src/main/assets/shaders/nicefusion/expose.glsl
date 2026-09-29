precision highp float;
precision highp sampler2D;
// NICE exposure fusion, step 1: two synthetic exposures of the linear HDR
// frame in a display-like gamma, with Mertens well-exposedness weights.
// rgba = (dark exposure, bright exposure, dark weight, bright weight).
uniform sampler2D InputBuffer; // reduced linear RGB, white balance applied
uniform float exposure;        // rendered (normal) exposure: display gain
uniform float darkEv;          // stops below normal: highlight recovery
uniform float brightEv;        // stops above normal: shadow lift
uniform float sigma;           // well-exposedness width
out vec4 result;
float encode(float x) { return pow(clamp(x, 0.0, 1.0), 1.0 / 2.2); }
float well(float v) { float d = v - 0.5; return exp(-d * d / (2.0 * sigma * sigma)) + 1e-4; }
void main() {
    vec3 c = max(texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0).rgb, vec3(0.0));
    float lightness = dot(c, vec3(1.0 / 3.0)) * exposure;
    float dark = encode(lightness * exp2(-darkEv));
    float bright = encode(lightness * exp2(brightEv));
    result = vec4(dark, bright, well(dark), well(bright));
}
