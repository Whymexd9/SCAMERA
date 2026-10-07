// P27 (VERIFY-11): the worker's HYBRID GAIN CHECK as a ratio source (tuning gainMeasured 1, the default). Synthetic plain-Bayer
// bursts: hybridMeasuredGain must find the data ratios (a dim, moved ultrashort frame included: the former per-frame noise test
// biased it upwards), and hybridApplyMeasuredGains must take the measured ratio only for an exposure whose frames reliably and
// consistently disagree with their metadata, and keep every other ratio bit for bit.
// vivo-nice-hybrid.h is not self-contained: the worker includes vivo-nice-capture.h first.
#include "../app/src/main/cpp/vivo-nice-capture.h"
#include "../app/src/main/cpp/vivo-nice-hybrid.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <random>
#include <string>
#include <vector>
using namespace vivo_nice;

static const int w = 512, h = 512;
static const float black = 64, white = 1023;
static std::mt19937 rng(7);

// The scene at (x + dx, y): 40..240 DN above black x level at gain 1; jitter: a per-16-px-tile gain of 1 +- jitter (uniform).
static std::vector<uint16_t> frame(float gain, float dx = 0.f, float jitter = 0.f, float level = 1.f) {
    std::normal_distribution<float> noise(0.f, 1.5f);
    std::uniform_real_distribution<float> u(-1.f, 1.f);
    const int tw = w / 16 + 1;
    std::vector<float> tileGain(size_t(tw) * (h / 16 + 1));
    for (auto& t : tileGain) t = 1.f + jitter * u(rng);
    std::vector<uint16_t> v(size_t(w) * h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const float X = x + dx;
            const float scene = level * (40.f + 200.f * (0.5f + 0.5f * std::sin(X * 0.05f) * std::cos(y * 0.04f)));
            const float g = gain * tileGain[size_t(y / 16) * tw + x / 16];
            v[size_t(y) * w + x] = uint16_t(std::lround(std::clamp(black + scene * g + noise(rng), 0.f, white)));
        }
    return v;
}

struct TestBurst {
    HybridInput in;
    std::vector<std::vector<uint16_t>> store;
    TestBurst() { in.w = w; in.h = h; in.cfa = 0; in.white = white; in.black = {black, black, black, black}; store.reserve(16); }
    void add(std::vector<uint16_t> raw, int role, float exposure) {
        store.push_back(std::move(raw));
        HybridFrame f; f.raw = store.back().data(); f.role = role; f.exposure = exposure; f.iso = 100;
        in.frames.push_back(f);
    }
};

static int apply(HybridInput& in, std::vector<std::string>& lines) {
    lines.clear();
    return hybridApplyMeasuredGains(in, [&](const std::string& s) { lines.push_back(s); std::printf("  %s\n", s.c_str()); });
}
static int count(const std::vector<std::string>& lines, const char* text) {
    int c = 0;
    for (const auto& l : lines) c += l.find(text) != std::string::npos;
    return c;
}

int main() {
    std::vector<std::string> lines;
    // ---- 1. a lone bracketed frame whose data is x1 while its metadata says x1.8; honest x2 and x0.25 exposures
    {
        TestBurst b;
        b.add(frame(1.f), kRoleNormal, 1.f);
        b.add(frame(1.f), kRoleBracketed, 1.8f);
        b.add(frame(2.f), kRoleBracketed, 2.f);
        b.add(frame(0.25f), kRoleUltrashort, 0.25f);
        const HybridGain g1 = hybridMeasuredGain(b.in, 1), g2 = hybridMeasuredGain(b.in, 2), g3 = hybridMeasuredGain(b.in, 3);
        std::printf("1: measured %.4f %.4f %.4f (tiles %d %d %d)\n", g1.measured, g2.measured, g3.measured, g1.tiles, g2.tiles, g3.tiles);
        assert(g1.ok && std::abs(g1.measured - 1.f) < 0.03f);
        assert(g2.ok && std::abs(g2.measured / 2.f - 1.f) < 0.03f);
        assert(g3.ok && std::abs(g3.measured / 0.25f - 1.f) < 0.03f);
        const int changed = apply(b.in, lines);
        assert(changed == 1);
        assert(count(lines, "HYBRID GAIN CHECK") == 3 && count(lines, "HYBRID GAIN: ") == 3);
        assert(count(lines, "measured ratio used") == 1);
        for (const auto& l : lines)
            if (l.find("measured ratio used") != std::string::npos) assert(l.find("frames=1: ") != std::string::npos);
        assert(std::abs(b.in.frames[1].exposure - 1.f) < 0.03f);
        assert(b.in.frames[2].exposure == 2.f && b.in.frames[3].exposure == 0.25f && b.in.frames[0].exposure == 1.f);
    }
    // ---- 2. two ultrashort frames, both 30 % brighter than their metadata x0.25: the exposure takes the measured ratio
    {
        TestBurst b;
        b.add(frame(1.f), kRoleNormal, 1.f);
        b.add(frame(0.325f), kRoleUltrashort, 0.25f);
        b.add(frame(0.325f), kRoleUltrashort, 0.25f);
        const int changed = apply(b.in, lines);
        std::printf("2: changed %d, ratios %.4f %.4f\n", changed, b.in.frames[1].exposure, b.in.frames[2].exposure);
        assert(changed == 2 && count(lines, "HYBRID GAIN: ") == 1 && count(lines, "measured ratio used") == 1);
        assert(std::abs(b.in.frames[1].exposure / 0.325f - 1.f) < 0.03f && b.in.frames[1].exposure == b.in.frames[2].exposure);
    }
    // ---- 3. two frames of one exposure that disagree (x1.3 and x1.0 of their metadata): both keep the metadata ratio
    {
        TestBurst b;
        b.add(frame(1.f), kRoleNormal, 1.f);
        b.add(frame(0.325f), kRoleUltrashort, 0.25f);
        b.add(frame(0.25f), kRoleUltrashort, 0.25f);
        const int changed = apply(b.in, lines);
        assert(changed == 0 && count(lines, "frames disagree") == 1);
        assert(b.in.frames[1].exposure == 0.25f && b.in.frames[2].exposure == 0.25f);
    }
    // ---- 4. inconsistent tiles (per-tile gain +-40 %, MAD ~20 %) on both frames of a x1.3 exposure: metadata kept
    {
        TestBurst b;
        b.add(frame(1.f), kRoleNormal, 1.f);
        b.add(frame(1.3f * 2.f, 0.f, 0.4f), kRoleBracketed, 2.f);
        b.add(frame(1.3f * 2.f, 0.f, 0.4f), kRoleBracketed, 2.f);
        const int changed = apply(b.in, lines);
        assert(changed == 0 && count(lines, "too few reliable frames") == 1);
        assert(b.in.frames[1].exposure == 2.f && b.in.frames[2].exposure == 2.f);
    }
    // ---- 5. a lone frame needs a lower MAD than a group: a x1.3 frame with +-14 % tile jitter (MAD ~7 %) keeps its metadata
    {
        TestBurst b;
        b.add(frame(1.f), kRoleNormal, 1.f);
        b.add(frame(1.3f * 2.f, 0.f, 0.14f), kRoleBracketed, 2.f);
        const HybridGain g = hybridMeasuredGain(b.in, 1);
        std::printf("5: lone frame mad %.1f %%\n", 100.0 * g.mad);
        assert(g.mad > kGainMaxMadAlone && g.mad <= kGainMaxMad);
        const int changed = apply(b.in, lines);
        assert(changed == 0 && count(lines, "single-frame limit") == 1 && b.in.frames[1].exposure == 2.f);
    }
    // ---- 6. dim, moved ultrashort frames at an exact x1/16: measured within 5 %, metadata kept bit for bit
    {
        TestBurst b;
        b.add(frame(1.f, 0.f, 0.f, 0.6f), kRoleNormal, 1.f);
        b.add(frame(1.f / 16, 3.f, 0.f, 0.6f), kRoleUltrashort, 1.f / 16);
        b.add(frame(1.f / 16, -2.f, 0.f, 0.6f), kRoleUltrashort, 1.f / 16);
        const HybridGain g = hybridMeasuredGain(b.in, 1);
        std::printf("6: dim ultrashort measured %.5f (%+.1f %%) tiles %d mad %.1f %%\n", g.measured, 100.0 * (g.measured * 16 - 1), g.tiles, 100.0 * g.mad);
        assert(g.tiles >= 64 && std::abs(g.measured * 16.f - 1.f) < 0.05f);
        const int changed = apply(b.in, lines);
        assert(changed == 0 && b.in.frames[1].exposure == 1.f / 16 && b.in.frames[2].exposure == 1.f / 16);
    }
    // ---- 7. normal frames only: nothing measured, nothing reported
    {
        TestBurst b;
        b.add(frame(1.f), kRoleNormal, 1.f);
        b.add(frame(1.f), kRoleNormal, 1.f);
        assert(apply(b.in, lines) == 0 && lines.empty());
    }
    std::printf("PASS: the measured gain replaces only a metadata ratio the data reliably and consistently disagrees with\n");
    return 0;
}
