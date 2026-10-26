// P351: the tone and colour of the photo editor, per pixel (EditMath.main is the CPU reference: keep them the same). In: develop's
// linear light + log2 Y, the Gaussian blurs of log2 Y (sharpening, texture), the guided bases of the proxy (mid: clarity; large:
// shadows / highlights, the dark channel for dehaze). Out: sRGB-encoded colour.
// The colour stage (vibrance, saturation, 8-band mixer, B&W mix, 3-way grading in OkLCh) is ported from LightCraft
// crates/pipeline/src/colorops.rs (MIT OR Apache-2.0, https://github.com/storytold/lightcraft), commit
// bb08a697a41b2561a54f1b740245119503b24758; there on linear Rec.2020, here on linear sRGB primaries.
uniform sampler2D uDev;
uniform sampler2D uBlur;
uniform sampler2D uMid;
uniform sampler2D uLarge;
uniform highp sampler2D uLut;   // R32F, 1024 x 1: the tone curve (EditMath.toneLut)
uniform vec2 uTexel;
uniform mat3 uTexToProxy;       // this pass's uv -> the proxy's uv (the whole source)
uniform vec2 uLargeScale;       // proxy uv -> the large level's uv (its 4x4 blocks may overhang)
uniform int uBlurOn;
uniform float uSharpen, uTexture, uClarity, uShadows, uHighlights, uDehaze;
uniform vec3 uAir;
uniform float uSat, uVib;
uniform int uScene;             // 1: a RAW's table over log2 Y; 0: a photo's over encoded Y
uniform int uColourOn, uMixerOn, uMono, uGradeOn, uPointsOn;
uniform vec3 uHsl[8];           // per band: hue, saturation, luminance (-1..1)
uniform float uBw[8];           // B&W mix per band (-1..1)
uniform float uBandHues[8];     // band centres, OkLCh hue in radians, rising
uniform float uSkinHue;
uniform vec3 uGrade[4];         // shadows, midtones, highlights, global: OkLab a, b offsets and lightness
uniform float uGradeBlend, uGradeBalance;
uniform highp sampler2D uPoints; // R32F 1024 x 4: point curves master, R, G, B over encoded 0..1
out vec4 o;

const float LUT_N = 1024.0;
const float DISPLAY_STEPS = 511.0;
const float SCENE_LO = -14.0, SCENE_HI = 6.0;

float lut(float y) {
    float x = uScene == 1 ? (log2(max(y, 1e-9)) - SCENE_LO) / (SCENE_HI - SCENE_LO) * (LUT_N - 1.0)
                          : linToSrgb(y) * DISPLAY_STEPS;
    x = clamp(x, 0.0, LUT_N - 1.0);
    float i0 = floor(x);
    float a = texelFetch(uLut, ivec2(int(i0), 0), 0).r;
    float b = texelFetch(uLut, ivec2(min(int(i0) + 1, int(LUT_N) - 1), 0), 0).r;
    return a + (b - a) * (x - i0);
}

const float PI = 3.14159265;

vec3 oklab(vec3 c) {
    c = max(c, 0.0);
    float l = 0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b;
    float m = 0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b;
    float s = 0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b;
    l = pow(l, 1.0 / 3.0);
    m = pow(m, 1.0 / 3.0);
    s = pow(s, 1.0 / 3.0);
    return vec3(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s);
}

vec3 oklabToLinear(vec3 lab) {
    float l = lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z;
    float m = lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z;
    float s = lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z;
    l = l * l * l;
    m = m * m * m;
    s = s * s * s;
    return vec3(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193414 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
}

float wrapAngle(float a) { return a - 2.0 * PI * floor((a + PI) / (2.0 * PI)); }

float tmod(float a) { return a - 2.0 * PI * floor(a / (2.0 * PI)); }

// LightCraft band_weights: the neighbouring pair whose arc holds h, raised-cosine split
void bandWeights(float h, out float w[8]) {
    for (int k = 0; k < 8; ++k) w[k] = 0.0;
    h = tmod(h);
    for (int i = 0; i < 8; ++i) {
        float a = uBandHues[i], b = uBandHues[(i + 1) % 8];
        float span = tmod(b - a), d = tmod(h - a);
        if (span <= 0.0 || d > span) continue;
        float s = 0.5 - 0.5 * cos(d / span * PI);
        w[i] += 1.0 - s;
        w[(i + 1) % 8] += s;
        break;
    }
}

float smoothRange(float a, float b, float x) { return b <= a ? step(b, x) : smoothstep(a, b, x); }

vec3 colourStage(vec3 c) {
    vec3 lab = oklab(c);
    float L = lab.x, C = length(lab.yz), h = atan(lab.z, lab.y);
    if (uVib != 0.0) {
        float low = 1.0 - clamp(C / 0.22, 0.0, 1.0);
        float sk = wrapAngle(h - uSkinHue) / 0.35;
        float skin = uVib > 0.0 ? 1.0 - 0.6 * exp(-sk * sk) : 1.0;
        C *= max(0.0, 1.0 + uVib * low * low * skin * 1.2);
    }
    C *= max(0.0, 1.0 + uSat);
    if (uMixerOn == 1 || uMono == 1) {
        float w[8];
        bandWeights(h, w);
        if (uMixerOn == 1) {
            vec3 dd = vec3(0.0);
            for (int i = 0; i < 8; ++i) dd += w[i] * uHsl[i];
            float cw = min(C / 0.12, 1.0);
            h += dd.x * 0.5 * cw;
            C *= max(0.0, 1.0 + dd.y);
            L += dd.z * 0.18 * cw * sqrt(max(L, 0.05));
        }
        if (uMono == 1) {
            float mix = 0.0;
            for (int i = 0; i < 8; ++i) mix += w[i] * uBw[i];
            L = max(0.0, L + mix * min(C / 0.2, 1.0) * 0.25);
            C = 0.0;
        }
    }
    vec2 ab = C * vec2(cos(h), sin(h));
    if (uGradeOn == 1) {
        float m = 0.5 - uGradeBalance * 0.25, width = 0.15 + uGradeBlend * 0.5;
        float ws = 1.0 - smoothRange(m - width, m + width * 0.25, L);
        float wh = smoothRange(m - width * 0.25, m + width, L);
        float wm = max(0.0, 1.0 - ws - wh);
        vec3 g = uGrade[0] * ws + uGrade[1] * wm + uGrade[2] * wh + uGrade[3];
        ab += g.xy;
        L += g.z;
    }
    return oklabToLinear(vec3(L, ab));
}

float point(int row, float x) {
    float f = clamp(x, 0.0, 1.0) * (LUT_N - 1.0);
    float i0 = floor(f);
    float a = texelFetch(uPoints, ivec2(int(i0), row), 0).r;
    float b = texelFetch(uPoints, ivec2(min(int(i0) + 1, int(LUT_N) - 1), row), 0).r;
    return a + (b - a) * (f - i0);
}

vec3 fitGamut(vec3 c, float y) {
    if (y >= 1.0) return vec3(1.0);
    float mn = min(c.r, min(c.g, c.b));
    if (mn < 0.0) c = y + (c - y) * (y / (y - mn));
    float mx = max(c.r, max(c.g, c.b));
    if (mx > 1.0) c = y + (c - y) * ((1.0 - y) / (mx - y));
    return c;
}

void main() {
    vec2 uv = gl_FragCoord.xy * uTexel;
    vec4 d = texture(uDev, uv);
    vec3 c = d.rgb;
    float L = d.a;
    vec2 pu = xform(uTexToProxy, uv);
    vec4 mid = texture(uMid, pu);
    vec4 lg = texture(uLarge, pu * uLargeScale);
    float x = clamp(L, -12.0, 4.0) + CENTRE;
    float bm = mid.r * x + mid.g - CENTRE;
    float bl = lg.r * x + lg.g - CENTRE;
    float dL = 0.0;
    float toe = smoothstep(-11.0, -7.0, L);
    if (uBlurOn == 1) {
        vec2 bf = texture(uBlur, uv).rg;
        dL += toe * (uSharpen * (L - bf.r) + uTexture * (L - bf.g));
    }
    float relm = bm - MID_GREY_LOG, rel = bl - MID_GREY_LOG;
    dL += uClarity * toe * exp(-relm * relm / 8.0) * (L - bm);
    dL += uShadows * (1.0 - smoothstep(-5.0, 0.0, rel)) + uHighlights * smoothstep(0.0, 2.5, rel);
    if (uDehaze > 0.0) {
        float t = max(1.0 - 0.95 * uDehaze * lg.b, 0.1);
        c = max((c - uAir) / t + uAir, 0.0);
    } else if (uDehaze < 0.0) {
        c = mix(c, uAir, -0.6 * uDehaze * (0.4 + 0.6 * clamp(lg.b, 0.0, 1.0)));
    }
    c *= exp2(dL);
    float y = dot(c, LUMA);
    if (y > 1e-7) {
        float y2 = srgbToLin(lut(y));
        c *= y2 / y;
        y = y2;
    } else {
        y = 0.0;
    }
    if (uColourOn == 1) c = colourStage(c);
    y = clamp(dot(c, LUMA), 0.0, 1.0);
    c = fitGamut(c, y);
    vec3 e = clamp(linToSrgb(c), 0.0, 1.0);
    if (uPointsOn == 1) e = vec3(point(1, point(0, e.r)), point(2, point(0, e.g)), point(3, point(0, e.b)));
    o = vec4(e, 1.0);
}
