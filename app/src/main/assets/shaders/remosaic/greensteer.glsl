#version 300 es
// Green interpolation steered by the local gradient.
//
// The isotropic version averages across edges as readily as along them. Where a
// dark letter meets a coloured background that average sits between the two
// sides, and since the colour differences are then taken against this green, the
// error comes out as a coloured fringe around the letter.
//
// Deciding direction first fixes it at the source: estimate the horizontal and
// vertical gradients from the samples that exist, then weight the two
// directional interpolations by how flat each direction is. Along an edge the
// gradient is small and that direction wins; across it the gradient is large and
// that direction is suppressed. On flat ground the weights are equal and the
// result matches the isotropic one.
//
// Input carries the masked channel in x and its mask in y, as maskblur expects.
precision highp float;

uniform sampler2D InputBuffer;
uniform ivec2 size;
/** Half-width of the search: 4 for tetra blocks, 2 for quad. */
uniform int reach;
/**
 * How sharply direction is chosen. Higher is more decisive and more prone to
 * picking wrongly on texture the gradient cannot resolve; 8 keeps edges clean
 * without forcing a choice where there is none.
 */
uniform float steer;

out vec4 Output;

// Nearest measured samples on either side bracket the missing site.
// Distance weighting reproduces affine ramps in the image interior instead
// of moving the effective sample position with the phase of the colour block.
// z is availability: an empty direction must not contribute a black estimate.
vec3 axisEstimate(ivec2 xy, ivec2 dir) {
    float a = 0.0, b = 0.0;
    int da = 0, db = 0;
    for (int d = 1; d <= reach; d++) {
        ivec2 pa = xy - dir * d;
        ivec2 pb = xy + dir * d;
        if (da == 0 && all(greaterThanEqual(pa, ivec2(0))) && all(lessThan(pa, size))) {
            vec2 s = texelFetch(InputBuffer, pa, 0).xy;
            if (s.y > 0.0) { a = s.x; da = d; }
        }
        if (db == 0 && all(greaterThanEqual(pb, ivec2(0))) && all(lessThan(pb, size))) {
            vec2 s = texelFetch(InputBuffer, pb, 0).xy;
            if (s.y > 0.0) { b = s.x; db = d; }
        }
    }
    if (da == 0 && db == 0) return vec3(0.0);
    if (da == 0) return vec3(b, 0.0, 0.5);
    if (db == 0) return vec3(a, 0.0, 0.5);
    float span = float(da + db);
    return vec3((a * float(db) + b * float(da)) / span,
                abs(b - a) / span, 1.0);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec2 self = texelFetch(InputBuffer, xy, 0).xy;
    if (self.y > 0.0) {
        // Measured sites keep their own value.
        Output = vec4(self.x, 1.0, 0.0, 1.0);
        return;
    }

    vec3 h = axisEstimate(xy, ivec2(1, 0));
    vec3 v = axisEstimate(xy, ivec2(0, 1));

    // Weight each direction by how flat it is. 1e-4 keeps a perfectly flat
    // direction from taking the whole weight on noise alone.
    float wh = h.z / (1.0 + steer * h.y + 1e-4);
    float wv = v.z / (1.0 + steer * v.y + 1e-4);
    float total = wh + wv;

    float value = total > 0.0 ? (h.x * wh + v.x * wv) / total : 0.0;
    Output = vec4(value, 1.0, 0.0, 1.0);
}
