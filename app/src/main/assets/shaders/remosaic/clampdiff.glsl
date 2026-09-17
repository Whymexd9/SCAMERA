#version 300 es
// Clamps an interpolated colour difference to the range of the differences
// actually measured nearby.
//
// Interpolation spans edges: at a dark letter on a coloured ground the
// difference is one value on one side and another on the other, and the mean
// lands between them. That in-between value is the pink fringe - it is a colour
// no sample in the neighbourhood had.
//
// Bounding the result by the local minimum and maximum of the measured
// differences leaves flat areas untouched, since there the interpolation is
// already inside the range, and cuts exactly the overshoot at edges. Standard
// practice in demosaics, one pass.
//
// x = interpolated difference, y = mask (which sites were measured).
precision highp float;

uniform sampler2D InterpBuffer;
uniform sampler2D MaskedBuffer;
uniform ivec2 size;
/** Half-width of the search; must reach at least one measured site. */
uniform int reach;

out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    float v = texelFetch(InterpBuffer, xy, 0).x;

    float lo = 1e9, hi = -1e9;
    int found = 0;
    for (int j = -reach; j <= reach; j++) {
        for (int i = -reach; i <= reach; i++) {
            ivec2 p = clamp(xy + ivec2(i, j), ivec2(0), size - 1);
            vec2 s = texelFetch(MaskedBuffer, p, 0).xy;
            if (s.y <= 0.0) continue;
            lo = min(lo, s.x);
            hi = max(hi, s.x);
            found++;
        }
    }

    // With nothing measured nearby there is no range to clamp against, and the
    // interpolated value is the only estimate available.
    if (found == 0) {
        Output = vec4(v, 0.0, 0.0, 1.0);
        return;
    }
    Output = vec4(clamp(v, lo, hi), 0.0, 0.0, 1.0);
}
