precision highp float;
precision highp sampler2D;
// P317: highp ints (they default to mediump, fp16 on Mali): float(h & 0xFFFFu) / 65535.0 was mediump and 65535.0 is +inf
// in fp16 (the B/W stocks' grain a constant, a flat brightening), and vec2(ixy) + 0.5 rounded the pixel position above
// 2048 (as film/noise.glsl).
precision highp int;
// P281: the film simulation's grain (FilmGrain) on the finished picture (display encoded). Per layer a field of grain clumps:
// Gaussian values on a lattice of the grain's size (in pixels of the frame) bilinearly blended with a smooth step, plus an
// octave of half that size; the layers' fields partly shared (a colour film's grain is mostly in the brightness), the blue
// layer's coarser. The grain modulates the light as a density: y' = y 10^(-k n), k = the density sigma of a pixel of the
// frame (granularity by Selwyn's law at the film's pixel size) times the tonal weight - for a print most in the mid tones,
// for a slide in the shadows.
uniform sampler2D InputBuffer;
uniform vec3 sigma;         // density sigma per layer (r, g, b) at mid grey
uniform vec3 cell;          // grain size per layer, pixels
uniform float sharedPart;      // 0..1 the common part of the three layers
uniform int slide;          // 1: projected film (grain most in the shadows)
uniform int mono;           // 1: one silver layer
uniform float seed;
out vec4 Output;

uint hash(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    return v.x ^ v.y ^ v.z;
}

float gauss(ivec2 p, int layer) {
    uint h1 = hash(uvec3(uvec2(p + 32768), uint(layer) * 977u + uint(seed)));
    uint h2 = hash(uvec3(h1, uint(layer) + 17u, 0x9E3779B9u));
    uint h3 = hash(uvec3(h2, h1, 0x85EBCA6Bu));
    // three uniforms: mean 1.5, variance 0.25 -> unit variance
    float u = float(h1 & 0xFFFFu) / 65535.0 + float(h2 & 0xFFFFu) / 65535.0 + float(h3 & 0xFFFFu) / 65535.0;
    return (u - 1.5) * 2.0;
}

float field(vec2 xy, float size, int layer) {
    vec2 q = xy / max(size, 1.0);
    vec2 i = floor(q);
    vec2 f = q - i;
    f = f * f * (3.0 - 2.0 * f);
    ivec2 c = ivec2(i);
    float a = gauss(c, layer), b = gauss(c + ivec2(1, 0), layer), d = gauss(c + ivec2(0, 1), layer), e = gauss(c + ivec2(1, 1), layer);
    // a smooth blend of four unit values keeps about 0.53 of the variance on average
    return mix(mix(a, b, f.x), mix(d, e, f.x), f.y) * 1.37;
}

float grain(vec2 xy, float size, int layer) {
    float n = field(xy, size, layer) + 0.5 * field(xy + 0.5 * size, 0.5 * size, layer + 8);
    return n * 0.894;
}

vec3 decode(vec3 v) {
    return mix(v / 12.92, pow((v + 0.055) / 1.055, vec3(2.4)), step(0.04045, v));
}

vec3 encode(vec3 y) {
    y = clamp(y, 0.0, 1.0);
    return mix(12.92 * y, 1.055 * pow(y, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, y));
}

void main() {
    ivec2 ixy = ivec2(gl_FragCoord.xy);
    vec2 xy = vec2(ixy) + 0.5;
    vec4 v = texelFetch(InputBuffer, ixy, 0);
    vec3 y = decode(clamp(v.rgb, 0.0, 1.0));
    float lum = max(dot(y, vec3(0.2126, 0.7152, 0.0722)), 1e-4);
    float stops = log2(lum / 0.18);
    float w = slide == 1 ? 0.25 + 0.75 * exp(-pow((log2(lum / 0.04)) / 2.5, 2.0))
                         : 0.35 + 0.65 * exp(-pow(stops / 2.2, 2.0));
    vec3 n;
    if (mono == 1) {
        float m = grain(xy, cell.g, 1);
        n = vec3(m);
    } else {
        float together = grain(xy, cell.g, 3);
        vec3 own = vec3(grain(xy, cell.r, 0), grain(xy, cell.g, 1), grain(xy, cell.b, 2));
        n = sqrt(sharedPart) * together + sqrt(1.0 - sharedPart) * own;
    }
    vec3 k = sigma * w;
    vec3 outY = y * pow(vec3(10.0), -k * n);
    Output = vec4(encode(outY), v.a);
}
