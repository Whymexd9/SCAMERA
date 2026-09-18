#pragma once
#include "vivo-neural-runtime.h"

// Loader + mechanics probe for the Vivo softpqe still-photo enhancement
// models (Y quality/2x/4x super-resolution). See docs/vivo-softpqe-upscale.md.
//
// Their tensors declare dataType 0x408 = QNN_DATATYPE_UFIXED_POINT_8 (per
// Qualcomm's public QNN_QnnTypes_h reference) and a quantization encoding
// definition=1 (QNN_DEFINITION_DEFINED), encoding=4 (QNN_QUANTIZATION_
// ENCODING_BLOCK). Both facts are from Qualcomm's own published headers, not
// guessed. Client buffers are therefore raw uint8_t, one byte per element -
// this is now certain, not a guess, since dataType alone fixes the wire
// width regardless of the quantization scheme layered on top.
//
// What is still NOT established: the actual scale/offset/block values. Those
// live inside Qnn_QuantizeParams_t's block-encoding union as heap pointers
// that only QnnSystemContext_getBinaryInfo() resolves at runtime; a static
// read of the container's on-disk bytes cannot recover them (confirmed: the
// on-disk bytes at that position are not literal little-endian floats, and a
// live struct's pointer fields are meaningless outside a live process). So
// this loader now goes one step further than a load-only probe: it executes
// the graph once with a neutral, flat placeholder input (0x80 everywhere)
// purely to prove the transport survives a real graphExecute() call, and it
// dereferences and logs the REAL, QNN-resolved quantizeParams union so a
// phone report finally reveals the actual scale/offset/block data. Output
// from the placeholder input is NOT meaningful image content - the input
// quantization needed to turn a real photograph into correct input bytes is
// exactly what this run is meant to discover.
namespace vivo_softpqe {
using vivo_nn::Error; using vivo_nn::Handle; using vivo_nn::Fn;
using vivo_nn::Provider; using vivo_nn::SystemProvider; using vivo_nn::Tensor; using vivo_nn::TensorV1;
using vivo_nn::GraphPrefix; using vivo_nn::BinaryV1Prefix; using vivo_nn::BinaryV3Prefix;
using vivo_nn::ClientBuffer;
using vivo_nn::check; using vivo_nn::log; using vivo_nn::read;

// Qnn_QuantizeParams_t's union members, from Qualcomm's public QNN_QnnTypes.h
// (docs.qualcomm.com). Reinterpreted over the same 32-byte payload the
// already-working TELE576 session already reserves (vivo-neural-runtime.h's
// Quant struct); TELE never inspects it, so this is additive, not a change
// to proven code.
struct ScaleOffset { float scale; int32_t offset; };
struct AxisScaleOffset { int32_t axis; uint32_t numScaleOffsets; const ScaleOffset* scaleOffset; };
struct BwScaleOffset { uint32_t bitwidth; float scale; int32_t offset; };
struct BwAxisScaleOffset { uint32_t bitwidth; int32_t axis; uint32_t numElements; const float* scales; const int32_t* offsets; };
struct BlockEncoding { const uint32_t* blockSize; const ScaleOffset* scaleOffset; };
static_assert(sizeof(BwAxisScaleOffset) <= 32, "must fit the reserved quant payload");
static_assert(sizeof(BlockEncoding) <= 32, "must fit the reserved quant payload");

struct ModelSpec {
    const char* file;
    const char* graph;
    size_t modelBytes;
    uint32_t inH, inW, inC;
    uint32_t outH, outW, outC;
    // dataType 0x408 = QNN_DATATYPE_UFIXED_POINT_8 for every softpqe model
    // supplied; bytesPerElement is spelled out rather than hardcoded inline
    // so a future model with a different dataType fails loudly, not silently.
    uint32_t dataType;
    size_t bytesPerElement;
};

// Real, verified against the supplied .vdnn containers (see the doc's table).
// Not `inline` (C++17): this header is included from a single translation
// unit, so plain namespace-scope const is safe and keeps the C++14 build.
const ModelSpec Y2X{"softpqe-y2x-v79.bin", "keta_sr2x_qat_20240314_quant_8w8a32b",
                     1326376, 560, 560, 4, 1120, 1120, 1, 0x408, 1};
const ModelSpec Y4X{"softpqe-y4x-v79.bin", "keta_sr4x_qat_20240310_quant_8w8a32b",
                     3276224, 560, 560, 4, 2240, 2240, 1, 0x408, 1};

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
    // Only ever called with quantizeParams QNN itself resolved via a live
    // sys->info() call: the union's pointer fields are then real heap
    // addresses. Never call this against bytes read directly from a file -
    // those pointer-shaped bytes are not real pointers and will crash.
    static void describeQuant(const vivo_nn::Quant& quant) {
        std::ostringstream q;
        q << "  quant: definition=" << quant.definition << " encoding=" << quant.encoding;
        switch (quant.encoding) {
            case 0: { // QNN_QUANTIZATION_ENCODING_SCALE_OFFSET
                auto* so = reinterpret_cast<const ScaleOffset*>(quant.payload);
                q << " scale=" << so->scale << " offset=" << so->offset;
                break;
            }
            case 1: { // QNN_QUANTIZATION_ENCODING_AXIS_SCALE_OFFSET
                auto* a = reinterpret_cast<const AxisScaleOffset*>(quant.payload);
                q << " axis=" << a->axis << " count=" << a->numScaleOffsets;
                if (a->scaleOffset && a->numScaleOffsets && a->numScaleOffsets < 4096) {
                    q << " [";
                    for (uint32_t i = 0; i < std::min<uint32_t>(a->numScaleOffsets, 8); i++)
                        q << a->scaleOffset[i].scale << "/" << a->scaleOffset[i].offset << " ";
                    q << (a->numScaleOffsets > 8 ? "...]" : "]");
                }
                break;
            }
            case 2: { // QNN_QUANTIZATION_ENCODING_BW_SCALE_OFFSET
                auto* b = reinterpret_cast<const BwScaleOffset*>(quant.payload);
                q << " bitwidth=" << b->bitwidth << " scale=" << b->scale << " offset=" << b->offset;
                break;
            }
            case 3: { // QNN_QUANTIZATION_ENCODING_BW_AXIS_SCALE_OFFSET
                auto* b = reinterpret_cast<const BwAxisScaleOffset*>(quant.payload);
                q << " bitwidth=" << b->bitwidth << " axis=" << b->axis << " count=" << b->numElements;
                if (b->scales && b->offsets && b->numElements && b->numElements < 4096) {
                    q << " [";
                    for (uint32_t i = 0; i < std::min<uint32_t>(b->numElements, 8); i++)
                        q << b->scales[i] << "/" << b->offsets[i] << " ";
                    q << (b->numElements > 8 ? "...]" : "]");
                }
                break;
            }
            case 4: { // QNN_QUANTIZATION_ENCODING_BLOCK
                auto* b = reinterpret_cast<const BlockEncoding*>(quant.payload);
                q << " blockSize_ptr=" << static_cast<const void*>(b->blockSize)
                  << " scaleOffset_ptr=" << static_cast<const void*>(b->scaleOffset);
                if (b->blockSize) {
                    // Per-dimension block size is the documented shape; rank is
                    // already validated to be 4 by the caller.
                    q << " blockSize=[" << b->blockSize[0] << "," << b->blockSize[1] << ","
                      << b->blockSize[2] << "," << b->blockSize[3] << "]";
                }
                break;
            }
            default:
                q << " (encoding not in the published QNN_QuantizationEncoding_t range 0-4)";
        }
        log(q.str());
    }
    static Tensor describeTensor(const char* label, Tensor* t, uint32_t expectedType,
                                  uint32_t h, uint32_t w, uint32_t c, uint32_t dataType) {
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
        if (v.dataType != dataType) line << " (dataType does not match the value recovered offline!)";
        log(line.str());
        describeQuant(v.quant);
        return Tensor{1, v};
    }
    Tensor in{}, out{};
public:
    std::vector<uint8_t> input, output;
    void init(const std::string& directory, const ModelSpec& spec) {
        input.assign(static_cast<size_t>(spec.inH) * spec.inW * spec.inC * spec.bytesPerElement, 0x80);
        output.assign(static_cast<size_t>(spec.outH) * spec.outW * spec.outC * spec.bytesPerElement, 0);
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
        in = describeTensor("input", g->input, 0, spec.inH, spec.inW, spec.inC, spec.dataType);
        out = describeTensor("output", g->output, 1, spec.outH, spec.outW, spec.outC, spec.dataType);
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
        for (int slot : {1, 4, 8, 13, 14, 20, 21, 40, 43})
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
        log("SOFTPQE CONTEXT+GRAPH CREATED");
        // Neutral placeholder input (0x80 = mid-scale for an 8-bit unsigned
        // fixed-point tensor, whatever its real quantization turns out to
        // be): this proves graphExecute() itself works end to end - buffer
        // sizes, memType, HTP dispatch - without claiming the OUTPUT is a
        // meaningful image. Real input quantization is exactly what the
        // logged quant struct above is for; it is not applied here yet.
        in.v1.memType = 0; in.v1.client = ClientBuffer{input.data(), static_cast<uint32_t>(input.size())};
        out.v1.memType = 0; out.v1.client = ClientBuffer{output.data(), static_cast<uint32_t>(output.size())};
        log("EXECUTE: placeholder input (0x80 flat), mechanics-only, NOT a real photograph");
        check(fn<Error(*)(Handle, const Tensor*, uint32_t, Tensor*, uint32_t, Handle, Handle)>(21)
              (graph, &in, 1, &out, 1, nullptr, nullptr), "Graph execute");
        uint64_t sum = 0; uint8_t lo = 0xff, hi = 0;
        for (uint8_t byte : output) { sum += byte; lo = std::min(lo, byte); hi = std::max(hi, byte); }
        log("EXECUTE OK: output bytes=" + std::to_string(output.size()) + " min=" + std::to_string(lo) +
            " max=" + std::to_string(hi) + " mean=" + std::to_string(output.empty() ? 0.0 : double(sum) / output.size()));
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
