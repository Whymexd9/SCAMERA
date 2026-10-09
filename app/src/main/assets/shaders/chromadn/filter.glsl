precision highp float;
precision highp sampler2D;
// Colour noise removal, step 2 (ScamHdrDenoise runs it twice, stepU 1 then 2): 5x5 dilated bilateral
// filter of the colour at half resolution. A neighbour counts when its colour ratios differ by
// no more than the colour noise expected at that brightness and its luminance is close; the
// pixel keeps its own luminance. Averaging is done on the linear values, not on the ratios, so
// dim pixels weigh less. The input may be signed (box means of the signed hybrid RGB, chromadn/down): the weights and
// ratios use the values clamped at zero, the average itself the signed values, clamped once after averaging.
uniform sampler2D InputBuffer;
uniform int stepU;           // dilation of the 5x5 taps (named stepU: `step` is a GLSL built-in)
uniform float strength;   // 0..1 blend towards the filtered colour
uniform float tolerance;  // colour-noise multiplier (1 = three times the relative luminance noise)
uniform float sigmaU;     // noise sigma of u = sqrt(Y + offsetC)
uniform float offsetC;
out vec4 Output;
void main() {
    int dil = max(stepU, 1); // unset uniform (0) = step 1
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    vec3 c0 = max(texelFetch(InputBuffer, p, 0).rgb, vec3(0.0));     // own level and colour: clamped
    float y0 = max(dot(c0, vec3(1.0 / 3.0)), 1.0e-6);
    vec3 q0 = c0 / y0;
    vec3 sum = vec3(0.0);
    float mass = 0.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            vec3 cs = texelFetch(InputBuffer, clamp(p + ivec2(i, j) * dil, ivec2(0), last), 0).rgb;
            vec3 c = max(cs, vec3(0.0));
            float y = max(dot(c, vec3(1.0 / 3.0)), 1.0e-6);
            vec3 d = c / y - q0;
            // Colour noise is about three times the relative luminance noise (white balance and the
            // colour matrix amplify red and blue); in bright areas that is a percent or two, so real
            // colour transitions (a shadow on a lit wall) are not merged.
            float rel = 2.0 * sigmaU * sqrt(y + offsetC) / max(y, 1.0e-5);
            float sigma = clamp(3.0 * tolerance * rel, 0.02, 0.5);
            float dl = log2(y / y0);
            float w = exp(-float(i * i + j * j) * 0.22 - dot(d, d) / (2.0 * sigma * sigma) - dl * dl / 0.72);
            sum += cs * w;
            mass += w;
        }
    }
    vec3 f = max(sum / max(mass, 1.0e-8), vec3(0.0));
    vec3 qf = f / max(dot(f, vec3(1.0 / 3.0)), 1.0e-6);
    Output = vec4(y0 * mix(q0, qf, strength), 1.0);
}
