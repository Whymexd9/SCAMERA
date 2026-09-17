#include "../app/src/main/cpp/vivo-hexquad-preprocess.h"
#include <cassert>
#include <iostream>
#include <limits>
using namespace vivo_hexquad;
template<class F> void rejects(F f) {
    bool caught = false;
    try { f(); } catch (const std::invalid_argument&) { caught = true; }
    assert(caught);
}
int main() {
    // Fixed source coordinates distinguish source CFA from destination CFA.
    assert(tagSource(123, 3, 3, 0) == 123);
    assert(tagSource(123, 4, 3, 0) == 0x407b);
    assert(tagSource(123, 3, 4, 0) == 0x407b);
    assert(tagSource(123, 4, 4, 0) == 0x807b);
    assert(tagSource(65535, 8, 8, 0) == 16383);
    assert(tagSource(123, -1, -1, 0) == 0x807b);
    for (int corner = 0; corner < 4; ++corner) {
        int counts[3] = {};
        for (int y = 0; y < 8; ++y) for (int x = 0; x < 8; ++x)
            ++counts[tagSource(0, x, y, corner) >> 14];
        assert(counts[0] == 16 && counts[1] == 32 && counts[2] == 16);
        assert((tagSource(1, 4*(corner&1), 4*(corner>>1), corner) >> 14) == 0);
    }
    rejects([] { tagSource(0, 0, 0, 4); });
    std::array<std::vector<uint16_t>, Frames> storage;
    std::array<TaggedFrame, Frames> frames;
    VstLuts luts;
    // Padded rows and a nonzero tile origin. Unselected data are holes.
    for (size_t f = 0; f < Frames; ++f) {
        storage[f].assign(24, 0xffff);
        frames[f] = {storage[f].data(), 24, 8, 5, 3};
        luts[f].assign(Colors * Levels, 0);
        luts[f][0 * Levels + 7] = uint16_t(100 + f);
        luts[f][1 * Levels + 7] = uint16_t(200 + f);
        luts[f][2 * Levels + 7] = uint16_t(300 + f);
    }
    storage[0][10] = 7; storage[1][10] = 0x4007;
    storage[2][10] = 0x8007; storage[3][10] = 7;
    storage[4][10] = 0xffff; storage[5][10] = 0x8007;
    auto out = packTile(frames, luts, 2, 1, 2, 1, {0.5f, 200.f, 0});
    const float expected[18] = {50,0,0, 0,100.5f,0, 0,0,151, 51.5f,0,0, 0,0,0, 0,0,152.5f};
    for (int i = 0; i < 18; ++i) assert(out[i] == expected[i]);
    for (int i = 18; i < 36; ++i) assert(out[i] == 0); // holes, no OOB LUT read
    out = packTile(frames, luts, 2, 1, 1, 1, {1.f, 150.f, 1});
    assert(out[0] == 150.f && out[4] == 150.f && out[8] == 150.f);
    luts[0][7] = 32769;
    out = packTile(frames, luts, 2, 1, 1, 1, {1.f, 65535.f, 1});
    assert(out[0] == 2); // explicit ushort shift semantics
    rejects([&] { packTile(frames, luts, 4, 1, 2, 1, {1,1,0}); });
    frames[1].capacity = 20;
    rejects([&] { packTile(frames, luts, 0, 0, 1, 1, {1,1,0}); });
    frames[1].capacity = 24;
    luts[1].pop_back();
    rejects([&] { packTile(frames, luts, 0, 0, 1, 1, {1,1,0}); });
    IvstLuts inverse;
    for (int c = 0; c < 3; ++c) {
        inverse[c].resize(32768);
        for (int i = 0; i < 32768; ++i) inverse[c][i] = float(i + c*100);
    }
    // LUT planes, truncation, right-shift, clipping, not square() or RGB shuffle.
    auto decoded = decodeTile({3.9f, -10.f, 70000.f}, inverse, {1,0,0,40000,1});
    assert(decoded[0] == 1 && decoded[1] == 100 && decoded[2] == 32967);
    decoded = decodeTile({0.f,1.f,2.f}, inverse, {4,2,0,40000,1});
    assert(decoded[0] == 1 && decoded[1] == 103 && decoded[2] == 205);
    rejects([&] { decodeTile({1,2,std::numeric_limits<float>::quiet_NaN()}, inverse, {1,0,0,1,1}); });
    rejects([&] { decodeTile({1,2,3}, inverse, {1,0,0,1,16}); });
    inverse[2][0] = std::numeric_limits<float>::infinity();
    rejects([&] { decodeTile({1,2,3}, inverse, {1,0,0,1,1}); });
    std::cout << "HexQuad sparse RAW boundary checks passed\n";
}
