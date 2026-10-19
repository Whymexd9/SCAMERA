precision highp float;
precision highp sampler2D;
// P281: the film's own sharpness (FilmGrain): a separable Gaussian of the film's MTF (sigma in pixels of the frame), along
// one axis per pass; taps spaced to cover three sigma with at most 25 of them.
uniform sampler2D InputBuffer;
uniform vec2 dir;       // (1, 0) or (0, 1)
uniform float sigma;    // pixels
out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    float radius = ceil(3.0 * sigma);
    float stepPx = max(1.0, radius / 12.0);
    vec4 sum = vec4(0.0);
    float wsum = 0.0;
    for (int i = -12; i <= 12; i++) {
        float d = float(i) * stepPx;
        if (abs(d) > radius + 0.5) continue;
        float w = exp(-0.5 * d * d / (sigma * sigma));
        ivec2 p = clamp(xy + ivec2(round(dir * d)), ivec2(0), last);
        sum += w * texelFetch(InputBuffer, p, 0);
        wsum += w;
    }
    Output = sum / wsum;
}
