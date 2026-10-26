// P351: the source in linear light (white balance, camera matrix, exposure: EditMath.develop); alpha = log2 Y.
uniform sampler2D uIn;
uniform vec2 uTexel;
out vec4 o;

void main() {
    vec3 c = developColour(texture(uIn, gl_FragCoord.xy * uTexel).rgb);
    o = vec4(c, log2(max(dot(c, LUMA), 1e-6)));
}
