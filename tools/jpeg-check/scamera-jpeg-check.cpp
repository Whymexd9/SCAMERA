// P147: the JPEG encoders of SCAMERA side by side on the phone (built with the NDK, run through adb shell):
//   one thread (scamera_jpeg::encodeRgba, progressive) and strips on N threads (scamera_jpeg::parallel::encodeRgba).
// Both files are decoded with jpegli and compared with each other and with the source: time, size, PSNR, largest
// difference. Usage: scamera-jpeg-check <width> <height> <quality> <threads> [<rgba file of width*height*4 bytes>] [out dir]
#include <chrono>
#include <cmath>
#include <cstdio>
#include <algorithm>
#include <cstdlib>
#include <string>
#include <vector>

#include "scamera-jpeg-core.h"
#include "scamera-jpeg-parallel.h"
#include "lib/jpegli/decode.h"

namespace {
std::vector<uint8_t> synthetic(int w, int h) {
    std::vector<uint8_t> px(size_t(w) * h * 4);
    uint32_t seed = 12345;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            seed = seed * 1664525u + 1013904223u;
            const float n = float((seed >> 8) & 0xFF) / 255.f - .5f;
            const float fx = float(x) / w, fy = float(y) / h;
            float r = 40 + 180 * fx + 18 * n, g = 60 + 120 * fy + 18 * n + 30 * std::sin(x * 0.05f) * std::cos(y * 0.07f);
            float b = 90 + 100 * (1 - fx) * fy + 18 * n;
            if (((x / 97) + (y / 61)) % 7 == 0) { r = 250; g = 245; b = 230; }   // sharp bright patches
            uint8_t* p = &px[(size_t(y) * w + x) * 4];
            p[0] = uint8_t(std::min(255.f, std::max(0.f, r)));
            p[1] = uint8_t(std::min(255.f, std::max(0.f, g)));
            p[2] = uint8_t(std::min(255.f, std::max(0.f, b)));
            p[3] = 255;
        }
    }
    return px;
}

std::vector<uint8_t> decode(const std::vector<uint8_t>& jpeg, int& w, int& h) {
    jpeg_decompress_struct d{};
    jpeg_error_mgr e{};
    d.err = jpegli_std_error(&e);
    jpegli_create_decompress(&d);
    jpegli_mem_src(&d, jpeg.data(), (unsigned long) jpeg.size());
    jpegli_read_header(&d, TRUE);
    d.out_color_space = JCS_RGB;
    jpegli_start_decompress(&d);
    w = int(d.output_width);
    h = int(d.output_height);
    std::vector<uint8_t> rgb(size_t(w) * h * 3);
    while (d.output_scanline < d.output_height) {
        JSAMPROW row = &rgb[size_t(d.output_scanline) * w * 3];
        jpegli_read_scanlines(&d, &row, 1);
    }
    jpegli_finish_decompress(&d);
    jpegli_destroy_decompress(&d);
    return rgb;
}

double compare(const char* label, const uint8_t* a, size_t strideA, const uint8_t* b, size_t strideB, int w, int h) {
    double se = 0;
    int worst = 0;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            for (int c = 0; c < 3; ++c) {
                const int d = int(a[size_t(y) * strideA + size_t(x) * (strideA / size_t(w)) + c])
                              - int(b[size_t(y) * strideB + size_t(x) * (strideB / size_t(w)) + c]);
                se += double(d) * d;
                worst = std::max(worst, std::abs(d));
            }
    const double mse = se / (double(w) * h * 3);
    const double psnr = mse == 0 ? 999.0 : 10 * std::log10(255.0 * 255.0 / mse);
    std::printf("  %-28s PSNR %s dB  max |diff| %d\n", label, mse == 0 ? "inf" : std::to_string(psnr).c_str(), worst);
    return psnr;
}
}  // namespace

int main(int argc, char** argv) {
    if (argc < 5) { std::fprintf(stderr, "usage: %s w h quality threads [rgba] [outdir]\n", argv[0]); return 2; }
    const int w = std::atoi(argv[1]), h = std::atoi(argv[2]), q = std::atoi(argv[3]), threads = std::atoi(argv[4]);
    std::vector<uint8_t> px;
    if (argc > 5 && std::string(argv[5]) != "-") {
        FILE* f = std::fopen(argv[5], "rb");
        if (!f) { std::perror("rgba"); return 2; }
        px.resize(size_t(w) * h * 4);
        if (std::fread(px.data(), 1, px.size(), f) != px.size()) { std::fprintf(stderr, "short rgba file\n"); return 2; }
        std::fclose(f);
    } else {
        px = synthetic(w, h);
    }
    const std::string out = argc > 6 ? argv[6] : ".";
    std::vector<uint8_t> single, multi;
    auto t0 = std::chrono::steady_clock::now();
    scamera_jpeg::encodeRgba(px.data(), w, h, size_t(w) * 4, q, [&](const uint8_t* d, size_t n) { single.insert(single.end(), d, d + n); return true; });
    auto t1 = std::chrono::steady_clock::now();
    scamera_jpeg::parallel::encodeRgba(px.data(), w, h, size_t(w) * 4, q, threads, 0,
                                       [&](const uint8_t* d, size_t n) { multi.insert(multi.end(), d, d + n); return true; });
    auto t2 = std::chrono::steady_clock::now();
    std::vector<uint8_t> every;
    scamera_jpeg::parallel::encodeRgba(px.data(), w, h, size_t(w) * 4, q, threads, 0,
                                       [&](const uint8_t* d, size_t n) { every.insert(every.end(), d, d + n); return true; }, 1);
    auto t3 = std::chrono::steady_clock::now();
    std::printf("  pass 1 on every strip: %.0f ms %zu B\n", std::chrono::duration<double, std::milli>(t3 - t2).count(), every.size());
    const double ms1 = std::chrono::duration<double, std::milli>(t1 - t0).count();
    const double ms2 = std::chrono::duration<double, std::milli>(t2 - t1).count();
    std::printf("%dx%d q%d: one thread %.0f ms %zu B | %d threads %.0f ms %zu B (%.2fx faster, size %+.2f%%)\n", w, h, q, ms1,
                single.size(), threads, ms2, multi.size(), ms1 / ms2, 100.0 * (double(multi.size()) / double(single.size()) - 1));
    int w1, h1, w2, h2;
    std::vector<uint8_t> d1 = decode(single, w1, h1), d2 = decode(multi, w2, h2);
    if (w1 != w || h1 != h || w2 != w || h2 != h) { std::printf("FAIL: decoded size\n"); return 1; }
    compare("one thread vs source", d1.data(), size_t(w) * 3, px.data(), size_t(w) * 4, w, h);
    compare("strips vs source", d2.data(), size_t(w) * 3, px.data(), size_t(w) * 4, w, h);
    const double same = compare("strips vs one thread", d2.data(), size_t(w) * 3, d1.data(), size_t(w) * 3, w, h);
    // the strips must give the one-thread pixels (only jpegli's adaptive quantisation at a strip's edge may differ)
    if (same < 60) { std::printf("FAIL: the strips differ from the one-thread JPEG (%.1f dB)\n", same); return 1; }
    if (multi.size() > single.size() * 11 / 10) { std::printf("FAIL: the strips' file is more than 10 %% larger\n"); return 1; }
    FILE* f = std::fopen((out + "/single.jpg").c_str(), "wb");
    if (f) { std::fwrite(single.data(), 1, single.size(), f); std::fclose(f); }
    f = std::fopen((out + "/parallel.jpg").c_str(), "wb");
    if (f) { std::fwrite(multi.data(), 1, multi.size(), f); std::fclose(f); }
    std::printf("jpeg check PASS\n");
    return 0;
}
