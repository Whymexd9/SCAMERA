// P371 (speed, gpu-merge-stream-output): the merge worker's RGB halves streamed into the app's output memfd during the merge
// (app/src/main/cpp/scam-stream-out.h, fed by scam::hybridFinalRowsHook from the plain merge's chroma consumer). Checked:
//   - halvesOf: the halves of scam::floatToHalf for every value (specials, subnormals, out of range), its sum and maximum;
//   - StreamOut's protocol on a memfd: a committed result is taken (the memfd holds exactly its halves, that long); without a
//     commit, with an event after it, for another buffer, rows out of order, an RGB above the cap, a non-empty or unmappable
//     output it is not, and the memfd it reserved is empty again (a non-empty output it did not reserve stays as it was); a
//     retry (the same size or another) starts over;
//   - with the real plain sink (MosaicChromaOverlap, emitFinal): strips of random height fed by a merge thread, the shift to the
//     RGGB origin, 1..8 threads, a retry half way, a stop half way: the streamed halves equal the halves of the finished result
//     byte for byte (every row reaches the hook once final), and a stopped merge leaves nothing taken.
//
// Build (CI):
//   g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_stream_out.cpp -ldl -lEGL -lGLESv2
// scam-hybrid.h is not self-contained: the worker includes scam-capture.h first.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include "../app/src/main/cpp/scam-stream-out.h"
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <random>
#include <string>
#include <thread>
#include <vector>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
using namespace scam;

static int failures = 0;
static void expect(bool ok, const std::string& what) {
    if (!ok) { std::printf("FAIL: %s\n", what.c_str()); ++failures; }
}

static off_t sizeOf(int fd) { struct stat st{}; return fstat(fd, &st) == 0 ? st.st_size : -1; }

// the memfd holds exactly the halves of `v`
static bool holds(int fd, const std::vector<float>& v) {
    if (sizeOf(fd) != off_t(v.size() * 2)) return false;
    std::vector<uint16_t> got(v.size());
    if (pread(fd, got.data(), got.size() * 2, 0) != ssize_t(got.size() * 2)) return false;
    for (size_t i = 0; i < v.size(); ++i) if (got[i] != floatToHalf(v[i])) return false;
    return true;
}

static std::vector<float> image(int w, int h, unsigned seed) {
    std::mt19937 r(seed);
    std::normal_distribution<float> n(0.f, 1.f);
    std::vector<float> v(size_t(w) * h * 3);
    for (size_t i = 0; i < v.size(); ++i) v[i] = 0.3f + 0.1f * n(r);
    return v;
}

// rows [0, h) to the stream in chunks of 1..max rows, as the consumer gives them
static void give(StreamOut& s, const std::vector<float>& v, int w, int h, int from, int to, unsigned seed, int max = 40) {
    std::mt19937 r(seed);
    std::uniform_int_distribution<int> step(1, max);
    for (int y = from; y < to;) { const int e = std::min(to, y + step(r)); s.event(v.data(), w, h, y, e); y = e; }
}

static void halves() {
    std::vector<float> v{0.f, -0.f, 1.f, -1.f, 65504.f, 65520.f, 1e9f, -1e9f, 6.1e-5f, 5.96e-8f, 2.98e-8f, 1e-10f, 0.333333f,
                         std::numeric_limits<float>::infinity(), -std::numeric_limits<float>::infinity(), std::nanf("")};
    std::mt19937 r(5);
    std::uniform_int_distribution<uint32_t> bits;
    for (int i = 0; i < 200000; ++i) { const uint32_t b = bits(r); float f; std::memcpy(&f, &b, 4); if (!std::isnan(f)) v.push_back(f); }
    for (int i = 0; i < 1001; ++i) v.push_back(float(i) * 0.37f - 50.f);
    std::vector<uint16_t> dst(v.size());
    double sum = 0; float maximum = 0;
    halvesOf(v.data(), dst.data(), v.size(), false, sum, maximum);
    bool same = true;
    for (size_t i = 0; i < v.size(); ++i) same &= dst[i] == floatToHalf(v[i]);
    expect(same, "halvesOf without stats: the halves of floatToHalf");
    // the stats over finite values (the worker's result is finite; a NaN / infinity only has to keep the halves)
    std::vector<float> f(v.begin() + 16, v.end());
    std::vector<uint16_t> d2(f.size());
    halvesOf(f.data(), d2.data(), f.size(), true, sum, maximum);
    double rs = 0; float rm = 0;
    same = true;
    for (size_t i = 0; i < f.size(); ++i) { rs += f[i]; rm = std::max(rm, f[i]); same &= d2[i] == floatToHalf(f[i]); }
    expect(same, "halvesOf with stats: the same halves");
    expect(std::fabs(sum - rs) <= 1e-9 * std::max(1.0, std::fabs(rs)) + 1e-3 && maximum == rm, "halvesOf: sum and maximum");
}

static void protocol() {
    const int w = 37, h = 29;
    const std::vector<float> v = image(w, h, 1);
    double s = 0; float m = 0;
    { // committed: taken, the memfd holds the halves, sum / max of the floats
        const int fd = memfd_create("t", 0);
        StreamOut out(dup(fd));
        out.event(nullptr, w, h, 0, 0);
        give(out, v, w, h, 0, h, 2);
        out.event(v.data(), w, h, -1, -1);
        expect(out.take(v, s, m), "committed: taken");
        double rs = 0; float rm = 0; for (float x : v) { rs += x; rm = std::max(rm, x); }
        expect(std::fabs(s - rs) < 1e-6 * std::fabs(rs) && m == rm, "committed: the floats' sum and max");
        expect(holds(fd, v), "committed: the memfd holds the halves, that long");
        close(fd);
    }
    { // a committed result stays in the memfd after the stream goes
        const int fd = memfd_create("t", 0);
        { StreamOut out(dup(fd)); out.event(nullptr, w, h, 0, 0); give(out, v, w, h, 0, h, 3); out.event(v.data(), w, h, -1, -1); out.take(v, s, m); }
        expect(holds(fd, v), "taken: kept after the stream goes");
        close(fd);
    }
    auto refused = [&](const char* what, auto&& run) {
        const int fd = memfd_create("t", 0);
        bool took = false;
        { StreamOut out(dup(fd)); took = run(out); }
        expect(!took, std::string(what) + ": not taken");
        expect(sizeOf(fd) == 0, std::string(what) + ": the memfd empty again");
        close(fd);
    };
    refused("no commit", [&](StreamOut& o) { o.event(nullptr, w, h, 0, 0); give(o, v, w, h, 0, h, 4); return o.take(v, s, m); });
    refused("an event after the commit", [&](StreamOut& o) {
        o.event(nullptr, w, h, 0, 0); give(o, v, w, h, 0, h, 5); o.event(v.data(), w, h, -1, -1); o.event(nullptr, w, h, 0, 0);
        return o.take(v, s, m);
    });
    refused("a commit before every row", [&](StreamOut& o) {
        o.event(nullptr, w, h, 0, 0); give(o, v, w, h, 0, h - 1, 6); o.event(v.data(), w, h, -1, -1); return o.take(v, s, m);
    });
    refused("another buffer", [&](StreamOut& o) {
        const std::vector<float> copy = v;
        o.event(nullptr, w, h, 0, 0); give(o, v, w, h, 0, h, 7); o.event(v.data(), w, h, -1, -1); return o.take(copy, s, m);
    });
    refused("rows out of order", [&](StreamOut& o) {
        o.event(nullptr, w, h, 0, 0); give(o, v, w, h, 0, 10, 8); give(o, v, w, h, 12, h, 9); o.event(v.data(), w, h, -1, -1);
        return o.take(v, s, m);
    });
    refused("rows of another buffer", [&](StreamOut& o) {
        const std::vector<float> copy = v;
        o.event(nullptr, w, h, 0, 0); give(o, v, w, h, 0, 10, 10); give(o, copy, w, h, 10, h, 11); o.event(v.data(), w, h, -1, -1);
        return o.take(v, s, m);
    });
    refused("a commit without an attempt", [&](StreamOut& o) { o.event(v.data(), w, h, -1, -1); return o.take(v, s, m); });
    refused("the RGB above the cap", [&](StreamOut& o) {
        const int bw = 8192, bh = 4096; // 192 MB of halves: never reserved, the rows never read
        float dummy[3]{};
        o.event(nullptr, bw, bh, 0, 0); o.event(dummy, bw, bh, 0, 1);
        const bool took = o.take(v, s, m);
        expect(o.reason().find("160 MB") != std::string::npos, "the RGB above the cap: the reason");
        return took;
    });
    { // a retry of the same size, then one of another size: the last attempt's rows
        const int fd = memfd_create("t", 0);
        StreamOut out(dup(fd));
        out.event(nullptr, w, h, 0, 0); give(out, v, w, h, 0, h / 2, 12);
        const std::vector<float> v2 = image(w, h, 13);
        out.event(nullptr, w, h, 0, 0); give(out, v2, w, h, 0, h, 14); out.event(v2.data(), w, h, -1, -1);
        expect(out.take(v2, s, m) && holds(fd, v2), "a retry of the same size: its rows");
        close(fd);
        const int fd2 = memfd_create("t", 0);
        StreamOut o2(dup(fd2));
        o2.event(nullptr, w, h, 0, 0); give(o2, v, w, h, 0, h, 15);
        const int w3 = 20, h3 = 11;
        const std::vector<float> v3 = image(w3, h3, 16);
        o2.event(nullptr, w3, h3, 0, 0); give(o2, v3, w3, h3, 0, h3, 17); o2.event(v3.data(), w3, h3, -1, -1);
        expect(o2.take(v3, s, m) && holds(fd2, v3), "a retry of another size: its rows, the memfd that long");
        close(fd2);
    }
    { // a non-empty output it did not reserve: not used, left as it was
        const int fd = memfd_create("t", 0);
        const char text[] = "someone else's bytes";
        expect(pwrite(fd, text, sizeof(text), 0) == ssize_t(sizeof(text)), "pwrite");
        bool took;
        { StreamOut out(dup(fd)); out.event(nullptr, w, h, 0, 0); give(out, v, w, h, 0, h, 18); out.event(v.data(), w, h, -1, -1); took = out.take(v, s, m); }
        char back[sizeof(text)]{};
        expect(!took && sizeOf(fd) == off_t(sizeof(text)) && pread(fd, back, sizeof(back), 0) == ssize_t(sizeof(back)) && std::memcmp(back, text, sizeof(text)) == 0,
               "a non-empty output: not used, untouched");
        close(fd);
    }
    { // a pipe: not used
        int p[2];
        expect(pipe(p) == 0, "pipe");
        StreamOut out(dup(p[1]));
        out.event(nullptr, w, h, 0, 0); give(out, v, w, h, 0, h, 19); out.event(v.data(), w, h, -1, -1);
        expect(!out.take(v, s, m), "a pipe: not used");
        close(p[0]); close(p[1]);
    }
}

// the merge thread: rows of `merged` into `out` in strips, each handed to the sink after it is written (check_chroma_overlap.cpp)
static void feed(HybridRowsSink& sink, std::vector<float>& out, const std::vector<float>& merged, int w, int upTo, unsigned seed) {
    std::mt19937 r(seed);
    std::uniform_int_distribution<int> strip(1, 120), pause(0, 3);
    const size_t row = size_t(w) * 3;
    for (int y0 = 0; y0 < upTo;) {
        const int y1 = std::min(upTo, y0 + strip(r));
        std::memcpy(out.data() + size_t(y0) * row, merged.data() + size_t(y0) * row, size_t(y1 - y0) * row * sizeof(float));
        sink.rows(out.data(), y1);
        if (pause(r) == 0) std::this_thread::sleep_for(std::chrono::microseconds(200 * pause(r)));
        y0 = y1;
    }
}

static void plainSink(long& cases) {
    std::mt19937 rng(371);
    struct Case { int w, h, block, dx, dy, during, last; };
    const Case all[] = {{96, 80, 1, 0, 0, 1, 1}, {130, 66, 1, 1, 1, 2, 4}, {200, 150, 2, 0, 2, 3, 6}, {66, 70, 2, 2, 2, 2, 8},
                        {256, 300, 4, 4, 0, 4, 8}, {64, 200, 1, 0, 1, 1, 3}};
    for (const Case& c : all) for (int variant = 0; variant < 3; ++variant) {
        const std::string at = std::to_string(c.w) + "x" + std::to_string(c.h) + " block " + std::to_string(c.block) + " shift " +
                               std::to_string(c.dx) + "," + std::to_string(c.dy) + " variant " + std::to_string(variant);
        const std::vector<float> merged = image(c.w, c.h, rng());
        std::vector<float> out(merged.size(), 0.f);
        const int fd = memfd_create("t", 0);
        auto stream = std::make_unique<StreamOut>(dup(fd));
        StreamOut* const s = stream.get();
        hybridFinalRowsHook() = [s](const float* rows, int w, int h, int y0, int y1) { s->event(rows, w, h, y0, y1); };
        auto sink = hybridPlainChromaSink(c.w, c.h, c.block);
        static_cast<MosaicChromaOverlap*>(sink.get())->threads(c.during, c.last);
        sink->emitFinal(true);
        if (variant == 1) { // a failed attempt half way: the retry's begin() starts over
            sink->begin(c.w, c.h, c.dx, c.dy);
            feed(*sink, out, merged, c.w, c.h / 2 + 1, rng());
        }
        sink->begin(c.w, c.h, c.dx, c.dy); // (stops the consumer of a previous attempt; the new one waits for rows)
        std::fill(out.begin(), out.end(), 0.f); // HybridGpu::merge: out.assign(..., 0)
        const int upTo = variant == 2 ? c.h / 2 : c.h;
        std::thread merge([&] { feed(*sink, out, merged, c.w, upTo, rng()); });
        merge.join();
        bool ok = false;
        if (variant == 2) sink->stop(); // a stop half way (an error in the merge): no result
        else {
            try { ok = sink->finish(out.data()); } catch (const std::exception& e) { std::printf("FAIL %s: finish threw %s\n", at.c_str(), e.what()); ++failures; }
            expect(ok, at + ": the sink finished the result");
            if (ok) hybridFinalRowsHook()(out.data(), c.w, c.h, -1, -1); // hybridReconstruct's commit
        }
        sink->stop();
        double sum = 0; float maximum = 0;
        const bool took = stream->take(out, sum, maximum);
        if (variant == 2) expect(!took && sizeOf(fd) == 0, at + ": a stopped merge leaves nothing taken, the memfd empty");
        else expect(took && holds(fd, out), at + ": the streamed halves == the halves of the finished result (" + stream->reason() + ")");
        hybridFinalRowsHook() = nullptr;
        stream.reset();
        close(fd);
        ++cases;
    }
    { // a sink not armed (a mosaic split's sub-frames, a large merge): no event, nothing taken
        const std::vector<float> merged = image(64, 64, 9);
        std::vector<float> out(merged.size(), 0.f);
        const int fd = memfd_create("t", 0);
        StreamOut stream(dup(fd));
        hybridFinalRowsHook() = [&stream](const float* rows, int w, int h, int y0, int y1) { stream.event(rows, w, h, y0, y1); };
        auto sink = hybridPlainChromaSink(64, 64, 1);
        sink->begin(64, 64, 0, 0);
        feed(*sink, out, merged, 64, 64, 10);
        expect(sink->finish(out.data()), "unarmed: finished");
        sink->stop();
        double sum = 0; float maximum = 0;
        expect(!stream.take(out, sum, maximum) && stream.rows() == 0 && sizeOf(fd) == 0, "unarmed: no rows, nothing taken");
        hybridFinalRowsHook() = nullptr;
        close(fd);
        ++cases;
    }
}

int main() {
    halves();
    protocol();
    long cases = 0;
    plainSink(cases);
    if (failures) { std::printf("stream out: %d failure(s)\n", failures); return 1; }
    std::printf("stream out: halvesOf == floatToHalf; StreamOut takes only a committed result (refused cases leave its memfd empty, "
                "another's untouched); the plain sink's streamed halves == the finished result's in %ld merges (retries, stops, "
                "shifts, 1..8 threads): ok\n", cases);
    return 0;
}
