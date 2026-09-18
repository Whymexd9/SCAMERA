#pragma once
#include "vivo-neural-runtime.h"

// Diagnostic-only loader for the Vivo softpqe still-photo enhancement models
// (Y quality/2x/4x super-resolution). See docs/vivo-softpqe-upscale.md.
//
// This does NOT execute the graph. These tensors declare a quantization
// encoding (definition=1, encoding=4) that does not match a plain per-tensor
// scale/offset pair; decoding it needs the real QNN SDK headers or ARM64
// emulation of the vendor's own parser, neither available here. Guessing a
// client-buffer byte width or dequantization formula would mean feeding an
// unverified buffer layout into proprietary code, which this project does
// not do. This loader proves context/graph creation and reports exactly the
// tensor descriptors QNN itself resolves, nothing more.
namespace vivo_softpqe {
using vivo_nn::Error; using vivo_nn::Handle; using vivo_nn::Fn;
using vivo_nn::Provider; using vivo_nn::SystemProvider; using vivo_nn::Tensor; using vivo_nn::TensorV1;
using vivo_nn::GraphPrefix; using vivo_nn::BinaryV1Prefix; using vivo_nn::BinaryV3Prefix;
using vivo_nn::check; using vivo_nn::log; using vivo_nn::read;

struct ModelSpec {
    const char* file;
    const char* graph;
    size_t modelBytes;
    uint32_t inH, inW, inC;
    uint32_t outH, outW, outC;
};

// Real, verified against the supplied .vdnn containers (see the doc's table).
// Not `inline` (C++17): this header is included from a single translation
// unit, so plain namespace-scope const is safe and keeps the C++14 build.
const ModelSpec Y2X{"softpqe-y2x-v79.bin", "keta_sr2x_qat_20240314_quant_8w8a32b",
                     1326376, 560, 560, 4, 1120, 1120, 1};
const ModelSpec Y4X{"softpqe-y4x-v79.bin", "keta_sr4x_qat_20240310_quant_8w8a32b",
                     3276224, 560, 560, 4, 2240, 2240, 1};

class Session {
    std::vector<void*> libraries;
    const Provider* api = nullptr;
    const SystemProvider* sys = nullptr;
    Handle backend = nullptr, device = nullptr, context = nullptr, metadata = nullptr, graph = nullptr;
    std::vector<uint8_t> model;
    template<class T> T fn(int i) { if (!api->slots[i]) throw std::runtime_error("Missing QNN function"); return reinterpret_cast<T>(api->slots[i]); }
    void* load(const char* p) {
        log(std::string("LOAD: ") + p);
        void* h = dlopen(p, RTLD_NOW | RTLD_LOCAL);
        if (!h) { const char* error = dlerror(); throw std::runtime_error(error ? error : "dlopen failed"); }
        libraries.push_back(h);
        return h;
    }
    static void describeTensor(const char* label, Tensor* t, uint32_t expectedType,
                                uint32_t h, uint32_t w, uint32_t c) {
        if (!t || t->version != 1) throw std::runtime_error("Expected firmware tensor version 1");
        const auto& v = t->v1;
        if (v.format != 0 || v.rank != 4 || !v.dimensions || !v.name)
            throw std::runtime_error("Unsupported tensor descriptor");
        std::ostringstream line;
        line << "TENSOR " << label << ": " << v.name << " id=" << v.id
             << " type=" << v.type << (v.type == expectedType ? "" : " (unexpected!)")
             << " dataType=0x" << std::hex << v.dataType << std::dec
             << " shape=[" << v.dimensions[0] << "," << v.dimensions[1] << ","
             << v.dimensions[2] << "," << v.dimensions[3] << "]";
        if (v.dimensions[0] != 1 || v.dimensions[1] != h || v.dimensions[2] != w || v.dimensions[3] != c)
            line << " (does not match the shape recovered offline!)";
        log(line.str());
        std::ostringstream q;
        q << "  quant: definition=" << v.quant.definition << " encoding=" << v.quant.encoding << " payload=";
        for (uint8_t byte : v.quant.payload) { char buf[3]; snprintf(buf, sizeof(buf), "%02x", byte); q << buf; }
        log(q.str());
    }
public:
    void init(const std::string& directory, const ModelSpec& spec) {
        model = read(directory + "/" + spec.file);
        if (model.size() != spec.modelBytes) throw std::runtime_error("Unexpected model length");
        auto system = load((directory + "/libQnnSystem.so").c_str());
        auto getSystem = reinterpret_cast<Error(*)(const SystemProvider***, uint32_t*)>(dlsym(system, "QnnSystemInterface_getProviders"));
        if (!getSystem) throw std::runtime_error("No System provider entry");
        const SystemProvider** systems = nullptr; uint32_t n = 0;
        check(getSystem(&systems, &n), "System providers");
        if (!systems || !n || n > 16) throw std::runtime_error("System provider count");
        for (uint32_t i = 0; i < n; i++) {
            if (!systems[i]) continue;
            log("SYSTEM PROVIDER: " + std::string(systems[i]->name ? systems[i]->name : "?") +
                " v" + std::to_string(systems[i]->version.major) + "." + std::to_string(systems[i]->version.minor));
            if (systems[i]->version.major == 1 && systems[i]->version.minor == 1) sys = systems[i];
        }
        if (!sys || !sys->create || !sys->info || !sys->free) throw std::runtime_error("System 1.1 required");
        Handle created = nullptr; check(sys->create(&created), "System create"); metadata = created;
        const void* info = nullptr; uint64_t bytes = 0;
        check(sys->info(metadata, model.data(), model.size(), &info, &bytes), "System metadata");
        if (!info || (bytes && bytes < 8)) throw std::runtime_error("Empty binary metadata");
        uint32_t version; std::memcpy(&version, info, 4);
        log("BINARY INFO: v" + std::to_string(version));
        GraphPrefix* g = nullptr; uint32_t graphs = 0;
        auto body = static_cast<const uint8_t*>(info) + 8;
        if ((version == 1 || version == 2) && (!bytes || bytes >= 8 + sizeof(BinaryV1Prefix))) {
            auto b = reinterpret_cast<const BinaryV1Prefix*>(body); g = b->graph; graphs = b->graphs;
        } else if (version == 3 && (!bytes || bytes >= 8 + sizeof(BinaryV3Prefix))) {
            auto b = reinterpret_cast<const BinaryV3Prefix*>(body); g = b->graph; graphs = b->graphs;
        } else throw std::runtime_error("Unsupported binary metadata version/size");
        if (graphs != 1 || !g || g->version < 1 || g->version > 3 || g->inputs != 1 || g->outputs != 1 || !g->name)
            throw std::runtime_error("Unexpected graph metadata");
        if (std::strcmp(g->name, spec.graph)) throw std::runtime_error("Wrong graph name");
        describeTensor("input", g->input, 0, spec.inH, spec.inW, spec.inC);
        describeTensor("output", g->output, 1, spec.outH, spec.outW, spec.outC);
        log("DRIVER: platform FastRPC (device compatibility required)");
        load("libcdsprpc.so");
        load((directory + "/libQnnHtpV79Stub.so").c_str());
        auto htp = load((directory + "/libQnnHtp.so").c_str());
        auto get = reinterpret_cast<Error(*)(const Provider***, uint32_t*)>(dlsym(htp, "QnnInterface_getProviders"));
        if (!get) throw std::runtime_error("No HTP providers");
        const Provider** providers = nullptr; n = 0;
        check(get(&providers, &n), "HTP providers");
        if (!providers || !n || n > 16) throw std::runtime_error("HTP provider count");
        // The correct HTP core version for these graphs is not yet known (unlike
        // TELE576's confirmed 2.18.0 or HexQuad's confirmed 2.28/2.29.8): log
        // every candidate and accept any major==2 provider so a phone report
        // establishes it, instead of guessing a minor/patch in advance.
        for (uint32_t i = 0; i < n; i++) {
            if (!providers[i]) continue;
            log("HTP PROVIDER: id=" + std::to_string(providers[i]->id) + " core=" +
                std::to_string(providers[i]->core.major) + "." + std::to_string(providers[i]->core.minor) +
                "." + std::to_string(providers[i]->core.patch) + " backend=" +
                std::to_string(providers[i]->backend.major) + "." + std::to_string(providers[i]->backend.minor) +
                "." + std::to_string(providers[i]->backend.patch));
            if (providers[i]->core.major == 2) api = providers[i];
        }
        if (!api) throw std::runtime_error("No QNN Core 2.x HTP provider found");
        for (int slot : {1, 4, 8, 13, 14, 20, 40, 43})
            if (!api->slots[slot]) throw std::runtime_error("Missing required QNN function");
        const char* build = nullptr; check(fn<Error(*)(const char**)>(4)(&build), "Build ID");
        log(std::string("SDK: ") + (build ? build : "unknown"));
        created = nullptr; check(fn<Error(*)(Handle, const void**, Handle*)>(1)(nullptr, nullptr, &created), "Backend create"); backend = created;
        created = nullptr; check(fn<Error(*)(Handle, const void**, Handle*)>(40)(nullptr, nullptr, &created), "Device create"); device = created;
        created = nullptr;
        check(fn<Error(*)(Handle, Handle, const void**, const void*, uint64_t, Handle*, Handle)>(13)
              (backend, device, nullptr, model.data(), model.size(), &created, nullptr), "Context create");
        context = created;
        check(fn<Error(*)(Handle, const char*, Handle*)>(20)(context, g->name, &graph), "Graph retrieve");
        if (!backend || !device || !context || !graph) throw std::runtime_error("QNN returned an empty handle");
        log("SOFTPQE CONTEXT+GRAPH CREATED (diagnostic only; graph not executed)");
    }
    ~Session() {
        // The process exits after one job; do not dlclose live vendor runtime code.
        if (api) {
            if (context && api->slots[14]) reinterpret_cast<Error(*)(Handle, Handle)>(api->slots[14])(context, nullptr);
            if (device && api->slots[43]) reinterpret_cast<Error(*)(Handle)>(api->slots[43])(device);
            if (backend && api->slots[8]) reinterpret_cast<Error(*)(Handle)>(api->slots[8])(backend);
        }
        if (sys && metadata) sys->free(metadata);
    }
};
} // namespace vivo_softpqe
