// P415 (noiseDarkRead): the noise check's deep-shadow point and the reject's read factor. Synthetic plain-Bayer pairs of flat
// patches whose true noise is var = s m + o_true against a header model (s, o): hybridMeasureNoise must give the single ratio
// from the patches above 0.002 of white as before and, from those at or below it, their median level and noise / model;
// hybridDarkReadK must turn that point into the read factor that makes the model meet it (o_true / o - 1 with an exact header
// slope), 0 with fewer than 50 dark blocks, never negative, at most 15.
// scam-hybrid.h is not self-contained: the worker includes scam-capture.h first.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>
using namespace scam;

static const int w = 1024, h = 1024;
static const float black = 64, white = 1023;
static int failures = 0;
static void expect(bool ok, const char* what) { std::printf("%s %s\n", ok ? "PASS" : "FAIL", what); if (!ok) ++failures; }

// Rows of 64 x 64 blocks: the top `darkRows` block rows at level `dark`, the rest at `bright` (fractions of white - black);
// noise var = s m + o (normalised units), drawn in DN.
static std::vector<uint16_t> frame(std::mt19937& rng, int darkRows, double dark, double bright, double s, double o) {
    std::normal_distribution<double> n(0.0, 1.0);
    const double range = white - black;
    std::vector<uint16_t> v(size_t(w) * h);
    for (int y = 0; y < h; ++y) {
        const double m = y / 64 < darkRows ? dark : bright;
        const double sd = std::sqrt(s * m + o) * range;
        for (int x = 0; x < w; ++x)
            v[size_t(y) * w + x] = uint16_t(std::lround(std::clamp(black + m * range + sd * n(rng), 0.0, double(white))));
    }
    return v;
}

struct Pair {
    HybridInput in;
    std::vector<uint16_t> a, b;
    Pair(int darkRows, double dark, double bright, double s, double oTrue, double oModel, uint32_t seed) {
        std::mt19937 rng(seed);
        a = frame(rng, darkRows, dark, bright, s, oTrue);
        b = frame(rng, darkRows, dark, bright, s, oTrue);
        in.w = w; in.h = h; in.cfa = 0; in.white = white; in.black = {black, black, black, black};
        for (int i = 0; i < 2; ++i) {
            HybridFrame f; f.raw = i ? b.data() : a.data(); f.role = kRoleNormal; f.exposure = 1.f; f.iso = 100;
            f.slope = float(s); f.offset = float(oModel); f.orderMs = float(i * 30);
            in.frames.push_back(f);
        }
    }
};

int main() {
    const double s = 2e-3, o = 2e-6; // ~2 DN of noise in the shadows: the rounding to DN (1/12 DN^2) stays ~1 % of it
    // 1. the read term 3 x the header's: half the blocks in the deep shadows (0.0012), half at 0.05
    {
        Pair p(8, 0.0012, 0.05, s, 3 * o, o, 415);
        const HybridNoiseCheck c = hybridMeasureNoise(p.in);
        const double want = (s * 0.0012 + 3 * o) / (s * 0.0012 + o), wantMid = (s * 0.05 + 3 * o) / (s * 0.05 + o);
        const float K = hybridDarkReadK(c, s, o, 1.0);
        std::printf("1: ratio %.4f (want %.4f), dark %ld blocks level %.5f ratio %.4f (want %.4f), K %.3f\n", c.ratio, wantMid, c.dark,
                    c.darkLevel, c.darkRatio, want, K);
        expect(c.frame == 1 && std::abs(c.ratio - wantMid) < 0.03, "the single ratio from the blocks above 0.002 only");
        expect(c.dark == 16 * 8 && std::abs(c.darkLevel - 0.0012) < 1e-4, "the deep shadows' blocks and median level");
        expect(std::abs(c.darkRatio / want - 1) < 0.04, "their noise / model");
        expect(std::abs(K - 2.f) < 0.15f, "K = o_true / o - 1 = 2");
        // the single factor already scaled the model: K is relative to it, the result never below it
        // 0.5 s m + ko o = s m + 3 o: ko = (0.5 s m + 3 o) / o, K = ko / 0.5 - 1
        const double want05 = (0.5 * s * 0.0012 + 3 * o) / o / 0.5 - 1;
        expect(std::abs(hybridDarkReadK(c, s, o, 0.5) / want05 - 1) < 0.08, "K over a single factor 0.5 (the true read term met)");
        expect(hybridDarkReadK(c, s, o, 4.0) == 0.f, "a single factor above the dark point: K = 0, not negative");
    }
    // 2. an exact model: K ~ 0
    {
        Pair p(8, 0.0012, 0.05, s, o, o, 416);
        const HybridNoiseCheck c = hybridMeasureNoise(p.in);
        const float K = hybridDarkReadK(c, s, o, 1.0);
        std::printf("2: dark ratio %.4f, K %.3f\n", c.darkRatio, K);
        expect(K >= 0.f && K < 0.1f, "an exact model: K about 0");
    }
    // 3. too few dark blocks (one block row: 16 blocks): not measured
    {
        Pair p(1, 0.0012, 0.05, s, 3 * o, o, 417);
        const HybridNoiseCheck c = hybridMeasureNoise(p.in);
        std::printf("3: dark %ld blocks\n", c.dark);
        expect(c.dark < 50 && hybridDarkReadK(c, s, o, 1.0) == 0.f, "fewer than 50 dark blocks: K = 0");
    }
    // 4. a read term 40 x the header's: capped at 16 x
    {
        Pair p(8, 0.0012, 0.05, s, 40 * o, o, 418);
        const HybridNoiseCheck c = hybridMeasureNoise(p.in);
        const float K = hybridDarkReadK(c, s, o, 1.0);
        std::printf("4: K %.3f\n", K);
        expect(K == 15.f, "capped at 16 x the model (K 15)");
    }
    if (failures) { std::printf("%d FAILED\n", failures); return 1; }
    std::printf("check_hybrid_dark_noise: all passed\n");
    return 0;
}
