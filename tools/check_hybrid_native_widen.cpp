// Native mosaic merge (Quad / Tetra): the base frame's kernel widens smoothly where moving objects leave few accepted donor frames
// (mosaicNativeWiden 2). Without it the base's narrow native kernel on the sparse colour blocks of one frame drew the block lattice
// around moving objects (vivo 4x ISZ Tetra "honeycomb", 2026-10-08). Checks the defaults, the tuning keys and that both native merge
// shaders carry the rule (research/moving-objects/MOVING_OBJECTS_REPORT.md has the device sweep).
// vivo-nice-hybrid.h is not self-contained: the worker includes vivo-nice-capture.h first.
#include "../app/src/main/cpp/vivo-nice-capture.h"
#include "../app/src/main/cpp/vivo-nice-hybrid.h"
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <unistd.h>
using namespace vivo_nice;

static int count(const std::string& s, const std::string& what) {
    int n = 0;
    for (size_t p = s.find(what); p != std::string::npos; p = s.find(what, p + 1)) ++n;
    return n;
}

int main() {
    const HybridTuning d;
    assert(d.mosaicNativeWiden == 2);
    assert(d.mosaicTetraWidenMul == 3.f && d.mosaicTetraWidenFrames == 8.f);
    assert(d.mosaicNativeWidenMul == 2.5f && d.mosaicNativeWidenFrames == 4.f);

    const char* tmp = std::getenv("TMPDIR");
    std::string tmpl = std::string(tmp && *tmp ? tmp : "/tmp") + "/hybrid-widen-XXXXXX";
    char* dir = tmpl.data();
    if (!mkdtemp(dir)) { std::perror("mkdtemp"); return 1; }
    {
        std::ofstream f(std::string(dir) + "/hybrid_tuning.txt");
        f << "mosaicNativeWiden 0\nmosaicNativeWidenMul 1.5\nmosaicNativeWidenFrames 3\nmosaicTetraWidenMul 4\nmosaicTetraWidenFrames 6\n";
    }
    const HybridTuning t = loadHybridTuning(dir, [](const std::string&) {});
    assert(t.mosaicNativeWiden == 0);
    assert(t.mosaicNativeWidenMul == 1.5f && t.mosaicNativeWidenFrames == 3.f);
    assert(t.mosaicTetraWidenMul == 4.f && t.mosaicTetraWidenFrames == 6.f);
    std::remove((std::string(dir) + "/hybrid_tuning.txt").c_str());
    rmdir(dir);

    // both native merges (generic and fast) take rule 2 before the older rules
    const std::string generic = kHybMergeMosaic, fast = kHybMergeMosaicFast;
    for (const std::string* s : {&generic, &fast}) {
        assert(count(*s, "uniform vec2 natWidenS;") == 1);
        assert(count(*s, "if(natWidenU==2)") == 1);
        assert(count(*s, "smoothstep(0.0,natWidenS.y,frames)") == 1);
    }
    std::puts("check_hybrid_native_widen: OK");
    return 0;
}
