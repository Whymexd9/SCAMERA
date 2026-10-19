precision highp float;
precision highp sampler2D;
precision highp sampler3D;
// P286: the reference model's grain (FilmGrain) on the finished picture (display encoded): per film layer an independent
// Gaussian density noise per pixel (the particle model's variance, FilmSpectral.grainSigma), blurred by the reference's
// 0.65 px Gaussian (GrainParams.blur; one pass over a 5 x 5 window of the hashed noise), seen through the print and the scan:
// out += GrainLut(out) / pixel size x noise. GrainLut holds |d out / d density| x the density sigma of a 1 um pixel.
uniform sampler2D InputBuffer;
uniform sampler3D GrainLut;
uniform float scale;        // the setting x strength / the film's pixel size (um)
uniform float blurSigma;    // pixels
uniform float seed;
uniform float lutSize;     // N: texel centres c (N - 1) / N + 0.5 / N
out vec4 Output;

uint hash(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    return v.x ^ v.y ^ v.z;
}

// three unit Gaussians (Box-Muller from two hashes)
vec3 white(ivec2 p) {
    uint h1 = hash(uvec3(uvec2(p + 32768), uint(seed)));
    uint h2 = hash(uvec3(h1, 0x9E3779B9u, uint(seed) + 7u));
    uint h3 = hash(uvec3(h2, h1, 0x85EBCA6Bu));
    vec2 u1 = (vec2(float(h1 & 0xFFFFu), float(h1 >> 16u)) + 0.5) / 65536.0;
    vec2 u2 = (vec2(float(h2 & 0xFFFFu), float(h2 >> 16u)) + 0.5) / 65536.0;
    float u5 = (float(h3 & 0xFFFFu) + 0.5) / 65536.0, u6 = (float(h3 >> 16u) + 0.5) / 65536.0;
    float r1 = sqrt(-2.0 * log(u1.x)), r2 = sqrt(-2.0 * log(u2.x));
    float r3 = sqrt(-2.0 * log(u5));
    return vec3(r1 * cos(6.2831853 * u1.y), r2 * cos(6.2831853 * u2.y), r3 * cos(6.2831853 * u6));
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec4 v = texelFetch(InputBuffer, xy, 0);
    vec3 n = vec3(0.0);
    float wsum = 0.0;
    float k = -0.5 / max(blurSigma * blurSigma, 1e-4);
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            float w = exp(k * float(i * i + j * j));
            n += w * white(xy + ivec2(i, j));
            wsum += w;
        }
    }
    // the blurred white noise as the reference's blurred density noise: the blur's own variance loss kept (a sum of weights w/sum)
    n /= wsum;
    vec3 c = clamp(v.rgb, 0.0, 1.0);
    vec3 g = texture(GrainLut, c * ((lutSize - 1.0) / lutSize) + 0.5 / lutSize).rgb;
    Output = vec4(clamp(c + g * scale * n, 0.0, 1.0), v.a);
}
