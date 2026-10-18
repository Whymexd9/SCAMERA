// P213c: the chroma median during the native merge (MosaicChromaOverlap in scam-hybrid.h) against what the merge's caller ran after
// the merge before it: shiftOrigin to the RGGB origin, the oscillation map of the base sites (verbatim below, c1aef307's
// hybridReconstructMosaicNativeMerge) and the full-frame planes of the chroma median (tools/check_chroma_band_ref.h, c1aef307).
// Byte for byte (memcmp) on synthetic merges:
//   - a "merge" thread writes the rows of the result in strips of random height (1..300 rows, at random moments) into a zeroed
//     output and hands every strip to the sink as the readback does (rows()), then finish(); the result must be the reference;
//   - the shift of every CFA phase (dx, dy = 0 or the grid), Quad and Tetra blocks, the 2x grid (block 2b), T2 (Quad sites from
//     a mosaic of its own), contents of the merged rows (noise, gratings, edges, NaN and negatives, black, clipped, wide range,
//     dots, infinities, noise at the gate) and of the RAW (noise, gratings of 2..6 sites, clipped areas, black);
//   - threads during the merge and for the last rows: 1/1, 3/6, 2/8;
//   - a retry (begin() again after part of the rows: the merge starts over and writes the rows again), a stop half way (no
//     result, the thread joined), a grid the sink was not made for (finish() false, the rows untouched);
//   - the map of MosaicOscSource (the code the caller runs now, also without the overlap) equals the verbatim map.
// With -DSCAM_CHROMA_RING_CHECK every ring row read (the stream's rings, the rings of f and of the map) is checked. Phone (NDK clang -O2,
// aarch64: the FMA contractions of the worker) and host (g++, CI) builds must both pass. Argument "quick" (CI): every seventh case, one
// team of threads.
// scam-hybrid.h is not self-contained: the worker includes scam-capture.h first.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include "check_chroma_band_ref.h"
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>
#include <thread>
#include <vector>
using namespace scam;

static int failures = 0;

// The oscillation map of c1aef307's hybridReconstructMosaicNativeMerge, verbatim but for the names of its inputs.
static std::vector<float> oscReference(const uint16_t* raw, const uint16_t* q0, const std::array<float, 64>& gain, const std::array<float, 4>& black,
                                       int W, int MW, int mb, int bw, int bh, double range, float slope, float offset) {
    const int bq = mb;
    std::vector<float> f(size_t(bw) * bh, 0.f), osc;
    mergeRowBands(bh, [&](int j0, int j1) {
        std::vector<double> d(size_t(bq) * bq);
        for (int J = j0; J < j1; ++J) for (int I = 0; I < bw; ++I) {
            double m = 0;
            for (int c = 0; c < bq; ++c) for (int a = 0; a < bq; ++a) {
                const int x = bq * I + a, y = bq * J + c;
                d[size_t(c) * bq + a] = q0 ? double(q0[size_t(y) * MW + x]) - black[((J & 1) << 1) | (I & 1)]
                                           : (double(raw[size_t(y) * W + x]) - black[((y & 1) << 1) | (x & 1)]) * gain[((y & 7) << 3) | (x & 7)];
                m += d[size_t(c) * bq + a];
            }
            m /= double(bq * bq);
            const double sigma = std::sqrt(std::max(slope * std::max(m, 0.0) / range + offset, 0.0)) * range;
            const double gate = 0.04 * std::max(m, 0.0) + 3.0 * sigma;
            double changes = 0; int pairs = 0;
            for (int c = 0; c < bq; ++c) for (int a = 0; a < bq; ++a) {
                const double p0 = d[size_t(c) * bq + a] - m;
                if (a + 1 < bq) { ++pairs; changes += mosaicSoftSignChange(p0, d[size_t(c) * bq + a + 1] - m, gate, kMosaicOscTau); }
                if (c + 1 < bq) { ++pairs; changes += mosaicSoftSignChange(p0, d[size_t(c + 1) * bq + a] - m, gate, kMosaicOscTau); }
            }
            f[size_t(J) * bw + I] = pairs ? float(changes / pairs) : 0.f;
        }
    });
    osc.assign(f.size(), 0.f);
    mergeRowBands(bh, [&](int j0, int j1) { for (int J = j0; J < j1; ++J) for (int I = 0; I < bw; ++I) {
        double s = 0; int cnt = 0;
        for (int dj = -2; dj <= 2; ++dj) for (int di = -2; di <= 2; ++di) { const int y = J + dj, x = I + di; if (x < 0 || y < 0 || x >= bw || y >= bh) continue; s += f[size_t(y) * bw + x]; ++cnt; }
        osc[size_t(J) * bw + I] = float(s / std::max(cnt, 1)); } });
    return osc;
}

// merged rows (as tools/check_chroma_band.cpp)
static std::vector<float> frame(int w, int h, int content, std::mt19937& rng) {
    std::vector<float> rgb(size_t(w) * h * 3);
    std::uniform_real_distribution<float> n(-0.01f, 0.01f), u01(0.f, 1.f);
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) for (int c = 0; c < 3; ++c) {
        float v = 0;
        switch (content) {
            case 0: v = 0.2f + n(rng) * 5.f; break;
            case 1: v = 0.3f * (1.f + 0.8f * std::sin(0.9f * float(x) + 0.7f * float(y) * float(c + 1))); break;
            case 2: v = (x > w / 3 ? 0.8f : 0.05f) * (y > h / 2 ? 1.2f : 0.6f) + n(rng); break;
            case 3: v = u01(rng) < 0.02f ? std::nanf("") : u01(rng) < 0.1f ? -0.05f * u01(rng) : 0.4f * u01(rng); break;
            case 4: v = 0.f; break;
            case 5: v = u01(rng) < 0.3f ? 1.f : u01(rng) < 0.1f ? 3.5f : 0.9f + n(rng); break;
            case 6: v = std::pow(u01(rng), 4.f) * 8.f; break;
            case 7: v = u01(rng) < 0.003f ? 50.f : 0.01f + n(rng) * 0.5f; break;
            case 8: { const float x8[] = {INFINITY, -INFINITY, 1e30f, 1e-30f, -0.f, 1e-40f, -1e30f, 0.3f};
                      v = u01(rng) < 0.05f ? x8[size_t(u01(rng) * 7.99f)] : 0.3f + n(rng); break; }
            default: v = 0.2f + n(rng) * 0.4f; break;
        }
        rgb[(size_t(y) * w + x) * 3 + c] = v;
    }
    return rgb;
}

// a RAW mosaic of W x H (10-bit codes, black 64, white 1023)
static std::vector<uint16_t> mosaic(int W, int H, int content, std::mt19937& rng) {
    std::vector<uint16_t> raw(size_t(W) * H);
    std::normal_distribution<float> n(0.f, 1.f);
    std::uniform_real_distribution<float> u01(0.f, 1.f);
    const int period = 2 + int(u01(rng) * 4.99f);
    for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
        float v = 64.f;
        switch (content % 4) {
            case 0: v = 300.f + 12.f * n(rng); break;                                                                  // noise
            case 1: v = 400.f + 250.f * float(((x / period) + (y / period)) & 1) + 8.f * n(rng); break;              // gratings
            case 2: v = (x > W / 2 && y < H / 3) ? 1023.f : 150.f + 30.f * std::sin(0.4f * float(x * y % 97)) + 5.f * n(rng); break; // clipped
            default: v = u01(rng) < 0.5f ? 64.f : 64.f + 3.f * std::abs(n(rng)); break;                               // black
        }
        raw[size_t(y) * W + x] = uint16_t(std::clamp(std::lround(v), 0L, 1023L));
    }
    return raw;
}

// the merge thread: rows of `merged` into `out` in strips, each handed to the sink after it is written
static void feed(HybridRowsSink& sink, std::vector<float>& out, const std::vector<float>& merged, int w, int upTo, unsigned seed) {
    std::mt19937 r(seed);
    std::uniform_int_distribution<int> strip(1, 300), pause(0, 3);
    const size_t row = size_t(w) * 3;
    for (int y0 = 0; y0 < upTo;) {
        const int y1 = std::min(upTo, y0 + strip(r));
        std::memcpy(out.data() + size_t(y0) * row, merged.data() + size_t(y0) * row, size_t(y1 - y0) * row * sizeof(float));
        sink.rows(out.data(), y1);
        if (pause(r) == 0) std::this_thread::sleep_for(std::chrono::microseconds(200 * pause(r)));
        y0 = y1;
    }
}

static std::string where(int vw, int vh, int bq, int k2, bool t2, int cfa, int content, int rawContent) {
    return std::to_string(vw) + "x" + std::to_string(vh) + " blocks of " + std::to_string(bq) + (t2 ? " T2" : "") + " grid x" + std::to_string(k2) +
           " cfa " + std::to_string(cfa) + " content " + std::to_string(content) + " raw " + std::to_string(rawContent);
}

int main(int argc, char** argv) {
    const bool quick = argc > 1 && std::string(argv[1]) == "quick";
    std::mt19937 rng(2131);
    struct Size { int vw, vh; };            // binned frame: vw x vh colour blocks
    const Size sizes[] = {{64, 64}, {66, 70}, {96, 130}, {130, 66}, {64, 200}};
    struct Route { int bq, k2; bool t2; };  // sensor block, output grid per merged site, Tetra T2
    const Route routes[] = {{2, 1, false}, {2, 2, false}, {4, 1, false}, {4, 2, false}, {4, 1, true}};
    struct Team { int during, last; };
    const Team teams[] = {{1, 1}, {3, 6}, {2, 8}};
    long cases = 0, results = 0, maps = 0;
    int combination = 0;
    for (const Size& sz : sizes) for (const Route& rt : routes) for (int cfa = 0; cfa < 4; ++cfa) for (int content = 0; content < 10; ++content) {
        if (quick && combination++ % 7 != 0) continue; // quick: every seventh combination (it walks through the sizes, routes, phases and contents)
        const int rawContent = (content + cfa) % 4;
        // the native path's geometry: sensor W x H, merged mosaic MW x MH of block mb, output ow x oh of block mb k2 (grid)
        const int bq = rt.bq, W = sz.vw * bq, H = sz.vh * bq, mb = rt.t2 ? 2 : bq, MW = rt.t2 ? W / 2 : W, MH = rt.t2 ? H / 2 : H, sub = bq / mb;
        const int grid = mb * rt.k2, ow = grid * sz.vw, oh = grid * sz.vh, bw = MW / mb, bh = MH / mb;
        const std::vector<uint16_t> raw = mosaic(W, H, rawContent, rng);
        std::vector<uint16_t> quad;
        if (rt.t2) { quad.resize(size_t(MW) * MH); for (size_t i = 0; i < quad.size(); ++i) quad[i] = raw[(i / MW) * 2 * W + (i % MW) * 2]; }
        std::array<float, 64> gain; for (int k = 0; k < 64; ++k) gain[k] = 0.99f + 0.0003f * float((k * 37) % 64);
        const std::array<float, 4> black{64.f, 64.5f, 63.75f, 64.f};
        const float white = 1023.f, slopeIn = 0.0012f, offsetIn = 0.00003f;
        MosaicOscSource src;
        src.raw = raw.data(); src.gain = &gain; src.quad = rt.t2 ? quad.data() : nullptr; src.black = black;
        src.W = W; src.MW = MW; src.block = mb; src.bw = bw; src.bh = bh;
        src.range = double(white) - 0.25 * (black[0] + black[1] + black[2] + black[3]);
        src.slope = std::max(slopeIn, 1e-9f) / float(sub * sub); src.offset = std::max(offsetIn, 0.f) / float(sub * sub);
        const std::vector<float> oscRef = oscReference(raw.data(), src.quad, gain, black, W, MW, mb, bw, bh, src.range, src.slope, src.offset);
        // the map the caller makes now without the overlap (MosaicOscSource over bands of rows)
        {
            std::vector<float> f(size_t(bw) * bh), osc(size_t(bw) * bh);
            mergeRowBands(bh, [&](int j0, int j1) { for (int J = j0; J < j1; ++J) src.signRow(J, f.data() + size_t(J) * bw); });
            mergeRowBands(bh, [&](int j0, int j1) { for (int J = j0; J < j1; ++J) src.boxRow(J, [&](int j) { return static_cast<const float*>(f.data() + size_t(j) * bw); }, osc.data() + size_t(J) * bw); });
            ++maps;
            if (std::memcmp(osc.data(), oscRef.data(), osc.size() * sizeof(float)) != 0) { std::printf("FAIL %s: oscillation map differs\n", where(sz.vw, sz.vh, bq, rt.k2, rt.t2, cfa, content, rawContent).c_str()); ++failures; }
        }
        const std::vector<float> merged = frame(ow, oh, content, rng);
        const int dx = (cfa & 1) * grid, dy = (cfa >> 1) * grid;
        std::vector<float> reference = merged;
        shiftOrigin(reference, ow, oh, 3, dx, dy);
        mosaicChromaMedianRef(reference, ow, oh, grid, &oscRef, false, nullptr, -1);
        ++cases;
        const std::string at = where(sz.vw, sz.vh, bq, rt.k2, rt.t2, cfa, content, rawContent);
        for (const Team& t : quick ? std::vector<Team>{teams[1]} : std::vector<Team>(std::begin(teams), std::end(teams))) {
            MosaicChromaOverlap ov(ow, oh, grid, src);
            ov.threads(t.during, t.last);
            std::vector<float> out(merged.size(), 0.f);
            const int variant = int(rng() % 3); // 0 plain, 1 a retry after part of the rows, 2 a stop half way first
            if (variant == 1) { // a failed attempt: the retry's begin() stops its consumer, then the merge zeroes out and starts over
                ov.begin(ow, oh, dx, dy);
                feed(ov, out, merged, ow, oh / 2 + 1, rng());
            } else if (variant == 2) {
                ov.begin(ow, oh, dx, dy);
                feed(ov, out, merged, ow, oh / 3 + 1, rng());
                ov.stop();
                if (ov.done()) { std::printf("FAIL %s: a stopped overlap has a result\n", at.c_str()); ++failures; }
            }
            ov.begin(ow, oh, dx, dy);
            std::fill(out.begin(), out.end(), 0.f); // HybridGpu::merge: out.assign(..., 0)
            std::thread merge([&] { feed(ov, out, merged, ow, oh, rng()); });
            merge.join();
            bool ok = false;
            try { ok = ov.finish(out.data()); } catch (const std::exception& e) { std::printf("FAIL %s: finish threw %s\n", at.c_str(), e.what()); ++failures; }
            ov.stop();
            ++results;
            if (!ok || !ov.done()) { std::printf("FAIL %s: no result (%s)\n", at.c_str(), ov.why.c_str()); ++failures; continue; }
            if (std::memcmp(out.data(), reference.data(), out.size() * sizeof(float)) != 0) {
                size_t first = 0;
                while (first < out.size() && std::memcmp(&out[first], &reference[first], sizeof(float)) == 0) ++first;
                std::printf("FAIL %s threads %d/%d variant %d: float %zu (row %zu) %.9g, after the merge %.9g\n", at.c_str(), t.during, t.last, variant,
                            first, first / (size_t(ow) * 3), out[first], reference[first]);
                ++failures;
            }
        }
        // a sink of another grid: refused, the rows untouched
        if (!quick && content == 0 && cfa == 1) {
            MosaicChromaOverlap ov(ow, oh, grid, src);
            ov.begin(ow + grid, oh, dx, dy);
            std::vector<float> out = merged;
            ov.rows(out.data(), oh);
            if (ov.finish(out.data()) || ov.done() || ov.why.empty() || std::memcmp(out.data(), merged.data(), out.size() * sizeof(float)) != 0) {
                std::printf("FAIL %s: a sink of another grid did something\n", at.c_str()); ++failures;
            }
        }
    }
    // the 50 MP row width (8192 px, Quad on the sensor grid) with the default threads, one CFA phase shifted
    {
        const int bq = 2, vw = 4096, vh = 75, W = vw * bq, H = vh * bq, ow = W, oh = H;
        const std::vector<uint16_t> raw = mosaic(W, H, 1, rng);
        std::array<float, 64> gain; gain.fill(1.f);
        const std::array<float, 4> black{64.f, 64.f, 64.f, 64.f};
        MosaicOscSource src;
        src.raw = raw.data(); src.gain = &gain; src.black = black; src.W = W; src.MW = W; src.block = bq; src.bw = vw; src.bh = vh;
        src.range = 1023.0 - 64.0; src.slope = 0.001f; src.offset = 0.00002f;
        const std::vector<float> oscRef = oscReference(raw.data(), nullptr, gain, black, W, W, bq, vw, vh, src.range, src.slope, src.offset);
        const std::vector<float> merged = frame(ow, oh, 1, rng);
        std::vector<float> reference = merged, out(merged.size(), 0.f);
        shiftOrigin(reference, ow, oh, 3, 0, bq);
        mosaicChromaMedianRef(reference, ow, oh, bq, &oscRef, false, nullptr, -1);
        MosaicChromaOverlap ov(ow, oh, bq, src);
        ov.begin(ow, oh, 0, bq);
        std::thread merge([&] { feed(ov, out, merged, ow, oh, 7); });
        merge.join();
        const bool ok = ov.finish(out.data());
        ++cases; ++results;
        if (!ok || std::memcmp(out.data(), reference.data(), out.size() * sizeof(float)) != 0) { std::printf("FAIL 8192x150: differs\n"); ++failures; }
        std::printf("8192x150 block 2: %s\n", ov.text().c_str());
    }
#ifdef SCAM_CHROMA_RING_CHECK
    const char* ring = ", every ring row read checked";
#else
    const char* ring = "";
#endif
    if (failures) { std::printf("FAIL: %d failures in %ld cases (%ld results)\n", failures, cases, results); return 1; }
    std::printf("PASS: the chroma median during the merge == shiftOrigin + the oscillation map + the full-frame planes after it, byte for byte: "
                "%ld cases, %ld results (strips of 1..300 rows, threads 1..8, retries, stops), %ld oscillation maps%s\n", cases, results, maps, ring);
    return 0;
}
