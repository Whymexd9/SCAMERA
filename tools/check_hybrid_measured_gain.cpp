// P27 (VERIFY-11): the worker's HYBRID GAIN CHECK as a ratio source (tuning gainMeasured 1). A synthetic plain-Bayer burst:
// the base frame, a bracketed frame whose data is x1 of the base while its metadata says x1.8, an honest bracketed frame (data
// and metadata x2) and an honest ultrashort one (x0.25). hybridMeasuredGain must find the data ratios, and
// hybridApplyMeasuredGains must replace only the wrong ratio.
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

int main() {
    const int w = 512, h = 512;
    const float black = 64, white = 1023;
    std::mt19937 rng(7);
    std::normal_distribution<float> noise(0.f, 1.5f);
    auto frame = [&](float gain) {
        std::vector<uint16_t> v(size_t(w) * h);
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                const float scene = 40.f + 200.f * (0.5f + 0.5f * std::sin(x * 0.05f) * std::cos(y * 0.04f));
                v[size_t(y) * w + x] = uint16_t(std::lround(std::clamp(black + scene * gain + noise(rng), 0.f, white)));
            }
        return v;
    };
    const auto base = frame(1.f), wrong = frame(1.f), bracketed = frame(2.f), shortFrame = frame(0.25f);
    HybridInput in;
    in.w = w; in.h = h; in.cfa = 0; in.white = white; in.black = {black, black, black, black};
    auto add = [&](const std::vector<uint16_t>& raw, int role, float exposure) {
        HybridFrame f; f.raw = raw.data(); f.role = role; f.exposure = exposure; f.iso = 100;
        in.frames.push_back(f);
    };
    add(base, kRoleNormal, 1.f);
    add(wrong, kRoleBracketed, 1.8f);
    add(bracketed, kRoleBracketed, 2.f);
    add(shortFrame, kRoleUltrashort, 0.25f);

    const HybridGain g1 = hybridMeasuredGain(in, 1), g2 = hybridMeasuredGain(in, 2), g3 = hybridMeasuredGain(in, 3);
    std::printf("measured %.4f %.4f %.4f (tiles %d %d %d)\n", g1.measured, g2.measured, g3.measured, g1.tiles, g2.tiles, g3.tiles);
    assert(g1.ok && std::abs(g1.measured - 1.f) < 0.03f);
    assert(g2.ok && std::abs(g2.measured / 2.f - 1.f) < 0.03f);
    assert(g3.ok && std::abs(g3.measured / 0.25f - 1.f) < 0.03f);

    std::vector<std::string> lines;
    const int changed = hybridApplyMeasuredGains(in, [&](const std::string& s) { lines.push_back(s); });
    assert(changed == 1 && lines.size() == 1);
    assert(lines[0].find("frame=1") != std::string::npos);
    assert(std::abs(in.frames[1].exposure - 1.f) < 0.03f);
    assert(in.frames[2].exposure == 2.f && in.frames[3].exposure == 0.25f && in.frames[0].exposure == 1.f);
    std::printf("%s\nPASS: measured gain replaces only the metadata ratio the data disagrees with\n", lines[0].c_str());
    return 0;
}
