// P213b: the streamed CPU chroma median (MosaicChromaStream in scam-hybrid.h) against the full-frame planes it replaced
// (tools/check_chroma_band_ref.h, verbatim from c1aef307): byte for byte (memcmp) on synthetic frames:
//   - contents: noise, gratings, edges, NaN and negatives, black, clipped and above white, a wide range, single bright dots;
//   - sizes: odd, narrower or shorter than the taps and boxes (1x1, 2x3, 5x1, 1x9), wide and tall ones;
//   - blocks 1, 2, 3, 4, 8; the oscillation map off, of the right size, of a wrong size (ignored);
//   - every case in several streams: steps of 32 (the default), 1, 5, 7 and 64 rows on 1, 2, 3 and 8 threads, and the rows fed
//     in random portions (advance() several times, as the merge's strips would);
//   - with -DSCAM_CHROMA_RING_CHECK every ring row read is checked to be the row asked for (an overwritten slot aborts).
// The bounded-rows strips the old path used above 64 MP are compared too (not equal: their column sums restarted at every
// strip; the largest difference is printed, on a subset). The phone build (NDK clang -O2, aarch64: the same FMA contractions as the worker)
// and the host build (g++, CI) must both pass. Argument "quick": fewer stream variants per case.
// scam-hybrid.h is not self-contained: the worker includes scam-capture.h first.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include "check_chroma_band_ref.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>
#include <vector>
using namespace scam;

static int failures = 0;

static std::vector<float> frame(int w, int h, int content, std::mt19937& rng) {
    std::vector<float> rgb(size_t(w) * h * 3);
    std::uniform_real_distribution<float> n(-0.01f, 0.01f), u01(0.f, 1.f);
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) for (int c = 0; c < 3; ++c) {
        float v = 0;
        switch (content) {
            case 0: v = 0.2f + n(rng) * 5.f; break;                                                          // noise
            case 1: v = 0.3f * (1.f + 0.8f * std::sin(0.9f * float(x) + 0.7f * float(y) * float(c + 1))); break; // gratings
            case 2: v = (x > w / 3 ? 0.8f : 0.05f) * (y > h / 2 ? 1.2f : 0.6f) + n(rng); break;               // edges
            case 3: v = u01(rng) < 0.02f ? std::nanf("") : u01(rng) < 0.1f ? -0.05f * u01(rng) : 0.4f * u01(rng); break; // NaN, negatives
            case 4: v = 0.f; break;                                                                           // black
            case 5: v = u01(rng) < 0.3f ? 1.f : u01(rng) < 0.1f ? 3.5f : 0.9f + n(rng); break;                // clipped, above white
            case 6: v = std::pow(u01(rng), 4.f) * 8.f; break;                                                 // wide range
            default: v = u01(rng) < 0.003f ? 50.f : 0.01f + n(rng) * 0.5f; break;                             // bright dots
        }
        rgb[(size_t(y) * w + x) * 3 + c] = v;
    }
    return rgb;
}

static std::string where(int w, int h, int block, int content, int oscMode) {
    return std::to_string(w) + "x" + std::to_string(h) + " block " + std::to_string(block) + " content " + std::to_string(content) +
           " osc " + std::to_string(oscMode);
}

int main(int argc, char** argv) {
    const bool quick = argc > 1 && std::string(argv[1]) == "quick";
    std::mt19937 rng(213);
    struct Size { int w, h; };
    const Size sizes[] = {{96, 311}, {257, 129}, {1000, 67}, {64, 64}, {130, 1030}, {33, 200}, {512, 48}, {1, 1}, {2, 3}, {5, 1},
                          {1, 9}, {17, 17}, {301, 7}, {2050, 96}};
    struct Variant { int band, threads; bool fed; };
    const Variant all[] = {{0, 8, false}, {1, 1, false}, {5, 2, false}, {7, 3, false}, {64, 8, false}, {0, 3, true}, {3, 8, true}};
    const Variant few[] = {{0, 8, false}, {7, 3, true}};
    long cases = 0, streams = 0, distinct = 0;
    float boundedWorst[2] = {0, 0}; // noise, gratings, edges / the extreme contents (finite values)
    for (const Size& sz : sizes) for (int block : {1, 2, 3, 4, 8}) for (int content = 0; content < 8; ++content) for (int oscMode = 0; oscMode < 3; ++oscMode) {
        const int w = sz.w, h = sz.h, bw = w / block, bh = h / block;
        if (oscMode && bw * bh == 0) continue; // no block of the map inside the frame
        const std::vector<float> input = frame(w, h, content, rng);
        std::vector<float> osc(oscMode == 1 ? size_t(bw) * bh : oscMode == 2 ? size_t(bw) * bh + 1 : 0);
        for (size_t i = 0; i < osc.size(); ++i) osc[i] = float((i * 7) % 23) / 22.f;
        const std::vector<float>* o = oscMode ? &osc : nullptr;
        std::vector<float> reference = input;
        mosaicChromaMedianRef(reference, w, h, block, o, false, nullptr, -1); // the full-frame planes
        if (std::memcmp(reference.data(), input.data(), input.size() * sizeof(float)) != 0) ++distinct;
        ++cases;
        for (const Variant& v : quick ? std::vector<Variant>(std::begin(few), std::end(few)) : std::vector<Variant>(std::begin(all), std::end(all))) {
            std::vector<float> rgb = input;
            MosaicChromaPasses passes;
            MosaicChromaStream stream(rgb.data(), w, h, block, o, v.band, v.threads, &passes);
            if (v.fed) {
                std::uniform_int_distribution<int> portion(1, 40);
                for (int ready = 0; ready < h;) {
                    ready = std::min(h, ready + portion(rng));
                    stream.advance(ready);
                    if (stream.done() > ready) { std::printf("FAIL %s: %d rows done of %d ready\n", where(w, h, block, content, oscMode).c_str(), stream.done(), ready); ++failures; }
                }
            } else stream.advance(h);
            ++streams;
            if (stream.done() != h) { std::printf("FAIL %s: %d of %d rows done\n", where(w, h, block, content, oscMode).c_str(), stream.done(), h); ++failures; }
            if (std::memcmp(rgb.data(), reference.data(), rgb.size() * sizeof(float)) != 0) {
                size_t first = 0;
                while (first < rgb.size() && std::memcmp(&rgb[first], &reference[first], sizeof(float)) == 0) ++first;
                std::printf("FAIL %s band %d threads %d%s: float %zu (row %zu) %.9g, the planes %.9g\n", where(w, h, block, content, oscMode).c_str(),
                            v.band, v.threads, v.fed ? " fed" : "", first, first / (size_t(w) * 3), rgb[first], reference[first]);
                ++failures;
            }
            for (size_t i = 1; i < rgb.size(); i += 3)
                if (std::memcmp(&rgb[i], &input[i], sizeof(float)) != 0) { std::printf("FAIL %s: green changed\n", where(w, h, block, content, oscMode).c_str()); ++failures; break; }
        }
        // the public entry (default step and threads) and the old bounded-rows strips (32 rows) for the record
        std::vector<float> viaEntry = input, bounded = input;
        std::string how;
        mosaicChromaMedian(viaEntry, w, h, block, o, false, &how, 0);
        if (how != "cpu" || std::memcmp(viaEntry.data(), reference.data(), viaEntry.size() * sizeof(float)) != 0) {
            std::printf("FAIL %s: mosaicChromaMedian (%s) differs from the planes\n", where(w, h, block, content, oscMode).c_str(), how.c_str());
            ++failures;
        }
        if (oscMode == 1 && (block == 2 || block == 4)) { // a subset: the strips start threads of their own for every pass
            mosaicChromaMedianRef(bounded, w, h, block, o, false, nullptr, 32);
            float& worst = boundedWorst[content <= 2 ? 0 : 1];
            for (size_t i = 0; i < bounded.size(); ++i)
                if (std::isfinite(bounded[i]) && std::isfinite(reference[i])) worst = std::max(worst, std::abs(bounded[i] - reference[i]));
        }
    }
    // a wide frame with all the default threads (the 8192 px rows of the 50 MP merge: 64 column bands of every ring)
    {
        const int w = 8192, h = 150;
        for (int block : {2, 4}) {
            const std::vector<float> input = frame(w, h, 1, rng);
            std::vector<float> reference = input, rgb = input;
            mosaicChromaMedianRef(reference, w, h, block, nullptr, false, nullptr, -1);
            MosaicChromaPasses passes;
            mosaicChromaMedian(rgb, w, h, block, nullptr, false, nullptr, 0, &passes);
            ++cases; ++streams;
            if (std::memcmp(rgb.data(), reference.data(), rgb.size() * sizeof(float)) != 0) { std::printf("FAIL 8192x150 block %d differs\n", block); ++failures; }
            std::printf("8192x%d block %d: %s\n", h, block, passes.text().c_str());
        }
    }
#ifdef SCAM_CHROMA_RING_CHECK
    const char* ring = ", every ring row read checked";
#else
    const char* ring = "";
#endif
    if (failures) { std::printf("FAIL: %d failures in %ld cases (%ld streams)\n", failures, cases, streams); return 1; }
    std::printf("PASS: streamed chroma median == the full-frame planes byte for byte, %ld cases (%ld changed by the filter), %ld streams "
                "(steps 1..64 rows, 1..8 threads, fed in portions)%s; the old bounded-rows strips were up to %.3g off (noise, gratings, "
                "edges; %.3g on NaN, black, clipped, wide range, dots)\n", cases, distinct, streams, ring, boundedWorst[0], boundedWorst[1]);
    return 0;
}
