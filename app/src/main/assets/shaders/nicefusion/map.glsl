precision highp float;
precision highp sampler2D;
// NICE exposure fusion, step 3: fused lightness as a linear gain over the
// normal exposure, consumed by HeadroomRender's FUSION path (clamped 0..3).
uniform sampler2D InputBuffer; // reduced linear RGB, same as the expose step
uniform sampler2D Fused;       // fused lightness, display gamma
uniform float exposure;
uniform float strength;        // 0 = no fusion, 1 = full
uniform vec2 invSize;
uniform float liftOnly;        // 1 = never darken below the normal exposure outside real highlights
out vec4 result;
void main() {
    vec3 c = max(texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0).rgb, vec3(0.0));
    float lightness = max(dot(c, vec3(1.0 / 3.0)) * exposure, 1e-5);
    float fused = pow(max(texture(Fused, gl_FragCoord.xy * invSize).r, 0.0), 2.2);
    float gain = mix(1.0, fused / lightness, strength);
    // Highlights are already compressed by HeadroomRender's shoulder. A fused
    // gain below 1 spreads through the pyramid from a light source onto the
    // surrounding wall as a dark halo, so in lift-only mode the map never goes
    // below 1. The floor is a constant: a lightness-dependent floor drew a
    // contour where the wall crossed its threshold.
    gain = mix(gain, max(gain, 1.0), liftOnly);
    result = vec4(clamp(gain, 0.0, 3.0));
}
