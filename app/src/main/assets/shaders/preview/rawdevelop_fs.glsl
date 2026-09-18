#version 300 es
// Single-pass RAW development for the viewfinder.
//
// The viewfinder has one frame time to work with, so everything is done in one
// fragment pass with no intermediate targets and no readback: demosaic, black
// and white level, white balance, colour matrix, tone mapping. The shot
// pipeline does the same work across many passes at full quality; this is the
// same chain collapsed to what fits in a frame.
//
// What cannot be here is anything needing the burst - merged noise reduction,
// MFSR detail, bracket highlight recovery. The preview is noisier and softer
// than the photo by construction. Tone placement is what matches, and tone
// placement is what the viewfinder is for.
precision highp float;
precision highp int;
precision highp usampler2D;

uniform usampler2D rawTexture;
uniform int rawWidth;
uniform int rawHeight;
/** 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. */
uniform int cfaPattern;
uniform vec4 blackLevel;
uniform float whiteLevel;
uniform sampler2D lensShading;
uniform vec3 wbGains;
uniform mat3 colorTransform;
/** Exposure gain measured from the scene, before tone mapping. */
uniform float exposureGain;
/** Reinhard white point. */
uniform float whitePoint;
/** Shoulder start; 1.0 disables it. */
uniform float knee;
uniform int mirror;
uniform mat4 texRotate;

in vec2 texCoord;
out vec4 fragColor;

float rawAt(int x, int y) {
    x = clamp(x, 0, rawWidth - 1);
    y = clamp(y, 0, rawHeight - 1);
    return float(texelFetch(rawTexture, ivec2(x, y), 0).r);
}

/**
 * Black level per CFA site, then normalise to [0,1].
 *
 * The four black levels are not interchangeable - sensors routinely differ by a
 * few counts between the two greens - and at preview gains a few counts is a
 * visible colour cast in the shadows.
 */
float normalized(int x, int y, float bl) {
    return max(0.0, (rawAt(x, y) - bl) / max(1.0, whiteLevel - bl));
}

// Camera2 BlackLevelPattern is already in raster-site order (TL, TR, BL, BR).
vec4 phaseLevels() { return blackLevel; }

/**
 * Macropixel demosaic: one RGB sample per 2x2 CFA cell.
 *
 * Half resolution in each direction, which is the right trade here - the
 * viewfinder is about a megapixel and the sensor is twelve, so the output is
 * downsampled regardless. Interpolating at full resolution first would cost
 * four times the fetches to produce detail that is thrown away in the same
 * frame. Both greens are kept and averaged, so the green channel keeps its
 * noise advantage.
 */
vec3 demosaicCell(int cx, int cy) {
    int x = cx * 2;
    int y = cy * 2;
    vec4 bl = phaseLevels();

    float c00 = normalized(x,     y,     bl.x);
    float c10 = normalized(x + 1, y,     bl.y);
    float c01 = normalized(x,     y + 1, bl.z);
    float c11 = normalized(x + 1, y + 1, bl.w);

    // Position of R, the two G and B within the cell follows from the pattern.
    if (cfaPattern == 0) return vec3(c00, (c10 + c01) * 0.5, c11);           // RGGB
    if (cfaPattern == 1) return vec3(c10, (c00 + c11) * 0.5, c01);           // GRBG
    if (cfaPattern == 2) return vec3(c01, (c00 + c11) * 0.5, c10);           // GBRG
    return vec3(c11, (c10 + c01) * 0.5, c00);                                // BGGR
}

/** Matches AutoExposureCurve.softShoulder, so preview and photo roll off alike. */
float softShoulder(float x, float k) {
    if (x <= k) return x;
    float t = x - k;
    float s = 1.0 - k;
    if (s <= 1.0e-4) return min(x, 1.0);
    float g = t / s;
    return k + s * g * g;
}

/** sRGB OETF. */
float srgbEncode(float v) {
    v = max(v, 0.0);
    return v <= 0.0031308 ? v * 12.92 : 1.055 * pow(v, 1.0 / 2.4) - 0.055;
}

void main() {
    vec2 uv = (texRotate * vec4(texCoord, 0.0, 1.0)).xy;
    if (mirror == 1) uv.x = 1.0 - uv.x;
    uv = clamp(uv, 0.0, 1.0);

    int cellsX = rawWidth / 2;
    int cellsY = rawHeight / 2;
    int cx = clamp(int(uv.x * float(cellsX)), 0, cellsX - 1);
    int cy = clamp(int(uv.y * float(cellsY)), 0, cellsY - 1);

    vec3 rgb = demosaicCell(cx, cy);

    // White balance before the matrix, as in the shot path: the matrix is
    // defined for balanced input.
    rgb *= texture(lensShading, uv).rgb * wbGains;
    rgb = colorTransform * rgb;
    rgb = max(rgb, vec3(0.0));

    // Extended Reinhard with the same form the shot's exposure curve uses, so
    // the two place midtones the same way.
    vec3 r = rgb * exposureGain;
    float w2 = max(whitePoint * whitePoint, 1.0e-4);
    r = r * (vec3(1.0) + r / w2) / (vec3(1.0) + r);

    vec3 outRgb = vec3(srgbEncode(r.r), srgbEncode(r.g), srgbEncode(r.b));
    if (knee < 1.0) {
        outRgb = vec3(softShoulder(outRgb.r, knee),
                      softShoulder(outRgb.g, knee),
                      softShoulder(outRgb.b, knee));
    }

    fragColor = vec4(clamp(outRgb, 0.0, 1.0), 1.0);
}
