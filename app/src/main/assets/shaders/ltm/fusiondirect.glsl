precision highp float;
precision highp sampler2D;

uniform sampler2D PackedExposure;
out float result;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 e = texelFetch(PackedExposure, p, 0);
    float t = clamp(e.a, 0.0, 1.0);
    float s1 = clamp(t * 2.0, 0.0, 1.0);
    float s2 = clamp(t * 2.0 - 1.0, 0.0, 1.0);
    // r = highlight/base, b = middle, g = shadow exposure.
    result = e.r * s2 + e.b * (s1 - s2) + e.g * (1.0 - s1);
}
