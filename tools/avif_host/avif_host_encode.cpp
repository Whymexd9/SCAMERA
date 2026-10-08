// Host build of the app's AVIF encoder (app/src/main/cpp/scamera-avif-core.h, same libavif / libaom pins) for
// tools/check_avif.py: encodes a raw pixel file exactly as the JNI library encodes a Bitmap and prints the result as one
// JSON line.
//
//   avif_host_encode in=<raw> width=<w> height=<h> layout=8888|1010102 out=<file.avif>
//                    [quality=90] [lossless=0] [depth=10] [yuv=444|420] [speed=6] [threads=<cpus>] [exif=<file>]
//                    [aom=<key>:<value>,...]   (extra libaom options, for tuning runs)
//                    [cicp=<primaries>/<transfer>/<matrix>] [icc=<file>]   (P46 colour; default 1/13/1, no ICC)
//
// The raw file holds rows of 4-byte pixels without padding, in the Android Bitmap memory layout of the layout.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <map>
#include <string>
#include <thread>
#include <vector>

#if defined(_WIN32)
#define PSAPI_VERSION 2
#include <windows.h>
#include <psapi.h>
#else
#include <sys/resource.h>
#endif

#include "scamera-avif-core.h"

namespace {
bool readFile(const std::string& path, std::vector<uint8_t>* out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    out->assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    return true;
}

double peakRssMb() {
#if defined(_WIN32)
    PROCESS_MEMORY_COUNTERS counters{};
    if (GetProcessMemoryInfo(GetCurrentProcess(), &counters, sizeof(counters))) return counters.PeakWorkingSetSize / 1048576.0;
    return -1;
#else
    rusage usage{};
    if (getrusage(RUSAGE_SELF, &usage) == 0) return usage.ru_maxrss / 1024.0; // kilobytes on Linux
    return -1;
#endif
}

void fail(const std::string& reason) {
    std::printf("{\"ok\":false,\"error\":\"%s\"}\n", reason.c_str());
    std::exit(1);
}
}  // namespace

int main(int argc, char** argv) {
    std::map<std::string, std::string> args;
    for (int i = 1; i < argc; ++i) {
        const char* eq = std::strchr(argv[i], '=');
        if (eq == nullptr) fail(std::string("argument without '=': ") + argv[i]);
        args[std::string(argv[i], size_t(eq - argv[i]))] = eq + 1;
    }
    auto get = [&](const char* key, const char* fallback) {
        auto it = args.find(key);
        if (it != args.end()) return it->second;
        if (fallback == nullptr) fail(std::string("missing ") + key);
        return std::string(fallback);
    };
    const int width = std::atoi(get("width", nullptr).c_str());
    const int height = std::atoi(get("height", nullptr).c_str());
    const std::string layoutName = get("layout", "8888");
    if (layoutName != "8888" && layoutName != "1010102") fail("layout must be 8888 or 1010102");
    const scamera_avif::Layout layout =
        layoutName == "8888" ? scamera_avif::Layout::Rgba8888 : scamera_avif::Layout::Rgba1010102;
    scamera_avif::Options options;
    options.quality = std::atoi(get("quality", "90").c_str());
    options.lossless = get("lossless", "0") == "1";
    options.depth = std::atoi(get("depth", "10").c_str());
    options.yuv444 = get("yuv", "444") != "420";
    options.speed = std::atoi(get("speed", "6").c_str());
    const unsigned cpus = std::thread::hardware_concurrency();
    options.threads = std::atoi(get("threads", std::to_string(cpus == 0 ? 1 : cpus).c_str()).c_str());

    for (std::string list = get("aom", ""); !list.empty();) {
        const size_t comma = list.find(',');
        const std::string item = list.substr(0, comma);
        list = comma == std::string::npos ? "" : list.substr(comma + 1);
        const size_t colon = item.find(':');
        if (colon == std::string::npos) fail("aom option without ':': " + item);
        options.codecOptions.emplace_back(item.substr(0, colon), item.substr(colon + 1));
    }

    const std::string cicp = get("cicp", "");
    if (!cicp.empty() && std::sscanf(cicp.c_str(), "%d/%d/%d", &options.primaries, &options.transfer, &options.matrix) != 3)
        fail("cicp must be primaries/transfer/matrix");
    const std::string iccPath = get("icc", "");
    if (!iccPath.empty() && !readFile(iccPath, &options.icc)) fail("cannot read icc");

    std::vector<uint8_t> pixels, exif;
    if (!readFile(get("in", nullptr), &pixels)) fail("cannot read in");
    if (width <= 0 || height <= 0 || pixels.size() != size_t(width) * size_t(height) * 4) fail("in size does not match");
    const std::string exifPath = get("exif", "");
    if (!exifPath.empty() && !readFile(exifPath, &exif)) fail("cannot read exif");

    scamera_avif::Stats stats;
    const std::string error =
        scamera_avif::encodeToFile(pixels.data(), width, height, size_t(width) * 4, layout, options,
                                   exif.empty() ? nullptr : exif.data(), exif.size(), get("out", nullptr), &stats);
    if (!error.empty()) fail(error);
    std::printf("{\"ok\":true,\"depth\":%d,\"yuv444\":%s,\"lossless\":%s,\"exif\":%s,\"convert_ms\":%.1f,"
                "\"encode_ms\":%.1f,\"bytes\":%zu,\"threads\":%d,\"peak_rss_mb\":%.1f}\n",
                stats.depth, stats.yuv444 ? "true" : "false", stats.lossless ? "true" : "false",
                stats.exif ? "true" : "false", stats.convertMs, stats.encodeMs, stats.bytes, options.threads,
                peakRssMb());
    return 0;
}
