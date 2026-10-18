// P197 (owner, 2026-10-17: «Сделай для нативной мозаики ту же цветовую проверку Bento, что у 12 Мп»): the ultrashort frame of a
// mosaic burst against the base, row band by row band and colour (rolling-shutter flicker of a lamp). Synthetic binned frames
// (plain Bayer, BGGR as the vivo ISZ / FULLRES streams):
//   - a consistent ultrashort frame: no correction, the frame bit for bit, not refused;
//   - a row-wise colour / level ramp (R x0.75..1.25, G x0.9..1.1, B x1.25..0.75 down the frame): the profile finds it, the corrected
//     frame has a flat profile, the gain check passes;
//   - a strong row-wise flicker (sinus, G +-45 %) that the gain check calls inconsistent: corrected, then consistent, not refused;
//   - a column-wise ramp (no row profile can explain it): no correction (no made-up colour profile), refused;
//   - the native sites corrected at upload (HybridMosaicNative::fixRows, Quad sensor layout) agree with the binned correction;
//   - mosaicUsRows 0: nothing measured, nothing changed;
//   - P212d: the green row-profile line the app fits the lamp flicker to (RowFlicker): a 5 % flicker at 100 / 120 Hz over a
//     40.11 ms skew (4.0 / 4.8 cycles a frame) in every counting band, with and without a 1 px shift of the scene; a flat profile
//     without flicker; the frames as before (the line is a report only).
// scam-hybrid.h is not self-contained: the worker includes scam-capture.h first.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <random>
#include <string>
#include <vector>
using namespace scam;

static const int w = 1024, h = 768, cfa = 3;
static const float black = 64, white = 1023, expected = 0.125f;
static std::mt19937 rng(11);

static int colourOf(int x, int y) { const int p = ((y & 1) << 1) | (x & 1); return p == cfa ? 0 : p == (cfa ^ 3) ? 2 : 1; }
// scene above black at gain 1 (base): 120..760 DN, colour R 0.5 / G 1 / B 0.7 of it
static float level(int x, int y) { return 120.f + 640.f * (0.5f + 0.5f * std::sin(x * 0.031f) * std::cos(y * 0.027f)); }
static float colourScale(int c) { return c == 0 ? 0.5f : c == 2 ? 0.7f : 1.f; }
static float scene(int x, int y) {
    const float s = level(x, y);
    const int c = colourOf(x, y);
    return s * colourScale(c);
}
// P212d: W x H, the scene moved dx px to the left (each site keeps its own colour)
template <class F> static std::vector<uint16_t> frameOf(int W, int H, float gain, F factor, int dx) {
    std::normal_distribution<float> noise(0.f, 0.8f);
    std::vector<uint16_t> v(size_t(W) * H);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
            const int c = colourOf(x, y);
            v[size_t(y) * W + x] = uint16_t(std::lround(std::clamp(black + level(x + dx, y) * colourScale(c) * gain * factor(c, x, y) + noise(rng), 0.f, white)));
        }
    return v;
}
template <class F> static std::vector<uint16_t> frame(float gain, F factor) {
    std::normal_distribution<float> noise(0.f, 0.8f);
    std::vector<uint16_t> v(size_t(w) * h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            v[size_t(y) * w + x] = uint16_t(std::lround(std::clamp(black + scene(x, y) * gain * factor(colourOf(x, y), x, y) + noise(rng), 0.f, white)));
    return v;
}
static HybridInput input() {
    HybridInput in; in.w = 2 * w; in.h = 2 * h; in.cfa = cfa; in.white = white; in.black = {black, black, black, black};
    HybridFrame b; b.role = kRoleNormal; b.exposure = 1.f; in.frames.push_back(b);
    HybridFrame u; u.role = kRoleUltrashort; u.exposure = expected; in.frames.push_back(u);
    return in;
}

int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    const HybridInput in = input();
    HybridTuning t;
    std::vector<std::string> lines;
    auto report = [&](const std::string& s) { lines.push_back(s); std::printf("  %s\n", s.c_str()); };
    const auto base = frame(1.f, [](int, int, int) { return 1.f; });

    std::printf("consistent ultrashort frame:\n");
    {
        auto us = frame(expected, [](int, int, int) { return 1.f; });
        const auto before = us;
        const HybridUsCheck c = hybridUsRowsCheck(in, 1, base.data(), us.data(), w, h, t, "CHECK", report);
        assert(c.rows.measured && !c.rows.apply && c.rows.maxDev < 0.03f);
        assert(!c.corrected && !c.refused && us == before);
    }
    std::printf("row-wise colour ramp:\n");
    {
        auto ramp = [](int c, int, int y) { const float s = float(y) / float(h - 1), a = c == 0 ? 0.25f : c == 1 ? 0.1f : -0.25f; return 1.f - a + 2.f * a * s; };
        auto us = frame(expected, ramp);
        const HybridUsCheck c = hybridUsRowsCheck(in, 1, base.data(), us.data(), w, h, t, "CHECK", report);
        assert(c.rows.apply && c.corrected && !c.refused && c.rows.maxDev > 0.2f);
        // top band: R was x0.75 / G x0.9 of the level -> factors ~1/0.75 x M and ~1/0.9 x M (M = the median green ratio ~ expected)
        assert(std::abs(c.rows.f[0][0] * 0.76f - 1.f) < 0.05f && std::abs(c.rows.f[2][0] * 1.24f - 1.f) < 0.05f);
        const HybridUsRows again = hybridUltrashortRows(base.data(), us.data(), w, h, cfa, in.black, white, expected, 0.08f);
        std::printf("  after: largest %.2f %%\n", 100.0 * again.maxDev);
        assert(again.measured && again.maxDev < 0.03f);
        // the native Quad sites of the same frame corrected at upload: each binned site b = 2 x 2 sensor sites (sensor row y sits at
        // binned row (y - 0.5) / 2), the block mean agrees with the binned correction within rounding
        auto raw = frame(expected, ramp);
        HybridMosaicNative nat; nat.block = 2; nat.W = 2 * w; nat.H = 2 * h;
        std::vector<uint16_t> mosaic(size_t(nat.W) * nat.H);
        for (int y = 0; y < nat.H; ++y) for (int x = 0; x < nat.W; ++x) mosaic[size_t(y) * nat.W + x] = raw[size_t(y / 2) * w + x / 2];
        nat.frames = {mosaic.data()}; nat.rowBlack = in.black; nat.rowCfa = cfa; nat.rowWhite = white;
        nat.rowGain.assign(1, std::vector<float>(size_t(nat.H) * 3));
        for (int y = 0; y < nat.H; ++y) for (int c2 = 0; c2 < 3; ++c2) nat.rowGain[0][size_t(y) * 3 + c2] = c.rows.at(c2, (float(y) - 0.5f) / 2.f);
        assert(nat.fixesRows(0) && !nat.fixesRows(1));
        std::vector<uint16_t> fixed(size_t(nat.W) * nat.H);
        nat.fixRows(0, 0, nat.H, fixed.data());
        hybridUsRowsApply(c.rows, raw.data(), w, h, cfa, in.black, white, 0.f);
        int worst = 0;
        for (int J = 0; J < h; ++J) for (int I = 0; I < w; ++I) {
            int s = 0; for (int k = 0; k < 4; ++k) s += fixed[size_t(2 * J + (k >> 1)) * nat.W + 2 * I + (k & 1)];
            worst = std::max(worst, std::abs(int(std::lround(s / 4.0)) - int(raw[size_t(J) * w + I])));
        }
        std::printf("  native vs binned correction: largest difference %d DN\n", worst);
        assert(worst <= 1);
    }
    std::printf("strong row-wise flicker (two periods; gain check inconsistent before the correction):\n");
    {
        auto flick = [](int c, int, int y) { const float s = float(y) / float(h - 1), a = c == 0 ? 0.6f : c == 1 ? 0.45f : 0.3f; return 1.f + a * std::sin(6.2831853f * 2.f * s); };
        auto us = frame(expected, flick);
        HybridInput pair = in; pair.w = w; pair.h = h; pair.frames[0].raw = base.data(); pair.frames[1].raw = us.data();
        const HybridGain g0 = hybridMeasuredGain(pair, 1);
        std::printf("  before: mad %.1f %% on %d tiles\n", 100.0 * g0.mad, g0.tiles);
        assert(g0.tiles >= 64 && !g0.ok);
        const HybridUsCheck c = hybridUsRowsCheck(in, 1, base.data(), us.data(), w, h, t, "CHECK", report);
        assert(c.corrected && !c.refused);
        const HybridGain g1 = hybridMeasuredGain(pair, 1);
        std::printf("  after: mad %.1f %% on %d tiles\n", 100.0 * g1.mad, g1.tiles);
        assert(g1.ok && g1.mad < 0.05f && std::abs(g1.measured / expected - 1.f) < 0.03f); // at the merge's ratio, not the flicker's median
    }
    std::printf("column-wise ramp:\n");
    {
        auto us = frame(expected, [](int, int x, int) { return 0.4f + 1.2f * float(x) / float(w - 1); });
        const HybridUsCheck c = hybridUsRowsCheck(in, 1, base.data(), us.data(), w, h, t, "CHECK", report);
        // no row profile can explain it (the tiles of every band disagree): not corrected - no made-up colour - and refused
        assert(!c.rows.measured && !c.corrected);
        assert(c.refused && lines.back().find("refused") != std::string::npos);
    }
    std::printf("mosaicUsRows 0:\n");
    {
        HybridTuning off; off.mosaicUsRows = 0;
        auto us = frame(expected, [](int, int x, int) { return 0.4f + 1.2f * float(x) / float(w - 1); });
        const auto before = us;
        lines.clear();
        const HybridUsCheck c = hybridUsRowsCheck(in, 1, base.data(), us.data(), w, h, off, "CHECK", report);
        assert(!c.corrected && !c.refused && !c.rows.measured && lines.empty() && us == before);
    }
    std::printf("row profile line for the flicker vote (P212d; 64 bands as a native binned frame):\n");
    {
        const int W = 512, H = 2048;
        const float skew = 0.04011f; // the PHY110's native modes
        const auto base2 = frameOf(W, H, 1.f, [](int, int, int) { return 1.f; }, 0);
        struct Case { int hz, dx; };
        for (const Case k : {Case{0, 0}, Case{100, 0}, Case{120, 0}, Case{100, 1}, Case{120, 1}}) {
            auto flick = [k, skew](int, int, int y) {
                return k.hz == 0 ? 1.f : 1.f + 0.05f * std::cos(6.2831853f * float(k.hz) * skew * (float(y) + 0.5f) / float(H) + 0.7f);
            };
            auto us = frameOf(W, H, expected, flick, k.dx);
            const auto before = us;
            lines.clear();
            std::printf(" %d Hz, shift %d px:\n", k.hz, k.dx);
            const HybridUsCheck c = hybridUsRowsCheck(in, 1, base2.data(), us.data(), W, H, t, "CHECK", report);
            assert(c.rows.measured && c.rows.bands == 64 && int(c.rows.g.size()) == 64 && !c.rows.apply && us == before);
            std::string line;
            for (const auto& l : lines) if (l.find(" row profile 64 bands G ") != std::string::npos) line = l;
            assert(!line.empty() && line.find("CHECK: ultrashort frame 1 row profile 64 bands G") == 0);
            int counted = 0;
            float worst = 0.f;
            for (int b = 0; b < 64; ++b) {
                if (!std::isfinite(c.rows.g[b])) continue;
                ++counted;
                worst = std::max(worst, std::abs(c.rows.g[b] - flick(1, 0, int((b + 0.5f) * H / 64))));
            }
            std::printf("  %d bands counted, largest departure from the flicker %.2f %%\n", counted, 100.0 * worst);
            assert(counted >= 48 && worst < 0.015f);
        }
    }
    std::printf("check_hybrid_us_rows: OK\n");
    return 0;
}
