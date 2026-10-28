precision highp float;
precision highp sampler2D;

// Ultra HDR scene-luma plane: linear scene luminance sampled from the captured
// post-demosaic buffer, with the exact same mirror / rotate / crop transform
// the stored SDR base went through (coordinate logic mirrors
// addwatermark_rotate.glsl) so the plane is pixel-aligned with the base image.
//
// This is the pre-local-tone-map scene energy: fusion LTM compresses
// highlights below display white inside the rendering chain, so the
// recoverable highlight headroom only exists here. No clamp - scene luminance
// may legitimately reach/exceed 1.0.

uniform sampler2D InputBuffer;
uniform sampler2D GainMap;
uniform int rotate;
uniform bool mirror;
uniform ivec2 cropSize;
uniform ivec2 rawSize;
// P370 (B3): the luminance weights of the white-balanced camera RGB the plane reads - LUMA^T . intermediateToSRGB .
// sensorToProPhoto . diag(neutral) (PostPipeline: the render's own colour matrix): Rec.709 weights on camera RGB gave the
// HDR boost by hue (the X8U's blue +0.41 EV, orange -0.14 EV). Unset (0): Rec.709 on the input as before.
uniform vec3 wU;
// P378 review (Ultra HDR keeps the sky term's change): the SDR's own ratio of the sky term (WhyCombine: why/combine at whyLow with
// and without it, why/skyratio; sensor layout as InputBuffer, normalised coordinates, linear filter) - the plane takes it, so the
// HDR rendition is the lowered SDR sky times the scene's headroom over the SDR before the term, not the scene's old light. 0 /
// unset: none (the plane as before).
uniform sampler2D SkyRatio;
uniform int skyRatioU;

out vec4 Output;

#define lum709(x) dot(x, vec3(0.2126, 0.7152, 0.0722))
#import interpolation

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 texSize = ivec2(textureSize(InputBuffer, 0));
    ivec2 srcCoord;
    vec3 c;
    switch (rotate) {
        case 0:
            xy += ivec2(0, (rawSize.y - cropSize.y));
            // P387: the last texel is size - 1, as addwatermark_rotate.glsl (the plane stays aligned with the base)
            if (mirror)
                xy.y = texSize.y - 1 - xy.y;
            srcCoord = xy;
            c = texelFetch(InputBuffer, srcCoord, 0).rgb;
            break;
        case 1:
            xy += ivec2((rawSize.y - cropSize.y), 0);
            if (mirror)
                xy.x = cropSize.y - 1 - xy.x;
            srcCoord = ivec2(texSize.x - 1 - xy.y, xy.x);
            c = texelFetch(InputBuffer, srcCoord, 0).rgb;
            break;
        case 2:
            if (mirror)
                xy.y = texSize.y - 1 - xy.y;
            srcCoord = ivec2(texSize.x - 1 - xy.x, texSize.y - 1 - xy.y);
            c = texelFetch(InputBuffer, srcCoord, 0).rgb;
            break;
        case 3:
            if (mirror)
                xy.x = cropSize.y - 1 - xy.x;
            srcCoord = ivec2(xy.y, texSize.y - 1 - xy.x);
            c = texelFetch(InputBuffer, srcCoord, 0).rgb;
            break;
        default:
            srcCoord = min(xy, texSize - 1);
            c = texelFetch(InputBuffer, srcCoord, 0).rgb;
            break;
    }
    // Lens-shading vignetting (must match Initial's luminance correction):
    // tofloat.glsl normalises its GainMap fetch to avg 1 (chromatic only), so
    // the archived post-demosaic buffer still carries the achromatic falloff.
    // Initial restores it via gainsVal = dot(avg_gains) on the SDR path; we
    // must do the same here so L and SDR are on the same flat field before
    // the gainmap ratio and median anchoring.
    vec4 gains = textureBicubicHardware(GainMap, vec2(srcCoord) / vec2(rawSize));
    gains.rgb = vec3(gains.r, (gains.g + gains.b) / 2.0, gains.a);
    float gainsVal = dot(gains.rgb, vec3(1.0 / 3.0));
    float l = (wU == vec3(0.0) ? lum709(max(c, vec3(0.0))) : max(dot(wU, c), 0.0)) * gainsVal;
    if (skyRatioU == 1) l *= max(texture(SkyRatio, (vec2(srcCoord) + 0.5) / vec2(texSize)).r, 0.0);
    Output = vec4(l, l, l, 1.0);
}
