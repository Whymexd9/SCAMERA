precision highp float;
precision highp sampler2D;
// Post-network denoise, last step. The colour of the full-resolution pixel is replaced by the
// filtered half-resolution colour (joint bilateral upsampling: the four nearest half-resolution
// samples are weighted by how close their luminance is to the pixel's own, so colour edges stay
// on the luminance edges); its luminance follows the non-local-means result. Very dark pixels
// carry no measurable colour (black-level error shows as a tint) and fade to neutral.
uniform sampler2D InputBuffer;
uniform sampler2D Before;    // half resolution colour, unfiltered
uniform sampler2D After;     // half resolution colour, filtered
uniform sampler2D Noisy;     // u = sqrt(Y + c), full resolution
uniform sampler2D Clean;     // the same after non-local means
uniform float offsetC;
uniform float lumaAmount;    // 0..1
uniform float chromaAmount;  // 0..1: 0 keeps the original colour
uniform vec2 darkFade;       // mean level where colour starts to fade / is fully kept
out vec4 Output;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 c = max(texelFetch(InputBuffer, p, 0).rgb, vec3(0.0));
    float ym = max(dot(c, vec3(1.0 / 3.0)), 1.0e-6);
    ivec2 lowSize = textureSize(After, 0);
    vec2 g = (vec2(p) + 0.5) * 0.5 - 0.5;
    ivec2 g0 = ivec2(floor(g));
    vec2 f = g - vec2(g0);
    vec3 acc = vec3(0.0);
    float wsum = 1.0e-6;
    float lp = log2(ym);
    for (int k = 0; k < 4; k++) {
        ivec2 o = ivec2(k & 1, k >> 1);
        ivec2 t = clamp(g0 + o, ivec2(0), lowSize - ivec2(1));
        vec3 a = max(texelFetch(After, t, 0).rgb, vec3(0.0));
        float yb = max(dot(texelFetch(Before, t, 0).rgb, vec3(1.0 / 3.0)), 1.0e-6);
        float wb = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
        float dl = lp - log2(yb);
        float w = wb * exp(-dl * dl / 0.5) + 1.0e-6;
        acc += w * a / max(dot(a, vec3(1.0 / 3.0)), 1.0e-6);
        wsum += w;
    }
    vec3 q = acc / wsum;
    q = mix(vec3(1.0), q, smoothstep(darkFade.x, darkFade.y, ym));
    vec3 chroma = ym * mix(c / ym, q, chromaAmount);
    float un = texelFetch(Noisy, p, 0).r;
    float uc = texelFetch(Clean, p, 0).r;
    float y0 = un * un - offsetC;
    float y1 = uc * uc - offsetC;
    float gain = y0 > 1.0e-6 ? clamp(y1 / y0, 0.25, 4.0) : 1.0;
    Output = vec4(chroma * mix(1.0, gain, lumaAmount), 1.0);
}
