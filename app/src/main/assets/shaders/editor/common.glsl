// P351: shared by every fragment pass of the photo editor (EditGl puts it after "#version 300 es" and the precisions).
// Textures: v = 0 is the top row of the picture; a pass writes texel (x, y) at gl_FragCoord (x + 0.5, y + 0.5).
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float CENTRE = 4.0;               // EditMath.CENTRE: log2 Y + 4, clamped to -12..4, in the guided filter textures
const float MID_GREY_LOG = -2.473931;   // log2(0.18)

float srgbToLin(float e) { return e <= 0.04045 ? e / 12.92 : pow((e + 0.055) / 1.055, 2.4); }
vec3 srgbToLin(vec3 e) { return vec3(srgbToLin(e.r), srgbToLin(e.g), srgbToLin(e.b)); }
float linToSrgb(float x) { return x <= 0.0031308 ? x * 12.92 : 1.055 * pow(x, 1.0 / 2.4) - 0.055; }
vec3 linToSrgb(vec3 c) { return vec3(linToSrgb(c.r), linToSrgb(c.g), linToSrgb(c.b)); }
vec2 xform(mat3 m, vec2 p) { return (m * vec3(p, 1.0)).xy; }

// develop (EditMath.develop): a source texel -> linear light in sRGB primaries, the RAW clip handling included
uniform int uLinearIn;   // 1: the texture is linear (RAW, F16 linear); 0: sRGB-encoded
uniform int uRaw;        // 1: camera RGB of a RAW (white-balance multipliers, clipped channels to neutral)
uniform vec3 uWbMul;
uniform mat3 uToWork;    // source / camera -> linear sRGB (uploaded row-major with transpose)
uniform vec3 uGain;      // a photo's WB gains x 2^(exposure + base)

vec3 developColour(vec3 s) {
    vec3 c = uLinearIn == 1 ? s : srgbToLin(s);
    if (uRaw == 1) {
        float m = max(c.r, max(c.g, c.b));
        c *= uWbMul;
        float t = smoothstep(0.92, 1.0, m);
        c = mix(c, vec3((c.r + c.g + c.b) / 3.0), t);
    }
    return (uToWork * c) * uGain;
}
