precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform float exposureEv;
#define LINEARINPUT 0
out vec3 Output;

// sRGB / Display-P3 transfer; common scalar gain preserves linear RGB ratios.
vec3 decodeDisplay(vec3 v) {
    return mix(pow((v + 0.055) / 1.055, vec3(2.4)), v / 12.92,
            lessThanEqual(v, vec3(0.04045)));
}
vec3 encodeDisplay(vec3 v) {
    return mix(1.055 * pow(v, vec3(1.0 / 2.4)) - 0.055, 12.92 * v,
            lessThanEqual(v, vec3(0.0031308)));
}
void main() {
    vec3 rgb = texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0).rgb;
    if (exposureEv == 0.0) { Output = rgb; return; }
    rgb = clamp(rgb, 0.0, 1.0);
    #if LINEARINPUT == 0
    rgb = decodeDisplay(rgb);
    #endif
    float gain = exp2(clamp(exposureEv, -2.0, 2.0));
    // Positive EV approaches 2^EV in shadows and preserves display white.
    // One shared denominator avoids per-channel clipping and hue changes.
    float peak = max(rgb.r, max(rgb.g, rgb.b));
    rgb *= gain / (1.0 + max(gain - 1.0, 0.0) * peak);
    #if LINEARINPUT == 0
    rgb = encodeDisplay(rgb);
    #endif
    Output = clamp(rgb, 0.0, 1.0);
}
