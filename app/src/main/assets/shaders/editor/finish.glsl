// P351: geometry and the after-crop effects. An output pixel -> base uv (crop, or the whole turned frame in the crop tab) ->
// oriented uv (inverse straighten, unflip, unturn) -> the tone pass's texture; then the vignette (linear light, elliptical in the
// crop) and the grain (value noise on a lattice fixed in crop coordinates, seeded: tiles and preview see the same grain).
uniform sampler2D uImg;
uniform mat3 uOutToBase;
uniform mat3 uBaseToImg;
uniform mat3 uImgToTex;
uniform vec4 uCrop;          // l, t, r, b in base uv
uniform vec2 uOutSize;
uniform vec2 uOrigin;        // this tile's corner in output pixels
uniform int uFlipY;          // 1: the screen (row 0 at the bottom)
uniform int uMode;           // 0: outside the crop is background; 1: the crop tab (outside dimmed)
uniform vec3 uBg;
uniform float uVig, uVigMid;
uniform float uGrain;        // amplitude
uniform vec2 uGrainLattice;  // lattice cells across the crop (x, y)
uniform float uGrainPx;      // output pixels per lattice cell
uniform int uSeed;
uniform int uSwap;           // 1: BGRA (the export reads it as ARGB ints)
out vec4 o;

float hash(ivec2 p) {
    uint h = uint(p.x) * 1664525u + uint(p.y) * 1013904223u + uint(uSeed) * 2654435761u;
    h ^= h >> 16;
    h *= 2246822519u;
    h ^= h >> 13;
    h *= 3266489917u;
    h ^= h >> 16;
    return float(h & 0xFFFFFFu) / 8388607.5 - 1.0;
}

float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    ivec2 q = ivec2(i);
    float a = hash(q), b = hash(q + ivec2(1, 0)), c = hash(q + ivec2(0, 1)), d = hash(q + ivec2(1, 1));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

void main() {
    vec2 px = gl_FragCoord.xy + uOrigin;
    if (uFlipY == 1) px.y = uOutSize.y - gl_FragCoord.y;
    vec2 base = xform(uOutToBase, px);
    vec2 cu = (base - uCrop.xy) / (uCrop.zw - uCrop.xy);
    bool inCrop = all(greaterThanEqual(cu, vec2(0.0))) && all(lessThanEqual(cu, vec2(1.0)));
    vec2 img = xform(uBaseToImg, base);
    bool inside = all(greaterThanEqual(img, vec2(-1e-4))) && all(lessThanEqual(img, vec2(1.0001)));
    if (!inside || (uMode == 0 && !inCrop)) {
        o = vec4(uBg, 1.0);
        return;
    }
    vec3 e = texture(uImg, xform(uImgToTex, img)).rgb;
    if (uVig != 0.0) {
        float dist = length((cu - 0.5) * 2.0);
        float start = 0.15 + 0.85 * uVigMid;
        float w = smoothstep(start, start + 0.9, dist);
        w *= w;
        vec3 c = srgbToLin(e);
        c = uVig < 0.0 ? c * (1.0 + uVig * w) : c + (1.0 - c) * uVig * w;
        e = linToSrgb(clamp(c, 0.0, 1.0));
    }
    if (uGrain > 0.0) {
        vec2 g = cu * uGrainLattice;
        float n = (vnoise(g) + 0.5 * vnoise(g * 2.03 + 17.0)) / 1.5;
        float ye = dot(e, vec3(0.299, 0.587, 0.114));
        float m = 1.0 - 0.7 * (2.0 * ye - 1.0) * (2.0 * ye - 1.0);
        e = clamp(e + uGrain * min(1.0, uGrainPx) * n * m, 0.0, 1.0);
    }
    if (uMode == 1 && !inCrop) e *= 0.4;
    o = uSwap == 1 ? vec4(e.b, e.g, e.r, 1.0) : vec4(e, 1.0);
}
