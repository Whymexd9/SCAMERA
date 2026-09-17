#include "vivo-neural-model.h"
#include <jni.h>
#include <dlfcn.h>
#include <fstream>
#include <functional>
#include <cstddef>

namespace {
using Error = uint64_t;
using Handle = void*;
using Fn = void (*)();
struct Version { uint32_t major, minor, patch; };
// Public QNN interface prefixes. Only these slots are consumed. See
// docs/vivo-neural.md for the official declarations and firmware validation.
struct SystemProvider {
    uint32_t backend;
    const char* name;
    Version version;
    Error (*create)(Handle*);
    Error (*info)(Handle, void*, uint64_t, const void**, uint64_t*);
    Fn metadata;
    Error (*free)(Handle);
};
struct BackendProvider {
    uint32_t backend;
    const char* name;
    Version core, implementation;
    Fn slots[44];
};
static_assert(offsetof(SystemProvider, create) == 32, "QNN ARM64 system ABI");
static_assert(offsetof(BackendProvider, slots) == 40, "QNN ARM64 core ABI");
using CreateBackend = Error (*)(Handle, const void**, Handle*);
using FreeBackend = Error (*)(Handle);
using CreateDevice = Error (*)(Handle, const void**, Handle*);
using FreeDevice = Error (*)(Handle);
using CreateContext = Error (*)(Handle, Handle, const void**, const void*, uint64_t, Handle*, Handle);
using FreeContext = Error (*)(Handle, Handle);

struct File {
    std::vector<uint8_t> data;
    explicit File(const std::string& path) {
        std::ifstream input(path, std::ios::binary | std::ios::ate);
        if (!input) throw std::runtime_error("Cannot read " + path);
        auto length = input.tellg();
        if (length < 0 || length > 128 * 1024 * 1024)
            throw std::runtime_error("Unexpected library size");
        data.resize(static_cast<size_t>(length));
        input.seekg(0);
        if (!input.read(reinterpret_cast<char*>(data.data()), length))
            throw std::runtime_error("Short library read");
    }
};

std::string version(Version v) {
    return std::to_string(v.major) + "." + std::to_string(v.minor) + "." + std::to_string(v.patch);
}

void probe(const std::string& directory, const std::function<void(const std::string&)>& log) {
    const std::string name = "T2Q_TELE_3x_v1p9_240628_576_576_v79_O3_2251_bin";
    std::vector<uint8_t> model;
    log("EXTRACT: " + name);
    {
        File library("/vendor/lib64/libremosaiclib_s5khp3.so");
        model = vivo_neural::extractModel(library.data.data(), library.data.size(), name);
    } // Release the 55 MB source before initializing QNN.
    if (model.size() != 5720680) throw std::runtime_error("Unexpected model size");
    log("MODEL: " + std::to_string(model.size()) + " bytes; candidate, HP9 4x input mapping UNVERIFIED");

    auto open = [&](const char* name) -> void* {
        log(std::string("LOAD: ") + name);
        if (std::strcmp(name, "libcdsprpc.so") == 0) {
            // Android may expose this through the declared optional native
            // library. That route also resolves its firmware dependencies.
            if (void* handle = dlopen(name, RTLD_NOW | RTLD_LOCAL)) return handle;
            const char* error = dlerror();
            log(std::string("PUBLIC RPC: ") + (error ? error : "unavailable"));
        }
        // These are verified copies of readable firmware files in app-private
        // storage, never namespace manipulation or access to a private HAL.
        void* handle = dlopen((directory + "/" + name).c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!handle) {
            const char* error = dlerror();
            throw std::runtime_error(std::string(name) + ": " + (error ? error : "dlopen failed"));
        }
        // All handles deliberately live until this dedicated process exits.
        return handle;
    };
    void* system = open("libQnnSystem.so");
    auto getSystem = reinterpret_cast<Error (*)(const SystemProvider***, uint32_t*)>(
            dlsym(system, "QnnSystemInterface_getProviders"));
    if (!getSystem) throw std::runtime_error("QNN System provider entry missing");
    const SystemProvider** systems = nullptr;
    uint32_t count = 0;
    Error result = getSystem(&systems, &count);
    if (result || !systems || count == 0 || count > 16)
        throw std::runtime_error("Invalid QNN System providers, status=" + std::to_string(result));
    const SystemProvider* api = nullptr;
    for (uint32_t i = 0; i < count; ++i) {
        auto candidate = systems[i];
        if (candidate && candidate->version.major == 1 && candidate->version.minor == 1)
            api = candidate;
    }
    if (!api || !api->create || !api->info || !api->free)
        throw std::runtime_error("QNN System 1.1 interface unavailable");
    log("SYSTEM API: " + version(api->version));
    Handle metadataContext = nullptr;
    result = api->create(&metadataContext);
    if (result || !metadataContext) throw std::runtime_error("System context create=" + std::to_string(result));
    const void* info = nullptr;
    uint64_t infoSize = 0;
    log("SYSTEM: parsing model metadata");
    result = api->info(metadataContext, model.data(), model.size(), &info, &infoSize);
    uint32_t infoVersion = 0;
    if (!result && info && infoSize >= sizeof(infoVersion)) std::memcpy(&infoVersion, info, sizeof(infoVersion));
    Error freed = api->free(metadataContext);
    log("METADATA: status=" + std::to_string(result) + " version=" + std::to_string(infoVersion) +
        " bytes=" + std::to_string(infoSize) + " free=" + std::to_string(freed));
    if (result || !infoVersion || freed) throw std::runtime_error("Model metadata not accepted");

    open("libcdsprpc.so");
    open("libQnnHtpV79Stub.so");
    void* htp = open("libQnnHtp.so");
    auto getBackend = reinterpret_cast<Error (*)(const BackendProvider***, uint32_t*)>(
            dlsym(htp, "QnnInterface_getProviders"));
    if (!getBackend) throw std::runtime_error("QNN backend entry missing");
    const BackendProvider** providers = nullptr;
    count = 0;
    result = getBackend(&providers, &count);
    if (result || !providers || !count || count > 16)
        throw std::runtime_error("Invalid backend providers, status=" + std::to_string(result));
    const BackendProvider* backendApi = nullptr;
    for (uint32_t i = 0; i < count; ++i) {
        const auto* candidate = providers[i];
        if (!candidate) continue;
        log("BACKEND API: core=" + version(candidate->core) + " implementation=" + version(candidate->implementation));
        if (candidate->core.major == 2 && candidate->core.minor >= 17 && candidate->core.minor <= 25)
            backendApi = candidate;
    }
    if (!backendApi) throw std::runtime_error("Unsupported QNN core API version");
    auto createBackend = reinterpret_cast<CreateBackend>(backendApi->slots[1]);
    auto freeBackend = reinterpret_cast<FreeBackend>(backendApi->slots[8]);
    auto createContext = reinterpret_cast<CreateContext>(backendApi->slots[13]);
    auto freeContext = reinterpret_cast<FreeContext>(backendApi->slots[14]);
    auto createDevice = reinterpret_cast<CreateDevice>(backendApi->slots[40]);
    auto freeDevice = reinterpret_cast<FreeDevice>(backendApi->slots[43]);
    if (!createBackend || !freeBackend || !createContext || !freeContext || !createDevice || !freeDevice)
        throw std::runtime_error("Required QNN function missing");
    Handle backend = nullptr, device = nullptr, context = nullptr;
    log("BACKEND: initializing HTP");
    result = createBackend(nullptr, nullptr, &backend);
    log("BACKEND CREATE: " + std::to_string(result));
    if (result || !backend) throw std::runtime_error("HTP backend initialization failed");
    log("DEVICE: initializing accelerator");
    result = createDevice(nullptr, nullptr, &device);
    log("DEVICE CREATE: " + std::to_string(result));
    if (!result && device) {
        log("CONTEXT: loading embedded TELE 576 candidate onto HTP");
        result = createContext(backend, device, nullptr, model.data(), model.size(), &context, nullptr);
        log("CONTEXT CREATE: " + std::to_string(result) + (context ? " handle=yes" : " handle=no"));
        bool loaded = !result && context;
        if (context) log("CONTEXT FREE: " + std::to_string(freeContext(context, nullptr)));
        log(loaded ? "MODEL LOAD PASSED. No graph execution and no RAW reconstruction performed."
                   : "MODEL LOAD FAILED. Do not enable a neural remosaic backend.");
    }
    if (device) log("DEVICE FREE: " + std::to_string(freeDevice(device)));
    log("BACKEND FREE: " + std::to_string(freeBackend(backend)));
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_ui_settings_VivoNeuralActivity_nativeProbe(
        JNIEnv* env, jobject activity, jstring path) {
    jclass type = env->GetObjectClass(activity);
    jmethodID progress = env->GetMethodID(type, "onNativeProgress", "(Ljava/lang/String;)V");
    if (!progress) return;
    auto log = [&](const std::string& line) {
        jstring text = env->NewStringUTF(line.c_str());
        if (!text) throw std::runtime_error("JNI string allocation failed");
        env->CallVoidMethod(activity, progress, text);
        env->DeleteLocalRef(text);
        if (env->ExceptionCheck()) throw std::runtime_error("Progress callback failed");
    };
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return;
    std::string directory(chars);
    env->ReleaseStringUTFChars(path, chars);
    try { probe(directory, log); }
    catch (const std::exception& error) { if (!env->ExceptionCheck()) log(std::string("STOP: ") + error.what()); }
}
