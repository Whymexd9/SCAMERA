// P318: the DNG of a Quad / Tetra mosaic (the merged mosaic DNG without our remosaic, a mosaic stream's reference frame)
// declares its colour filter over the blocks: CFARepeatPatternDim 2b x 2b, CFAPattern the 2x2 order per block, the black
// level and every lens-shading GainMap site by block - for each of the four CFA orders and blocks 1, 2, 4. Also the colour
// tags of every order: setCFAPattern wrote a 4x4 expansion into its 4-byte array, over the has_* flags after it (BGGR lost
// ForwardMatrix1 and AsShotNeutral, GBRG every colour matrix).
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <set>
#include <utility>
#include <vector>
#include "../app/src/main/cpp/dngCreator.cpp"

namespace {

struct Tag { unsigned type = 0, count = 0; std::vector<unsigned char> data; };

unsigned le(const unsigned char *p, int n) {
    unsigned v = 0;
    for (int i = 0; i < n; i++) v |= unsigned(p[i]) << (8 * i);
    return v;
}

unsigned be(const unsigned char *p) { return (unsigned(p[0]) << 24) | (unsigned(p[1]) << 16) | (unsigned(p[2]) << 8) | p[3]; }

float beFloat(const unsigned char *p) {
    const unsigned u = be(p);
    float f;
    std::memcpy(&f, &u, 4);
    return f;
}

std::map<unsigned, Tag> ifd0(const unsigned char *b, size_t size) {
    static const int typeSize[] = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8};
    std::map<unsigned, Tag> tags;
    const unsigned ifd = le(b + 4, 4), count = le(b + ifd, 2);
    for (unsigned i = 0; i < count; i++) {
        const unsigned char *e = b + ifd + 2 + 12 * i;
        Tag t;
        t.type = le(e + 2, 2);
        t.count = le(e + 4, 4);
        const size_t len = size_t(t.count) * (t.type < 13 ? typeSize[t.type] : 1);
        const unsigned char *v = len <= 4 ? e + 8 : b + le(e + 8, 4);
        if (v + len > b + size) { std::printf("tag %u past the end\n", le(e, 2)); std::exit(1); }
        t.data.assign(v, v + len);
        tags[le(e, 2)] = t;
    }
    return tags;
}

int failures = 0, cases = 0;

void expect(bool ok, const char *what, int cfa, int block, bool unequal, bool after) {
    if (!ok) {
        std::printf("FAIL cfa %d block %d%s%s: %s\n", cfa, block, unequal ? " unequal black" : "", after ? " matrices after the CFA" : "", what);
        ++failures;
    }
}

/** Colour (0 R, 1 G, 2 B) of the 2x2 order `cfa` (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR) at (x, y) of a block-b mosaic. */
int colourAt(int cfa, int block, int x, int y) {
    static const int order[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
    return order[cfa][((y / block) & 1) * 2 + ((x / block) & 1)];
}

void check(int cfa, int block, bool unequal, bool after) {
    ++cases;
    const int w = 64, h = 48, mapW = 3, mapH = 2;
    DngCreator c;
    // DngCreator.setParameters' order: the block, the levels and the colour, the CFA, then the GainMap
    c.setMosaicBlock(block);
    c.setWhiteLevel(1023);
    const unsigned short black[4] = {60, static_cast<unsigned short>(unequal ? 61 : 60),
                                     static_cast<unsigned short>(unequal ? 62 : 60), static_cast<unsigned short>(unequal ? 63 : 60)};
    c.setBlackLevel(black);
    const double cm[9] = {0.9, -0.3, -0.1, -0.4, 1.2, 0.2, -0.1, 0.2, 0.6};
    const double fm[9] = {0.6, 0.2, 0.15, 0.25, 0.7, 0.05, 0.02, 0.1, 0.7};
    const double neutral[3] = {0.5, 1.0, 0.6};
    c.setCalibrationIlluminant1(21);
    if (!after) { c.setColorMatrix1(cm); c.setForwardMatrix1(fm); c.setAsShotNeutral(neutral); }
    c.setCFAPattern(cfa);
    if (after) { c.setColorMatrix1(cm); c.setForwardMatrix1(fm); c.setAsShotNeutral(neutral); }
    // LENS_SHADING_MAP channels R, G even, G odd, B: each map one value, 1.1 .. 1.4
    std::vector<float> gain(size_t(4) * mapW * mapH);
    for (size_t i = 0; i < gain.size(); i++) gain[i] = 1.1f + 0.1f * float(i % 4);
    c.setGainMap(gain.data(), 0, 0, w, h, mapW, mapH);
    std::vector<unsigned short> raw(size_t(w) * h, 100);
    size_t size = 0;
    unsigned char *dng = static_cast<unsigned char *>(c.createDng(raw.data(), w, h, size));
    if (!dng) { expect(false, "no DNG", cfa, block, unequal, after); return; }
    std::map<unsigned, Tag> t = ifd0(dng, size);
    const int dim = 2 * block;

    expect(t.count(33421) && le(t[33421].data.data(), 2) == unsigned(dim) && le(t[33421].data.data() + 2, 2) == unsigned(dim),
           "CFARepeatPatternDim 2b x 2b", cfa, block, unequal, after);
    bool pattern = t.count(33422) && t[33422].count == unsigned(dim * dim);
    for (int y = 0; pattern && y < dim; y++)
        for (int x = 0; x < dim; x++) pattern = pattern && t[33422].data[size_t(y) * dim + x] == colourAt(cfa, block, x, y);
    expect(pattern, "CFAPattern = the 2x2 order per colour block", cfa, block, unequal, after);

    const int bdim = unequal ? dim : 2, bb = bdim / 2;
    expect(t.count(50713) && le(t[50713].data.data(), 2) == unsigned(bdim) && le(t[50713].data.data() + 2, 2) == unsigned(bdim),
           "BlackLevelRepeatDim", cfa, block, unequal, after);
    bool levels = t.count(50714) && t[50714].count == unsigned(bdim * bdim) && t[50714].type == 3;
    for (int y = 0; levels && y < bdim; y++)
        for (int x = 0; x < bdim; x++)
            levels = levels && le(t[50714].data.data() + 2 * (size_t(y) * bdim + x), 2) == black[((y / bb) & 1) * 2 + ((x / bb) & 1)];
    expect(levels, "BlackLevel per site of the colour blocks", cfa, block, unequal, after);

    expect(t.count(50721) > 0, "ColorMatrix1 written", cfa, block, unequal, after);
    expect(t.count(50964) > 0, "ForwardMatrix1 written", cfa, block, unequal, after);
    expect(t.count(50728) > 0, "AsShotNeutral written", cfa, block, unequal, after);
    expect(t.count(50778) > 0, "CalibrationIlluminant1 written", cfa, block, unequal, after);

    // OpcodeList2: one GainMap per channel and block site, pitch 2b, each on a site of its own colour, every site once
    bool maps = t.count(51009) > 0;
    if (maps) {
        const unsigned char *o = t[51009].data.data();
        const unsigned n = be(o);
        maps = n == unsigned(4 * block * block);
        std::set<std::pair<unsigned, unsigned>> sites;
        size_t at = 4;
        for (unsigned k = 0; maps && k < n; k++) {
            const unsigned bytes = be(o + at + 12);
            const unsigned char *p = o + at + 16;
            const unsigned top = be(p), left = be(p + 4), rowPitch = be(p + 24), colPitch = be(p + 28);
            // 10 LONGs, 4 DOUBLEs, MapPlanes, then the gains
            const float first = beFloat(p + 40 + 32 + 4);
            const int channel = int(first * 10.f + 0.5f) - 11; // 0 R, 1 / 2 G, 3 B
            const int colour = channel == 0 ? 0 : channel == 3 ? 2 : 1;
            maps = maps && rowPitch == unsigned(dim) && colPitch == unsigned(dim) && top < unsigned(dim) && left < unsigned(dim)
                    && channel >= 0 && channel <= 3 && colourAt(cfa, block, int(left), int(top)) == colour
                    && sites.insert({top, left}).second;
            at += 16 + bytes;
        }
        maps = maps && sites.size() == size_t(dim * dim);
    }
    expect(maps, "GainMap sites by colour block", cfa, block, unequal, after);
    std::free(dng);
}

}  // namespace

int main() {
    for (int cfa = 0; cfa < 4; cfa++)
        for (int block : {1, 2, 4})
            for (int unequal = 0; unequal < 2; unequal++)
                for (int after = 0; after < 2; after++) check(cfa, block, unequal != 0, after != 0);
    // a block other than 2 / 4 is Bayer
    for (int block : {0, 3, 8}) {
        ++cases;
        DngCreator c;
        c.setMosaicBlock(block);
        if (c.metadata.mosaic_block != 1) { std::printf("FAIL block %d kept\n", block); ++failures; }
    }
    if (failures) std::printf("DNG mosaic CFA: %d failures of %d cases\n", failures, cases);
    else std::printf("DNG mosaic CFA: ok (%d cases)\n", cases);
    return failures ? 1 : 0;
}
