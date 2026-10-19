// P215 (owner, X200 Ultra DNGs of 2026-10-18: «при вытягивании светов есть фиолетовые артефакты, а при вытягивании теней есть
// пастеризация»): the merged DNG of hybridWriteMergedDng.
//   - the ceiling is 1 / the shortest ultrashort TET ratio (x8 at 1/8), 1 without an ultrashort frame, at most 16;
//   - a site at the ceiling (a highlight clipped even in the ultrashort frame) is written at 65535 = WhiteLevel in every channel,
//     and so is a stray site above it (the G overshoot next to lamps that set the headroom to 16 before);
//   - the headroom is the highest site up to the ceiling (a frame without clipped highlights keeps the smaller headroom);
//   - the dither is a fixed function of the site, triangular within (-1, 1) code, off on clipped sites, codes within 0..65535;
//     the mean of a flat dithered patch stays on its undithered level; dngDither 0 writes the plain rounding as before.
// scam-hybrid.h is not self-contained: the worker includes scam-capture.h first.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>
using namespace scam;

static HybridInput input(float ultrashort) {
    HybridInput in;
    in.w = 256; in.h = 128; in.cfa = 3; in.white = 1023.f; in.black = {64.f, 64.f, 64.f, 64.f};
    HybridFrame base; base.role = kRoleNormal; base.exposure = 1.f;
    in.frames.push_back(base);
    if (ultrashort > 0.f) { HybridFrame us; us.role = kRoleUltrashort; us.exposure = ultrashort; in.frames.push_back(us); }
    return in;
}

int main() {
    assert(std::fabs(hybridDngCeiling(input(0.125f)) - 8.f) < 1e-6f);
    assert(hybridDngCeiling(input(0.f)) == 1.f);
    assert(std::fabs(hybridDngCeiling(input(0.1986f)) - 1.f / 0.1986f) < 1e-4f); // the measured ratio
    assert(hybridDngCeiling(input(0.01f)) == 16.f);

    // a frame: smooth levels 0.001..0.9 of N white, a clipped patch at the ceiling (8), a few stray sites at 20
    const HybridInput in = input(0.125f);
    const int w = in.w, h = in.h;
    auto level = [&](int x, int y, int c) -> float {
        if (x >= 200 && y < 40) return (x & 1) ? 8.f : 7.9996f;     // clipped even in the ultrashort frame (the merged plateau)
        if (x == 10 && y == 10) return 20.f;                       // overshoot
        if (x >= 100 && x < 140 && y >= 60 && y < 100) return 0.0021f; // flat dark patch
        return 0.001f + 0.9f * float(x) / float(w) * (c == 1 ? 1.f : 0.6f);
    };
    std::vector<uint16_t> plain, dith;
    const float hp = hybridWriteMergedDng(plain, w, h, in, level, false);
    const float hd = hybridWriteMergedDng(dith, w, h, in, level, true);
    assert(std::fabs(hp - 8.f) < 1e-6f && hp == hd);
    // clipped area and the stray site: WhiteLevel in every channel
    for (int y = 0; y < 40; ++y) for (int x = 200; x < w; ++x) { assert(plain[size_t(y) * w + x] == 65535); assert(dith[size_t(y) * w + x] == 65535); }
    assert(plain[size_t(10) * w + 10] == 65535 && dith[size_t(10) * w + 10] == 65535);
    // no unclipped site reaches WhiteLevel; every dithered code within +-1 of the plain one
    long sum = 0, sumPlain = 0, n = 0;
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
        const size_t i = size_t(y) * w + x;
        if (x >= 200 && y < 40) continue;
        if (x == 10 && y == 10) continue;
        assert(plain[i] < 65535 && dith[i] < 65535);
        assert(std::abs(int(dith[i]) - int(plain[i])) <= 1);
        if (x >= 100 && x < 140 && y >= 60 && y < 100) { sum += dith[i]; sumPlain += plain[i]; ++n; }
    }
    // the flat dark patch: same mean within a tenth of a code, and more than one code value used
    assert(std::fabs(double(sum - sumPlain) / double(n)) < 0.1);
    bool varied = false;
    for (int y = 60; y < 100 && !varied; ++y) for (int x = 100; x < 140; x += 2) if (dith[size_t(y) * w + x] != dith[size_t(60) * w + 100]) { varied = true; break; }
    assert(varied);
    // deterministic
    std::vector<uint16_t> again;
    hybridWriteMergedDng(again, w, h, in, level, true);
    assert(again == dith);
    // a frame without clipped highlights keeps its own headroom (the highest site, 3.5 here), not the ceiling
    auto dim = [&](int x, int y, int c) -> float { return x == 5 && y == 5 ? 3.5f : 0.5f; };
    std::vector<uint16_t> d2;
    assert(std::fabs(hybridWriteMergedDng(d2, w, h, in, dim, true) - 3.5f) < 1e-6f);
    // without an ultrashort frame nothing goes above N white: the headroom is 1, as before P165's highlights
    std::vector<uint16_t> d3;
    assert(hybridWriteMergedDng(d3, w, h, input(0.f), level, false) == 1.f);
    // P267 dngMosaic: block 2 / 4 writes each site the colour of its b x b block in the blocks' CFA order (cfa 3 = BGGR: the
    // top-left block blue), the Quad / Tetra layout as captured; block 1 is the plain Bayer layout as before
    auto byColour = [](int, int, int c) -> float { return 0.1f + 0.2f * float(c); }; // R 0.1, G 0.3, B 0.5
    for (int b : {1, 2, 4}) {
        std::vector<uint16_t> m;
        hybridWriteMergedDng(m, w, h, input(0.f), byColour, false, b);
        for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
            const int bx = (x / b) & 1, by = (y / b) & 1;
            const int c = bx == 0 && by == 0 ? 2 : bx == 1 && by == 1 ? 0 : 1; // BGGR
            const float want = (64.f + (0.1f + 0.2f * float(c)) * (1023.f - 64.f)) * (65535.f / 1023.f);
            assert(std::fabs(float(m[size_t(y) * w + x]) - want) <= 1.f);
        }
    }
    std::printf("check_dng_ceiling: OK\n");
    return 0;
}
