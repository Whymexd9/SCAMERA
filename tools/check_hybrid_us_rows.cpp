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
//   - P290: a desk lamp clipping the base whose light the ultrashort frame caught 30 % dimmer (another flicker phase than the rest of
//     the scene): the tile field corrects the ring around the clip and carries its factor into the clipped highlight (the native
//     sites at upload agree); without the dimming, or with usLocal 0, the frame stays bit for bit.
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
    std::printf("plain Bayer burst (owner, 2026-10-19: the 12 MP lamp painted blue - the check in every mode):\n");
    {
        auto flick = [](int c, int, int y) { const float s = float(y) / float(h - 1), a = c == 0 ? 0.6f : c == 1 ? 0.45f : 0.3f; return 1.f + a * std::sin(6.2831853f * 2.f * s); };
        const auto usF = frame(expected, flick), usC = frame(expected, [](int, int, int) { return 1.f; });
        const auto usX = frame(expected, [](int, int x, int) { return 0.4f + 1.2f * float(x) / float(w - 1); });
        const auto keepF = usF, keepC = usC;
        HybridInput plain; plain.w = w; plain.h = h; plain.cfa = cfa; plain.white = white; plain.black = in.black;
        HybridFrame b0; b0.role = kRoleNormal; b0.exposure = 1.f; b0.raw = base.data(); plain.frames.push_back(b0);
        for (const auto* raw : {&usF, &usC, &usX}) { HybridFrame u; u.role = kRoleUltrashort; u.exposure = expected; u.raw = raw->data(); plain.frames.push_back(u); }
        std::vector<std::vector<uint16_t>> own;
        lines.clear();
        const HybridInput out = hybridPlainUsRows(plain, t, own, report);
        assert(out.usRowsChecked);
        assert(out.frames.size() == 3); // the column ramp: refused (no row profile explains it)
        assert(own.size() == 1 && out.frames[1].raw == own[0].data()); // the flickering frame: a corrected copy
        assert(out.frames[2].raw == usC.data()); // the consistent frame: untouched, no copy
        assert(usF == keepF && usC == keepC); // the mapped frames are never written
        HybridInput pair; pair.w = w; pair.h = h; pair.cfa = cfa; pair.white = white; pair.black = in.black;
        pair.frames = {out.frames[0], out.frames[1]};
        const HybridGain g = hybridMeasuredGain(pair, 1);
        std::printf("  corrected copy: mad %.1f %% on %d tiles\n", 100.0 * g.mad, g.tiles);
        assert(g.ok && g.mad < 0.05f);
        HybridTuning off; off.mosaicUsRows = 0;
        std::vector<std::vector<uint16_t>> none;
        const HybridInput same = hybridPlainUsRows(plain, off, none, report);
        assert(same.frames.size() == 4 && none.empty() && same.frames[1].raw == usF.data());
    }
    std::printf("lamp on another flicker phase (P290; the base clips under it):\n");
    {
        const float cx = 300.f, cy = 384.f;
        auto blob = [&](int x, int y) { const float r2 = (x - cx) * (x - cx) + (y - cy) * (y - cy); return 3000.f * std::exp(-r2 / (2.f * 90.f * 90.f)); };
        auto dim = [&](int x, int y) { const float r2 = (x - cx) * (x - cx) + (y - cy) * (y - cy); return 1.f - 0.3f * std::exp(-r2 / (2.f * 250.f * 250.f)); };
        auto make = [&](float gain, bool dimmed) {
            std::normal_distribution<float> noise(0.f, 0.8f);
            std::vector<uint16_t> v(size_t(w) * h);
            for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
                const float s = (level(x, y) + blob(x, y)) * colourScale(colourOf(x, y)) * gain * (dimmed ? dim(x, y) : 1.f);
                v[size_t(y) * w + x] = uint16_t(std::lround(std::clamp(black + s + noise(rng), 0.f, white)));
            }
            return v;
        };
        const auto lampBase = make(1.f, false);
        {   // the ultrashort frame agrees: as before
            auto us = make(expected, false);
            const auto before = us;
            const HybridUsCheck c = hybridUsRowsCheck(in, 1, lampBase.data(), us.data(), w, h, t, "CHECK", report);
            assert(!c.rows.local.on() && c.rows.local.tiles >= 64 && c.rows.local.maxDev < 0.08f && us == before && !c.refused);
        }
        {   // usLocal 0: the row profile alone (it takes the band means of the dimming: the rows only, no tile field)
            HybridTuning off = t; off.usLocal = 0;
            auto us = make(expected, true);
            const HybridUsCheck c = hybridUsRowsCheck(in, 1, lampBase.data(), us.data(), w, h, off, "CHECK", report);
            assert(!c.rows.local.on() && c.rows.local.tiles == 0);
        }
        auto us = make(expected, true);
        const auto raw = us;
        const HybridUsCheck c = hybridUsRowsCheck(in, 1, lampBase.data(), us.data(), w, h, t, "CHECK", report);
        assert(c.rows.local.on() && c.corrected && !c.refused && c.rows.local.maxDev > 0.2f);
        // the corrected frame against the true scene at the merge's ratio: the ring (base unclipped) and the clipped highlight
        double ring = 0, inside = 0, ring0 = 0, inside0 = 0; int nr = 0, ni = 0;
        for (int y = 8; y < h - 8; y += 4) for (int x = 8; x < w - 8; x += 4) {
            const float r = std::hypot(x - cx, y - cy);
            if (r > 320.f) continue;
            const float truth = (level(x, y) + blob(x, y)) * colourScale(colourOf(x, y)) * expected;
            const size_t i = size_t(y) * w + x;
            if (us[i] >= 0.98f * white || truth < 40.f) continue;
            const float e = std::abs((float(us[i]) - black) / truth - 1.f), e0 = std::abs((float(raw[i]) - black) / truth - 1.f);
            if (lampBase[i] >= 0.98f * white) { inside += e; inside0 += e0; ++ni; } else { ring += e; ring0 += e0; ++nr; }
        }
        ring /= std::max(nr, 1); inside /= std::max(ni, 1); ring0 /= std::max(nr, 1); inside0 /= std::max(ni, 1);
        std::printf("  mean error in the ring (%d) %.2f %% -> %.2f %%, under the clipped base (%d) %.2f %% -> %.2f %%\n",
                    nr, 100.0 * ring0, 100.0 * ring, ni, 100.0 * inside0, 100.0 * inside);
        // under the clip the ring's factor is carried in; the dimming's own curvature there (x0.7 at the centre) is not measurable
        assert(nr > 500 && ni > 500 && ring < 0.03 && inside < 0.5 * inside0);
        // the native Quad sites of the same frame at upload agree with the binned correction
        HybridMosaicNative nat; nat.block = 2; nat.W = 2 * w; nat.H = 2 * h;
        std::vector<uint16_t> mosaic(size_t(nat.W) * nat.H);
        for (int y = 0; y < nat.H; ++y) for (int x = 0; x < nat.W; ++x) mosaic[size_t(y) * nat.W + x] = raw[size_t(y / 2) * w + x / 2];
        nat.frames = {mosaic.data()}; nat.rowBlack = in.black; nat.rowCfa = cfa; nat.rowWhite = white;
        nat.rowGain.assign(1, std::vector<float>());
        if (c.rows.apply) { // the rows first (they took the band means of the dimming), then the tile field
            nat.rowGain[0].resize(size_t(nat.H) * 3);
            for (int y = 0; y < nat.H; ++y) for (int c2 = 0; c2 < 3; ++c2) nat.rowGain[0][size_t(y) * 3 + c2] = c.rows.at(c2, (float(y) - 0.5f) / 2.f);
        }
        nat.local.assign(1, c.rows.local);
        assert(nat.fixesRows(0) && !nat.fixesRows(1));
        std::vector<uint16_t> fixed(size_t(nat.W) * nat.H);
        nat.fixRows(0, 0, nat.H, fixed.data());
        int worst = 0;
        for (int J = 0; J < h; ++J) for (int I = 0; I < w; ++I) {
            int s = 0; for (int k = 0; k < 4; ++k) s += fixed[size_t(2 * J + (k >> 1)) * nat.W + 2 * I + (k & 1)];
            worst = std::max(worst, std::abs(int(std::lround(s / 4.0)) - int(us[size_t(J) * w + I])));
        }
        std::printf("  native vs binned correction: largest difference %d DN\n", worst);
        assert(worst <= 2);
    }
    std::printf("check_hybrid_us_rows: OK\n");
    return 0;
}
