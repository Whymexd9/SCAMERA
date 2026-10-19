precision highp float;
precision highp int;
precision highp sampler2D;
// P288 (LensFilter, DiffusionFft.convolve): the tiles of a batch into the FFT planes - plane z = tile * 3 + channel, each a
// grid x grid complex plane ([y][x]); grid cell (gx, gy) holds the level's pixel (ox - radius + gx, oy - radius + gy) reflected
// at the edges (numpy 'reflect'), the cells past useful + 2 radius zero (the kernel never reaches them from the kept middle).
#define LAYOUT //
LAYOUT

layout(std430, binding = 1) writeonly buffer Planes { vec2 planes[]; };
uniform sampler2D Level;
uniform ivec2 levelU;       // the level's size
uniform ivec4 tileU;        // x grid, y useful, z radius, w tiles across
uniform int firstTileU;     // the batch's first tile

int reflectIndex(int i, int n) {
    if (n == 1) return 0;
    int period = 2 * (n - 1);
    i = i % period;
    if (i < 0) i += period;
    return i < n ? i : period - i;
}

void main() {
    int g = tileU.x;
    ivec3 id = ivec3(gl_GlobalInvocationID);
    if (id.x >= g || id.y >= g) return;
    int tile = firstTileU + id.z / 3, c = id.z % 3;
    int ox = (tile % tileU.w) * tileU.y, oy = (tile / tileU.w) * tileU.y;
    float v = 0.0;
    int span = tileU.y + 2 * tileU.z;
    if (id.x < span && id.y < span) {
        ivec2 p = ivec2(reflectIndex(ox - tileU.z + id.x, levelU.x), reflectIndex(oy - tileU.z + id.y, levelU.y));
        v = texelFetch(Level, p, 0)[c];
    }
    planes[(id.z * g + id.y) * g + id.x] = vec2(v, 0.0);
}
