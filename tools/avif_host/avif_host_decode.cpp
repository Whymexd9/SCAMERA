// Host build of the gallery's AVIF decode (P65: app/src/main/cpp/scamera-avif-core.h decodeToRgba8, the same libavif /
// libaom pins as the APK's libscameraAvif.so) for tools/check_avif.py: decodes an AVIF file to raw RGBA rows and prints
// one JSON line.
//
//   avif_host_decode in=<file.avif> out=<file.raw> [maxside=0] [threads=4]
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <map>
#include <string>
#include <vector>

#include "scamera-avif-core.h"

int main(int argc, char** argv) {
    std::map<std::string, std::string> a;
    for (int i = 1; i < argc; ++i) {
        const std::string s(argv[i]);
        const size_t eq = s.find('=');
        if (eq != std::string::npos) a[s.substr(0, eq)] = s.substr(eq + 1);
    }
    if (!a.count("in") || !a.count("out")) {
        std::fprintf(stderr, "usage: avif_host_decode in=<file.avif> out=<file.raw> [maxside=0] [threads=4]\n");
        return 2;
    }
    std::ifstream in(a["in"], std::ios::binary);
    if (!in) {
        std::printf("{\"error\":\"cannot read input\"}\n");
        return 1;
    }
    const std::vector<uint8_t> data((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    scamera_avif::Decoded decoded;
    const std::string error = scamera_avif::decodeToRgba8(data.data(), data.size(), a.count("maxside") ? std::atoi(a["maxside"].c_str()) : 0,
                                                          a.count("threads") ? std::atoi(a["threads"].c_str()) : 4, &decoded);
    if (!error.empty()) {
        std::printf("{\"error\":\"%s\"}\n", error.c_str());
        return 1;
    }
    std::ofstream out(a["out"], std::ios::binary);
    out.write(reinterpret_cast<const char*>(decoded.rgba.data()), std::streamsize(decoded.rgba.size()));
    std::printf("{\"width\":%u,\"height\":%u,\"source_width\":%u,\"source_height\":%u,\"depth\":%u}\n", decoded.width,
                decoded.height, decoded.sourceWidth, decoded.sourceHeight, decoded.depth);
    return out ? 0 : 1;
}
