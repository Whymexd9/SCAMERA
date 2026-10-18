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

static bool same(float a, float b) { return std::memcmp(&a, &b, sizeof a) == 0; }

// MosaicBoxMean against float(acc / n): sums of floats of every scale, and sums placed within a few units of a float rounding
// midpoint (the products there must fall back to the division).
static long boxDivisions = 0, signShortcuts[2] = {0, 0}, signRamps = 0;
static long checkBoxMean(long count, std::mt19937_64& rng) {
    std::uniform_real_distribution<double> u01(0.0, 1.0);
    std::uniform_int_distribution<int> expo(-140, 120), off(-40, 40), terms(1, 49);
    long n = 0;
    for (int r : {1, 2, 3, 4, 6, 8, 9, 12, 16, 24}) {
        const MosaicBoxMean mean(r);
        const double den = double(2 * r + 1);
        std::vector<double> sums;
        std::vector<float> fast(64);
        for (long i = 0; i < count; ++i) {
            double acc = 0;
            if (i % 3 == 0) { // a sum of floats
                const int e = expo(rng);
                for (int k = terms(rng); k > 0; --k) acc += double(float((u01(rng) - 0.3) * std::ldexp(1.0, e)));
            } else {          // a float rounding midpoint times n, a few units of the double away
                const float f = float(u01(rng) * std::ldexp(1.0, expo(rng)));
                const double mid = 0.5 * (double(f) + double(std::nextafter(f, 3e38f)));
                acc = mid * den;
                for (int k = off(rng); k != 0; k += k < 0 ? 1 : -1) acc = std::nextafter(acc, k < 0 ? -1e300 : 1e300);
                if (i % 2) acc = -acc;
            }
            sums.push_back(acc);
            if (!MosaicBoxMean::sure(acc * mean.inv)) ++boxDivisions;
            if (!same(mean(acc), float(acc / den))) { std::printf("FAIL box mean r %d: %.17g\n", r, acc); ++failures; }
            if (sums.size() == fast.size()) {
                mean.row(sums.data(), fast.data(), int(sums.size()));
                for (size_t k = 0; k < sums.size(); ++k)
                    if (!same(fast[k], float(sums[k] / den))) { std::printf("FAIL box mean row r %d: %.17g\n", r, sums[k]); ++failures; }
                sums.clear();
            }
            ++n;
        }
        for (double special : {0.0, -0.0, HUGE_VAL, -HUGE_VAL, std::nan(""), std::ldexp(1.0, -149), 3.4e38 * 49.0})
            if (!same(mean(special), float(special / den))) { std::printf("FAIL box mean r %d: special %.17g\n", r, special); ++failures; }
    }
    return n;
}

// The soft sign change against the old one (no shortcut): luma-like float gates (tau 0.25) and double gates of the oscillation
// maps (tau 0.05), deviations of every scale, swings near the ramp ends, zeros, infinities, NaN.
static long checkSoftSign(long count, std::mt19937_64& rng) {
    std::uniform_real_distribution<double> u01(0.0, 1.0), ramp(0.70, 1.30);
    std::uniform_int_distribution<int> expo(-30, 30), kind(0, 9);
    const double specials[] = {0.0, -0.0, HUGE_VAL, -HUGE_VAL, std::nan(""), 1e-310, 1e305, 4e-6};
    long n = 0;
    for (long i = 0; i < count; ++i) {
        const double tau = i % 2 ? kMosaicLumaTau : kMosaicOscTau;
        double gate = std::ldexp(u01(rng), expo(rng));
        if (i % 4 < 2) gate = double(float(gate)); // the luma map: 0.04f max(L, 1e-4f)
        double p0 = (u01(rng) - 0.5) * gate * std::ldexp(1.0, expo(rng) / 6), p1 = (u01(rng) - 0.5) * gate * std::ldexp(1.0, expo(rng) / 6);
        switch (kind(rng)) {
            case 0: { const double s = (u01(rng) < 0.5 ? 0.75 : 1.25) * (0.98 + 0.04 * u01(rng)) * gate; p0 = s * 0.5; p1 = -s * 0.5; break; } // ramp ends
            case 1: p0 = tau * gate * (0.5 + u01(rng)); p1 = -p0 * ramp(rng) * 1.3; break;                                            // opp ~ tau gate
            case 2: p0 = specials[i % 8]; break;
            case 3: gate = specials[(i / 8) % 8]; break;
            case 4: p1 = specials[(i / 3) % 8]; break;
            default: break;
        }
        const float a = mosaicSoftSignChange(p0, p1, gate, tau), b = mosaicSoftSignChangeRef(p0, p1, gate, tau);
        const double opp = std::max(0.0, std::min(p0, -p1)) + std::max(0.0, std::min(-p0, p1)), swing = std::abs(p0) + std::abs(p1);
        if (gate > 1e-300 && gate < 1e300 && swing < 1e300 && opp > 0 && !std::isnan(p0) && !std::isnan(p1)) {
            if (swing < 0.74 * gate) ++signShortcuts[0];
            else if (opp >= tau * gate && swing > 1.26 * gate) ++signShortcuts[1];
            else ++signRamps;
        }
        if (!same(a, b)) { std::printf("FAIL soft sign change %.17g %.17g gate %.17g tau %g: %.9g, was %.9g\n", p0, p1, gate, tau, a, b); ++failures; }
        ++n;
    }
    return n;
}

int main(int argc, char** argv) {
    const bool quick = argc > 1 && std::string(argv[1]) == "quick";
    std::mt19937_64 rng64(2131);
    const long means = checkBoxMean(quick ? 20000 : 400000, rng64), signs = checkSoftSign(quick ? 400000 : 20000000, rng64);
    std::printf("box means %ld (%ld by the division), soft sign changes %ld (%ld shortcut to 0, %ld to 1, %ld through the ramps): %s\n",
                means, boxDivisions, signs, signShortcuts[0], signShortcuts[1], signRamps, failures ? "FAIL" : "the same floats");
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
