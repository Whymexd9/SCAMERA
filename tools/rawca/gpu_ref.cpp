// P28 frames mode, CPU side of the host GPU check (check_gpu.py): the base frame's estimate (estimateBase), the tables the GPU
// pre-pass uploads (tiles, factors, uniforms) and the CPU port's correction of a frame with them (correctFrameCpu, the GPU's
// fallback), for the GLSL of vivo-nice-rawca-gpu.h to be run on the same data through desktop GL.
//
//   gpu_ref <base.r16> <frame.r16> <out dir> [--passes N] [--manual R B] [--no-avoid]
// Writes <out>/meta.json, tiles.f32, factors.f32, frame.u16 (input codes), cpu.u16 (CPU result).
#include "../../app/src/main/cpp/vivo-nice-rawca.h"
#include <fstream>
#include <string>

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
template<class T> static void dump(const std::string& path, const std::vector<T>& v) {
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(v.data()), std::streamsize(v.size() * sizeof(T)));
}

int main(int argc, char** argv) {
    if (argc < 4) { std::fprintf(stderr, "usage: gpu_ref base.r16 frame.r16 outdir [--passes N] [--manual R B] [--no-avoid]\n"); return 2; }
    Frame base, frame;
    if (!readR16(argv[1], base) || !readR16(argv[2], frame) || base.w != frame.w || base.h != frame.h) { std::fprintf(stderr, "bad input\n"); return 2; }
    const std::string out = argv[3];
    vivo_rawca::BurstSettings s;
    s.mode = 2;
    for (int i = 4; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--passes" && i + 1 < argc) s.passes = std::atoi(argv[++i]);
        else if (a == "--manual" && i + 2 < argc) { s.autoCA = false; s.red = std::atof(argv[++i]); s.blue = std::atof(argv[++i]); }
        else if (a == "--no-avoid") s.avoid = false;
    }
    vivo_rawca::Bayer b;
    b.w = base.w; b.h = base.h; b.cfa = base.cfa; b.white = base.white;
    for (int k = 0; k < 4; ++k) b.black[size_t(k)] = base.black[k];
    const vivo_rawca::BaseEstimate e = vivo_rawca::estimateBase(b, base.raw.data(), s);
    if (!e.ok) { std::fprintf(stderr, "estimate failed: %s\n", e.why.c_str()); return 1; }
    std::vector<uint16_t> cpu(frame.raw.size());
    const auto t0 = std::chrono::steady_clock::now();
    const long changed = vivo_rawca::correctFrameCpu(e, s, frame.raw.data(), cpu.data());
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    dump(out + "/tiles.f32", vivo_rawca::tileTables(e));
    std::vector<float> fac;
    const bool factors = !e.factors.empty() && int(e.factors.size()) >= e.passes;
    if (factors)
        for (int p = 0; p < e.passes; ++p) {
            fac.insert(fac.end(), e.factors[size_t(p)].red.begin(), e.factors[size_t(p)].red.end());
            fac.insert(fac.end(), e.factors[size_t(p)].blue.begin(), e.factors[size_t(p)].blue.end());
        }
    else fac.assign(4, 1.f);
    dump(out + "/factors.f32", fac);
    dump(out + "/frame.u16", frame.raw);
    dump(out + "/cpu.u16", cpu);
    const auto sc = vivo_rawca::workingScale(e.bayer);
    std::ofstream m(out + "/meta.json");
    char line[1024];
    std::snprintf(line, sizeof(line),
                  "{\"w\":%d,\"h\":%d,\"cfa\":%d,\"black\":[%.9g,%.9g,%.9g,%.9g],\"white\":%.9g,\"scale\":[%.9g,%.9g,%.9g,%.9g],\"clip_level\":%.9g,"
                  "\"vblsz\":%d,\"hblsz\":%d,\"passes\":%d,\"factors\":%d,\"fW\":%d,\"fH\":%d,\"cpu_changed\":%ld,\"cpu_ms\":%.1f,\"summary\":\"%s\"}\n",
                  e.bayer.w, e.bayer.h, e.bayer.cfa, e.bayer.black[0], e.bayer.black[1], e.bayer.black[2], e.bayer.black[3], e.bayer.white,
                  sc[0], sc[1], sc[2], sc[3], s.clipLevel, e.vblsz, e.hblsz, e.passes, int(factors), factors ? e.factors[0].w : 1,
                  factors ? e.factors[0].h : 1, changed, ms, e.summary.c_str());
    m << line;
    std::printf("%s", line);
    return 0;
}
