// P351: the analysis proxy (512 px on the long side): each texel the mean of an n x n grid over its footprint in the source.
uniform sampler2D uIn;
uniform vec2 uInSize;    // source pixels
uniform vec2 uOutSize;
uniform int uN;
out vec4 o;

void main() {
    vec2 scale = uInSize / uOutSize;
    vec2 base = (gl_FragCoord.xy - 0.5) * scale;
    vec4 s = vec4(0.0);
    for (int j = 0; j < uN; ++j)
        for (int i = 0; i < uN; ++i) {
            vec2 p = base + (vec2(float(i), float(j)) + 0.5) * scale / float(uN);
            s += texture(uIn, p / uInSize);
        }
    o = s / float(uN * uN);
}
