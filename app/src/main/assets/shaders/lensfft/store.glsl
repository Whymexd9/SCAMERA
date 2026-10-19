precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
// P288: the kept middles of a batch's convolved tiles (real parts) plus the coarser levels' sum bilinearly enlarged (a GL
// texture sample, as DiffusionFft.bilinear) into this level's sum; on level 0 the filter itself: (1 - p) E + p (sum).
#define LAYOUT //
LAYOUT

layout(std430, binding = 1) readonly buffer Planes { vec2 planes[]; };
layout(rgba16f, binding = 0) writeonly uniform image2D outImage;
uniform sampler2D Coarse;   // the coarser levels' sum (when hasCoarseU)
uniform sampler2D Image;    // level 0: the image E (when finalU)
uniform ivec2 levelU;
uniform ivec4 tileU;        // x grid, y useful, z radius, w tiles across
uniform int firstTileU;
uniform int hasCoarseU;
uniform int finalU;
uniform float pU;

void main() {
    int g = tileU.x;
    ivec3 id = ivec3(gl_GlobalInvocationID);
    if (id.x >= tileU.y || id.y >= tileU.y) return;
    int tile = firstTileU + id.z;
    ivec2 o = ivec2((tile % tileU.w) * tileU.y, (tile / tileU.w) * tileU.y);
    ivec2 p = o + id.xy;
    if (p.x >= levelU.x || p.y >= levelU.y) return;
    vec3 s;
    for (int c = 0; c < 3; c++) s[c] = planes[((id.z * 3 + c) * g + (id.y + tileU.z)) * g + id.x + tileU.z].x;
    if (hasCoarseU == 1) s += texture(Coarse, (vec2(p) + 0.5) / vec2(levelU)).rgb;
    if (finalU == 1) {
        vec4 e = texelFetch(Image, p, 0);
        imageStore(outImage, p, vec4((1.0 - pU) * e.rgb + pU * s, e.a));
    } else {
        imageStore(outImage, p, vec4(s, 1.0));
    }
}
