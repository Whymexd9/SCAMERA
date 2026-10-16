// P187: DNGWriter::WriteToMemory (one buffer of the final size) gives the same bytes as WriteToFile (the stream path it
// replaced): little and big endian, one and several images, small and 12.5 MP frames.
#define TINY_DNG_WRITER_IMPLEMENTATION
#include "../app/src/main/cpp/deps/tiny_dng_writer.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

static bool same(int w, int h, bool bigEndian, int images, const std::string &path) {
    tinydngwriter::DNGWriter writer(bigEndian);
    std::vector<tinydngwriter::DNGImage *> keep;
    std::vector<std::vector<unsigned short>> pixels(images);
    for (int k = 0; k < images; k++) {
        auto *img = new tinydngwriter::DNGImage();
        img->SetBigEndian(bigEndian);
        img->SetSubfileType(false, false, false);
        img->SetSamplesPerPixel(1);
        img->SetPhotometric(tinydngwriter::PHOTOMETRIC_CFA);
        img->SetPlanarConfig(tinydngwriter::PLANARCONFIG_CONTIG);
        img->SetDNGVersion(1, 3, 0, 0);
        unsigned short bps = 16;
        img->SetBitsPerSample(1, &bps);
        img->SetImageWidth(w);
        img->SetImageLength(h);
        img->SetRowsPerStrip(h);
        img->SetUniqueCameraModel("SCAMERA check");
        unsigned short black[4] = {64, 64, 64, 64};
        img->SetBlackLevelRepeatDim(2, 2);
        img->SetBlackLevel(4, black);
        double white = 1023;
        img->SetWhiteLevelRational(1, &white);
        unsigned char cfa[4] = {0, 1, 1, 2};
        img->SetCFARepeatPatternDim(2, 2);
        img->SetCFAPattern(4, cfa);
        double cm[9] = {1.1, -0.2, 0.1, -0.3, 1.2, 0.1, 0.05, -0.4, 1.3};
        img->SetColorMatrix1(3, cm);
        double neutral[3] = {0.5, 1.0, 0.6};
        img->SetAsShotNeutral(3, neutral);
        pixels[k].resize(size_t(w) * h);
        for (size_t i = 0; i < pixels[k].size(); i++) pixels[k][i] = (unsigned short) (((i * 2654435761u + k * 77) >> 7) & 1023);
        img->SetImageData(reinterpret_cast<const unsigned char *>(pixels[k].data()), pixels[k].size() * 2);
        writer.AddImage(img);
        keep.push_back(img);
    }
    size_t size = 0, strip = 0;
    std::string err;
    void *mem = writer.WriteToMemory(&size, &err, &strip);
    bool ok = mem != nullptr && writer.WriteToFile(path.c_str(), &err);
    if (ok) {
        std::ifstream in(path, std::ios::binary);
        std::vector<char> file((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        ok = file.size() == size && memcmp(file.data(), mem, size) == 0;
        printf("%dx%d %s x%d: %zu B %s\n", w, h, bigEndian ? "big" : "little", images, size, ok ? "same" : "DIFFERENT");
    } else {
        printf("%dx%d: failed: %s\n", w, h, err.c_str());
    }
    free(mem);
    for (auto *p : keep) delete p;
    remove(path.c_str());
    return ok;
}

int main(int argc, char **argv) {
    const std::string dir = argc > 1 ? argv[1] : "/tmp";
    bool ok = same(64, 48, false, 1, dir + "/dng-a.dng");
    ok &= same(4080, 3072, false, 1, dir + "/dng-b.dng");
    ok &= same(130, 66, true, 1, dir + "/dng-c.dng");
    ok &= same(96, 40, false, 2, dir + "/dng-d.dng");
    ok &= same(96, 40, true, 3, dir + "/dng-e.dng");
    printf(ok ? "DNG memory writer: OK\n" : "DNG memory writer: FAILED\n");
    return ok ? 0 : 1;
}
