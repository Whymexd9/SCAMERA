precision highp float;
precision highp sampler2D;
// P288: a level without components of its own: the coarser levels' sum bilinearly enlarged to it (on level 0, finalU: the
// filter (1 - p) E + p (that sum)).
uniform sampler2D Coarse;
uniform sampler2D Image;
uniform int finalU;
uniform float pU;
out vec4 Output;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 size = vec2(textureSize(Image, 0));
    vec3 s = texture(Coarse, (vec2(p) + 0.5) / size).rgb;
    if (finalU == 1) {
        vec4 e = texelFetch(Image, p, 0);
        Output = vec4((1.0 - pU) * e.rgb + pU * s, e.a);
    } else {
        Output = vec4(s, 1.0);
    }
}
