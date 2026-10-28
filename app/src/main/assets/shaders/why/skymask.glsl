precision highp float;
precision highp int;
precision highp sampler2D;
// P378 (WhySky): the sky mask - the cells' weights of WhySkyMask.decide (SkyCells, bilinear: r the sky, g the clear sky, b the local
// sky level as log2 Y) times the pixel's own gate (utils/import_skygate.glsl skyFine: its luminance from 1.75 stops under the local
// sky level (none) to 1 stop under it (all), so branches, wires, a roof's edge and the facades below a skyline - darker than the
// sky - drop out at their own pixels, while the cloud bases (within a stop of the sky around them) stay in; P378 review: and its
// hue, grey or blue only - a face, a red roof, foliage against the sky drop out too). One mask pixel is boxU x boxU whyLow px (the
// mean of their colour): whyLow itself up to 12 MP, its longer side kept at ~2048 px above (50 MP: 2 x 2, 200 MP: 4 x 4; the
// photo's passes read it by normalised coordinates, bilinear). P277 (a lab fragment): the whole frame's cells at the fragment's
// place, cellOriginU whyLow px in. Output (RG16F): r the sky, g the clear sky.
#import skygate
uniform sampler2D InputBuffer;      // whyLow after bracket_dn (linear Rec.709)
uniform sampler2D SkyCells;         // RGBA16F, one texel a cell, linear filter
uniform vec2 cellScaleU;            // whyLow px -> SkyCells uv: 1 / (the cell's side x the cells)
uniform vec2 cellOriginU;           // P277: the fragment's origin in the whole frame's whyLow px (0, 0: the photo itself)
uniform int boxU;                   // whyLow px along a mask pixel's side; <= 0 -> 1
out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    int k = max(boxU, 1);
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    ivec2 o = xy * k;
    vec4 c = texture(SkyCells, (vec2(o) + 0.5 * float(k) + cellOriginU) * cellScaleU);
    vec3 rgb = vec3(0.0);
    for (int j = 0; j < k; j++)
        for (int i = 0; i < k; i++)
            rgb += max(texelFetch(InputBuffer, min(o + ivec2(i, j), last), 0).rgb, vec3(0.0));
    rgb /= float(k * k);
    float fine = skyFine(rgb, c.b);
    Output = vec4(clamp(c.r, 0.0, 1.0) * fine, clamp(c.g, 0.0, 1.0) * fine, 0.0, 1.0);
}
