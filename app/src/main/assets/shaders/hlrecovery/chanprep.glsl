precision highp float;
precision highp sampler2D;
// Per-channel highlight recovery (SCAM Hybrid), statistics: per block of output pixels, the white-balanced colour of the
// pixels that are bright but unclipped (the light around a clipped highlight) and their weight.
// rgba = (sum of weight * colour, sum of weight), both divided by the number of samples: rgb/a is their mean colour,
// its normalised chromaticity the light's colour, its level the brightness a clipped neighbour must at least reach.
// Reduced 4x4 twice by hlrecovery/reduce (32- and 128-blocks), read by scamhdr/scamrgb (localLight). Statistics only:
// on the Sabre 2x grid the samples are taken one SENSOR pixel apart (pxStepU), so the cost and the neighbourhood are
// those of the sensor grid.
uniform sampler2D InputBuffer;   // worker RGB: camera channels, base-frame white = 1, before WB and lens shading
uniform sampler2D GainMap;
uniform vec3 whitePoint;
uniform vec2 inverseSize;
uniform ivec2 cropOffset;
uniform vec3 clipLoU;            // as in scamhdr/scamrgb
uniform vec3 clipHiU;
uniform int clipHiUnflaggedU;    // as in scamhdr/scamrgb (P58)
uniform int clipFlagsU;
uniform sampler2D ClipFlags;
uniform int blockU;              // output pixels per block (8 * outputScale)
uniform int pxStepU;             // sample spacing in output pixels (outputScale; 1 when unset)
out vec4 Output;

// Same selection as scamhdr/scamrgb levelAt (keep in sync), plus the level the brightness is measured against.
vec3 levelAt(ivec2 p, out vec3 ref) {
    bool lo = clipLoU.g > 0.0, hi = clipHiU.g > 0.0;
    ref = hi ? clipHiU : clipLoU;
    if (clipFlagsU == 0) return ref;
    uint f = uint(texelFetch(ClipFlags, p, 0).r * 255.0 + 0.5);
    if ((f & 16u) == 0u && lo) ref = clipLoU;                // outside the Bento mask: the base frames' white
    if ((f & 15u) == 0u) return clipHiUnflaggedU != 0 && hi && (f & 16u) != 0u ? clipHiU : vec3(-1.0);
    if ((f & 32u) != 0u) return hi ? clipHiU : clipLoU;
    if ((f & 7u) != 0u) return lo ? clipLoU : clipHiU;
    if ((f & 16u) != 0u) return hi ? clipHiU : vec3(-1.0);  // border inside the Bento mask: ultrashort content
    return lo ? clipLoU : clipHiU;
}

void main() {
    int b = max(blockU, 1), s = max(pxStepU, 1);
    ivec2 origin = ivec2(gl_FragCoord.xy) * b;
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    vec3 sumQ = vec3(0.0);
    float sumW = 0.0;
    int n = (b + s - 1) / s;
    for (int j = 0; j < n; j++) {
        for (int i = 0; i < n; i++) {
            ivec2 p = clamp(origin + ivec2(i, j) * s + cropOffset, ivec2(0), last);
            vec3 raw = max(texelFetch(InputBuffer, p, 0).rgb, vec3(0.0));
            vec3 ref;
            vec3 L = levelAt(p, ref);
            // near the clip (censored or clipped means) the colour is not measured: only clearly unclipped pixels
            vec3 near = L.g > 0.0 ? smoothstep(vec3(0.85), vec3(0.95), raw / max(L, vec3(1.0e-6))) : vec3(0.0);
            vec3 rel = raw / max(ref, vec3(1.0e-6));
            float bright = smoothstep(0.08, 0.30, max(max(rel.r, rel.g), rel.b));
            float w = (1.0 - max(max(near.r, near.g), near.b)) * bright;
            if (w <= 0.0) continue;
            vec4 sites = texture(GainMap, vec2(p) * inverseSize);
            vec3 gains = vec3(sites.r, (sites.g + sites.b) * 0.5, sites.a);
            gains /= max(dot(gains, vec3(1.0 / 3.0)), 1.0e-6);
            vec3 c = raw * gains / max(whitePoint, vec3(1.0e-6));
            sumQ += w * c;
            sumW += w;
        }
    }
    float area = float(n * n);
    Output = vec4(sumQ / area, sumW / area);
}
