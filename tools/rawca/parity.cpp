// P28 parity harness: RawTherapee's CA_correct_RT (original source, rt_ref_*.cpp) against the worker's port
// (app/src/main/cpp/vivo-nice-rawca.h) on the same RAW. Build and run: python tools/rawca/run_parity.py.
//
//   parity <in.r16> [--in2 <frame2.r16>] [--iters N] [--threads T] [--manual R B] [--sse] [--time] [--out <corrected.r16>]
//
// .r16: "R16C", int32 w, h, cfa (sensor phase of red), float black[4], float white, uint16 w*h (sensor layout).
// The frame goes to RT's working scale with the port's toWorking (grey-world white balance), then each configuration runs
// through both implementations on copies, each with a zero-filled work buffer of the same size. Compared: the whole work
// buffer after the call (G at the R / B sites, the corrected R / B of the last pass, block weights and the per-tile shifts of
// the last estimation), the fitted coefficients, and the output plane. One JSON line per check on stdout.
#include "../../app/src/main/cpp/vivo-nice-rawca.h"
#include "rt/stub/rtengine.h"
#include "rt_ref.h"
#include <chrono>
#include <cstdio>
#include <fstream>
#include <string>

namespace rtengine {
static const Settings kSettings{};
const Settings* settings = &kSettings;
}

struct Frame { int w = 0, h = 0, cfa = 0; float black[4]{}, white = 0; std::vector<uint16_t> raw; };
static bool readR16(const std::string& path, Frame& f) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    char magic[4];
    int32_t hd[3];
    in.read(magic, 4); in.read(reinterpret_cast<char*>(hd), 12); in.read(reinterpret_cast<char*>(f.black), 16); in.read(reinterpret_cast<char*>(&f.white), 4);
    if (!in || std::memcmp(magic, "R16C", 4) != 0) return false;
    f.w = hd[0]; f.h = hd[1]; f.cfa = hd[2];
    f.raw.resize(size_t(f.w) * f.h);
    in.read(reinterpret_cast<char*>(f.raw.data()), std::streamsize(f.raw.size() * 2));
    return bool(in);
}
static void writeR16(const std::string& path, const Frame& f, const std::vector<uint16_t>& raw) {
    std::ofstream out(path, std::ios::binary);
    const int32_t hd[3] = {f.w, f.h, f.cfa};
    out.write("R16C", 4); out.write(reinterpret_cast<const char*>(hd), 12); out.write(reinterpret_cast<const char*>(f.black), 16);
    out.write(reinterpret_cast<const char*>(&f.white), 4); out.write(reinterpret_cast<const char*>(raw.data()), std::streamsize(raw.size() * 2));
}
static std::string jsonPath(std::string s) { for (char& c : s) if (c == '\\' || c == '"') c = '/'; return s; }
static vivo_rawca::Bayer bayerOf(const Frame& f) {
    vivo_rawca::Bayer b;
    b.w = f.w; b.h = f.h; b.cfa = f.cfa; b.white = f.white;
    for (int k = 0; k < 4; ++k) b.black[size_t(k)] = f.black[k];
    return b;
}
static double ms(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

struct Cmp { long differ = 0; double maxAbs = 0; };
static Cmp compare(const float* a, const float* b, size_t n) {
    Cmp c;
    for (size_t i = 0; i < n; ++i) {
        uint32_t x, y;
        std::memcpy(&x, a + i, 4); std::memcpy(&y, b + i, 4);
        if (x != y) {
            ++c.differ;
            const double d = std::fabs(double(a[i]) - double(b[i]));
            if (d > c.maxAbs || std::isnan(d)) c.maxAbs = std::isnan(d) ? 1e30 : d;
        }
    }
    return c;
}
// R / B samples that changed against the input; green samples that changed (must be 0)
static void changed(const float* in, const float* out, int w, int h, const unsigned cfa[2][2], long& rb, long& g) {
    rb = g = 0;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const size_t i = size_t(y) * w + x;
            if (std::memcmp(in + i, out + i, 4) == 0) continue;
            if (vivo_rawca::fc(cfa, y, x) == 1) ++g; else ++rb;
        }
}

struct Config { const char* name; bool autoCA; size_t iters; double cared, cablue; bool avoid; };

int main(int argc, char** argv) {
    std::string in1, in2, outPath;
    size_t iters = 2;
    int threads = 0;
    double mRed = 1.5, mBlue = -1.0;
    bool sse = false, timing = false;
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--in2" && i + 1 < argc) in2 = argv[++i];
        else if (a == "--iters" && i + 1 < argc) iters = size_t(std::atoi(argv[++i]));
        else if (a == "--threads" && i + 1 < argc) threads = std::atoi(argv[++i]);
        else if (a == "--manual" && i + 2 < argc) { mRed = std::atof(argv[++i]); mBlue = std::atof(argv[++i]); }
        else if (a == "--sse") sse = true;
        else if (a == "--time") timing = true;
        else if (a == "--out" && i + 1 < argc) outPath = argv[++i];
        else in1 = a;
    }
    Frame f;
    if (!readR16(in1, f)) { std::fprintf(stderr, "cannot read %s\n", in1.c_str()); return 2; }
    vivo_rawca::Bayer b = bayerOf(f);
    b.wb = vivo_rawca::greyWorldWb(b, f.raw.data());
    unsigned cfa[2][2];
    vivo_rawca::cfaTable(f.cfa, cfa);
    const int W = f.w, H = f.h;
    const size_t N = size_t(W) * H, bufN = vivo_rawca::bufferFloats(W, H);
    std::vector<float> work(N);
    vivo_rawca::toWorking(b, f.raw.data(), work.data(), threads);
    const int vblsz = vivo_rawca::blocksV(H), hblsz = vivo_rawca::blocksH(W);
    std::printf("{\"check\":\"input\",\"file\":\"%s\",\"w\":%d,\"h\":%d,\"cfa\":%d,\"wb\":[%.4f,%.4f,%.4f],\"vblsz\":%d,\"hblsz\":%d}\n",
                jsonPath(in1).c_str(), W, H, f.cfa, b.wb[0], b.wb[1], b.wb[2], vblsz, hblsz);
    bool allExact = true;

    const Config configs[] = {
        {"auto1", true, 1, 0, 0, false},
        {"auto1_avoid", true, 1, 0, 0, true},
        {"autoN_avoid", true, iters, 0, 0, true},
        {"autoN", true, iters, 0, 0, false},
        {"manual", false, 1, mRed, mBlue, false},
        {"manual_avoid", false, 1, mRed, mBlue, true},
    };
    std::vector<float> baseBufRef, baseBufPort;
    double baseFit[64]{};
    for (const Config& c : configs) {
        std::vector<float> ref = work, port = work;
        float* bufRef = static_cast<float*>(std::calloc(bufN, sizeof(float)));
        float* bufPort = static_cast<float*>(std::calloc(bufN, sizeof(float)));
        double fitRef[64]{}, fitPort[64]{};
        const auto t0 = std::chrono::steady_clock::now();
        rtRefScalar(c.autoCA, c.iters, c.cared, c.cablue, c.avoid, ref.data(), W, H, cfa, fitRef, false, true, bufRef, false);
        const double refMs = ms(t0);
        vivo_rawca::Options o;
        o.autoCA = c.autoCA; o.autoIterations = c.iters; o.cared = c.cared; o.cablue = c.cablue; o.avoidColourshift = c.avoid;
        o.fitParamsTransfer = fitPort; o.fitParamsOut = true; o.threads = threads;
        vivo_rawca::Diag diag; o.diag = &diag;
        bool ok = false;
        const auto t1 = std::chrono::steady_clock::now();
        vivo_rawca::caCorrectRT(o, port.data(), W, H, cfa, bufPort, false, &ok);
        const double portMs = ms(t1);
        const Cmp out = compare(ref.data(), port.data(), N);
        const Cmp buf = compare(bufRef, bufPort, bufN);
        // fitted coefficients: the solved ones (numpar of the last estimated pass)
        int polyord = 0, numblox0 = 0, numblox1 = 0;
        for (const auto& it : diag.iters) if (it.estimated) { polyord = it.polyord; numblox0 = it.numblox[0]; numblox1 = it.numblox[1]; }
        double fitMax = 0;
        long fitDiffer = 0;
        if (c.autoCA && ok)
            for (int k = 0; k < 64; ++k) {
                if ((k % 16) >= polyord * polyord) continue;
                if (std::memcmp(&fitRef[k], &fitPort[k], 8) != 0) { ++fitDiffer; fitMax = std::max(fitMax, std::fabs(fitRef[k] - fitPort[k])); }
            }
        long rbRef, gRef, rbPort, gPort;
        changed(work.data(), ref.data(), W, H, cfa, rbRef, gRef);
        changed(work.data(), port.data(), W, H, cfa, rbPort, gPort);
        // per-tile shifts of the last pass (the port's diag): largest |shift| applied, R / B, for the report
        double maxShift[2] = {0, 0};
        if (!diag.iters.empty())
            for (size_t t = 0; t + 3 < diag.iters.back().lblockshifts.size(); t += 4)
                for (int col = 0; col < 2; ++col)
                    maxShift[col] = std::max(maxShift[col], std::max(std::fabs(diag.iters.back().lblockshifts[t + size_t(col) * 2]), std::fabs(diag.iters.back().lblockshifts[t + size_t(col) * 2 + 1])));
        const bool exact = out.differ == 0 && buf.differ == 0 && fitDiffer == 0 && gRef == 0 && gPort == 0;
        allExact = allExact && exact;
        std::printf("{\"check\":\"%s\",\"ok\":%d,\"passes\":%zu,\"polyord\":%d,\"numblox\":[%d,%d],\"exact\":%d,\"out_differ\":%ld,\"out_maxabs\":%.3g,"
                    "\"buffer_differ\":%ld,\"buffer_maxabs\":%.3g,\"fit_differ\":%ld,\"fit_maxabs\":%.3g,\"rb_changed\":[%ld,%ld],\"g_changed\":[%ld,%ld],"
                    "\"max_shift_px\":[%.3f,%.3f],\"rt_ms\":%.1f,\"port_ms\":%.1f}\n",
                    c.name, int(ok), diag.iters.size(), polyord, numblox0, numblox1, int(exact), out.differ, out.maxAbs, buf.differ, buf.maxAbs,
                    fitDiffer, fitMax, rbRef, rbPort, gRef, gPort, maxShift[0], maxShift[1], refMs, portMs);
        if (std::string(c.name) == "auto1") {
            baseBufRef.assign(bufRef, bufRef + bufN); baseBufPort.assign(bufPort, bufPort + bufN);
            std::memcpy(baseFit, fitRef, sizeof(baseFit));
            // the same with one thread: the port is deterministic whatever the thread count
            std::vector<float> one = work;
            float* bufOne = static_cast<float*>(std::calloc(bufN, sizeof(float)));
            vivo_rawca::Options o1 = o; o1.threads = 1; vivo_rawca::Diag d1; o1.diag = &d1; double fit1[64]{}; o1.fitParamsTransfer = fit1;
            vivo_rawca::caCorrectRT(o1, one.data(), W, H, cfa, bufOne, false);
            const Cmp t1c = compare(one.data(), port.data(), N);
            std::printf("{\"check\":\"threads\",\"exact\":%d,\"out_differ\":%ld}\n", int(t1c.differ == 0), t1c.differ);
            allExact = allExact && t1c.differ == 0;
            std::free(bufOne);
            // per-tile shifts as estimated (pass 1): number of tiles and range of the block shifts that entered the fit
            if (!diag.iters.empty() && !diag.iters[0].blockshifts.empty()) {
                const auto& bs = diag.iters[0].blockshifts;
                double lo[2] = {1e9, 1e9}, hi[2] = {-1e9, -1e9};
                for (size_t t = 0; t < bs.size(); t += 4)
                    for (int col = 0; col < 2; ++col)
                        for (int d = 0; d < 2; ++d) {
                            const double v = bs[t + size_t(col) * 2 + size_t(d)];
                            if (std::fabs(v) >= 2.0) continue;
                            lo[col] = std::min(lo[col], v); hi[col] = std::max(hi[col], v);
                        }
                std::printf("{\"check\":\"tile_shifts\",\"tiles\":%zu,\"R_range\":[%.3f,%.3f],\"B_range\":[%.3f,%.3f]}\n", bs.size() / 4, lo[0], hi[0], lo[1], hi[1]);
            }
        }
        if (sse && c.autoCA) {
            // the SSE2 path of RawTherapee against its scalar path (summation order of the tile sums differs)
            std::vector<float> s = work;
            float* bufS = static_cast<float*>(std::calloc(bufN, sizeof(float)));
            double fitS[64]{};
            rtRefSse(c.autoCA, c.iters, c.cared, c.cablue, c.avoid, s.data(), W, H, cfa, fitS, false, true, bufS, false);
            const Cmp so = compare(ref.data(), s.data(), N);
            double fmax = 0, frel = 0;
            for (int k = 0; k < 64; ++k) {
                if ((k % 16) >= polyord * polyord) continue;
                fmax = std::max(fmax, std::fabs(fitS[k] - fitRef[k]));
                frel = std::max(frel, std::fabs(fitS[k] - fitRef[k]) / std::max(std::fabs(fitRef[k]), 1e-12));
            }
            // shifts applied per tile: SSE vs scalar (both at block centres, from the fits)
            double shiftMax = 0;
            vivo_rawca::Fit fr, fs; fr.polyord = fs.polyord = polyord > 0 ? polyord : 4;
            std::memcpy(fr.p, fitRef, sizeof(fr.p)); std::memcpy(fs.p, fitS, sizeof(fs.p));
            for (int vb = 1; vb < vblsz - 1; ++vb)
                for (int hb = 1; hb < hblsz - 1; ++hb) {
                    double a[2][2], bb[2][2];
                    vivo_rawca::fitShifts(fr, vb, hb, a); vivo_rawca::fitShifts(fs, vb, hb, bb);
                    for (int q = 0; q < 2; ++q) for (int d = 0; d < 2; ++d) shiftMax = std::max(shiftMax, std::fabs(a[q][d] - bb[q][d]));
                }
            std::printf("{\"check\":\"sse_vs_scalar_%s\",\"out_differ\":%ld,\"out_maxabs\":%.4g,\"fit_maxabs\":%.3g,\"fit_maxrel\":%.3g,\"tile_shift_maxabs_px\":%.3g}\n",
                        c.name, so.differ, so.maxAbs, fmax, frel, shiftMax);
            std::free(bufS);
        }
        std::free(bufRef); std::free(bufPort);
    }

    // RT's fitParamsIn: the base frame's fit applied to another frame (RT's Pixel Shift flow; the buffer of the base run)
    if (!in2.empty()) {
        Frame f2;
        if (!readR16(in2, f2) || f2.w != W || f2.h != H) { std::fprintf(stderr, "bad --in2\n"); return 2; }
        std::vector<float> w2(N);
        vivo_rawca::Bayer b2 = bayerOf(f2); b2.wb = b.wb;
        vivo_rawca::toWorking(b2, f2.raw.data(), w2.data(), threads);
        std::vector<float> ref = w2, port = w2;
        std::vector<float> bufRef = baseBufRef, bufPort = baseBufPort;
        double fitRef[64], fitPort[64];
        std::memcpy(fitRef, baseFit, sizeof(fitRef)); std::memcpy(fitPort, baseFit, sizeof(fitPort));
        rtRefScalar(true, 1, 0, 0, false, ref.data(), W, H, cfa, fitRef, true, false, bufRef.data(), false);
        vivo_rawca::Options o;
        o.autoCA = true; o.autoIterations = 1; o.avoidColourshift = false; o.fitParamsTransfer = fitPort; o.fitParamsIn = true; o.threads = threads;
        vivo_rawca::caCorrectRT(o, port.data(), W, H, cfa, bufPort.data(), false);
        const Cmp out = compare(ref.data(), port.data(), N);
        const Cmp buf = compare(bufRef.data(), bufPort.data(), bufN);
        long rb, g;
        changed(w2.data(), port.data(), W, H, cfa, rb, g);
        allExact = allExact && out.differ == 0 && buf.differ == 0 && g == 0;
        std::printf("{\"check\":\"fit_params_in\",\"exact\":%d,\"out_differ\":%ld,\"buffer_differ\":%ld,\"rb_changed\":%ld,\"g_changed\":%ld}\n",
                    int(out.differ == 0 && buf.differ == 0), out.differ, buf.differ, rb, g);
    }

    if (timing) {
        const int hw = vivo_rawca::threadCount(threads);
        auto best = [&](int reps, const std::function<void()>& fn) {
            double m = 1e30;
            for (int r = 0; r < reps; ++r) { const auto t0 = std::chrono::steady_clock::now(); fn(); m = std::min(m, ms(t0)); }
            return m;
        };
        float* buf = static_cast<float*>(std::calloc(bufN, sizeof(float)));
        std::vector<vivo_rawca::Fit> fits;
        std::vector<vivo_rawca::Factors> factors;
        auto estimate = [&](int t, bool keep) {
            std::vector<float> p = work;
            vivo_rawca::Options o;
            o.autoCA = true; o.autoIterations = iters; o.avoidColourshift = true; o.threads = t;
            if (keep) { o.fitsOut = &fits; o.factorsOut = &factors; }
            vivo_rawca::caCorrectRT(o, p.data(), W, H, cfa, buf, false);
        };
        const double est1 = best(1, [&] { estimate(1, false); });
        const double estN = best(3, [&] { estimate(hw, true); });
        // frames mode on the CPU (the GPU's fallback): the base's fits and factors applied to a frame
        std::vector<float> p(N);
        const double corrN = best(3, [&] {
            p = work;
            vivo_rawca::Options o;
            o.autoCA = true; o.avoidColourshift = true; o.fitsIn = &fits; o.factorsIn = &factors; o.freshGtmp = true; o.threads = hw;
            vivo_rawca::caCorrectRT(o, p.data(), W, H, cfa, buf, false);
        });
        // uint16 round trip of a frame
        std::vector<uint16_t> codes(N);
        const double conv = best(3, [&] {
            vivo_rawca::toWorking(b, f.raw.data(), p.data(), hw);
            vivo_rawca::fromWorking(b, f.raw.data(), p.data(), codes.data(), hw);
        });
        // base mode: the summed field on a merged RGB of the frame size (nearest-neighbour RGB of the frame)
        std::vector<float> rgb(N * 3);
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x)
                for (int q = 0; q < 3; ++q) {
                    const int yy = (y & ~1) + ((q == 0 ? f.cfa >> 1 : q == 2 ? (f.cfa ^ 3) >> 1 : (f.cfa >> 1) ^ 1));
                    const int xx = (x & ~1) + ((q == 0 ? f.cfa & 1 : q == 2 ? (f.cfa ^ 3) & 1 : f.cfa & 1));
                    rgb[(size_t(y) * W + x) * 3 + size_t(q)] = work[size_t(std::min(yy, H - 1)) * W + std::min(xx, W - 1)] / 65535.f;
                }
        vivo_rawca::RgbField field; field.fits = fits; field.W = W; field.H = H;
        const double rgbMs = best(2, [&] { std::vector<float> r = rgb; vivo_rawca::correctRgb(r, W, H, 1, 0, 0, field, 1.f, 1.f, hw); });
        std::printf("{\"check\":\"timing\",\"mp\":%.2f,\"passes\":%zu,\"threads\":%d,\"estimate_1thread_ms\":%.0f,\"estimate_ms\":%.0f,"
                    "\"correct_frame_cpu_ms\":%.0f,\"uint16_roundtrip_ms\":%.0f,\"rgb_base_mode_ms\":%.0f}\n",
                    N / 1e6, iters, hw, est1, estN, corrN, conv, rgbMs);
        std::free(buf);
    }

    if (!outPath.empty()) {
        // the port's auto correction (iters passes, avoid colour shift) back to codes
        std::vector<float> p = work;
        vivo_rawca::Options o;
        o.autoCA = true; o.autoIterations = iters; o.avoidColourshift = true; o.threads = threads;
        bool ok = false;
        vivo_rawca::caCorrectRT(o, p.data(), W, H, cfa, nullptr, true, &ok);
        std::vector<uint16_t> codes(N);
        const long n = vivo_rawca::fromWorking(b, f.raw.data(), p.data(), codes.data(), threads);
        writeR16(outPath, f, codes);
        std::printf("{\"check\":\"written\",\"ok\":%d,\"sites_changed\":%ld,\"path\":\"%s\"}\n", int(ok), n, jsonPath(outPath).c_str());
    }
    std::printf("{\"check\":\"summary\",\"all_exact\":%d}\n", int(allExact));
    return allExact ? 0 : 1;
}
