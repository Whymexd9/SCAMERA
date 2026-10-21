precision highp float;
precision highp int;
precision highp sampler2D;
// P145: the largest encoded byte of the GPU gain map (R of the RGBA8 output of ultrahdr/gainmap) - the first pass of
// GainMapComputer.compute (the range) - as a compute pass: a maximum per 16x16 group in shared memory, one atomicMax of
// each group into gainMax[0]. P317: memoryBarrierShared() with every barrier() (ESSL 3.10 8.14, as ark/aestats.glsl).
#define LAYOUT //
LAYOUT

uniform sampler2D InputBuffer;
uniform ivec2 sizeU;
layout(std430, binding = 1) buffer gainMax { uint maxByte[]; };
shared uint localMax;

void main() {
    if (gl_LocalInvocationIndex == 0u) localMax = 0u;
    memoryBarrierShared(); barrier();
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x < sizeU.x && p.y < sizeU.y) atomicMax(localMax, uint(texelFetch(InputBuffer, p, 0).r * 255.0 + 0.5));
    memoryBarrierShared(); barrier();
    if (gl_LocalInvocationIndex == 0u) atomicMax(maxByte[0], localMax);
}
