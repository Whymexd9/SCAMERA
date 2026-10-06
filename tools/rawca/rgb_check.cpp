// P28 base mode on a merged RGB: the base frame's estimate (estimateBase) applied to a linear RGB with correctRgb in three ways,
// for check_rgb.py to compare with the CA-free truth: the first pass's field only, the passes' fields summed into one, and
// one correctRgb per pass (sequential, as RT corrects the RAW).
//   rgb_check <base.r16> <rgb.f32 (h x w x 3, canonical geometry)> <out prefix> [--passes N] [--avoid]
// Writes <prefix>.first.f32, <prefix>.sum.f32, <prefix>.seq.f32 and prints the times.
#include "../../app/src/main/cpp/vivo-nice-rawca.h"
#include <fstream>
#include <string>

int main(int argc, char** argv) {
    if (argc < 4) return 2;
    std::ifstream in(argv[1], std::ios::binary);
    char magic[4];
    int32_t hd[3];
    float black[4], white;
    in.read(magic, 4); in.read(reinterpret_cast<char*>(hd), 12); in.read(reinterpret_cast<char*>(black), 16); in.read(reinterpret_cast<char*>(&white), 4);
    std::vector<uint16_t> raw(size_t(hd[0]) * hd[1]);
    in.read(reinterpret_cast<char*>(raw.data()), std::streamsize(raw.size() * 2));
    vivo_rawca::BurstSettings s;
    bool avoid = false;
    for (int i = 4; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--passes" && i + 1 < argc) s.passes = std::atoi(argv[++i]);
        else if (a == "--avoid") avoid = true;
    }
    s.avoid = false;
    vivo_rawca::Bayer b;
    b.w = hd[0]; b.h = hd[1]; b.cfa = hd[2]; b.white = white;
    for (int k = 0; k < 4; ++k) b.black[size_t(k)] = black[k];
    const vivo_rawca::BaseEstimate e = vivo_rawca::estimateBase(b, raw.data(), s);
    if (!e.ok) { std::fprintf(stderr, "no estimate: %s\n", e.why.c_str()); return 1; }
    // the RGB (h x w x 3, sensor layout)
    const int ox = 0, oy = 0, w = b.w, h = b.h; // the test RGB is in sensor layout (the worker passes the canonical offset)
    std::vector<float> rgb(size_t(w) * h * 3);
    std::ifstream f(argv[2], std::ios::binary);
    f.read(reinterpret_cast<char*>(rgb.data()), std::streamsize(rgb.size() * 4));
    const float g = std::max(e.bayer.wb[1], 1e-6f), wbR = e.bayer.wb[0] / g, wbB = e.bayer.wb[2] / g;
    const std::string prefix = argv[3];
    auto save = [&](const std::string& name, const std::vector<float>& v) {
        std::ofstream o(prefix + "." + name + ".f32", std::ios::binary);
        o.write(reinterpret_cast<const char*>(v.data()), std::streamsize(v.size() * 4));
    };
    auto timed = [&](const char* name, const std::function<void(std::vector<float>&)>& fn) {
        std::vector<float> r = rgb;
        const auto t0 = std::chrono::steady_clock::now();
        fn(r);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        std::printf("{\"variant\":\"%s\",\"ms\":%.0f}\n", name, ms);
        save(name, r);
    };
    vivo_rawca::RgbField f1;
    f1.W = w; f1.H = h;
    f1.fits.push_back(e.fits[0]);
    timed("first", [&](std::vector<float>& r) { vivo_rawca::correctRgb(r, w, h, 1, ox, oy, f1, wbR, wbB, 0, avoid); });
    vivo_rawca::RgbField fs = f1;
    fs.fits = e.fits;
    timed("sum", [&](std::vector<float>& r) { vivo_rawca::correctRgb(r, w, h, 1, ox, oy, fs, wbR, wbB, 0, avoid); });
    timed("seq", [&](std::vector<float>& r) {
        for (const auto& fit : e.fits) {
            vivo_rawca::RgbField one = f1;
            one.fits = {fit};
            vivo_rawca::correctRgb(r, w, h, 1, ox, oy, one, wbR, wbB, 0, avoid);
        }
    });
    std::printf("{\"passes\":%d,\"summary\":\"%s\"}\n", e.passes, e.summary.c_str());
    return 0;
}
