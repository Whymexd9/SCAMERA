//
// Created by eszdman on 03.06.2025.
//

#include <jni.h>
#include <malloc.h>
#include <string.h>
#include <algorithm>
#include <cmath>
#include <thread>
#include <vector>
#include "android/log.h"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "Allocator", __VA_ARGS__)

#include <atomic>
#include <mutex>
#include <sys/mman.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>
std::atomic<long> memoryCount{0};

// P30: shot arenas. The RAW frames of a Hybrid shot are copied straight into one memfd (the worker's transport) instead of
// malloc buffers that were copied into the memfd again before the merge (~680 MB, ~250 ms). A frame buffer inside an arena is
// a view: free() drops a reference instead of freeing it, the arena is unmapped once released by the shot and unreferenced.
namespace {
struct Arena { int id; int fd; uint8_t* base; size_t size; long refs; bool released; };
std::mutex arenaLock;
std::vector<Arena> arenas;
int nextArenaId = 1;
void unmapLocked(size_t index) {
    munmap(arenas[index].base, arenas[index].size);
    close(arenas[index].fd);
    arenas.erase(arenas.begin() + long(index));
}
long findLocked(int id) {
    for (size_t i = 0; i < arenas.size(); ++i) if (arenas[i].id == id) return long(i);
    return -1;
}
long containingLocked(const void* p) {
    const auto* q = static_cast<const uint8_t*>(p);
    for (size_t i = 0; i < arenas.size(); ++i) if (q >= arenas[i].base && q < arenas[i].base + arenas[i].size) return long(i);
    return -1;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_arenaCreate(JNIEnv*, jclass, jlong bytes) {
    if (bytes <= 0) return 0;
    const int fd = int(syscall(__NR_memfd_create, "scamera-shot", 1 /* MFD_CLOEXEC */));
    if (fd < 0) return 0;
    if (ftruncate(fd, off_t(bytes)) != 0) { close(fd); return 0; }
    void* base = mmap(nullptr, size_t(bytes), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (base == MAP_FAILED) { close(fd); return 0; }
    std::lock_guard<std::mutex> lock(arenaLock);
    const int id = nextArenaId++;
    arenas.push_back({id, fd, static_cast<uint8_t*>(base), size_t(bytes), 0, false});
    return id;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_arenaCopy(JNIEnv* env, jclass, jint id, jlong offset, jobject origin,
                                                              jint originOffset, jint bytes) {
    void* src = origin ? env->GetDirectBufferAddress(origin) : nullptr;
    const jlong srcCapacity = origin ? env->GetDirectBufferCapacity(origin) : 0;
    if (!src || bytes <= 0 || originOffset < 0 || jlong(originOffset) + bytes > srcCapacity) return nullptr;
    uint8_t* dst;
    {
        std::lock_guard<std::mutex> lock(arenaLock);
        const long i = findLocked(id);
        if (i < 0 || arenas[size_t(i)].released || offset < 0 || size_t(offset) + size_t(bytes) > arenas[size_t(i)].size) return nullptr;
        dst = arenas[size_t(i)].base + offset;
        arenas[size_t(i)].refs++;
    }
    memcpy(dst, static_cast<uint8_t*>(src) + originOffset, size_t(bytes));
    jobject view = env->NewDirectByteBuffer(dst, bytes);
    if (!view) {
        std::lock_guard<std::mutex> lock(arenaLock);
        const long i = containingLocked(dst);
        if (i >= 0 && --arenas[size_t(i)].refs == 0 && arenas[size_t(i)].released) unmapLocked(size_t(i));
    }
    return view;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_arenaWrite(JNIEnv* env, jclass, jint id, jlong offset, jobject origin) {
    void* src = origin ? env->GetDirectBufferAddress(origin) : nullptr;
    const jlong bytes = origin ? env->GetDirectBufferCapacity(origin) : 0;
    if (!src || bytes <= 0) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(arenaLock);
    const long i = findLocked(id);
    if (i < 0 || offset < 0 || size_t(offset) + size_t(bytes) > arenas[size_t(i)].size) return JNI_FALSE;
    memcpy(arenas[size_t(i)].base + offset, src, size_t(bytes));
    return JNI_TRUE;
}

// {arena id, byte offset} of a buffer that is an arena view, or null.
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_arenaOf(JNIEnv* env, jclass, jobject buffer) {
    void* p = buffer ? env->GetDirectBufferAddress(buffer) : nullptr;
    if (!p) return nullptr;
    jlong out[2];
    {
        std::lock_guard<std::mutex> lock(arenaLock);
        const long i = containingLocked(p);
        if (i < 0) return nullptr;
        out[0] = arenas[size_t(i)].id;
        out[1] = jlong(static_cast<uint8_t*>(p) - arenas[size_t(i)].base);
    }
    jlongArray result = env->NewLongArray(2);
    if (result) env->SetLongArrayRegion(result, 0, 2, out);
    return result;
}

// A duplicate of the arena's memfd (the caller owns it), -1 when the arena is gone.
extern "C" JNIEXPORT jint JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_arenaFd(JNIEnv*, jclass, jint id) {
    std::lock_guard<std::mutex> lock(arenaLock);
    const long i = findLocked(id);
    return i < 0 ? -1 : fcntl(arenas[size_t(i)].fd, F_DUPFD_CLOEXEC, 3);
}

// The shot hands out no more views; the arena goes once its views are freed.
extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_arenaRelease(JNIEnv*, jclass, jint id) {
    std::lock_guard<std::mutex> lock(arenaLock);
    const long i = findLocked(id);
    if (i < 0) return;
    arenas[size_t(i)].released = true;
    if (arenas[size_t(i)].refs <= 0) unmapLocked(size_t(i));
}

static float mosaicSrKernel(float x, int kernel) {
    x = std::fabs(x);
    if (kernel == 0) return x < 1.0f ? 1.0f - x : 0.0f;
    if (kernel == 1) {
        // Catmull-Rom bicubic: sharp but still well behaved on a CFA plane.
        if (x < 1.0f) return 1.5f*x*x*x - 2.5f*x*x + 1.0f;
        if (x < 2.0f) return -0.5f*x*x*x + 2.5f*x*x - 4.0f*x + 2.0f;
        return 0.0f;
    }
    // Windowed sinc, radius 2. Negative lobes recover more high-frequency
    // detail; the final sample is clamped to the 16-bit DNG domain.
    if (x >= 2.0f) return 0.0f;
    if (x < 1.0e-5f) return 1.0f;
    constexpr float pi = 3.14159265358979323846f;
    return (std::sin(pi*x) / (pi*x)) * (std::sin(pi*x/2.0f) / (pi*x/2.0f));
}

static inline int clampMosaicIndex(int value, int count) {
    return std::max(0, std::min(value, count - 1));
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_reconstructMosaicSr(
        JNIEnv *env, jclass, jobject originBuffer, jint inputWidth, jint inputHeight,
        jint outputWidth, jint outputHeight, jint kernel) {
    auto *input = static_cast<const uint16_t*>(env->GetDirectBufferAddress(originBuffer));
    jlong inputCapacity = env->GetDirectBufferCapacity(originBuffer);
    if (input == nullptr || inputWidth < 2 || inputHeight < 2 || outputWidth < 2 || outputHeight < 2
            || inputCapacity < static_cast<jlong>(inputWidth) * inputHeight * 2) {
        LOGD("Invalid RAW SR mosaic input");
        return nullptr;
    }
    outputWidth &= ~1;
    outputHeight &= ~1;
    size_t pixelCount = static_cast<size_t>(outputWidth) * outputHeight;
    auto *output = static_cast<uint16_t*>(malloc(pixelCount * sizeof(uint16_t)));
    if (output == nullptr) return nullptr;

    const float scaleX = static_cast<float>(outputWidth) / inputWidth;
    const float scaleY = static_cast<float>(outputHeight) / inputHeight;
    const int radius = kernel == 0 ? 1 : 2;
    unsigned workerCount = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
    std::vector<std::thread> workers;
    workers.reserve(workerCount);
    for (unsigned worker = 0; worker < workerCount; ++worker) {
        workers.emplace_back([=]() {
            for (int y = static_cast<int>(worker); y < outputHeight; y += static_cast<int>(workerCount)) {
                int phaseY = y & 1;
                float sourceY = (y + 0.5f) / scaleY - 0.5f;
                float gridY = (sourceY - phaseY) * 0.5f;
                int baseY = static_cast<int>(std::floor(gridY));
                int gridHeight = (inputHeight - phaseY + 1) / 2;
                for (int x = 0; x < outputWidth; ++x) {
                    int phaseX = x & 1;
                    float sourceX = (x + 0.5f) / scaleX - 0.5f;
                    float gridX = (sourceX - phaseX) * 0.5f;
                    int baseX = static_cast<int>(std::floor(gridX));
                    int gridWidth = (inputWidth - phaseX + 1) / 2;
                    float sum = 0.0f;
                    float weightSum = 0.0f;
                    int first = kernel == 0 ? 0 : -1;
                    int last = radius;
                    for (int ky = first; ky <= last; ++ky) {
                        int gy = clampMosaicIndex(baseY + ky, gridHeight);
                        float wy = mosaicSrKernel(gridY - (baseY + ky), kernel);
                        for (int kx = first; kx <= last; ++kx) {
                            int gx = clampMosaicIndex(baseX + kx, gridWidth);
                            float wx = mosaicSrKernel(gridX - (baseX + kx), kernel);
                            float w = wx * wy;
                            int sx = phaseX + 2 * gx;
                            int sy = phaseY + 2 * gy;
                            sum += static_cast<float>(input[static_cast<size_t>(sy) * inputWidth + sx]) * w;
                            weightSum += w;
                        }
                    }
                    float value = std::fabs(weightSum) > 1.0e-8f ? sum / weightSum : 0.0f;
                    output[static_cast<size_t>(y) * outputWidth + x] = static_cast<uint16_t>(
                            std::lround(std::max(0.0f, std::min(65535.0f, value))));
                }
            }
        });
    }
    for (auto &worker : workers) worker.join();
    size_t byteCount = pixelCount * sizeof(uint16_t);
    jobject result = env->NewDirectByteBuffer(output, static_cast<jlong>(byteCount));
    if (result == nullptr) {
        free(output);
        return nullptr;
    }
    memoryCount += static_cast<long>(byteCount);
    LOGD("RAW SR mosaic reconstructed: %dx%d -> %dx%d, kernel=%d",
         inputWidth, inputHeight, outputWidth, outputHeight, kernel);
    return result;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_allocate(JNIEnv *env, jclass clazz,
                                                            jint capacity) {
    // Allocate a direct ByteBuffer of the specified size
    jobject buffer = env->NewDirectByteBuffer(malloc(capacity), capacity);
    if (buffer == nullptr) {
        // Handle allocation failure
        LOGD("Failed to allocate buffer of size %d", capacity);
        return nullptr;
    }
    memoryCount += capacity;
    return buffer;
}

extern "C"
#pragma clang diagnostic push
#pragma ide diagnostic ignored "MemoryLeak"
JNIEXPORT jobject JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_allocateAndCopy(JNIEnv *env, jclass clazz,
                                                            jint capacity, jobject originBuffer, jint offset) {
    // Allocate a direct ByteBuffer of the specified size
    void* allocation = malloc(capacity);
    jobject buffer = env->NewDirectByteBuffer(allocation, capacity);
    if (buffer == nullptr) {
        // Handle allocation failure
        LOGD("Failed to allocate buffer of size %ld", capacity);
        if (allocation != nullptr) {
            free(allocation);
        }
        return nullptr;
    }
    void* ptr = env->GetDirectBufferAddress(originBuffer);
    if (ptr == nullptr) {
        LOGD("Failed to get direct buffer address of originBuffer, disabling copying");
        return buffer;
    } else {
        // Copy the contents of the original buffer to the new buffer
        memcpy(allocation, reinterpret_cast<uint8_t*>(ptr) + offset, capacity);
        LOGD("Buffer allocated and copied successfully");
    }
    memoryCount += capacity;
    LOGD("Current memory count: %ld MB", (memoryCount/1024)/1024);
    return buffer;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_allocateAndCopyConvert(JNIEnv *env, jclass clazz,
                                                                          jint capacity, jobject originBuffer,
                                                                          jint width, jint row_stride, jint offset) {
    // Calculate output buffer size (width * height * 2 bytes per pixel)
    int height = capacity / row_stride;
    int output_size = width * height * sizeof(uint16_t);

    // Allocate output buffer
    auto* allocation = static_cast<uint16_t *>(malloc(output_size));
    jobject buffer = env->NewDirectByteBuffer(allocation, output_size);

    if (buffer == nullptr) {
        LOGD("Failed to allocate buffer of size %d", output_size);
        free(allocation);
        return nullptr;
    }

    void* ptr = env->GetDirectBufferAddress(originBuffer);
    if (ptr == nullptr) {
        LOGD("Failed to get direct buffer address of originBuffer");
        free(allocation);
        return nullptr;
    }

    uint8_t* input = static_cast<uint8_t*>(ptr) + offset;
    uint16_t* output = allocation;

    // Calculate bytes per row of actual image data (without padding)
    int bytes_per_row = (width * 10) / 8;

    // Process each row
    for (int row = 0; row < height; row++) {
        uint8_t* row_start = input + (row * row_stride);

        // Process each group of 4 pixels (5 bytes) in the row
        for (int col = 0; col < bytes_per_row; col += 5) {
            // Ensure we don't read beyond the row
            if (col + 4 >= bytes_per_row) break;

            uint8_t b0 = row_start[col];
            uint8_t b1 = row_start[col + 1];
            uint8_t b2 = row_start[col + 2];
            uint8_t b3 = row_start[col + 3];
            uint8_t b4 = row_start[col + 4];

            // Convert and store as 16-bit values
            *output++ = (b0 << 2) | (b4 & 0x03);        // Pixel 0
            *output++ = (b1 << 2) | ((b4 >> 2) & 0x03); // Pixel 1
            *output++ = (b2 << 2) | ((b4 >> 4) & 0x03); // Pixel 2
            *output++ = (b3 << 2) | (b4 >> 6);          // Pixel 3
        }
    }

    LOGD("Buffer allocated and converted successfully with padding handling");
    memoryCount += output_size;
    LOGD("Current memory count: %ld MB", (memoryCount / 1024) / 1024);
    return buffer;
}

// Helper: decode one RAW10 row into dst (width uint16_t values)
static void decodeRaw10Row(const uint8_t* row_start, uint16_t* dst, int width) {
    int bytes_per_row = (width * 10) / 8;
    for (int col = 0, px = 0; col + 4 < bytes_per_row && px + 3 < width; col += 5, px += 4) {
        uint8_t b0 = row_start[col];
        uint8_t b1 = row_start[col + 1];
        uint8_t b2 = row_start[col + 2];
        uint8_t b3 = row_start[col + 3];
        uint8_t b4 = row_start[col + 4];
        dst[px]     = (uint16_t)((b0 << 2) | (b4 & 0x03));
        dst[px + 1] = (uint16_t)((b1 << 2) | ((b4 >> 2) & 0x03));
        dst[px + 2] = (uint16_t)((b2 << 2) | ((b4 >> 4) & 0x03));
        dst[px + 3] = (uint16_t)((b3 << 2) | (b4 >> 6));
    }
}

// Helper: Bayer-aware 2x2 binning matching dngCreator::applyBayerBinning.
// Each output pixel combines 4 same-colour pixels from a 4x4 input block.
// input is width*height uint16_t (packed, no stride padding).
static void applyBayerBinning(const uint16_t* input, uint16_t* output,
                               int srcWidth, int srcHeight,
                               int outWidth, int outHeight) {
    for (int oy = 0; oy < outHeight; oy++) {
        int blockStartRow = (oy / 2) * 4;
        int dr    = oy % 2;
        int inRow  = blockStartRow + dr;
        int inRow2 = (inRow + 2 < srcHeight) ? inRow + 2 : srcHeight - 1;

        for (int ox = 0; ox < outWidth; ox++) {
            int blockStartCol = (ox / 2) * 4;
            int dc    = ox % 2;
            int inCol  = blockStartCol + dc;
            int inCol2 = (inCol + 2 < srcWidth) ? inCol + 2 : srcWidth - 1;

            uint32_t sum =
                (uint32_t)input[inRow  * srcWidth + inCol ] +
                (uint32_t)input[inRow  * srcWidth + inCol2] +
                (uint32_t)input[inRow2 * srcWidth + inCol ] +
                (uint32_t)input[inRow2 * srcWidth + inCol2];

            output[oy * outWidth + ox] = (uint16_t)(sum > 65535u ? 65535u : sum);
        }
    }
}

// Converts RAW10 to uint16, then applies Bayer-aware 2x2 sum binning.
// Output size: (width/2) * (height/2) * sizeof(uint16_t)
extern "C"
JNIEXPORT jobject JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_allocateAndCopyConvertBinning(JNIEnv *env, jclass clazz,
                                                                                  jint capacity, jobject originBuffer,
                                                                                  jint width, jint row_stride, jint offset) {
    int height = capacity / row_stride;
    int out_width  = width  / 2;
    int out_height = height / 2;
    int output_size = out_width * out_height * (int)sizeof(uint16_t);

    auto* allocation = static_cast<uint16_t*>(malloc(output_size));
    jobject buffer = env->NewDirectByteBuffer(allocation, output_size);
    if (buffer == nullptr) {
        LOGD("allocateAndCopyConvertBinning: failed to allocate output");
        free(allocation);
        return nullptr;
    }

    void* ptr = env->GetDirectBufferAddress(originBuffer);
    if (ptr == nullptr) {
        LOGD("allocateAndCopyConvertBinning: failed to get buffer address");
        free(allocation);
        return nullptr;
    }

    // Decode entire RAW10 image into a packed uint16 buffer (no row padding)
    int full_size = width * height * (int)sizeof(uint16_t);
    auto* decoded = static_cast<uint16_t*>(malloc(full_size));
    if (decoded == nullptr) {
        LOGD("allocateAndCopyConvertBinning: failed to allocate decode buffer");
        free(allocation);
        return nullptr;
    }
    uint8_t* input = static_cast<uint8_t*>(ptr) + offset;
    for (int row = 0; row < height; row++) {
        decodeRaw10Row(input + row * row_stride, decoded + row * width, width);
    }

    applyBayerBinning(decoded, allocation, width, height, out_width, out_height);
    free(decoded);

    memoryCount += output_size;
    LOGD("allocateAndCopyConvertBinning: %dx%d -> %dx%d, memory %ld MB",
         width, height, out_width, out_height, (memoryCount / 1024) / 1024);
    return buffer;
}

// Helper: decode one RAW12 row (MIPI packed, 3 bytes -> 2 pixels) into dst (width uint16_t values)
static void decodeRaw12Row(const uint8_t* row_start, uint16_t* dst, int width) {
    int bytes_per_row = (width * 12) / 8;
    for (int col = 0, px = 0; col + 2 < bytes_per_row && px + 1 < width; col += 3, px += 2) {
        uint8_t b0 = row_start[col];
        uint8_t b1 = row_start[col + 1];
        uint8_t b2 = row_start[col + 2];
        dst[px]     = (uint16_t)((b0 << 4) | (b2 & 0x0F));
        dst[px + 1] = (uint16_t)((b1 << 4) | (b2 >> 4));
    }
}

// Converts RAW12 (packed) to uint16 (width*height*2, no row padding).
extern "C"
JNIEXPORT jobject JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_allocateAndCopyConvert12(JNIEnv *env, jclass clazz,
                                                                            jint capacity, jobject originBuffer,
                                                                            jint width, jint row_stride, jint offset) {
    int height = capacity / row_stride;
    int output_size = width * height * (int)sizeof(uint16_t);
    auto* allocation = static_cast<uint16_t*>(malloc(output_size));
    jobject buffer = env->NewDirectByteBuffer(allocation, output_size);
    if (buffer == nullptr) { LOGD("allocateAndCopyConvert12: failed to allocate output"); free(allocation); return nullptr; }
    void* ptr = env->GetDirectBufferAddress(originBuffer);
    if (ptr == nullptr) { LOGD("allocateAndCopyConvert12: failed to get buffer address"); free(allocation); return nullptr; }
    uint8_t* input = static_cast<uint8_t*>(ptr) + offset;
    for (int row = 0; row < height; row++) decodeRaw12Row(input + row * row_stride, allocation + (size_t)row * width, width);
    memoryCount += output_size;
    LOGD("allocateAndCopyConvert12: %dx%d, memory %ld MB", width, height, (memoryCount / 1024) / 1024);
    return buffer;
}

// Converts RAW12 to uint16, then applies Bayer-aware 2x2 sum binning.
extern "C"
JNIEXPORT jobject JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_allocateAndCopyConvert12Binning(JNIEnv *env, jclass clazz,
                                                                                   jint capacity, jobject originBuffer,
                                                                                   jint width, jint row_stride, jint offset) {
    int height = capacity / row_stride;
    int out_width = width / 2, out_height = height / 2;
    int output_size = out_width * out_height * (int)sizeof(uint16_t);
    auto* allocation = static_cast<uint16_t*>(malloc(output_size));
    jobject buffer = env->NewDirectByteBuffer(allocation, output_size);
    if (buffer == nullptr) { LOGD("allocateAndCopyConvert12Binning: failed to allocate output"); free(allocation); return nullptr; }
    void* ptr = env->GetDirectBufferAddress(originBuffer);
    if (ptr == nullptr) { LOGD("allocateAndCopyConvert12Binning: failed to get buffer address"); free(allocation); return nullptr; }
    auto* decoded = static_cast<uint16_t*>(malloc((size_t)width * height * sizeof(uint16_t)));
    if (decoded == nullptr) { free(allocation); return nullptr; }
    uint8_t* input = static_cast<uint8_t*>(ptr) + offset;
    for (int row = 0; row < height; row++) decodeRaw12Row(input + row * row_stride, decoded + (size_t)row * width, width);
    applyBayerBinning(decoded, allocation, width, height, out_width, out_height);
    free(decoded);
    memoryCount += output_size;
    return buffer;
}

// Applies Bayer-aware 2x2 sum binning on a RAW16 (uint16_t) buffer.
// row_stride is in bytes; output is (width/2)*(height/2)*sizeof(uint16_t)
extern "C"
JNIEXPORT jobject JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_allocateAndCopyBinning(JNIEnv *env, jclass clazz,
                                                                           jint capacity, jobject originBuffer,
                                                                           jint width, jint height, jint row_stride) {
    int out_width  = width  / 2;
    int out_height = height / 2;
    int output_size = out_width * out_height * (int)sizeof(uint16_t);

    auto* allocation = static_cast<uint16_t*>(malloc(output_size));
    jobject buffer = env->NewDirectByteBuffer(allocation, output_size);
    if (buffer == nullptr) {
        LOGD("allocateAndCopyBinning: failed to allocate output");
        free(allocation);
        return nullptr;
    }

    void* ptr = env->GetDirectBufferAddress(originBuffer);
    if (ptr == nullptr) {
        LOGD("allocateAndCopyBinning: failed to get buffer address");
        free(allocation);
        return nullptr;
    }

    // If row_stride matches width (no padding), bin directly
    int stride_pixels = row_stride / (int)sizeof(uint16_t);
    if (stride_pixels == width) {
        applyBayerBinning(static_cast<const uint16_t*>(ptr), allocation,
                          width, height, out_width, out_height);
    } else {
        // De-stride into a packed buffer first
        int full_size = width * height * (int)sizeof(uint16_t);
        auto* packed = static_cast<uint16_t*>(malloc(full_size));
        if (packed == nullptr) {
            LOGD("allocateAndCopyBinning: failed to allocate pack buffer");
            free(allocation);
            return nullptr;
        }
        const uint8_t* src = static_cast<const uint8_t*>(ptr);
        for (int row = 0; row < height; row++) {
            memcpy(packed + row * width, src + row * row_stride, width * sizeof(uint16_t));
        }
        applyBayerBinning(packed, allocation, width, height, out_width, out_height);
        free(packed);
    }

    memoryCount += output_size;
    LOGD("allocateAndCopyBinning: %dx%d -> %dx%d, memory %ld MB",
         width, height, out_width, out_height, (memoryCount / 1024) / 1024);
    return buffer;
}

#pragma clang diagnostic pop

extern "C"
JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_free(JNIEnv *env, jclass clazz,
                                                            jobject buffer) {
    if (buffer == nullptr) {
        LOGD("Buffer is null, nothing to free");
        return;
    }

    // Get the address of the allocated memory
    void* ptr = env->GetDirectBufferAddress(buffer);
    long capacity = env->GetDirectBufferCapacity(buffer);
    if (ptr == nullptr) {
        LOGD("Failed to get direct buffer address");
        return;
    }

    {   // an arena view: one reference less (P30)
        std::lock_guard<std::mutex> lock(arenaLock);
        const long i = containingLocked(ptr);
        if (i >= 0) {
            if (--arenas[size_t(i)].refs <= 0 && arenas[size_t(i)].released) unmapLocked(size_t(i));
            return;
        }
    }
    // Free the allocated memory
    free(ptr);
    memoryCount -= capacity;
    LOGD("Buffer freed successfully");
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_getMemoryCount(JNIEnv *env, jclass clazz) {
    // Return the current memory count
    LOGD("Current memory count: %ld MB", (memoryCount/1024)/1024);
    return memoryCount;
}
