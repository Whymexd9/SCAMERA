precision highp float;
precision highp sampler2D;
// ARK tone (ArkSharpenGuard): the sharpening after ArkCombine is weakened where the fusion lifted the shadows. ArkCam
// sharpens its guide before the detail delta, so the lift never multiplies sharpened noise; here the post-tone
// sharpener (RawTherapee or SCAM) runs on the toned image and its change is scaled back by the weight ArkCombine
// stored in alpha (gain^-ark_sharp_guard, 1 where nothing was lifted). Output alpha is 1.
uniform sampler2D InputBuffer;  // sharpened image
uniform sampler2D PreBuffer;    // ArkCombine output before sharpening: rgb, a = sharpening weight
out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec4 pre = texelFetch(PreBuffer, xy, 0);
    vec3 sharp = texelFetch(InputBuffer, xy, 0).rgb;
    Output = vec4(mix(pre.rgb, sharp, clamp(pre.a, 0.0, 1.0)), 1.0);
}
