precision highp float;
precision highp sampler2D;
// Highlight recovery, step 3 (DaVinci Resolve style colour propagation): a pixel whose
// channels all clipped at the sensor white keeps only its luminance; its colour is taken
// from the surrounding trustworthy pixels (nearby first, then the wider neighbourhood,
// neutral when none), so a blown window takes the colour of its surroundings' light
// instead of the magenta that white balance leaves on clipped data.
uniform sampler2D InputBuffer;
uniform sampler2D Chroma8;
uniform sampler2D Chroma32;
uniform float kFull;
uniform float yRef;
uniform float strength;
out vec3 Output;
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InputBuffer, 0);
    vec3 c = max(texelFetch(InputBuffer, p, 0).rgb, vec3(0.0));
    vec3 mean = vec3(0.0);
    for (int j = -1; j <= 1; j++)
        for (int i = -1; i <= 1; i++)
            mean += max(texelFetch(InputBuffer, clamp(p + ivec2(i, j), ivec2(0), size - ivec2(1)), 0).rgb, vec3(0.0));
    mean *= 1.0 / 9.0;
    float tint = min(mean.r, mean.b) / max(mean.g, 1.0e-6);
    float clipped = smoothstep(1.0 + 0.45 * (kFull - 1.0), 1.0 + 0.8 * (kFull - 1.0), tint)
            * smoothstep(0.30, 0.55, luma(mean) / max(yRef, 1.0e-6));
    if (clipped <= 0.0) { Output = c; return; }
    vec2 uv = (vec2(p) + 0.5) / vec2(size);
    vec4 near = texture(Chroma8, uv);
    vec4 wide = texture(Chroma32, uv);
    vec3 qNear = near.rgb / max(near.a, 1.0e-4);
    vec3 qWide = wide.rgb / max(wide.a, 1.0e-4);
    float tNear = smoothstep(0.02, 0.12, near.a);
    float tWide = smoothstep(0.002, 0.03, wide.a);
    vec3 q = mix(vec3(1.0), qWide, tWide);
    q = mix(q, qNear, tNear);
    q /= max(luma(q), 1.0e-4);
    Output = mix(c, luma(c) * q, clipped * strength);
}
