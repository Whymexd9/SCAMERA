// P268: the site response field of the mosaic merges (hybridMosaicGainsRobust with a HybridGainField). A 2x2-OCL Quad sensor's
// site classes change their response over the field (vivo X200 Ultra wide, IMX06C: -16..+16 % from corner to corner), so one
// gain set per class left a 4 px hatch; the field measures the classes per zone, fits each with a robust cubic and applies it
// where it differs from the global set beyond the zones' noise. Synthetic Quad bursts (1024 x 768, 12 frames, shot noise):
//   A  a smooth field (+-12 % across the frame, a different tilt per class): the field is applied, it recovers the true gains
//      within 1 % at the centre and the corners, and HybridGainField::at() is continuous (no step at a zone border);
//   B  one uniform gain set (5 %): no field (the global set alone, as before P268);
//   C  uniform gains under a scene with fine stripes in two patches (content the per-zone ratios could take for a pattern):
//      no field;
//   D  hybridGainFieldFromZones: zones whose ratios a patch of the scene biases drop out of the robust fit.
// scam-hybrid.h is not self-contained: the worker includes scam-capture.h first.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>
using namespace scam;

static const int W = 1024, H = 768, N = 12;

// the true gain the sensor applied to site class k at (x, y) (the merge's gain undoes it: 1 / response)
static double response(int k, int x, int y, double amp, bool smooth) {
    const double u = (x + 0.5) / W * 2 - 1, v = (y + 0.5) / H * 2 - 1;
    const int sx = k & 3, sy = (k >> 3) & 3;           // position of the site in its 4 x 4 colour period
    const double base = 1.0 + 0.05 * ((sx & 1) ? 1 : -1) * ((sy & 1) ? -1 : 1);
    if (!smooth) return base;
    const double tilt = ((sx & 1) ? 1 : -1) * u + ((sy & 1) ? 1 : -1) * v;
    return base * (1.0 + amp * 0.5 * tilt + 0.3 * amp * u * v);
}

struct FieldBurst {
    std::vector<std::vector<uint16_t>> frames;
    HybridInput in;
};

static FieldBurst make(double amp, bool smooth, bool stripes, unsigned seed) {
    FieldBurst b;
    std::mt19937 rng(seed);
    b.in.w = W; b.in.h = H; b.in.cfa = 0; b.in.white = 1023.f; b.in.black = {64.f, 64.f, 64.f, 64.f};
    std::vector<double> scene(size_t(W) * H);
    for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
        double s = 0.25 + 0.15 * std::sin(x / 97.0) * std::cos(y / 71.0) + 0.1 * (x + y) / double(W + H);
        if (stripes && ((x > 200 && x < 440 && y > 150 && y < 390) || (x > 640 && x < 900 && y > 420 && y < 700)))
            s *= 1.0 + 0.25 * (((x >> 1) & 1) ? 1 : -1);  // a 4 px grating in the scene
        scene[size_t(y) * W + x] = s;
    }
    b.frames.resize(N);
    for (int f = 0; f < N; ++f) {
        b.frames[f].resize(size_t(W) * H);
        std::normal_distribution<double> nd(0.0, 1.0);
        for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
            const int k = ((y & 7) << 3) | (x & 7);
            // Quad colour of the site: the scene's colour varies little, a grey world (R 0.8, G 1, B 0.9 of the level)
            const int c = (((y >> 1) & 1) << 1) | ((x >> 1) & 1);
            const double col = c == 0 ? 0.8 : c == 3 ? 0.9 : 1.0;
            const double e = scene[size_t(y) * W + x] * col * response(k, x, y, amp, smooth) * 959.0;
            const double v = 64.0 + e + nd(rng) * std::sqrt(std::max(e, 1.0) * 0.6 + 4.0);
            b.frames[f][size_t(y) * W + x] = uint16_t(std::clamp(std::lround(v), 0L, 1023L));
        }
        HybridFrame fr; fr.raw = b.frames[f].data(); fr.role = kRoleNormal; fr.exposure = 1.f;
        fr.slope = float(0.6 / 959.0); fr.offset = float(4.0 / (959.0 * 959.0));
        b.in.frames.push_back(fr);
    }
    return b;
}

int main() {
    std::vector<int> frames;
    for (int f = 0; f < N; ++f) frames.push_back(f);
    // A: a smooth field
    {
        FieldBurst b = make(0.24, true, false, 1);
        float spread = 0, err = 0; long used = 0;
        HybridGainField field;
        const std::array<float, 64> g = hybridMosaicGainsRobust(b.in, frames, 2, spread, &used, &err, &field, 24);
        std::printf("A: %ld tiles, field %dx%d (%d measured), %.2f %% from the global set, noise %.2f %% -> %s\n", used, field.zx, field.zy,
                    field.measured, 100.0 * field.rmsDev, 100.0 * field.noise, field.on() ? "applied" : "off");
        assert(field.on());
        double worst = 0;
        for (int p = 0; p < 5; ++p) {
            const int x = p == 0 ? W / 2 : (p & 1) ? 48 : W - 48, y = p == 0 ? H / 2 : (p & 2) ? 48 : H - 48;
            for (int k = 0; k < 64; ++k) {
                // the field's gain times the response is flat within its colour group: the ratio to the group's mean
                double mean = 0; int cnt = 0;
                const int c = ((((k >> 3) >> 1) & 1) << 1) | (((k & 7) >> 1) & 1);
                for (int q = 0; q < 64; ++q) {
                    const int cq = ((((q >> 3) >> 1) & 1) << 1) | (((q & 7) >> 1) & 1);
                    const bool gq = cq == 1 || cq == 2, gk = c == 1 || c == 2;
                    if ((gk && gq) || cq == c) { mean += field.at(q, x, y) * response(q, x, y, 0.24, true); ++cnt; }
                }
                const double r = field.at(k, x, y) * response(k, x, y, 0.24, true) / (mean / cnt);
                worst = std::max(worst, std::abs(r - 1.0));
            }
        }
        std::printf("A: residual pattern after the field %.3f %% (the global set alone: spread %.1f %%)\n", 100.0 * worst, 100.0 * spread);
        assert(worst < 0.01);
        // continuity of at(): a step across every zone border below 0.2 %
        double step = 0;
        for (int x = 1; x < W; ++x) step = std::max(step, double(std::abs(field.at(9, x, H / 3) - field.at(9, x - 1, H / 3))));
        std::printf("A: largest 1-px step of the field %.4f %%\n", 100.0 * step);
        assert(step < 0.002);
        (void)g;
    }
    // B: one uniform gain set
    {
        FieldBurst b = make(0.0, false, false, 2);
        float spread = 0, err = 0; long used = 0;
        HybridGainField field;
        hybridMosaicGainsRobust(b.in, frames, 2, spread, &used, &err, &field, 24);
        std::printf("B: uniform set, global spread %.1f %%, field %.2f %% from it (noise %.2f %%) -> %s\n", 100.0 * spread,
                    100.0 * field.rmsDev, 100.0 * field.noise, field.on() ? "applied" : "off");
        assert(!field.on());
        assert(spread > 0.08 && spread < 0.12);
    }
    // C: uniform gains, a scene with gratings in two patches
    {
        FieldBurst b = make(0.0, false, true, 3);
        float spread = 0, err = 0; long used = 0;
        HybridGainField field;
        hybridMosaicGainsRobust(b.in, frames, 2, spread, &used, &err, &field, 24);
        std::printf("C: gratings in the scene, field %.2f %% (noise %.2f %%) -> %s\n", 100.0 * field.rmsDev, 100.0 * field.noise,
                    field.on() ? "applied" : "off");
        assert(!field.on());
    }
    // D: the robust fit drops zones biased by the scene
    {
        HybridGainField f; f.zx = 12; f.zy = 9; f.zw = 64; f.zh = 64;
        const int Z = f.zx * f.zy;
        std::vector<double> sT(size_t(Z) * 64), sP(size_t(Z) * 64, 1.0);
        std::vector<int> tiles(Z, 100);
        int grpOf[64];
        for (int k = 0; k < 64; ++k) { const int c = ((((k >> 3) >> 1) & 1) << 1) | (((k & 7) >> 1) & 1); grpOf[k] = c == 1 || c == 2 ? 4 : c; }
        std::array<float, 64> global; global.fill(1.f);
        for (int z = 0; z < Z; ++z) for (int k = 0; k < 64; ++k) {
            const bool biased = (z % f.zx) >= 3 && (z % f.zx) < 5 && (z / f.zx) >= 2 && (z / f.zx) < 4;
            sT[size_t(z) * 64 + k] = 1.0 + (biased ? 0.08 * ((k & 1) ? 1 : -1) : 0.0);
        }
        const bool built = hybridGainFieldFromZones(f, sT, sP, tiles, grpOf, global);
        std::printf("D: 4 of %d zones biased by 8 %%: fitted field %.3f %% from flat\n", Z, 100.0 * f.rmsDev);
        assert(built && f.rmsDev < 0.005);
    }
    std::printf("check_gain_field: OK\n");
    return 0;
}
