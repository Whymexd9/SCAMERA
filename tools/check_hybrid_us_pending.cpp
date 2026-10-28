// P364: the plain Bayer burst's ultrashort check runs beside the speculative merge (HybridUsPending) on a std::async thread that
// writes pending->own (its corrected copies) and pending->lines (under pending->lock). A merge that throws anything but
// HybridUsRestart before it joins the check drops the last reference to HybridUsPending while that thread still runs: the
// thread must end before own / lock / lines go (they were destroyed first and the future that waits for the thread last: a
// heap-use-after-free in hybridUsRowsMeasure's copies, a destroyed mutex locked, a SIGSEGV / SIGABRT of the worker instead of its
// 'STOP: <error>').
// The exception: the corner tracker (ScamAlignment) throws a non-std exception at a share of the check's own run time after the
// merge started, while the check measures the ultrashort frames or corrects their rolling-shutter flicker in its copies (any
// exception that leaves the merge there - a std::bad_alloc in its front end, an error of the GPU alignment - leaves the same way).
// Expected: the alignment's exception reaches the caller every time, and the sanitizer reports nothing.
//  - -fsanitize=thread (deterministic): the check thread writes own and lines with no happens-before to their destructors unless
//    they wait for it, at any of the times;
//  - -fsanitize=address,undefined: the heap-use-after-free of the copies when the throw falls while the check reads them.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <thread>
using namespace scam;

static const int W = 3072, H = 2304;
static std::vector<uint16_t> frame(float exposure, bool flicker, unsigned seed) {
    std::mt19937 r(seed);
    std::normal_distribution<float> n(0.f, 1.f);
    std::vector<uint16_t> out(size_t(W) * H);
    for (int y = 0; y < H; ++y) {
        // a lamp's rolling-shutter flicker on the ultrashort frames: their rows are corrected (in a copy in own)
        const float f = flicker ? 1.f + 0.25f * std::sin(6.2831853f * float(y) / 300.f) : 1.f;
        for (int x = 0; x < W; ++x) {
            float v = 0.25f + 0.15f * std::sin(x * 0.013f) * std::cos(y * 0.011f);
            if (((x / 200) + (y / 200)) % 7 == 0) v = 3.f; // clipped lamps in the base
            v *= exposure * f;
            v += n(r) * std::sqrt(4e-4f * std::max(v, 0.f) + 2e-6f);
            out[size_t(y) * W + x] = uint16_t(std::lround(64 + std::clamp(v, 0.f, 1.f) * (4095 - 64)));
        }
    }
    return out;
}

int main() {
    setenv("SCAM_NO_EARLY_GPU", "1", 1); // nothing else to wait for when the merge unwinds (no GPU context is built)
    std::vector<std::vector<uint16_t>> raw;
    const std::vector<int> role{kRoleNormal, kRoleUltrashort, kRoleUltrashort, kRoleUltrashort, kRoleNormal};
    for (size_t f = 0; f < role.size(); ++f) raw.push_back(frame(role[f] == kRoleUltrashort ? 0.125f : 1.f, role[f] == kRoleUltrashort, 9 + unsigned(f)));
    HybridInput in;in.w = W;in.h = H;in.cfa = 0;in.white = 4095;in.black.fill(64);in.mosaic = 1;
    for (size_t f = 0; f < role.size(); ++f) {
        HybridFrame fr;fr.raw = raw[f].data();fr.role = role[f];fr.exposure = role[f] == kRoleUltrashort ? 0.125f : 1.f;fr.iso = 800;
        fr.slope = 4e-4f / fr.exposure;fr.offset = 2e-6f / (fr.exposure * fr.exposure);fr.orderMs = float(f) * 33.f;
        in.frames.push_back(fr);
    }
    HybridTuning t; // the ultrashort check as the app runs it (mosaicUsRows 1); no pool work for the unwinding merge to wait for
    t.bento = 0;t.localAlign = 0;t.caCorrect = 0;t.noiseMeasured = 0;t.gainMeasured = 0;
    int fails = 0;
    double checkMs = 0;
    {
        // the premise: the check corrects the ultrashort frames in copies of its own (own), for a while
        std::vector<std::vector<uint16_t>> own;
        const auto t0 = std::chrono::steady_clock::now();
        hybridPlainUsRows(in, t, own, [](const std::string&) {});
        checkMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        std::printf("%s: the check alone: %zu corrected copies, %.0f ms\n", own.empty() ? "FAIL" : "ok", own.size(), checkMs);
        std::fflush(stdout);
        if (own.empty()) ++fails;
    }
    // the throws spread over the check's run (it runs a little slower beside the merge than alone)
    for (const double share : {0.1, 0.3, 0.5, 0.7, 0.9}) {
        using Clock = std::chrono::steady_clock;
        Clock::time_point t0;
        double threwMs = 0;
        ScamAlignment align = [&](Burst&) -> std::array<BackwardHomography, 7> {
            std::this_thread::sleep_until(t0 + std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double, std::milli>(share * checkMs)));
            threwMs = std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
            throw 42;
        };
        std::vector<std::string> log;std::mutex lock;
        bool caught = false;
        t0 = Clock::now();
        try {
            hybridReconstruct(in, t, align, [&](const std::string& l) {std::lock_guard<std::mutex> g(lock);log.push_back(l);}, nullptr, nullptr);
        } catch (int e) {
            caught = e == 42;
        }
        const double ms = std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
        std::printf("%s: the alignment threw %.0f ms after the merge started: %s (%.0f ms, %zu report lines)\n", caught ? "ok" : "FAIL",
                    threwMs, caught ? "passed on to the caller" : "not passed on", ms, log.size());
        std::fflush(stdout);
        if (!caught) ++fails;
    }
    std::printf("check_hybrid_us_pending: %s\n", fails ? "FAILED" : "OK");
    return fails ? 1 : 0;
}
