precision highp float;
precision highp sampler2D;
precision highp int;
// P402 «Пирамидальный шумодав» (WhyMsDenoise; the reference app's ms denoise: pyr_downsample, pyr_laplace_filter_msdenoise,
// pyr_base_filter, pyr_reconstruct): the merge in OKLab of its render's linear sRGB, a Laplacian pyramid of 3 levels and a base;
// each level's luminance detail soft-thresholded (edges kept), its colour detail pulled towards a luminance-guided mean and, off the
// edges, towards none; the base's colour by a 5 x 5 luminance-guided mean. Thresholds rise in the shadows (shadowU). OKLab with a
// signed cube root, so a colour outside sRGB (a negative channel) goes through unchanged where nothing is filtered; the result is
// given back as a change of the camera RGB (the input + its denoised change), so a pixel the filter leaves alone stays as it was.
//  MODE 0: level 1 from the camera RGB (a [1 3 3 1] / 8 kernel per axis over 4 x 4 pixels, OKLab per pixel)
//  MODE 1: the next level from an OKLab level, the same kernel
//  MODE 2: the base (level 3): its colour filtered
//  MODE 3: level 1 or 2 rebuilt: the coarser rebuilt level + the filtered detail of this one
//  MODE 4: the full-size camera RGB: the input + the change of level 0 rebuilt
#define MODE 0
// MOTION 1 (WhyMsDenoise, the merge's frame map at the input's size and «Усиление в зонах движения» above 0): the detail thresholds
// rise where the merge kept few frames (pyr_laplace_filter_msdenoise's motion boost: 1 frame full, 6 and more none).
#define MOTION 0
uniform sampler2D InputBuffer;      // MODE 0, 4: the camera RGB (white-balanced, linear); MODE 1, 2, 3: the OKLab level (NEAREST)
uniform sampler2D Coarse;           // MODE 3, 4: the next coarser OKLab level (LINEAR)
uniform sampler2D CoarseRecon;      // MODE 3, 4: the next coarser rebuilt level (LINEAR)
uniform mat3 toRenderU;             // camera RGB x neutral -> the render's linear sRGB
uniform mat3 fromRenderU;           // its inverse
uniform vec3 neutralU;              // the white point (camera RGB x it = the render's input)
uniform float lumaThU;              // this level's luminance threshold (already x the user's level share)
uniform float chromaThU;            // this level's colour threshold
uniform float shadowU;              // shadow boost of the thresholds
uniform int levelU;                 // 0, 1, 2
#if MOTION == 1
uniform highp usampler2D EffMap;    // the merge's frame map (codes, 0 = unknown), full size
uniform float effScaleU;            // frames per code
uniform vec2 motionU;               // the luminance and colour thresholds' boost at full motion (x the setting: 0.30, 0.50)
#endif
out vec4 Output;

float scbrt(float x) { return sign(x) * pow(abs(x), 1.0 / 3.0); }

vec3 toLab(vec3 c) {
    float l = scbrt(0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b);
    float m = scbrt(0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b);
    float s = scbrt(0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b);
    return vec3(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s, 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s);
}

vec3 fromLab(vec3 lab) {
    float l = lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z;
    float m = lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z;
    float s = lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z;
    l = l * l * l; m = m * m * m; s = s * s * s;
    return vec3(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
}

#if MODE == 0 || MODE == 4
vec3 camLab(ivec2 p) {
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    vec3 c = texelFetch(InputBuffer, clamp(p, ivec2(0), last), 0).rgb;
    return toLab(toRenderU * (c * neutralU));
}
#define FINE(p) camLab(p)
#else
vec3 fineAt(ivec2 p) { return texelFetch(InputBuffer, clamp(p, ivec2(0), textureSize(InputBuffer, 0) - ivec2(1)), 0).rgb; }
#define FINE(p) fineAt(p)
#endif

#if MODE == 3 || MODE == 4
// pyr_laplace_filter_msdenoise
vec3 filteredDetail(ivec2 xy, vec2 inv) {
    vec3 fine = FINE(xy);
    vec3 lap = fine - texture(Coarse, (vec2(xy) + 0.5) * inv).rgb;
    float shadow = 1.0 + shadowU * max(0.0, 1.0 - fine.x * 2.5);
    float lTh = lumaThU * shadow * (levelU == 0 ? 1.0 : (levelU == 1 ? 0.65 : 0.35));
    float cTh = chromaThU * shadow * (levelU == 0 ? 1.0 : (levelU == 1 ? 0.85 : 0.70));
#if MOTION == 1
    uint code = texelFetch(EffMap, clamp(xy << levelU, ivec2(0), textureSize(EffMap, 0) - ivec2(1)), 0).r;
    if (code > 0u) {
        float t = clamp(1.0 - (max(float(code) * effScaleU, 1.0) - 1.0) / 5.0, 0.0, 1.0);
        float mo = t * t * (3.0 - 2.0 * t);
        lTh *= 1.0 + motionU.x * mo;
        cTh *= 1.0 + motionU.y * mo;
    }
#endif
    float la = abs(lap.x);
    float lF = sign(lap.x) * max(0.0, la - lTh * (1.0 - smoothstep(0.0, lTh * 2.5, la)));
    vec2 ab = lap.yz;
    if (cTh > 0.0001) {
        vec2 sum = lap.yz;
        float sw = 1.0;
        int st = levelU == 2 ? 2 : 1;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                ivec2 q = xy + ivec2(dx, dy) * st;
                vec3 nf = FINE(q);
                vec3 nl = nf - texture(Coarse, (vec2(q) + 0.5) * inv).rgb;
                float w = exp(-float(dx * dx + dy * dy) * 0.5) * exp(-abs(nf.x - fine.x) * 35.0);
                sum += nl.yz * w;
                sw += w;
            }
        float shrink = clamp(cTh * 45.0, 0.0, 1.0);
        vec2 cleaned = mix(sum / sw, vec2(0.0), shrink * (1.0 - smoothstep(0.005, 0.035, abs(lap.x))));
        ab = mix(lap.yz, cleaned, shrink);
    }
    return vec3(lF, ab);
}
#endif

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
#if MODE == 0 || MODE == 1
    // [1 3 3 1] / 8 per axis over the 4 x 4 pixels round this one's 2 x 2 block
    const float K[4] = float[4](0.125, 0.375, 0.375, 0.125);
    vec3 sum = vec3(0.0);
    for (int j = 0; j < 4; j++)
        for (int i = 0; i < 4; i++) sum += FINE(xy * 2 + ivec2(i - 1, j - 1)) * (K[i] * K[j]);
    Output = vec4(sum, 1.0);
#elif MODE == 2
    vec3 c = FINE(xy);
    float cTh = chromaThU * (1.0 + shadowU * max(0.0, 1.0 - c.x * 2.5));
    if (cTh <= 0.0001) { Output = vec4(c, 1.0); return; }
    vec2 sum = c.yz;
    float sw = 1.0;
    for (int dy = -2; dy <= 2; dy++)
        for (int dx = -2; dx <= 2; dx++) {
            if (dx == 0 && dy == 0) continue;
            vec3 n = FINE(xy + ivec2(dx, dy));
            float w = exp(-float(dx * dx + dy * dy) * 0.25) * exp(-abs(n.x - c.x) * 30.0);
            sum += n.yz * w;
            sw += w;
        }
    Output = vec4(c.x, mix(c.yz, sum / sw, clamp(cTh * 45.0, 0.0, 1.0)), 1.0);
#elif MODE == 3
    vec2 inv = 1.0 / vec2(textureSize(InputBuffer, 0));
    Output = vec4(texture(CoarseRecon, (vec2(xy) + 0.5) * inv).rgb + filteredDetail(xy, inv), 1.0);
#else
    vec2 inv = 1.0 / vec2(textureSize(InputBuffer, 0));
    vec2 uv = (vec2(xy) + 0.5) * inv;
    vec4 in4 = texelFetch(InputBuffer, xy, 0);
    vec3 fine = camLab(xy);
    vec3 rebuilt = texture(CoarseRecon, uv).rgb + filteredDetail(xy, inv);
    vec3 change = fromRenderU * (fromLab(rebuilt) - fromLab(fine));
    Output = vec4(in4.rgb + change / max(neutralU, vec3(1.0e-6)), in4.a);
#endif
}
