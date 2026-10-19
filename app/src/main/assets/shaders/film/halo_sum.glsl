precision highp float;
precision highp sampler2D;
// P286: the reference model's halation bounces summed (FilmHalation): sum_k w_k G(sigma_h sqrt(k)) * E, the three blurred
// levels already at one size, w = 1, 1/2, 1/4 over their sum.
uniform sampler2D B1;
uniform sampler2D B2;
uniform sampler2D B3;
out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec3 h = (texelFetch(B1, xy, 0).rgb + 0.5 * texelFetch(B2, xy, 0).rgb + 0.25 * texelFetch(B3, xy, 0).rgb) / 1.75;
    Output = vec4(h, 1.0);
}
