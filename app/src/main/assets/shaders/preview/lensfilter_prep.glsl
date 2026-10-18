#extension GL_OES_EGL_image_external_essl3 : require
// P228 / owner, 2026-10-18 (the live filter showed copies of the bright parts): the live lens filter's first pass, at half the
// viewfinder's size, in the screen's layout (main_vs.glsl's quad): the light above the knee (with the highlights' headroom) and
// the linear frame, into two textures whose mip levels are then the blur pyramid (lensfilter_live.glsl samples them as the photo
// samples its G1..G6 / V4..V6). uLfSqrt 1: 8-bit targets, the square root stored (no banding in the dark halo).
precision highp float;
uniform samplerExternalOES sTexture;
uniform bool mirror;
uniform float uGain;
uniform float uLfKnee;
uniform int uLfSqrt;
in vec2 texCoord;
layout(location = 0) out vec4 HiOut;
layout(location = 1) out vec4 LinOut;

vec3 lfLin(vec3 c) { return pow(max(c, vec3(0.0)), vec3(2.2)); }
float lfLuma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

void main() {
    vec2 uv = texCoord.xy;
    if (mirror)
        uv.y = 1.0 - uv.y;
    vec3 d = texture(sTexture, uv).rgb;
    if (uGain != 1.0) d = pow(clamp(lfLin(d) * uGain, 0.0, 1.0), vec3(1.0 / 2.2));
    vec3 lin = lfLin(d);
    // the headroom of a white core (4 x white, as the settings' preview and the tray)
    float t = smoothstep(0.82, 0.92, lfLuma(d));
    vec3 scene = lin * (1.0 + 3.0 * t);
    float y = lfLuma(scene);
    float k = y > uLfKnee ? (y - uLfKnee) / max(y, 1e-4) : 0.0;
    vec3 hi = scene * k;
    // the second target: the scene with its highlights' headroom (the diffusion spreads all the light, a lamp's by its energy)
    if (uLfSqrt == 1) {
        HiOut = vec4(sqrt(clamp(hi * 0.25, 0.0, 1.0)), 1.0);
        LinOut = vec4(sqrt(clamp(scene * 0.25, 0.0, 1.0)), 1.0);
    } else {
        HiOut = vec4(hi, 1.0);
        LinOut = vec4(scene, 1.0);
    }
}
