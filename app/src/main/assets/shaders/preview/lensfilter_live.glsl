// P228: the lens filter live on the ISP viewfinder, the photo's model (LensFilters / lensfilter/apply.glsl) in short. Owner,
// 2026-10-18 (copies of the bright parts around them): the glow, the veil, the streaks and the prism's ghost come from a blur
// pyramid - lensfilter_prep.glsl drew the light above the knee and the linear frame at half the screen's size, their mip levels
// are the pyramid's levels 1.. (as the photo's G1..G6 / V4..V6) - not from rings of single taps. GND, Light Pollution,
// Infrared and Kaleidoscope are per pixel. The host defines LF_SAMPLE(uv) (display-referred RGB) and calls lensFilter(...).
// Screen directions come from the derivatives of uv (the texture is rotated to the screen): px one pixel right, py one pixel
// down. uLf 0: no filter, no taps.
uniform int uLf;
uniform float uLfStrength;
uniform float uLfParam;
uniform vec3 uLfTint;
// owner, 2026-10-18: the diffusion filters (uLf 1..6) as the photo's physical model (DiffusionModel) - the frame times uLfW0
// plus the pyramid's levels 1..uLfLevels (the mip levels 0.. of uLfLinTex) times uLfW, all the light, energy kept
uniform vec3 uLfW0;
uniform vec3 uLfW[12];
uniform int uLfLevels;
uniform float uLfStreak;      // the streak's half length, a share of the width
uniform float uLfSegments;
uniform highp sampler2D uLfHiTex;   // the light above the knee, half size, mipmapped
uniform highp sampler2D uLfLinTex;  // the linear frame with its highlights' headroom, half size, mipmapped
uniform int uLfSqrt;                // 1: the textures hold square roots (8-bit targets)
uniform vec4 uLfView;               // the viewport the pyramid was drawn for (x, y, w, h in window pixels)

vec3 lfLin(vec3 c) { return pow(max(c, vec3(0.0)), vec3(2.2)); }
vec3 lfEnc(vec3 c) { return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }
float lfLuma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// back to the display: the frame itself is not re-toned (only the filter's light is added)
vec3 lfOut(vec3 lin) {
    return lfEnc(clamp(lin, 0.0, 1.0));
}

vec3 lfDec(vec3 v, float scale) {
    return uLfSqrt == 1 ? v * v * scale : v;
}

// level k (0 = half size) of a pyramid texture at p (0..1 of the viewport, y up), upsampled with a cubic B-spline from four
// bilinear taps (owner, 2026-10-18: square highlights - a bilinear stretch of a coarse level draws a light's glow as a diamond)
vec3 lfLevel(sampler2D t, vec2 p, int k, float scale) {
    vec2 size = vec2(textureSize(t, k));
    vec2 q = p * size - 0.5;
    vec2 i = floor(q);
    vec2 f = q - i;
    vec2 f2 = f * f, f3 = f2 * f;
    vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
    vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
    vec2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
    vec2 w3 = f3 / 6.0;
    vec2 g0 = w0 + w1, g1 = w2 + w3;
    vec2 h0 = (i - 0.5 + w1 / g0) / size, h1 = (i + 1.5 + w3 / g1) / size;
    float lod = float(k);
    vec3 s = g0.y * (g0.x * textureLod(t, vec2(h0.x, h0.y), lod).rgb + g1.x * textureLod(t, vec2(h1.x, h0.y), lod).rgb)
            + g1.y * (g0.x * textureLod(t, vec2(h0.x, h1.y), lod).rgb + g1.x * textureLod(t, vec2(h1.x, h1.y), lod).rgb);
    return lfDec(s, scale);
}

vec3 lfVeilAt(vec2 p) {
    return 0.2 * lfLevel(uLfLinTex, p, 3, 4.0) + 0.4 * lfLevel(uLfLinTex, p, 4, 4.0) + 0.4 * lfLevel(uLfLinTex, p, 5, 4.0);
}

vec3 lensFilter(vec2 uv, vec3 display, vec2 px, vec2 py, vec2 screen) {
    if (uLf <= 0 || uLfStrength <= 0.0) return display;
    float s = uLfStrength;
    vec3 c = lfLin(display);
    vec2 view = max(uLfView.zw, vec2(1.0));
    vec2 p = (gl_FragCoord.xy - uLfView.xy) / view;   // 0..1 of the picture, y up
    if (uLf == 7) {                                    // GND: the screen's top darker
        float t = 1.0 - p.y;
        float d = 1.0 - smoothstep(uLfParam - 0.15, uLfParam + 0.15, t);
        return lfOut(c * exp2(-3.0 * s * d));
    }
    if (uLf == 8) {                                    // Light Pollution
        float y = lfLuma(c);
        float mx = max(c.r, max(c.g, c.b));
        float yellow = max(0.0, (c.r + c.g) * 0.5 - c.b) / max(1e-4, mx);
        float band = smoothstep(0.15, 0.55, yellow) * (c.r >= c.g ? 1.0 : smoothstep(0.6, 1.0, c.r / max(1e-4, c.g)));
        c = c + 0.8 * s * band * (vec3(y) - c);
        return lfOut(c * vec3(1.0 - 0.06 * s, 1.0, 1.0 + 0.08 * s));
    }
    if (uLf == 12) {                                   // Infrared
        float ir = max(0.0, 0.25 * c.r + 1.2 * c.g - 0.45 * c.b);
        vec3 o;
        if (uLfParam < 1.0 / 3.0) o = vec3(ir);
        else if (uLfParam < 2.0 / 3.0) o = vec3(0.6 * ir + 0.6 * c.r, 0.75 * ir + 0.1 * c.r, 0.55 * c.b + 0.25 * ir);
        else o = c.bgr;
        return lfOut(c + s * (o - c));
    }
    if (uLf == 11) {                                   // Kaleidoscope: folded about the screen's centre
        vec2 q0 = (p - 0.5) * view;
        q0.y = -q0.y;
        float r = length(q0), a = atan(q0.y, q0.x);
        float seg = 6.28318530718 / uLfSegments;
        float m = mod(a, seg);
        float f = abs(m - seg * 0.5);
        vec2 q = r * vec2(cos(f), sin(f)) - q0;        // screen pixels from here
        vec3 k = lfLin(LF_SAMPLE(uv + q.x * px + q.y * py));
        return lfOut(c + s * (k - c));
    }
    if (uLf == 10) {                                   // Prism: the soft frame shifted right and up, tinted, on the left part
        float off = 0.05 + 0.2 * uLfParam;
        vec2 base = p + vec2(off, 0.35 * off * view.x / view.y);
        vec3 g;
        g.r = lfVeilAt(base + vec2(-0.004, 0.0)).r;
        g.g = lfVeilAt(base).g;
        g.b = lfVeilAt(base + vec2(0.004, 0.0)).b;
        float mask = 1.0 - smoothstep(0.35, 0.75, p.x);
        return lfOut(c + 0.35 * s * mask * vec3(1.15, 0.9, 1.2) * g);
    }
    vec3 o = c;
    if (uLf <= 6) {
        o = uLfW0 * c;
        for (int k = 1; k <= 12; k++) {
            if (k > uLfLevels) break;
            vec3 w = uLfW[k - 1];
            if (w.r + w.g + w.b > 1e-5) o += w * lfLevel(uLfLinTex, p, k - 1, 4.0);
        }
    }
    if (uLf == 9 || uLf == 3) {                        // Streak (screen x) / Glimmerglass (the diagonals)
        float len = uLfStreak;                         // a share of the width
        vec2 dA = uLf == 9 ? vec2(1.0, 0.0) : vec2(0.7071, 0.7071 * view.x / view.y);
        vec2 dB = vec2(0.7071, -0.7071 * view.x / view.y);
        // 16 taps a side; each tap blurred over about its spacing (a mip level), so the taps join into a line
        float spacing = len / 16.0 * 0.5 * view.x;     // in half-size texels
        float lod = clamp(log2(max(spacing, 1.0)), 0.0, 5.0);
        vec3 st = vec3(0.0);
        float norm = 0.0;
        for (int i = 1; i <= 16; i++) {
            float w = exp(-3.0 * float(i) / 16.0);
            float o1 = len * float(i) / 16.0;
            st += w * (textureLod(uLfHiTex, p + o1 * dA, lod).rgb + textureLod(uLfHiTex, p - o1 * dA, lod).rgb);
            norm += 2.0 * w;
            if (uLf == 3) {
                st += w * (textureLod(uLfHiTex, p + o1 * dB, lod).rgb + textureLod(uLfHiTex, p - o1 * dB, lod).rgb);
                norm += 2.0 * w;
            }
        }
        o += s * (uLf == 9 ? 4.0 : 1.6) * lfDec(st / norm, 4.0) * uLfTint;
    }
    if (uLf == 4) o += 0.12 * s * (vec3(lfLuma(o)) - o);   // Pearlescent: pastel
    return lfOut(o);
}
