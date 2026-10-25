// P337 (owner, 2026-10-25: «коррекция ха у нас ограничена 2мя пикселями, убери ограничение»): the lateral CA of the merge
// (hybridRawCa / hybridCorrectCa) on a synthetic BGGR frame whose R and B planes are the scene displaced radially, d = k p
// (p from the centre over the half diagonal), as a lens with lateral CA draws them:
//   - R +6 / B -3 RAW px at the corner (beyond the old 2-cell cut of a tile's shift): the model finds both within 0.3 px;
//   - R +0.5 / B -0.4 px (the usual phone lens): found within 0.15 px, as before;
//   - no CA: below the worker's 0.25 px threshold;
//   - the correction of a merged RGB with R / B displaced by +6 / -3 px: R and B back on G within 0.03 of the scene's range.
// scam-hybrid.h is not self-contained: the worker includes scam-capture.h first.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <chrono>
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>
using namespace scam;

static const int w = 2048, h = 1536, cfa = 3;
static const float black = 64, white = 1023;

struct Blob { float x, y, s, a; };
static std::vector<Blob> blobs;
static const int pad = 32, sw = w + 2 * pad, sh = h + 2 * pad;
static std::vector<float> img; // the scene rendered once on the RAW grid (+ pad), sampled bilinearly

// the scene: a grey level of 100..700 DN with blobs of 3..12 px (gradients everywhere, nothing clipped)
static void render() {
    img.assign(size_t(sw) * sh, 380.f);
    for (const Blob& b : blobs) {
        const int r = int(std::ceil(5.f * b.s));
        for (int y = std::max(0, int(b.y) - r); y < std::min(sh, int(b.y) + r + 1); ++y)
            for (int x = std::max(0, int(b.x) - r); x < std::min(sw, int(b.x) + r + 1); ++x) {
                const float dx = x - b.x, dy = y - b.y;
                img[size_t(y) * sw + x] += b.a * std::exp(-0.5f * (dx * dx + dy * dy) / (b.s * b.s));
            }
    }
    for (float& v : img) v = std::min(std::max(v, 100.f), 700.f);
}

static float scene(float x, float y) {
    x = std::min(std::max(x + pad, 0.f), float(sw - 2));
    y = std::min(std::max(y + pad, 0.f), float(sh - 2));
    const int x0 = int(x), y0 = int(y);
    const float fx = x - x0, fy = y - y0;
    const float* p = img.data() + size_t(y0) * sw + x0;
    return (p[0] * (1 - fx) + p[1] * fx) * (1 - fy) + (p[sw] * (1 - fx) + p[sw + 1] * fx) * fy;
}

// the displacement (RAW px) of a colour plane at RAW position (x, y) for a corner shift c (RAW px): radial, linear in p
static void shift(float x, float y, float corner, float& sx, float& sy) {
    const float cx = 0.5f * w, cy = 0.5f * h, half = std::hypot(cx, cy);
    sx = corner * (x - cx) / half;
    sy = corner * (y - cy) / half;
}

static HybridCa measure(std::vector<uint16_t>& raw, float cr, float cb) {
    // BGGR: B at (even, even), R at (odd, odd), G elsewhere; R / B at x show the scene at x - s(x)
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const bool r = (x & 1) && (y & 1), b = !(x & 1) && !(y & 1);
            float sx = 0, sy = 0;
            if (r) shift(float(x), float(y), cr, sx, sy);
            if (b) shift(float(x), float(y), cb, sx, sy);
            const float v = (r ? 0.6f : b ? 0.5f : 1.f) * scene(x - sx, y - sy);
            raw[size_t(y) * w + x] = uint16_t(std::lround(black + v));
        }
    HybridInput in;
    in.w = w; in.h = h; in.cfa = cfa; in.white = white; in.black = {black, black, black, black};
    HybridFrame f; f.raw = raw.data();
    in.frames.push_back(f);
    const auto t0 = std::chrono::steady_clock::now();
    HybridCa ca = hybridRawCa(in);
    std::printf("  hybridRawCa %.0f ms\n", std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    return ca;
}

int main() {
    std::mt19937 rng(7);
    std::uniform_real_distribution<float> ux(0, float(sw)), uy(0, float(sh)), us(3, 12), ua(-160, 160);
    for (int i = 0; i < 9000; ++i) blobs.push_back({ux(rng), uy(rng), us(rng), ua(rng)});
    render();
    std::vector<uint16_t> raw(size_t(w) * h);
    int fails = 0;
    struct Case { float r, b, tol; };
    for (const Case c : {Case{6.f, -3.f, 0.3f}, Case{0.5f, -0.4f, 0.15f}, Case{0.f, 0.f, 0.1f}}) {
        const HybridCa ca = measure(raw, c.r, c.b);
        const bool ok = ca.ok && std::abs(ca.cornerPx[0] - c.r) < c.tol && std::abs(ca.cornerPx[1] - c.b) < c.tol;
        std::printf("CA R %+.2f B %+.2f px: measured R %+.3f (rms %.3f, %d tiles) B %+.3f (rms %.3f, %d tiles) %s\n", c.r, c.b,
                    ca.cornerPx[0], ca.rmsPx[0], ca.tiles[0], ca.cornerPx[1], ca.rmsPx[1], ca.tiles[1], ok ? "ok" : "FAIL");
        fails += !ok;
    }
    // the correction of a merged RGB (grid 1): R / B planes displaced as above, G the scene
    {
        const float cr = 6.f, cb = -3.f;
        const HybridCa ca = measure(raw, cr, cb);
        std::vector<float> rgb(size_t(w) * h * 3);
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                float sx, sy;
                float* o = rgb.data() + (size_t(y) * w + x) * 3;
                shift(x + 0.5f, y + 0.5f, cr, sx, sy); o[0] = scene(x - sx, y - sy);
                o[1] = scene(float(x), float(y));
                shift(x + 0.5f, y + 0.5f, cb, sx, sy); o[2] = scene(x - sx, y - sy);
            }
        double before = 0, after = 0; int n = 0;
        for (int y = 32; y < h - 32; y += 3) for (int x = 32; x < w - 32; x += 3) {
            const float* o = rgb.data() + (size_t(y) * w + x) * 3;
            before += std::abs(o[0] - o[1]) + std::abs(o[2] - o[1]); ++n;
        }
        hybridCorrectCa(rgb, w, h, 1, ca);
        for (int y = 32; y < h - 32; y += 3) for (int x = 32; x < w - 32; x += 3) {
            const float* o = rgb.data() + (size_t(y) * w + x) * 3;
            after += std::abs(o[0] - o[1]) + std::abs(o[2] - o[1]);
        }
        before /= 2.0 * n * 600.0; after /= 2.0 * n * 600.0;
        const bool ok = after < 0.03 && after < 0.25 * before;
        std::printf("correction R +6 / B -3 px: mean |R - G|, |B - G| %.4f -> %.4f of the range %s\n", before, after, ok ? "ok" : "FAIL");
        fails += !ok;
    }
    if (fails) { std::printf("FAIL: %d\n", fails); return 1; }
    std::printf("PASS: the lateral CA is measured and corrected beyond 2 cells\n");
    return 0;
}
