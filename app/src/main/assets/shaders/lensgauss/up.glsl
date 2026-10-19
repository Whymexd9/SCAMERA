precision highp float;
precision highp sampler2D;
// P289 (LensFilter, DiffusionGauss.apply): a level's sum plus the coarser levels' sum bilinearly enlarged (a GL_LINEAR sample
// at the pixel's centre); on level 0 (finalU) the filter itself: (1 - p) E + p (that sum), E's alpha kept.
uniform sampler2D Fine;      // this level's own sum (hasFineU 0: none)
uniform sampler2D Coarse;    // the coarser levels' sum (hasCoarseU 0: none)
uniform sampler2D Image;     // the level's image (its size; on level 0 E)
uniform int hasFineU;
uniform int hasCoarseU;
uniform int finalU;
uniform float pU;
out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec2 size = vec2(textureSize(Image, 0));
    vec3 s = hasFineU == 1 ? texelFetch(Fine, xy, 0).rgb : vec3(0.0);
    if (hasCoarseU == 1) s += texture(Coarse, (vec2(xy) + 0.5) / size).rgb;
    if (finalU == 1) {
        vec4 e = texelFetch(Image, xy, 0);
        Output = vec4((1.0 - pU) * e.rgb + pU * s, e.a);
    } else {
        Output = vec4(s, 1.0);
    }
}
