precision highp float;
precision highp sampler2D;
precision highp usampler2D;
// Post-network denoise, last pxStep. The colour of the full-resolution pixel is replaced by the
// filtered half-resolution colour (joint bilateral upsampling: the four nearest half-resolution
// samples are weighted by how close their luminance is to the pixel's own, so colour edges stay
// on the luminance edges); its luminance follows the non-local-means result. Very dark pixels
// carry no measurable colour (black-level error shows as a tint) and fade to neutral.
// Signed input (LMC hybrid: nicergb signedU, signedU 1 here): the 3x3 colour averages the signed values and clamps the
// mean, so a channel near zero is not lifted by a clamp per pixel. A pixel with a negative channel is not clamped either:
// its luminance is the non-local-means result (chromadn/luma took its signed luminance) or, without the luma pass, its own
// signed luminance; its colour difference is the filtered colour's (chroma pass) or its own signed one (chroma 0). Such a
// pixel may stay negative: the hybrid clips once, after averaging, downstream (ark/low, ark/combine signedColourU), and
// PostPipeline.signedRgb stays set. A pixel without a negative channel takes the former formula and comes out >= 0 (its
// non-local-means luminance may see signed neighbours); input without negative values, and signedU 0 (SCAM HDR), give the
// former output bit for bit (tools/check_signed_rgb.py).
uniform sampler2D InputBuffer;
uniform sampler2D Before;    // half resolution colour, unfiltered
uniform sampler2D After;     // half resolution colour, filtered
uniform sampler2D Noisy;     // u = sqrt(Y + c), full resolution
uniform sampler2D Clean;     // the same after non-local means
uniform sampler2D Coarse;    // quarter resolution correction of the blotch-scale residue
uniform float sigma;         // noise sigma of u (0 = off)
uniform usampler2D EffMap;   // effective merged frames per pixel (1/8 frame steps, 0 = unknown)
uniform float effRef;
uniform float effMax;       // upper clamp of the noise boost (hybrid: Bento denoise limit setting)
uniform int useEff;
uniform float grain;         // share of the removed noise put back (a flat, fine grain), 0..1
uniform float offsetC;
uniform float lumaAmount;    // 0..1
uniform float chromaAmount;  // 0..1: 0 keeps the original colour
uniform vec2 darkFade;       // mean level where colour starts to fade / is fully kept
uniform vec2 darkChroma;     // colour deviation |RGB - mean| from which a dark colour starts to stay / stays fully
                             // (signed hybrid input; 0 = off, the fade of the luminance alone)
uniform int pxStepU;            // outputScale: dilates the fixed 3x3 and +-3 px windows to sensor-pixel units
uniform float lowRatio;      // full-resolution pixels per Before/After texel (2 * outputScale)
uniform int signedU;         // 1: signed input (PostPipeline.signedRgb), see above
out vec4 Output;
void main() {
    int pxStep = max(pxStepU, 1); // unset uniform (0) = 1x behaviour
    ivec2 p = ivec2(gl_FragCoord.xy);
    // Noise of this pixel: the measured level scaled by how well the merge covered it, one-sided (W3.5, as chromadn/nlm).
    float sg = sigma;
    if (useEff != 0 && sigma > 0.0) {
        uint v = texelFetch(EffMap, p, 0).r;
        if (v > 0u && float(v) * 0.125 < effRef) sg = sigma * clamp(sqrt(effRef / (float(v) * 0.125)), 1.0, (effMax > 0.0 ? effMax : 3.0));
    }
    vec3 cIn = texelFetch(InputBuffer, p, 0).rgb; // signed with signedU 1
    vec3 c = max(cIn, vec3(0.0));
    bool negative = signedU != 0 && any(lessThan(cIn, vec3(0.0)));
    float ym = max(dot(c, vec3(1.0 / 3.0)), 1.0e-6);
    ivec2 lowSize = textureSize(After, 0);
    vec2 g = (vec2(p) + 0.5) / (lowRatio > 0.0 ? lowRatio : 2.0) - 0.5; // unset = 2:1
    ivec2 g0 = ivec2(floor(g));
    vec2 f = g - vec2(g0);
    // The pixel's colour averaged over 3x3 (luminance weighted) has a third of the single-pixel
    // colour noise, so a coherent coloured shape a few pixels wide stands out of it where a
    // single pixel would not.
    vec3 own = vec3(1.0);
    float tolC = 1.0;
    if (sigma > 0.0) {
        ivec2 lastI = textureSize(InputBuffer, 0) - ivec2(1);
        vec3 s3 = vec3(0.0);
        float w3 = 0.0;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                float wgt = ((i == 0) ? 1.0 : 0.3614) * ((j == 0) ? 1.0 : 0.3614);
                s3 += wgt * texelFetch(InputBuffer, clamp(p + ivec2(i, j) * pxStep, ivec2(0), lastI), 0).rgb;
                w3 += wgt;
            }
        }
        s3 = max(s3, vec3(0.0));
        own = s3 / max(dot(s3, vec3(1.0 / 3.0)), 1.0e-6);
        float relN = 2.0 * sg * sqrt(ym + offsetC) / ym;
        tolC = clamp(3.0 * relN, 0.02, 0.5) * 1.7 * 0.42;
    }
    vec3 acc = vec3(0.0);
    float wsum = 1.0e-6;
    float lp = log2(ym);
    for (int k = 0; k < 4; k++) {
        ivec2 o = ivec2(k & 1, k >> 1);
        ivec2 t = clamp(g0 + o, ivec2(0), lowSize - ivec2(1));
        vec3 a = max(texelFetch(After, t, 0).rgb, vec3(0.0));
        vec3 bc = max(texelFetch(Before, t, 0).rgb, vec3(0.0));
        float yb = max(dot(bc, vec3(1.0 / 3.0)), 1.0e-6);
        float wb = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
        float dl = lp - log2(yb);
        float w = wb * exp(-dl * dl / 0.5);
        // Colour edges that are not luminance edges (white beside light blue, red beside green of the
        // same brightness) carry no weight in the luminance term: a sample of the other colour must
        // not be mixed in. Its unfiltered colour is compared with the pixel's own (both normalised):
        // clearly different beyond the colour noise means the other side of a colour edge.
        if (sigma > 0.0) {
            float dc = length(own - bc / yb) / tolC;
            w *= exp(-max(dc - 1.5, 0.0) * 0.7);
        }
        w += 1.0e-6;
        acc += w * a / max(dot(a, vec3(1.0 / 3.0)), 1.0e-6);
        wsum += w;
    }
    vec3 q = acc / wsum;
    // Colour detail: the half-resolution filter dilutes thin coloured strokes (red lettering on a
    // yellow card). Where the pixel's own colour differs from the filtered one by clearly more than
    // the colour noise expected at its brightness, it is real structure and the pixel keeps its
    // own colour; flat noise stays filtered.
    if (sigma > 0.0) {
        float dist = length(own - q) / tolC;
        float keep = smoothstep(1.6, 3.0, dist);
        q = mix(q, own, keep);
    }
    // q has mean 1, so ym * (q - 1) is the colour's deviation from neutral in linear RGB: a colour clearly stronger than
    // a black-level tint (a dark teal curtain) is kept.
    float fadeT = smoothstep(darkFade.x, darkFade.y, ym);
    if (darkChroma.y > 0.0) fadeT = max(fadeT, smoothstep(darkChroma.x, darkChroma.y, ym * length(q - vec3(1.0))));
    q = mix(vec3(1.0), q, fadeT);
    vec3 chroma = ym * mix(c / ym, q, chromaAmount);
    // Replacing the colour must not move the luminance: a chroma taken from a strongly coloured
    // neighbour (equal mean, different luminance weights) turned pixels at colour edges dark.
    const vec3 lw = vec3(0.2126, 0.7152, 0.0722);
    chroma *= clamp(dot(c, lw) / max(dot(chroma, lw), 1.0e-6), 0.5, 2.0);
    float un = texelFetch(Noisy, p, 0).r;
    // Flat level = non-local means plus the coarse correction; the removed noise is put back
    // partly so the grain that stays is fine and even at every scale (a denoiser that leaves
    // only its own mid-frequency residue looks blotchy).
    vec2 cs = (vec2(p) + 0.5) / vec2(textureSize(InputBuffer, 0));
    ivec2 lastC = textureSize(Clean, 0) - ivec2(1);
    float gx = abs(texelFetch(Clean, clamp(p + ivec2(3, 0) * pxStep, ivec2(0), lastC), 0).r - texelFetch(Clean, clamp(p - ivec2(3, 0) * pxStep, ivec2(0), lastC), 0).r);
    float gy = abs(texelFetch(Clean, clamp(p + ivec2(0, 3) * pxStep, ivec2(0), lastC), 0).r - texelFetch(Clean, clamp(p - ivec2(0, 3) * pxStep, ivec2(0), lastC), 0).r);
    // Only flat areas get the coarse correction and the tail cap; at edges the quarter-resolution
    // correction would leave a stair-stepped rim.
    float flatness = sigma > 0.0 ? 1.0 - smoothstep(3.0 * sg, 6.0 * sg, max(gx, gy)) : 0.0;
    float uf = texelFetch(Clean, p, 0).r + flatness * texture(Coarse, cs).r;
    if (isnan(uf) || isinf(uf)) uf = un;
    float d = un - uf;
    // Dark specks: black-level clipping gives the noise of the darkest areas a heavy dark tail;
    // cap the dark side at about two sigma where the level is flat.
    d = mix(d, max(d, -2.0 * sg), flatness);
    // Fine structure: the non-local means flattens low-contrast texture (lettering, weave, wood
    // grain) together with the noise. The residue (noisy - clean) smoothed over 3x3 keeps what is
    // spatially coherent and averages the white noise down to about a third, so where the smoothed
    // residue is clearly above the noise it is real texture and goes back in.
    float detail = 0.0;
    if (sigma > 0.0) {
        ivec2 lastN = textureSize(Noisy, 0) - ivec2(1);
        float acc9 = 0.0;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                ivec2 t = clamp(p + ivec2(i, j) * pxStep, ivec2(0), lastN);
                float wgt = ((i == 0) ? 1.0 : 0.3614) * ((j == 0) ? 1.0 : 0.3614);
                acc9 += wgt * clamp(texelFetch(Noisy, t, 0).r - texelFetch(Clean, clamp(t, ivec2(0), lastC), 0).r, -3.0 * sg, 3.0 * sg);
            }
        }
        float rb = acc9 / 2.9588;
        float mk = smoothstep(0.7 * sg, 1.6 * sg, abs(rb));
        detail = (1.0 - grain) * mk * rb;
    }
    float uc = uf + grain * d + detail;
    if (isnan(uc) || isinf(uc)) uc = un;
    float y0 = un * un - offsetC;
    float y1 = uc * uc - offsetC;
    float gain = y0 > 1.0e-6 ? clamp(y1 / y0, 0.25, 4.0) : 1.0;
    if (negative) {
        // signed pixel (see the head): luminance and colour difference added, no clamp
        float ys = dot(cIn, lw);
        float yt = mix(ys, y1, lumaAmount);
        vec3 diff = chromaAmount > 0.0 ? max(yt, 0.0) * (q / max(dot(q, lw), 1.0e-6) - vec3(1.0)) : cIn - vec3(ys);
        Output = vec4(vec3(yt) + diff, 1.0);
        return;
    }
    Output = vec4(chroma * mix(1.0, gain, lumaAmount), 1.0);
}
