precision highp float;
precision highp sampler2D;
// ARK tone, step 1 (ArkStats): the G_CLEAN equivalent "arkLow" - the box mean (factorU x factorU output pixels) of the
// linear Rec.709 scene, i.e. Google's ds_linear_rgb that the ArkCam / SCAM 9.6 photo tone reads (half the sensor:
// factor 2 on the 1x grid, 4 on the Sabre 2x grid). Same colour chain as the former headroom/render.glsl
// (intermediateToSRGB * sensorToIntermediate * (in * NEUTRALPOINT * LSC)) with the FULL lens-shading gain and
// without any clip: Bento content above 1 is kept. Factor 2 on the 2x grid gives "arkMid", the 1x-equivalent colour
// source of ArkCombine. Keep sceneLinear(clampInput true) identical to ark/combine.glsl (the detail there is measured
// against it).
// DETAIL_REF 1 (ArkCombine): .r = box mean of min(ae * Y709, 1), the bounded reference of the detail delta - the
// kernel's guide a3 is "normalized to [0, 1]"; the mean of the clipped values (not the clip of the mean) keeps a
// lamp's headroom out of the reference of its dark neighbours.
// Signed input (SCAM Hybrid, scamrgb signedU; the denoise clamps its output, without the denoise the merge's signed noise
// arrives here): the colour box mean averages the signed values, a clamp per pixel would lift the mean of a channel near
// zero (red mottling of a dark teal curtain under the ARK shadow lift). DETAIL_REF clamps per pixel as ark/combine's
// detail luminance does (the delta compares the two). For non-negative input both are the former means.
#define DETAIL_REF 0
#import interpolation
uniform sampler2D InputBuffer;      // white-balanced linear camera RGB (ScamRgb .. ScamHdrDenoise), output grid
uniform sampler2D GainMap;          // lens shading gains (r, g_even, g_odd, b)
uniform mat3 sensorToIntermediate;  // sensor -> ProPhoto
uniform mat3 intermediateToSRGB;    // ProPhoto -> linear sRGB / Rec.709
uniform vec3 neutralPointU;         // white point (NEUTRALPOINT of render.glsl); unset -> 1
uniform float inScaleU;             // one multiplier to the G_CLEAN scale (ark_input_ev); <= 0 -> 1
uniform int factorU;                // box size in output pixels; <= 0 -> 2
uniform float detailClipU;          // DETAIL_REF 1: the auto exposure ae; <= 0 -> 1
out vec4 Output;

vec3 sceneLinear(ivec2 xy, ivec2 size, bool clampInput) {
    vec3 inColor = texelFetch(InputBuffer, xy, 0).rgb;
    if (clampInput) inColor = max(inColor, vec3(0.0));
    else inColor = vec3(isnan(inColor.r) || isinf(inColor.r) ? 0.0 : inColor.r,
                        isnan(inColor.g) || isinf(inColor.g) ? 0.0 : inColor.g,
                        isnan(inColor.b) || isinf(inColor.b) ? 0.0 : inColor.b);   // as max(., 0) zeroed them
    vec4 gains = textureBicubicHardware(GainMap, vec2(xy) / vec2(size));
    gains.rgb = vec3(gains.r, (gains.g + gains.b) / 2.0, gains.a);
    float lsc = dot(gains.rgb, vec3(1.0 / 3.0));
    vec3 neutral = neutralPointU.r + neutralPointU.g + neutralPointU.b > 0.0 ? neutralPointU : vec3(1.0);
    float scale = inScaleU > 0.0 ? inScaleU : 1.0;
    return intermediateToSRGB * (sensorToIntermediate * (inColor * neutral * (lsc * scale)));
}

void main() {
    int f = factorU > 0 ? factorU : 2;
    ivec2 size = textureSize(InputBuffer, 0);
    ivec2 last = size - ivec2(1);
    ivec2 origin = ivec2(gl_FragCoord.xy) * f;
#if DETAIL_REF == 1
    float ae = detailClipU > 0.0 ? detailClipU : 1.0;
    float sumY = 0.0;
    for (int j = 0; j < f; j++) {
        for (int i = 0; i < f; i++) {
            float y = dot(sceneLinear(min(origin + ivec2(i, j), last), size, true), vec3(0.2126, 0.7152, 0.0722));
            sumY += min(max(y, 0.0) * ae, 1.0);
        }
    }
    Output = vec4(sumY / float(f * f), 0.0, 0.0, 1.0);
#else
    vec3 sum = vec3(0.0);
    for (int j = 0; j < f; j++) {
        for (int i = 0; i < f; i++) {
            sum += sceneLinear(min(origin + ivec2(i, j), last), size, false);
        }
    }
    Output = vec4(sum / float(f * f), 1.0);
#endif
}
