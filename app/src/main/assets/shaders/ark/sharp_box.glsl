precision highp float;
precision highp sampler2D;
// ARK luma sharpening (ArkLumaSharpen): guided-filter local contrast of luma_compose [fc_cl_source.cl:235-301;
// ark_sharpen.md 3.2] - box sums of (I, I^2), a = var / (var + max(eps, 1e-5)), b = mean - a * mean, box of (a, b).
// The box has 2r + 1 taps and repeats the edge pixels (divisor 2r + 1), as the kernel. On the Sabre 2x grid the filter
// runs on the 1x-equivalent grid (I = downU x downU mean of Ya), where ArkCam runs it; ArkLumaSharpen samples (a, b)
// bilinearly (a and b are box-smooth).
//  modeU 0: horizontal box of (I, I^2), I from InputBuffer = Ya (full grid)   -> RG32F, reduced grid
//  modeU 1: vertical box -> (a, b)                                           -> RG16F
//  modeU 2: horizontal box of (a, b); modeU 3: vertical box of (a, b)        -> RG16F
uniform sampler2D InputBuffer;
uniform int modeU;
uniform int radiusU;                // box radius on the reduced grid; <= 0 -> 8
uniform int downU;                  // modeU 0: reduction of Ya; <= 0 -> 1
uniform float epsU;                 // modeU 1; <= 0 -> 0.01
out vec4 Output;

float guide(ivec2 p, int d, ivec2 lastFull) {
    if (d == 1) return texelFetch(InputBuffer, min(p, lastFull), 0).r;
    float s = 0.0;
    for (int j = 0; j < d; j++)
        for (int i = 0; i < d; i++) s += texelFetch(InputBuffer, min(p * d + ivec2(i, j), lastFull), 0).r;
    return s / float(d * d);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    int r = radiusU > 0 ? radiusU : 8;
    float n = float(2 * r + 1);
    if (modeU == 0) {
        int d = downU > 0 ? downU : 1;
        ivec2 full = textureSize(InputBuffer, 0);
        ivec2 lastFull = full - ivec2(1);
        int lastX = (full.x + d - 1) / d - 1;
        vec2 s = vec2(0.0);
        for (int i = -r; i <= r; i++) {
            float v = guide(ivec2(clamp(xy.x + i, 0, lastX), xy.y), d, lastFull);
            s += vec2(v, v * v);
        }
        Output = vec4(s / n, 0.0, 1.0);
        return;
    }
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    ivec2 dir = modeU == 2 ? ivec2(1, 0) : ivec2(0, 1);
    vec2 s = vec2(0.0);
    for (int i = -r; i <= r; i++) s += texelFetch(InputBuffer, clamp(xy + dir * i, ivec2(0), last), 0).rg;
    s /= n;
    if (modeU == 1) {
        float eps = epsU > 0.0 ? epsU : 0.01;
        float var = max(0.0, s.y - s.x * s.x);
        float a = var / (var + max(eps, 0.00001));
        Output = vec4(a, s.x - a * s.x, 0.0, 1.0);
        return;
    }
    Output = vec4(s, 0.0, 1.0);
}
