precision highp float;
precision highp int;
// P288: one radix-2 Stockham pass of the FFT along the rows (or the columns) of every plane (DiffusionFft.fft: natural order
// after log2(grid) passes, ns = 1, 2, 4 ...; sign -1 forward, +1 inverse, unscaled).
#define LAYOUT //
LAYOUT

layout(std430, binding = 1) readonly buffer Src { vec2 src[]; };
layout(std430, binding = 2) writeonly buffer Dst { vec2 dst[]; };
uniform ivec4 fftU;         // x grid, y ns, z 0 rows / 1 columns, w sign

int at(int plane, int line, int e, int g) {
    return fftU.z == 0 ? (plane * g + line) * g + e : (plane * g + e) * g + line;
}

void main() {
    int g = fftU.x, ns = fftU.y, half_ = g / 2;
    int j = int(gl_GlobalInvocationID.x), line = int(gl_GlobalInvocationID.y), plane = int(gl_GlobalInvocationID.z);
    if (j >= half_ || line >= g) return;
    int k = j % ns;
    float ang = float(fftU.w) * 6.283185307179586 * float(k) / float(2 * ns);
    vec2 w = vec2(cos(ang), sin(ang));
    vec2 a = src[at(plane, line, j, g)], b = src[at(plane, line, j + half_, g)];
    b = vec2(b.x * w.x - b.y * w.y, b.x * w.y + b.y * w.x);
    int d = (j / ns) * ns * 2 + k;
    dst[at(plane, line, d, g)] = a + b;
    dst[at(plane, line, d + ns, g)] = a - b;
}
