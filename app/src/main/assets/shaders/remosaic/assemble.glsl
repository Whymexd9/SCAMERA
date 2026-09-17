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
        int sc = colorAt(xy);
        if (sc == 1) {
            // Measured green stays measured. Taking the interpolated field here
            // instead was tried, on the reasoning that one estimate per 2x2
            // cancels in g + diff; checked against the reference app's output on
            // the same raw, it is not what that does - green passes through bit
            // for bit at 99.8% of the coinciding sites, and passing it through
            // matches the reference four times closer on average, seventeen
            // times closer at the 99th percentile.
            float v = (float(texelFetch(RawBuffer, xy, 0).r) - blackLevel) / range;
            outv = clamp(v, 0.0, 1.0);
        } else {
            outv = g;
        }
    } else if (tc == 2) {
        outv = (g + texelFetch(DiffBBuffer, xy, 0).x) / max(gainB, 1e-6);
    } else {
        outv = (g + texelFetch(DiffRBuffer, xy, 0).x) / max(gainR, 1e-6);
    }

    float v = clamp(outv, 0.0, 1.0) * range + blackLevel;
    Output = uvec4(uint(clamp(v, 0.0, 65535.0)), 0u, 0u, 0u);
}
