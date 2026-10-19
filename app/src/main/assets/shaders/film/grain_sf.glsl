precision highp float;
precision highp sampler2D;
precision highp sampler3D;
// P286 / P289: the reference model's grain (FilmGrain) on the finished picture (display encoded): per film layer an independent
// Gaussian density noise (the particle model's variance, FilmSpectral.grainSigma) blurred by the reference app's 11.8 um
// (film/noise.glsl, film/blur.glsl: Noise), seen through the print and the scan: out += GrainLut(out) / pixel size x noise.
// GrainLut holds |d out / d density| x the density sigma of a 1 um pixel.
uniform sampler2D InputBuffer;
uniform sampler2D Noise;
uniform sampler3D GrainLut;
uniform float scale;        // the setting x strength / the film's pixel size (um)
uniform float lutSize;      // N: texel centres c (N - 1) / N + 0.5 / N
out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec4 v = texelFetch(InputBuffer, xy, 0);
    vec3 n = texelFetch(Noise, xy, 0).rgb;
    vec3 c = clamp(v.rgb, 0.0, 1.0);
    vec3 g = texture(GrainLut, c * ((lutSize - 1.0) / lutSize) + 0.5 / lutSize).rgb;
    Output = vec4(clamp(c + g * scale * n, 0.0, 1.0), v.a);
}
