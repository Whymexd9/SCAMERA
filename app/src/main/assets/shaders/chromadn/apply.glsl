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
uniform sampler2D Coarse;    // quarter resolution correction of the blotch-scale residue
uniform float sigma;         // noise sigma of u (0 = off)
uniform float grain;         // share of the removed noise put back (a flat, fine grain), 0..1
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
    // Flat level = non-local means plus the coarse correction; the removed noise is put back
    // partly so the grain that stays is fine and even at every scale (a denoiser that leaves
    // only its own mid-frequency residue looks blotchy).
    vec2 cs = (vec2(p) + 0.5) / vec2(textureSize(InputBuffer, 0));
    ivec2 lastC = textureSize(Clean, 0) - ivec2(1);
    float gx = abs(texelFetch(Clean, clamp(p + ivec2(3, 0), ivec2(0), lastC), 0).r - texelFetch(Clean, clamp(p - ivec2(3, 0), ivec2(0), lastC), 0).r);
    float gy = abs(texelFetch(Clean, clamp(p + ivec2(0, 3), ivec2(0), lastC), 0).r - texelFetch(Clean, clamp(p - ivec2(0, 3), ivec2(0), lastC), 0).r);
    // Only flat areas get the coarse correction and the tail cap; at edges the quarter-resolution
    // correction would leave a stair-stepped rim.
    float flatness = sigma > 0.0 ? 1.0 - smoothstep(3.0 * sigma, 6.0 * sigma, max(gx, gy)) : 0.0;
    float uf = texelFetch(Clean, p, 0).r + flatness * texture(Coarse, cs).r;
    float d = un - uf;
    // Dark specks: black-level clipping gives the noise of the darkest areas a heavy dark tail;
    // cap the dark side at about two sigma where the level is flat.
    d = mix(d, max(d, -2.0 * sigma), flatness);
    float uc = uf + grain * d;
    float y0 = un * un - offsetC;
    float y1 = uc * uc - offsetC;
    float gain = y0 > 1.0e-6 ? clamp(y1 / y0, 0.25, 4.0) : 1.0;
    Output = vec4(chroma * mix(1.0, gain, lumaAmount), 1.0);
}
