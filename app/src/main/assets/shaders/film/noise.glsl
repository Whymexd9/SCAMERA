precision highp float;
// P317: the ints are highp too. A fragment shader's ints default to mediump (GLSL ES 3.10 4.7.4), so the hash, p + 32768
// and float(h & 0xFFFFu) + 0.5, / 65536.0 below were mediump: fp16 on Mali, where 65536.0 is +inf, u = 0 and the
// Box-Muller noise +inf in every pixel - every film photo with grain came out pure white (vivo X300 Pro, Mali-G1-Ultra).
precision highp int;
// P289 (FilmGrain): three independent unit Gaussians per pixel (Box-Muller from hashes), the grain's white noise before its
// blur (film/blur.glsl) and its application (film/grain_sf.glsl).
uniform float seed;
out vec4 Output;

uint hash(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    return v.x ^ v.y ^ v.z;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    uint h1 = hash(uvec3(uvec2(p + 32768), uint(seed)));
    uint h2 = hash(uvec3(h1, 0x9E3779B9u, uint(seed) + 7u));
    uint h3 = hash(uvec3(h2, h1, 0x85EBCA6Bu));
    vec2 u1 = (vec2(float(h1 & 0xFFFFu), float(h1 >> 16u)) + 0.5) / 65536.0;
    vec2 u2 = (vec2(float(h2 & 0xFFFFu), float(h2 >> 16u)) + 0.5) / 65536.0;
    float u5 = (float(h3 & 0xFFFFu) + 0.5) / 65536.0, u6 = (float(h3 >> 16u) + 0.5) / 65536.0;
    Output = vec4(sqrt(-2.0 * log(u1.x)) * cos(6.2831853 * u1.y), sqrt(-2.0 * log(u2.x)) * cos(6.2831853 * u2.y),
                  sqrt(-2.0 * log(u5)) * cos(6.2831853 * u6), 1.0);
}
