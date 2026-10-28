// P364: the plain merge with the app's GPU alignment (laGpu 2, gpuAlign 1: the GPU homographies, the ultrashort frames first),
// the P350 ultrashort registration (bentoUsRegister 2) and the early Bento masks of the ultrashort frames, on a synthetic burst
// with two ultrashort frames (Mesa llvmpipe on the host: LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless).
//  - gpuAlignUsRegister 1: every ultrashort frame registered on a thread of its own (usRegLater) while the alignment goes on; the
//    early-mask test of the last ultrashort frame reads only main-thread state (it read the registration's report line that the
//    thread was assigning: a data race on a std::string).
//  - gpuAlignUsRegister 0: the registrations after good GPU homographies skipped ('not needed'), the early masks taken.
// Each run aligns every frame on the GPU, registers or skips the ultrashort ones as asked and merges a finite frame.
// The memory guard of the GPU field above kHybridClassicPixels: with the GPU homography the field holds ~18.5 B per RAW pixel to
// the F6 stage (check_la_gpu_errors C), the guard counted the field's 11.5 B alone. A two-frame plain burst just above 16 MP with
// MemAvailable (SCAM_LA_GPU_MEMAVAIL_MB) at 4 x 260 MB, between the two figures: the GPU field skipped with gpuAlign 1, kept with
// gpuAlign 0 (the field alone). Only the decision is checked: the merge stops at its alignment (the corner tracker or the first
// GPU homography line throws).
// A GPU field fault mid-burst (SCAM_LA_GPU_FAULTS: the second normal frame's field throws, as a GL error there does), with the
// ultrashort check beside the merge: the merge goes on (the homography whose field failed kept: no broken promise, no unwinding
// past the check), the frame whose homography the failed worker no longer computed is aligned by the global translation (it
// started from the identity), the CPU computes the field, and the merge is finite.
// Built with -D_GLIBCXX_ASSERTIONS -fsanitize=thread (std::string's members then instrumented, not libstdc++'s extern instances)
// and -DSCAM_LA_GPU_FAULTS, run with tools/check_hybrid_gpu_align.tsan.supp (Mesa's own threads and its EGL set-up) under
// setarch -R: ThreadSanitizer reports no race (a report fails the check through its exit code).
#ifndef SCAM_LA_GPU_FAULTS
#error "build with -DSCAM_LA_GPU_FAULTS"
#endif
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
#include <map>
#include <random>
#include <thread>
using namespace scam;

static const int W = 2048, H = 1536;
static int fails = 0;
static void expect(bool ok, const std::string& what) {
    std::printf("%s: %s\n", ok ? "ok" : "FAIL", what.c_str());
    std::fflush(stdout);
    if (!ok) ++fails;
}
static std::vector<float> scene() {
    std::mt19937 r(11);
    const int SW = W + 64, SH = H + 64;
    std::vector<float> img(size_t(SW) * SH, 0.15f);
    for (int i = 0; i < 3500; ++i) {
        const int cx = int(r() % SW), cy = int(r() % SH), rad = 3 + int(r() % 14);
        const float v = 0.05f + 0.5f * float(r() % 1000) / 1000.f;
        for (int y = std::max(0, cy - rad); y < std::min(SH, cy + rad); ++y)
            for (int x = std::max(0, cx - rad); x < std::min(SW, cx + rad); ++x) img[size_t(y) * SW + x] = v;
    }
    // lamps: clipped in the normal frames (x4 over white), unclipped in the ultrashort ones
    for (int i = 0; i < 40; ++i) {
        const int cx = 100 + int(r() % (SW - 200)), cy = 100 + int(r() % (SH - 200)), rad = 6 + int(r() % 20);
        for (int y = cy - rad; y < cy + rad; ++y)
            for (int x = cx - rad; x < cx + rad; ++x)
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < rad * rad) img[size_t(y) * SW + x] = 4.f;
    }
    return img;
}
static std::vector<uint16_t> bayer(const std::vector<float>& s, int dx, int dy, float exposure, unsigned seed) {
    std::mt19937 r(seed);
    std::normal_distribution<float> n(0.f, 1.f);
    const int SW = W + 64;
    std::vector<uint16_t> out(size_t(W) * H);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
            float v = s[size_t(y + 32 + dy) * SW + x + 32 + dx] * exposure;
            v += n(r) * std::sqrt(4e-4f * std::max(v, 0.f) + 2e-6f);
            out[size_t(y) * W + x] = uint16_t(std::lround(64 + std::clamp(v, 0.f, 1.f) * (4095 - 64)));
        }
    return out;
}
static int count(const std::vector<std::string>& log, const std::string& prefix, const std::string& part) {
    int k = 0;
    for (const auto& l : log) k += l.rfind(prefix, 0) == 0 && l.find(part) != std::string::npos ? 1 : 0;
    return k;
}

struct Stop {};
static void memoryGuard() {
    const int GW = 4096, GH = 4000; // 16.4 MP, above kHybridClassicPixels
    std::vector<std::vector<uint16_t>> raw(2, std::vector<uint16_t>(size_t(GW) * GH));
    for (size_t f = 0; f < raw.size(); ++f)
        for (int y = 0; y < GH; ++y)
            for (int x = 0; x < GW; ++x) raw[f][size_t(y) * GW + x] = uint16_t(64 + ((x + int(f) * 3) / 16 * 37 + y / 16 * 91) % 2000);
    HybridInput in;in.w = GW;in.h = GH;in.cfa = 0;in.white = 4095;in.black.fill(64);in.mosaic = 1;
    for (size_t f = 0; f < raw.size(); ++f) {
        HybridFrame fr;fr.raw = raw[f].data();fr.role = kRoleNormal;fr.exposure = 1.f;fr.iso = 800;
        fr.slope = 4e-4f;fr.offset = 2e-6f;fr.orderMs = float(f) * 33.f;
        in.frames.push_back(fr);
    }
    // need = 18.75 x 16.4 MP = 293 MB with the homography, 11.5 x 16.4 MP = 180 MB without: a quarter of MemAvailable is 260 MB
    setenv("SCAM_LA_GPU_MEMAVAIL_MB", "1040", 1);
    for (const int gpuAlign : {1, 0}) {
        HybridTuning t;
        t.laGpu = 2;t.gpuAlign = gpuAlign;
        std::vector<std::string> log;
        std::mutex lock;
        const auto main = std::this_thread::get_id();
        auto report = [&](const std::string& l) {
            {std::lock_guard<std::mutex> g(lock);log.push_back(l);}
            if (l.rfind("HYBRID GPU ALIGN f=", 0) == 0 && std::this_thread::get_id() == main) throw Stop{};
        };
        ScamAlignment align = [](Burst&) -> std::array<BackwardHomography, 7> { throw Stop{}; };
        bool stopped = false;
        try {hybridReconstruct(in, t, align, report, nullptr, nullptr);} catch (const Stop&) {stopped = true;}
        const int skipped = count(log, "HYBRID LOCAL ALIGN: GPU field skipped", "");
        for (const auto& l : log)
            if (l.rfind("HYBRID LOCAL ALIGN: GPU field skipped", 0) == 0) std::printf("| %s\n", l.c_str());
        const std::string run = "memory guard, gpuAlign " + std::to_string(gpuAlign) + ": ";
        expect(stopped, run + "the merge reached its alignment");
        expect(skipped == (gpuAlign ? 1 : 0), run + (gpuAlign ? "the GPU field skipped (the homography's memory counted)"
                                                               : "the GPU field kept (the field's memory alone)"));
    }
    unsetenv("SCAM_LA_GPU_MEMAVAIL_MB");
}

int main() {
    memoryGuard();
    const auto s = scene();
    struct F { int dx, dy, role; float exposure; };
    const std::vector<F> fs{{0, 0, kRoleNormal, 1.f}, {2, 0, kRoleUltrashort, 0.125f}, {-2, 2, kRoleUltrashort, 0.125f},
                            {4, -2, kRoleNormal, 1.f}, {-4, 2, kRoleNormal, 1.f}, {2, 4, kRoleNormal, 1.f}};
    std::vector<std::vector<uint16_t>> raw;
    for (size_t f = 0; f < fs.size(); ++f) raw.push_back(bayer(s, fs[f].dx, fs[f].dy, fs[f].exposure, 50 + unsigned(f)));
    HybridInput in;in.w = W;in.h = H;in.cfa = 0;in.white = 4095;in.black.fill(64);in.mosaic = 1;
    for (size_t f = 0; f < fs.size(); ++f) {
        HybridFrame fr;fr.raw = raw[f].data();fr.role = fs[f].role;fr.exposure = fs[f].exposure;fr.iso = 800;
        fr.slope = 4e-4f / fs[f].exposure;fr.offset = 2e-6f / (fs[f].exposure * fs[f].exposure);fr.orderMs = float(f) * 33.f;
        in.frames.push_back(fr);
    }
    // the corner tracker is not asked for (every GPU homography holds); it fails the check if it is
    bool trackerAsked = false;
    ScamAlignment align = [&](Burst&) { trackerAsked = true;return std::array<BackwardHomography, 7>{}; };
    for (const int regEach : {1, 0}) {
        HybridTuning t;
        t.laGpu = 2;t.gpuAlign = 1;t.gpuAlignUsRegister = regEach;
        t.bentoUsRegister = 2;t.laUltrashort = 1;t.bentoBlend = 1;t.bentoRefuseScope = 1;t.bentoBlendCellMode = 2;
        std::vector<std::string> log;
        std::mutex lock;
        auto report = [&](const std::string& l) {std::lock_guard<std::mutex> g(lock);log.push_back(l);};
        const std::vector<float> out = hybridReconstruct(in, t, align, report, nullptr, nullptr);
        for (const auto& l : log)
            if (l.rfind("HYBRID GPU ALIGN", 0) == 0 || l.rfind("HYBRID US REGISTER", 0) == 0) std::printf("| %s\n", l.c_str());
        const std::string run = "gpuAlignUsRegister " + std::to_string(regEach) + ": ";
        expect(count(log, "HYBRID GPU ALIGN f=", " ok:") == int(fs.size()) - 1, run + "every frame aligned by its GPU homography");
        expect(!trackerAsked, run + "the corner tracker not asked for");
        expect(count(log, "HYBRID US REGISTER", "not needed") == (regEach ? 0 : 2),
               run + (regEach ? "both ultrashort frames registered" : "both registrations skipped after good homographies"));
        expect(count(log, "HYBRID US REGISTER", "frame ") == 2, run + "one registration line per ultrashort frame");
        bool finite = !out.empty();
        double sum = 0;
        for (float v : out) {finite &= std::isfinite(v);sum += v;}
        expect(finite, run + "a finite merge (" + std::to_string(out.size()) + " values, mean " + std::to_string(out.empty() ? 0.0 : sum / double(out.size())) + ")");
    }
    {
        HybridTuning t;
        t.laGpu = 2;t.gpuAlign = 1;
        t.bentoUsRegister = 2;t.laUltrashort = 1;t.bentoBlend = 1;t.bentoRefuseScope = 1;t.bentoBlendCellMode = 2;
        std::vector<std::string> log;
        std::mutex lock;
        auto report = [&](const std::string& l) {std::lock_guard<std::mutex> g(lock);log.push_back(l);};
        // the fields from the homographies come first, in the alignment's order (the ultrashort frames 1, 2 have none): frame 3's
        // field, then frame 4's (it fails; frame 5's homography is in flight or queued then)
        laGpuFaultField = 2;
        std::vector<float> out;
        std::string error;
        try {out = hybridReconstruct(in, t, align, report, nullptr, nullptr);}
        catch (const std::exception& e) {error = std::string("threw: ") + e.what();}
        catch (...) {error = "threw a non-std exception";}
        laGpuFaultField = 0;
        for (const auto& l : log)
            if (l.rfind("HYBRID GPU ALIGN", 0) == 0 || l.rfind("HYBRID LOCAL ALIGN", 0) == 0 || l.rfind("HYBRID ALIGN", 0) == 0
                || l.rfind("HYBRID PLAIN", 0) == 0) std::printf("| %s\n", l.c_str());
        const std::string run = "GPU field fault: ";
        expect(error.empty(), run + "the merge goes on" + (error.empty() ? "" : " (" + error + ")"));
        expect(count(log, "HYBRID GPU ALIGN f=4 ", " ok:") == 1, run + "frame 4 keeps the homography whose field failed");
        expect(count(log, "HYBRID GPU ALIGN f=5 ", "GPU failed: LA GPU frame GL error (injected)") == 1,
               run + "frame 5 answered 'GPU failed' by the failed worker");
        float tx = 99.f, ty = 99.f;
        for (const auto& l : log)
            if (l.rfind("HYBRID GPU ALIGN f=5: no GPU homography: global translation", 0) == 0)
                std::sscanf(l.c_str(), "HYBRID GPU ALIGN f=5: no GPU homography: global translation (%f, %f)", &tx, &ty);
        // frame 5 at (+2, +4): the base's point p is the donor's p - (2, 4); the guide pyramid's step is 4 RAW px (globalShift: half
        // a step off at most, F6 takes the rest), the identity start was 4 px off in y
        expect(std::abs(tx + 2.f) <= 2.01f && std::abs(ty + 4.f) <= 2.01f,
               run + "frame 5 aligned by its global translation (" + std::to_string(tx) + ", " + std::to_string(ty) + "; at -2, -4)");
        expect(count(log, "HYBRID ALIGN: frames", "refused by the alignment") == 0, run + "no frame started from the identity");
        expect(count(log, "HYBRID LOCAL ALIGN: GPU failed (LA GPU frame GL error (injected))", "the CPU computes the field") == 1,
               run + "the CPU computes the field");
        bool finite = !out.empty();
        double sum = 0;
        for (float v : out) {finite &= std::isfinite(v);sum += v;}
        expect(finite, run + "a finite merge (" + std::to_string(out.size()) + " values, mean " + std::to_string(out.empty() ? 0.0 : sum / double(out.size())) + ")");
        expect(!trackerAsked, run + "the corner tracker not asked for");
    }
    std::printf("check_hybrid_gpu_align: %s\n", fails ? "FAILED" : "OK");
    return fails ? 1 : 0;
}
