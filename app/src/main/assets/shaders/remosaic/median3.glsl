#version 300 es
// 3x3 median of the interpolated colour differences.
//
// The reference applies this to diff_B and diff_R after interpolation. The
// differences are nearly flat over real surfaces, so a median removes the
// isolated outliers left where a block edge met an image edge without touching
// anything else. Applying it to the channels themselves would cost detail; on
// the differences it costs nothing.
precision highp float;

uniform sampler2D InputBuffer;
uniform ivec2 size;
out vec4 Output;

void sort2(inout float a, inout float b) {
    float t = min(a, b);
    b = max(a, b);
    a = t;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    float v[9];
    int n = 0;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            ivec2 p = clamp(xy + ivec2(i, j), ivec2(0), size - 1);
            v[n++] = texelFetch(InputBuffer, p, 0).x;
        }
    }
    // Partial sorting network: enough passes to settle the middle element,
    // which is all a median needs.
    for (int i = 0; i < 5; i++) {
        for (int j = i + 1; j < 9; j++) sort2(v[i], v[j]);
    }
    Output = vec4(v[4], 0.0, 0.0, 1.0);
}
