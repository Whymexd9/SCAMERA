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

/** Mask-normalised mean along one axis, plus the gradient seen along it. */
vec2 axisEstimate(ivec2 xy, ivec2 dir) {
    float sum = 0.0, wsum = 0.0;
    float prev = -1.0;
    float grad = 0.0;
    int gradN = 0;

    for (int i = -reach; i <= reach; i++) {
        ivec2 p = clamp(xy + dir * i, ivec2(0), size - 1);
        vec2 s = texelFetch(InputBuffer, p, 0).xy;
        if (s.y <= 0.0) continue;
        // Flat weights: over one block period a flat kernel averages every
        // block evenly, which is what keeps block edges from stepping.
        sum += s.x;
        wsum += 1.0;
        if (prev >= 0.0) {
            grad += abs(s.x - prev);
            gradN++;
        }
        prev = s.x;
    }
    float mean = wsum > 0.0 ? sum / wsum : 0.0;
    float g = gradN > 0 ? grad / float(gradN) : 0.0;
    return vec2(mean, g);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec2 self = texelFetch(InputBuffer, xy, 0).xy;
    if (self.y > 0.0) {
        // Measured sites keep their own value.
        Output = vec4(self.x, 1.0, 0.0, 1.0);
        return;
    }

    vec2 h = axisEstimate(xy, ivec2(1, 0));
    vec2 v = axisEstimate(xy, ivec2(0, 1));

    // Weight each direction by how flat it is. 1e-4 keeps a perfectly flat
    // direction from taking the whole weight on noise alone.
    float wh = 1.0 / (1.0 + steer * h.y + 1e-4);
    float wv = 1.0 / (1.0 + steer * v.y + 1e-4);
    float total = wh + wv;

    float value = total > 0.0 ? (h.x * wh + v.x * wv) / total : 0.0;
    Output = vec4(value, 1.0, 0.0, 1.0);
}
