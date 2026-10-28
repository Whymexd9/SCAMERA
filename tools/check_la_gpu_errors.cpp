// The P364 GPU homography and field (scam-la-gpu.h) on their error paths and their memory, on a GLES 3.1 context (Mesa llvmpipe on
// the host: LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless).
//  A. LaGpuWorker: a GL error in the field the homography hands on (submitFromHomography, or the collect() that frees its slot)
//     must not leave the popped homography's promise unset: estimate(...).get() returns the homography (the merge's est[f].get()
//     threw std::future_error 'Broken promise' and the shot was lost), the worker reports the failure (the F6 stage takes the CPU
//     field).
//  B. LaGpu::homCollect: a readback that throws consumes its slot: the next collect returns the next frame's homography (it returned
//     the failed frame's again, and the counters stayed one behind for the rest of the burst).
//  C. The memory the GPU field and homography hold from the alignment to the F6 stage (two RAW uploads and the base levels for the
//     field, two more RAW uploads and the L0..L3 levels of two donors for the homography) within the figure the merge's memory guard
//     counts per RAW pixel (kLaGpuHomBytesPerPixel), and above the field's own figure (kLaGpuFieldBytesPerPixel, all the guard counted
//     before).
//  D. B in LaGpuWorker, with the burst's estimates queued at once: only the frame whose readback failed (SCAM_LA_GPU_FAULTS)
//     is answered with the error, the others get their own homographies (the next two were refused 'two homographies in flight
//     already' and the one in flight got the failed frame's homography).
// Built with -DSCAM_LA_GPU_FAULTS (LaGpu's injected readback failure) and -fsanitize=address,undefined.
#ifndef SCAM_LA_GPU_FAULTS
#error "build with -DSCAM_LA_GPU_FAULTS"
#endif
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-la-gpu.h"
#include <cstdio>
#include <random>
using namespace scam;

static const int W = 2048, H = 1536;
static int fails = 0;
static void expect(bool ok, const char* what) {
    std::printf("%s: %s\n", ok ? "ok" : "FAIL", what);
    if (!ok) ++fails;
}
// a textured Bayer scene, its content shifted by (sx, sy) RAW px
static std::vector<uint16_t> frame(int sx, int sy) {
    std::mt19937 r(7);
    std::vector<float> blobs; // x, y, radius, level
    for (int i = 0; i < 3000; ++i) blobs.insert(blobs.end(), {float(r() % 2200) - 60, float(r() % 1700) - 60, float(4 + r() % 14), float(r() % 1000) / 1000.f});
    std::vector<float> img(size_t(W) * H, 0.2f);
    for (size_t b = 0; b < blobs.size(); b += 4) {
        const int x0 = int(blobs[b] - blobs[b + 2]) - sx, x1 = int(blobs[b] + blobs[b + 2]) - sx;
        const int y0 = int(blobs[b + 1] - blobs[b + 2]) - sy, y1 = int(blobs[b + 1] + blobs[b + 2]) - sy;
        for (int y = std::max(0, y0); y < std::min(H, y1); ++y)
            for (int x = std::max(0, x0); x < std::min(W, x1); ++x) img[size_t(y) * W + x] = 0.1f + 0.6f * blobs[b + 3];
    }
    std::vector<uint16_t> out(size_t(W) * H);
    for (size_t i = 0; i < out.size(); ++i) out[i] = uint16_t(64 + img[i] * (4095 - 64));
    return out;
}
// the merge's grids (laGrid of L0 with laWin 16 / laStride 8, of L1 with 16 / 8); hugeL1: an L1 grid whose median dispatch exceeds
// GL_MAX_COMPUTE_WORK_GROUP_COUNT (65535 groups of 64), a GL_INVALID_VALUE in the field passes that stands in for any GL error or
// failed map there (out of memory, a context reset, a driver error)
static void setup(LaGpu& gpu, const std::vector<uint16_t>& base, bool hugeL1) {
    LaGpuBurst b;b.w = W;b.h = H;b.cfa = 0;b.canonical = true;b.white = 4095;b.black = {64, 64, 64, 64};
    const int w0 = W / 2, h0 = H / 2, w1 = w0 / 2, h1 = h0 / 2;
    LaGpuGrid g0{(w0 - 16) / 8 + 1, (h0 - 16) / 8 + 1, 16, 8, 2}, g1{(w1 - 16) / 8 + 1, (h1 - 16) / 8 + 1, 16, 8, 4};
    if (hugeL1) g1 = {1, 64 * 65536 + 64, 16, 8, 4};
    LaGpuTune t;t.itersCoarse = 0;
    gpu.base(b, base.data(), 1e-3f, 1e-5f, g0, g1, t);
}

int main() {
    const auto base = frame(0, 0), a = frame(6, 2), bb = frame(-10, 4), c = frame(3, -5), d = frame(-4, -3);
    // ---- B: a failed homography readback
    {
        LaGpu gpu("");
        setup(gpu, base, false);
        gpu.homSubmit(a.data(), 1.f, 1);
        gpu.homSubmit(bb.data(), 1.f, 2);
        glBindBuffer(0xDEAD, 0); // a GL error pending at frame 1's readback (check("homography readback") throws, as a failed map would)
        bool threw = false;
        try { gpu.homCollect(); } catch (const std::exception& e) { threw = true; std::printf("B: frame 1's collect threw: %s\n", e.what()); }
        expect(threw, "B: frame 1's collect threw on the GL error");
        expect(gpu.homInFlight() == 1, "B: frame 1's slot consumed (one homography in flight)");
        const LaGpuHom r2 = gpu.homCollect();
        std::printf("B: frame 2's collect: ok=%d coarse (%+.1f, %+.1f) h tx=%+.2f ty=%+.2f inliers %d (frame 1 at +6,+2, frame 2 at -10,+4)\n",
                    int(r2.ok), r2.coarseX, r2.coarseY, r2.h[2], r2.h[5], r2.inliers);
        expect(r2.ok && std::abs(r2.h[2] - 10.f) < 1.f && std::abs(r2.h[5] + 4.f) < 1.f, "B: frame 2's collect returns frame 2's homography");
        expect(gpu.homInFlight() == 0, "B: nothing in flight after frame 2");
        bool third = false;
        try { gpu.homCollect(); } catch (const std::exception&) { third = true; }
        expect(third, "B: a third collect finds nothing in flight");
        // the slots go on: a third frame on the consumed slot
        gpu.homSubmit(c.data(), 1.f, 3);
        const LaGpuHom r3 = gpu.homCollect();
        std::printf("B: frame 3: ok=%d h tx=%+.2f ty=%+.2f (at +3,-5)\n", int(r3.ok), r3.h[2], r3.h[5]);
        expect(r3.ok && std::abs(r3.h[2] + 3.f) < 1.f && std::abs(r3.h[5] - 5.f) < 1.f, "B: frame 3 after the failure gets its own homography");
    }
    // ---- A: a GL error in the field from a homography
    {
        LaGpuWorker worker(3, [&] {
            auto g = std::make_unique<LaGpu>("");
            setup(*g, base, true);
            return g;
        });
        auto fut = worker.estimate(1, a.data(), 1.f, /*field=*/true);
        bool answered = false;
        try {
            const LaGpuHom r = fut.get();
            answered = true;
            std::printf("A: estimate: ok=%d why=%s inliers %d h tx=%+.2f ty=%+.2f\n", int(r.ok), r.why.c_str(), r.inliers, r.h[2], r.h[5]);
            expect(r.ok && std::abs(r.h[2] + 6.f) < 1.f && std::abs(r.h[5] + 2.f) < 1.f, "A: the alignment keeps the homography whose field failed");
        } catch (const std::future_error& e) {
            std::printf("A: estimate().get() threw std::future_error: %s\n", e.what());
        }
        expect(answered, "A: estimate() answered (no broken promise)");
        // the failure reaches the F6 stage: the field never becomes ready, wait() says the GPU failed, error() says why
        expect(!worker.wait({1}), "A: the worker failed (the F6 stage takes the CPU field)");
        std::printf("A: worker error: %s\n", worker.error().c_str());
        expect(!worker.error().empty(), "A: the worker reports its error");
        const LaGpuHom later = worker.estimate(2, bb.data(), 1.f, true).get();
        expect(!later.ok && later.why.rfind("GPU failed", 0) == 0, "A: a later estimate is answered 'GPU failed'");
    }
    // ---- D: a failed homography readback in the worker, the burst's estimates queued at once (as the merge asks for them)
    {
        LaGpuWorker worker(5, [&] {
            auto g = std::make_unique<LaGpu>("");
            setup(*g, base, false);
            g->faultHomReadbacks = 1; // frame 1's readback
            return g;
        });
        const std::vector<const std::vector<uint16_t>*> raws{&a, &bb, &c, &d};
        const float tx[4] = {-6.f, 10.f, -3.f, 4.f}, ty[4] = {-2.f, -4.f, 5.f, 3.f}; // frames at (+6,+2) (-10,+4) (+3,-5) (-4,-3)
        std::vector<std::shared_future<LaGpuHom>> est;
        for (int f = 1; f <= 4; ++f) est.push_back(worker.estimate(f, raws[size_t(f - 1)]->data(), 1.f, false));
        for (int f = 1; f <= 4; ++f) {
            const LaGpuHom r = est[size_t(f - 1)].get();
            std::printf("D: frame %d: ok=%d why=%s h tx=%+.2f ty=%+.2f\n", f, int(r.ok), r.why.c_str(), r.h[2], r.h[5]);
            const std::string what = "D: frame " + std::to_string(f) + (f == 1 ? " answered with its readback's error" : " gets its own homography");
            if (f == 1) expect(!r.ok && r.why.rfind("GPU error", 0) == 0, what.c_str());
            else expect(r.ok && std::abs(r.h[2] - tx[f - 1]) < 1.f && std::abs(r.h[5] - ty[f - 1]) < 1.f, what.c_str());
        }
    }
    // ---- C: the memory held from the alignment to the F6 stage
    {
        LaGpu gpu("");
        setup(gpu, base, false);
        const double pix = double(W) * H;
        const double field = double(gpu.bytesHeld());
        // the homographies of two donors (both slots), their fields on their levels, then two fields from RAW uploads (the ultrashort
        // frames with laUltrashort: kRaw and kRaw2)
        gpu.homSubmit(a.data(), 1.f, 1);
        gpu.homSubmit(bb.data(), 1.f, 2);
        LaGpuHom h1 = gpu.homCollect();
        gpu.submitFromHomography(h1.h.data(), 1.f);
        LaGpuHom h2 = gpu.homCollect();
        gpu.submitFromHomography(h2.h.data(), 1.f);
        gpu.collect();gpu.collect();
        gpu.submit(c.data(), 8.f, h1.h.data(), 1.f);
        gpu.submit(d.data(), 8.f, h2.h.data(), 1.f);
        gpu.collect();gpu.collect();
        const double held = double(gpu.bytesHeld());
        std::printf("C: base %.2f B/px, with the homography and four fields %.2f B/px (guard: field %.2f, homography %.2f B/px)\n",
                    field / pix, held / pix, kLaGpuFieldBytesPerPixel, kLaGpuHomBytesPerPixel);
        expect(held <= kLaGpuHomBytesPerPixel * pix, "C: the homography's guard figure covers what is held");
        expect(held > kLaGpuFieldBytesPerPixel * pix, "C: the field's figure alone undercounts the homography");
    }
    std::printf("check_la_gpu_errors: %s\n", fails ? "FAILED" : "OK");
    return fails ? 1 : 0;
}
