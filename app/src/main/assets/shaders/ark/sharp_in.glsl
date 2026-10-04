precision highp float;
precision highp sampler2D;
// ARK luma sharpening (ArkLumaSharpen), step 1: the guide that ArkCam's process_luma_fp16 sharpens [libfc_suppressor
// 0x7c5c4; research/hybrid5/ark_sharpen.md 1, 6.2]: Ya = clamp(mulU * Y709(sceneLinear), 0, 1) on the output grid.
// mulU 1 = the G_CLEAN scale of Google's guide a3 (ark_sharp_domain 1), mulU ae = the domain of the detail delta
// (ark_sharp_domain 0). Keep sceneLinear() identical to ark/low.glsl and ark/combine.glsl.
#import interpolation
uniform sampler2D InputBuffer;      // white-balanced linear camera RGB (the merge after denoising), output grid
uniform sampler2D GainMap;          // lens shading gains (r, g_even, g_odd, b)
uniform mat3 sensorToIntermediate;
uniform mat3 intermediateToSRGB;
uniform vec3 neutralPointU;         // unset -> 1
uniform float inScaleU;             // <= 0 -> 1
uniform float mulU;                 // domain multiplier; <= 0 -> 1
out vec4 Output;

vec3 sceneLinear(ivec2 xy, ivec2 size) {
    vec3 inColor = max(texelFetch(InputBuffer, xy, 0).rgb, vec3(0.0));
    vec4 gains = textureBicubicHardware(GainMap, vec2(xy) / vec2(size));
    gains.rgb = vec3(gains.r, (gains.g + gains.b) / 2.0, gains.a);
    float lsc = dot(gains.rgb, vec3(1.0 / 3.0));
    vec3 neutral = neutralPointU.r + neutralPointU.g + neutralPointU.b > 0.0 ? neutralPointU : vec3(1.0);
    float scale = inScaleU > 0.0 ? inScaleU : 1.0;
    return intermediateToSRGB * (sensorToIntermediate * (inColor * neutral * (lsc * scale)));
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    float m = mulU > 0.0 ? mulU : 1.0;
    float y = dot(sceneLinear(xy, textureSize(InputBuffer, 0)), vec3(0.2126, 0.7152, 0.0722));
    Output = vec4(clamp(y * m, 0.0, 1.0), 0.0, 0.0, 1.0);
}
