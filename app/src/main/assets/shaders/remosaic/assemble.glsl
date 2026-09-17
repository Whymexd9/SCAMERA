#version 300 es
// Final assembly of the remosaic, written to the R16UI texture the rest of the
// pipeline reads as raw.
//
// Separate from stages.glsl because the target is an integer format: a shader
// declaring `out vec4` against R16UI does not write what it looks like it
// writes, which came out as a uniformly white frame even though the masks were
// correct (green coverage measured exactly 0.5).
precision highp float;
precision highp int;
precision highp usampler2D;

uniform usampler2D RawBuffer;
uniform sampler2D GreenBuffer;
uniform sampler2D DiffBBuffer;
uniform sampler2D DiffRBuffer;

uniform int blockSize;
uniform ivec2 phase;
uniform ivec4 quadColors;
uniform float blackLevel;
uniform float whiteLevel;
uniform float gainB;
uniform float gainR;

out uvec4 Output;

int colorAt(ivec2 xy) {
    int period = blockSize * 2;
    int ry = (xy.y + phase.y) % period;
    int rx = (xy.x + phase.x) % period;
    int idx = (ry < blockSize)
            ? ((rx < blockSize) ? 0 : 1)
            : ((rx < blockSize) ? 2 : 3);
    return (idx == 0) ? quadColors.x : (idx == 1) ? quadColors.y
         : (idx == 2) ? quadColors.z : quadColors.w;
}

int targetColorAt(ivec2 xy) {
    int idx = (xy.y % 2) * 2 + (xy.x % 2);
    return (idx == 0) ? quadColors.x : (idx == 1) ? quadColors.y
         : (idx == 2) ? quadColors.z : quadColors.w;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    float range = max(whiteLevel - blackLevel, 1.0);

    int tc = targetColorAt(xy);
    float g = texelFetch(GreenBuffer, xy, 0).x;
    float outv;

    if (tc == 1) {
        // Every green site takes the interpolated field, including the ones
        // that were measured. Passing the measured value through looks like a
        // free gain in sharpness, but it puts two different green estimates in
        // the same 2x2: the measured one, and the smoothed one that R and B
        // inherit through g + diff. Inside a dark letter the smoothed green is
        // too bright, so R and B come out bright against a dark measured green
        // - the pink fringe. Sharing one estimate makes the difference cancel,
        // which is the whole point of interpolating differences; it is also
        // what the reference implementation does.
        outv = g;
    } else if (tc == 2) {
        outv = (g + texelFetch(DiffBBuffer, xy, 0).x) / max(gainB, 1e-6);
    } else {
        outv = (g + texelFetch(DiffRBuffer, xy, 0).x) / max(gainR, 1e-6);
    }

    float v = clamp(outv, 0.0, 1.0) * range + blackLevel;
    Output = uvec4(uint(clamp(v, 0.0, 65535.0)), 0u, 0u, 0u);
}
