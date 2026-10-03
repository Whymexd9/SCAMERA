precision highp float;
precision highp sampler2D;
// ARK tone (ArkFusion): guided-filter coefficients [fc_cl_source.cl:1343-1372 gf_compute_ab] per weight channel k:
// a = cov(I, p_k) / (var I + eps), b = mean p_k - a * mean I (MODE 0 writes a, MODE 1 writes b).
#define MODE 0
uniform sampler2D MeanII;   // box mean of (I, I*I)
uniform sampler2D MeanP;    // box mean of the weights p
uniform sampler2D MeanIP;   // box mean of p * I
uniform float epsU;         // max(ef_gf_eps, 1e-6) (ArkCam 0.001); <= 0 -> 0.001
out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec2 m = texelFetch(MeanII, xy, 0).xy;
    float varI = max(m.y - m.x * m.x, 0.0);
    vec4 mp = texelFetch(MeanP, xy, 0);
    vec4 cov = texelFetch(MeanIP, xy, 0) - m.x * mp;
    vec4 a = cov / (varI + (epsU > 0.0 ? epsU : 0.001));
#if MODE == 0
    Output = a;
#else
    Output = mp - a * m.x;
#endif
}
