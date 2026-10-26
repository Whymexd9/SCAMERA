// P351: the proxy's moments for the guided filters: x = clamp(log2 Y, -12, 4) + CENTRE, x^2, and the dark channel of the colour
// over the white point (dehaze).
uniform sampler2D uIn;
uniform vec2 uTexel;
uniform vec3 uAir;
out vec4 o;

void main() {
    vec3 c = developColour(texture(uIn, gl_FragCoord.xy * uTexel).rgb);
    float x = clamp(log2(max(dot(c, LUMA), 1e-6)), -12.0, 4.0) + CENTRE;
    vec3 r = c / max(uAir, vec3(1e-6));
    o = vec4(x, x * x, clamp(min(r.r, min(r.g, r.b)), 0.0, 4.0), 1.0);
}
