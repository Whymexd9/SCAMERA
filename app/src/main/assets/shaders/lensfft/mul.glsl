precision highp float;
precision highp int;
// P288: every plane's spectrum times its channel's kernel spectrum (plane z = tile * 3 + channel), scaled by 1 / grid^2 (the
// inverse FFT's normalisation).
#define LAYOUT //
LAYOUT

layout(std430, binding = 1) buffer Planes { vec2 planes[]; };
layout(std430, binding = 2) readonly buffer Kernel { vec2 kernel_[]; };
uniform int gridU;

void main() {
    int g = gridU;
    ivec3 id = ivec3(gl_GlobalInvocationID);
    if (id.x >= g || id.y >= g) return;
    int i = (id.z * g + id.y) * g + id.x;
    vec2 a = planes[i], k = kernel_[((id.z % 3) * g + id.y) * g + id.x];
    planes[i] = vec2(a.x * k.x - a.y * k.y, a.x * k.y + a.y * k.x) / float(g * g);
}
