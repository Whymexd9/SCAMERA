#pragma once
// P28 RAW chromatic aberration correction on Bayer CFA data: a C++ port of RawTherapee's CA_correct_RT
// (rtengine/CA_correct_RT.cc, "code dated: September 8, 2018"):
//
//   Chromatic Aberration correction on raw bayer cfa data
//   copyright (c) 2008-2010 Emil Martinec <ejmartin@uchicago.edu>
//   copyright (c) for improvements (speedups, iterated correction and avoid colour shift) 2018 Ingo Weyrich
//   <heckflosse67@gmx.de>
//
// CA_correct_RT.cc is free software under the GNU General Public License, version 3 or later, the licence of SCAMERA.
// This port follows the scalar (non-SSE) code path line by line, including its buffer layout and its quirks, so that
// tools/rawca/ can build the original function next to it and compare coefficients, per-tile shifts and R / B samples:
//   - tiles of 128 px with an 8 px border (16 px overlap, stride 112); G interpolated at the R / B sites with directional
//     weights; per tile the R / B shift that minimises the colour-difference variance along each direction;
//   - block shifts filtered by a 3x3 median and rejected beyond caAutostrength / 2 standard deviations; 2D polynomial fit
//     of 16 coefficients (4 below 32 blocks; no correction below 10); shifts clamped to +-3.99 px;
//   - correction through the colour difference to G at the shifted position, gradient weights where the change is large,
//     never increasing |G - R|, "desaturate" on overshoot (old x new < 0); multi-pass auto, manual cared / cablue,
//     avoidColourshift (ratio old / new blurred with sigma 30 per R / B plane); green sites are never written.
// Quirks kept on purpose (RT behaviour, documented where they occur): LinEqSolve's pivot search compares against a signed
// element; blockwt holds the last (B, horizontal) weight of a tile; the 4-coefficient fallback solves the leading 4x4 of the
// 16-coefficient normal matrix; the estimation pass fills the bottom / right image border of a tile without the row / column
// limit of the correction pass; the manual G interpolation starts at 3 + fc(row, 1).
// Extensions (off by default, not in RT): fitsIn / fitsOut carry every pass's fit (with its polynomial order) from one frame
// to others, factorsIn / factorsOut the avoid-colour-shift factors, freshGtmp interpolates G over the whole frame before a
// correction with given fits (RT reads the G a previous call left in the buffer at the tile edges).
// Also here (CPU, not in RT): sensor codes <-> RT's working scale (toWorking / fromWorking), a grey-world white balance (the NCH
// transport has none), correctRgb (RT's correction rule on a merged RGB, the hybrid's "base" mode) and the burst level
// (estimateBase on the base frame, tileTables, correctFrameCpu); the GPU pre-pass and the hybrid glue: scam-rawca-gpu.h.
// Parity with the original function: tools/rawca/run_parity.py (bit-exact on synthetic and real RAWs, any thread count).
// Only the standard library: the same file builds into the Android worker and into the desktop parity harness.
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace scam_rawca {

// ---- helpers of rt_math.h / median.h (same semantics, also for NaN)
template<typename T> constexpr T rtSqr(T x) { return x * x; }
template<typename T> constexpr const T& rtMin(const T& a, const T& b) { return b < a ? b : a; }
template<typename T> constexpr const T& rtMax(const T& a, const T& b) { return a < b ? b : a; }
template<typename T> constexpr const T& rtLim(const T& v, const T& lo, const T& hi) { return rtMax(lo, rtMin(v, hi)); }
template<typename T> constexpr T rtIntp(T a, T b, T c) { return a * (b - c) + c; } // a * b + (1 - a) * c
inline unsigned fc(const unsigned cfa[2][2], int r, int c) { return cfa[r & 1][c & 1]; }
inline float median9(std::array<float, 9> p) { std::nth_element(p.begin(), p.begin() + 4, p.end()); return p[4]; }

// Henry Guennadi Levkin's solver as in CA_correct_RT.cc (pivot search keeps RT's signed assignment).
inline bool linEqSolve(int nDim, double* pfMatr, double* pfVect, double* pfSolution) {
    double fAcc;
    int i, j, k;
    for (k = 0; k < (nDim - 1); k++) {
        double fMaxElem = std::fabs(pfMatr[k * nDim + k]);
        int m = k;
        for (i = k + 1; i < nDim; i++) {
            if (fMaxElem < std::fabs(pfMatr[i * nDim + k])) {
                fMaxElem = pfMatr[i * nDim + k];
                m = i;
            }
        }
        if (m != k) {
            for (i = k; i < nDim; i++) {
                fAcc = pfMatr[k * nDim + i];
                pfMatr[k * nDim + i] = pfMatr[m * nDim + i];
                pfMatr[m * nDim + i] = fAcc;
            }
            fAcc = pfVect[k];
            pfVect[k] = pfVect[m];
            pfVect[m] = fAcc;
        }
        if (pfMatr[k * nDim + k] == 0.) return false;
        for (j = (k + 1); j < nDim; j++) {
            fAcc = -pfMatr[j * nDim + k] / pfMatr[k * nDim + k];
            for (i = k; i < nDim; i++) pfMatr[j * nDim + i] = pfMatr[j * nDim + i] + fAcc * pfMatr[k * nDim + i];
            pfVect[j] = pfVect[j] + fAcc * pfVect[k];
        }
    }
    for (k = (nDim - 1); k >= 0; k--) {
        pfSolution[k] = pfVect[k];
        for (i = (k + 1); i < nDim; i++) pfSolution[k] -= (pfMatr[k * nDim + i] * pfSolution[i]);
        pfSolution[k] = pfSolution[k] / pfMatr[k * nDim + k];
    }
    return true;
}

// ---- threads: n jobs taken in order by `threads` workers (0 = hardware concurrency); fn(job, worker)
inline int threadCount(int threads) {
    if (threads > 0) return threads;
    const unsigned hw = std::thread::hardware_concurrency();
    return int(std::max(1u, std::min(hw, 16u)));
}
inline void parallelFor(int n, int threads, const std::function<void(int, int)>& fn) {
    const int t = std::min(threadCount(threads), std::max(n, 1));
    if (t <= 1 || n <= 1) { for (int i = 0; i < n; ++i) fn(i, 0); return; }
    std::atomic<int> next{0};
    auto work = [&](int worker) { for (int i = next.fetch_add(1); i < n; i = next.fetch_add(1)) fn(i, worker); };
    std::vector<std::thread> pool;
    pool.reserve(size_t(t - 1));
    for (int k = 1; k < t; ++k) {
        try { pool.emplace_back(work, k); } catch (...) { break; } // fewer threads: the others take the jobs
    }
    work(0);
    for (auto& th : pool) th.join();
}

// ---- Gaussian blur of a plane (RT gauss.cc for sigma >= 25: Young - van Vliet recursive filter in double, rows then
// columns, the left / top end started at the steady state of the edge value, the right / bottom end by the Triggs - Sdika
// boundary matrix for a constant extension). RT's gauss.cc is not reproduced verbatim (not available offline): the boundary
// matrix is computed here by running the recursion on the extension, i.e. its exact value; the parity harness uses this
// same function for both builds.
struct YvV { double B = 0, b1 = 0, b2 = 0, b3 = 0, M[3][3]{}; };
inline YvV yvvFactors(double sigma) {
    YvV f;
    const double q = sigma < 2.5 ? 3.97156 - 4.14554 * std::sqrt(1.0 - 0.26891 * sigma) : 0.98711 * sigma - 0.96330;
    const double b0 = 1.57825 + 2.44413 * q + 1.4281 * q * q + 0.422205 * q * q * q;
    f.b1 = (2.44413 * q + 2.85619 * q * q + 1.26661 * q * q * q) / b0;
    f.b2 = (-1.4281 * q * q - 1.26661 * q * q * q) / b0;
    f.b3 = (0.422205 * q * q * q) / b0;
    f.B = 1.0 - (f.b1 + f.b2 + f.b3);
    // Right boundary: the forward state deviations (w[N-1], w[N-2], w[N-3]) - u decay on the constant extension u; the backward
    // pass over that tail, started at zero far away, gives (y[N-1], y[N], y[N+1]) - u = M (w - u).
    const int K = int(std::ceil(40.0 * std::max(sigma, 1.0))) + 16;
    std::vector<double> w(size_t(K) + 3), y(size_t(K) + 6);
    for (int j = 0; j < 3; ++j) {
        // w[0..2] = state (index 2 = w[N-1]); forward on zero input
        std::fill(w.begin(), w.end(), 0.0);
        w[size_t(2 - j)] = 1.0;
        for (int n = 3; n < K + 3; ++n) w[size_t(n)] = f.b1 * w[size_t(n - 1)] + f.b2 * w[size_t(n - 2)] + f.b3 * w[size_t(n - 3)];
        std::fill(y.begin(), y.end(), 0.0);
        for (int n = K + 2; n >= 2; --n)
            y[size_t(n)] = f.B * w[size_t(n)] + f.b1 * y[size_t(n + 1)] + f.b2 * y[size_t(n + 2)] + f.b3 * y[size_t(n + 3)];
        f.M[0][j] = y[2];
        f.M[1][j] = y[3];
        f.M[2][j] = y[4];
    }
    return f;
}
// One line (n >= 4) of stride `step`, in place.
inline void yvvLine(float* p, int n, size_t step, const YvV& f, std::vector<double>& t) {
    t.resize(size_t(n));
    const double B = f.B, b1 = f.b1, b2 = f.b2, b3 = f.b3;
    auto at = [&](int i) { return double(p[size_t(i) * step]); };
    t[0] = B * at(0) + b1 * at(0) + b2 * at(0) + b3 * at(0);
    t[1] = B * at(1) + b1 * t[0] + b2 * at(0) + b3 * at(0);
    t[2] = B * at(2) + b1 * t[1] + b2 * t[0] + b3 * at(0);
    for (int j = 3; j < n; ++j) t[size_t(j)] = B * at(j) + b1 * t[size_t(j - 1)] + b2 * t[size_t(j - 2)] + b3 * t[size_t(j - 3)];
    const double u = at(n - 1);
    const double d0 = t[size_t(n - 1)] - u, d1 = t[size_t(n - 2)] - u, d2 = t[size_t(n - 3)] - u;
    const double yNm1 = u + f.M[0][0] * d0 + f.M[0][1] * d1 + f.M[0][2] * d2;
    const double yN = u + f.M[1][0] * d0 + f.M[1][1] * d1 + f.M[1][2] * d2;
    const double yNp1 = u + f.M[2][0] * d0 + f.M[2][1] * d1 + f.M[2][2] * d2;
    t[size_t(n - 1)] = yNm1;
    t[size_t(n - 2)] = B * t[size_t(n - 2)] + b1 * t[size_t(n - 1)] + b2 * yN + b3 * yNp1;
    t[size_t(n - 3)] = B * t[size_t(n - 3)] + b1 * t[size_t(n - 2)] + b2 * t[size_t(n - 1)] + b3 * yN;
    for (int j = n - 4; j >= 0; --j) t[size_t(j)] = B * t[size_t(j)] + b1 * t[size_t(j + 1)] + b2 * t[size_t(j + 2)] + b3 * t[size_t(j + 3)];
    for (int j = 0; j < n; ++j) p[size_t(j) * step] = float(t[size_t(j)]);
}
// plane[h][w] in place.
inline void gaussianBlur(float* plane, int w, int h, double sigma, int threads = 0) {
    if (w < 4 || h < 4) return;
    const YvV f = yvvFactors(sigma);
    const int chunks = std::max(1, std::min(64, h / 16));
    parallelFor(chunks, threads, [&](int k, int) {
        std::vector<double> t;
        for (int i = int(int64_t(h) * k / chunks); i < int(int64_t(h) * (k + 1) / chunks); ++i) yvvLine(plane + size_t(i) * w, w, 1, f, t);
    });
    const int cchunks = std::max(1, std::min(64, w / 16));
    parallelFor(cchunks, threads, [&](int k, int) {
        std::vector<double> t;
        for (int j = int(int64_t(w) * k / cchunks); j < int(int64_t(w) * (k + 1) / cchunks); ++j) yvvLine(plane + j, h, size_t(w), f, t);
    });
}

// ---- the port
struct Fit { int polyord = 4; double p[2][2][16]{}; };                   // [R, B][vertical, horizontal][polyord * i + j]
struct Factors { int w = 0, h = 0; std::vector<float> red, blue; };      // avoidColourshift factors after the blur (one pass)
struct IterDiag {
    bool estimated = false, solved = false, corrected = false;
    int numblox[2]{}, polyord = 0;
    float blockvar[2][2]{};
    std::vector<float> blockshifts;   // vblsz * hblsz * [R, B][vertical, horizontal], after the border fill
    std::vector<float> blockwt;       // vblsz * hblsz
    std::vector<double> lblockshifts; // vblsz * hblsz * [R, B][v, h]: the shifts the correction applied (0 where no tile)
    Fit fit;
};
struct Diag { int vblsz = 0, hblsz = 0; std::vector<IterDiag> iters; };
struct Options {
    bool autoCA = true;
    size_t autoIterations = 1;
    double cared = 0, cablue = 0;
    bool avoidColourshift = true;
    int borderCrop = 0;
    // RawTherapee's interface: 64 doubles [c][d][16] in / out (in only with autoIterations < 2)
    double* fitParamsTransfer = nullptr;
    bool fitParamsIn = false, fitParamsOut = false;
    // extensions (not in RT)
    const std::vector<Fit>* fitsIn = nullptr;         // one correction pass per fit, no estimation (polyord of each fit)
    std::vector<Fit>* fitsOut = nullptr;              // the fit of every estimated pass
    const std::vector<Factors>* factorsIn = nullptr;  // avoidColourshift: these factors (one per pass) instead of old / new
    std::vector<Factors>* factorsOut = nullptr;       // the blurred factors of every pass
    bool freshGtmp = false;                           // with given fits: interpolate G at R / B over the whole frame first
    int threads = 0;
    Diag* diag = nullptr;
};

constexpr int kTs = 128, kTsh = kTs / 2, kBorder = 8, kBorder2 = 16;
inline int blocksV(int height) {
    const int vz1 = (height + kBorder2) % (kTs - kBorder2) == 0 ? 1 : 0;
    return int(std::ceil(float(height + kBorder2) / (kTs - kBorder2) + 2 + vz1));
}
inline int blocksH(int W) {
    const int width = W + (W & 1);
    const int hz1 = (width + kBorder2) % (kTs - kBorder2) == 0 ? 1 : 0;
    return int(std::ceil(float(width + kBorder2) / (kTs - kBorder2) + 2 + hz1));
}
// Size in floats of the work buffer (Gtmp, RawDataTmp, block arrays), as RT's malloc.
inline size_t bufferFloats(int W, int H) {
    const size_t width = size_t(W + (W & 1)), height = size_t(H);
    return height * width + size_t(blocksV(H)) * blocksH(W) * (2 * 2 + 1);
}
// The shifts (rows, columns) a pass applies to tile (vblock, hblock), R = [0], B = [1]: the polynomial of `fit`, clamped.
inline void fitShifts(const Fit& fit, int vblock, int hblock, double out[2][2]) {
    out[0][0] = out[0][1] = out[1][0] = out[1][1] = 0;
    double powVblock = 1.0;
    for (int i = 0; i < fit.polyord; i++) {
        double powHblock = powVblock;
        for (int j = 0; j < fit.polyord; j++) {
            out[0][0] += powHblock * fit.p[0][0][fit.polyord * i + j];
            out[0][1] += powHblock * fit.p[0][1][fit.polyord * i + j];
            out[1][0] += powHblock * fit.p[1][0][fit.polyord * i + j];
            out[1][1] += powHblock * fit.p[1][1][fit.polyord * i + j];
            powHblock *= hblock;
        }
        powVblock *= vblock;
    }
    constexpr double bslim = 3.99f;
    for (int c = 0; c < 2; ++c) for (int d = 0; d < 2; ++d) out[c][d] = rtLim(out[c][d], -bslim, bslim);
}
// Manual (radial) shifts of RT for tile (vblock, hblock).
inline void manualShifts(double cared, double cablue, int vblock, int hblock, int vblsz, int hblsz, int width, int height, double out[2][2]) {
    const double hfrac = -((hblock - 0.5) / (hblsz - 2) - 0.5);
    const double vfrac = -((vblock - 0.5) / (vblsz - 2) - 0.5) * height / width;
    out[0][0] = 2 * vfrac * cared;
    out[0][1] = 2 * hfrac * cared;
    out[1][0] = 2 * vfrac * cablue;
    out[1][1] = 2 * hfrac * cablue;
}

// rawData: W x H floats, row-major, RT's scale (black subtracted, white-balanced, 0..65535); cfa[r & 1][c & 1] in {0 R, 1 G, 2 B}.
// Returns the work buffer (freed and nullptr when freeBuffer); *ok = false when no pass corrected (too few blocks, singular fit).
inline float* caCorrectRT(const Options& o, float* rawData, int W, int H, const unsigned cfa[2][2], float* buffer, bool freeBuffer,
                          bool* ok = nullptr) {
    auto raw = [&](int row, int col) -> float& { return rawData[size_t(row) * size_t(W) + size_t(col)]; };
    constexpr int ts = kTs, tsh = kTsh;
    const int cb = 2 * ((o.borderCrop + 1) / 2);
    constexpr int v1 = ts, v2 = 2 * ts, v3 = 3 * ts, v4 = 4 * ts;
    if (ok) *ok = false;
    for (int i = 0; i < 2; i++)
        for (int j = 0; j < 2; j++)
            if (fc(cfa, i, j) == 3) return buffer; // CA correction supports only RGB colour filter arrays
    const bool avoidColourshift = o.avoidColourshift;
    // redFactor / blueFactor: array2D(width (W + 1 - 2 cb) / 2, height (H + 1 - 2 cb) / 2); oldraw: ((W + 1 - 2 cb) / 2) x (H - 2 cb)
    const int fW = (W + 1 - 2 * cb) / 2, fH = (H + 1 - 2 * cb) / 2, oW = (W + 1 - 2 * cb) / 2;
    std::vector<float> redFactor, blueFactor, oldraw;
    if (avoidColourshift) {
        redFactor.assign(size_t(fW) * fH, 0.f);
        blueFactor.assign(size_t(fW) * fH, 0.f);
        oldraw.assign(size_t(oW) * (H - 2 * cb), 0.f);
        parallelFor(H - 2 * cb, o.threads, [&](int k, int) {
            const int i = k + cb;
            for (int j = cb + (fc(cfa, i, 0) & 1); j < W - cb; j += 2) oldraw[size_t(i - cb) * oW + (j - cb) / 2] = raw(i, j);
        });
    }
    const int width = W + (W & 1), height = H;
    constexpr int border = kBorder, border2 = kBorder2;
    const int vblsz = blocksV(height), hblsz = blocksH(W);
    if (!buffer) buffer = static_cast<float*>(std::calloc(bufferFloats(W, H), sizeof(float))); // RT: malloc (contents undefined)
    if (!buffer) return nullptr;
    float* Gtmp = buffer;
    float* RawDataTmp = buffer + (size_t(height) * width) / 2;
    float* const blockwt = buffer + (size_t(height) * width);
    std::memset(blockwt, 0, size_t(vblsz) * hblsz * (2 * 2 + 1) * sizeof(float));
    float (*blockshifts)[2][2] = (float (*)[2][2])(blockwt + vblsz * hblsz);
    if (o.diag) { o.diag->vblsz = vblsz; o.diag->hblsz = hblsz; o.diag->iters.clear(); }
    if (o.fitsOut) o.fitsOut->clear();
    if (o.factorsOut) o.factorsOut->clear();

    bool processpasstwo = true;
    double fitparams[2][2][16] = {}; // RT: uninitialised (only the first numpar are solved)
    const bool fitsGiven = o.fitsIn != nullptr;
    const size_t iterations = fitsGiven ? o.fitsIn->size() : o.autoCA ? std::max<size_t>(o.autoIterations, 1) : 1;
    const bool fitParamsSet = !fitsGiven && o.fitParamsTransfer && o.fitParamsIn && iterations < 2;
    const bool fitParamsIn = o.fitParamsIn || fitsGiven;
    if (o.autoCA && fitParamsSet) {
        int index = 0;
        for (int c = 0; c < 2; ++c) for (int d = 0; d < 2; ++d) for (int e = 0; e < 16; ++e) fitparams[c][d][e] = o.fitParamsTransfer[index++];
    }
    const bool estimate = o.autoCA && !fitParamsSet && !fitsGiven;
    // per-worker tile buffers (RT: malloc per thread, 64-byte aligned start)
    constexpr int buffersize = sizeof(float) * ts * ts + 8 * sizeof(float) * ts * tsh + 8 * 64 + 63;
    constexpr int buffersizePassTwo = sizeof(float) * ts * ts + 4 * sizeof(float) * ts * tsh + 4 * 64 + 63;
    const int workers = threadCount(o.threads);
    std::vector<std::vector<char>> bufferThrs(size_t(workers), std::vector<char>(size_t(estimate ? buffersize : buffersizePassTwo)));
    auto dataOf = [](std::vector<char>& b) { return (char*)((uintptr_t(b.data()) + uintptr_t(63)) / 64 * 64); };
    std::vector<std::pair<int, int>> tiles; // (top, left), RT's collapse(2) order
    for (int top = -border; top < height; top += ts - border2)
        for (int left = -border; left < width - (W & 1); left += ts - border2) tiles.push_back({top, left});
    constexpr float eps = 1e-5f, eps2 = 1e-10f;
    int iterationsDone = 0;

    // Loads tile (top, left) of rawData into rgb[] (estimation layout), with RT's border fill. Returns rr1, cc1 etc.
    struct TileGeo { int vblock, hblock, rr1, cc1, rrmin, rrmax, ccmin, ccmax; };
    auto geometry = [&](int top, int left) {
        TileGeo g;
        g.vblock = (top + border) / (ts - border2) + 1;
        g.hblock = (left + border) / (ts - border2) + 1;
        const int bottom = rtMin(top + ts, height + border);
        const int right = rtMin(left + ts, width - (W & 1) + border);
        g.rr1 = bottom - top;
        g.cc1 = right - left;
        g.rrmin = top < 0 ? border : 0;
        g.rrmax = bottom > height ? height - top : g.rr1;
        g.ccmin = left < 0 ? border : 0;
        g.ccmax = (right > width - (W & 1)) ? width - (W & 1) - left : g.cc1;
        return g;
    };
    // Estimation-pass load (RT pass one: the border fill loops run over the whole border, see the header note).
    auto loadPassOne = [&](int top, int left, const TileGeo& g, float* rgb[3]) {
        for (int rr = g.rrmin; rr < g.rrmax; rr++) {
            const int row = rr + top;
            int cc = g.ccmin;
            int col = cc + left;
            for (; cc < g.ccmax; cc++, col++) {
                const int c = fc(cfa, rr, cc);
                const int indx1 = rr * ts + cc;
                rgb[c][indx1 >> ((c & 1) ^ 1)] = raw(row, col) / 65535.f;
            }
        }
        if (g.rrmin > 0)
            for (int rr = 0; rr < border; rr++)
                for (int cc = g.ccmin; cc < g.ccmax; cc++) {
                    const int c = fc(cfa, rr, cc);
                    rgb[c][(rr * ts + cc) >> ((c & 1) ^ 1)] = rgb[c][((border2 - rr) * ts + cc) >> ((c & 1) ^ 1)];
                }
        if (g.rrmax < g.rr1)
            for (int rr = 0; rr < border; rr++)
                for (int cc = g.ccmin; cc < g.ccmax; cc++) {
                    const int c = fc(cfa, rr, cc);
                    rgb[c][((g.rrmax + rr) * ts + cc) >> ((c & 1) ^ 1)] = raw((height - rr - 2), left + cc) / 65535.f;
                }
        if (g.ccmin > 0)
            for (int rr = g.rrmin; rr < g.rrmax; rr++)
                for (int cc = 0; cc < border; cc++) {
                    const int c = fc(cfa, rr, cc);
                    rgb[c][(rr * ts + cc) >> ((c & 1) ^ 1)] = rgb[c][(rr * ts + border2 - cc) >> ((c & 1) ^ 1)];
                }
        if (g.ccmax < g.cc1)
            for (int rr = g.rrmin; rr < g.rrmax; rr++)
                for (int cc = 0; cc < border; cc++) {
                    const int c = fc(cfa, rr, cc);
                    rgb[c][(rr * ts + g.ccmax + cc) >> ((c & 1) ^ 1)] = raw((top + rr), (width - cc - 2)) / 65535.f;
                }
        if (g.rrmin > 0 && g.ccmin > 0)
            for (int rr = 0; rr < border; rr++)
                for (int cc = 0; cc < border; cc++) {
                    const int c = fc(cfa, rr, cc);
                    rgb[c][(rr * ts + cc) >> ((c & 1) ^ 1)] = raw(border2 - rr, border2 - cc) / 65535.f;
                }
        if (g.rrmax < g.rr1 && g.ccmax < g.cc1)
            for (int rr = 0; rr < border; rr++)
                for (int cc = 0; cc < border; cc++) {
                    const int c = fc(cfa, rr, cc);
                    rgb[c][((g.rrmax + rr) * ts + g.ccmax + cc) >> ((c & 1) ^ 1)] = raw((height - rr - 2), (width - cc - 2)) / 65535.f;
                }
        if (g.rrmin > 0 && g.ccmax < g.cc1)
            for (int rr = 0; rr < border; rr++)
                for (int cc = 0; cc < border; cc++) {
                    const int c = fc(cfa, rr, cc);
                    rgb[c][(rr * ts + g.ccmax + cc) >> ((c & 1) ^ 1)] = raw((border2 - rr), (width - cc - 2)) / 65535.f;
                }
        if (g.rrmax < g.rr1 && g.ccmin > 0)
            for (int rr = 0; rr < border; rr++)
                for (int cc = 0; cc < border; cc++) {
                    const int c = fc(cfa, rr, cc);
                    rgb[c][((g.rrmax + rr) * ts + cc) >> ((c & 1) ^ 1)] = raw((height - rr - 2), (border2 - cc)) / 65535.f;
                }
    };
    // Estimation-pass G at the R / B sites with directional weights, stored in Gtmp. Overlapping tiles store the same values,
    // except next to the pass-one border overrun (W or H mod 112 in 1..7), where RT single-threaded keeps the last tile's: a
    // tile stores only the rows / columns no later tile (in RT's order) overwrites, so any thread count gives RT's Gtmp.
    auto interpolatePassOne = [&](int top, int left, const TileGeo& g, float* rgb[3]) {
        const int rowEnd = top + (ts - border2) < height ? top + (ts - border2) + 3 : height;
        const int colEnd = left + (ts - border2) < width - (W & 1) ? left + (ts - border2) + 3 : width;
        for (int rr = 3; rr < g.rr1 - 3; rr++) {
            const int row = rr + top;
            int cc = 3 + (fc(cfa, rr, 3) & 1);
            int indx = rr * ts + cc;
            const int c = fc(cfa, rr, cc);
            for (; cc < g.cc1 - 3; cc += 2, indx += 2) {
                const float wtu = 1.f / rtSqr(eps + std::fabs(rgb[1][indx + v1] - rgb[1][indx - v1]) + std::fabs(rgb[c][indx >> 1] - rgb[c][(indx - v2) >> 1]) + std::fabs(rgb[1][indx - v1] - rgb[1][indx - v3]));
                const float wtd = 1.f / rtSqr(eps + std::fabs(rgb[1][indx - v1] - rgb[1][indx + v1]) + std::fabs(rgb[c][indx >> 1] - rgb[c][(indx + v2) >> 1]) + std::fabs(rgb[1][indx + v1] - rgb[1][indx + v3]));
                const float wtl = 1.f / rtSqr(eps + std::fabs(rgb[1][indx + 1] - rgb[1][indx - 1]) + std::fabs(rgb[c][indx >> 1] - rgb[c][(indx - 2) >> 1]) + std::fabs(rgb[1][indx - 1] - rgb[1][indx - 3]));
                const float wtr = 1.f / rtSqr(eps + std::fabs(rgb[1][indx - 1] - rgb[1][indx + 1]) + std::fabs(rgb[c][indx >> 1] - rgb[c][(indx + 2) >> 1]) + std::fabs(rgb[1][indx + 1] - rgb[1][indx + 3]));
                rgb[1][indx] = (wtu * rgb[1][indx - v1] + wtd * rgb[1][indx + v1] + wtl * rgb[1][indx - 1] + wtr * rgb[1][indx + 1]) / (wtu + wtd + wtl + wtr);
            }
            if (row > -1 && row < height && row < rowEnd) {
                const int offset = (fc(cfa, row, rtMax(left + 3, 0)) & 1);
                int col = rtMax(left + 3, 0) + offset;
                int indx2 = rr * ts + 3 - (left < 0 ? (left + 3) : 0) + offset;
                const int end = rtMin(rtMin(g.cc1 + left - 3, width), colEnd);
                for (; col < end; col += 2, indx2 += 2) Gtmp[(size_t(row) * width + col) >> 1] = rgb[1][indx2];
            }
        }
    };
    // freshGtmp: Gtmp of the whole frame as the estimation pass would leave it
    auto fillGtmp = [&]() {
        parallelFor(int(tiles.size()), o.threads, [&](int t, int worker) {
            char* const data = dataOf(bufferThrs[size_t(worker)]);
            std::memset(bufferThrs[size_t(worker)].data(), 0, bufferThrs[size_t(worker)].size());
            float* rgb[3];
            rgb[0] = (float*)data;
            rgb[1] = (float*)(data + sizeof(float) * ts * tsh + 1 * 64);
            rgb[2] = (float*)(data + sizeof(float) * (ts * ts + ts * tsh) + 2 * 64);
            const int top = tiles[size_t(t)].first, left = tiles[size_t(t)].second;
            const TileGeo g = geometry(top, left);
            loadPassOne(top, left, g, rgb);
            interpolatePassOne(top, left, g, rgb);
        });
    };

    for (size_t it = 0; it < iterations && processpasstwo; ++it) {
        float blockave[2][2] = {}, blocksqave[2][2] = {}, blockdenom[2][2] = {}, blockvar[2][2] = {};
        int polyord = 4, numpar = 16;
        IterDiag* dg = nullptr;
        if (o.diag) { o.diag->iters.emplace_back(); dg = &o.diag->iters.back(); }
        if (fitsGiven) {
            const Fit& f = (*o.fitsIn)[it];
            polyord = f.polyord;
            numpar = polyord * polyord;
            std::memcpy(fitparams, f.p, sizeof(fitparams));
            if (o.freshGtmp) fillGtmp();
        } else if (fitParamsIn && o.freshGtmp && !estimate) {
            fillGtmp();
        }
        if (estimate) {
            constexpr float caAutostrength = 8.f;
            std::vector<std::array<float, 4>> tileShift(tiles.size()); // CAshift[dir][c] per tile, summed in tile order below
            parallelFor(int(tiles.size()), o.threads, [&](int t, int worker) {
                std::vector<char>& bufferThr = bufferThrs[size_t(worker)];
                char* const data = dataOf(bufferThr);
                float* rgb[3];
                rgb[0] = (float*)data;
                rgb[1] = (float*)(data + sizeof(float) * ts * tsh + 1 * 64);
                rgb[2] = (float*)(data + sizeof(float) * (ts * ts + ts * tsh) + 2 * 64);
                float* rbhpfh = (float*)(data + 2 * sizeof(float) * ts * ts + 3 * 64);
                float* rbhpfv = (float*)(data + 2 * sizeof(float) * ts * ts + sizeof(float) * ts * tsh + 4 * 64);
                float* rblpfh = (float*)(data + 3 * sizeof(float) * ts * ts + 5 * 64);
                float* rblpfv = (float*)(data + 3 * sizeof(float) * ts * ts + sizeof(float) * ts * tsh + 6 * 64);
                float* grblpfh = (float*)(data + 4 * sizeof(float) * ts * ts + 7 * 64);
                float* grblpfv = (float*)(data + 4 * sizeof(float) * ts * ts + sizeof(float) * ts * tsh + 8 * 64);
                float coeff[2][3][2];
                float CAshift[2][2];
                const int top = tiles[size_t(t)].first, left = tiles[size_t(t)].second;
                std::memset(bufferThr.data(), 0, buffersize);
                const TileGeo g = geometry(top, left);
                const int vblock = g.vblock, hblock = g.hblock, rr1 = g.rr1, cc1 = g.cc1;
                loadPassOne(top, left, g, rgb);
                interpolatePassOne(top, left, g, rgb);
                for (int rr = 4; rr < rr1 - 4; rr++) {
                    int cc = 4 + (fc(cfa, rr, 2) & 1);
                    int indx = rr * ts + cc;
                    const int c = fc(cfa, rr, cc);
                    for (; cc < cc1 - 4; cc += 2, indx += 2) {
                        rbhpfv[indx >> 1] = std::fabs(std::fabs((rgb[1][indx] - rgb[c][indx >> 1]) - (rgb[1][indx + v4] - rgb[c][(indx + v4) >> 1])) +
                                                      std::fabs((rgb[1][indx - v4] - rgb[c][(indx - v4) >> 1]) - (rgb[1][indx] - rgb[c][indx >> 1])) -
                                                      std::fabs((rgb[1][indx - v4] - rgb[c][(indx - v4) >> 1]) - (rgb[1][indx + v4] - rgb[c][(indx + v4) >> 1])));
                        rbhpfh[indx >> 1] = std::fabs(std::fabs((rgb[1][indx] - rgb[c][indx >> 1]) - (rgb[1][indx + 4] - rgb[c][(indx + 4) >> 1])) +
                                                      std::fabs((rgb[1][indx - 4] - rgb[c][(indx - 4) >> 1]) - (rgb[1][indx] - rgb[c][indx >> 1])) -
                                                      std::fabs((rgb[1][indx - 4] - rgb[c][(indx - 4) >> 1]) - (rgb[1][indx + 4] - rgb[c][(indx + 4) >> 1])));
                        const float glpfv = (2.f * rgb[1][indx] + rgb[1][indx + v2] + rgb[1][indx - v2]);
                        const float glpfh = (2.f * rgb[1][indx] + rgb[1][indx + 2] + rgb[1][indx - 2]);
                        rblpfv[indx >> 1] = 0.25f * std::fabs(glpfv - (2.f * rgb[c][indx >> 1] + rgb[c][(indx + v2) >> 1] + rgb[c][(indx - v2) >> 1]));
                        rblpfh[indx >> 1] = 0.25f * std::fabs(glpfh - (2.f * rgb[c][indx >> 1] + rgb[c][(indx + 2) >> 1] + rgb[c][(indx - 2) >> 1]));
                        grblpfv[indx >> 1] = 0.25f * (glpfv + (2.f * rgb[c][indx >> 1] + rgb[c][(indx + v2) >> 1] + rgb[c][(indx - v2) >> 1]));
                        grblpfh[indx >> 1] = 0.25f * (glpfh + (2.f * rgb[c][indx >> 1] + rgb[c][(indx + 2) >> 1] + rgb[c][(indx - 2) >> 1]));
                    }
                }
                for (int dir = 0; dir < 2; dir++)
                    for (int k = 0; k < 3; k++)
                        for (int c = 0; c < 2; c++) coeff[dir][k][c] = 0;
                // along line segments, the point that minimises the colour variance over the tile (up/down, left/right)
                for (int rr = 8; rr < rr1 - 8; rr++) {
                    int cc = 8 + (fc(cfa, rr, 2) & 1);
                    int indx = rr * ts + cc;
                    const int c = fc(cfa, rr, cc);
                    for (; cc < cc1 - 8; cc += 2, indx += 2) {
                        float gdiff = (rgb[1][indx + ts] - rgb[1][indx - ts]) + 0.3f * (rgb[1][indx + ts + 1] - rgb[1][indx - ts + 1] + rgb[1][indx + ts - 1] - rgb[1][indx - ts - 1]);
                        const float deltgrb = (rgb[c][indx >> 1] - rgb[1][indx]);
                        float gradwt = (rbhpfv[indx >> 1] + 0.5f * (rbhpfv[(indx >> 1) + 1] + rbhpfv[(indx >> 1) - 1])) * (grblpfv[(indx >> 1) - v1] + grblpfv[(indx >> 1) + v1]) / (eps + 0.1f * (grblpfv[(indx >> 1) - v1] + grblpfv[(indx >> 1) + v1]) + rblpfv[(indx >> 1) - v1] + rblpfv[(indx >> 1) + v1]);
                        coeff[0][0][c >> 1] += gradwt * deltgrb * deltgrb;
                        coeff[0][1][c >> 1] += gradwt * gdiff * deltgrb;
                        coeff[0][2][c >> 1] += gradwt * gdiff * gdiff;
                        gdiff = (rgb[1][indx + 1] - rgb[1][indx - 1]) + 0.3f * (rgb[1][indx + 1 + ts] - rgb[1][indx - 1 + ts] + rgb[1][indx + 1 - ts] - rgb[1][indx - 1 - ts]);
                        gradwt = (rbhpfh[indx >> 1] + 0.5f * (rbhpfh[(indx >> 1) + v1] + rbhpfh[(indx >> 1) - v1])) * (grblpfh[(indx >> 1) - 1] + grblpfh[(indx >> 1) + 1]) / (eps + 0.1f * (grblpfh[(indx >> 1) - 1] + grblpfh[(indx >> 1) + 1]) + rblpfh[(indx >> 1) - 1] + rblpfh[(indx >> 1) + 1]);
                        coeff[1][0][c >> 1] += gradwt * deltgrb * deltgrb;
                        coeff[1][1][c >> 1] += gradwt * gdiff * deltgrb;
                        coeff[1][2][c >> 1] += gradwt * gdiff * gdiff;
                    }
                }
                for (int dir = 0; dir < 2; dir++)
                    for (int k = 0; k < 3; k++)
                        for (int c = 0; c < 2; c++) {
                            coeff[dir][k][c] *= 0.25f;
                            if (k == 1) coeff[dir][k][c] *= 0.3125f;
                            else if (k == 2) coeff[dir][k][c] *= rtSqr(0.3125f);
                        }
                for (int c = 0; c < 2; c++) {
                    for (int dir = 0; dir < 2; dir++) {
                        // CAshift[dir][c]: the approximate optical location of the R / B pixels; blockwt keeps the last weight
                        if (coeff[dir][2][c] > eps2) {
                            CAshift[dir][c] = coeff[dir][1][c] / coeff[dir][2][c];
                            blockwt[vblock * hblsz + hblock] = coeff[dir][2][c] / (eps + coeff[dir][0][c]);
                        } else {
                            CAshift[dir][c] = 17.0;
                            blockwt[vblock * hblsz + hblock] = 0;
                        }
                        blockshifts[vblock * hblsz + hblock][c][dir] = CAshift[dir][c];
                    }
                }
                tileShift[size_t(t)] = {CAshift[0][0], CAshift[0][1], CAshift[1][0], CAshift[1][1]};
            });
            // block CA shift variance, accumulated in tile order (RT's per-thread float sums, single-threaded order)
            {
                float blockavethr[2][2] = {}, blocksqavethr[2][2] = {}, blockdenomthr[2][2] = {};
                for (const auto& s : tileShift)
                    for (int dir = 0; dir < 2; dir++)
                        for (int c = 0; c < 2; c++) {
                            const float v = s[size_t(dir * 2 + c)];
                            if (std::fabs(v) < 2.0f) {
                                blockavethr[dir][c] += v;
                                blocksqavethr[dir][c] += rtSqr(v);
                                blockdenomthr[dir][c] += 1;
                            }
                        }
                for (int dir = 0; dir < 2; dir++)
                    for (int c = 0; c < 2; c++) {
                        blockdenom[dir][c] += blockdenomthr[dir][c];
                        blocksqave[dir][c] += blocksqavethr[dir][c];
                        blockave[dir][c] += blockavethr[dir][c];
                    }
            }
            for (int dir = 0; dir < 2; dir++)
                for (int c = 0; c < 2; c++) {
                    if (blockdenom[dir][c]) {
                        blockvar[dir][c] = blocksqave[dir][c] / blockdenom[dir][c] - rtSqr(blockave[dir][c] / blockdenom[dir][c]);
                    } else {
                        processpasstwo = false;
                        break; // RT: leaves the inner loop only
                    }
                }
            if (dg) { dg->estimated = true; std::memcpy(dg->blockvar, blockvar, sizeof(blockvar)); }
            if (processpasstwo) {
                // border blocks of the blockshift array
                for (int vblock = 1; vblock < vblsz - 1; vblock++)
                    for (int c = 0; c < 2; c++)
                        for (int i = 0; i < 2; i++) {
                            blockshifts[vblock * hblsz][c][i] = blockshifts[(vblock)*hblsz + 2][c][i];
                            blockshifts[vblock * hblsz + hblsz - 1][c][i] = blockshifts[(vblock)*hblsz + hblsz - 3][c][i];
                        }
                for (int hblock = 0; hblock < hblsz; hblock++)
                    for (int c = 0; c < 2; c++)
                        for (int i = 0; i < 2; i++) {
                            blockshifts[hblock][c][i] = blockshifts[2 * hblsz + hblock][c][i];
                            blockshifts[(vblsz - 1) * hblsz + hblock][c][i] = blockshifts[(vblsz - 3) * hblsz + hblock][c][i];
                        }
                if (dg) {
                    dg->blockshifts.assign(&blockshifts[0][0][0], &blockshifts[0][0][0] + size_t(vblsz) * hblsz * 4);
                    dg->blockwt.assign(blockwt, blockwt + size_t(vblsz) * hblsz);
                }
                double polymat[2][2][256], shiftmat[2][2][16];
                for (int i = 0; i < 256; i++) polymat[0][0][i] = polymat[0][1][i] = polymat[1][0][i] = polymat[1][1][i] = 0;
                for (int i = 0; i < 16; i++) shiftmat[0][0][i] = shiftmat[0][1][i] = shiftmat[1][0][i] = shiftmat[1][1][i] = 0;
                int numblox[2] = {0, 0};
                for (int vblock = 1; vblock < vblsz - 1; vblock++) {
                    for (int hblock = 1; hblock < hblsz - 1; hblock++) {
                        for (int c = 0; c < 2; c++) {
                            float bstemp[2];
                            for (int dir = 0; dir < 2; dir++) {
                                const std::array<float, 9> p = {
                                    blockshifts[(vblock - 1) * hblsz + hblock - 1][c][dir], blockshifts[(vblock - 1) * hblsz + hblock][c][dir],
                                    blockshifts[(vblock - 1) * hblsz + hblock + 1][c][dir], blockshifts[(vblock)*hblsz + hblock - 1][c][dir],
                                    blockshifts[(vblock)*hblsz + hblock][c][dir], blockshifts[(vblock)*hblsz + hblock + 1][c][dir],
                                    blockshifts[(vblock + 1) * hblsz + hblock - 1][c][dir], blockshifts[(vblock + 1) * hblsz + hblock][c][dir],
                                    blockshifts[(vblock + 1) * hblsz + hblock + 1][c][dir]};
                                bstemp[dir] = median9(p);
                            }
                            // only data points within caAutostrength / 2 std devs of zero
                            if (rtSqr(bstemp[0]) > caAutostrength * blockvar[0][c] || rtSqr(bstemp[1]) > caAutostrength * blockvar[1][c]) continue;
                            numblox[c]++;
                            for (int dir = 0; dir < 2; dir++) {
                                double powVblockInit = 1.0;
                                for (int i = 0; i < polyord; i++) {
                                    double powHblockInit = 1.0;
                                    for (int j = 0; j < polyord; j++) {
                                        double powVblock = powVblockInit;
                                        for (int m = 0; m < polyord; m++) {
                                            double powHblock = powHblockInit;
                                            for (int n = 0; n < polyord; n++) {
                                                polymat[c][dir][numpar * (polyord * i + j) + (polyord * m + n)] += powVblock * powHblock * static_cast<double>(blockwt[vblock * hblsz + hblock]);
                                                powHblock *= hblock;
                                            }
                                            powVblock *= vblock;
                                        }
                                        shiftmat[c][dir][(polyord * i + j)] += powVblockInit * powHblockInit * static_cast<double>(bstemp[dir]) * static_cast<double>(blockwt[vblock * hblsz + hblock]);
                                        powHblockInit *= hblock;
                                    }
                                    powVblockInit *= vblock;
                                }
                            }
                        }
                    }
                }
                numblox[1] = rtMin(numblox[0], numblox[1]);
                // too few data points: linear order. RT keeps the 16-coefficient matrix and solves its leading 4x4 (see the header).
                if (numblox[1] < 32) {
                    polyord = 2;
                    numpar = 4;
                    if (numblox[1] < 10) processpasstwo = false;
                }
                if (dg) { dg->numblox[0] = numblox[0]; dg->numblox[1] = numblox[1]; dg->polyord = polyord; }
                if (processpasstwo) {
                    for (int c = 0; c < 2; c++)
                        for (int dir = 0; dir < 2; dir++)
                            if (!linEqSolve(numpar, polymat[c][dir], shiftmat[c][dir], fitparams[c][dir])) processpasstwo = false;
                }
                if (dg) dg->solved = processpasstwo;
            }
        }
        // ---- correction pass
        if (processpasstwo) {
            Fit applied;
            applied.polyord = polyord;
            std::memcpy(applied.p, fitparams, sizeof(fitparams));
            if (o.fitsOut && estimate) o.fitsOut->push_back(applied);
            if (dg) { dg->fit = applied; dg->lblockshifts.assign(size_t(vblsz) * hblsz * 4, 0.0); }
            const bool interpolateHere = !o.autoCA || fitParamsIn;
            parallelFor(int(tiles.size()), o.threads, [&](int t, int worker) {
                std::vector<char>& bufferThr = bufferThrs[size_t(worker)];
                char* const data = dataOf(bufferThr);
                float* rgb[3];
                rgb[0] = (float*)data;
                rgb[1] = (float*)(data + sizeof(float) * ts * tsh + 1 * 64);
                rgb[2] = (float*)(data + sizeof(float) * (ts * ts + ts * tsh) + 2 * 64);
                float* grbdiff = (float*)(data + 2 * sizeof(float) * ts * ts + 3 * 64);
                float* gshift = (float*)(data + 2 * sizeof(float) * ts * ts + sizeof(float) * ts * tsh + 4 * 64);
                int GRBdir[2][3];
                int shifthfloor[3], shiftvfloor[3], shifthceil[3], shiftvceil[3];
                float shifthfrac[3], shiftvfrac[3];
                const int top = tiles[size_t(t)].first, left = tiles[size_t(t)].second;
                std::memset(bufferThr.data(), 0, buffersizePassTwo);
                double lblockshifts[2][2];
                const TileGeo g = geometry(top, left);
                const int vblock = g.vblock, hblock = g.hblock, rr1 = g.rr1, cc1 = g.cc1;
                const int rrmin = g.rrmin, rrmax = g.rrmax, ccmin = g.ccmin, ccmax = g.ccmax;
                for (int rr = rrmin; rr < rrmax; rr++) {
                    const int row = rr + top;
                    int cc = ccmin;
                    int col = cc + left;
                    size_t indx = size_t(row) * width + col;
                    int indx1 = rr * ts + cc;
                    for (; cc < ccmax; cc++, col++, indx++, indx1++) {
                        const int c = fc(cfa, rr, cc);
                        rgb[c][indx1 >> ((c & 1) ^ 1)] = raw(row, col) / 65535.f;
                        if ((c & 1) == 0) rgb[1][indx1] = Gtmp[indx >> 1];
                    }
                }
                if (rrmin > 0)
                    for (int rr = 0; rr < border; rr++)
                        for (int cc = ccmin; cc < ccmax; cc++) {
                            const int c = fc(cfa, rr, cc);
                            rgb[c][(rr * ts + cc) >> ((c & 1) ^ 1)] = rgb[c][((border2 - rr) * ts + cc) >> ((c & 1) ^ 1)];
                            rgb[1][rr * ts + cc] = rgb[1][(border2 - rr) * ts + cc];
                        }
                if (rrmax < rr1)
                    for (int rr = 0; rr < std::min(border, rr1 - rrmax); rr++)
                        for (int cc = ccmin; cc < ccmax; cc++) {
                            const int c = fc(cfa, rr, cc);
                            rgb[c][((rrmax + rr) * ts + cc) >> ((c & 1) ^ 1)] = (raw((height - rr - 2), left + cc)) / 65535.f;
                            if ((c & 1) == 0) rgb[1][(rrmax + rr) * ts + cc] = Gtmp[(size_t(height - rr - 2) * width + left + cc) >> 1];
                        }
                if (ccmin > 0)
                    for (int rr = rrmin; rr < rrmax; rr++)
                        for (int cc = 0; cc < border; cc++) {
                            const int c = fc(cfa, rr, cc);
                            rgb[c][(rr * ts + cc) >> ((c & 1) ^ 1)] = rgb[c][(rr * ts + border2 - cc) >> ((c & 1) ^ 1)];
                            rgb[1][rr * ts + cc] = rgb[1][rr * ts + border2 - cc];
                        }
                if (ccmax < cc1)
                    for (int rr = rrmin; rr < rrmax; rr++)
                        for (int cc = 0; cc < std::min(border, cc1 - ccmax); cc++) {
                            const int c = fc(cfa, rr, cc);
                            rgb[c][(rr * ts + ccmax + cc) >> ((c & 1) ^ 1)] = (raw((top + rr), (width - cc - 2))) / 65535.f;
                            if ((c & 1) == 0) rgb[1][rr * ts + ccmax + cc] = Gtmp[(size_t(top + rr) * width + (width - cc - 2)) >> 1];
                        }
                if (rrmin > 0 && ccmin > 0)
                    for (int rr = 0; rr < border; rr++)
                        for (int cc = 0; cc < border; cc++) {
                            const int c = fc(cfa, rr, cc);
                            rgb[c][(rr * ts + cc) >> ((c & 1) ^ 1)] = (raw(border2 - rr, border2 - cc)) / 65535.f;
                            if ((c & 1) == 0) rgb[1][rr * ts + cc] = Gtmp[(size_t(border2 - rr) * width + border2 - cc) >> 1];
                        }
                if (rrmax < rr1 && ccmax < cc1)
                    for (int rr = 0; rr < std::min(border, rr1 - rrmax); rr++)
                        for (int cc = 0; cc < std::min(border, cc1 - ccmax); cc++) {
                            const int c = fc(cfa, rr, cc);
                            rgb[c][((rrmax + rr) * ts + ccmax + cc) >> ((c & 1) ^ 1)] = (raw((height - rr - 2), (width - cc - 2))) / 65535.f;
                            if ((c & 1) == 0) rgb[1][(rrmax + rr) * ts + ccmax + cc] = Gtmp[(size_t(height - rr - 2) * width + (width - cc - 2)) >> 1];
                        }
                if (rrmin > 0 && ccmax < cc1)
                    for (int rr = 0; rr < border; rr++)
                        for (int cc = 0; cc < std::min(border, cc1 - ccmax); cc++) {
                            const int c = fc(cfa, rr, cc);
                            rgb[c][(rr * ts + ccmax + cc) >> ((c & 1) ^ 1)] = (raw((border2 - rr), (width - cc - 2))) / 65535.f;
                            if ((c & 1) == 0) rgb[1][rr * ts + ccmax + cc] = Gtmp[(size_t(border2 - rr) * width + (width - cc - 2)) >> 1];
                        }
                if (rrmax < rr1 && ccmin > 0)
                    for (int rr = 0; rr < std::min(border, rr1 - rrmax); rr++)
                        for (int cc = 0; cc < border; cc++) {
                            const int c = fc(cfa, rr, cc);
                            rgb[c][((rrmax + rr) * ts + cc) >> ((c & 1) ^ 1)] = (raw((height - rr - 2), (border2 - cc))) / 65535.f;
                            if ((c & 1) == 0) rgb[1][(rrmax + rr) * ts + cc] = Gtmp[(size_t(height - rr - 2) * width + (border2 - cc)) >> 1];
                        }
                if (interpolateHere) {
                    // manual / given fits: G at the R / B sites (RT starts at 3 + fc(row, 1), skipping one B site on B rows)
                    for (int rr = 3; rr < rr1 - 3; rr++) {
                        int cc = 3 + fc(cfa, rr, 1), c = fc(cfa, rr, cc), indx = rr * ts + cc;
                        for (; cc < cc1 - 3; cc += 2, indx += 2) {
                            const float wtu = 1.f / rtSqr(eps + std::fabs(rgb[1][(rr + 1) * ts + cc] - rgb[1][(rr - 1) * ts + cc]) + std::fabs(rgb[c][(rr * ts + cc) >> 1] - rgb[c][((rr - 2) * ts + cc) >> 1]) + std::fabs(rgb[1][(rr - 1) * ts + cc] - rgb[1][(rr - 3) * ts + cc]));
                            const float wtd = 1.f / rtSqr(eps + std::fabs(rgb[1][(rr + 1) * ts + cc] - rgb[1][(rr - 1) * ts + cc]) + std::fabs(rgb[c][(rr * ts + cc) >> 1] - rgb[c][((rr + 2) * ts + cc) >> 1]) + std::fabs(rgb[1][(rr + 1) * ts + cc] - rgb[1][(rr + 3) * ts + cc]));
                            const float wtl = 1.f / rtSqr(eps + std::fabs(rgb[1][rr * ts + cc + 1] - rgb[1][rr * ts + cc - 1]) + std::fabs(rgb[c][(rr * ts + cc) >> 1] - rgb[c][(rr * ts + cc - 2) >> 1]) + std::fabs(rgb[1][rr * ts + cc - 1] - rgb[1][rr * ts + cc - 3]));
                            const float wtr = 1.f / rtSqr(eps + std::fabs(rgb[1][rr * ts + cc + 1] - rgb[1][rr * ts + cc - 1]) + std::fabs(rgb[c][(rr * ts + cc) >> 1] - rgb[c][(rr * ts + cc + 2) >> 1]) + std::fabs(rgb[1][rr * ts + cc + 1] - rgb[1][rr * ts + cc + 3]));
                            rgb[1][indx] = (wtu * rgb[1][indx - v1] + wtd * rgb[1][indx + v1] + wtl * rgb[1][indx - 1] + wtr * rgb[1][indx + 1]) / (wtu + wtd + wtl + wtr);
                        }
                    }
                }
                if (!o.autoCA) {
                    manualShifts(o.cared, o.cablue, vblock, hblock, vblsz, hblsz, width, height, lblockshifts);
                } else {
                    Fit f;
                    f.polyord = polyord;
                    std::memcpy(f.p, fitparams, sizeof(fitparams));
                    fitShifts(f, vblock, hblock, lblockshifts);
                }
                if (dg && vblock < vblsz && hblock < hblsz)
                    for (int c = 0; c < 2; ++c)
                        for (int d = 0; d < 2; ++d) dg->lblockshifts[(size_t(vblock) * hblsz + hblock) * 4 + size_t(c * 2 + d)] = lblockshifts[c][d];
                for (int c = 0; c < 3; c += 2) {
                    shiftvfloor[c] = int(std::floor((float)lblockshifts[c >> 1][0]));
                    shiftvceil[c] = int(std::ceil((float)lblockshifts[c >> 1][0]));
                    if (lblockshifts[c >> 1][0] < 0.0) std::swap(shiftvfloor[c], shiftvceil[c]);
                    shiftvfrac[c] = float(std::fabs(lblockshifts[c >> 1][0] - shiftvfloor[c]));
                    shifthfloor[c] = int(std::floor((float)lblockshifts[c >> 1][1]));
                    shifthceil[c] = int(std::ceil((float)lblockshifts[c >> 1][1]));
                    if (lblockshifts[c >> 1][1] < 0.0) std::swap(shifthfloor[c], shifthceil[c]);
                    shifthfrac[c] = float(std::fabs(lblockshifts[c >> 1][1] - shifthfloor[c]));
                    GRBdir[0][c] = lblockshifts[c >> 1][0] > 0 ? 2 : -2;
                    GRBdir[1][c] = lblockshifts[c >> 1][1] > 0 ? 2 : -2;
                }
                for (int rr = 4; rr < rr1 - 4; rr++) {
                    int cc = 4 + (fc(cfa, rr, 2) & 1);
                    const int c = fc(cfa, rr, cc);
                    int indx = (rr * ts + cc) >> 1;
                    int indxfc = (rr + shiftvfloor[c]) * ts + cc + shifthceil[c];
                    int indxff = (rr + shiftvfloor[c]) * ts + cc + shifthfloor[c];
                    int indxcc = (rr + shiftvceil[c]) * ts + cc + shifthceil[c];
                    int indxcf = (rr + shiftvceil[c]) * ts + cc + shifthfloor[c];
                    for (; cc < cc1 - 4; cc += 2, indxfc += 2, indxff += 2, indxcc += 2, indxcf += 2, ++indx) {
                        const float Ginthfloor = rtIntp(shifthfrac[c], rgb[1][indxfc], rgb[1][indxff]);
                        const float Ginthceil = rtIntp(shifthfrac[c], rgb[1][indxcc], rgb[1][indxcf]);
                        const float Gint = rtIntp(shiftvfrac[c], Ginthceil, Ginthfloor);
                        grbdiff[indx] = Gint - rgb[c][indx];
                        gshift[indx] = Gint;
                    }
                }
                shifthfrac[0] /= 2.f;
                shifthfrac[2] /= 2.f;
                shiftvfrac[0] /= 2.f;
                shiftvfrac[2] /= 2.f;
                for (int rr = 8; rr < rr1 - 8; rr++) {
                    int cc = 8 + (fc(cfa, rr, 2) & 1);
                    const int c0 = fc(cfa, rr, cc);
                    const int GRBdir0 = GRBdir[0][c0];
                    const int GRBdir1 = GRBdir[1][c0];
                    for (int c = fc(cfa, rr, cc), indx = rr * ts + cc; cc < cc1 - 8; cc += 2, indx += 2) {
                        const float grbdiffold = rgb[1][indx] - rgb[c][indx >> 1];
                        // colour difference from the optical R / B locations to the grid
                        const float grbdiffinthfloor = rtIntp(shifthfrac[c], grbdiff[(indx - GRBdir1) >> 1], grbdiff[indx >> 1]);
                        const float grbdiffinthceil = rtIntp(shifthfrac[c], grbdiff[((rr - GRBdir0) * ts + cc - GRBdir1) >> 1], grbdiff[((rr - GRBdir0) * ts + cc) >> 1]);
                        float grbdiffint = rtIntp(shiftvfrac[c], grbdiffinthceil, grbdiffinthfloor);
                        const float RBint = rgb[1][indx] - grbdiffint;
                        if (std::fabs(RBint - rgb[c][indx >> 1]) < 0.25f * (RBint + rgb[c][indx >> 1])) {
                            if (std::fabs(grbdiffold) > std::fabs(grbdiffint)) rgb[c][indx >> 1] = RBint;
                        } else {
                            // gradient weights: difference of G at the CA shift points from G at the grid point
                            const float p0 = 1.f / (eps + std::fabs(rgb[1][indx] - gshift[indx >> 1]));
                            const float p1 = 1.f / (eps + std::fabs(rgb[1][indx] - gshift[(indx - GRBdir1) >> 1]));
                            const float p2 = 1.f / (eps + std::fabs(rgb[1][indx] - gshift[((rr - GRBdir0) * ts + cc) >> 1]));
                            const float p3 = 1.f / (eps + std::fabs(rgb[1][indx] - gshift[((rr - GRBdir0) * ts + cc - GRBdir1) >> 1]));
                            grbdiffint = (p0 * grbdiff[indx >> 1] + p1 * grbdiff[(indx - GRBdir1) >> 1] +
                                          p2 * grbdiff[((rr - GRBdir0) * ts + cc) >> 1] + p3 * grbdiff[((rr - GRBdir0) * ts + cc - GRBdir1) >> 1]) / (p0 + p1 + p2 + p3);
                            if (std::fabs(grbdiffold) > std::fabs(grbdiffint)) rgb[c][indx >> 1] = rgb[1][indx] - grbdiffint;
                        }
                        // the colour difference interpolation overshot the correction: desaturate
                        if (grbdiffold * grbdiffint < 0) rgb[c][indx >> 1] = rgb[1][indx] - 0.5f * (grbdiffold + grbdiffint);
                    }
                }
                // corrected tile to the temporary image
                for (int rr = border; rr < rr1 - border; rr++) {
                    const int c = fc(cfa, rr + top, left + border + (fc(cfa, rr + top, 2) & 1));
                    const int row = rr + top;
                    const int cc = border + (fc(cfa, rr, 2) & 1);
                    size_t indx = (size_t(row) * width + cc + left) >> 1;
                    int indx1 = (rr * ts + cc) >> 1;
                    for (; indx < (size_t(row) * width + cc1 - border + left) >> 1; indx++, indx1++) RawDataTmp[indx] = 65535.f * rgb[c][indx1];
                }
            });
            // temporary image back to rawData (R / B sites only)
            parallelFor(height - 2 * cb, o.threads, [&](int k, int) {
                const int row = k + cb;
                int col = cb + (fc(cfa, row, 0) & 1);
                size_t indx = (size_t(row) * width + col) >> 1;
                for (; col < width - cb; col += 2, indx++) raw(row, col) = std::max(0.f, RawDataTmp[indx]);
            });
            if (dg) dg->corrected = true;
            ++iterationsDone;
        }
        if (avoidColourshift) {
            // per-pixel ratio old / new of the red and blue planes, blurred, applied to the corrected data
            const Factors* given = o.factorsIn && it < o.factorsIn->size() ? &(*o.factorsIn)[it] : nullptr;
            if (given && given->w == fW && given->h == fH) {
                redFactor = given->red;
                blueFactor = given->blue;
            } else {
                parallelFor(H - 2 * cb, o.threads, [&](int i, int) {
                    const int firstCol = fc(cfa, i, 0) & 1;
                    const int colour = fc(cfa, i, firstCol);
                    std::vector<float>& nonGreen = colour == 0 ? redFactor : blueFactor;
                    for (int j = firstCol; j < W - 2 * cb; j += 2) {
                        const float nv = raw(i + cb, j + cb), ov = oldraw[size_t(i) * oW + j / 2];
                        nonGreen[size_t(i / 2) * fW + j / 2] = (nv <= 1.f || ov <= 1.f) ? 1.f : rtLim(ov / nv, 0.5f, 2.f);
                    }
                });
                if (H % 2) {
                    for (int j = 0; j < (W + 1 - 2 * cb) / 2; ++j) {
                        redFactor[size_t((H - 2 * cb + 1) / 2 - 1) * fW + j] = redFactor[size_t((H - 2 * cb + 1) / 2 - 2) * fW + j];
                        blueFactor[size_t((H - 2 * cb + 1) / 2 - 1) * fW + j] = blueFactor[size_t((H - 2 * cb + 1) / 2 - 2) * fW + j];
                    }
                }
                if (W % 2) {
                    const int ngRow = 1 - (fc(cfa, 0, 0) & 1);
                    const int ngCol = fc(cfa, ngRow, 0) & 1;
                    const int colour = fc(cfa, ngRow, ngCol);
                    std::vector<float>& nonGreen = colour == 0 ? redFactor : blueFactor;
                    for (int i = 0; i < (H + 1 - 2 * cb) / 2; ++i)
                        nonGreen[size_t(i) * fW + (W - 2 * cb + 1) / 2 - 1] = nonGreen[size_t(i) * fW + (W - 2 * cb + 1) / 2 - 2];
                }
                gaussianBlur(redFactor.data(), fW, fH, 30.0, o.threads);
                gaussianBlur(blueFactor.data(), fW, fH, 30.0, o.threads);
            }
            if (o.factorsOut) { Factors f; f.w = fW; f.h = fH; f.red = redFactor; f.blue = blueFactor; o.factorsOut->push_back(std::move(f)); }
            parallelFor(H - 2 * cb, o.threads, [&](int i, int) {
                const int firstCol = fc(cfa, i, 0) & 1;
                const int colour = fc(cfa, i, firstCol);
                const std::vector<float>& nonGreen = colour == 0 ? redFactor : blueFactor;
                for (int j = firstCol; j < W - 2 * cb; j += 2) raw(i + cb, j + cb) *= nonGreen[size_t(i / 2) * fW + j / 2];
            });
        }
    }
    if (o.autoCA && o.fitParamsTransfer && o.fitParamsOut) {
        int index = 0;
        for (int c = 0; c < 2; ++c) for (int d = 0; d < 2; ++d) for (int e = 0; e < 16; ++e) o.fitParamsTransfer[index++] = fitparams[c][d][e];
    }
    if (ok) *ok = iterationsDone > 0;
    if (freeBuffer) { std::free(buffer); buffer = nullptr; }
    return buffer;
}

// ---------------------------------------------------------------------------------------------------------------------
// Sensor codes <-> RT's working scale. Working value of a site = max(code - black, 0) x (wb[colour] / max wb) x 65535 /
// (white - black): the owner's PC RAW / SABRE preparation (RT's scale_mul). Back to codes only where the value changed: a
// site whose working value is bit-identical keeps its original code (below-black noise included), so with nothing corrected
// the frame is unchanged; green sites are never touched.
struct Bayer {
    int w = 0, h = 0, cfa = 0;        // cfa: sensor phase ((y & 1) << 1 | (x & 1)) of red, as HybridInput
    std::array<float, 4> black{};     // per phase
    float white = 0;
    std::array<float, 3> wb{1, 1, 1}; // R, G, B multipliers (neutral -> equal); only their ratios to the largest matter
};
inline void cfaTable(int redPhase, unsigned cfa[2][2]) {
    for (int y = 0; y < 2; ++y)
        for (int x = 0; x < 2; ++x) {
            const int p = (y << 1) | x;
            cfa[y][x] = p == redPhase ? 0u : p == (redPhase ^ 3) ? 2u : 1u;
        }
}
inline std::array<float, 4> workingScale(const Bayer& b) {
    unsigned cfa[2][2];
    cfaTable(b.cfa, cfa);
    const float m = std::max(b.wb[0], std::max(b.wb[1], b.wb[2]));
    std::array<float, 4> s{};
    for (int p = 0; p < 4; ++p) {
        const int c = int(cfa[p >> 1][p & 1]);
        s[size_t(p)] = (b.wb[size_t(c)] / m) * 65535.f / std::max(b.white - b.black[size_t(p)], 1.f);
    }
    return s;
}
inline void toWorking(const Bayer& b, const uint16_t* raw, float* out, int threads = 0) {
    const auto s = workingScale(b);
    parallelFor(b.h, threads, [&](int y, int) {
        const uint16_t* r = raw + size_t(y) * b.w;
        float* o = out + size_t(y) * b.w;
        for (int x = 0; x < b.w; ++x) {
            const int p = ((y & 1) << 1) | (x & 1);
            o[x] = std::max(float(r[x]) - b.black[size_t(p)], 0.f) * s[size_t(p)];
        }
    });
}
// R / B sites of `work` back to codes in `out` (may be `original` itself). A site whose original level (code - black) /
// (white - black) is at or above clipLevel keeps its code (a clipped sample must stay clipped for the merge's clip test).
// Returns the sites changed.
inline long fromWorking(const Bayer& b, const uint16_t* original, const float* work, uint16_t* out, int threads = 0, float clipLevel = 2.f) {
    const auto s = workingScale(b);
    unsigned cfa[2][2];
    cfaTable(b.cfa, cfa);
    std::array<float, 4> clipCodes{};
    for (int p = 0; p < 4; ++p) clipCodes[size_t(p)] = b.black[size_t(p)] + clipLevel * (b.white - b.black[size_t(p)]);
    std::vector<long> changed(size_t(std::max(b.h, 1)), 0);
    parallelFor(b.h, threads, [&](int y, int) {
        const uint16_t* r = original + size_t(y) * b.w;
        const float* wv = work + size_t(y) * b.w;
        uint16_t* o = out + size_t(y) * b.w;
        long n = 0;
        for (int x = 0; x < b.w; ++x) {
            const int p = ((y & 1) << 1) | (x & 1);
            if (fc(cfa, y, x) == 1 || float(r[x]) >= clipCodes[size_t(p)]) { o[x] = r[x]; continue; }
            const float before = std::max(float(r[x]) - b.black[size_t(p)], 0.f) * s[size_t(p)];
            if (wv[x] == before) { o[x] = r[x]; continue; }
            const float code = b.black[size_t(p)] + wv[x] / s[size_t(p)];
            o[x] = uint16_t(std::clamp(std::lround(code), 0L, 65535L));
            ++n;
        }
        changed[size_t(y)] = n;
    });
    long total = 0;
    for (long n : changed) total += n;
    return total;
}
// Grey-world white balance of a frame (the NCH transport has no white balance): mean G / mean C over unclipped 2x2 cells
// above 2 % of the range, clamped to 1/8..8. RT works on daylight-balanced data; the exact values only steer the algorithm's
// guards (the output goes back through the same scale).
inline std::array<float, 3> greyWorldWb(const Bayer& b, const uint16_t* raw) {
    unsigned cfa[2][2];
    cfaTable(b.cfa, cfa);
    double sum[3] = {0, 0, 0};
    const float range = b.white - 0.25f * (b.black[0] + b.black[1] + b.black[2] + b.black[3]);
    for (int y = 0; y + 1 < b.h; y += 8)
        for (int x = 0; x + 1 < b.w; x += 8) {
            const int y0 = y & ~1, x0 = x & ~1;
            float v[4];
            bool good = true;
            for (int k = 0; k < 4; ++k) {
                const int yy = y0 + (k >> 1), xx = x0 + (k & 1);
                const uint16_t code = raw[size_t(yy) * b.w + xx];
                if (code >= 0.98f * b.white) { good = false; break; }
                v[k] = float(code) - b.black[size_t(((yy & 1) << 1) | (xx & 1))];
            }
            if (!good) continue;
            const float m = 0.25f * (v[0] + v[1] + v[2] + v[3]);
            if (m < 0.02f * range) continue;
            for (int k = 0; k < 4; ++k) {
                const int yy = y0 + (k >> 1), xx = x0 + (k & 1);
                sum[fc(cfa, yy, xx)] += v[k];
            }
        }
    std::array<float, 3> wb{1, 1, 1};
    if (sum[0] > 0 && sum[1] > 0 && sum[2] > 0) {
        const double g = sum[1] * 0.5; // two green sites per cell
        wb[0] = float(std::clamp(g / sum[0], 0.125, 8.0));
        wb[2] = float(std::clamp(g / sum[2], 0.125, 8.0));
    }
    return wb;
}

// ---------------------------------------------------------------------------------------------------------------------
// The CA field of a set of passes on a merged RGB ("base" mode): the passes' shifts summed (RT corrects pass after pass;
// for the small residual of later passes the sum is their composition), evaluated as a smooth function of the block
// coordinates (RT holds it constant per 112 px tile: on an RGB a step at every tile edge would show), clamped like RT.
struct RgbField {
    std::vector<Fit> fits;        // auto: one per pass
    bool manual = false;          // manual: RT's radial red / blue
    double cared = 0, cablue = 0;
    int W = 0, H = 0;             // frame the field belongs to (sensor px of the estimated frame)
    void at(double row, double col, double out[2][2]) const {
        // tile k covers rows [112 (k-1), 112 k): its centre row 112 (k-1) + 55.5 is block coordinate k
        const double vb = (row + 56.5) / 112.0, hb = (col + 56.5) / 112.0;
        out[0][0] = out[0][1] = out[1][0] = out[1][1] = 0;
        if (manual) {
            const int vblsz = blocksV(H), hblsz = blocksH(W), width = W + (W & 1);
            const double hfrac = -((hb - 0.5) / (hblsz - 2) - 0.5), vfrac = -((vb - 0.5) / (vblsz - 2) - 0.5) * H / width;
            out[0][0] = 2 * vfrac * cared; out[0][1] = 2 * hfrac * cared; out[1][0] = 2 * vfrac * cablue; out[1][1] = 2 * hfrac * cablue;
            return;
        }
        for (const Fit& f : fits) {
            double s[2][2] = {{0, 0}, {0, 0}};
            double powV = 1.0;
            for (int i = 0; i < f.polyord; i++) {
                double powH = powV;
                for (int j = 0; j < f.polyord; j++) {
                    for (int c = 0; c < 2; ++c) for (int d = 0; d < 2; ++d) s[c][d] += powH * f.p[c][d][f.polyord * i + j];
                    powH *= hb;
                }
                powV *= vb;
            }
            for (int c = 0; c < 2; ++c) for (int d = 0; d < 2; ++d) out[c][d] += rtLim(s[c][d], -3.99, 3.99);
        }
        for (int c = 0; c < 2; ++c) for (int d = 0; d < 2; ++d) out[c][d] = rtLim(out[c][d], -3.99, 3.99); // one application
    }
};

// R and B of a linear RGB (w x h x 3, canonical geometry, output grid g: pixel X sits at canonical position (X + 0.5) / g - 0.5,
// canonical (0, 0) = sensor (ox, oy) of the estimated frame) moved onto G by RT's colour-difference rule on the dense grid:
// D(Y) = G(Y + s) - C(Y), C' = G - D(X - s) (bilinear; where that changes C by more than 25 %, the four neighbours weighted by
// 1 / |G(X) - G(Y_k + s)|), applied only where it lowers |G - C|, and G - (old + new) / 2 on a sign change (overshoot).
// (RT interpolates the colour difference over the fractional part of the shift only, on its 2 px R / B lattice; on the dense
// grid the whole shift is used.) wbR / wbB balance R and B against G for the guards (RT works on white-balanced data).
// The field is evaluated every 8 output px and interpolated. avoidColourshift: per 8 x 8 block the ratio sum(before) /
// sum(after) of R and of B (0.5..2), blurred with RT's sigma (30 R / B sites = 60 sensor px), multiplies the corrected values.
// Bands of `band` rows; R / B of the rows above a band were already written, so their D comes from the previous band's buffer.
inline long correctRgb(std::vector<float>& rgb, int w, int h, int grid, int ox, int oy, const RgbField& field, float wbR, float wbB,
                       int threads = 0, bool avoidColourshift = false) {
    if (w < 4 || h < 4) return 0;
    const float g = float(grid);
    const int margin = int(std::ceil(4.f * g)) + 3, band = 256;
    constexpr float eps = 1e-5f;
    const float wbC[2] = {wbR, wbB};
    // the field on a grid of 8 output px: [gy][gx][R dy, R dx, B dy, B dx] in output px
    constexpr int step = 8;
    const int gw = w / step + 2, gh = h / step + 2;
    std::vector<float> fgrid(size_t(gw) * gh * 4);
    parallelFor(gh, threads, [&](int j, int) {
        for (int i = 0; i < gw; ++i) {
            double f[2][2];
            field.at((j * step + 0.5) / g - 0.5 + oy, (i * step + 0.5) / g - 0.5 + ox, f);
            float* q = fgrid.data() + (size_t(j) * gw + i) * 4;
            q[0] = float(f[0][0]) * g; q[1] = float(f[0][1]) * g; q[2] = float(f[1][0]) * g; q[3] = float(f[1][1]) * g;
        }
    });
    auto shiftOf = [&](int X, int Y, float s[2][2]) {
        const int i = std::min(X / step, gw - 2), j = std::min(Y / step, gh - 2);
        const float fx = float(X - i * step) / step, fy = float(Y - j * step) / step;
        const float* q00 = fgrid.data() + (size_t(j) * gw + i) * 4;
        const float* q01 = q00 + 4;
        const float* q10 = q00 + size_t(gw) * 4;
        const float* q11 = q10 + 4;
        for (int k = 0; k < 4; ++k) s[k >> 1][k & 1] = (q00[k] * (1 - fx) + q01[k] * fx) * (1 - fy) + (q10[k] * (1 - fx) + q11[k] * fx) * fy;
    };
    auto G = [&](int x, int y) { x = std::clamp(x, 0, w - 1); y = std::clamp(y, 0, h - 1); return rgb[(size_t(y) * w + x) * 3 + 1]; };
    auto Gbil = [&](float x, float y) {
        x = std::clamp(x, 0.f, float(w - 1)); y = std::clamp(y, 0.f, float(h - 1));
        const int x0 = std::min(int(x), w - 2), y0 = std::min(int(y), h - 2);
        const float fx = x - x0, fy = y - y0;
        return (G(x0, y0) * (1 - fx) + G(x0 + 1, y0) * fx) * (1 - fy) + (G(x0, y0 + 1) * (1 - fx) + G(x0 + 1, y0 + 1) * fx) * fy;
    };
    // avoidColourshift: per 8 x 8 block sums of R / B before and after
    constexpr int ab = 8;
    const int bw = (w + ab - 1) / ab, bh = (h + ab - 1) / ab;
    std::vector<double> sums;
    if (avoidColourshift) sums.assign(size_t(bw) * bh * 4, 0.0); // [R before, R after, B before, B after]
    // D and G(Y + s) of rows [b0, b1) for both colours: [row][x][colour][D, Gs]
    std::vector<float> dg, prev;
    int prevFrom = 0, prevTo = 0;
    std::vector<long> counts(size_t(std::max(1, h)), 0);
    for (int y0 = 0; y0 < h; y0 += band) {
        const int y1 = std::min(h, y0 + band), b0 = std::max(0, y0 - margin), b1 = std::min(h, y1 + margin);
        dg.assign(size_t(b1 - b0) * w * 4, 0.f);
        parallelFor(b1 - b0, threads, [&](int k, int) {
            const int Y = b0 + k;
            float* d = dg.data() + size_t(k) * w * 4;
            if (Y >= prevFrom && Y < prevTo) { // computed before this band wrote anything above
                const float* p = prev.data() + size_t(Y - prevFrom) * w * 4;
                std::copy(p, p + size_t(w) * 4, d);
                return;
            }
            const float* row = rgb.data() + size_t(Y) * w * 3;
            for (int X = 0; X < w; ++X) {
                float s[2][2];
                shiftOf(X, Y, s);
                for (int c = 0; c < 2; ++c) {
                    const float gs = Gbil(X + s[c][1], Y + s[c][0]);
                    d[size_t(X) * 4 + size_t(c) * 2] = gs - row[size_t(X) * 3 + size_t(c) * 2] * wbC[c];
                    d[size_t(X) * 4 + size_t(c) * 2 + 1] = gs;
                }
            }
        });
        parallelFor(y1 - y0, threads, [&](int k, int) {
            const int Y = y0 + k;
            float* row = rgb.data() + size_t(Y) * w * 3;
            long n = 0;
            for (int X = 0; X < w; ++X) {
                float s[2][2];
                shiftOf(X, Y, s);
                const float Gx = row[size_t(X) * 3 + 1];
                for (int c = 0; c < 2; ++c) {
                    const float C = row[size_t(X) * 3 + size_t(c) * 2] * wbC[c];
                    const float tx = std::clamp(X - s[c][1], 0.f, float(w - 1)), ty = std::clamp(Y - s[c][0], float(b0), float(b1 - 1));
                    const int x0 = std::min(int(tx), w - 2), yq = std::min(int(ty), b1 - 2);
                    const float fx = tx - x0, fy = ty - yq;
                    auto D = [&](int xx, int yy, int v) { return dg[((size_t(yy - b0) * w + xx) * 4) + size_t(c) * 2 + size_t(v)]; };
                    const float grbdiffold = Gx - C;
                    float grbdiffint = (D(x0, yq, 0) * (1 - fx) + D(x0 + 1, yq, 0) * fx) * (1 - fy) + (D(x0, yq + 1, 0) * (1 - fx) + D(x0 + 1, yq + 1, 0) * fx) * fy;
                    float out = C;
                    const float RBint = Gx - grbdiffint;
                    if (std::fabs(RBint - C) < 0.25f * (RBint + C)) {
                        if (std::fabs(grbdiffold) > std::fabs(grbdiffint)) out = RBint;
                    } else {
                        const float p0 = 1.f / (eps + std::fabs(Gx - D(x0, yq, 1))), p1 = 1.f / (eps + std::fabs(Gx - D(x0 + 1, yq, 1)));
                        const float p2 = 1.f / (eps + std::fabs(Gx - D(x0, yq + 1, 1))), p3 = 1.f / (eps + std::fabs(Gx - D(x0 + 1, yq + 1, 1)));
                        grbdiffint = (p0 * D(x0, yq, 0) + p1 * D(x0 + 1, yq, 0) + p2 * D(x0, yq + 1, 0) + p3 * D(x0 + 1, yq + 1, 0)) / (p0 + p1 + p2 + p3);
                        if (std::fabs(grbdiffold) > std::fabs(grbdiffint)) out = Gx - grbdiffint;
                    }
                    if (grbdiffold * grbdiffint < 0) out = Gx - 0.5f * (grbdiffold + grbdiffint);
                    if (out != C) { row[size_t(X) * 3 + size_t(c) * 2] = out / wbC[c]; ++n; }
                }
            }
            counts[size_t(Y)] = n;
        });
        if (avoidColourshift) { // block sums of this band: before = Gs - D (dg), after = the written image
            const int r0 = y0 / ab, r1 = (y1 + ab - 1) / ab;
            parallelFor(r1 - r0, threads, [&](int k, int) { // one block row per job (band = 256 rows: whole block rows)
                const int jb = r0 + k;
                double* srow = sums.data() + size_t(jb) * bw * 4;
                for (int Y = std::max(y0, jb * ab); Y < std::min(y1, (jb + 1) * ab); ++Y) {
                    const float* d = dg.data() + size_t(Y - b0) * w * 4;
                    const float* row = rgb.data() + size_t(Y) * w * 3;
                    for (int X = 0; X < w; ++X)
                        for (int c = 0; c < 2; ++c) {
                            const float before = (d[size_t(X) * 4 + size_t(c) * 2 + 1] - d[size_t(X) * 4 + size_t(c) * 2]) / wbC[c];
                            srow[size_t(X / ab) * 4 + size_t(c) * 2] += before;
                            srow[size_t(X / ab) * 4 + size_t(c) * 2 + 1] += row[size_t(X) * 3 + size_t(c) * 2];
                        }
                }
            });
        }
        // rows the next band reads above it, computed from the values before this band wrote them
        prevFrom = std::max(b0, y1 - margin);
        prevTo = b1;
        prev.assign(dg.begin() + std::ptrdiff_t(size_t(prevFrom - b0) * w * 4), dg.end());
    }
    if (avoidColourshift) {
        std::vector<float> fr(size_t(bw) * bh), fb(fr.size());
        for (size_t k = 0; k < fr.size(); ++k) {
            const double* q = sums.data() + k * 4;
            fr[k] = (q[0] <= 0 || q[1] <= 0) ? 1.f : float(std::clamp(q[0] / q[1], 0.5, 2.0));
            fb[k] = (q[2] <= 0 || q[3] <= 0) ? 1.f : float(std::clamp(q[2] / q[3], 0.5, 2.0));
        }
        const double sigma = 60.0 * g / ab; // RT: 30 R / B sites
        gaussianBlur(fr.data(), bw, bh, sigma, threads);
        gaussianBlur(fb.data(), bw, bh, sigma, threads);
        parallelFor(h, threads, [&](int Y, int) {
            float* row = rgb.data() + size_t(Y) * w * 3;
            const float by = std::clamp((Y + 0.5f) / ab - 0.5f, 0.f, float(bh - 1));
            const int j0 = std::min(int(by), std::max(bh - 2, 0)), j1 = std::min(j0 + 1, bh - 1);
            const float fy = by - j0;
            for (int X = 0; X < w; ++X) {
                const float bx = std::clamp((X + 0.5f) / ab - 0.5f, 0.f, float(bw - 1));
                const int i0 = std::min(int(bx), std::max(bw - 2, 0)), i1 = std::min(i0 + 1, bw - 1);
                const float fx = bx - i0;
                auto bil = [&](const std::vector<float>& p) {
                    return (p[size_t(j0) * bw + i0] * (1 - fx) + p[size_t(j0) * bw + i1] * fx) * (1 - fy) + (p[size_t(j1) * bw + i0] * (1 - fx) + p[size_t(j1) * bw + i1] * fx) * fy;
                };
                row[size_t(X) * 3] *= bil(fr);
                row[size_t(X) * 3 + 2] *= bil(fb);
            }
        });
    }
    long total = 0;
    for (long n : counts) total += n;
    return total;
}

// ---------------------------------------------------------------------------------------------------------------------
// Burst level (CPU part; the GPU pre-pass and the hybrid glue are in scam-rawca-gpu.h)
struct BurstSettings {
    int mode = 0;            // 1 base, 2 frames
    bool autoCA = true;      // false: manual red / blue
    double red = 0, blue = 0;
    int passes = 2;          // auto passes (RT 1..5)
    bool avoid = true;       // avoidColourshift
    int threads = 0;
    bool gpu = true;         // frames: GLES compute (CPU port otherwise / as fallback)
    float clipLevel = 0.98f; // R / B sites at or above this share of the range keep their code
};
struct BaseEstimate {
    bool ok = false;
    std::string why;           // when not ok
    Bayer bayer;               // with the white balance used
    bool manual = false;
    double red = 0, blue = 0;
    std::vector<Fit> fits;     // auto: one per pass
    std::vector<Factors> factors;
    int vblsz = 0, hblsz = 0, passes = 0;
    double ms = 0;
    std::string summary;
};
// Largest shift (px, hypot of rows / columns) at the four corner tiles, R and B, of one pass (or the sum of the passes).
inline void cornerShifts(const BaseEstimate& e, int pass, double out[2]) {
    out[0] = out[1] = 0;
    const int vb[2] = {1, e.vblsz - 2}, hb[2] = {1, e.hblsz - 2};
    for (int a = 0; a < 2; ++a)
        for (int b = 0; b < 2; ++b) {
            double s[2][2] = {{0, 0}, {0, 0}};
            if (e.manual) {
                manualShifts(e.red, e.blue, vb[a], hb[b], e.vblsz, e.hblsz, e.bayer.w + (e.bayer.w & 1), e.bayer.h, s);
            } else {
                for (int p = 0; p < int(e.fits.size()); ++p) {
                    if (pass >= 0 && p != pass) continue;
                    double q[2][2];
                    fitShifts(e.fits[size_t(p)], vb[a], hb[b], q);
                    for (int c = 0; c < 2; ++c) for (int d = 0; d < 2; ++d) s[c][d] += q[c][d];
                }
            }
            for (int c = 0; c < 2; ++c) out[c] = std::max(out[c], std::hypot(s[c][0], s[c][1]));
        }
}
// CA_correct_RT on the base frame (auto: all passes; manual: one pass for the avoid-colour-shift factors). Passes from the first
// 4-coefficient fit on are dropped: RT's fallback solves the leading 4x4 of the 16-coefficient matrix (on synthetic frames with
// 12..30 blocks its shifts hit the +-3.99 clamp and made B worse); a 12 MP frame has ~1000 blocks.
inline BaseEstimate estimateBase(Bayer b, const uint16_t* raw, const BurstSettings& s) {
    BaseEstimate e;
    const auto t0 = std::chrono::steady_clock::now();
    b.wb = greyWorldWb(b, raw);
    e.bayer = b;
    e.vblsz = blocksV(b.h);
    e.hblsz = blocksH(b.w);
    e.manual = !s.autoCA;
    e.red = s.red;
    e.blue = s.blue;
    if (b.w < 256 || b.h < 256 || (b.w & 1) || (b.h & 1)) { e.why = "frame too small or odd"; return e; }
    if (e.manual && s.red == 0 && s.blue == 0) { e.why = "manual red and blue are 0"; return e; }
    unsigned cfa[2][2];
    cfaTable(b.cfa, cfa);
    std::vector<float> work(size_t(b.w) * b.h);
    toWorking(b, raw, work.data(), s.threads);
    Options o;
    o.autoCA = s.autoCA;
    o.autoIterations = size_t(std::clamp(s.passes, 1, 5));
    o.cared = s.red;
    o.cablue = s.blue;
    o.avoidColourshift = s.avoid;
    std::vector<Fit> fits;
    std::vector<Factors> factors;
    o.fitsOut = &fits;
    o.factorsOut = &factors;
    o.threads = s.threads;
    Diag d;
    o.diag = &d;
    bool ok = false;
    caCorrectRT(o, work.data(), b.w, b.h, cfa, nullptr, true, &ok);
    char line[200];
    if (e.manual) {
        e.passes = 1;
        if (s.avoid && !factors.empty()) e.factors.push_back(std::move(factors[0]));
        e.ok = true;
    } else {
        int estimated = 0;
        for (size_t k = 0; k < d.iters.size(); ++k) {
            const IterDiag& it = d.iters[k];
            if (!it.estimated) continue;
            ++estimated;
            std::snprintf(line, sizeof(line), " pass %zu: %d blocks, %s;", k + 1, it.numblox[1],
                          !it.solved ? "no fit" : it.polyord == 4 ? "16 coefficients" : "4 coefficients (dropped)");
            e.summary += line;
        }
        for (size_t k = 0; k < fits.size(); ++k) {
            if (fits[k].polyord != 4) break;
            e.fits.push_back(fits[k]);
            if (s.avoid && k < factors.size()) e.factors.push_back(factors[k]);
        }
        e.passes = int(e.fits.size());
        e.ok = e.passes > 0;
        if (!e.ok) e.why = estimated ? "too few blocks with CA signal" : "no estimate";
    }
    if (e.ok) { // pass 1 is the lens's field (synthetic truth: R median error 0.15 px); later passes add what pass 1 left
        e.summary += " corner shift R / B px:";
        for (int p = 0; p < (e.manual ? 1 : e.passes); ++p) {
            double c[2];
            cornerShifts(e, p, c);
            std::snprintf(line, sizeof(line), " pass %d %.2f / %.2f", p + 1, c[0], c[1]);
            e.summary += line;
        }
    }
    std::snprintf(line, sizeof(line), " wb %.3f/%.3f", b.wb[0], b.wb[2]);
    e.summary += line;
    e.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    return e;
}
// Per pass, per tile (vblsz x hblsz): R rows, R cols, B rows, B cols, as CA_correct_RT's correction pass applies them.
inline std::vector<float> tileTables(const BaseEstimate& e) {
    std::vector<float> t(size_t(e.passes) * e.vblsz * e.hblsz * 4, 0.f);
    const int width = e.bayer.w + (e.bayer.w & 1);
    for (int p = 0; p < e.passes; ++p)
        for (int vb = 0; vb < e.vblsz; ++vb)
            for (int hb = 0; hb < e.hblsz; ++hb) {
                double s[2][2];
                if (e.manual) manualShifts(e.red, e.blue, vb, hb, e.vblsz, e.hblsz, width, e.bayer.h, s);
                else fitShifts(e.fits[size_t(p)], vb, hb, s);
                float* q = t.data() + ((size_t(p) * e.vblsz + vb) * e.hblsz + hb) * 4;
                q[0] = float(s[0][0]); q[1] = float(s[0][1]); q[2] = float(s[1][0]); q[3] = float(s[1][1]);
            }
    return t;
}
// The port on the CPU: one frame with the base frame's fits / factors (G over the whole frame first, as the GPU does).
inline long correctFrameCpu(const BaseEstimate& e, const BurstSettings& s, const uint16_t* in, uint16_t* out) {
    const Bayer& b = e.bayer;
    unsigned cfa[2][2];
    cfaTable(b.cfa, cfa);
    std::vector<float> work(size_t(b.w) * b.h);
    toWorking(b, in, work.data(), s.threads);
    Options o;
    o.autoCA = !e.manual;
    o.cared = e.red;
    o.cablue = e.blue;
    o.avoidColourshift = s.avoid && !e.factors.empty();
    if (!e.manual) o.fitsIn = &e.fits;
    o.factorsIn = &e.factors;
    o.freshGtmp = true;
    o.threads = s.threads;
    caCorrectRT(o, work.data(), b.w, b.h, cfa, nullptr, true);
    return fromWorking(b, in, work.data(), out, s.threads, s.clipLevel);
}

} // namespace scam_rawca
