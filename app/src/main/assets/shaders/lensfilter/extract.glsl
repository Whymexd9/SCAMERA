precision highp float;
precision highp sampler2D;
// P228 lens filters: the light above the knee (its colour kept), at half the input size (a 2x2 mean) - the
// source of the glow pyramid and the streaks. Linear scene-referred RGB, highlights above 1 keep their energy.
uniform sampler2D InputBuffer;
uniform float knee;
out vec4 Output;
vec3 hi(ivec2 p, ivec2 last) {
    vec3 c = max(texelFetch(InputBuffer, clamp(p, ivec2(0), last), 0).rgb, vec3(0.0));
    float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float k = y > knee ? (y - knee) / max(y, 1e-6) : 0.0;
    return c * k;
}
void main() {
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    ivec2 p = ivec2(gl_FragCoord.xy) * 2;
    vec3 s = hi(p, last) + hi(p + ivec2(1, 0), last) + hi(p + ivec2(0, 1), last) + hi(p + ivec2(1, 1), last);
    Output = vec4(s * 0.25, 1.0);
}
