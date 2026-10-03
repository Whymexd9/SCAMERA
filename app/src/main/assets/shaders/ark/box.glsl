precision highp float;
precision highp sampler2D;
// ARK tone (ArkFusion): separable box mean of the guided-filter statistics [fc_cl_source.cl:1291-1341
// box_blur_h/v_f2/f4]: 2r+1 taps, clamp to edge (the count stays 2r+1). Statistics only - the box never
// reconstructs image detail, it averages the weight field of the fusion at half the arkLow size.
uniform sampler2D InputBuffer;  // RGBA32F statistics (NEAREST)
uniform ivec2 dirU;             // (1, 0) horizontal, (0, 1) vertical; unset -> horizontal
uniform int radiusU;            // r = ef_gf_radius / 2 (ArkCam 16); <= 0 -> 16
out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    ivec2 dir = dirU.x == 0 && dirU.y == 0 ? ivec2(1, 0) : dirU;
    int r = radiusU > 0 ? radiusU : 16;
    vec4 sum = vec4(0.0);
    for (int k = -r; k <= r; k++) {
        sum += texelFetch(InputBuffer, clamp(xy + dir * k, ivec2(0), last), 0);
    }
    Output = sum / float(2 * r + 1);
}
