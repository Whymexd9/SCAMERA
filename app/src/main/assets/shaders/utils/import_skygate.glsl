// P378 (WhySky / LiveWhyTone; owner, 2026-10-28: «Нет, только небо»): the sky mask's own gate of a pixel - why/skymask.glsl per
// whyLow pixel (the photo), why/combine.glsl SKY 1 with POINT_FUSED 1 per tone pixel (the viewfinder: its mask is the decision's
// cells, LiveWhyTone). skyHue is WhySkyMask.colourGate on the pixel's colour: grey (OKLab chroma under 0.04 .. 0.08 at a fixed
// lightness) or blue, no yellow-orange (b over 0.008 .. 0.02) or red-magenta (a over 0.015 .. 0.03) cast - P378 review: a
// front-lit face against the sky took the sky term (x0.88); the clouds' own cast (within +0.002 .. 0.01) stays in. skyFine adds
// the luminance gate against the local sky level (log2 Y): from 1.75 stops under it (none) to 1 stop under it (all) - branches,
// wires, a roof's edge and the facades below a skyline drop out at their own pixels, the cloud bases stay in.
float skyHue(vec3 c) {
    c = max(c, vec3(0.0));
    float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
    if (!(y > 0.0) || !(y < 1.0e30)) return 0.0;
    vec3 n = c * (0.5 / y);
    vec3 lms = vec3(dot(n, vec3(0.4122214708, 0.5363325363, 0.0514459929)), dot(n, vec3(0.2119034982, 0.6806995451, 0.1073969566)),
                    dot(n, vec3(0.0883024619, 0.2817188376, 0.6299787005)));
    lms = pow(max(lms, vec3(0.0)), vec3(1.0 / 3.0));
    float a = 1.9779984951 * lms.x - 2.4285922050 * lms.y + 0.4505937099 * lms.z;
    float b = 0.0259040371 * lms.x + 0.7827717662 * lms.y - 0.8086757660 * lms.z;
    float chroma = length(vec2(a, b));
    float blue = clamp(-b / max(chroma, 0.000001), 0.0, 1.0);
    return (1.0 - smoothstep(0.04, 0.08, chroma) * (1.0 - smoothstep(0.5, 0.8, blue))) * (1.0 - smoothstep(0.008, 0.02, b))
           * (1.0 - smoothstep(0.015, 0.03, a));
}

float skyFine(vec3 c, float level) {
    float y = dot(max(c, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722));
    // a non-finite pixel is no sky
    if (!(y < 1.0e30)) return 0.0;
    return smoothstep(level - 1.75, level - 1.0, log2(max(y, 0.000001))) * skyHue(c);
}
