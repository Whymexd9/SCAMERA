// P187: DNGWriter::WriteToMemory (one buffer of the final size) gives the same bytes as WriteToFile (the stream path it
// replaced): little and big endian, one and several images, small and 12.5 MP frames.
// P280 (owner, 2026-10-19: «Наши равы не открываются в лайтруме»): the file is valid TIFF for the DNG SDK - every IFD, every
// value outside an IFD and every strip at an even offset, the tags of each IFD ascending, WhiteLevel a LONG.
#define TINY_DNG_WRITER_IMPLEMENTATION
#include "../app/src/main/cpp/deps/tiny_dng_writer.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

static unsigned rd(const unsigned char *p, int n, bool be) {
    unsigned v = 0;
    for (int i = 0; i < n; i++) v |= unsigned(p[be ? n - 1 - i : i]) << (8 * i);
    return v;
}

/** P280: the TIFF structure the DNG SDK insists on; prints what is wrong. */
static bool structure(const unsigned char *b, size_t size, bool be) {
    static const int typeSize[] = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8};
    unsigned ifd = rd(b + 4, 4, be);
    bool ok = true;
    int n = 0;
    while (ifd != 0 && n++ < 8) {
        if (ifd % 2 || ifd + 2 > size) { printf("  IFD at %u: odd or past the end\n", ifd); return false; }
        const unsigned count = rd(b + ifd, 2, be);
        unsigned last = 0;
        for (unsigned i = 0; i < count; i++) {
            const unsigned char *e = b + ifd + 2 + 12 * i;
            const unsigned tag = rd(e, 2, be), type = rd(e + 2, 2, be), cnt = rd(e + 4, 4, be), val = rd(e + 8, 4, be);
            if (i > 0 && tag <= last) { printf("  tag %u after %u\n", tag, last); ok = false; }
            last = tag;
            const size_t len = size_t(cnt) * (type < 13 ? typeSize[type] : 1);
            if (len > 4 && (val % 2 || val + len > size)) { printf("  tag %u value at %u (%zu B): odd or past the end\n", tag, val, len); ok = false; }
            if (tag == 273 && val % 2) { printf("  strip at odd offset %u\n", val); ok = false; }
            if (tag == 50717 && type != 3 && type != 4) { printf("  WhiteLevel of type %u\n", type); ok = false; }
        }
        ifd = rd(b + ifd + 2 + 12 * count, 4, be);
    }
    return ok;
}

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
        img->SetUniqueCameraModel("SCAMERA check!"); // 15 bytes: an odd value outside the IFD
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
        const bool valid = structure(static_cast<const unsigned char *>(mem), size, bigEndian);
        printf("%dx%d %s x%d: %zu B %s, structure %s\n", w, h, bigEndian ? "big" : "little", images, size,
               ok ? "same" : "DIFFERENT", valid ? "valid" : "INVALID");
        ok &= valid;
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
    ok &= same(97, 41, false, 1, dir + "/dng-f.dng"); // an odd strip length
    printf(ok ? "DNG memory writer: OK\n" : "DNG memory writer: FAILED\n");
    return ok ? 0 : 1;
}
