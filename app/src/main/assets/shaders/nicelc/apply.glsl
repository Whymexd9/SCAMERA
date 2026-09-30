precision highp float;
precision highp sampler2D;
// Mid-frequency local contrast (texture): the Laplacian levels of the display luminance that hold
// structures of about 2-16 pixels are scaled up where their local amplitude is clearly above the
// noise floor of the band (flat areas keep their noise level), with less gain on strong edges (no
// halos). The change is added to all three channels (luminance only).
uniform sampler2D InputBuffer;
uniform sampler2D Lap1;
uniform sampler2D Lap2;
uniform sampler2D Lap3;
uniform sampler2D En1;
uniform sampler2D En2;
uniform sampler2D En3;
uniform vec3 gain;     // extra gain per level: (g - 1) * amount
uniform vec3 floorE;   // noise floor (amplitude) per level
uniform vec2 core;     // amplitude / floor where the gain starts / is complete
uniform float bmax;    // band value at which the gain has halved
out vec4 Output;
float bilinear(sampler2D t, vec2 pos, vec2 full) {
    ivec2 size = textureSize(t, 0);
    vec2 q = pos * vec2(size) / full - 0.5;
    ivec2 i0 = ivec2(floor(q));
    vec2 f = q - vec2(i0);
    ivec2 last = size - ivec2(1);
    float a = texelFetch(t, clamp(i0, ivec2(0), last), 0).r;
    float b = texelFetch(t, clamp(i0 + ivec2(1, 0), ivec2(0), last), 0).r;
    float c = texelFetch(t, clamp(i0 + ivec2(0, 1), ivec2(0), last), 0).r;
    float d = texelFetch(t, clamp(i0 + ivec2(1, 1), ivec2(0), last), 0).r;
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
float band(sampler2D lap, sampler2D en, float g, float fl, vec2 pos, vec2 full) {
    float b = bilinear(lap, pos, full);
    float e = bilinear(en, pos, full);
    float w = smoothstep(core.x * fl, core.y * fl, e);
    float r = abs(b) / bmax;
    return g * w * b / (1.0 + r * r);
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 full = vec2(textureSize(InputBuffer, 0));
    vec2 pos = vec2(p) + 0.5;
    vec3 c = texelFetch(InputBuffer, p, 0).rgb;
    float d = band(Lap1, En1, gain.x, floorE.x, pos, full)
            + band(Lap2, En2, gain.y, floorE.y, pos, full)
            + band(Lap3, En3, gain.z, floorE.z, pos, full);
    Output = vec4(max(c + vec3(d), vec3(0.0)), 1.0);
}
